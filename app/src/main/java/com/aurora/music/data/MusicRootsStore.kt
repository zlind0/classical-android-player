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
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
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
//
// 同名专辑/艺人跨文件夹合并展示（归一 key，与 MediaStore 栈一致；代价：
// 撞名的不同专辑会被并到一起）。内存 songs/albums/artists 只在 DB 变化时
// 重建一次，UI 每次直接读缓存，不再全量 groupBy。
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

    // 归一专辑 key → 预计算合并行，全局重算，UI 只查表
    private val _fileMerges = MutableStateFlow<Map<String, List<MergedRow>>>(emptyMap())
    val fileMerges: StateFlow<Map<String, List<MergedRow>>> = _fileMerges.asStateFlow()

    // 内存缓存：DB 变化时重建，UI/Backend 直接读，O(1)
    @Volatile private var cachedSongs: List<Song> = emptyList()
    @Volatile private var cachedAlbums: List<Album> = emptyList()
    @Volatile private var cachedArtists: List<Artist> = emptyList()

    val progress = MutableStateFlow(ScanProgress())

    init {
        _roots.value = readRoots()
    }

    /** 启动时调一次：DB 快照进内存 + JSON 一次性迁移。IO 线程调用。 */
    suspend fun loadFromDb() {
        migrateJsonIfNeeded()
        val rows = filesDao.allTrackRows()
        _trackRows.value = rows.groupBy({ it.rootId }, { it.toScanned() })
        rebuildCache()
        // merges 为空（升级重建表/新库）→ 纯内存全量重算一次，无 IO
        val stored = filesDao.allMerges()
        if (stored.isEmpty() && rows.isNotEmpty()) {
            recomputeMerges()
        } else {
            _fileMerges.value = stored.associate { it.albumId to parseMergeJson(it.rowsJson) }
        }
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
        for (root in _roots.value) {
            val rows = readJsonIndex(root.id)
            if (rows.isEmpty()) continue
            filesDao.replaceRootScan(
                root.id,
                rows.map { it.toRow(root.id) },
                albumsOf(root.id, rows, albumCovers(rows)),
            )
        }
        _roots.value.forEach { runCatching { indexFile(it.id).delete() } }
    }

    /** 内存快照（启动 loadFromDb 后有效），调用方不再碰磁盘。 */
    fun readIndex(rootId: Long): List<ScannedTrack> = _trackRows.value[rootId].orEmpty()

    /**
     * 深扫落盘：tracks/albums 原子替换 + 内存快照更新 + 全局 merges 重算。
     * 只在加库/手动重扫时调用，启动时永不调用（启动只读 + 存在性检查）。
     */
    suspend fun writeScanResult(
        rootId: Long,
        tracks: List<ScannedTrack>,
        albums: List<FileAlbum> = albumsOf(rootId, tracks, albumCovers(tracks)),
    ) {
        filesDao.replaceRootScan(rootId, tracks.map { it.toRow(rootId) }, albums)
        _trackRows.value = _trackRows.value + (rootId to tracks)
        rebuildCache()
        recomputeMerges()
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
        rebuildCache()
        refreshCounts()
    }

    suspend fun markAvailable(paths: List<String>) {
        if (paths.isEmpty()) return
        filesDao.markAvailable(paths)
        val back = paths.toSet()
        _trackRows.value = _trackRows.value.mapValues { (_, rows) ->
            rows.map { if (it.path in back) it.copy(available = true) else it }
        }
        rebuildCache()
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
        rebuildCache()
        recomputeMerges()
    }

    fun refreshCounts() {
        _counts.value = _roots.value.associate { it.id to readIndex(it.id).count { t -> t.available } }
    }

    /** 展示用全量（可用在前原序，不可用沉底灰色）。O(1) 读缓存。 */
    fun allSongs(): List<Song> = cachedSongs

    fun fileAlbums(): List<Album> = cachedAlbums

    fun fileArtists(): List<Artist> = cachedArtists

    /** 全量行（含 unavailable），供后台存在性检查。 */
    fun allRows(): List<ScannedTrack> = _trackRows.value.values.flatten()

    fun songsOf(rootId: Long): List<Song> {
        val rows = readIndex(rootId)
        if (rows.isEmpty()) return emptyList()
        val covers = albumCovers(rows)
        val (av, un) = rows.partition { it.available }
        return (av + un).map { r -> r.toSong().copy(artworkUrl = resolveArtwork(r, covers)) }
    }

    /** 专辑曲目标准序（路径序），与 merge 预计算同一份顺序，序号对齐。O(1) 缓存过滤。 */
    fun albumTracksSorted(albumId: String): List<Song> =
        cachedSongs.filter { it.albumId == albumId }.sortedBy { it.path.lowercase() }

    /** 从 _trackRows 重建 songs/albums/artists 缓存。调用方在 DB/内存变化后调。 */
    private fun rebuildCache() {
        val enabled = _roots.value.filter { it.enabled }.map { it.id }.toSet()
        val rows = _trackRows.value.filterKeys { it in enabled }.values.flatten()
        val covers = albumCovers(rows)
        val (av, un) = rows.partition { it.available }
        cachedSongs = (av + un).map { r -> r.toSong().copy(artworkUrl = resolveArtwork(r, covers)) }
        val availSongs = cachedSongs.filter { it.available }
        cachedAlbums = availSongs.groupBy { it.albumId }
            .map { (aid, ts) ->
                // 展示标题只认 ID3：取出现最多的原始专辑名，无标签显示 Unknown album，不用目录名
                val title = ts.groupingBy { it.album }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
                Album(
                    id = aid,
                    title = title.ifBlank { "Unknown album" },
                    artist = ts.map { it.artist }.distinct().let { if (it.size == 1) it.first() else "Various artists" },
                    artworkUrl = covers[aid].orEmpty(),
                    year = 0,
                    songCount = ts.size,
                    durationSec = ts.sumOf { it.durationSec },
                )
            }
            .sortedBy { it.title.lowercase() }
        cachedArtists = availSongs.groupBy { it.artistId }
            .map { (aid, ts) ->
                Artist(
                    id = aid,
                    name = ts.first().artist.ifBlank { "Unknown artist" },
                    imageUrl = ts.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
                    monthlyListeners = 0,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * 专辑封面：从该专辑有内嵌图的歌里随机抽一首（种子固定，结果稳定）；
     * 都没有内嵌图则用专辑目录下随机一张 jpg；再没有就是空（UI 默认图兜底）。
     * 目录只用于找图，不参与分类。
     */
    private fun albumCovers(rows: List<ScannedTrack>): Map<String, String> =
        rows.groupBy { fileAlbumKey(it.album, "file:${it.path}") }.mapValues { (aid, rs) ->
            val ordered = rs.sortedBy { it.path.lowercase() }
            val withArt = ordered.filter { it.hasEmbedded == true }
            if (withArt.isNotEmpty()) {
                val pick = withArt[kotlin.math.abs(aid.hashCode()) % withArt.size]
                TrackArtworkCache.embeddedCacheUri(context, "file:${pick.path}")
            } else {
                // 专辑可能跨文件夹：取曲目最多的那个目录为“专辑目录”
                val dir = ordered.groupingBy { it.path.substringBeforeLast('/') }
                    .eachCount().maxByOrNull { it.value }?.key.orEmpty()
                randomDirJpg(dir, aid)
            }
        }

    /**
     * 单曲 artwork 解析：自带内嵌图（扫描时已进缓存）就用自己的；
     * 没有则 fallback 到专辑封面；专辑也没有则保留行内目录封面/空（UI 默认图兜底）。
     * 展示层直读缓存 URI，不预检存在。
     */
    private fun resolveArtwork(row: ScannedTrack, covers: Map<String, String>): String {
    val id = "file:${row.path}"
    if (row.hasEmbedded == true) return TrackArtworkCache.embeddedCacheUri(context, id)
        val albumArt = covers[fileAlbumKey(row.album, id)]
        if (!albumArt.isNullOrBlank()) return albumArt
        return row.artworkUrl
    }

    /** 目录下随机一张 jpg（种子固定，结果稳定）。 */
    private fun randomDirJpg(dirPath: String, seedKey: String): String {
        if (dirPath.isBlank()) return ""
        val jpgs = runCatching {
            File(dirPath).listFiles { f ->
                val name = f.name.lowercase()
                runCatching { f.isFile && f.canRead() }.getOrDefault(false) &&
                    (name.endsWith(".jpg") || name.endsWith(".jpeg"))
            }?.toList().orEmpty()
        }.getOrDefault(emptyList())
        if (jpgs.isEmpty()) return ""
        val pick = jpgs.sortedBy { it.name.lowercase() }[kotlin.math.abs(seedKey.hashCode()) % jpgs.size]
        return runCatching { android.net.Uri.fromFile(pick).toString() }.getOrDefault("")
    }

    /**
     * 全局 merges 重算（纯内存，无 IO）：enabled 库全量行按归一专辑 key 分组，
     * 路径序与展示 albumTracksSorted 完全一致，序号对齐。成员变化时调用
     * （深扫/删库/清理/开关/改标签）；仅 available 翻转时不需调用（顺序不变）。
     */
    private suspend fun recomputeMerges() {
        val enabled = _roots.value.filter { it.enabled }.map { it.id }.toSet()
        val rows = _trackRows.value.filterKeys { it in enabled }.values.flatten()
        val merges = rows.sortedBy { it.path.lowercase() }
            .groupBy { fileAlbumKey(it.album, "file:${it.path}") }
            .map { (aid, rs) -> FileMerge(aid, buildMergeJson(rs.map { it.toSong() })) }
        filesDao.replaceMerges(merges)
        _fileMerges.value = merges.associate { it.albumId to parseMergeJson(it.rowsJson) }
    }

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
        rebuildCache()
        recomputeMerges()
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        persistRoots(_roots.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
        rebuildCache()
        recomputeMerges()
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
        rebuildCache()
        recomputeMerges()
        refreshCounts()
        return removed
    }
}

/** 文件栈专辑标准序：路径序。深扫 merge 预计算与展示必须用同一份顺序。 */
fun fileTracksSorted(tracks: List<ScannedTrack>): List<ScannedTrack> =
    tracks.sortedBy { it.path.lowercase() }

/**
 * 文件栈归一专辑 key：严格按 ID3 专辑名归一（跨文件夹同名即同专辑，
 * 与 MediaStore 栈一致；代价：撞名的不同专辑会被并到一起）。
 * 不用任何文件夹信息：无专辑标签的文件每首独立成专（unique key），
 * 绝不把同目录下的不同专辑并到一起。
 */
fun fileAlbumKey(title: String, songId: String): String {
    val t = title.trim()
    return if (t.isEmpty()) "unknown-album::$songId" else "album::" + t.lowercase()
}

/** 艺人 key：严格按 ID3 艺人名；无标签每首独立，不合并“未知艺人”。 */
fun fileArtistKey(artist: String, songId: String): String {
    val t = artist.trim()
    return if (t.isEmpty()) "unknown-artist::$songId" else t
}

private fun albumsOf(
    rootId: Long,
    tracks: List<ScannedTrack>,
    covers: Map<String, String>,
): List<FileAlbum> =
    fileTracksSorted(tracks).groupBy { fileAlbumKey(it.album, "file:${it.path}") }.map { (aid, rows) ->
        val first = rows.first()
        FileAlbum(
            rootId = rootId,
            albumId = aid,
            title = first.album.ifBlank { "Unknown album" },
            artist = rows.map { it.artist }.distinct().let { if (it.size == 1) it.first() else "Various artists" },
            artworkUrl = covers[aid].orEmpty(),
            songCount = rows.size,
            durationSec = rows.sumOf { it.durationSec },
        )
    }

private fun ScannedTrack.toRow(rootId: Long) = FileTrack(
    path = path, rootId = rootId, size = size, lastModified = lastModified,
    title = title, artist = artist, album = album, durationSec = durationSec,
    artworkUrl = artworkUrl, codec = codec, available = available,
    hasEmbedded = hasEmbedded,
)

private fun FileTrack.toScanned() = ScannedTrack(
    path = path, size = size, lastModified = lastModified,
    title = title, artist = artist, album = album, durationSec = durationSec,
    artworkUrl = artworkUrl, available = available, codec = codec,
    hasEmbedded = hasEmbedded,
)

fun ScannedTrack.toSong(): Song {
    val fileName = path.substringAfterLast('/')
    val id = "file:$path"
    return Song(
        id = id,
        title = title.ifBlank { fileName.substringBeforeLast('.') },
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        durationSec = durationSec,
        // 查询时不做 File.exists() 预检（大库下是全表 stat 风暴）；
        // 文件真没了由播放报错时标 unavailable + 跳过
        streamUrl = android.net.Uri.fromFile(File(path)).toString(),
        albumId = fileAlbumKey(album, id),
        artistId = fileArtistKey(artist, id),
        suffix = fileName.substringAfterLast('.', ""),
        path = path,
        accent = accentFor(path),
        codecMime = codec,
        available = available,
    )
}
