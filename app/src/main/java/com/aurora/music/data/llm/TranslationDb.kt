package com.aurora.music.data.llm

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * 翻译切块缓存：单独一个 SQLite 文件 `translation_cache.db`。
 *
 * 一行 = 一个「已经翻译完成的块」：端点毫秒 + 译文 + 生成它的模型/目标语言。
 * 端点一旦写进来就是永久事实：之后的切块规划遇到它必须到此为止，不能再跨过去，
 * 所以同一段音频第二次播放时不会变成另一个区间。
 *
 * 索引 `(bookId, chapterKey, startMs)` 同时承担两件事：
 *  - 按章顺序取出整章计划（WHERE + ORDER BY 都走索引）；
 *  - 按时间找「包含当前位置的块 / 下一个已知端点」（有序范围扫描）。
 */
@Entity(
    tableName = "translation_chunks",
    indices = [Index(value = ["bookId", "chapterKey", "startMs"], unique = true)],
)
data class TranslationChunkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: String,
    val chapterKey: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val modelId: String,
    val lang: String,
    val updatedAt: Long,
)

@Dao
interface TranslationDao {

    @Query(
        "SELECT * FROM translation_chunks " +
            "WHERE bookId = :bookId AND chapterKey = :chapterKey ORDER BY startMs ASC",
    )
    suspend fun chapterChunks(bookId: String, chapterKey: String): List<TranslationChunkRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TranslationChunkRow)
}

@Database(entities = [TranslationChunkRow::class], version = 1, exportSchema = false)
abstract class TranslationDb : RoomDatabase() {
    abstract fun translationDao(): TranslationDao
}

/**
 * 缓存库的生命周期。
 *
 * 清缓存 = 关掉库、删掉 db / -wal / -shm 三个文件；下一次查询会自动重新建库，
 * 不需要重启 App。库里没有别的东西，删文件就是全清。
 */
object TranslationCache {

    private const val DB_NAME = "translation_cache.db"

    @Volatile private var db: TranslationDb? = null

    fun dao(context: Context): TranslationDao = synchronized(this) {
        val database = db ?: Room.databaseBuilder(
            context.applicationContext,
            TranslationDb::class.java,
            DB_NAME,
        ).fallbackToDestructiveMigration().build().also { db = it }
        database.translationDao()
    }

    fun file(context: Context): File = context.applicationContext.getDatabasePath(DB_NAME)

    /** 整个缓存库（含 WAL）占用的字节数。 */
    fun bytes(context: Context): Long {
        val f = file(context)
        return listOf(f, File(f.path + "-wal"), File(f.path + "-shm"))
            .sumOf { if (it.isFile) it.length() else 0L }
    }

    /** 删除整个缓存库；返回是否删掉了主文件。 */
    fun clear(context: Context): Boolean = synchronized(this) {
        runCatching { db?.close() }
        db = null
        val f = file(context)
        val deleted = runCatching { f.delete() }.getOrDefault(false)
        runCatching { File(f.path + "-wal").delete() }
        runCatching { File(f.path + "-shm").delete() }
        deleted
    }
}