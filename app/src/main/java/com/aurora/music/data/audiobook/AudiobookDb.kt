package com.aurora.music.data.audiobook

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 有声书独立库：audiobook_library.db。
// roots = 用户选的扫描根；chapters = 每根下"书目录→章节文件"的扫描快照；
// progress = 每本书的记忆（按书目录 path 主键，关键字/重扫变化不续错章）。
//
// 章节主键用 key 而不是 path：m4b 一个文件里有多章，同一个 path 会对应多行，
// key = path#文件内起点毫秒（普通文件起点恒为 0）。

@Entity(tableName = "abook_roots")
data class AudiobookRootRow(
    @PrimaryKey val id: Long,
    val rootPath: String,
    val displayName: String,
    val storageType: String = "INTERNAL",
    val enabled: Boolean = true,
    val lastScanTime: Long = 0L,
)

@Entity(
    tableName = "abook_chapters",
    indices = [Index(value = ["rootId"]), Index(value = ["bookId"]), Index(value = ["path"])],
)
data class AudiobookChapterRow(
    @PrimaryKey val key: String,
    val path: String = "",
    val bookId: String = "",
    val title: String = "",
    val trackNumber: Int = 0,
    val durationSec: Int = 0,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val rootId: Long = 0L,
    /** m4b 内嵌章节在文件内的起点毫秒；普通文件为 0。 */
    val startMs: Long = 0L,
    /** m4b 内嵌章节在文件内的终点毫秒；0 = 播到文件末尾。 */
    val endMs: Long = 0L,
)

@Entity(tableName = "abook_progress")
data class AudiobookProgressRow(
    @PrimaryKey val bookId: String,
    val currentPath: String = "",
    val currentStartMs: Long = 0L,
    val positionMs: Long = 0L,
    val speed: Float = 1f,
    val gain: Float = 1f,
    val lastPlayedAt: Long = 0L,
)

@Dao
interface AudiobookDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRoots(roots: List<AudiobookRootRow>)

    @Query("SELECT * FROM abook_roots ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun allRoots(): List<AudiobookRootRow>

    @Query("UPDATE abook_roots SET enabled = :enabled WHERE id = :id")
    suspend fun setRootEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE abook_roots SET lastScanTime = :time WHERE id = :id")
    suspend fun stampRoot(id: Long, time: Long)

    @Query("DELETE FROM abook_roots WHERE id = :id")
    suspend fun deleteRoot(id: Long)

    @Query("SELECT * FROM abook_chapters WHERE rootId = :rootId")
    suspend fun chaptersOfRoot(rootId: Long): List<AudiobookChapterRow>

    @Query("SELECT * FROM abook_chapters WHERE bookId = :bookId")
    suspend fun chaptersOfBook(bookId: String): List<AudiobookChapterRow>

    @Query("DELETE FROM abook_chapters WHERE rootId = :rootId")
    suspend fun deleteChaptersOfRoot(rootId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(rows: List<AudiobookChapterRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProgress(row: AudiobookProgressRow)

    @Query("SELECT * FROM abook_progress WHERE bookId = :bookId")
    suspend fun progressOf(bookId: String): AudiobookProgressRow?

    @Query("SELECT * FROM abook_progress WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun recentProgress(limit: Int): List<AudiobookProgressRow>

    @Query(
        "UPDATE abook_progress SET currentPath = :path, currentStartMs = :startMs, " +
            "positionMs = :pos, lastPlayedAt = :at WHERE bookId = :bookId",
    )
    suspend fun savePos(bookId: String, path: String, startMs: Long, pos: Long, at: Long): Int

    @Query("UPDATE abook_progress SET speed = :speed WHERE bookId = :bookId")
    suspend fun saveSpeed(bookId: String, speed: Float): Int

    @Query("UPDATE abook_progress SET gain = :gain WHERE bookId = :bookId")
    suspend fun saveGain(bookId: String, gain: Float): Int

    // 先 UPDATE、0 行再 INSERT（@Transaction 保证原子）：调用方并发时 check-then-act 会丢其中一份。
    // 位置 UPSERT 只碰章节/进度列，不动 speed/gain。
    @Transaction
    suspend fun upsertPos(bookId: String, path: String, startMs: Long, pos: Long, at: Long) {
        if (savePos(bookId, path, startMs, pos, at) == 0) {
            insertProgress(AudiobookProgressRow(bookId, path, startMs, pos, 1f, 1f, at))
        }
    }

    @Transaction
    suspend fun upsertSpeed(bookId: String, speed: Float) {
        if (saveSpeed(bookId, speed) == 0) {
            insertProgress(AudiobookProgressRow(bookId, "", 0L, 0L, speed, 1f, 0L))
        }
    }

    @Transaction
    suspend fun upsertGain(bookId: String, gain: Float) {
        if (saveGain(bookId, gain) == 0) {
            insertProgress(AudiobookProgressRow(bookId, "", 0L, 0L, 1f, gain, 0L))
        }
    }

    @Query("SELECT path FROM abook_chapters WHERE rootId = :rootId")
    suspend fun pathsOfRoot(rootId: Long): List<String>

    @Query("DELETE FROM abook_progress WHERE bookId IN (:bookIds)")
    suspend fun deleteProgress(bookIds: List<String>)

    @Transaction
    suspend fun replaceRootScan(rootId: Long, rows: List<AudiobookChapterRow>) {
        val oldBookIds = chaptersOfRoot(rootId).map { it.bookId }.toSet()
        deleteChaptersOfRoot(rootId)
        upsertChapters(rows)
        // 空扫（权限丢失/介质未挂载/瞬时失败）不删进度，否则重扫回来进度全丢。
        if (rows.isEmpty()) return
        val keep = rows.map { it.bookId }.toSet()
        val gone = (oldBookIds - keep).toList()
        if (gone.isNotEmpty()) deleteProgress(gone)
    }
}

@Database(
    entities = [AudiobookRootRow::class, AudiobookChapterRow::class, AudiobookProgressRow::class],
    version = 2,
    exportSchema = false,
)
abstract class AudiobookDb : RoomDatabase() {
    abstract fun audiobookDao(): AudiobookDao

    companion object {
        /** v1 → v2：章节表支持"一个文件多章"（key/startMs/endMs），进度记住文件内起点。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `abook_chapters_new` (" +
                        "`key` TEXT NOT NULL, `path` TEXT NOT NULL, `bookId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `trackNumber` INTEGER NOT NULL, " +
                        "`durationSec` INTEGER NOT NULL, `size` INTEGER NOT NULL, " +
                        "`lastModified` INTEGER NOT NULL, `rootId` INTEGER NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `abook_chapters_new` " +
                        "(`key`, `path`, `bookId`, `title`, `trackNumber`, `durationSec`, `size`, " +
                        "`lastModified`, `rootId`, `startMs`, `endMs`) " +
                        "SELECT `path`, `path`, `bookId`, `title`, `trackNumber`, `durationSec`, `size`, " +
                        "`lastModified`, `rootId`, 0, 0 FROM `abook_chapters`",
                )
                db.execSQL("DROP TABLE `abook_chapters`")
                db.execSQL("ALTER TABLE `abook_chapters_new` RENAME TO `abook_chapters`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_abook_chapters_rootId` ON `abook_chapters` (`rootId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_abook_chapters_bookId` ON `abook_chapters` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_abook_chapters_path` ON `abook_chapters` (`path`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `abook_progress_new` (" +
                        "`bookId` TEXT NOT NULL, `currentPath` TEXT NOT NULL, " +
                        "`currentStartMs` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, " +
                        "`speed` REAL NOT NULL, `gain` REAL NOT NULL, `lastPlayedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`bookId`))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `abook_progress_new` " +
                        "(`bookId`, `currentPath`, `currentStartMs`, `positionMs`, `speed`, `gain`, `lastPlayedAt`) " +
                        "SELECT `bookId`, `currentPath`, 0, `positionMs`, `speed`, `gain`, `lastPlayedAt` " +
                        "FROM `abook_progress`",
                )
                db.execSQL("DROP TABLE `abook_progress`")
                db.execSQL("ALTER TABLE `abook_progress_new` RENAME TO `abook_progress`")
            }
        }
    }
}
