package com.aurora.music.data.ebook

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

// 电子书独立库：ebook_library.db。主键 path（绝对路径唯一）。
// 扫描库与默认书架共用一张表，用 rootId 区分（默认书架 rootId = EBOOK_SHELF_ROOT_ID）。

@Entity(tableName = "ebook_roots")
data class EbookRootRow(
    @PrimaryKey val id: Long,
    val rootPath: String,
    val displayName: String,
    val storageType: String = "INTERNAL",
    val enabled: Boolean = true,
    val lastScanTime: Long = 0L,
)

@Entity(
    tableName = "ebooks",
    indices = [Index(value = ["rootId"]), Index(value = ["lastReadAt"]), Index(value = ["md5", "size"])],
)
data class EbookRow(
    @PrimaryKey val path: String,
    val md5: String = "",
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val title: String = "",
    val author: String = "",
    val coverPath: String = "",
    val rootId: Long = EBOOK_SHELF_ROOT_ID,
    val inShelf: Boolean = false,
    val spineIndex: Int = 0,
    val pageIndex: Int = 0,
    val progressPct: Float = 0f,
    val lastReadAt: Long = 0L,
    /** 朗读/阅读锚点：块下标 + 块内字偏移（句子级恢复用；页码由它推导更准） */
    val blockIndex: Int = 0,
    val charOffset: Int = 0,
    /** 进度唯一编码：章:段（显示用，1-based；恢复仍以 spine/block/char 为准）。 */
    val anchorCode: String = "",
)

/** 每本书的进度历史：按自然分钟去重（同分钟只留最后一条），每书最多留 30 条。 */
@Entity(
    tableName = "ebook_progress_history",
    primaryKeys = ["path", "minuteBucket"],
    indices = [Index(value = ["path"]), Index(value = ["updatedAt"])],
)
data class EbookProgressHistoryRow(
    val path: String,
    /** 本地自然分钟起始毫秒（见 EbookAnchorCode.minuteStart）。 */
    val minuteBucket: Long,
    val anchorCode: String = "",
    val spine: Int = 0,
    val block: Int = 0,
    val updatedAt: Long = 0L,
)

@Dao
interface EbookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<EbookRow>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRoots(roots: List<EbookRootRow>)

    @Query("SELECT * FROM ebook_roots ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun allRoots(): List<EbookRootRow>

    @Query("SELECT * FROM ebook_roots WHERE id = :id")
    suspend fun rootById(id: Long): EbookRootRow?

    @Query("UPDATE ebook_roots SET enabled = :enabled WHERE id = :id")
    suspend fun setRootEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE ebook_roots SET lastScanTime = :time WHERE id = :id")
    suspend fun stampRoot(id: Long, time: Long)

    @Query("DELETE FROM ebook_roots WHERE id = :id")
    suspend fun deleteRoot(id: Long)

    @Query("SELECT * FROM ebooks WHERE rootId = :rootId ORDER BY title COLLATE NOCASE ASC")
    suspend fun booksOfRoot(rootId: Long): List<EbookRow>

    @Query("SELECT * FROM ebooks WHERE inShelf = 1 ORDER BY title COLLATE NOCASE ASC")
    suspend fun shelfBooks(): List<EbookRow>

    // 局域网设备的书落在 filesDir/remote_ebooks，由 RemoteLibraryManager 单独展示最近阅读；
    // 这里排除掉，避免本机与远程两份“最近阅读”重复。
    @Query("SELECT * FROM ebooks WHERE lastReadAt > 0 AND path NOT LIKE '%/remote_ebooks/%' ORDER BY lastReadAt DESC LIMIT :limit")
    suspend fun recentBooks(limit: Int): List<EbookRow>

    @Query("SELECT * FROM ebooks WHERE path = :path")
    suspend fun bookByPath(path: String): EbookRow?

    @Query("SELECT * FROM ebooks WHERE md5 = :md5 AND size = :size AND inShelf = 1 LIMIT 1")
    suspend fun shelfByFingerprint(md5: String, size: Long): EbookRow?

    @Query("SELECT * FROM ebooks")
    suspend fun allBooks(): List<EbookRow>

    /** 进度写带单调保护：`at` 是该位置被观测到的时刻，比库里更旧的快照直接丢弃（返回 0）。
     * 没有这一条，锁屏/重建时阅读页手里冻结的旧位置会把后台听书推进的新进度洗回去。 */
    @Query("UPDATE ebooks SET spineIndex = :spine, pageIndex = :page, blockIndex = :block, charOffset = :charOff, progressPct = :pct, anchorCode = :code, lastReadAt = :at WHERE path = :path AND lastReadAt <= :at")
    suspend fun saveProgress(path: String, spine: Int, page: Int, block: Int, charOff: Int, pct: Float, code: String, at: Long): Int

    /** TTS 推进时的轻量保存：更新章/块/字偏移 + 最近阅读；页码归零等 UI 侧按块锚点推导回填。
     * 页码必须一起写（不能留旧章的页），否则 DB 出现“新章 + 旧页”的杂交行，
     * 下次打开时块锚点为章首（0,0）不触发推导，就会落在错页。 */
    @Query("UPDATE ebooks SET spineIndex = :spine, pageIndex = 0, blockIndex = :block, charOffset = :charOff, anchorCode = :code, lastReadAt = :at WHERE path = :path AND lastReadAt <= :at")
    suspend fun saveTtsPos(path: String, spine: Int, block: Int, charOff: Int, code: String, at: Long): Int

    @Query("DELETE FROM ebooks WHERE rootId = :rootId")
    suspend fun deleteBooksOfRoot(rootId: Long)

    @Query("DELETE FROM ebooks WHERE path IN (:paths)")
    suspend fun deleteBooks(paths: List<String>)

    // ---- 进度历史（随书一起删） ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(row: EbookProgressHistoryRow)

    @Query("SELECT * FROM ebook_progress_history WHERE path = :path ORDER BY minuteBucket DESC LIMIT 30")
    suspend fun historyOf(path: String): List<EbookProgressHistoryRow>

    @Query("SELECT path FROM ebooks WHERE rootId = :rootId")
    suspend fun pathsOfRoot(rootId: Long): List<String>

    @Query("DELETE FROM ebook_progress_history WHERE path IN (:paths)")
    suspend fun deleteHistoryOf(paths: List<String>)

    @Query("DELETE FROM ebook_progress_history WHERE path = :path AND minuteBucket NOT IN (SELECT minuteBucket FROM ebook_progress_history WHERE path = :path ORDER BY minuteBucket DESC LIMIT 30)")
    suspend fun pruneHistory(path: String)

    @Transaction
    suspend fun replaceRootScan(rootId: Long, books: List<EbookRow>) {
        val oldPaths = pathsOfRoot(rootId).toSet()
        val keep = books.map { it.path }.toSet()
        deleteBooksOfRoot(rootId)
        upsertBooks(books)
        // 只清掉本次扫描已消失的书的历史，保留书的历史不动
        val gone = (oldPaths - keep).toList()
        if (gone.isNotEmpty()) deleteHistoryOf(gone)
    }
}

@Database(entities = [EbookRootRow::class, EbookRow::class, EbookProgressHistoryRow::class], version = 3, exportSchema = false)
abstract class EbookDb : RoomDatabase() {
    abstract fun ebookDao(): EbookDao
}
