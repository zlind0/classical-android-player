package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.titlemerge.MergeInput
import com.aurora.titlemerge.MergedItem
import com.aurora.titlemerge.MergedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun song(title: String, index: Int, path: String = "/music/$title.flac"): Song =
    Song(
        id = "t$index",
        title = title,
        artist = "Bach",
        album = "Bach",
        artworkUrl = "",
        durationSec = 60,
        path = path,
    )

/** 引擎逻辑见 :lib-titlemerge；这里只测 app 适配（查表映射 + 曲库开关）。 */
class TitleMergeTest {

    @Test
    fun mapMergeRows_identityWhenNoTable() {
        val songs = listOf(song("A", 0), song("B", 1))
        val rows = mapMergeRows(null, songs)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is AlbumRow.Single })
    }

    @Test
    fun mapMergeRows_mapsLibRowsToSongs() {
        val songs = listOf(song("Symphony No.1 I", 0), song("Symphony No.1 II", 1))
        val lib = listOf(
            MergedRow.Group("Symphony No.1", listOf(MergedItem(0, "I"), MergedItem(1, "II"))),
        )
        val rows = mapMergeRows(lib, songs)
        val g = rows.single() as AlbumRow.Group
        assertEquals("Symphony No.1", g.major)
        assertEquals(listOf("t0", "t1"), g.items.map { it.song.id })
        assertEquals(listOf(0, 1), g.items.map { it.index })
        assertEquals("II", g.items[1].minor)
    }

    @Test
    fun lookupMergedTitle_hitsAndMisses() {
        val songs = listOf(song("Symphony No.1 I", 0), song("Symphony No.1 II", 1))
        val lib = listOf(
            MergedRow.Group("Symphony No.1", listOf(MergedItem(0, "I"), MergedItem(1, "II"))),
        )
        assertEquals("Symphony No.1" to "II", lookupMergedTitle(lib, songs, "t1"))
        assertNull(lookupMergedTitle(lib, songs, "nope"))
        assertNull(lookupMergedTitle(null, songs, "t1"))
    }

    @Test
    fun mergeEnabledFor_respectsRoots() {
        val roots = listOf(
            MusicRoot(1, "/a", "A", StorageType.INTERNAL, mergeTitles = true),
            MusicRoot(2, "/b", "B", StorageType.INTERNAL, mergeTitles = false),
        )
        val inA = listOf(song("X 1", 0, "/a/x.flac"), song("X 2", 1, "/a/y.flac"))
        val mixed = inA + listOf(song("X 3", 2, "/b/z.flac"))
        assertTrue(mergeEnabledFor(emptyList(), mixed))
        assertTrue(mergeEnabledFor(roots, inA))
        assertTrue(!mergeEnabledFor(roots, mixed))
    }

    @Test
    fun naturalOrder_numericChunks() {
        val titles = listOf(
            "Symphony No.11", "Symphony No.2", "Symphony No.1",
            "Album 10", "Album 2", "album 1", "Op.2", "Op.10", "Op.1",
        )
        assertEquals(
            listOf(
                "album 1", "Album 2", "Album 10",
                "Op.1", "Op.2", "Op.10",
                "Symphony No.1", "Symphony No.2", "Symphony No.11",
            ),
            titles.sortedWith(naturalStringOrder),
        )
    }

    @Test
    fun libEngine_smoke() {
        // 库本身行为由 :lib-titlemerge 单测覆盖；这里只确认接得上
        val lib = com.aurora.titlemerge.mergeTracks(
            listOf(MergeInput("a", "Nocturne Op.9 No.1"), MergeInput("b", "Nocturne Op.9 No.2")),
        )
        assertEquals(1, lib.size)
        assertTrue(lib.single() is MergedRow.Group)
    }
}
