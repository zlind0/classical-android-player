package com.aurora.titlemerge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun tracks(vararg titles: String): List<MergeInput> =
    titles.mapIndexed { i, t -> MergeInput("t$i", t) }

private fun majors(rows: List<MergedRow>): List<String> = rows.map {
    when (it) {
        is MergedRow.Single -> "single"
        is MergedRow.Group -> "group:${it.major} x${it.items.size}"
    }
}

class TitleMergeTest {

    @Test
    fun symphony_splitsByWork() {
        val rows = mergeTracks(
            tracks(
                "Symphony No.1 I. Adagio", "Symphony No.1 II. Allegro",
                "Symphony No.1 III. Menuetto", "Symphony No.1 IV. Finale",
                "Symphony No.2 I. Adagio", "Symphony No.2 II. Allegro",
                "Symphony No.2 III. Scherzo", "Symphony No.2 IV. Finale",
            ),
        )
        assertEquals(listOf("group:Symphony No.1 x4", "group:Symphony No.2 x4"), majors(rows))
    }

    @Test
    fun goldberg_singleWordStubNeverMerges() {
        val titles = listOf("Goldberg Aria") + (1..17).map { "Variatio $it a 1 Clav." }
        val rows = mergeTracks(tracks(*titles.toTypedArray()))
        assertEquals(18, rows.size)
        assertTrue(rows.all { it is MergedRow.Single })
    }

    @Test
    fun artistStampStripped_frontSingles_workGroups() {
        val rows = mergeTracks(
            tracks(
                "Bach - Orchestral Suite No.4 in D major",
                "Bach - Fantasia and Fugue in G minor",
                "Bach - Fantasia in G major, BWV 572",
                "Bach - Passacaglia and Fugue in C minor A",
                "Bach - Passacaglia and Fugue in C minor B",
                "Bach - Toccata and Fugue in D minor A",
                "Bach - Toccata and Fugue in D minor B",
                "Bach - Toccata and Fugue in F major",
            ),
        )
        val g4 = rows.filterIsInstance<MergedRow.Group>()
        assertTrue(g4.none { it.major == "Bach -" || it.major == "Bach" })
        assertTrue(g4.any { it.major == "Passacaglia and Fugue in C minor" && it.items.size == 2 })
        assertTrue(g4.any { it.major == "Toccata and Fugue in D minor" && it.items.size == 2 })
        // 前三个落单
        assertEquals(3, rows.take(3).filterIsInstance<MergedRow.Single>().size)
    }

    @Test
    fun beethovenStamp_groupNeverWins() {
        val rows = mergeTracks(
            tracks(
                "Beethoven - Grosse Fuge in B flat, Op.133",
                "Beethoven - Missa Solemnis in D major Kyrie",
                "Beethoven - Missa Solemnis in D major Gloria",
                "Beethoven - Coriolan Overture, Op.62",
                "Beethoven - Egmont Overture, Op.84",
                "Beethoven - Toccata A part one",
                "Beethoven - Toccata A part two",
            ),
        )
        assertTrue(rows.none { it is MergedRow.Group && it.major == "Beethoven -" })
        val missa = rows.filterIsInstance<MergedRow.Group>().firstOrNull { it.items.size == 2 }
        assertEquals("Missa Solemnis in D major", missa?.major)
    }

    @Test
    fun identicalTitlesNeverMerge() {
        assertEquals(2, mergeTracks(tracks("Same", "Same")).size)
    }

    @Test
    fun oneWordPairLowCoverageStaysSingle() {
        val all = (1..10).map { "Work $it movement" } + listOf("Fantasia alpha", "Fantasia beta")
        val rows = mergeTracks(tracks(*all.toTypedArray()))
        assertTrue(rows.takeLast(2).all { it is MergedRow.Single })
    }

    @Test
    fun nonConsecutiveSamePrefixStaysSeparate() {
        val rows = mergeTracks(
            tracks(
                "Alpha Beta Gamma one", "Alpha Beta Gamma two",
                "Intermezzo",
                "Delta Epsilon Zeta one", "Delta Epsilon Zeta two",
            ),
        )
        assertEquals(
            listOf("group:Alpha Beta Gamma x2", "single", "group:Delta Epsilon Zeta x2"),
            majors(rows),
        )
    }

    @Test
    fun shortMajorNeedsHalfCoverage() {
        val rows = mergeTracks(
            tracks(
                "Bach X convexity", "Bach X concavity",
                "Bach Alpha", "Bach Beta", "Intermezzo", "Etude",
            ),
        )
        assertTrue(rows.all { it is MergedRow.Single })
    }

    @Test
    fun mergedTitleOf_resolvesMajorMinor() {
        val input = tracks("Symphony No.1 I. Adagio", "Symphony No.1 II. Allegro")
        val rows = mergeTracks(input)
        assertEquals(
            "Symphony No.1" to "II. Allegro",
            mergedTitleOf(rows, input.map { it.id }, "t1"),
        )
        assertNull(mergedTitleOf(rows, input.map { it.id }, "nope"))
    }
}
