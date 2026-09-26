package com.aurora.music.data.ebook

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 电子书扫描：只递归用户选定的目录；只认 epub/mobi/azw/azw3。
// 增量：path+size+mtime 一致则复用旧行（含标题/封面/进度），只解析新增或变化的文件。
class EbookScanner(private val store: EbookStore, private val dao: EbookDao) {

    suspend fun scan(root: EbookRoot, onProgress: (EbookScanProgress) -> Unit) {
        withContext(Dispatchers.IO) {
            val base = File(root.rootPath)
            if (!base.exists()) {
                onProgress(EbookScanProgress(running = false, rootId = root.id))
                return@withContext
            }
            onProgress(EbookScanProgress(running = true, rootId = root.id, current = root.rootPath))
            // Stage A：枚举
            val files = mutableListOf<File>()
            val stack = ArrayDeque<File>()
            stack.add(base)
            while (stack.isNotEmpty()) {
                val d = stack.removeLast()
                val kids = runCatching { d.listFiles()?.toList() }.getOrDefault(null) ?: continue
                kids.forEach { f ->
                    runCatching {
                        if (f.isDirectory) {
                            if (!f.isHidden && f.canRead()) stack.add(f)
                        } else if (f.isFile && isEbookFile(f.name)) {
                            files.add(f)
                        }
                    }
                }
                if (files.size % 200 == 0) {
                    onProgress(EbookScanProgress(running = true, rootId = root.id, current = d.name))
                }
            }
            // Stage B：元信息（增量复用）
            val old = dao.booksOfRoot(root.id).associateBy { it.path }
            val out = mutableListOf<EbookRow>()
            files.forEachIndexed { i, f ->
                val prev = old[f.absolutePath]
                if (prev != null && prev.size == f.length() && prev.lastModified == f.lastModified()) {
                    out.add(prev)
                } else {
                    val meta = when (ebookFormatOf(f.name)) {
                        EbookFormat.EPUB -> EpubParser.parseMeta(f).let { it.title to it.author }
                        else -> MobiParser.parseMeta(f).let { it.title to it.author }
                    }
                    // md5 只在新增/变化时算一次（封面与缓存寻址用）
                    val md5 = store.fingerprint(f)
                    val cover = store.extractCover(f, md5)
                    out.add(
                        EbookRow(
                            path = f.absolutePath, md5 = md5, size = f.length(),
                            lastModified = f.lastModified(),
                            title = meta.first.ifBlank { f.nameWithoutExtension },
                            author = meta.second, coverPath = cover,
                            rootId = root.id, inShelf = false,
                            // 文件变化但仍是同一路径：保留旧进度
                            spineIndex = prev?.spineIndex ?: 0,
                            pageIndex = prev?.pageIndex ?: 0,
                            progressPct = prev?.progressPct ?: 0f,
                            lastReadAt = prev?.lastReadAt ?: 0L,
                        ),
                    )
                }
                if (i % 5 == 0 || i == files.size - 1) {
                    onProgress(
                        EbookScanProgress(
                            running = true, rootId = root.id,
                            found = i + 1, total = files.size, current = f.name,
                        ),
                    )
                }
            }
            dao.replaceRootScan(root.id, out)
            store.stampScan(root.id)
            store.refresh()
            onProgress(EbookScanProgress(running = false, rootId = root.id, found = out.size, total = out.size))
        }
    }
}
