package com.aurora.music.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

// FILE 栈的独立库：library_files.db。主键 path，与 MediaStore 栈物理隔离。
// 深扫（加库/手动重扫）时写入；启动只读 + File.exists() 存在性检查。

@Entity(tableName = "tracks")
data class FileTrack(
    @PrimaryKey val path: String,
    val rootId: Long,
    val size: Long,
    val lastModified: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val artworkUrl: String = "",
    val codec: String = "",
    val available: Boolean = true,
)

@Entity(tableName = "albums", primaryKeys = ["rootId", "albumId"])
data class FileAlbum(
    val rootId: Long,
    val albumId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String = "",
    val songCount: Int = 0,
    val durationSec: Int = 0,
)

// 标题合并预计算：mergeTracks() 在深扫时一次算好，UI 只查表。
@Entity(tableName = "merges", primaryKeys = ["rootId", "albumId"])
data class FileMerge(
    val rootId: Long,
    val albumId: String,
    val rowsJson: String,
)

@Dao
interface FilesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<FileTrack>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<FileAlbum>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMerges(merges: List<FileMerge>)

    @Query("SELECT * FROM tracks WHERE rootId = :rootId ORDER BY path COLLATE NOCASE ASC")
    suspend fun tracksOfRoot(rootId: Long): List<FileTrack>

    @Query("SELECT * FROM tracks WHERE rootId IN (:rootIds) AND available = 1 ORDER BY path COLLATE NOCASE ASC")
    suspend fun availableTracks(rootIds: List<Long>): List<FileTrack>

    @Query("SELECT * FROM tracks ORDER BY path COLLATE NOCASE ASC")
    suspend fun allTrackRows(): List<FileTrack>

    @Query("SELECT * FROM tracks WHERE rootId IN (:rootIds) ORDER BY path COLLATE NOCASE ASC")
    suspend fun allTracks(rootIds: List<Long>): List<FileTrack>

    @Query("SELECT * FROM albums WHERE rootId IN (:rootIds)")
    suspend fun albumsOfRoots(rootIds: List<Long>): List<FileAlbum>

    @Query("SELECT * FROM merges WHERE rootId IN (:rootIds)")
    suspend fun mergesOfRoots(rootIds: List<Long>): List<FileMerge>

    @Query("SELECT COUNT(*) FROM tracks WHERE rootId = :rootId AND available = 1")
    suspend fun availableCount(rootId: Long): Int

    @Query("UPDATE tracks SET available = 0 WHERE path IN (:paths)")
    suspend fun markUnavailable(paths: List<String>)

    @Query("UPDATE tracks SET available = 1 WHERE path IN (:paths)")
    suspend fun markAvailable(paths: List<String>)

    @Query("DELETE FROM tracks WHERE rootId = :rootId")
    suspend fun deleteTracksOfRoot(rootId: Long)

    @Query("DELETE FROM albums WHERE rootId = :rootId")
    suspend fun deleteAlbumsOfRoot(rootId: Long)

    @Query("DELETE FROM merges WHERE rootId = :rootId")
    suspend fun deleteMergesOfRoot(rootId: Long)

    @Query("DELETE FROM tracks WHERE rootId = :rootId AND available = 0")
    suspend fun deleteUnavailableOfRoot(rootId: Long): Int

    // 深扫落盘：一个 root 的三张表原子替换，陈旧行不可能残留
    @Transaction
    suspend fun replaceRootScan(
        rootId: Long,
        tracks: List<FileTrack>,
        albums: List<FileAlbum>,
        merges: List<FileMerge>,
    ) {
        deleteTracksOfRoot(rootId)
        deleteAlbumsOfRoot(rootId)
        deleteMergesOfRoot(rootId)
        upsertTracks(tracks)
        upsertAlbums(albums)
        upsertMerges(merges)
    }

    @Transaction
    suspend fun deleteRoot(rootId: Long) {
        deleteTracksOfRoot(rootId)
        deleteAlbumsOfRoot(rootId)
        deleteMergesOfRoot(rootId)
    }
}

@Database(entities = [FileTrack::class, FileAlbum::class, FileMerge::class], version = 1, exportSchema = false)
abstract class FilesDb : RoomDatabase() {
    abstract fun filesDao(): FilesDao
}
