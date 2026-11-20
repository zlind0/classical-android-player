package com.aurora.music.data.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCoverageTest {

    @Test
    fun skippedRangeIsReportedAsGap() {
        // 5→6 分钟听过，然后直接跳到 20 分钟：8 分钟的位置仍然是空洞
        val spans = ArrayList<SubtitleCoverage.Span>()
        SubtitleCoverage.add(spans, 300.0, 361.0)
        SubtitleCoverage.add(spans, 1198.0, 1258.0)
        assertEquals(480.0, SubtitleCoverage.firstGap(spans, 480.0, 540.0)!!, 1e-6)
        // 从已覆盖的地方继续听不用重新生成
        assertNull(SubtitleCoverage.firstGap(spans, 310.0, 360.0))
    }

    @Test
    fun gapStartsAtEndOfLastCoveredBlock() {
        val spans = listOf(SubtitleCoverage.Span(100.0, 160.0))
        assertEquals(160.0, SubtitleCoverage.firstGap(spans, 120.0, 220.0)!!, 1e-6)
    }

    @Test
    fun emptySpansMeansEverythingIsMissing() {
        assertEquals(42.0, SubtitleCoverage.firstGap(emptyList(), 42.0, 100.0)!!, 1e-6)
    }

    @Test
    fun addMergesOverlappingAndAdjacentSpans() {
        val spans = ArrayList<SubtitleCoverage.Span>()
        SubtitleCoverage.add(spans, 0.0, 10.0)
        SubtitleCoverage.add(spans, 10.0, 20.0)
        SubtitleCoverage.add(spans, 5.0, 7.0)
        SubtitleCoverage.add(spans, 30.0, 40.0)
        assertEquals(2, spans.size)
        assertEquals(0.0, spans[0].start, 1e-6)
        assertEquals(20.0, spans[0].end, 1e-6)
        assertEquals(30.0, spans[1].start, 1e-6)
    }

    @Test
    fun addKeepsSpansSortedWhenSeekingBackwards() {
        val spans = ArrayList<SubtitleCoverage.Span>()
        SubtitleCoverage.add(spans, 600.0, 660.0)
        SubtitleCoverage.add(spans, 60.0, 120.0)
        assertTrue(spans.zipWithNext().all { (a, b) -> a.start < b.start })
        assertNull(SubtitleCoverage.firstGap(spans, 60.0, 120.0))
    }

    @Test
    fun tinySeamCountsAsCovered() {
        val spans = listOf(
            SubtitleCoverage.Span(0.0, 10.0),
            SubtitleCoverage.Span(10.2, 20.0),
        )
        assertNull(SubtitleCoverage.firstGap(spans, 0.0, 20.0))
    }

    @Test
    fun doesNotRestartWhenGapIsJustAheadOfPipeline() {
        // 重叠区：管线从 95 开始解，空洞在 96，当前只解到 95.0000625（首块只有 1 个采样）
        assertTrue(
            !SubtitleCoverage.needsRestart(
                gap = 96.0,
                anchor = 95.0,
                decodedUntil = 95.0000625,
            ),
        )
        // 正常连续解码：空洞就在管线末端
        assertTrue(!SubtitleCoverage.needsRestart(gap = 105.0, anchor = 95.0, decodedUntil = 105.0))
    }

    @Test
    fun restartsWhenUserJumpsForwardOrBackward() {
        // 从 5 分钟跳到 20 分钟：管线还在前面，直接重开
        assertTrue(SubtitleCoverage.needsRestart(gap = 1200.0, anchor = 95.0, decodedUntil = 105.0))
        // 从 20 分钟拖回 8 分钟：管线在空洞后面，必须重开
        assertTrue(SubtitleCoverage.needsRestart(gap = 310.0, anchor = 1200.0, decodedUntil = 1260.0))
    }

    @Test
    fun mergePointsRestoresBlocksFromLegacyCues() {
        val merged = SubtitleCoverage.mergePoints(
            listOf(
                SubtitleCoverage.Span(100.0, 103.0),
                SubtitleCoverage.Span(104.0, 106.0),
                SubtitleCoverage.Span(900.0, 903.0),
            ),
            6.0,
        )
        assertEquals(2, merged.size)
        assertEquals(100.0, merged[0].start, 1e-6)
        assertEquals(106.0, merged[0].end, 1e-6)
    }
}
