package com.aurora.music.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsUnitTest {

    @Test
    fun chunker_shortPassthrough() {
        assertEquals(listOf("你好世界"), Chunker.split("你好世界"))
        assertTrue(Chunker.split("   ").isEmpty())
    }

    @Test
    fun chunker_longSplitsOnPunctuation() {
        val t = "春眠不觉晓，处处闻啼鸟。夜来风雨声，花落知多少。" + "abcdefghij".repeat(50)
        val parts = Chunker.split(t, 60)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= 60 })
        // 不丢字
        assertEquals(t.filterNot { it == ' ' }, parts.joinToString("").filterNot { it == ' ' })
    }

    @Test
    fun ssml_escapesAndRate() {
        val v = MsVoices.byCode("zh-CN-XiaoxiaoNeural")!!
        val s = SsmlBuilder.build(v, "A<B>&\"'", 1.5f, 0.5f)
        assertTrue(s.contains("A&lt;B&gt;&amp;&quot;&apos;"))
        assertTrue(s.contains("rate='50%'"))
        assertTrue(s.contains("pitch='-50%'"))
        assertTrue(s.contains("name='zh-CN-XiaoxiaoNeural'"))
    }

    @Test
    fun wav_roundTrip() {
        val pcm = ByteArray(16) { it.toByte() }
        val wav = TtsWav.encodeWav(24000, 1, 16, pcm)
        val d = TtsWav.decodeWav(wav)
        assertEquals(24000, d.sampleRate)
        assertEquals(1, d.channels)
        assertEquals(16, d.bits)
        assertArrayEquals(pcm, d.data)
    }

    @Test
    fun normalize_mono24k_identityRate() {
        // 48k 立体声输入应接近直通（只做格式对齐）
        val frames = 48
        val mono = ByteArray(frames * 2)
        var v = 0
        for (i in 0 until frames) {
            v = (v + 1000) % 30000
            mono[i * 2] = (v and 0xFF).toByte()
            mono[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val pcm = TtsWav.Pcm(48000, 1, 16, mono)
        val out = TtsWav.normalize48kStereo16(pcm)
        assertEquals(frames * 4, out.size)
        // 第 0 帧左右声道相等
        assertEquals(mono[0], out[0])
        assertEquals(mono[1], out[1])
        assertEquals(mono[0], out[2])
        assertEquals(mono[1], out[3])
    }

    @Test
    fun mono24k_upasmples2x() {
        val mono = byteArrayOf(0, 0, 0x10, 0x27) // 0, 10000
        val out = TtsWav.mono24kToStereo48k(mono)
        assertEquals(2 * 2 * 4, out.size)
    }
}
