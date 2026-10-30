package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPositionTest {

    @Test
    fun settle_singleZeroIsIgnoredWhilePlaying() {
        val held = settlePosition(
            mediaId = "song",
            positionMs = 0L,
            playing = true,
            previous = PositionState("song", 42_000L),
            nowMs = 1_000L,
        )
        val state = requireNotNull(held)
        // 单次 0 不落 UI：保持上一次显示位置，并记住第一次看到 0 的时刻
        assertEquals("song", state.mediaId)
        assertEquals(42_000L, state.positionMs)
        assertTrue(state.zeroSinceMs >= 0L)
    }

    @Test
    fun settle_zeroAcceptedAfterWindow() {
        val held = settlePosition(
            mediaId = "song",
            positionMs = 0L,
            playing = true,
            previous = PositionState("song", 42_000L, zeroSinceMs = 1_000L),
            nowMs = 1_400L,
            windowMs = 300L,
        )
        // 用户真把进度拖到 0：连续 0 超过窗口后生效
        assertEquals(0L, requireNotNull(held).positionMs)
        assertEquals(-1L, requireNotNull(held).zeroSinceMs)
    }

    @Test
    fun settle_zeroAcceptedAcrossTracksOrWhilePaused() {
        // 换曲的 0 是“从头开始”，立即接受
        assertEquals(
            0L,
            requireNotNull(settlePosition("next", 0L, true, PositionState("song", 42_000L), 1_000L)).positionMs,
        )
        // 暂停在 0 位也立即接受
        assertEquals(
            0L,
            requireNotNull(settlePosition("song", 0L, false, PositionState("song", 42_000L), 1_000L)).positionMs,
        )
        // 没有旧样本时接受
        assertEquals(
            0L,
            requireNotNull(settlePosition("song", 0L, true, null, 1_000L)).positionMs,
        )
    }

    @Test
    fun settle_positivePositionWins() {
        val state = requireNotNull(
            settlePosition("song", 12_500L, true, PositionState("song", 42_000L), 1_000L),
        )
        assertEquals(12_500L, state.positionMs)
        assertEquals(-1L, state.zeroSinceMs)
    }

    @Test
    fun resolve_snapshotWinsDuringDualPlayback() {
        // 双播：会话是书（ebook_tts，位置恒 0），快照才是真实音乐
        val r = requireNotNull(
            resolvePosition(
                snapshotMediaId = "song",
                snapshotPositionMs = 61_000L,
                snapshotPlaying = true,
                sessionMediaId = "ebook_tts",
                sessionPositionMs = 0L,
                sessionPlaying = true,
                knownMediaIds = setOf("song"),
                previous = null,
                nowMs = 1_000L,
            ),
        )
        assertEquals("song", r.mediaId)
        assertEquals(61_000L, r.positionMs)
    }

    @Test
    fun resolve_fallsBackToSessionOnlyForKnownSong() {
        val known = resolvePosition(
            snapshotMediaId = "",
            snapshotPositionMs = 0L,
            snapshotPlaying = false,
            sessionMediaId = "song",
            sessionPositionMs = 9_000L,
            sessionPlaying = true,
            knownMediaIds = setOf("song"),
            previous = null,
            nowMs = 1_000L,
        )
        assertEquals(9_000L, requireNotNull(known).positionMs)

        // 会话是朗读占位：不能用它的 0 位覆盖 UI，返回 null 表示保持不动
        val placeholder = resolvePosition(
            snapshotMediaId = "",
            snapshotPositionMs = 0L,
            snapshotPlaying = false,
            sessionMediaId = "ebook_tts",
            sessionPositionMs = 0L,
            sessionPlaying = true,
            knownMediaIds = setOf("song"),
            previous = PositionState("song", 61_000L),
            nowMs = 1_000L,
        )
        assertNull(placeholder)
    }

    @Test
    fun resolve_singleZeroInSnapshotKeepsDisplayedPosition() {
        val r = requireNotNull(
            resolvePosition(
                snapshotMediaId = "song",
                snapshotPositionMs = 0L,
                snapshotPlaying = true,
                sessionMediaId = "ebook_tts",
                sessionPositionMs = 0L,
                sessionPlaying = true,
                knownMediaIds = setOf("song"),
                previous = PositionState("song", 30_000L),
                nowMs = 1_000L,
            ),
        )
        assertEquals(30_000L, r.positionMs)
    }

    @Test
    fun transitionOnlyResetsOnKnownDifferentSong() {
        val known = setOf("a", "b")
        assertTrue(shouldResetPositionOnTransition("b", "a", known))
        assertFalse(shouldResetPositionOnTransition("a", "a", known))
        assertFalse(shouldResetPositionOnTransition("ebook_tts", "a", known))
        assertFalse(shouldResetPositionOnTransition("unknown", "a", known))
        assertFalse(shouldResetPositionOnTransition(null, "a", known))
    }
}
