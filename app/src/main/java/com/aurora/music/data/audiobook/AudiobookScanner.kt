package com.aurora.music.data.audiobook

import android.media.MediaMetadataRetriever
import com.aurora.music.data.isAudioFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 有声书扫描：根目录的直接子目录 = 一本书；根目录里直接放着的音频文件也归成一本
//（一本书拆成几个 m4b 的常见布局）。书内递归收平（含 CD1 这类二级目录）。
// 单个 m4b/m4a 里带内嵌章节轨时，按章节拆成多行（startMs/endMs 记录文件内区间），
// 章节标题优先用内嵌章节标题（没有标题才回落成"文件标题 (n)"）。
// 增量：path+size+mtime 一致且是当前 key 格式时复用旧行，只读新增/变化文件的标签与章节。
class AudiobookScanner(private val store: AudiobookStore, private val dao: AudiobookDao) {

    suspend fun scan(root: AudiobookRoot, onProgress: (AudiobookScanProgress) -> Unit) {
        withContext(Dispatchers.IO) {
            val base = File(root.rootPath)
            if (!base.exists()) {
                onProgress(AudiobookScanProgress(running = false, rootId = root.id))
                return@withContext
            }
            onProgress(AudiobookScanProgress(running = true, rootId = root.id, current = root.rootPath))
            // 直接子目录 = 书；根目录里直接躺着的音频文件（如"一本书拆成几个 m4b"）合并成一本书
            val bookDirs = runCatching {
                base.listFiles()?.filter {
                    runCatching { it.isDirectory && !it.isHidden && it.canRead() }.getOrDefault(false)
                }?.sortedBy { it.name.lowercase() }.orEmpty()
            }.getOrDefault(emptyList())
            val looseFiles = runCatching {
                base.listFiles()?.filter {
                    runCatching { it.isFile && !it.isHidden && it.canRead() && isAudioFile(it.name) }
                        .getOrDefault(false)
                }?.sortedBy { it.name.lowercase() }.orEmpty()
            }.getOrDefault(emptyList())
            // path -> 旧章节行（一个 m4b 会有多行）
            val oldByPath = dao.chaptersOfRoot(root.id).groupBy { it.path }
            val out = mutableListOf<AudiobookChapterRow>()
            var done = 0
            val books = ArrayList<Pair<String, List<File>>>()
            for (bookDir in bookDirs) {
                val bookId = runCatching { bookDir.canonicalPath }.getOrDefault(bookDir.absolutePath)
                books.add(bookId to collectAudioFiles(bookDir))
            }
            if (looseFiles.isNotEmpty()) {
                // 根目录自己就是一本"多文件有声书"（例：4 个 m4b 组成的一本书）
                val rootBookId = runCatching { base.canonicalPath }.getOrDefault(base.absolutePath)
                books.add(rootBookId to looseFiles)
            }
            for ((bookId, files) in books) {
                files.forEach { f ->
                    val abs = f.absolutePath
                    val prev = oldByPath[abs]
                    val reusable = prev != null && prev.isNotEmpty() &&
                        prev.all {
                            it.path == abs && it.size == f.length() && it.lastModified == f.lastModified() &&
                                // 旧版本（key == path）没有章节区间信息：强制重扫一次
                                it.key == abookChapterKey(abs, it.startMs)
                        }
                    val rows = if (reusable) {
                        prev!!.map { it.copy(bookId = bookId, rootId = root.id) }
                    } else {
                        scanFile(f, bookId, root.id)
                    }
                    out.addAll(rows)
                    done += rows.size
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

    /** 书内递归收平（含 CD1 这类二级目录）。 */
    private fun collectAudioFiles(bookDir: File): List<File> {
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
        return files
    }

    /** 一个音频文件 → 一行（普通文件）或多行（m4b/m4a 内嵌章节）。 */
    private fun scanFile(f: File, bookId: String, rootId: Long): List<AudiobookChapterRow> {
        val (tagTitle, trackNo, durSec) = readTags(f)
        val fileTitle = tagTitle.ifBlank { f.nameWithoutExtension }
        val durMs = durSec * 1000L
        val embedded = if (f.extension.lowercase() in CHAPTER_CONTAINER_EXTENSIONS) {
            Mp4ChapterReader.read(f)
        } else {
            emptyList()
        }
        if (embedded.size < 2) {
            return listOf(rowOf(f, fileTitle, trackNo, durSec, startMs = 0L, endMs = 0L, bookId = bookId, rootId = rootId))
        }
        return embedded.mapIndexed { i, ch ->
            val start = ch.startMs.coerceAtLeast(0L)
            val next = embedded.getOrNull(i + 1)?.startMs
            val end = (next ?: durMs).takeIf { it > start } ?: 0L
            // 没有内嵌标题时回落到文件标题；多章重名时带上序号，避免列表里全是同名行。
            val title = ch.title.trim().ifBlank { "$fileTitle (${i + 1})" }
            val chapterDur = if (end > start) ((end - start + 999) / 1000).toInt() else 0
            rowOf(f, title, trackNo, chapterDur, start, end, bookId, rootId)
        }
    }

    private fun rowOf(
        f: File,
        title: String,
        trackNo: Int,
        durationSec: Int,
        startMs: Long,
        endMs: Long,
        bookId: String,
        rootId: Long,
    ): AudiobookChapterRow {
        val abs = f.absolutePath
        return AudiobookChapterRow(
            key = abookChapterKey(abs, startMs),
            path = abs, bookId = bookId,
            title = title,
            trackNumber = trackNo,
            durationSec = durationSec,
            size = f.length(), lastModified = f.lastModified(),
            rootId = rootId,
            startMs = startMs, endMs = endMs,
        )
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

    private companion object {
        val CHAPTER_CONTAINER_EXTENSIONS = setOf("m4b", "m4a")
    }
}
