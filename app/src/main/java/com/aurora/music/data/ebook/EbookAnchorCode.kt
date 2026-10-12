package com.aurora.music.data.ebook

import java.util.Calendar

// 电子书进度唯一编码：章:段（设定：只要章和段，不要块内字偏移）。
// - 编码对象是 chapters/blocks 下标，与目录 toc/headings 无关：
//   有些章节没有任何 heading 指向（孤儿章），按 toc 下标编码会跳不到它们。
// - 显示 1-based（用户可读可输入），内部 0-based；分页 pageIndex 绝不进编码
//   （字号/屏宽一变页就变，编码必须稳定）。
// - 格式："{章}:{段}"，如 "12:34"；解析容忍全角冒号、横杠、空格、"章/段"字样。
data class EbookAnchor(val chapter: Int, val block: Int)

object EbookAnchorCode {

    /** 内部下标（0-based）→ 显示编码（1-based）。 */
    fun encode(chapter: Int, block: Int): String =
        "${(chapter.coerceAtLeast(0)) + 1}:${(block.coerceAtLeast(0)) + 1}"

    /** 不依赖书的裸解析：返回 0-based (chapter, block)，失败返回 null。 */
    fun decodeRaw(input: String): Pair<Int, Int>? {
        val nums = Regex("\\d+").findAll(input.replace('：', ':')).map { it.value.toIntOrNull() ?: -1 }
            .filter { it > 0 }.toList()
        if (nums.isEmpty()) return null
        val chapter = nums[0] - 1
        val block = if (nums.size >= 2) nums[1] - 1 else 0
        if (chapter < 0 || block < 0) return null
        return chapter to block
    }

    /** 带书钳位的解析：下标越界就近钳到合法范围，空书返回 null。 */
    fun decode(input: String, book: ParsedEbook): EbookAnchor? {
        if (book.chapters.isEmpty()) return null
        val (c, b) = decodeRaw(input) ?: return null
        val chapter = c.coerceIn(0, book.chapters.size - 1)
        val blocks = book.chapters[chapter].blocks.size
        // 空章（无块）：块钳为 0，跳转时按章首处理
        val block = if (blocks <= 0) 0 else b.coerceIn(0, blocks - 1)
        return EbookAnchor(chapter, block)
    }

    /** 自然分钟桶（本地 wall-clock 分钟起始毫秒）：同分钟多次写入只留最后一条。 */
    fun minuteStart(atMillis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = atMillis
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
