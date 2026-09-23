package com.aurora.music.data

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

// Classical fork v0.3 (plan §8-13): scans exactly the user's MusicRoots.
// Stage A (file discovery) walks with File.listFiles; Stage B (metadata) reads
// tags with MediaMetadataRetriever; unchanged files (path+size+mtime, plan §11)
// keep their cached rows. One corrupt file never aborts the scan (plan §84).
class RootScanner(
    private val store: MusicRootsStore,
) {
    // Cancellation is cooperative via the caller's coroutine scope.
    suspend fun scan(root: MusicRoot, onProgress: (ScanProgress) -> Unit = {}) =
        withContext(Dispatchers.IO) { runScan(root, onProgress) }

    private suspend fun kotlinx.coroutines.CoroutineScope.runScan(root: MusicRoot, onProgress: (ScanProgress) -> Unit) {
        val previous = store.readIndex(root.id).associateBy { it.path }
        val seen = HashSet<String>()
        val out = ArrayList<ScannedTrack>(previous.size)
        var added = 0
        var updated = 0
        var found = 0

        fun emit(current: String) = onProgress(
            ScanProgress(rootId = root.id, running = true, found = found,
                total = previous.size, added = added, updated = updated, current = current)
        )

        try {
            val stack = ArrayDeque<File>()
            val base = File(root.rootPath)
            if (base.isDirectory) stack.add(base)
            while (stack.isNotEmpty()) {
                ensureActive()
                val dir = stack.removeLast()
                val kids = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (f in kids) {
                    if (runCatching { f.isDirectory }.getOrDefault(false)) {
                        // skip unreadable/hidden dirs quietly; never leave the root subtree
                        if (runCatching { f.canRead() && !f.isHidden }.getOrDefault(false)) stack.add(f)
                        continue
                    }
                    if (!isAudioFile(f.name)) continue
                    found++
                    val path = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                    seen.add(path)
                    val size = runCatching { f.length() }.getOrDefault(-1L)
                    val mtime = runCatching { f.lastModified() }.getOrDefault(0L)
                    val old = previous[path]
                    if (old != null && old.size == size && old.lastModified == mtime && old.available) {
                        out.add(old)
                    } else {
                        val meta = readMetadata(f)
                        out.add(
                            ScannedTrack(
                                path = path, size = size, lastModified = mtime,
                                title = meta.title, artist = meta.artist, album = meta.album,
                                durationSec = meta.durationSec, artworkUrl = folderCover(f),
                                codec = sniffCodec(f),
                            )
                        )
                        if (old == null) added++ else updated++
                    }
                    if (found % 25 == 0) emit(f.name)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // scan-level failure still persists whatever was indexed so far
        }
        // plan §12: entries no longer on disk stay in the DB but flagged unavailable
        var missing = 0
        for ((path, old) in previous) {
            if (path !in seen) {
                out.add(old.copy(available = false))
                missing++
            }
        }
        out.sortBy { it.path.lowercase() }
        // 深扫落盘进 library_files.db（三表原子替换）；启动时只读 + 存在性检查，不走这里
        store.writeScanResult(root.id, out)
        store.stampScan(root.id)
        onProgress(ScanProgress(rootId = root.id, running = false, found = found,
            total = out.size, added = added, updated = updated, missing = missing))
    }

    private data class Meta(val title: String, val artist: String, val album: String, val durationSec: Int)

    private fun readMetadata(f: File): Meta {
        val mmr = MediaMetadataRetriever()
        return try {
            runCatching { mmr.setDataSource(f.absolutePath) }.getOrNull() ?: return fallback(f)
            fun s(key: Int) = runCatching { mmr.extractMetadata(key) }.getOrNull().orEmpty()
            val durMs = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            Meta(
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .ifBlank { s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationSec = (durMs / 1000).toInt().coerceAtLeast(0),
            )
        } catch (e: Exception) {
            fallback(f)
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun fallback(f: File): Meta {
        val base = f.nameWithoutExtension
        // "Artist - Title" filename convention as a last resort
        val parts = base.split(" - ", limit = 2)
        return if (parts.size == 2) Meta(parts[1].trim(), parts[0].trim(), "", 0)
        else Meta(base, "", "", 0)
    }

    // definitive codec id (KEY_MIME of the first audio track): tells ALAC-in-m4a
    // apart from AAC-in-m4a. Header-only read, no decode.
    private fun sniffCodec(f: File): String = runCatching {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) return mime
            }
            ""
        } finally {
            runCatching { ex.release() }
        }
    }.getOrDefault("")

    // plan §82 artwork priority (v0.3 subset): directory cover files
    private fun folderCover(f: File): String {
        val dir = f.parentFile ?: return ""
        for (name in arrayOf("cover.jpg", "cover.jpeg", "folder.jpg", "cover.png", "folder.png", "front.jpg")) {
            val c = File(dir, name)
            if (runCatching { c.isFile && c.canRead() }.getOrDefault(false)) {
                return runCatching { android.net.Uri.fromFile(c).toString() }.getOrDefault("")
            }
        }
        return ""
    }
}
