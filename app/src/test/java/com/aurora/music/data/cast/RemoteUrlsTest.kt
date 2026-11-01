package com.aurora.music.data.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUrlsTest {

    private val device = "8a1f2d4e-7c3b-4a55-9f10-2b6e0c9d1234"

    @Test
    fun buildsTransportIndependentUrls() {
        val music = RemoteUrls.music(device, "track 42")
        assertEquals("pod-remote://device/$device/media/music?id=track+42", music)
        assertTrue(RemoteUrls.isVirtual(music))
        assertTrue(RemoteUrls.isRemoteStream(music))
        assertEquals(device, RemoteUrls.deviceIdOf(music))
    }

    @Test
    fun resolvesAgainstCurrentEndpoint() {
        val virtual = RemoteUrls.music(device, "track 42")
        val base = "http://192.168.1.23:8080"
        assertEquals(
            "$base/media/music?id=track+42",
            RemoteUrls.resolve(virtual) { if (it == device) base else null },
        )
        val relayBase = "http://127.0.0.1:47001"
        assertEquals(
            "$relayBase/media/music?id=track+42",
            RemoteUrls.resolve(virtual) { relayBase },
        )
    }

    @Test
    fun keepsNonVirtualUrlsAndUnknownEndpoints() {
        val plain = "http://192.168.1.23:8080/media/music?id=x"
        assertEquals(plain, RemoteUrls.resolve(plain) { "http://127.0.0.1:1" })
        assertFalse(RemoteUrls.isVirtual(plain))

        val virtual = RemoteUrls.audiobook(device, "/books/有声书/01 第一章.mp3")
        // 端点未知时原样返回，交给上层按失败处理。
        assertEquals(virtual, RemoteUrls.resolve(virtual) { null })
        assertEquals(device, RemoteUrls.deviceIdOf(virtual))
    }

    @Test
    fun encodesAudiobookPaths() {
        val virtual = RemoteUrls.audiobook(device, "/books/第一章.mp3")
        val resolved = RemoteUrls.resolve(virtual) { "http://10.0.0.2:9000" }
        assertEquals(
            "http://10.0.0.2:9000/media/audiobook?path=%2Fbooks%2F%E7%AC%AC%E4%B8%80%E7%AB%A0.mp3",
            resolved,
        )
    }

    @Test
    fun rejectsUnrelatedSchemes() {
        assertNull(RemoteUrls.deviceIdOf("http://x/device/a/media/music?id=1"))
        assertFalse(RemoteUrls.isRemoteStream("file:///sdcard/a.mp3"))
        assertFalse(RemoteUrls.isRemoteStream("content://media/external/audio/1"))
    }
}
