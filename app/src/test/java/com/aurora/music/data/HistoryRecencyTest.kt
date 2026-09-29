package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRecencyTest {

    private fun ev(song: String, albumId: String, album: String, artistId: String, artist: String, ts: Long) =
        PlayEvent(song, "t", artist, album, albumId, artistId, "art-$albumId", 200, ts)

    @Test
    fun albums_orderByLatestPlayDeduped() {
        val evs = listOf(
            ev("s1", "a", "A专辑", "x", "X", 300), // A 最后一次 300
            ev("s2", "b", "B专辑", "y", "Y", 100),
            ev("s3", "a", "A专辑", "x", "X", 200), // A 旧一次 200
            ev("s4", "c", "C专辑", "z", "Z", 250),
        )
        val out = recentAlbumsFromHistory(evs)
        assertEquals(listOf("a", "c", "b"), out.map { it.id })
        assertEquals("A专辑", out[0].title)
    }

    @Test
    fun albums_blankIdDroppedAndLimited() {
        val evs = (1..12).map { i -> ev("s$i", "a$i", "专辑$i", "x", "X", i.toLong()) } +
            ev("sx", "", "无专辑", "x", "X", 999)
        val out = recentAlbumsFromHistory(evs, limit = 10)
        assertEquals(10, out.size)
        assertEquals("a12", out.first().id) // 时间最近的排最左
        assertEquals("a3", out.last().id)
    }

    @Test
    fun artists_orderByLatestPlayDeduped() {
        val evs = listOf(
            ev("s1", "a1", "A1", "x", "歌手X", 50),
            ev("s2", "a2", "A2", "y", "歌手Y", 900),
            ev("s3", "a3", "A3", "x", "歌手X", 100),
        )
        val out = recentArtistsFromHistory(evs)
        assertEquals(listOf("y", "x"), out.map { it.id })
        assertEquals("歌手Y", out[0].name)
    }

    @Test
    fun emptyHistory_returnsEmpty() {
        assertEquals(0, recentAlbumsFromHistory(emptyList()).size)
        assertEquals(0, recentArtistsFromHistory(emptyList()).size)
    }
}
