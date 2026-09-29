package com.aurora.music.data

import android.content.Context
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
    private val context: Context,
    private val checkpoints: ScanCheckpoints = ScanCheckpoints(context),
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

        // 断点续扫：上次每 200 个落盘的已扫结果。path+size+mtime 三元一致才复用，
        // 文件变化/删除的条目在循环里自然落选，最终写库时不会残留。
        var checkpointBase: List<ScannedTrack> = emptyList()
        var checkByPath: Map<String, ScannedTrack> = emptyMap()
        // 本次新扫出的结果（参与落盘累积）；checkpointBase 只读不写
        val fresh = Collections.synchronizedList(ArrayList<ScannedTrack>())
        val checkpointMutex = kotlinx.coroutines.sync.Mutex()
        val lastSaved = AtomicInteger(0)

        suspend fun saveCheckpoint() {
            // 多 worker 并发到达时只有一个真正写盘，抢不到锁的直接跳过
            if (!checkpointMutex.tryLock()) return
            try {
                val snapshot = ArrayList<ScannedTrack>(checkpointBase.size + fresh.size)
                snapshot.addAll(checkpointBase)
                snapshot.addAll(fresh)
                checkpoints.save(ScanCheckpoint(rootId = root.id, rootPath = root.rootPath, completed = snapshot))
                lastSaved.set(done.get())
            } finally {
                checkpointMutex.unlock()
            }
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
                    // 隐藏文件/目录一律忽略（unix 前缀 `.` + File.isHidden 双保险）
                    if (isHiddenName(f.name)) continue
                    if (runCatching { f.isDirectory }.getOrDefault(false)) {
                        // skip unreadable/hidden dirs quietly; never leave the root subtree
                        if (runCatching { f.canRead() && !f.isHidden }.getOrDefault(false)) stack.add(f)
                        continue
                    }
                    if (runCatching { f.isHidden }.getOrDefault(false)) continue
                    if (isAudioFile(f.name)) files.add(f)
                }
            }
            // 确定性顺序：断点续扫的进度含义才稳定，多次扫描的 done/total 可比
            files.sortBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath).lowercase() }
            total = files.size
            // 读断点：root 路径变了（换盘/换目录复用 id 极少见）则丢弃，避免张冠李戴
            val loaded = checkpoints.load(root.id)?.takeIf { it.rootPath == root.rootPath }
            checkpointBase = loaded?.completed.orEmpty()
            checkByPath = checkpointBase.associateBy { it.path }
            // 断点里短时长（规则后加的）这次起不再收录，直接过滤
            if (checkByPath.isNotEmpty()) {
                checkpointBase = checkpointBase.filter { it.durationSec >= MIN_TRACK_DURATION_SEC }
                checkByPath = checkpointBase.associateBy { it.path }
            }
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
                            // hasEmbedded 未知（老数据迁移而来）也强制重读一次，补上内嵌图标记
                            if (old != null && old.hasEmbedded != null && old.size == size && old.lastModified == mtime && old.available) {
                                // 未变更文件直接复用，但短时长（<10s，含老数据里的 0）这次起不再收录
                                if (old.durationSec >= MIN_TRACK_DURATION_SEC) {
                                    out.add(old)
                                }
                            } else {
                                // 断点命中：上次已扫且未变更，直接复用，不再读标签
                                val ck = checkByPath[path]
                                if (ck != null && ck.size == size && ck.lastModified == mtime && ck.available &&
                                    ck.durationSec >= MIN_TRACK_DURATION_SEC
                                ) {
                                    out.add(ck)
                                } else {
                                    // 单文件超时即放弃（走文件名兜底），坏文件不阻塞整库。
                                    // 同一次 MMR 会话里顺手把内嵌图存进 track_art 缓存，供单曲/专辑封面用。
                                    val meta = withTimeoutOrNull(META_TIMEOUT_MS) { readMetadata(f) } ?: fallback(f)
                                    // 短时长（<10s，含读不到时长的 0）直接忽略，不入库
                                    if (meta.durationSec >= MIN_TRACK_DURATION_SEC) {
                                        val codec = withTimeoutOrNull(CODEC_TIMEOUT_MS) { sniffCodec(f) }.orEmpty()
                                        val songId = "file:$path"
                                        val hasArt = meta.art?.takeIf { it.isNotEmpty() }?.let { bytes ->
                                            runCatching { TrackArtworkCache.saveEmbedded(context, songId, bytes) }.getOrDefault(false)
                                        } == true
                                        val row = ScannedTrack(
                                            path = path, size = size, lastModified = mtime,
                                            title = meta.title, artist = meta.artist, album = meta.album,
                                            durationSec = meta.durationSec, artworkUrl = folderCover(f),
                                            codec = codec, hasEmbedded = hasArt,
                                        )
                                        out.add(row)
                                        fresh.add(row)
                                        if (old == null) added.incrementAndGet() else updated.incrementAndGet()
                                    }
                                }
                            }
                            val d = done.incrementAndGet()
                            // 每 200 个落一次断点：杀进程/切后台被回收都不丢进度
                            if (d - lastSaved.get() >= CHECKPOINT_EVERY) saveCheckpoint()
                            if (d == files.size) emit(f.name) else emitThrottled(f.name)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 被取消（用户取消/新扫描顶掉）也先把已扫的落盘，下次接着扫
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val snapshot = ArrayList<ScannedTrack>(checkpointBase.size + fresh.size)
                    snapshot.addAll(checkpointBase)
                    snapshot.addAll(fresh)
                    if (snapshot.isNotEmpty()) {
                        checkpoints.save(ScanCheckpoint(rootId = root.id, rootPath = root.rootPath, completed = snapshot))
                    }
                }
            }
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
        // 扫完断点即作废，下次是全新扫描
        checkpoints.clear(root.id)
        onProgress(ScanProgress(rootId = root.id, running = false, found = done.get(),
            total = sorted.size, added = added.get(), updated = updated.get(), missing = missing))
    }

    private companion object {
        // 并行读标签 worker 数：MMR 走跨进程 mediaserver，4 并发收益明显，再多收益递减
        const val SCAN_WORKERS = 4
        // 单文件超时：正常 setDataSource 秒级返回，超时的基本是坏文件/坏介质，直接跳过
        const val META_TIMEOUT_MS = 15_000L
        const val CODEC_TIMEOUT_MS = 10_000L
        // 断点落盘粒度：每扫完 200 个文件暂存一次，杀进程/回收都不丢进度
        const val CHECKPOINT_EVERY = 200
    }

    private data class Meta(
        val title: String, val artist: String, val album: String, val durationSec: Int,
        val art: ByteArray? = null,
    )

    private fun readMetadata(f: File): Meta {
        val mmr = MediaMetadataRetriever()
        return try {
            runCatching { mmr.setDataSource(f.absolutePath) }.getOrNull() ?: return fallback(f)
            fun s(key: Int) = runCatching { mmr.extractMetadata(key) }.getOrNull().orEmpty()
            val durMs = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            // 内嵌图同一会话里一起取（大图几 MB，超时保护在外层）；存缓存由调用方做
            val art = runCatching { mmr.embeddedPicture }.getOrNull()?.takeIf { it.isNotEmpty() }
            Meta(
                title = s(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .ifBlank { s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationSec = (durMs / 1000).toInt().coerceAtLeast(0),
                art = art,
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
