package com.aurora.music.data.ebook

import org.junit.Assert.assertEquals
import org.junit.Test

class TocSectionTest {

    // 第一部分(h1) > 第一章(h2) > 第一节(h3)；第二章(h2)；第二部分(h1)
    private val toc = listOf(
        EbookTocEntry(0, 0, "第一部分", 1),
        EbookTocEntry(0, 2, "第一章", 2),
        EbookTocEntry(0, 5, "第一节", 3),
        EbookTocEntry(1, 0, "第二章", 2),
        EbookTocEntry(2, 0, "第二部分", 1),
    )

    private fun titleAt(chapter: Int, block: Int): String {
        val i = TocSection.currentEntry(toc, chapter, block)
        return if (i < 0) "" else toc[i].title
    }

    @Test
    fun deepPosition_returnsNearestHeading() {
        // 节内深处 → 第一节（最贴近的深层标题）
        assertEquals("第一节", titleAt(0, 9))
        // 章内节前 → 第一章
        assertEquals("第一章", titleAt(0, 3))
        // 章首 → 第一章
        assertEquals("第一章", titleAt(0, 2))
    }

    @Test
    fun laterChapter_returnsItsHeading() {
        assertEquals("第二章", titleAt(1, 4))
        assertEquals("第二部分", titleAt(2, 0))
    }

    @Test
    fun emptyToc_returnsMinusOne() {
        assertEquals(-1, TocSection.currentEntry(emptyList(), 0, 0))
    }
}
