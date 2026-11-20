package com.aurora.music.data.subtitle

import kotlin.math.max

/**
 * 「已处理区间」的集合运算：判断需要字幕的时间段是否都覆盖到了，没覆盖到的空洞从哪开始。
 *
 * 区间为章节内相对秒，列表有序且尽量合并。字幕进度必须按区间算：
 * 用户把进度条从 5 分钟拖到 20 分钟时，8–20 分钟是没解的，不能被当成已生成。
 */
object SubtitleCoverage {

    data class Span(val start: Double, val end: Double)

    /** 把 [start, end] 并入 [spans]：保持有序，重叠/相邻（≤ [joinGapSec]）的合并。 */
    fun add(
        spans: MutableList<Span>,
        start: Double,
        end: Double,
        joinGapSec: Double = 0.05,
    ) {
        if (end <= start) return
        var i = spans.indexOfFirst { it.start > start }
        if (i < 0) i = spans.size
        spans.add(i, Span(start, end))
        var j = max(0, i - 1)
        while (j + 1 < spans.size) {
            val a = spans[j]
            val b = spans[j + 1]
            if (b.start <= a.end + joinGapSec) {
                spans[j] = Span(a.start, max(a.end, b.end))
                spans.removeAt(j + 1)
            } else {
                j++
            }
        }
    }

    /**
     * [from, to) 内第一个还没处理过的位置；全部覆盖返回 null。
     *
     * [eps] 内的细小空隙视为已覆盖：解码块首尾相接、1 s 重叠都会产生亚秒级的接缝，
     * 不值得为它重开一次解码管线。
     */
    fun firstGap(
        spans: List<Span>,
        from: Double,
        to: Double,
        eps: Double = 0.35,
    ): Double? {
        if (to <= from + eps) return null
        var cursor = from
        for (s in spans) {
            if (s.end <= cursor + eps) continue
            if (s.start > cursor + eps) return cursor
            cursor = max(cursor, s.end)
            if (cursor >= to - eps) return null
        }
        return if (cursor >= to - eps) null else cursor
    }

    /** 是否整段覆盖（[firstGap] 为空的另一种说法）。 */
    fun covers(spans: List<Span>, from: Double, to: Double, eps: Double = 0.35): Boolean =
        firstGap(spans, from, to, eps) == null

    /**
     * 当前解码管线（从 [anchor] 顺序解到 [decodedUntil]）要不要为 [gap] 重开。
     *
     * - 管线已经在空洞起点之后（用户往回拖）：必须重开。
     * - 空洞就在管线前方一点点（重叠区 / 首块只解出几个采样）：继续往下解即可，
     *   重开反而会把解码器刚吐出来的那一小段丢掉，永远原地打转。
     * - 空洞远在前方（用户跳到后面）：重开，省得白解中间一大段。
     */
    fun needsRestart(
        gap: Double,
        anchor: Double,
        decodedUntil: Double,
        skipSec: Double = 2.0,
        backSec: Double = 0.5,
    ): Boolean = gap < anchor - backSec || gap > decodedUntil + skipSec

    /** 把零碎的片段区间按 [maxGapSec] 合并成连续块（旧缓存迁移用）。 */
    fun mergePoints(points: List<Span>, maxGapSec: Double): List<Span> {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        for (p in points.filter { it.end > it.start }.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && p.start <= last.end + maxGapSec) {
                out[out.size - 1] = Span(last.start, max(last.end, p.end))
            } else {
                out.add(Span(p.start, p.end))
            }
        }
        return out
    }
}
