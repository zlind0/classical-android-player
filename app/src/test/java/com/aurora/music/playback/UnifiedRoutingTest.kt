package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedRoutingTest {

    @Test
    fun mode_dualStaysWhileEitherPlaying() {
        assertEquals(UnifiedSessionMode.DUAL, computeUnifiedMode(true, true, true))
        assertEquals(UnifiedSessionMode.DUAL, computeUnifiedMode(true, false, true))
        assertEquals(UnifiedSessionMode.DUAL, computeUnifiedMode(false, true, true))
    }

    @Test
    fun mode_dualStaysWhenExternallyPaused() {
        // 外部/系统双停后两路都停，但 dualArmed 还在：仍呈现 DUAL，等系统播放键双恢复。
        assertEquals(UnifiedSessionMode.DUAL, computeUnifiedMode(false, false, true))
    }

    @Test
    fun mode_singleFallsBack() {
        assertEquals(UnifiedSessionMode.BOOK, computeUnifiedMode(false, true, false))
        assertEquals(UnifiedSessionMode.MUSIC, computeUnifiedMode(true, false, false))
        // 两路都停且非 DUAL 时纯函数仍回 MUSIC，粘滞（暂停留当前 player）由 refreshArbitration 负责。
        assertEquals(UnifiedSessionMode.MUSIC, computeUnifiedMode(false, false, false))
    }

    @Test
    fun singlePlay_pauseWhatResumeWhat() {
        // 单书在播 -> 停书；单歌在播 -> 停歌。
        assertEquals(SinglePlayTarget.BOOK, resolveSinglePlayTarget(false, true, true, true))
        assertEquals(SinglePlayTarget.MUSIC, resolveSinglePlayTarget(true, false, false, false))
        // 书暂停（会话仍在书）-> 续书，不碰音乐旧队列。
        assertEquals(SinglePlayTarget.BOOK, resolveSinglePlayTarget(false, false, true, true))
        // 歌暂停（会话在音乐，无书断点或不在书会话）-> 动音乐。
        assertEquals(SinglePlayTarget.MUSIC, resolveSinglePlayTarget(false, false, false, false))
        assertEquals(SinglePlayTarget.MUSIC, resolveSinglePlayTarget(false, false, false, true))
        assertEquals(SinglePlayTarget.MUSIC, resolveSinglePlayTarget(false, false, true, false))
    }

    @Test
    fun nav_dualRoutesToBook() {
        assertEquals(SystemNavTarget.BOOK, routeSystemNav(true, true, true))
    }

    @Test
    fun nav_singleRoutesUnchanged() {
        // 只播书 → 书；只播音乐/啥都没播 → 音乐（与原来一样）。
        assertEquals(SystemNavTarget.BOOK, routeSystemNav(false, false, true))
        assertEquals(SystemNavTarget.MUSIC, routeSystemNav(false, true, false))
        assertEquals(SystemNavTarget.MUSIC, routeSystemNav(false, false, false))
        assertEquals(SystemNavTarget.MUSIC, routeSystemNav(true, false, false))
    }

    @Test
    fun dualMusicStub_defaultsToBookOnly() {
        val control = object : DualMusicControl {
            override fun isMusicPlaying(): Boolean = true
        }
        // 预留口默认不处理：系统上/下曲只动书。
        assertEquals(false, control.switchMusicFromSystem(+1))
        assertEquals(false, control.switchMusicFromSystem(-1))
    }
}
