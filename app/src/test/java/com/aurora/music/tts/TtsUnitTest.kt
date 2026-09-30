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
    fun upsample24k_evenPassthroughAndDcGain() {
        // 偶样点必须原样直通；常数信号直流增益为 1（无响度跳变）
        val samples = intArrayOf(0, 10000, -10000, 32767, -32768, 1234)
        val mono = ByteArray(samples.size * 2)
        val ib = java.nio.ByteBuffer.wrap(mono).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEachIndexed { i, s -> ib.putShort(i * 2, s.toShort()) }
        val out = TtsWav.upsample24kTo48kMono(mono)
        assertEquals(samples.size * 2 * 2, out.size)
        val ob = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEachIndexed { i, s -> assertEquals(s, ob.getShort(i * 4).toInt()) }
        // 常数 1000：奇样点也应 ≈1000（归一化抽头保证直流通过）
        val flat = ByteArray(64 * 2)
        val fb = java.nio.ByteBuffer.wrap(flat).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 64) fb.putShort(i * 2, 1000.toShort())
        val fout = TtsWav.upsample24kTo48kMono(flat)
        val fob = java.nio.ByteBuffer.wrap(fout).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 64 * 2) assertEquals(1000, fob.getShort(i * 2).toInt())
    }

    @Test
    fun upsample24k_sineMidpointBeatsLinear() {
        // 6kHz 正弦 @24k：线性中点误差 ≈0.207·A；带限内插应远小于它
        val amp = 10000.0
        val n = 96
        val mono = ByteArray(n * 2)
        val ib = java.nio.ByteBuffer.wrap(mono).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            ib.putShort(i * 2, (amp * Math.sin(2 * Math.PI * 6000 * i / 24000)).toInt().toShort())
        }
        val out = TtsWav.upsample24kTo48kMono(mono)
        val ob = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var maxErr = 0
        for (i in 4 until n - 4) { // 掐头去尾，避开边缘钳位
            val ideal = amp * Math.sin(2 * Math.PI * 6000 * (2 * i + 1) / 48000)
            val err = Math.abs(ob.getShort((2 * i + 1) * 2).toInt() - ideal).toInt()
            if (err > maxErr) maxErr = err
        }
        assertTrue("midpoint maxErr=$maxErr", maxErr < 800)
    }

    @Test
    fun mono48k_stereoDuplicates() {
        val mono = byteArrayOf(0xE8.toByte(), 0x03, 0x30, 0xF8.toByte()) // 1000, -2000
        val out = TtsWav.mono48kToStereo48k(mono)
        assertEquals(2 * 4, out.size)
        val ob = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000, ob.getShort(0).toInt())
        assertEquals(1000, ob.getShort(2).toInt())
        assertEquals(-2000, ob.getShort(4).toInt())
        assertEquals(-2000, ob.getShort(6).toInt())
        assertEquals(0, TtsWav.mono48kToStereo48k(ByteArray(0)).size)
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

    @Test
    fun speakable_punctOnlySkipped() {
        // 卡死 bug 回归：纯标点/空白/符号必须不可读，调用方直接跳过
        assertFalse(isSpeakable(""))
        assertFalse(isSpeakable("   "))
        assertFalse(isSpeakable("."))
        assertFalse(isSpeakable("。"))
        assertFalse(isSpeakable("？！"))
        assertFalse(isSpeakable("……"))
        assertFalse(isSpeakable("——"))
        assertFalse(isSpeakable("「」"))
        // 含任一字母/数字（含 CJK）即值得合成
        assertTrue(isSpeakable("你好"))
        assertTrue(isSpeakable("a"))
        assertTrue(isSpeakable("3.14"))
        assertTrue(isSpeakable("。你好"))
        assertTrue(isSpeakable("word."))
    }
}
