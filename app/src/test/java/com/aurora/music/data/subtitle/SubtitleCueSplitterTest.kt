package com.aurora.music.data.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleCueSplitterTest {

    @Test
    fun splitsEnglishSentencesAndKeepsTimelineMonotonic() {
        val cues = SubtitleCueSplitter.split(
            "In this lecture we shall witness the birth of philosophy. We shall explore how these forerunners shifted the focus. From who and why to what and how.",
            10.0,
            22.0,
        )
        assertEquals(3, cues.size)
        assertEquals(10.0, cues.first().start, 1e-6)
        assertEquals(22.0, cues.last().end, 1e-6)
        cues.zipWithNext().forEach { (a, b) ->
            assertTrue("cues must not overlap", a.end <= b.start + 1e-6)
        }
        assertTrue(cues.all { it.text.isNotBlank() })
    }

    @Test
    fun splitsCjkPunctuation() {
        val cues = SubtitleCueSplitter.split("这是第一句。这是第二句！这是第三句？", 0.0, 9.0)
        assertEquals(3, cues.size)
        assertEquals("这是第一句。", cues[0].text)
    }

    @Test
    fun splitsLongUnpunctuatedTextOnCommas() {
        val text = "a".repeat(60) + ", " + "b".repeat(60) + ", " + "c".repeat(60)
        val cues = SubtitleCueSplitter.split(text, 0.0, 30.0)
        assertTrue("long text must be split further, got ${cues.size}", cues.size >= 2)
    }

    @Test
    fun blankTextProducesNothing() {
        assertTrue(SubtitleCueSplitter.split("   ", 0.0, 5.0).isEmpty())
    }

    @Test
    fun neverExceedsSegmentBounds() {
        val cues = SubtitleCueSplitter.split("One. Two. Three.", 5.0, 8.0)
        assertTrue(cues.all { it.start >= 5.0 - 1e-6 && it.end <= 8.0 + 1e-6 })
    }
}
