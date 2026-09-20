package com.aurora.music.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import com.aurora.music.util.accentFor
import com.aurora.titlemerge.MergeInput
import com.aurora.titlemerge.MergedRow
import com.aurora.titlemerge.mergeTracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

class LocalLibrary(
    private val context: Context,
    // scanned replaygain overlaid by path since mediastore tags rarely carry it
    private val gainProvider: (String) -> Pair<Float, Float>? = { null },
) {

    @Volatile private var loaded = false
    private val mutex = Mutex()

    @Volatile var songs: List<Song> = emptyList(); private set
    @Volatile var albums: List<Album> = emptyList(); private set
    @Volatile var artists: List<Artist> = emptyList(); private set
    private var byId: Map<String, Song> = emptyMap()

    @Volatile private var matchIndex: Map<String, List<Song>> = emptyMap()

    // 扫描时预计算的每专辑标题合并表（后台线程一次算好，UI 只查表）。
    // 顺序与 albumTracksSorted 完全一致，index 可直接对上。
    private val _albumMerges = MutableStateFlow<Map<String, List<MergedRow>>>(emptyMap())
    val albumMerges: StateFlow<Map<String, List<MergedRow>>> = _albumMerges.asStateFlow()

    @Volatile private var dirOf: Map<String, String> = emptyMap()

    @Volatile var folderRoot: String = ""; private set

    // Classical fork v0.3 (plan §6): when the user configured scan roots, only
    // tracks inside those subtrees enter the library. Null = unscoped (legacy).
    // Takes effect on the next scan()/refresh().
    @Volatile var scopeFilter: ((Song) -> Boolean)? = null

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (!loaded) { scan(); loaded = true }
        }
    }

    suspend fun refresh() {
        mutex.withLock { scan(); loaded = true }
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
        val here = songs.filter { dirOf[it.id] == base }.sortedBy { it.title.lowercase() }
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

    private suspend fun scan() = withContext(Dispatchers.IO) {
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
        val out = ArrayList<Song>()
        val albumDateAdded = HashMap<String, Long>()
        val albumYear = HashMap<String, Int>()
        val dirs = HashMap<String, String>()
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
                    val albumName = c.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "Unknown album"
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
                    if (data.contains('/')) dirs[id.toString()] = data.substringBeforeLast('/')
                    val rg = if (data.isNotBlank()) gainProvider(data) else null
                    // 专辑键只看归一化标题：MediaStore 的 album_id 按艺人维度拆分，
                    // 同名专辑跨文件夹/跨艺人会被拆成多个 id，这里直接无视它
                    val sidAlbum = albumKey(albumName)
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
                    out += Song(
                        id = id.toString(),
                        title = title,
                        artist = artistName,
                        album = albumName,
                        artworkUrl = art,
                        durationSec = durSec,
                        accent = accentFor(id.toString()),
                        streamUrl = uri,
                        albumId = sidAlbum,
                        artistId = artistId.toString(),
                        suffix = suffix,
                        bitrateKbps = bitrateKbps,
                        path = data,
                        replayGainTrack = rg?.first ?: 0f,
                        replayGainAlbum = rg?.second ?: 0f,
                        genre = if (genreCol >= 0) c.getString(genreCol).orEmpty() else "",
                        composer = if (composerCol >= 0) c.getString(composerCol).orEmpty() else "",
                        discNumber = discNo,
                        trackNumber = trackNo,
                        dateAddedSec = added,
                    )
                }
            }
        }
        val scoped = scopeFilter?.let { f -> out.filter(f) } ?: out
        val scopedIds = scoped.map { it.id }.toSet()
        songs = scoped
        byId = scoped.associateBy { it.id }
        matchIndex = scoped.groupBy { TrackMatch.key(it.artist, it.title) }
        dirOf = dirs.filterKeys { it in scopedIds }
        folderRoot = commonDir(dirOf.values)
        albums = scoped.groupBy { it.albumId }
            .map { (aid, tracks) ->
                val f = tracks.first()
                // 封面：有图的曲子里随机一张，种子固定保证每次扫描结果一致；
                // URI 非空不等于能解出来，逐个验链（IO 线程），第一张通的留下，
                // 全不通就空着走默认图，不留注定失败的请求
                val candidates = tracks.filter { it.artworkUrl.isNotBlank() }
                    .shuffled(kotlin.random.Random(aid.hashCode()))
                val cover = candidates.firstOrNull { probeArt(it.artworkUrl) }?.artworkUrl.orEmpty()
                Album(
                    id = aid,
                    title = f.album,
                    artist = tracks.map { it.artist }.distinct().let { if (it.size == 1) it.first() else "Various artists" },
                    artworkUrl = cover,
                    year = albumYear[aid] ?: 0,
                    songCount = tracks.size,
                    durationSec = tracks.sumOf { it.durationSec },
                )
            }
            .sortedByDescending { albumDateAdded[it.id] ?: 0L }
        artists = scoped.groupBy { it.artistId }
            .map { (aid, tracks) ->
                Artist(
                    id = aid,
                    name = tracks.first().artist,
                    imageUrl = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: "",
                    monthlyListeners = 0,
                )
            }
            .sortedBy { it.name.lowercase() }
        // 标题合并表：同一份标准序上一次算好，UI 查表即可不再分词
        _albumMerges.value = albums.associate { a ->
            val ordered = songsByAlbumId(a.id).sortedWith(ALBUM_TRACK_ORDER)
            a.id to mergeTracks(ordered.map { MergeInput(it.id, it.title) })
        }
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
