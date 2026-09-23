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

// MEDIASTORE 栈的独立库：library_mediastore.db。主键 mediaId，与文件栈物理隔离。
// 首次进此模式（或手动重同步）时把 MediaStore 全量结果写入；启动只读 + DATA 路径存在性检查。

@Entity(tableName = "tracks")
data class MsTrack(
    @PrimaryKey val mediaId: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val album: String,
    val albumKey: String,
    val durationSec: Int,
    val year: Int = 0,
    val dateAddedSec: Long = 0,
    val displayName: String = "",
    val mime: String = "",
    val suffix: String = "",
    val bitrateKbps: Int = 0,
    val genre: String = "",
    val composer: String = "",
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
    val dataPath: String = "",
    val streamUrl: String = "",
    val artworkUrl: String = "",
    val available: Boolean = true,
)

@Entity(tableName = "albums")
data class MsAlbum(
    @PrimaryKey val albumKey: String,
    val title: String,
    val artist: String,
    val artworkUrl: String = "",
    val year: Int = 0,
    val songCount: Int = 0,
    val durationSec: Int = 0,
    val dateAddedSec: Long = 0,
)

@Entity(tableName = "merges")
data class MsMerge(
    @PrimaryKey val albumKey: String,
    val rowsJson: String,
)

@Dao
interface MediastoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<MsTrack>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<MsAlbum>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMerges(merges: List<MsMerge>)

    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    suspend fun allTracks(): List<MsTrack>

    @Query("SELECT * FROM tracks WHERE available = 1 ORDER BY title COLLATE NOCASE ASC")
    suspend fun availableTracks(): List<MsTrack>

    @Query("SELECT * FROM albums")
    suspend fun allAlbums(): List<MsAlbum>

    @Query("SELECT * FROM merges")
    suspend fun allMerges(): List<MsMerge>

    @Query("UPDATE tracks SET available = 0 WHERE mediaId IN (:ids)")
    suspend fun markUnavailable(ids: List<String>)

    @Query("UPDATE tracks SET available = 1 WHERE mediaId IN (:ids)")
    suspend fun markAvailable(ids: List<String>)

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    @Query("DELETE FROM merges")
    suspend fun clearMerges()

    // 系统库重同步：三张表原子替换
    @Transaction
    suspend fun replaceAll(
        tracks: List<MsTrack>,
        albums: List<MsAlbum>,
        merges: List<MsMerge>,
    ) {
        clearTracks()
        clearAlbums()
        clearMerges()
        upsertTracks(tracks)
        upsertAlbums(albums)
        upsertMerges(merges)
    }
}

@Database(entities = [MsTrack::class, MsAlbum::class, MsMerge::class], version = 1, exportSchema = false)
abstract class MediastoreDb : RoomDatabase() {
    abstract fun mediastoreDao(): MediastoreDao
}
