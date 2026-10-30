package com.aurora.music.data.cast

import android.content.Context
import android.net.Uri
import com.aurora.music.data.TrackArtworkCache
import com.aurora.music.data.audiobook.AudiobookBook
import com.aurora.music.data.ebook.EbookBook
import com.aurora.music.data.ebook.HttpUtil
import com.aurora.music.data.ebook.ebookFormatOf
import com.aurora.music.model.Song
import com.google.gson.Gson
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking

/**
 * 把本机的三套资料库暴露给局域网里的其他 Aurora：
 * - `/library/all`：一次性元信息（曲目 / 电子书 / 有声书章节）；
 * - `/media/music?id=`、`/media/audiobook?path=`：音频，支持 Range，播放器可边听边下；
 * - `/media/songart?id=`、`/media/cover?kind=&key=`：封面；
 * - `/media/ebook?path=`：电子书原文件（接收端落盘后交给阅读器解析）。
 *
 * 只允许访问库里登记过的路径，避免把整个文件系统漏到局域网。
 */
class LocalLibraryHttpProvider(
    private val context: Context,
    private val identity: CastIdentity,
    private val musicProvider: () -> List<Song>,
    private val ebookProvider: () -> List<EbookBook>,
    private val ebookRootsProvider: () -> List<com.aurora.music.data.ebook.EbookRoot>,
    private val audiobookProvider: () -> List<AudiobookBook>,
) : RemoteLibraryProvider {

    private val appContext = context.applicationContext
    private val gson = Gson()

    override fun libraryJson(): String {
        val songs = runCatching { musicProvider() }.getOrDefault(emptyList())
        val ebooks = runCatching { ebookProvider() }.getOrDefault(emptyList())
        val ebookRoots = runCatching { ebookRootsProvider() }.getOrDefault(emptyList())
        val abooks = runCatching { audiobookProvider() }.getOrDefault(emptyList())
        val enabledRoots = ebookRoots.filter { it.enabled }
        val shelfBooks = ebooks.filter { it.inShelf || it.rootId == null }
        fun rootKeyOf(book: EbookBook): String {
            if (book.inShelf || book.rootId == null) return "shelf"
            val match = enabledRoots
                .filter { book.path == it.rootPath || book.path.startsWith(it.rootPath.trimEnd('/') + "/") }
                .maxByOrNull { it.rootPath.length }
            return match?.id?.toString().orEmpty()
        }
        val payload = RemoteLibraryWire(
            deviceId = identity.id,
            deviceName = identity.name,
            generatedAt = System.currentTimeMillis(),
            music = songs.filter { it.available && it.id.isNotBlank() }.map {
                RemoteTrackWire(
                    id = it.id,
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    albumId = it.albumId,
                    artistId = it.artistId,
                    durationSec = it.durationSec,
                    trackNumber = it.trackNumber,
                    discNumber = it.discNumber,
                    hasArt = true,
                )
            },
            ebookRoots = buildList {
                add(
                    RemoteEbookRootWire(
                        key = "shelf",
                        rootPath = "",
                        displayName = "默认书架",
                        shelf = true,
                        bookCount = shelfBooks.size,
                    ),
                )
                enabledRoots.forEach { r ->
                    add(
                        RemoteEbookRootWire(
                            key = r.id.toString(),
                            rootPath = r.rootPath,
                            displayName = r.displayName,
                            shelf = false,
                            bookCount = ebooks.count { b ->
                                !b.inShelf && (b.path == r.rootPath || b.path.startsWith(r.rootPath.trimEnd('/') + "/"))
                            },
                        ),
                    )
                }
            },
            ebooks = ebooks.distinctBy { it.path }.filter { it.path.isNotBlank() }.map {
                RemoteEbookWire(
                    path = it.path,
                    title = it.displayTitle,
                    author = it.author,
                    size = it.size,
                    hasCover = it.coverPath.isNotBlank(),
                    rootKey = rootKeyOf(it),
                )
            },
            audiobooks = abooks.map { b ->
                RemoteAudiobookWire(
                    id = b.id,
                    title = b.displayTitle,
                    hasCover = b.coverPath.isNotBlank(),
                    chapters = b.chapters.map { c ->
                        RemoteChapterWire(
                            path = c.path,
                            title = c.displayTitle,
                            trackNumber = c.trackNumber,
                            durationSec = c.durationSec,
                            size = c.size,
                        )
                    },
                )
            },
        )
        // 设备身份由 EbookCastController 在发送时补上（这里不知道 identity，留空由调用方处理）
        return gson.toJson(payload)
    }

    override fun serve(
        out: OutputStream,
        headers: Map<String, String>,
        query: Map<String, String>,
    ): Boolean {
        return when (query["__route"]) {
            "/library/all" -> {
                HttpUtil.sendText(out, 200, "OK", "application/json; charset=utf-8", libraryJson())
                true
            }
            "/media/music" -> {
                val id = query["id"].orEmpty()
                val song = runCatching { musicProvider().firstOrNull { it.id == id } }.getOrNull()
                if (song == null) sendNotFound(out) else sendAudioResource(out, headers, audioResourceOf(song))
                true
            }
            "/media/audiobook" -> {
                val path = query["path"].orEmpty()
                val chapter = runCatching {
                    audiobookProvider().asSequence().flatMap { it.chapters.asSequence() }
                        .firstOrNull { it.path == path }
                }.getOrNull()
                if (chapter == null) sendNotFound(out) else {
                    val f = File(chapter.path)
                    if (f.isFile) sendFile(out, headers, f, HttpUtil.mimeOf(f.name)) else sendNotFound(out)
                }
                true
            }
            "/media/ebook" -> {
                val path = query["path"].orEmpty()
                val book = runCatching { ebookProvider().firstOrNull { it.path == path } }.getOrNull()
                val f = book?.let { File(it.path) }
                if (f != null && f.isFile) sendFile(out, headers, f, ebookMime(f.name)) else sendNotFound(out)
                true
            }
            "/media/songart" -> {
                val id = query["id"].orEmpty()
                val song = runCatching { musicProvider().firstOrNull { it.id == id } }.getOrNull()
                if (song == null) sendDefaultArt(out) else sendArtForSong(out, song)
                true
            }
            "/media/cover" -> {
                val kind = query["kind"].orEmpty()
                val key = query["key"].orEmpty()
                val cover = when (kind) {
                    "ebook" -> runCatching { ebookProvider().firstOrNull { it.path == key }?.coverPath }.getOrNull()
                    "audiobook" -> runCatching { audiobookProvider().firstOrNull { it.id == key }?.coverPath }.getOrNull()
                    else -> null
                }
                if (cover.isNullOrBlank()) sendDefaultArt(out) else sendImageUri(out, cover)
                true
            }
            else -> false
        }
    }

    private fun audioResourceOf(song: Song): LibraryResource? {
        val raw = song.path.ifBlank { song.streamUrl }
        if (raw.isBlank()) return null
        val mime = HttpUtil.mimeOf(song.suffix.ifBlank { raw })
        return when {
            raw.startsWith("file://") -> Uri.parse(raw).path?.let { File(it) }?.takeIf { it.isFile }
                ?.let { LibraryResource.LocalFile(it, mime) }
            raw.startsWith("/") -> File(raw).takeIf { it.isFile }?.let { LibraryResource.LocalFile(it, mime) }
            raw.startsWith("content://") -> LibraryResource.ContentUri(Uri.parse(raw), mime)
            else -> null
        }
    }

    private fun sendAudioResource(out: OutputStream, headers: Map<String, String>, res: LibraryResource?) {
        when (res) {
            is LibraryResource.LocalFile -> sendFile(out, headers, res.file, res.mime)
            is LibraryResource.ContentUri -> sendContentUri(out, headers, res.uri, res.mime)
            is LibraryResource.Bytes -> HttpUtil.sendBytes(out, 200, "OK", res.mime, res.bytes)
            null -> sendNotFound(out)
        }
    }

    private fun sendArtForSong(out: OutputStream, song: Song) {
        val uri = runCatching { TrackArtworkCache.cachedSync(appContext, song) }.getOrNull().orEmpty()
            .ifBlank { song.artworkUrl }
        if (uri.isBlank()) {
            val resolved = runCatching { runBlocking { TrackArtworkCache.resolve(appContext, song) } }.getOrNull()
            if (resolved.isNullOrBlank()) sendDefaultArt(out) else sendImageUri(out, resolved)
        } else {
            sendImageUri(out, uri)
        }
    }

    private fun sendImageUri(out: OutputStream, uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null) {
            sendDefaultArt(out)
            return
        }
        when (uri.scheme?.lowercase()) {
            "file" -> {
                val f = uri.path?.let { File(it) }
                if (f != null && f.isFile) {
                    HttpUtil.sendBytes(out, 200, "OK", HttpUtil.mimeOf(f.name), f.readBytes())
                } else sendDefaultArt(out)
            }
            "content" -> {
                val bytes = runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes == null || bytes.isEmpty()) sendDefaultArt(out)
                else HttpUtil.sendBytes(out, 200, "OK", sniffImageMime(bytes), bytes)
            }
            else -> {
                val f = uriString.takeIf { it.startsWith("/") }?.let { File(it) }
                if (f != null && f.isFile) {
                    HttpUtil.sendBytes(out, 200, "OK", HttpUtil.mimeOf(f.name), f.readBytes())
                } else sendDefaultArt(out)
            }
        }
    }

    private fun sendDefaultArt(out: OutputStream) {
        val bytes = runCatching {
            val bmp = android.graphics.BitmapFactory.decodeResource(
                appContext.resources, com.aurora.music.R.drawable.default_cover,
            ) ?: return@runCatching null
            java.io.ByteArrayOutputStream().use { bos ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
        }.getOrNull()
        if (bytes == null) sendNotFound(out)
        else HttpUtil.sendBytes(out, 200, "OK", "image/png", bytes)
    }

    /** Range 只对本地文件与 content://（AssetFileDescriptor 可定位）生效。 */
    private fun sendFile(out: OutputStream, headers: Map<String, String>, file: File, mime: String) {
        val total = file.length()
        val range = parseRange(headers["range"], total)
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1).coerceAtLeast(0L)
        val length = (end - start + 1).coerceAtLeast(0L)
        val status = if (range != null) 206 else 200
        val extra = buildList {
            if (range != null) add("Content-Range" to "bytes $start-$end/$total")
        }
        HttpUtil.sendHeaders(out, status, if (range != null) "Partial Content" else "OK", mime, length, extra)
        if (length <= 0) return
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(128 * 1024)
                var left = length
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    private fun sendContentUri(out: OutputStream, headers: Map<String, String>, uri: Uri, mime: String) {
        val afd = runCatching { appContext.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        if (afd == null) {
            // 少数 provider 不支持 AssetFileDescriptor：整段读进内存发送（只影响这类边缘 provider）
            val bytes = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) sendNotFound(out)
            else HttpUtil.sendBytes(out, 200, "OK", mime, bytes)
            return
        }
        afd.use { fd ->
            val base = fd.startOffset
            val declared = fd.declaredLength
            val total = if (declared >= 0) declared else (fd.length - base).coerceAtLeast(0L)
            val range = parseRange(headers["range"], total)
            val start = range?.first ?: 0L
            val end = range?.second ?: (total - 1).coerceAtLeast(0L)
            val length = (end - start + 1).coerceAtLeast(0L)
            val status = if (range != null) 206 else 200
            val extra = buildList {
                if (range != null) add("Content-Range" to "bytes $start-$end/$total")
            }
            HttpUtil.sendHeaders(out, status, if (range != null) "Partial Content" else "OK", mime, length, extra)
            if (length <= 0) return
            // 用 FileChannel 定位：createInputStream() 的偏移语义随 provider 而异，
            // 直接 seek 到 fd 起始 + Range 起点最稳。
            java.io.FileInputStream(fd.fileDescriptor).use { ins ->
                runCatching { ins.channel.position(base + start) }
                copyStreamLimited(ins, out, length)
            }
        }
    }

    private fun copyStream(ins: InputStream, out: OutputStream) {
        val buf = ByteArray(128 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
    }

    private fun copyStreamLimited(ins: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(128 * 1024)
        var left = length
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** "bytes=start-end" / "bytes=start-" / "bytes=-suffix"。 */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null || total <= 0) return null
        val h = header.trim()
        if (!h.startsWith("bytes=")) return null
        val part = h.removePrefix("bytes=").substringBefore(',').trim()
        if (part.isEmpty()) return null
        val a = part.substringBefore('-').trim()
        val b = part.substringAfter('-').trim()
        val start: Long
        val end: Long
        if (a.isEmpty()) {
            val suffix = b.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            start = (total - suffix).coerceAtLeast(0L)
            end = total - 1
        } else {
            start = a.toLongOrNull() ?: return null
            end = if (b.isEmpty()) total - 1 else (b.toLongOrNull() ?: return null)
        }
        if (start > end || start >= total) return null
        return start to end.coerceAtMost(total - 1)
    }

    private fun sendNotFound(out: OutputStream) {
        HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "not found")
    }

    private fun sniffImageMime(bytes: ByteArray): String {
        fun at(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun ebookMime(name: String): String = when (ebookFormatOf(name)) {
        com.aurora.music.data.ebook.EbookFormat.EPUB -> "application/epub+zip"
        com.aurora.music.data.ebook.EbookFormat.MOBI -> "application/x-mobipocket-ebook"
        com.aurora.music.data.ebook.EbookFormat.AZW, com.aurora.music.data.ebook.EbookFormat.AZW3 ->
            "application/vnd.amazon.ebook"
        else -> "application/octet-stream"
    }
}
