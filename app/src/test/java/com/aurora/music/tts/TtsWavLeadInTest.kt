package com.aurora.music.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前置静音（吃掉 ExoPlayer 每次 flush 的 20ms 淡入斜坡）。 */
class TtsWavLeadInTest {

    @Test
    fun prependSilenceAddsExactFramesAndKeepsPcm() {
        val pcm = ByteArray(64) { 7 }
        val out = TtsWav.prependSilence16(pcm, 48000, 2, 25)
        // 25ms @48k = 1200 帧，立体声 16bit = 4800 字节
        assertEquals(4800 + pcm.size, out.size)
        assertTrue("前置部分必须全是 0", out.copyOfRange(0, 4800).all { it == 0.toByte() })
        assertEquals(pcm.toList(), out.copyOfRange(4800, out.size).toList())
    }

    @Test
    fun nonPositiveMsReturnsInputUnchanged() {
        val pcm = ByteArray(16) { 3 }
        assertEquals(pcm, TtsWav.prependSilence16(pcm, 48000, 2, 0))
        assertEquals(pcm, TtsWav.prependSilence16(pcm, 48000, 2, -5))
    }
}
