package com.aurora.music.ui.ebook

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.aurora.music.data.ebook.EbookBlock

// 章节内分：用 TextMeasurer 按真实行高把块切成恰好一屏的页。
// 结果只含 (blockIndex, startChar, endChar)，可 JSON 落盘，下次同字号/字体/屏宽直接读。
// v1 不做跨章预排：只排当前章（及缓存命中时整本），大书首开不卡。

data class PageSlice(val block: Int, val start: Int, val end: Int)

/** 缓存形态：每章 → 每页 → [block,start,end]* */
fun pagesToCache(all: Map<Int, List<List<PageSlice>>>): List<List<List<Int>>> {
    val max = (all.keys.maxOrNull() ?: -1) + 1
    return (0 until max).map { ci ->
        all[ci]?.map { page -> page.flatMap { listOf(it.block, it.start, it.end) } }.orEmpty()
    }
}

fun pagesFromCache(cached: List<List<List<Int>>>): Map<Int, List<List<PageSlice>>> {
    val out = mutableMapOf<Int, List<List<PageSlice>>>()
    cached.forEachIndexed { ci, pages ->
        out[ci] = pages.map { flat ->
            flat.chunked(3).filter { it.size == 3 }.map { PageSlice(it[0], it[1], it[2]) }
        }
    }
    return out
}

class ChapterPaginator(
    private val measurer: androidx.compose.ui.text.TextMeasurer,
    private val density: Density,
    private val widthPx: Int,
    private val maxHeightPx: Int,
    private val baseFontSp: Float,
    private val fontFamily: FontFamily,
) {
    private fun styleFor(level: Int): TextStyle {
        val mult = when (level) {
            1 -> 1.45f
            2 -> 1.28f
            3 -> 1.16f
            4, 5, 6 -> 1.08f
            else -> 1f
        }
        return TextStyle(
            fontSize = (baseFontSp * mult).sp,
            lineHeight = (baseFontSp * mult * 1.55f).sp,
            fontFamily = fontFamily,
            fontWeight = if (level in 1..6) FontWeight.Bold else FontWeight.Normal,
        )
    }

    private fun spacingBeforePx(level: Int): Int = with(density) {
        if (level in 1..6) (baseFontSp * 0.7f).sp.toPx().toInt() else 0
    }

    private fun spacingAfterPx(level: Int): Int = with(density) {
        (if (level in 1..6) baseFontSp * 0.45f else baseFontSp * 0.55f).sp.toPx().toInt()
    }

    private fun measureHeight(text: String, style: TextStyle): Int {
        if (text.isEmpty()) return 0
        val r = measurer.measure(
            text = AnnotatedString(text),
            style = style,
            constraints = Constraints(maxWidth = widthPx),
            overflow = TextOverflow.Visible,
            maxLines = Int.MAX_VALUE,
        )
        return r.size.height
    }

    fun paginate(blocks: List<EbookBlock>): List<List<PageSlice>> {
        val pages = mutableListOf<MutableList<PageSlice>>()
        var cur = mutableListOf<PageSlice>()
        var used = 0
        fun flush() {
            if (cur.isNotEmpty()) {
                pages.add(cur)
                cur = mutableListOf()
                used = 0
            }
        }
        blocks.forEachIndexed { bi, b ->
            val text = b.text
            if (text.isBlank()) return@forEachIndexed
            val style = styleFor(b.level)
            val before = if (cur.isEmpty()) 0 else spacingBeforePx(b.level)
            val fullH = measureHeight(text, style)
            // 标题孤儿控制：标题 + 至少两行正文都放不下就整块换页
            if (b.level in 1..6 && cur.isNotEmpty() && before + fullH + (baseFontSp * 2 * 1.55f * density.density / 1f).toInt() > maxHeightPx - used && fullH < maxHeightPx) {
                val twoLines = with(density) { (baseFontSp * 2 * 1.55f).sp.toPx().toInt() }
                if (before + fullH + twoLines > maxHeightPx - used) flush()
            }
            var start = 0
            var first = true
            while (start < text.length) {
                val avail = maxHeightPx - used - (if (first) before else 0)
                val rest = text.substring(start)
                val restH = measureHeight(rest, style)
                if (restH <= avail) {
                    cur.add(PageSlice(bi, start, text.length))
                    used += (if (first) before else 0) + restH + spacingAfterPx(b.level)
                    break
                }
                // 放不下：二分能放多少字
                if (avail <= 0) {
                    flush()
                    first = false
                    continue
                }
                var lo = 1
                var hi = rest.length
                var fit = 0
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    // 尽量不断词：中文无所谓，西文回退到空格
                    if (measureHeight(rest.substring(0, mid), style) <= avail) {
                        fit = mid
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                if (fit <= 0) {
                    flush()
                    first = false
                    continue
                }
                // 西文断词回退
                var cut = fit
                if (fit < rest.length && rest[fit].isLetterOrDigit()) {
                    val back = rest.substring(0, fit).lastIndexOf(' ')
                    if (back > fit - 20 && back > 0) cut = back + 1
                }
                cur.add(PageSlice(bi, start, start + cut))
                flush()
                start += cut
                first = false
            }
        }
        flush()
        return pages.filter { it.isNotEmpty() }
    }
}
