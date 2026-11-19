package com.aurora.music.data.audiobook

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Mp4ChapterReader 回归测试：手工构造最小 MP4（音轨 tref/chap 指向文字章节轨），
// 验证 stts/stsc/stco/stsz 解析、章节起点与标题解码。
class Mp4ChapterReaderTest {

    private fun be16(v: Int): ByteArray = ByteBuffer.allocate(2).putShort(v.toShort()).array()

    private fun be32(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(be32(8 + body.size))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    private fun fullBox(type: String, version: Int, vararg parts: ByteArray): ByteArray {
        val flags = byteArrayOf(version.toByte(), 0, 0, 0)
        return box(type, flags, *parts)
    }

    private fun sample(title: String): ByteArray {
        val text = title.toByteArray(Charsets.UTF_8)
        return be16(text.size) + text
    }

    private fun buildMp4(chapterTitles: List<String>): ByteArray {
        val samples = chapterTitles.map { sample(it) }
        val sizes = samples.map { it.size }
        val block = ByteArrayOutputStream().apply { samples.forEach { write(it) } }.toByteArray()

        // 章节轨 mdhd：timescale = 1000（1 tick = 1ms）
        val mdhd = fullBox(
            "mdhd", 0,
            ByteArray(8), // creation + modification
            be32(1000), // timescale
            be32(0), // duration
            ByteArray(4), // language + quality
        )
        val stts = fullBox(
            "stts", 0,
            be32(chapterTitles.size),
            // 每章间隔 1000ms（最后一章 delta 无意义）
            *chapterTitles.indices.flatMap { listOf(be32(1), be32(1000)) }.toTypedArray(),
        )
        val stsz = fullBox(
            "stsz", 0,
            be32(0), // constant size = 0
            be32(sizes.size),
            *sizes.map { be32(it) }.toTypedArray(),
        )
        val stsc = fullBox("stsc", 0, be32(1), be32(1), be32(sizes.size), be32(1))
        fun stco(offset: Int) = fullBox("stco", 0, be32(1), be32(offset))

        fun trak(id: Int, chapterRef: Int?, children: List<ByteArray>): ByteArray {
            val tkhd = fullBox("tkhd", 0, ByteArray(8), be32(id), ByteArray(60))
            val body = mutableListOf<ByteArray>(tkhd)
            if (chapterRef != null) {
                body.add(box("tref", box("chap", be32(chapterRef))))
            }
            body.addAll(children)
            return box("trak", *body.toTypedArray())
        }

        val audio = trak(
            1, 2,
            listOf(box("mdia", fullBox("mdhd", 0, ByteArray(8), be32(44100), be32(0), ByteArray(4)))),
        )

        fun chapterTrak(offset: Int): ByteArray = trak(
            2, null,
            listOf(
                box("mdia", mdhd, box("minf", box("stbl", stts, stsz, stsc, stco(offset)))),
            ),
        )

        fun moovWith(offset: Int): ByteArray = box("moov", audio, chapterTrak(offset))

        val placeholder = moovWith(0)
        val mdatHeader = be32(8 + block.size) + "mdat".toByteArray(Charsets.US_ASCII)
        val dataOffset = placeholder.size + mdatHeader.size
        val moov = moovWith(dataOffset)
        assertEquals(placeholder.size, moov.size)

        val out = ByteArrayOutputStream()
        out.write(moov)
        out.write(mdatHeader)
        out.write(block)
        return out.toByteArray()
    }

    @Test
    fun readsChapterTitlesAndStartTimes() {
        val file = File.createTempFile("mp4chapters", ".m4b")
        try {
            file.writeBytes(buildMp4(listOf("01. Introduction", "02. Plato - Politics", "03. Aristotle")))
            val chapters = Mp4ChapterReader.read(file)
            assertEquals(3, chapters.size)
            assertEquals(listOf(0L, 1000L, 2000L), chapters.map { it.startMs })
            assertEquals("01. Introduction", chapters[0].title)
            assertEquals("02. Plato - Politics", chapters[1].title)
            assertEquals("03. Aristotle", chapters[2].title)
        } finally {
            file.delete()
        }
    }

    @Test
    fun nonMp4ReturnsEmpty() {
        val file = File.createTempFile("mp4chapters", ".m4b")
        try {
            file.writeBytes("not an mp4 file".toByteArray())
            assertTrue(Mp4ChapterReader.read(file).isEmpty())
        } finally {
            file.delete()
        }
    }
}
