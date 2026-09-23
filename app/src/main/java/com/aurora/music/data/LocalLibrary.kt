package com.aurora.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.aurora.music.data.db.MediastoreDao
import com.aurora.music.data.db.MsAlbum
import com.aurora.music.data.db.MsMerge
import com.aurora.music.data.db.MsTrack
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import com.aurora.music.util.accentFor
import com.aurora.titlemerge.MergedRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自然排序比较器：切成数字/非数字段，数字段按数值比（先比长度再逐字，
 * 防溢出），其余忽略大小写。纯 Kotlin，可单测。
 * public 仅为单测；业务统一走 [LocalLibrary.albumsByArtistId]。
 */
val naturalStringOrder: Comparator<String> = Comparator { a, b ->
    val ac = NaturalChunks.findAll(a).map { it.value }.toList()
    val bc = NaturalChunks.findAll(b).map { it.value }.toList()
    val diff = ac.zip(bc).firstOrNull { (x, y) -> chunkDiff(x, y) != 0 }
    diff?.let { chunkDiff(it.first, it.second) } ?: ac.size.compareTo(bc.size)
}

private val NaturalChunks = Regex("\\d+|\\D+")

private fun chunkDiff(x: String, y: String): Int {
    val xn = x.all { it.isDigit() }
    val yn = y.all { it.isDigit() }
    if (xn && yn) {
        val xs = x.trimStart('0')
        val ys = y.trimStart('0')
        val c = xs.length.compareTo(ys.length)
        if (c != 0) return c
        return xs.compareTo(ys)
    }
    return x.lowercase().compareTo(y.lowercase())
}

/**
 * MEDIASTORE 栈的内存库，数据来自 library_mediastore.db。
 * 启动只读 DB（[loadFromDb]）；MediaStore 全量查询只在
 * DB 为空的首次同步或手动重同步（[syncFromMediastore]）时发生；
 * 启动后台只做 DATA 路径存在性检查（[pruneMissing]），缺失标
 * unavailable（沉底灰色），不删行、不读标签。
 */
class LocalLibrary(
    private val context: Context,
    private val dao: MediastoreDao,
    // scanned replaygain overlaid by path since mediastore tags rarely carry it
    private val gainProvider: (String) -> Pair<Float, Float>? = { null },
) : SongPool {

    @Volatile private var loaded = false
    private val mutex = Mutex()

    override var songs: List<Song> = emptyList(); private set
    @Volatile var albums: List<Album> = emptyList(); private set
    @Volatile var artists: List<Artist> = emptyList(); private set
    private var byId: Map<String, Song> = emptyMap()

    @Volatile private var matchIndex: Map<String, List<Song>> = emptyMap()

    // 深扫时预计算的每专辑标题合并表（后台线程一次算好，UI 只查表）。
    // 顺序与 albumTracksSorted 完全一致，index 可直接对上。
    private val _albumMerges = MutableStateFlow<Map<String, List<MergedRow>>>(emptyMap())
    val albumMerges: StateFlow<Map<String, List<MergedRow>>> = _albumMerges.asStateFlow()

    @Volatile private var dirOf: Map<String, String> = emptyMap()

    @Volatile var folderRoot: String = ""; private set

    /** 启动路径：只读 DB，不碰 MediaStore；DB 为空则是首次进此模式，一次全量同步落盘。 */
    suspend fun loadFromDb() {
        mutex.withLock {
            val rows = dao.allTracks()
            if (rows.isEmpty()) syncLocked() else rebuildFromRows(rows)
            loaded = true
        }
    }

    override suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (!loaded) {
                val rows = dao.allTracks()
                if (rows.isEmpty()) {
                    // 首次进入此模式：一次全量同步落盘（之后不再自动全扫）
                    syncLocked()
                } else {
                    rebuildFromRows(rows)
                }
                loaded = true
            }
        }
    }

    /** 手动“重同步系统曲库”：全量 MediaStore 查询 + 落盘。 */
    override suspend fun refresh() {
        mutex.withLock { syncLocked(); loaded = true }
    }

    /**
     * 后台存在性检查：DATA 路径还在的保持可用，消失的标 unavailable。
     * 无 DATA 的行（新系统受限）无法判断，保持原样。返回变更数。
     */
    suspend fun pruneMissing(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val gone = songs.filter { it.path.isNotBlank() && !File(it.path).exists() }
            if (gone.isEmpty()) return@withLock 0
            val ids = gone.map { it.id }
            ids.chunked(500).forEach { dao.markUnavailable(it) }
            rebuildFromRows(dao.allTracks())
            gone.size
        }
    }

    /** 播放报错纠正：单曲/批量标 unavailable（重建内存，不删行）。 */
    suspend fun markUnavailableByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            ids.chunked(500).forEach { dao.markUnavailable(it) }
            rebuildFromRows(dao.allTracks())
        }
    }

    /** 播成功复活：灰色曲目实际可播时标回 available（重建内存）。 */
    suspend fun markAvailableByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            ids.chunked(500).forEach { dao.markAvailable(it) }
            rebuildFromRows(dao.allTracks())
        }
    }

    fun song(id: String): Song? = byId[id]

    // only substitute on a single unambiguous match so a different version is never swapped in
    fun findMatch(artist: String, title: String, durationSec: Int): Song? {
        if (title.isBlank()) return null
        val candidates = matchIndex[TrackMatch.key(artist, title)] ?: return null
        val byDuration = candidates.filter { durationSec > 0 && it.durationSec > 0 && abs(it.durationSec - durationSec) <= TrackMatch.DURATION_TOLERANCE_SEC }
        if (byDuration.isNotEmpty()) return byDuration.minByOrNull { abs(it.durationSec - durationSec) }
        return candidates.singleOrNull()
    }

    /** 专辑封面验链：URI 能打开才算有图（不解码，关流即走）。IO 线程调用。 */
    private fun probeArt(url: String): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(url))?.close()
        true
    }.getOrDefault(false)

    fun browse(path: String): Pair<List<String>, List<Song>> {
        val base = path.ifBlank { folderRoot }
        if (base.isBlank()) return emptyList<String>() to emptyList()
        val here = songs.filter { it.available && dirOf[it.id] == base }.sortedBy { it.title.lowercase() }
        val subdirs = dirOf.values.asSequence()
            .filter { it != base && it.startsWith("$base/") }
            .map { it.removePrefix("$base/").substringBefore('/') }
            .distinct().sortedBy { it.lowercase() }.toList()
        return subdirs to here
    }

    private fun commonDir(dirs: Collection<String>): String {
        if (dirs.isEmpty()) return ""
        var prefix = dirs.first().split('/')
        for (d in dirs) {
            val seg = d.split('/')
            var i = 0
            while (i < prefix.size && i < seg.size && prefix[i] == seg[i]) i++
            prefix = prefix.subList(0, i)
        }
        return prefix.joinToString("/")
    }
    fun songsIn(album: Album): List<Song> = songs.filter { it.albumId == album.id }
    fun songsByAlbumId(albumId: String): List<Song> = songs.filter { it.albumId == albumId }

    /** 专辑曲目标准序：碟号 → 曲号 → 标题 → 文件名（展示与合并都用它对齐序号）。 */
    fun albumTracksSorted(albumId: String): List<Song> =
        songsByAlbumId(albumId).sortedWith(ALBUM_TRACK_ORDER)
    fun songsByArtistId(artistId: String): List<Song> = songs.filter { it.artistId == artistId }

    /** 艺人名下专辑：按名称自然排序（1, 2, … 11，而不是字典序 1, 11, 2）。 */
    fun albumsByArtistId(artistId: String): List<Album> =
        songs.filter { it.artistId == artistId }.map { it.albumId }.distinct()
            .mapNotNull { aid -> albums.firstOrNull { it.id == aid } }
            .sortedWith(compareBy(naturalStringOrder) { it.title })

    private fun albumArtUri(albumId: Long): String =
        if (albumId <= 0) "" else ContentUris.withAppendedId(ALBUM_ART_BASE, albumId).toString()

    private fun suffixFrom(displayName: String?, mime: String?): String {
        displayName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length in 2..4 }?.let { return it.lowercase() }
        val m = mime?.lowercase() ?: return ""
        return when {
            m.contains("flac") -> "flac"
            m.contains("mpeg") || m.contains("mp3") -> "mp3"
            m.contains("aac") || m.contains("mp4") || m.contains("m4a") -> "m4a"
            m.contains("opus") -> "opus"
            m.contains("ogg") || m.contains("vorbis") -> "ogg"
            m.contains("wav") -> "wav"
            m.contains("aiff") || m.contains("aif") -> "aiff"
            else -> ""
        }
    }

    /** DB 行 → 内存（available 在前原序，unavailable 沉底；专辑/艺人只看可用行）。 */
    private suspend fun rebuildFromRows(rows: List<MsTrack>) {
        val avail = rows.filter { it.available }
        val un = rows.filterNot { it.available }
        val availSongs = avail.map { it.toSong(overlayGain = true) }
        val unSongs = un.map { it.toSong(overlayGain = true) }
        songs = availSongs + unSongs
        byId = songs.associateBy { it.id }
        matchIndex = availSongs.groupBy { TrackMatch.key(it.artist, it.title) }
        dirOf = availSongs.mapNotNull { s ->
            val p = s.path
            if (p.contains('/')) s.id to p.substringBeforeLast('/') else null
        }.toMap()
        folderRoot = commonDir(dirOf.values)
        val albumDateAdded = avail.groupBy { it.albumKey }.mapValues { (_, ts) -> ts.maxOf { it.dateAddedSec } }
        albums = avail.groupBy { it.albumKey }
            .map { (aid, ts) ->
                Album(
                    id = aid,
                    title = ts.first().album,
                    artist = ts.map { it.artist }.distinct().let { if (it.size == 1) it.first() else "Various artists" },
                    artworkUrl = ts.first().artworkUrl,
                    year = ts.firstOrNull { it.year > 0 }?.year ?: 0,
                    songCount = ts.size,
                    durationSec = ts.sumOf { it.durationSec },
                )
            }
            .sortedByDescending { albumDateAdded[it.id] ?: 0L }
        artists = availSongs.groupBy { it.artistId }
            .map { (aid, tracks) ->
                Artist(
                    id = aid,
                    name = tracks.first().artist,
                    imageUrl = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: "",
                    monthlyListeners = 0,
                )
            }
            .sortedBy { it.name.lowercase() }
        _albumMerges.value = dao.allMerges().associate { it.albumKey to parseMergeJson(it.rowsJson) }
    }

    private fun MsTrack.toSong(overlayGain: Boolean): Song {
        val rg = if (overlayGain && dataPath.isNotBlank()) gainProvider(dataPath) else null
        return Song(
            id = mediaId,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            durationSec = durationSec,
            accent = accentFor(mediaId),
            streamUrl = streamUrl,
            albumId = albumKey,
            artistId = artistId,
            suffix = suffix,
            bitrateKbps = bitrateKbps,
            path = dataPath,
            replayGainTrack = rg?.first ?: 0f,
            replayGainAlbum = rg?.second ?: 0f,
            genre = genre,
            composer = composer,
            discNumber = discNumber,
            trackNumber = trackNumber,
            dateAddedSec = dateAddedSec,
            available = available,
        )
    }

    /** 全量同步：查 MediaStore → 验封面 → 算合并 → 原子落盘 → 重建内存。 */
    private suspend fun syncLocked() = withContext(Dispatchers.IO) {
        val prevAvail = dao.allTracks().associate { it.mediaId to it.available }
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.COMPOSER,
            MediaStore.Audio.Media.TRACK,
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= 30) {
            cols.add(MediaStore.Audio.Media.BITRATE) // columns absent pre-30
            cols.add(MediaStore.Audio.Media.GENRE)
        }
        val projection = cols.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val rows = ArrayList<MsTrack>()
        val albumDateAdded = HashMap<String, Long>()
        val albumYear = HashMap<String, Int>()
        val albumArts = HashMap<String, String>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val artistIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val bitrateCol = c.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                val genreCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
                val composerCol = c.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
                val trackCol = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                // 非标准列：AOSP provider 没有，OEM 有就顺手读，没有返回 -1
                val discCol = c.getColumnIndex("disc_number")
                @Suppress("DEPRECATION") val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val albumId = c.getLong(albumIdCol)
                    val artistId = c.getLong(artistIdCol)
                    val title = c.getString(titleCol) ?: continue
                    val artistName = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                    val rawAlbum = c.getString(albumCol)?.takeIf { it.isNotBlank() }
                    val albumName = rawAlbum ?: "Unknown album"
                    val durSec = (c.getLong(durCol) / 1000L).toInt()
                    val year = runCatching { c.getInt(yearCol) }.getOrDefault(0)
                    val added = runCatching { c.getLong(addedCol) }.getOrDefault(0L)
                    val display = if (nameCol >= 0) c.getString(nameCol) else null
                    val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                    val suffix = suffixFrom(display, mime)
                    val bitrateKbps = if (bitrateCol >= 0) (runCatching { c.getInt(bitrateCol) }.getOrDefault(0) / 1000) else 0
                    val art = albumArtUri(albumId)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val data = if (dataCol >= 0) c.getString(dataCol).orEmpty() else ""
                    // 专辑键只看归一化标题：MediaStore 的 album_id 按艺人维度拆分，
                    // 同名专辑跨文件夹/跨艺人会被拆成多个 id，这里直接无视它；
                    // 无专辑标签的每首独立成专（unique key），绝不合并
                    val sidAlbum = if (rawAlbum == null) "unknown-album::$id" else albumKey(albumName)
                    val rawTrack = if (trackCol >= 0) runCatching { c.getInt(trackCol) }.getOrDefault(0) else 0
                    var discNo = if (discCol >= 0) runCatching { c.getInt(discCol) }.getOrDefault(0) else 0
                    var trackNo = rawTrack
                    // 部分实现把 disc 打包进 TRACK 高位（disc * 1000 + track）
                    if (discNo == 0 && trackNo >= 1000) {
                        discNo = trackNo / 1000
                        trackNo %= 1000
                    }
                    if (added > (albumDateAdded[sidAlbum] ?: 0L)) albumDateAdded[sidAlbum] = added
                    if (year > 0 && albumYear[sidAlbum] == null) albumYear[sidAlbum] = year
                    rows += MsTrack(
                        mediaId = id.toString(),
                        title = title,
                        artist = artistName,
                        artistId = artistId.toString(),
                        album = albumName,
                        albumKey = sidAlbum,
                        durationSec = durSec,
                        year = year,
                        dateAddedSec = added,
                        displayName = display.orEmpty(),
                        mime = mime.orEmpty(),
                        suffix = suffix,
                        bitrateKbps = bitrateKbps,
                        genre = if (genreCol >= 0) c.getString(genreCol).orEmpty() else "",
                        composer = if (composerCol >= 0) c.getString(composerCol).orEmpty() else "",
                        discNumber = discNo,
                        trackNumber = trackNo,
                        dataPath = data,
                        streamUrl = uri,
                        artworkUrl = art,
                        // 跨同步保留已标 unavailable（后台检查的结果不被重同步洗掉）
                        available = prevAvail[id.toString()] ?: true,
                    )
                }
            }
        }
        // 封面：有图的曲子里随机一张，种子固定保证每次结果一致；
        // URI 非空不等于能解出来，逐个验链（IO 线程），第一张通的留下
        val covers = rows.groupBy { it.albumKey }.mapValues { (aid, ts) ->
            ts.filter { it.artworkUrl.isNotBlank() }
                .shuffled(kotlin.random.Random(aid.hashCode()))
                .firstOrNull { probeArt(it.artworkUrl) }?.artworkUrl.orEmpty()
        }
        rows.replaceAll { it.copy(artworkUrl = covers[it.albumKey].orEmpty()) }
        val albums = rows.groupBy { it.albumKey }.map { (aid, ts) ->
            MsAlbum(
                albumKey = aid,
                title = ts.first().album,
                artist = ts.map { it.artist }.distinct().let { if (it.size == 1) it.first() else "Various artists" },
                artworkUrl = covers[aid].orEmpty(),
                year = albumYear[aid] ?: 0,
                songCount = ts.size,
                durationSec = ts.sumOf { it.durationSec },
                dateAddedSec = albumDateAdded[aid] ?: 0L,
            )
        }
        // 标题合并表：同一份标准序上一次算好，UI 查表即可不再分词
        val memSongs = rows.map { it.toSong(overlayGain = true) }
        val merges = albums.map { a ->
            val ordered = memSongs.filter { it.albumId == a.albumKey }.sortedWith(ALBUM_TRACK_ORDER)
            MsMerge(a.albumKey, buildMergeJson(ordered))
        }
        dao.replaceAll(rows, albums, merges)
        rebuildFromRows(rows)
        _albumMerges.value = merges.associate { it.albumKey to parseMergeJson(it.rowsJson) }
    }

    private companion object {
        val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

        /** 跨文件夹/跨艺人同名即同专辑（代价：标题撞名的不同专辑会被并到一起）。 */
        fun albumKey(title: String): String = "album::" + title.trim().lowercase()

        /** 碟号 → 曲号 → 标题 → 文件名；缺号沉底。 */
        val ALBUM_TRACK_ORDER: Comparator<Song> =
            compareBy<Song> { if (it.discNumber == 0) Int.MAX_VALUE else it.discNumber }
                .thenBy { if (it.trackNumber == 0) Int.MAX_VALUE else it.trackNumber }
                .thenBy { it.title.lowercase() }
                .thenBy { it.path.substringAfterLast('/').lowercase() }
    }
}
