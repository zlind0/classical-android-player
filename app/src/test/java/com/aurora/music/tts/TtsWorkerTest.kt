package com.aurora.music.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsWorkerTest {

    @Test
    fun chunkKey_stableAndSensitive() {
        val a = ttsChunkKey("internal", "", 100, 100, "你好世界")
        assertEquals(a, ttsChunkKey("internal", "", 100, 100, "你好世界"))
        assertFalse(a == ttsChunkKey("internal", "zh-CN-XiaoxiaoNeural", 100, 100, "你好世界"))
        assertFalse(a == ttsChunkKey("internal", "", 150, 100, "你好世界"))
        assertFalse(a == ttsChunkKey("internal", "", 100, 100, "你好世界！"))
        assertFalse(a == ttsChunkKey("", "", 100, 100, "你好世界"))
    }

    @Test
    fun pickVoice_explicitHit() {
        val vs = listOf(
            VoiceId("v1", "zh", "CN"),
            VoiceId("v2", "en", "US"),
        )
        assertEquals("v2", pickSystemVoiceName(vs, "v2"))
    }

    @Test
    fun pickVoice_autoPrefersCn() {
        val vs = listOf(
            VoiceId("en", "en", "US"),
            VoiceId("tw", "zh", "TW"),
            VoiceId("cn", "zh", "CN"),
        )
        // 显式不存在回退自动；简中优先
        assertEquals("cn", pickSystemVoiceName(vs, "nope"))
        assertEquals("cn", pickSystemVoiceName(vs, ""))
        assertEquals("cn", pickSystemVoiceName(vs, null))
        // 无简中有繁中/中文也要中文
        assertEquals("tw", pickSystemVoiceName(listOf(vs[0], vs[1]), null))
        // 无中文跟随系统
        assertNull(pickSystemVoiceName(listOf(vs[0]), null))
        assertNull(pickSystemVoiceName(emptyList(), null))
    }

    @Test
    fun memCache_hitAndEvictOldest() {
        val c = TtsMemCache(maxBytes = 32)
        c.put("a", ByteArray(10))
        c.put("b", ByteArray(10))
        assertEquals(10, c.get("a")!!.size)
        // a 刚被访问过，淘汰应先到 b
        c.put("c", ByteArray(20))
        assertEquals(30, c.sizeBytes())
        assertTrue(c.get("a") != null)
        assertNull(c.get("b"))
        assertTrue(c.get("c") != null)
    }

    @Test
    fun memCache_clear() {
        val c = TtsMemCache()
        c.put("a", ByteArray(8))
        c.clear()
        assertNull(c.get("a"))
        assertEquals(0, c.sizeBytes())
    }

    @Test
    fun generations_invalidate() {
        val g = TtsGenerations()
        val g1 = g.next(TtsOwner.EBOOK)
        assertTrue(g.isCurrent(TtsOwner.EBOOK, g1))
        val g2 = g.next(TtsOwner.EBOOK)
        assertFalse(g.isCurrent(TtsOwner.EBOOK, g1))
        assertTrue(g.isCurrent(TtsOwner.EBOOK, g2))
        // 跨 owner 互不干扰
        val i1 = g.next(TtsOwner.INTRO)
        assertTrue(g.isCurrent(TtsOwner.INTRO, i1))
        assertTrue(g.isCurrent(TtsOwner.EBOOK, g2))
    }
}
