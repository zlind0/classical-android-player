package com.aurora.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aurora.music.data.db.FileAlbum
import com.aurora.music.data.db.FileMerge
import com.aurora.music.data.db.FileTrack
import com.aurora.music.data.db.FilesDao
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import com.aurora.titlemerge.MergedRow
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.musicRootsDataStore by preferencesDataStore(name = "music_roots")

// Classical fork v0.3 (plan §7-13): owns the user's scan roots and the per-root
// file index. Roots live in DataStore; track rows live in library_files.db
// (Room, one table set per root) — FILE 栈与 MediaStore 栈物理隔离。
class MusicRootsStore(
    private val context: Context,
    private val filesDao: FilesDao,
) : SongPool {

    @Volatile private var loaded = false

    override val songs: List<Song> get() = allSongs()

    override suspend fun ensureLoaded() {
        if (!loaded) { loadFromDb(); loaded = true }
    }

    override suspend fun refresh() {
        loadFromDb(); loaded = true
    }

    private val gson = Gson()

    private object Keys {
        val ROOTS = stringPreferencesKey("roots")
        val NEXT_ID = longPreferencesKey("next_id")
    }

    private val indexType = object : TypeToken<List<ScannedTrack>>() {}.type
    private val rootsType = object : TypeToken<List<MusicRoot>>() {}.type

    private val _roots = MutableStateFlow<List<MusicRoot>>(emptyList())
    val roots: StateFlow<List<MusicRoot>> = _roots.asStateFlow()

    private val _counts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val trackCounts: StateFlow<Map<Long, Int>> = _counts.asStateFlow()

    // rootId → 该库全量行（含 unavailable，启动只从 DB 读一次）
    private val _trackRows = MutableStateFlow<Map<Long, List<ScannedTrack>>>(emptyMap())

    // albumId("dir:父目录") → 预计算合并行，深扫时一次算好，UI 只查表
    private val _fileMerges = MutableStateFlow<Map<String, List<MergedRow>>>(emptyMap())
    val fileMerges: StateFlow<Map<String, List<MergedRow>>> = _fileMerges.asStateFlow()

    val progress = MutableStateFlow(ScanProgress())

    init {
        _roots.value = readRoots()
    }

    /** 启动时调一次：DB 快照进内存 + JSON 一次性迁移。IO 线程调用。 */
    suspend fun loadFromDb() {
        migrateJsonIfNeeded()
        val rows = filesDao.allTrackRows()
        _trackRows.value = rows.groupBy({ it.rootId }, { it.toScanned() })
        val merges = filesDao.mergesOfRoots(_roots.value.map { it.id })
        _fileMerges.value = merges.associate { it.albumId to parseMergeJson(it.rowsJson) }
        refreshCounts()
    }

    val rootsFlow: Flow<List<MusicRoot>> = context.musicRootsDataStore.data.map { parseRoots(it[Keys.ROOTS]) }

    private fun parseRoots(json: String?): List<MusicRoot> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else (gson.fromJson<List<MusicRoot>>(json, rootsType) ?: emptyList()).let { list ->
            // 老存档没有 mergeTitles 键（Gson 缺键读成 false），一次性迁成默认开
            if (json.contains("\"mergeTitles\"")) list
            else list.map { it.copy(mergeTitles = true) }
        }
    }.getOrDefault(emptyList())

    private fun readRoots(): List<MusicRoot> = runCatching {
        val file = File(context.filesDir, "music_roots.json")
        if (!file.exists()) emptyList() else parseRoots(file.readText())
    }.getOrDefault(emptyList())

    private suspend fun persistRoots(next: List<MusicRoot>) {
        _roots.value = next
        runCatching { File(context.filesDir, "music_roots.json").writeText(gson.toJson(next)) }
        // mirror into DataStore so future Room migration has a second copy
        runCatching { context.musicRootsDataStore.edit { it[Keys.ROOTS] = gson.toJson(next) } }
    }

    private fun indexFile(rootId: Long) = File(context.filesDir, "scan_$rootId.json")

    private fun readJsonIndex(rootId: Long): List<ScannedTrack> = runCatching {
        val f = indexFile(rootId)
        if (!f.exists()) emptyList() else (gson.fromJson<List<ScannedTrack>>(f.readText(), indexType) ?: emptyList())
    }.getOrDefault(emptyList())

    /** JSON → Room 一次性迁移：DB 已有数据则跳过。 */
    private suspend fun migrateJsonIfNeeded() {
        if (filesDao.allTrackRows().isNotEmpty()) {
            // 还有残留 JSON 就删掉，避免下次误判
            _roots.value.forEach { runCatching { indexFile(it.id).delete() } }
            return
        }
        var importedAny = false
        for (root in _roots.value) {
            val rows = readJsonIndex(root.id)
            if (rows.isEmpty()) continue
            filesDao.replaceRootScan(
                root.id,
                rows.map { it.toRow(root.id) },
                albumsOf(root.id, rows),
                mergesOf(root.id, rows),
            )
            importedAny = true
        }
        _roots.value.forEach { runCatching { indexFile(it.id).delete() } }
    }

    /** 内存快照（启动 loadFromDb 后有效），调用方不再碰磁盘。 */
    fun readIndex(rootId: Long): List<ScannedTrack> = _trackRows.value[rootId].orEmpty()

    /**
     * 深扫落盘：三张表原子替换 + 内存快照更新。只在加库/手动重扫时调用，
     * 启动时永不调用（启动只读 + 存在性检查）。
     */
    suspend fun writeScanResult(
        rootId: Long,
        tracks: List<ScannedTrack>,
        albums: List<FileAlbum> = albumsOf(rootId, tracks),
        merges: List<FileMerge> = mergesOf(rootId, tracks),
    ) {
        filesDao.replaceRootScan(rootId, tracks.map { it.toRow(rootId) }, albums, merges)
        _trackRows.value = _trackRows.value + (rootId to tracks)
        val enabledIds = _roots.value.filter { it.enabled }.map { it.id }
        _fileMerges.value = filesDao.mergesOfRoots(enabledIds).associate { it.albumId to parseMergeJson(it.rowsJson) }
        refreshCounts()
    }

    /** 后台存在性检查：消失的标 unavailable（沉底灰色，不删行）。分片调用。 */
    suspend fun markUnavailable(paths: List<String>) {
        if (paths.isEmpty()) return
        filesDao.markUnavailable(paths)
        val gone = paths.toSet()
        _trackRows.value = _trackRows.value.mapValues { (_, rows) ->
            rows.map { if (it.path in gone) it.copy(available = false) else it }
        }
        refreshCounts()
    }

    suspend fun markAvailable(paths: List<String>) {
        if (paths.isEmpty()) return
        filesDao.markAvailable(paths)
        val back = paths.toSet()
        _trackRows.value = _trackRows.value.mapValues { (_, rows) ->
            rows.map { if (it.path in back) it.copy(available = true) else it }
        }
        refreshCounts()
    }

    /** 单文件标签改写后：刷新该行元信息（size/mtime/标签），不触发重扫。 */
    suspend fun updateTrackMeta(track: ScannedTrack) {
        val rootId = _trackRows.value.entries.firstOrNull { (_, rows) -> rows.any { it.path == track.path } }?.key
            ?: return
        filesDao.upsertTracks(listOf(track.toRow(rootId)))
        _trackRows.value = _trackRows.value.mapValues { (_, rows) ->
            rows.map { if (it.path == track.path) track else it }
        }
    }

    fun refreshCounts() {
        _counts.value = _roots.value.associate { it.id to readIndex(it.id).count { t -> t.available } }
    }

    /** All available tracks across enabled roots, as playable Songs. */
    fun allSongs(): List<Song> = _roots.value
        .filter { it.enabled }
        .flatMap { readIndex(it.id) }
        .filter { it.available }
        .map { it.toSong() }

    /** 全量行（含 unavailable），供后台存在性检查。 */
    fun allRows(): List<ScannedTrack> = _trackRows.value.values.flatten()

    fun songsOf(rootId: Long): List<Song> =
        readIndex(rootId).filter { it.available }.map { it.toSong() }

    /** 专辑曲目标准序（路径序），与深扫 merge 预计算同一份顺序，序号对齐。 */
    fun albumTracksSorted(albumId: String): List<Song> =
        allSongs().filter { it.albumId == albumId }.sortedBy { it.path.lowercase() }

    suspend fun addRoot(path: String, displayName: String, type: StorageType): MusicRoot? {
        val norm = File(path).canonicalPath
        if (_roots.value.any { File(it.rootPath).canonicalPath == norm }) return null
        val root = MusicRoot(
            id = System.currentTimeMillis(),
            rootPath = norm,
            displayName = displayName,
            storageType = type,
        )
        persistRoots(_roots.value + root)
        return root
    }

    suspend fun removeRoot(id: Long) {
        persistRoots(_roots.value.filterNot { it.id == id })
        filesDao.deleteRoot(id)
        runCatching { indexFile(id).delete() }
        _trackRows.value = _trackRows.value - id
        _counts.value = _counts.value - id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        persistRoots(_roots.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun setMergeTitles(id: Long, merge: Boolean) {
        persistRoots(_roots.value.map { if (it.id == id) it.copy(mergeTitles = merge) else it })
    }

    suspend fun stampScan(id: Long) {
        persistRoots(_roots.value.map { if (it.id == id) it.copy(lastScanTime = System.currentTimeMillis()) else it })
    }

    /** 用户点的“清理”：把已标 unavailable 的行硬删掉（启动检查本身只标记不删）。 */
    suspend fun cleanMissing(id: Long): Int {
        val removed = filesDao.deleteUnavailableOfRoot(id)
        _trackRows.value = _trackRows.value.mapValues { (rid, rows) ->
            if (rid == id) rows.filter { it.available } else rows
        }
        refreshCounts()
        return removed
    }
}

/** 文件栈专辑标准序：路径序。深扫 merge 预计算与展示必须用同一份顺序。 */
fun fileTracksSorted(tracks: List<ScannedTrack>): List<ScannedTrack> =
    tracks.sortedBy { it.path.lowercase() }

private fun albumIdOf(path: String): String = "dir:${File(path).parent ?: ""}"

private fun albumsOf(rootId: Long, tracks: List<ScannedTrack>): List<FileAlbum> =
    fileTracksSorted(tracks).groupBy { albumIdOf(it.path) }.map { (aid, rows) ->
        val first = rows.first()
        FileAlbum(
            rootId = rootId,
            albumId = aid,
            title = first.album.ifBlank { File(first.path).parentFile?.name.orEmpty() },
            artist = rows.map { it.artist }.distinct().let { if (it.size == 1) it.first() else "Various artists" },
            artworkUrl = rows.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
            songCount = rows.size,
            durationSec = rows.sumOf { it.durationSec },
        )
    }

private fun mergesOf(rootId: Long, tracks: List<ScannedTrack>): List<FileMerge> {
    val ordered = fileTracksSorted(tracks)
    return ordered.groupBy { albumIdOf(it.path) }.map { (aid, rows) ->
        FileMerge(rootId, aid, buildMergeJson(rows.map { it.toSong() }))
    }
}

private fun ScannedTrack.toRow(rootId: Long) = FileTrack(
    path = path, rootId = rootId, size = size, lastModified = lastModified,
    title = title, artist = artist, album = album, durationSec = durationSec,
    artworkUrl = artworkUrl, codec = codec, available = available,
)

private fun FileTrack.toScanned() = ScannedTrack(
    path = path, size = size, lastModified = lastModified,
    title = title, artist = artist, album = album, durationSec = durationSec,
    artworkUrl = artworkUrl, available = available, codec = codec,
)

fun ScannedTrack.toSong(): Song {
    val fileName = path.substringAfterLast('/')
    return Song(
        id = "file:$path",
        title = title.ifBlank { fileName.substringBeforeLast('.') },
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        durationSec = durationSec,
        streamUrl = File(path).let { if (it.exists()) android.net.Uri.fromFile(it).toString() else "" },
        albumId = "dir:${File(path).parent ?: ""}",
        artistId = artist,
        suffix = fileName.substringAfterLast('.', ""),
        path = path,
        accent = accentFor(path),
        codecMime = codec,
        available = available,
    )
}
