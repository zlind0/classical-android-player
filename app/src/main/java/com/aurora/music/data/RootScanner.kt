package com.aurora.music.data

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

// Classical fork v0.3 (plan §8-13): scans exactly the user's MusicRoots.
// Stage A (file discovery) walks with File.listFiles without touching tags;
// Stage B (metadata) reads tags with MediaMetadataRetriever on N workers;
// unchanged files (path+size+mtime, plan §11) keep their cached rows.
// A single hanging/corrupt file never stalls the scan (per-file timeout,
// plan §84): it falls back to the filename and the scan moves on.
class RootScanner(
    private val store: MusicRootsStore,
) {
    // Cancellation is cooperative via the caller's coroutine scope.
    suspend fun scan(root: MusicRoot, onProgress: (ScanProgress) -> Unit = {}) =
        withContext(Dispatchers.IO) { runScan(root, onProgress) }

    private suspend fun kotlinx.coroutines.CoroutineScope.runScan(root: MusicRoot, onProgress: (ScanProgress) -> Unit) {
        val previous = store.readIndex(root.id).associateBy { it.path }
        val out = Collections.synchronizedList(ArrayList<ScannedTrack>(previous.size))
        val added = AtomicInteger(0)
        val updated = AtomicInteger(0)
        val done = AtomicInteger(0)
        var total = 0
        var lastEmitMs = 0L

        fun emit(current: String, finished: Boolean = false) {
            lastEmitMs = System.currentTimeMillis()
            onProgress(
                ScanProgress(rootId = root.id, running = !finished, found = done.get(),
                    total = total, added = added.get(), updated = updated.get(), current = current)
            )
        }

        // 节流进度：多 worker 高频上报会刷爆重组，200ms 一次足够
        fun emitThrottled(current: String) {
            if (System.currentTimeMillis() - lastEmitMs >= 200) emit(current)
        }

        try {
            // Stage A：纯文件遍历（快，不读标签），先把音频文件清单收齐
            emit("")
            val files = ArrayList<File>()
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
                    if (isAudioFile(f.name)) files.add(f)
                }
            }
            total = files.size
            emit("")

            // Stage B：多 worker 并行读标签。单个文件 hang 住（坏文件/慢介质上
            // setDataSource 不返回）时超时跳过，不卡死整库；未变更文件直接复用旧行。
            val next = AtomicInteger(0)
            coroutineScope {
                repeat(SCAN_WORKERS) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            ensureActive()
                            val i = next.getAndIncrement()
                            if (i >= files.size) break
                            val f = files[i]
                            val path = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                            val size = runCatching { f.length() }.getOrDefault(-1L)
                            val mtime = runCatching { f.lastModified() }.getOrDefault(0L)
                            val old = previous[path]
                            if (old != null && old.size == size && old.lastModified == mtime && old.available) {
                                out.add(old)
                            } else {
                                // 单文件超时即放弃（走文件名兜底），坏文件不阻塞整库
                                val meta = withTimeoutOrNull(META_TIMEOUT_MS) { readMetadata(f) } ?: fallback(f)
                                val codec = withTimeoutOrNull(CODEC_TIMEOUT_MS) { sniffCodec(f) }.orEmpty()
                                out.add(
                                    ScannedTrack(
                                        path = path, size = size, lastModified = mtime,
                                        title = meta.title, artist = meta.artist, album = meta.album,
                                        durationSec = meta.durationSec, artworkUrl = folderCover(f),
                                        codec = codec,
                                    )
                                )
                                if (old == null) added.incrementAndGet() else updated.incrementAndGet()
                            }
                            val d = done.incrementAndGet()
                            if (d == files.size) emit(f.name) else emitThrottled(f.name)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // scan-level failure still persists whatever was indexed so far
        }
        // entries no longer on disk stay in the DB but flagged unavailable
        val seen = out.map { it.path }.toSet()
        var missing = 0
        for ((path, old) in previous) {
            if (path !in seen) {
                out.add(old.copy(available = false))
                missing++
            }
        }
        val sorted = out.sortedBy { it.path.lowercase() }
        // 深扫落盘进 library_files.db（三表原子替换）；启动时只读 + 存在性检查，不走这里
        store.writeScanResult(root.id, sorted)
        store.stampScan(root.id)
        onProgress(ScanProgress(rootId = root.id, running = false, found = done.get(),
            total = sorted.size, added = added.get(), updated = updated.get(), missing = missing))
    }

    private companion object {
        // 并行读标签 worker 数：MMR 走跨进程 mediaserver，4 并发收益明显，再多收益递减
        const val SCAN_WORKERS = 4
        // 单文件超时：正常 setDataSource 秒级返回，超时的基本是坏文件/坏介质，直接跳过
        const val META_TIMEOUT_MS = 15_000L
        const val CODEC_TIMEOUT_MS = 10_000L
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
