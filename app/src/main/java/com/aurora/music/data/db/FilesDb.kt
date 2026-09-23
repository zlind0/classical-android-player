package com.aurora.music.data.db

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

// FILE 栈的独立库：library_files.db。主键 path，与 MediaStore 栈物理隔离。
// 深扫（加库/手动重扫）时写入；启动只读 + File.exists() 存在性检查。
// v2：补常用查询索引；merges 改全局主键（同名专辑跨文件夹合并展示）。

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["rootId", "available"]), Index(value = ["available"])],
)
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

@Entity(
    tableName = "albums",
    primaryKeys = ["rootId", "albumId"],
    indices = [Index(value = ["rootId"])],
)
data class FileAlbum(
    val rootId: Long,
    val albumId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String = "",
    val songCount: Int = 0,
    val durationSec: Int = 0,
)

// 标题合并预计算：全局主键 albumKey（归一专辑名，跨文件夹同名即同专辑），
// 深扫/库变更时全量重算一次，UI 只查表。
@Entity(tableName = "merges")
data class FileMerge(
    @PrimaryKey val albumId: String,
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

    @Query("SELECT * FROM merges")
    suspend fun allMerges(): List<FileMerge>

    @Query("DELETE FROM merges")
    suspend fun clearMerges()

    @Transaction
    suspend fun replaceMerges(merges: List<FileMerge>) {
        clearMerges()
        upsertMerges(merges)
    }

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

    @Query("DELETE FROM albums WHERE albumId LIKE 'dir:%'")
    suspend fun deleteLegacyDirAlbums(): Int

    @Query("DELETE FROM tracks WHERE rootId = :rootId AND available = 0")
    suspend fun deleteUnavailableOfRoot(rootId: Long): Int

    // 深扫落盘：一个 root 的 tracks/albums 原子替换，陈旧行不可能残留。
    // merges 是全局表，深扫后由调用方全量重算（replaceMerges），这里不动。
    @Transaction
    suspend fun replaceRootScan(
        rootId: Long,
        tracks: List<FileTrack>,
        albums: List<FileAlbum>,
    ) {
        deleteTracksOfRoot(rootId)
        deleteAlbumsOfRoot(rootId)
        upsertTracks(tracks)
        upsertAlbums(albums)
    }

    @Transaction
    suspend fun deleteRoot(rootId: Long) {
        deleteTracksOfRoot(rootId)
        deleteAlbumsOfRoot(rootId)
    }
}

val FilesMigration1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_rootId_available` ON `tracks` (`rootId`, `available`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_available` ON `tracks` (`available`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_albums_rootId` ON `albums` (`rootId`)")
        // merges 改全局主键：旧表（rootId, albumId）直接重建，内容由启动时从 tracks 全量重算
        db.execSQL("DROP TABLE IF EXISTS `merges`")
        db.execSQL("CREATE TABLE IF NOT EXISTS `merges` (`albumId` TEXT NOT NULL, `rowsJson` TEXT NOT NULL, PRIMARY KEY(`albumId`))")
        // 旧专辑 key 是 dir:xxx，新 key 是归一专辑名，历史行删掉等下次深扫重写（展示不读此表）
        db.execSQL("DELETE FROM `albums` WHERE `albumId` LIKE 'dir:%'")
    }
}

@Database(entities = [FileTrack::class, FileAlbum::class, FileMerge::class], version = 2, exportSchema = false)
abstract class FilesDb : RoomDatabase() {
    abstract fun filesDao(): FilesDao
}
