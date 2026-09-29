package com.aurora.music.tts

import com.aurora.music.data.TTS_ENGINE_INTERNAL
import com.aurora.music.data.UnifiedTtsPrefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun sentences_chinese() {
        val t = "床前明月光，疑是地上霜。举头望明月，低头思故乡！"
        val rs = SentenceSplitter.split(t)
        assertEquals(2, rs.size)
        assertEquals(t, rs.joinToString("") { t.substring(it.first, it.last + 1) })
        assertEquals("床前明月光，疑是地上霜。", t.substring(rs[0].first, rs[0].last + 1))
    }

    @Test
    fun sentences_english() {
        val t = "Hello world. How are you? Fine!"
        val rs = SentenceSplitter.split(t)
        assertEquals(3, rs.size)
        assertEquals(t, rs.joinToString("") { t.substring(it.first, it.last + 1) })
    }

    @Test
    fun sentences_decimalNotSplit() {
        val t = "圆周率是 3.14，记住它。The value is 3.14. Got it?"
        val rs = SentenceSplitter.split(t)
        val texts = rs.map { t.substring(it.first, it.last + 1) }
        assertEquals(3, texts.size)
        assertTrue(texts[0].contains("3.14"))
        assertTrue(texts[1].contains("3.14"))
    }

    @Test
    fun sentences_groupedPunctAndQuotes() {
        val t = "他说：“真的吗？！”然后走了。Wait?! Really… yes."
        val rs = SentenceSplitter.split(t)
        val texts = rs.map { t.substring(it.first, it.last + 1) }
        // 中文 ？！” 收拢为一句，英文 ?! / … 各收拢
        assertEquals(5, texts.size)
        assertTrue(texts[0].endsWith("？！”"))
        assertEquals(t, texts.joinToString(""))
    }

    @Test
    fun sentences_noPunctAndBlank() {
        assertEquals(1, SentenceSplitter.split("无标点整块一句").size)
        assertTrue(SentenceSplitter.split("   ").isEmpty())
        // 换行断句，不丢字
        val t = "第一行\n第二行"
        val rs = SentenceSplitter.split(t)
        assertEquals(2, rs.size)
        assertEquals(t, rs.joinToString("") { t.substring(it.first, it.last + 1) })
    }
    @Test
    fun mono24k_upasmples2x() {
        val mono = byteArrayOf(0, 0, 0x10, 0x27) // 0, 10000
        val out = TtsWav.mono24kToStereo48k(mono)
        assertEquals(2 * 2 * 4, out.size)
    }

    @Test
    fun gain_passthroughAndScale() {
        // 1000, -2000（小端 16bit）
        val pcm = byteArrayOf(0xE8.toByte(), 0x03, 0x30, 0xF8.toByte())
        assertArrayEquals(pcm, TtsWav.applyGainStereo16(pcm, 1f))
        // 50% → 500, -1000
        val half = TtsWav.applyGainStereo16(pcm, 0.5f)
        assertEquals(500, java.nio.ByteBuffer.wrap(half).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(0).toInt())
        assertEquals(-1000, java.nio.ByteBuffer.wrap(half).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(2).toInt())
        // 空输入不崩
        assertEquals(0, TtsWav.applyGainStereo16(ByteArray(0), 2f).size)
    }

    @Test
    fun gain_clampsAtFullScale() {
        // 20000 × 2.0 = 40000 → 钳到 32767，不卷绕
        val pcm = byteArrayOf(0x20, 0x4E, 0x20, 0x4E)
        val out = TtsWav.applyGainStereo16(pcm, 2f)
        val s = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(0).toInt()
        assertEquals(32767, s)
    }

    @Test
    fun effectiveGain_systemClampedInternalFull() {
        val sys = UnifiedTtsPrefs(engine = "", volume = 2f)
        assertEquals(1f, ttsEffectiveGain(sys))
        val sysLow = UnifiedTtsPrefs(engine = "", volume = 0.5f)
        assertEquals(0.5f, ttsEffectiveGain(sysLow))
        val internal = UnifiedTtsPrefs(engine = TTS_ENGINE_INTERNAL, volume = 2f)
        assertEquals(2f, ttsEffectiveGain(internal))
        val internalLow = UnifiedTtsPrefs(engine = TTS_ENGINE_INTERNAL, volume = 0.2f)
        assertEquals(0.2f, ttsEffectiveGain(internalLow))
    }

    @Test
    fun chunkKey_volumeSensitive() {
        val a = ttsChunkKey("internal", "", 100, 100, "你好世界", 100)
        assertEquals(a, ttsChunkKey("internal", "", 100, 100, "你好世界", 100))
        assertFalse(a == ttsChunkKey("internal", "", 100, 100, "你好世界", 200))
        // 旧 5 参调用默认 100，与显式 100 一致
        assertEquals(a, ttsChunkKey("internal", "", 100, 100, "你好世界"))
    }
}
