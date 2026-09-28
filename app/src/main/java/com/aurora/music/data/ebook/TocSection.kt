package com.aurora.music.data.ebook

/**
 * 当前位置所在的目录条目下标（目录页 currentEntry 同口径：
 * 同章且块号不超过当前位置的最后一条；空目录返回 -1）。
 * 阅读页目录高亮与听书通知栏作者栏共用这一份口径。
 */
object TocSection {
    fun currentEntry(toc: List<EbookTocEntry>, chapter: Int, block: Int): Int {
        if (toc.isEmpty()) return -1
        var idx = toc.indexOfFirst { it.chapterIndex == chapter }
        toc.forEachIndexed { i, e ->
            if (e.chapterIndex == chapter && e.blockIndex <= block) idx = i
        }
        return if (idx < 0) 0 else idx
    }
}
