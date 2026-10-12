package com.aurora.music.data.ebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EbookAnchorCodeTest {

    private val book = ParsedEbook(
        title = "t", author = "a",
        chapters = listOf(
            EbookChapter("c0", listOf(EbookBlock("a"), EbookBlock("b"), EbookBlock("c"))),
            EbookChapter("c1", listOf(EbookBlock("x"))),
            EbookChapter("c2", emptyList()),
        ),
        toc = listOf(EbookTocEntry(0, 0, "c0", 1)),
    )

    @Test
    fun encode_isOneBased() {
        assertEquals("1:1", EbookAnchorCode.encode(0, 0))
        assertEquals("12:34", EbookAnchorCode.encode(11, 33))
    }

    @Test
    fun decodeRaw_acceptsVariants() {
        assertEquals(11 to 33, EbookAnchorCode.decodeRaw("12:34"))
        assertEquals(11 to 33, EbookAnchorCode.decodeRaw("12：34"))
        assertEquals(11 to 33, EbookAnchorCode.decodeRaw("12-34"))
        assertEquals(11 to 33, EbookAnchorCode.decodeRaw("12章34段"))
        assertEquals(11 to 33, EbookAnchorCode.decodeRaw(" 12 : 34 "))
        // 只有一个数字：当作章，段取首段
        assertEquals(4 to 0, EbookAnchorCode.decodeRaw("5"))
        assertNull(EbookAnchorCode.decodeRaw("abc"))
        assertNull(EbookAnchorCode.decodeRaw(""))
    }

    @Test
    fun decode_clampsToBook() {
        // 越界钳到末章/末段
        assertEquals(EbookAnchor(2, 0), EbookAnchorCode.decode("99:99", book))
        // 空章块钳为 0
        assertEquals(EbookAnchor(2, 0), EbookAnchorCode.decode("3:5", book))
        // 孤儿章（无 heading 指向）照样能解：编码与目录无关
        assertEquals(EbookAnchor(1, 0), EbookAnchorCode.decode("2:1", book))
        assertNull(EbookAnchorCode.decode("nope", book))
    }

    @Test
    fun minuteStart_groupsNaturalMinute() {
        // 同一自然分钟内不同秒 → 同一桶
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.SECOND, 10)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val a = c.timeInMillis
        c.set(java.util.Calendar.SECOND, 50)
        val b = c.timeInMillis
        assertEquals(EbookAnchorCode.minuteStart(a), EbookAnchorCode.minuteStart(b))
        // 下一分钟 → 不同桶
        c.add(java.util.Calendar.MINUTE, 1)
        val d = c.timeInMillis
        assertEquals(false, EbookAnchorCode.minuteStart(a) == EbookAnchorCode.minuteStart(d))
    }
}
