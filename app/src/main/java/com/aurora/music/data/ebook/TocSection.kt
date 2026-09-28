package com.aurora.music.data.ebook

/**
 * 当前位置所在的目录条目下标（目录页 currentEntry 同口径：
 * 同章且块号不超过当前位置的最后一条；空目录返回 -1）。
 * 孤儿章节（本章在目录中没有任何链接，如只有部分介绍页进目录、
 * 下面第 x 章全是孤儿的套装书）归到最近的前一个有目录链接的章节；
 * 在第一条之前则取第一条。阅读页目录高亮与听书通知栏作者栏共用这一份口径。
 */
object TocSection {
    fun currentEntry(toc: List<EbookTocEntry>, chapter: Int, block: Int): Int {
        if (toc.isEmpty()) return -1
        var idx = toc.indexOfFirst { it.chapterIndex == chapter }
        toc.forEachIndexed { i, e ->
            if (e.chapterIndex == chapter && e.blockIndex <= block) idx = i
        }
        if (idx >= 0) return idx
        // 孤儿章节：归到最近的前一个有目录链接的章节
        idx = toc.indexOfLast { it.chapterIndex < chapter }
        return if (idx >= 0) idx else 0
    }
}
