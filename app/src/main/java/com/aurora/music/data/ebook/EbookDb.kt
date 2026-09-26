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

    @Query("SELECT * FROM ebooks WHERE lastReadAt > 0 ORDER BY lastReadAt DESC LIMIT :limit")
    suspend fun recentBooks(limit: Int): List<EbookRow>

    @Query("SELECT * FROM ebooks WHERE path = :path")
    suspend fun bookByPath(path: String): EbookRow?

    @Query("SELECT * FROM ebooks WHERE md5 = :md5 AND size = :size AND inShelf = 1 LIMIT 1")
    suspend fun shelfByFingerprint(md5: String, size: Long): EbookRow?

    @Query("SELECT * FROM ebooks")
    suspend fun allBooks(): List<EbookRow>

    @Query("UPDATE ebooks SET spineIndex = :spine, pageIndex = :page, progressPct = :pct, lastReadAt = :at WHERE path = :path")
    suspend fun saveProgress(path: String, spine: Int, page: Int, pct: Float, at: Long)

    @Query("DELETE FROM ebooks WHERE rootId = :rootId")
    suspend fun deleteBooksOfRoot(rootId: Long)

    @Query("DELETE FROM ebooks WHERE path IN (:paths)")
    suspend fun deleteBooks(paths: List<String>)

    @Transaction
    suspend fun replaceRootScan(rootId: Long, books: List<EbookRow>) {
        deleteBooksOfRoot(rootId)
        upsertBooks(books)
    }
}

@Database(entities = [EbookRootRow::class, EbookRow::class], version = 1, exportSchema = false)
abstract class EbookDb : RoomDatabase() {
    abstract fun ebookDao(): EbookDao
}
