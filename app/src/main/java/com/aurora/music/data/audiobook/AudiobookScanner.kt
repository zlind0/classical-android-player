package com.aurora.music.data.audiobook

import android.media.MediaMetadataRetriever
import com.aurora.music.data.isAudioFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 有声书扫描：只看根目录的直接子目录（=书）；书内递归收平（含 CD1 这类二级目录）。
// 增量：path+size+mtime 一致复用旧行，只读新增/变化文件的标签。
class AudiobookScanner(private val store: AudiobookStore, private val dao: AudiobookDao) {

    suspend fun scan(root: AudiobookRoot, onProgress: (AudiobookScanProgress) -> Unit) {
        withContext(Dispatchers.IO) {
            val base = File(root.rootPath)
            if (!base.exists()) {
                onProgress(AudiobookScanProgress(running = false, rootId = root.id))
                return@withContext
            }
            onProgress(AudiobookScanProgress(running = true, rootId = root.id, current = root.rootPath))
            // 直接子目录 = 书
            val bookDirs = runCatching {
                base.listFiles()?.filter {
                    runCatching { it.isDirectory && !it.isHidden && it.canRead() }.getOrDefault(false)
                }?.sortedBy { it.name.lowercase() }.orEmpty()
            }.getOrDefault(emptyList())
            val old = dao.chaptersOfRoot(root.id).associateBy { it.path }
            val out = mutableListOf<AudiobookChapterRow>()
            var done = 0
            for (bookDir in bookDirs) {
                val bookId = runCatching { bookDir.canonicalPath }.getOrDefault(bookDir.absolutePath)
                // 书内递归收平
                val files = mutableListOf<File>()
                val stack = ArrayDeque<File>()
                stack.add(bookDir)
                while (stack.isNotEmpty()) {
                    val d = stack.removeLast()
                    val kids = runCatching { d.listFiles()?.toList() }.getOrDefault(null) ?: continue
                    kids.forEach { f ->
                        runCatching {
                            if (f.isDirectory) {
                                if (!f.isHidden && f.canRead()) stack.add(f)
                            } else if (f.isFile && isAudioFile(f.name)) {
                                files.add(f)
                            }
                        }
                    }
                }
                files.forEach { f ->
                    val abs = f.absolutePath
                    val prev = old[abs]
                    if (prev != null && prev.size == f.length() && prev.lastModified == f.lastModified()) {
                        out.add(prev.copy(bookId = bookId, rootId = root.id))
                    } else {
                        val (title, trackNo, dur) = readTags(f)
                        out.add(
                            AudiobookChapterRow(
                                path = abs, bookId = bookId,
                                title = title.ifBlank { f.nameWithoutExtension },
                                trackNumber = trackNo,
                                durationSec = dur,
                                size = f.length(), lastModified = f.lastModified(),
                                rootId = root.id,
                            ),
                        )
                    }
                    done++
                    if (done % 5 == 0) {
                        onProgress(
                            AudiobookScanProgress(
                                running = true, rootId = root.id,
                                found = done, current = f.name,
                            ),
                        )
                    }
                }
            }
            dao.replaceRootScan(root.id, out)
            store.stampScan(root.id)
            store.refresh()
            onProgress(AudiobookScanProgress(running = false, rootId = root.id, found = done, total = done))
        }
    }

    private fun readTags(f: File): Triple<String, Int, Int> {
        val mmr = MediaMetadataRetriever()
        return try {
            runCatching { mmr.setDataSource(f.absolutePath) }
            val title = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            }.getOrNull().orEmpty()
            val rawTrack = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            }.getOrNull().orEmpty()
            val durMs = runCatching {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            Triple(title, parseTrackNo(rawTrack, f.name), (durMs / 1000).toInt().coerceAtLeast(0))
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 标签 trackNumber 优先（"3/12" 取 3），没有则解析文件名前导数字。 */
    private fun parseTrackNo(raw: String, name: String): Int {
        raw.trim().substringBefore('/').filter { it.isDigit() }.toIntOrNull()?.let { return it }
        return name.trimStart().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}
