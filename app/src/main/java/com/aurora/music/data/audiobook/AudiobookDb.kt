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

// 有声书独立库：audiobook_library.db。
// roots = 用户选的扫描根；chapters = 每根下"书目录→章节文件"的扫描快照；
// progress = 每本书的记忆（按书目录 path 主键，关键字/重扫变化不续错章）。

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
    indices = [Index(value = ["rootId"]), Index(value = ["bookId"])],
)
data class AudiobookChapterRow(
    @PrimaryKey val path: String,
    val bookId: String = "",
    val title: String = "",
    val trackNumber: Int = 0,
    val durationSec: Int = 0,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val rootId: Long = 0L,
)

@Entity(tableName = "abook_progress")
data class AudiobookProgressRow(
    @PrimaryKey val bookId: String,
    val currentPath: String = "",
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

    @Query("UPDATE abook_progress SET currentPath = :path, positionMs = :pos, lastPlayedAt = :at WHERE bookId = :bookId")
    suspend fun savePos(bookId: String, path: String, pos: Long, at: Long)

    @Query("UPDATE abook_progress SET speed = :speed WHERE bookId = :bookId")
    suspend fun saveSpeed(bookId: String, speed: Float)

    @Query("UPDATE abook_progress SET gain = :gain WHERE bookId = :bookId")
    suspend fun saveGain(bookId: String, gain: Float)

    @Query("SELECT path FROM abook_chapters WHERE rootId = :rootId")
    suspend fun pathsOfRoot(rootId: Long): List<String>

    @Query("DELETE FROM abook_progress WHERE bookId IN (:bookIds)")
    suspend fun deleteProgress(bookIds: List<String>)

    @Transaction
    suspend fun replaceRootScan(rootId: Long, rows: List<AudiobookChapterRow>) {
        val oldBookIds = chaptersOfRoot(rootId).map { it.bookId }.toSet()
        deleteChaptersOfRoot(rootId)
        upsertChapters(rows)
        val keep = rows.map { it.bookId }.toSet()
        val gone = (oldBookIds - keep).toList()
        if (gone.isNotEmpty()) deleteProgress(gone)
    }
}

@Database(entities = [AudiobookRootRow::class, AudiobookChapterRow::class, AudiobookProgressRow::class], version = 1, exportSchema = false)
abstract class AudiobookDb : RoomDatabase() {
    abstract fun audiobookDao(): AudiobookDao
}
