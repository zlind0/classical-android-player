package com.aurora.music.data.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 用户显式下载的远程资料库条目类型。 */
object RemoteDownloadKind {
    const val TRACK = "track"
    const val ALBUM = "album"
    const val ARTIST = "artist"
    const val EBOOK = "ebook"
    const val EBOOK_FOLDER = "ebookFolder"
    const val EBOOK_ROOT = "ebookRoot"
    const val AUDIOBOOK = "audiobook"
}

enum class RemoteDownloadStatus { IDLE, DOWNLOADING, DOWNLOADED }

data class RemoteDownloadUi(
    val status: RemoteDownloadStatus = RemoteDownloadStatus.IDLE,
    val progress: Float = 0f,
)

/**
 * 单曲在下载记录里的快照。播放时优先使用 [localPath]，这样远端设备离线也能播。
 * 快照保存的是“用户下载那一刻”的元信息，远端后来删除/改名也不影响本地展示。
 */
data class RemoteTrackSnapshot(
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
    val artistId: String = "",
    val durationSec: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val mergeTitles: Boolean = true,
    val musicPathOrder: Boolean = false,
    val remoteUrl: String = "",
    val localPath: String = "",
    val artworkUrl: String = "",
    val suffix: String = "",
    val albumGroupId: String = "",
    val albumGroupTitle: String = "",
)

/** 一条完整下载记录（歌曲/专辑/歌手/电子书/电子书目录/有声书）。 */
data class RemoteDownloadRecord(
    val kind: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val key: String = "",
    val title: String = "",
    val subtitle: String = "",
    val cover: String = "",
    val createdAt: Long = 0L,
    val tracks: List<RemoteTrackSnapshot> = emptyList(),
    val ebook: RemoteEbookWire? = null,
    val ebookRoot: RemoteEbookRootWire? = null,
    val ebooks: List<RemoteEbookWire> = emptyList(),
    val audiobook: RemoteAudiobookWire? = null,
    val ebookLocalPath: String = "",
    val ebookFiles: Map<String, String> = emptyMap(),
    val ebookCovers: Map<String, String> = emptyMap(),
    val chapterFiles: Map<String, String> = emptyMap(),
    val files: List<String> = emptyList(),
)

/**
 * 远程资料库“离线下载”管理器。
 *
 * 与 [RemoteMediaCache] 的边播边缓存不同：这里的文件是用户明确点“下载”后保存的，
 * 不会被播放窗口清理；同时保存条目快照，远端设备离线或远端条目消失后仍能在原设备
 * 页面下展示，直到用户左滑删除。
 */
class RemoteDownloadManager(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _records = MutableStateFlow<List<RemoteDownloadRecord>>(emptyList())
    val records: StateFlow<List<RemoteDownloadRecord>> = _records.asStateFlow()

    private val _ui = MutableStateFlow<Map<String, RemoteDownloadUi>>(emptyMap())
    val ui: StateFlow<Map<String, RemoteDownloadUi>> = _ui.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeProgress = ConcurrentHashMap<String, Float>()
    private val activeChildren = ConcurrentHashMap<String, List<String>>()

    private val rootDir: File
        get() = File(appContext.filesDir, "remote_downloads").apply { if (!isDirectory) runCatching { mkdirs() } }

    private val indexFile: File
        get() = File(rootDir, "index.json")

    init {
        scope.launch {
            load()
            rebuildUi()
        }
    }

    // ---- 查询 ----

    fun stateKey(kind: String, deviceId: String, key: String): String = "$kind|$deviceId|$key"

    fun uiStateFor(kind: String, deviceId: String, key: String): RemoteDownloadUi =
        _ui.value[stateKey(kind, deviceId, key)] ?: RemoteDownloadUi()

    fun recordsFor(deviceId: String): List<RemoteDownloadRecord> =
        _records.value.filter { it.deviceId == deviceId }

    fun hasDownloads(deviceId: String): Boolean = _records.value.any { it.deviceId == deviceId }

    fun deviceIdsWithDownloads(): Set<String> = _records.value.map { it.deviceId }.toSet()

    fun deviceNameOf(deviceId: String): String? =
        _records.value.firstOrNull { it.deviceId == deviceId }?.deviceName?.takeIf { it.isNotBlank() }

    fun localSongPath(deviceId: String, songId: String): String? {
        _records.value.asSequence()
            .filter { it.deviceId == deviceId }
            .flatMap { it.tracks.asSequence() }
            .firstOrNull { it.songId == songId && it.localPath.isNotBlank() }
            ?.localPath
            ?.takeIf { File(it).isFile }
            ?.let { return it }
        return null
    }

    fun localEbookPath(deviceId: String, path: String): String? {
        for (r in _records.value) {
            if (r.deviceId != deviceId) continue
            if (r.kind == RemoteDownloadKind.EBOOK && r.key == path && r.ebookLocalPath.isNotBlank()) {
                if (File(r.ebookLocalPath).isFile) return r.ebookLocalPath
            }
            r.ebookFiles[path]?.takeIf { File(it).isFile }?.let { return it }
        }
        return null
    }

    /** 这本书是否已经随单本/目录/根目录下载过，避免重复占盘。 */
    fun isEbookDownloaded(deviceId: String, path: String): Boolean = _records.value.any { rec ->
        rec.deviceId == deviceId && when (rec.kind) {
            RemoteDownloadKind.EBOOK -> rec.key == path && rec.ebookLocalPath.isNotBlank()
            RemoteDownloadKind.EBOOK_FOLDER,
            RemoteDownloadKind.EBOOK_ROOT,
            -> rec.ebookFiles.containsKey(path)
            else -> false
        }
    }

    fun localChapterPath(deviceId: String, path: String): String? {
        for (r in _records.value) {
            if (r.deviceId != deviceId) continue
            r.chapterFiles[path]?.takeIf { File(it).isFile }?.let { return it }
        }
        return null
    }

    fun ebookCover(deviceId: String, path: String): String? {
        for (r in _records.value) {
            if (r.deviceId != deviceId) continue
            if (r.kind == RemoteDownloadKind.EBOOK && r.key == path && r.cover.isNotBlank()) return r.cover
            r.ebookCovers[path]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun audiobookCover(deviceId: String, bookId: String): String? =
        _records.value.firstOrNull {
            it.deviceId == deviceId && it.kind == RemoteDownloadKind.AUDIOBOOK && it.key == bookId
        }?.cover?.takeIf { it.isNotBlank() }

    fun artistCovers(deviceId: String): Map<String, String> =
        _records.value.filter {
            it.deviceId == deviceId && it.kind == RemoteDownloadKind.ARTIST && it.cover.isNotBlank()
        }.associate { it.key to it.cover }

    // ---- 下载入口 ----

    fun downloadSong(deviceId: String, deviceName: String, song: Song) {
        if (!song.streamUrl.startsWith("http")) return
        val groupKey = song.albumId.ifBlank { "${song.artist}|${song.album}" }
        val groupId = "remote-album:$deviceId:$groupKey"
        val snapshot = snapshotOf(song, groupId, song.album, true)
        val record = RemoteDownloadRecord(
            kind = RemoteDownloadKind.TRACK,
            deviceId = deviceId,
            deviceName = deviceName,
            key = song.id,
            title = song.title,
            subtitle = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" — "),
            cover = snapshot.artworkUrl,
            createdAt = System.currentTimeMillis(),
            tracks = listOf(snapshot),
        )
        val dir = recordDir(record)
        val units = buildList {
            if (song.artworkUrl.startsWith("http")) {
                add(DownloadUnit(song.artworkUrl, File(dir, "cover.img"), 0L) { rec, path ->
                    rec.copy(cover = fileUri(path), tracks = rec.tracks.map { it.copy(artworkUrl = fileUri(path)) })
                })
            }
            add(
                DownloadUnit(song.streamUrl, File(dir, "track.${audioExt(song)}"), 0L) { rec, path ->
                    rec.copy(tracks = rec.tracks.map { it.copy(localPath = path) })
                },
            )
        }
        enqueue(record, units, listOf(stateKey(RemoteDownloadKind.TRACK, deviceId, song.id)))
    }

    fun downloadAlbum(deviceId: String, deviceName: String, album: RemoteAlbumGroup) {
        val songs = album.songs.filter { it.streamUrl.startsWith("http") }
        if (songs.isEmpty()) return
        val record = RemoteDownloadRecord(
            kind = RemoteDownloadKind.ALBUM,
            deviceId = deviceId,
            deviceName = deviceName,
            key = album.id,
            title = album.title,
            subtitle = album.artist,
            cover = album.artworkUrl,
            createdAt = System.currentTimeMillis(),
            tracks = songs.map { snapshotOf(it, album.id, album.title, album.mergeTitles) },
        )
        val dir = recordDir(record)
        val units = buildList {
            if (album.artworkUrl.startsWith("http")) {
                add(DownloadUnit(album.artworkUrl, File(dir, "cover.img"), 0L) { rec, path ->
                    rec.copy(
                        cover = fileUri(path),
                        tracks = rec.tracks.map { it.copy(artworkUrl = fileUri(path)) },
                    )
                })
            }
            songs.forEachIndexed { i, song ->
                add(
                    DownloadUnit(song.streamUrl, File(dir, "track_$i.${audioExt(song)}"), 0L) { rec, path ->
                        rec.copy(
                            tracks = rec.tracks.mapIndexed { idx, t ->
                                if (idx == i) {
                                    t.copy(localPath = path, artworkUrl = rec.cover.ifBlank { t.artworkUrl })
                                } else t
                            },
                        )
                    },
                )
            }
        }
        enqueue(record, units, songs.map { stateKey(RemoteDownloadKind.TRACK, deviceId, it.id) })
    }

    fun downloadArtist(deviceId: String, deviceName: String, artist: RemoteArtistGroup) {
        val songs = artist.songs.filter { it.streamUrl.startsWith("http") }
        if (songs.isEmpty()) return
        val record = RemoteDownloadRecord(
            kind = RemoteDownloadKind.ARTIST,
            deviceId = deviceId,
            deviceName = deviceName,
            key = artist.name,
            title = artist.name,
            subtitle = "${songs.size} 首",
            cover = artist.imageUrl,
            createdAt = System.currentTimeMillis(),
            tracks = songs.map { snapshotOf(it, albumGroupIdOf(deviceId, it), it.album, true) },
        )
        val dir = recordDir(record)
        val units = buildList {
            if (artist.imageUrl.startsWith("http")) {
                add(DownloadUnit(artist.imageUrl, File(dir, "cover.img"), 0L) { rec, path ->
                    rec.copy(
                        cover = fileUri(path),
                        tracks = rec.tracks.map { it.copy(artworkUrl = fileUri(path)) },
                    )
                })
            }
            songs.forEachIndexed { i, song ->
                add(
                    DownloadUnit(song.streamUrl, File(dir, "track_$i.${audioExt(song)}"), 0L) { rec, path ->
                        rec.copy(
                            tracks = rec.tracks.mapIndexed { idx, t ->
                                if (idx == i) {
                                    t.copy(localPath = path, artworkUrl = rec.cover.ifBlank { t.artworkUrl })
                                } else t
                            },
                        )
                    },
                )
            }
        }
        enqueue(record, units, songs.map { stateKey(RemoteDownloadKind.TRACK, deviceId, it.id) })
    }

    fun downloadEbook(
        deviceId: String,
        deviceName: String,
        baseUrl: String,
        ebook: RemoteEbookWire,
        coverUrl: String,
        root: RemoteEbookRootWire? = null,
    ) {
        if (isEbookDownloaded(deviceId, ebook.path)) return
        val record = RemoteDownloadRecord(
            kind = RemoteDownloadKind.EBOOK,
            deviceId = deviceId,
            deviceName = deviceName,
            key = ebook.path,
            title = ebook.title,
            subtitle = ebook.author,
            cover = coverUrl,
            createdAt = System.currentTimeMillis(),
            ebook = ebook,
            ebookRoot = root,
        )
        val dir = recordDir(record)
        val units = buildList {
            if (coverUrl.startsWith("http")) {
                add(DownloadUnit(coverUrl, File(dir, "cover.img"), 0L) { rec, path ->
                    rec.copy(cover = fileUri(path), ebookCovers = rec.ebookCovers + (ebook.path to fileUri(path)))
                })
            }
            add(
                DownloadUnit(
                    "$baseUrl/media/ebook?path=${enc(ebook.path)}",
                    File(dir, "ebook.${ebookExt(ebook.path)}"),
                    ebook.size,
                ) { rec, path -> rec.copy(ebookLocalPath = path) },
            )
        }
        enqueue(record, units, listOf(stateKey(RemoteDownloadKind.EBOOK, deviceId, ebook.path)))
    }

    fun downloadEbookFolder(
        deviceId: String,
        deviceName: String,
        baseUrl: String,
        root: RemoteEbookRootWire,
        dirPath: String,
        title: String,
        books: List<RemoteEbookWire>,
    ) {
        val pending = books.filterNot { isEbookDownloaded(deviceId, it.path) }
        if (pending.isEmpty()) return
        val key = "${root.key}|$dirPath"
        val record = RemoteDownloadRecord(
            kind = RemoteDownloadKind.EBOOK_FOLDER,
            deviceId = deviceId,
            deviceName = deviceName,
            key = key,
            title = title.ifBlank { root.displayName.ifBlank { "电子书" } },
            subtitle = "${pending.size} 本",
            createdAt = System.currentTimeMillis(),
            ebookRoot = root,
            ebooks = pending,
        )
        val dir = recordDir(record)
        val units = buildList {
            pending.forEachIndexed { i, book ->
                add(
                    DownloadUnit(
                        "$baseUrl/media/ebook?path=${enc(book.path)}",
                        File(dir, "book_$i.${ebookExt(book.path)}"),
                        book.size,
                    ) { rec, path -> rec.copy(ebookFiles = rec.ebookFiles + (book.path to path)) },
                )
                if (book.hasCover) {
                    add(
                        DownloadUnit(
                            "$baseUrl/media/cover?kind=ebook&key=${enc(book.path)}",
                            File(dir, "cover_$i.img"),
                            0L,
                        ) { rec, path ->
                            rec.copy(ebookCovers = rec.ebookCovers + (book.path to fileUri(path)))
                        },
                    )
                }
            }
        }
        enqueue(record, units, pending.map { stateKey(RemoteDownloadKind.EBOOK, deviceId, it.path) })
    }

    fun downloadEbookRoot(
        deviceId: String,
        deviceName: String,
        baseUrl: String,
        root: RemoteEbookRootWire,
        books: List<RemoteEbookWire>,
    ) {
        val pending = books.filterNot { isEbookDownloaded(deviceId, it.path) }
        if (pending.isEmpty()) return
        val record = RemoteDownloadRecord(
            kind = RemoteDownloadKind.EBOOK_ROOT,
            deviceId = deviceId,
            deviceName = deviceName,
            key = root.key,
            title = root.displayName.ifBlank { "电子书" },
            subtitle = "${pending.size} 本",
            createdAt = System.currentTimeMillis(),
            ebookRoot = root,
            ebooks = pending,
        )
        val dir = recordDir(record)
        val units = buildList {
            pending.forEachIndexed { i, book ->
                add(
                    DownloadUnit(
                        "$baseUrl/media/ebook?path=${enc(book.path)}",
                        File(dir, "book_$i.${ebookExt(book.path)}"),
                        book.size,
                    ) { rec, path -> rec.copy(ebookFiles = rec.ebookFiles + (book.path to path)) },
                )
                if (book.hasCover) {
                    add(
                        DownloadUnit(
                            "$baseUrl/media/cover?kind=ebook&key=${enc(book.path)}",
                            File(dir, "cover_$i.img"),
                            0L,
                        ) { rec, path ->
                            rec.copy(ebookCovers = rec.ebookCovers + (book.path to fileUri(path)))
                        },
                    )
                }
            }
        }
        enqueue(record, units, pending.map { stateKey(RemoteDownloadKind.EBOOK, deviceId, it.path) })
    }

    fun downloadAudiobook(deviceId: String, deviceName: String, baseUrl: String, book: RemoteAudiobookWire) {
        if (book.chapters.isEmpty()) return
        val record = RemoteDownloadRecord(
            kind = RemoteDownloadKind.AUDIOBOOK,
            deviceId = deviceId,
            deviceName = deviceName,
            key = book.id,
            title = book.title,
            subtitle = "${book.chapters.size} 章",
            createdAt = System.currentTimeMillis(),
            audiobook = book,
        )
        val dir = recordDir(record)
        val units = buildList {
            if (book.hasCover) {
                add(
                    DownloadUnit(
                        "$baseUrl/media/cover?kind=audiobook&key=${enc(book.id)}",
                        File(dir, "cover.img"),
                        0L,
                    ) { rec, path -> rec.copy(cover = fileUri(path)) },
                )
            }
            book.chapters.forEachIndexed { i, ch ->
                add(
                    DownloadUnit(
                        "$baseUrl/media/audiobook?path=${enc(ch.path)}",
                        File(dir, "chapter_$i.${audioExt(ch.path)}"),
                        ch.size,
                    ) { rec, path -> rec.copy(chapterFiles = rec.chapterFiles + (ch.path to path)) },
                )
            }
        }
        enqueue(record, units, listOf(stateKey(RemoteDownloadKind.AUDIOBOOK, deviceId, book.id)))
    }

    // ---- 删除 ----

    fun delete(kind: String, deviceId: String, key: String) {
        val activeKey = stateKey(kind, deviceId, key)
        activeJobs.remove(activeKey)?.cancel()
        activeProgress.remove(activeKey)
        activeChildren.remove(activeKey)

        val updated = when (kind) {
            RemoteDownloadKind.TRACK -> deleteTrack(deviceId, key)
            RemoteDownloadKind.EBOOK -> deleteEbook(deviceId, key)
            RemoteDownloadKind.EBOOK_FOLDER -> deleteEbookFolder(deviceId, key)
            else -> _records.value.filterNot {
                it.kind == kind && it.deviceId == deviceId && it.key == key
            }.also {
                _records.value.firstOrNull { r ->
                    r.kind == kind && r.deviceId == deviceId && r.key == key
                }?.let { r -> deleteRecordFiles(r) }
            }
        }
        _records.value = updated
        persist()
        rebuildUi()
    }

    private fun deleteTrack(deviceId: String, songId: String): List<RemoteDownloadRecord> {
        val out = mutableListOf<RemoteDownloadRecord>()
        _records.value.forEach { rec ->
            if (rec.deviceId != deviceId || rec.tracks.none { it.songId == songId }) {
                out += rec
                return@forEach
            }
            if (rec.kind == RemoteDownloadKind.TRACK && rec.key == songId) {
                deleteRecordFiles(rec)
                return@forEach
            }
            val removed = rec.tracks.filter { it.songId == songId }
            removed.forEach { t -> if (t.localPath.isNotBlank()) runCatching { File(t.localPath).delete() } }
            val kept = rec.tracks.filterNot { it.songId == songId }
            if (kept.isEmpty()) {
                deleteRecordFiles(rec)
                return@forEach
            }
            out += rec.copy(tracks = kept)
        }
        return out
    }

    private fun deleteEbook(deviceId: String, path: String): List<RemoteDownloadRecord> =
        deleteEbookFrom(_records.value, deviceId, path)

    private fun deleteEbookFrom(
        records: List<RemoteDownloadRecord>,
        deviceId: String,
        path: String,
    ): List<RemoteDownloadRecord> {
        val out = mutableListOf<RemoteDownloadRecord>()
        records.forEach { rec ->
            if (rec.deviceId != deviceId) {
                out += rec
                return@forEach
            }
            if (rec.kind == RemoteDownloadKind.EBOOK && rec.key == path) {
                deleteRecordFiles(rec)
                return@forEach
            }
            val contained = rec.ebooks.any { it.path == path } || rec.ebookFiles.containsKey(path)
            if (!contained) {
                out += rec
                return@forEach
            }
            rec.ebookFiles[path]?.let { runCatching { File(it).delete() } }
            val keptBooks = rec.ebooks.filterNot { it.path == path }
            if (keptBooks.isEmpty()) {
                deleteRecordFiles(rec)
                return@forEach
            }
            out += rec.copy(
                ebooks = keptBooks,
                ebookFiles = rec.ebookFiles - path,
                ebookCovers = rec.ebookCovers - path,
            )
        }
        return out
    }

    /**
     * 目录的“已下载”状态是从目录内已下载的书推导出来的，可能没有对应的文件夹记录；
     * 删除时把该目录下所有已下载的书（含子目录和根目录记录里的书）一起清掉。
     */
    private fun deleteEbookFolder(deviceId: String, key: String): List<RemoteDownloadRecord> {
        val rootKey = key.substringBefore('|')
        val dirPath = key.substringAfter('|', "")
        if (dirPath.isBlank()) return _records.value
        val folderPrefix = "${dirPath.trimEnd('/')}/"

        val folderRecords = _records.value.filter {
            it.deviceId == deviceId && it.kind == RemoteDownloadKind.EBOOK_FOLDER &&
                (it.key == key || it.key.startsWith("$rootKey|$folderPrefix"))
        }
        folderRecords.forEach { deleteRecordFiles(it) }
        var records = _records.value.filterNot { it in folderRecords }

        val bookPaths = records.asSequence()
            .filter { it.deviceId == deviceId }
            .flatMap { rec ->
                sequenceOf(rec.ebook?.path.orEmpty()) +
                    rec.ebooks.asSequence().map { it.path } +
                    rec.ebookFiles.keys.asSequence()
            }
            .filter { it.isNotBlank() && (it == dirPath || it.startsWith(folderPrefix)) }
            .toSet()
        bookPaths.forEach { path -> records = deleteEbookFrom(records, deviceId, path) }
        return records
    }

    private fun deleteRecordFiles(rec: RemoteDownloadRecord) {
        rec.files.forEach { p -> runCatching { File(p).delete() } }
        runCatching { recordDir(rec).deleteRecursively() }
    }

    // ---- 下载执行 ----

    private data class DownloadUnit(
        val url: String,
        val dest: File,
        val expectedBytes: Long,
        val apply: (RemoteDownloadRecord, String) -> RemoteDownloadRecord,
    )

    private fun enqueue(record: RemoteDownloadRecord, units: List<DownloadUnit>, childKeys: List<String>) {
        if (units.isEmpty()) return
        val k = stateKey(record.kind, record.deviceId, record.key)
        if (_records.value.any { stateKey(it.kind, it.deviceId, it.key) == k }) return
        if (activeJobs.containsKey(k)) return
        activeProgress[k] = 0f
        activeChildren[k] = childKeys
        publishProgress(k, childKeys, 0f)

        val job = scope.launch {
            var rec = record
            try {
                withContext(Dispatchers.IO) {
                    var baseDone = 0f
                    units.forEach { unit ->
                        val progressed: (Long, Long) -> Unit = { read, total ->
                            val frac = if (total > 0L) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                            publishProgress(k, childKeys, ((baseDone + frac) / units.size).coerceIn(0f, 1f))
                        }
                        val local = unit.dest
                        val ok = if (local.isFile && local.length() > 0L) {
                            true
                        } else {
                            fetch(unit.url, local, unit.expectedBytes, progressed)
                        }
                        if (!ok) error("download failed: ${unit.url}")
                        rec = unit.apply(rec, local.absolutePath)
                        if (local.absolutePath !in rec.files) rec = rec.copy(files = rec.files + local.absolutePath)
                        baseDone += 1f
                        publishProgress(k, childKeys, (baseDone / units.size).coerceIn(0f, 1f))
                    }
                }
                _records.value = (_records.value.filterNot {
                    it.kind == rec.kind && it.deviceId == rec.deviceId && it.key == rec.key
                } + rec).sortedBy { it.createdAt }
                persist()
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.w("AuroraRemoteDownload", "download failed: ${record.key}", t)
                }
            } finally {
                activeJobs.remove(k)
                activeProgress.remove(k)
                activeChildren.remove(k)
                rebuildUi()
            }
        }
        activeJobs[k] = job
    }

    private fun fetch(
        url: String,
        dest: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Boolean = runCatching {
        dest.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val tmp = File(dest.parentFile, dest.name + ".part")
        val req = Request.Builder()
            .url(url)
            .header("X-Aurora-Device", "1")
            .get()
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use false
            val body = r.body ?: return@use false
            val total = body.contentLength().takeIf { it > 0L } ?: expectedBytes
            val input = body.byteStream()
            tmp.outputStream().use { out ->
                val buf = ByteArray(128 * 1024)
                var readTotal = 0L
                var lastUpdate = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buf, 0, n)
                    readTotal += n
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 120L) {
                        lastUpdate = now
                        onProgress(readTotal, total)
                    }
                }
                out.flush()
            }
            true
        }.also { ok ->
            if (ok && tmp.isFile && tmp.length() > 0L) {
                if (!tmp.renameTo(dest)) {
                    runCatching { tmp.copyTo(dest, overwrite = true) }
                    runCatching { tmp.delete() }
                }
            } else {
                runCatching { tmp.delete() }
            }
        }
    }.getOrDefault(false)

    private fun publishProgress(parentKey: String, childKeys: List<String>, progress: Float) {
        activeProgress[parentKey] = progress
        val p = progress.coerceIn(0f, 1f)
        _ui.update { current ->
            val next = current.toMutableMap()
            next[parentKey] = RemoteDownloadUi(RemoteDownloadStatus.DOWNLOADING, p)
            childKeys.forEach { next[it] = RemoteDownloadUi(RemoteDownloadStatus.DOWNLOADING, p) }
            next
        }
    }

    // ---- 持久化 ----

    private fun load() {
        runCatching {
            if (!indexFile.isFile) return@runCatching
            val type = object : TypeToken<List<RemoteDownloadRecord>>() {}.type
            val list = gson.fromJson<List<RemoteDownloadRecord>>(indexFile.readText(), type).orEmpty()
            _records.value = list.filter { it.deviceId.isNotBlank() }
        }
    }

    private fun persist() {
        runCatching {
            val tmp = File(rootDir, "index.json.part")
            tmp.writeText(gson.toJson(_records.value))
            if (!tmp.renameTo(indexFile)) {
                runCatching { tmp.copyTo(indexFile, overwrite = true) }
                runCatching { tmp.delete() }
            }
        }
    }

    private fun rebuildUi() {
        val next = HashMap<String, RemoteDownloadUi>()
        _records.value.forEach { rec ->
            next[stateKey(rec.kind, rec.deviceId, rec.key)] = RemoteDownloadUi(RemoteDownloadStatus.DOWNLOADED, 1f)
            childKeysOf(rec).forEach { next[it] = RemoteDownloadUi(RemoteDownloadStatus.DOWNLOADED, 1f) }
        }
        // 目录本身可能没有下载记录（用户下的是目录里的书，或下的是整个根目录），
        // 这里按已下载的书反推路径上的每一级目录，让目录行也显示已下载勾。
        _records.value.forEach { rec ->
            if (rec.deviceId.isBlank()) return@forEach
            val root = rec.ebookRoot ?: return@forEach
            val paths = buildList {
                rec.ebook?.let { add(it.path) }
                rec.ebooks.forEach { add(it.path) }
                addAll(rec.ebookFiles.keys)
            }
            val rootPath = root.rootPath.trimEnd('/')
            paths.forEach { path ->
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotBlank()) {
                    if (rootPath.isNotBlank() && parent.length <= rootPath.length) break
                    next[stateKey(RemoteDownloadKind.EBOOK_FOLDER, rec.deviceId, "${root.key}|$parent")] =
                        RemoteDownloadUi(RemoteDownloadStatus.DOWNLOADED, 1f)
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }
        activeChildren.forEach { (parent, children) ->
            val p = activeProgress[parent] ?: 0f
            next[parent] = RemoteDownloadUi(RemoteDownloadStatus.DOWNLOADING, p)
            children.forEach { next[it] = RemoteDownloadUi(RemoteDownloadStatus.DOWNLOADING, p) }
        }
        _ui.value = next
    }

    // ---- 快照 -> 可展示模型 ----

    /** 某个设备上所有已下载曲目，按记录顺序去重。 */
    fun downloadedSongs(deviceId: String): List<Song> {
        val seen = HashSet<String>()
        val out = ArrayList<Song>()
        _records.value.asSequence()
            .filter { it.deviceId == deviceId && it.kind in MUSIC_KINDS }
            .flatMap { it.tracks.asSequence() }
            .forEach { t ->
                if (t.songId.isNotBlank() && seen.add(t.songId)) out += t.toSong()
            }
        return out
    }

    /** 某设备所有已下载曲目按专辑聚合；只用于补上远端已不存在的专辑。 */
    fun downloadedAlbums(deviceId: String): List<RemoteAlbumGroup> {
        val groups = LinkedHashMap<String, MutableList<RemoteTrackSnapshot>>()
        _records.value.asSequence()
            .filter { it.deviceId == deviceId && it.kind in MUSIC_KINDS }
            .flatMap { it.tracks.asSequence() }
            .forEach { t ->
                val gid = t.albumGroupId.ifBlank { albumGroupIdOf(deviceId, t) }
                groups.getOrPut(gid) { mutableListOf() }.add(t)
            }
        val covers = _records.value.filter { it.deviceId == deviceId && it.cover.isNotBlank() }
            .associate { it.key to it.cover }
        return groups.map { (gid, list) ->
            val seen = HashSet<String>()
            val tracks = list.filter { seen.add(it.songId) }
            val first = tracks.firstOrNull()
            val cover = covers[gid] ?: covers[first?.albumGroupTitle.orEmpty()]
                ?: tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
            RemoteAlbumGroup(
                id = gid,
                title = first?.albumGroupTitle?.ifBlank { first.album } ?: "未知专辑",
                artist = first?.artist.orEmpty(),
                artworkUrl = cover,
                songs = tracks.map { it.toSong() },
                mergeTitles = tracks.all { it.mergeTitles },
            )
        }.sortedBy { it.title.lowercase() }
    }

    fun downloadedEbooks(deviceId: String): List<RemoteEbookWire> {
        val map = LinkedHashMap<String, RemoteEbookWire>()
        _records.value.filter { it.deviceId == deviceId }.forEach { rec ->
            rec.ebook?.let { map.putIfAbsent(it.path, it) }
            rec.ebooks.forEach { map.putIfAbsent(it.path, it) }
        }
        return map.values.toList()
    }

    fun downloadedEbookRoots(deviceId: String): List<RemoteEbookRootWire> {
        val map = LinkedHashMap<String, RemoteEbookRootWire>()
        _records.value.filter { it.deviceId == deviceId }.forEach { rec ->
            rec.ebookRoot?.let { map.putIfAbsent(it.key, it) }
        }
        downloadedEbooks(deviceId).forEach { b ->
            val key = b.rootKey.ifBlank { "downloaded" }
            map.putIfAbsent(
                key,
                RemoteEbookRootWire(
                    key = key,
                    rootPath = "",
                    displayName = "已下载",
                    shelf = false,
                    bookCount = 0,
                ),
            )
        }
        return map.values.toList()
    }

    fun downloadedAudiobooks(deviceId: String): List<RemoteAudiobookWire> {
        val map = LinkedHashMap<String, RemoteAudiobookWire>()
        _records.value.filter {
            it.deviceId == deviceId && it.kind == RemoteDownloadKind.AUDIOBOOK
        }.forEach { rec -> rec.audiobook?.let { map.putIfAbsent(it.id, it) } }
        return map.values.toList()
    }

    private fun fileUri(path: String): String = Uri.fromFile(File(path)).toString()

    private fun snapshotOf(
        song: Song,
        albumGroupId: String,
        albumGroupTitle: String,
        mergeTitles: Boolean,
    ): RemoteTrackSnapshot = RemoteTrackSnapshot(
        songId = song.id,
        title = song.title,
        artist = song.artist,
        album = song.album,
        albumId = song.albumId,
        artistId = song.artistId,
        durationSec = song.durationSec,
        trackNumber = song.trackNumber,
        discNumber = song.discNumber,
        mergeTitles = mergeTitles,
        remoteUrl = song.streamUrl,
        artworkUrl = song.artworkUrl,
        suffix = song.suffix.ifBlank { song.path.substringAfterLast('.', "") },
        albumGroupId = albumGroupId,
        albumGroupTitle = albumGroupTitle,
    )

    private fun RemoteTrackSnapshot.toSong(): Song {
        val local = localPath.takeIf { it.isNotBlank() && File(it).isFile }
        val localUri = local?.let { Uri.fromFile(File(it)).toString() }.orEmpty()
        return Song(
            id = songId,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            durationSec = durationSec,
            streamUrl = localUri.ifBlank { remoteUrl },
            albumId = albumId,
            artistId = artistId,
            suffix = suffix,
            path = local.orEmpty(),
            accent = accentFor(songId.ifBlank { title }),
            discNumber = discNumber,
            trackNumber = trackNumber,
            available = localUri.isNotBlank(),
        )
    }

    private fun albumGroupIdOf(deviceId: String, song: Song): String =
        "remote-album:$deviceId:${song.albumId.ifBlank { "${song.artist}|${song.album}" }}"

    private fun albumGroupIdOf(deviceId: String, t: RemoteTrackSnapshot): String =
        "remote-album:$deviceId:${t.albumId.ifBlank { "${t.artist}|${t.album}" }}"

    private fun recordDir(record: RemoteDownloadRecord): File {
        val dir = File(rootDir, "${sha1(record.deviceId)}/${record.kind}/${sha1(record.key)}")
        if (!dir.isDirectory) runCatching { dir.mkdirs() }
        return dir
    }

    private fun audioExt(song: Song): String = extensionOf(song.path, song.streamUrl, "mp3")
    private fun audioExt(path: String): String = extensionOf(path, path, "mp3")
    private fun ebookExt(path: String): String = extensionOf(path, path, "epub")

    private fun extensionOf(path: String, url: String, fallback: String): String {
        val known = setOf(
            "mp3", "m4a", "mp4", "alac", "aac", "flac", "ogg", "oga", "opus",
            "wav", "aif", "aiff", "wma", "epub", "mobi", "azw", "azw3",
        )
        val fromPath = path.substringAfterLast('.', "").lowercase()
        if (fromPath in known) return fromPath
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        if (fromUrl in known) return fromUrl
        return fallback
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun sha1(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(s.hashCode().toString())

    private fun childKeysOf(rec: RemoteDownloadRecord): List<String> = when (rec.kind) {
        RemoteDownloadKind.TRACK,
        RemoteDownloadKind.ALBUM,
        RemoteDownloadKind.ARTIST,
        -> rec.tracks.map { stateKey(RemoteDownloadKind.TRACK, rec.deviceId, it.songId) }

        RemoteDownloadKind.EBOOK -> listOf(stateKey(RemoteDownloadKind.EBOOK, rec.deviceId, rec.key))
        RemoteDownloadKind.EBOOK_FOLDER,
        RemoteDownloadKind.EBOOK_ROOT,
        -> rec.ebooks.map { stateKey(RemoteDownloadKind.EBOOK, rec.deviceId, it.path) }

        else -> emptyList()
    }

    private companion object {
        val MUSIC_KINDS = setOf(
            RemoteDownloadKind.TRACK,
            RemoteDownloadKind.ALBUM,
            RemoteDownloadKind.ARTIST,
        )
    }
}
