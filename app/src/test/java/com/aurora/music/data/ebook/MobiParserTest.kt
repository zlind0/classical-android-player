package com.aurora.music.data.ebook

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// 新 MobiParser（foliate 移植，纯 JVM）回归测试：
// 用手工构造的最小 PalmDB/MOBI 验证头解析、EXTH、解压、分页切章、DRM 拒开。
class MobiParserTest {

    private fun be16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun be32(v: Int): ByteArray {
        val b = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
        return b
    }

    private fun exthRecord(type: Int, text: String): ByteArray {
        val data = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(be32(type))
        out.write(be32(8 + data.size))
        out.write(data)
        return out.toByteArray()
    }

    /**
     * 最小 MOBI：PDB 头 + rec0（含 PalmDOC/MOBI/EXTH）+ 文本记录。
     * compression: 1 = 无压缩，2 = PalmDOC（内容须纯 ASCII 才与原文逐字节一致）。
     */
    private fun buildMobi(
        texts: List<ByteArray>,
        compression: Int = 1,
        encryption: Int = 0,
        exthTitle: String = "测试书名",
        exthAuthor: String = "测试作者",
    ): File {
        val numRecords = 1 + texts.size
        val out = ByteArrayOutputStream()
        // PDB 头 78B
        val name = "TestBook".toByteArray(Charsets.US_ASCII)
        out.write(name + ByteArray(32 - name.size))
        out.write(ByteArray(28)) // attributes..id
        out.write("BOOKMOBI".toByteArray(Charsets.US_ASCII)) // 60..67
        out.write(ByteArray(8)) // 68..75
        out.write(be16(numRecords)) // 76
        // 记录表（先占位）
        val tablePos = out.size()
        out.write(ByteArray(numRecords * 8))
        // rec0：512B
        val rec0 = ByteArray(512)
        fun put(off: Int, b: ByteArray) = b.copyInto(rec0, off)
        put(0, be16(compression))
        put(4, be32(texts.sumOf { it.size }))
        put(8, be16(texts.size))
        put(10, be16(4096))
        put(12, be16(encryption))
        put(16, "MOBI".toByteArray(Charsets.US_ASCII))
        put(20, be32(232)) // mobiLength
        put(28, be32(65001)) // UTF-8
        put(36, be32(6)) // version MOBI7
        put(84, be32(0)) // titleOffset（空，走 EXTH 503）
        put(88, be32(0))
        put(108, be32(0))
        put(112, be32(0))
        put(116, be32(0))
        put(128, be32(0x40)) // EXTH 存在
        put(168, be32(0xFFFFFFFF.toInt())) // DRM offset：无 DRM
        put(240, be32(0)) // trailingFlags
        put(244, be32(0xFFFFFFFF.toInt())) // indx 无
        // EXTH @248
        val exth = ByteArrayOutputStream()
        exth.write("EXTH".toByteArray(Charsets.US_ASCII))
        val r100 = exthRecord(100, exthAuthor)
        val r503 = exthRecord(503, exthTitle)
        exth.write(be32(12 + r100.size + r503.size))
        exth.write(be32(2))
        exth.write(r100)
        exth.write(r503)
        put(248, exth.toByteArray())
        val rec0Start = out.size()
        out.write(rec0)
        val textStarts = mutableListOf<Int>()
        texts.forEach {
            textStarts.add(out.size())
            out.write(it)
        }
        val bytes = out.toByteArray()
        // 回填记录表
        val table = ByteBuffer.allocate(numRecords * 8).order(ByteOrder.BIG_ENDIAN)
        val starts = listOf(rec0Start) + textStarts
        starts.forEach {
            table.putInt(it)
            table.putInt(0)
        }
        table.array().copyInto(bytes, tablePos)
        val f = File.createTempFile("mobi_test", ".mobi")
        f.writeBytes(bytes)
        return f
    }

    private val htmlZh = """
        <html><head><title>t</title></head><body>
        <h1>第一章</h1><p>你好世界，寒夜里一盏灯。</p>
        <mbp:pagebreak/>
        <h2>第二章</h2><p>再见白天，星星出来了。</p>
        </body></html>
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private val htmlAscii = """
        <html><head><title>t</title></head><body>
        <h1>Chapter One</h1><p>Hello world, a lamp in the cold night.</p>
        <mbp:pagebreak/>
        <h1>Chapter Two</h1><p>Goodbye day, the stars are out.</p>
        </body></html>
    """.trimIndent().toByteArray(Charsets.UTF_8)

    @Test
    fun meta_fromExth() {
        val f = buildMobi(listOf(htmlZh))
        try {
            val m = MobiParser.parseMeta(f)
            assertEquals("测试书名", m.title)
            assertEquals("测试作者", m.author)
        } finally {
            f.delete()
        }
    }

    @Test
    fun full_uncompressed_pagebreakChapters() {
        val f = buildMobi(listOf(htmlZh))
        try {
            val book = MobiParser.parseFull(f)
            assertNotNull(book)
            book!!
            assertEquals("测试书名", book.title)
            assertEquals("测试作者", book.author)
            assertEquals(2, book.chapters.size)
            assertEquals("第一章", book.chapters[0].title)
            assertEquals("第二章", book.chapters[1].title)
            assertEquals(1, book.chapters[0].blocks[0].level)
            assertTrue(book.chapters[0].blocks.any { it.level == 0 && it.text.contains("你好世界") })
            assertTrue(book.chapters[1].blocks.any { it.text.contains("星星") })
            // 目录：h1/h2 成条
            assertTrue(book.toc.size >= 2)
            assertEquals(0, book.toc[0].chapterIndex)
            assertEquals(1, book.toc[0].level)
        } finally {
            f.delete()
        }
    }

    @Test
    fun full_palmDocAsciiIdentity() {
        // 纯 ASCII 在 PalmDOC type2 下逐字节直通，可验证解压路径
        val f = buildMobi(listOf(htmlAscii), compression = 2)
        try {
            val book = MobiParser.parseFull(f)
            assertNotNull(book)
            book!!
            assertEquals(2, book.chapters.size)
            assertTrue(book.chapters[0].blocks.any { it.text.contains("Hello world") })
        } finally {
            f.delete()
        }
    }

    @Test
    fun full_multiTextRecords() {
        val f = buildMobi(listOf(htmlAscii, htmlAscii))
        try {
            val book = MobiParser.parseFull(f)
            assertNotNull(book)
            // 两条记录拼成一条流：2 个分页符 → 3 章，中间章横跨记录边界
            assertEquals(3, book!!.chapters.size)
            val middle = book.chapters[1].blocks.joinToString("\n") { it.text }
            assertTrue(middle.contains("Goodbye day"))
            assertTrue(middle.contains("Hello world"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun nonMobi_returnsNull() {
        val f = File.createTempFile("not_mobi", ".mobi")
        try {
            f.writeBytes(ByteArray(256))
            assertNull(MobiParser.parseFull(f))
            val m = MobiParser.parseMeta(f)
            assertEquals("", m.title)
        } finally {
            f.delete()
        }
    }

    @Test
    fun drm_refused() {
        val f = buildMobi(listOf(htmlZh), encryption = 1)
        try {
            try {
                MobiParser.parseFull(f)
                fail("DRM 书应该拒开")
            } catch (e: IllegalStateException) {
                assertTrue(e.message?.contains("DRM") == true)
            }
        } finally {
            f.delete()
        }
    }
}
