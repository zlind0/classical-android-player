package com.aurora.music.data.audiobook

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 读取 m4b / m4a 里的内嵌章节。
 *
 * 有声书常见两种章节存放方式：
 * 1. QuickTime 章节轨：音轨的 `tref/chap` 指向一条文字轨，每个 sample = 一章
 *    （start 由 stts 给出，标题是 sample 数据里的 2 字节长度前缀 + 文本）；
 * 2. Nero 章节表：`moov/udta/chpl`，直接列出起点（100ns 单位）和标题。
 *
 * 只做只读解析，不依赖 MediaMetadataRetriever（Android 没有章节 API）。
 * 任何异常都当"没有内嵌章节"，回落成整文件一章。
 */
object Mp4ChapterReader {

    data class Chapter(val startMs: Long, val title: String)

    private data class Box(val type: String, val start: Long, val end: Long)

    private data class Track(
        val id: Long,
        val start: Long,
        val end: Long,
        val chapterTrackId: Long?,
    )

    private const val MAX_SAMPLES = 100_000
    private const val MAX_TITLE_BYTES = 4096

    fun read(file: File): List<Chapter> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val fileEnd = raf.length()
            val moov = boxes(raf, 0L, fileEnd).firstOrNull { it.type == "moov" }
                ?: return@use emptyList()
            val tracks = children(raf, moov, "trak").mapNotNull { parseTrack(raf, it) }
            val referenced = tracks.mapNotNull { it.chapterTrackId }.toSet()
            val chapterTrack = tracks.firstOrNull { it.id in referenced }
            val fromTrack = chapterTrack?.let { readChapterTrack(raf, it) }.orEmpty()
            val raw = if (fromTrack.isNotEmpty()) {
                fromTrack
            } else {
                children(raf, moov, "udta").flatMap { readNeroChapters(raf, it) }
            }
            raw.filter { it.startMs >= 0L }
                .sortedBy { it.startMs }
                .distinctBy { it.startMs }
        }
    }.getOrDefault(emptyList())

    // ---- MP4 box 遍历 ----

    private fun boxes(raf: RandomAccessFile, start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = readType(raf)
            var header = 8L
            val size = when (size32) {
                0L -> end - pos
                1L -> {
                    if (pos + 16 > end) break
                    header = 16L
                    raf.readLong()
                }
                else -> size32
            }
            if (size < header || pos + size > end) break
            out.add(Box(type, pos + header, pos + size))
            pos += size
        }
        return out
    }

    private fun readType(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    private fun children(raf: RandomAccessFile, box: Box, type: String): List<Box> =
        boxes(raf, box.start, box.end).filter { it.type == type }

    private fun child(raf: RandomAccessFile, box: Box, type: String): Box? =
        boxes(raf, box.start, box.end).firstOrNull { it.type == type }

    private fun readU32(raf: RandomAccessFile): Long = raf.readInt().toLong() and 0xFFFFFFFFL

    // ---- 轨道 ----

    private fun parseTrack(raf: RandomAccessFile, box: Box): Track? {
        val tkhd = child(raf, box, "tkhd") ?: return null
        raf.seek(tkhd.start)
        val version = raf.readUnsignedByte()
        raf.skipBytes(3)
        raf.skipBytes(if (version == 1) 16 else 8)
        val id = readU32(raf)
        val tref = child(raf, box, "tref")
        val chapterTrackId = tref?.let { t ->
            children(raf, t, "chap").firstOrNull()?.let { chap ->
                raf.seek(chap.start)
                readU32(raf)
            }
        }
        return Track(id, box.start, box.end, chapterTrackId)
    }

    // ---- QuickTime 文字章节轨 ----

    private fun readChapterTrack(raf: RandomAccessFile, track: Track): List<Chapter> {
        val trackBox = Box("trak", track.start, track.end)
        val mdia = child(raf, trackBox, "mdia") ?: return emptyList()
        val minf = child(raf, mdia, "minf") ?: return emptyList()
        val stbl = child(raf, minf, "stbl") ?: return emptyList()
        val mdhd = child(raf, mdia, "mdhd") ?: return emptyList()
        val stts = child(raf, stbl, "stts") ?: return emptyList()
        val stsz = child(raf, stbl, "stsz") ?: return emptyList()
        val stsc = child(raf, stbl, "stsc") ?: return emptyList()
        val chunks = readChunkOffsets(raf, stbl) ?: return emptyList()

        val timescale = readTimescale(raf, mdhd).takeIf { it > 0 } ?: return emptyList()

        // stsz：每章 sample 的字节数
        raf.seek(stsz.start + 4)
        val constantSize = readU32(raf)
        val sampleCount = readU32(raf).toInt()
        if (sampleCount <= 0 || sampleCount > MAX_SAMPLES) return emptyList()
        val sizes = LongArray(sampleCount)
        if (constantSize > 0) {
            sizes.fill(constantSize)
        } else {
            repeat(sampleCount) { sizes[it] = readU32(raf) }
        }

        // stts：sample 起始时间（以 timescale 为单位）
        val starts = LongArray(sampleCount)
        run {
            raf.seek(stts.start + 4)
            val entryCount = readU32(raf).toInt().coerceIn(0, MAX_SAMPLES)
            var sample = 0
            var time = 0L
            repeat(entryCount) {
                val count = readU32(raf).toInt()
                val delta = readU32(raf)
                if (count < 0 || sample + count > sampleCount) return emptyList()
                repeat(count) {
                    starts[sample++] = time
                    time += delta
                }
            }
            if (sample < sampleCount) return emptyList()
        }

        // stsc：chunk 里装多少个 sample，配合 stco/co64 得到每个 sample 的文件偏移
        val stscEntries = ArrayList<Triple<Int, Int, Int>>()
        run {
            raf.seek(stsc.start + 4)
            val entryCount = readU32(raf).toInt().coerceIn(0, MAX_SAMPLES)
            repeat(entryCount) {
                stscEntries.add(Triple(readU32(raf).toInt(), readU32(raf).toInt(), readU32(raf).toInt()))
            }
        }
        if (stscEntries.isEmpty()) return emptyList()
        val offsets = LongArray(sampleCount)
        var chunkIndex = 0
        var sampleIndex = 0
        while (sampleIndex < sampleCount && chunkIndex < chunks.size) {
            val chunkNumber = chunkIndex + 1
            val perChunk = samplesPerChunk(stscEntries, chunkNumber).coerceIn(1, 10_000)
            var offset = chunks[chunkIndex]
            repeat(perChunk) {
                if (sampleIndex >= sampleCount) return@repeat
                offsets[sampleIndex] = offset
                offset += sizes[sampleIndex]
                sampleIndex++
            }
            chunkIndex++
        }
        if (sampleIndex < sampleCount) return emptyList()

        val out = ArrayList<Chapter>(sampleCount)
        val buffer = ByteArray(MAX_TITLE_BYTES)
        for (i in 0 until sampleCount) {
            val size = sizes[i].coerceAtMost(MAX_TITLE_BYTES.toLong()).toInt()
            if (size < 2) {
                out.add(Chapter(starts[i] * 1000L / timescale, ""))
                continue
            }
            raf.seek(offsets[i])
            var read = 0
            while (read < size) {
                val n = raf.read(buffer, read, size - read)
                if (n <= 0) break
                read += n
            }
            if (read < 2) {
                out.add(Chapter(starts[i] * 1000L / timescale, ""))
                continue
            }
            val titleLen = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            val titleBytes = titleLen.coerceIn(0, read - 2)
            val title = decodeText(buffer.copyOfRange(2, 2 + titleBytes)).trim()
            out.add(Chapter(starts[i] * 1000L / timescale, title))
        }
        return out
    }

    private fun samplesPerChunk(entries: List<Triple<Int, Int, Int>>, chunkNumber: Int): Int {
        var result = 1
        for ((firstChunk, perChunk, _) in entries) {
            if (firstChunk <= chunkNumber) result = perChunk else break
        }
        return result
    }

    private fun readTimescale(raf: RandomAccessFile, mdhd: Box): Long {
        raf.seek(mdhd.start)
        val version = raf.readUnsignedByte()
        raf.skipBytes(3)
        raf.skipBytes(if (version == 1) 16 else 8)
        return readU32(raf)
    }

    private fun readChunkOffsets(raf: RandomAccessFile, stbl: Box): LongArray? {
        val stco = child(raf, stbl, "stco")
        val co64 = child(raf, stbl, "co64")
        val box = stco ?: co64 ?: return null
        raf.seek(box.start + 4)
        val count = readU32(raf).toInt()
        if (count <= 0 || count > MAX_SAMPLES) return null
        val out = LongArray(count)
        for (i in 0 until count) {
            out[i] = if (co64 != null) raf.readLong() else readU32(raf)
        }
        return out
    }

    // ---- Nero chpl ----

    private fun readNeroChapters(raf: RandomAccessFile, udta: Box): List<Chapter> {
        val chpl = child(raf, udta, "chpl") ?: return emptyList()
        if (chpl.end - chpl.start < 9) return emptyList()
        raf.seek(chpl.start)
        val version = raf.readUnsignedByte()
        if (version != 1) return emptyList()
        raf.skipBytes(3)
        raf.skipBytes(4)
        val count = raf.readUnsignedByte()
        if (count <= 0) return emptyList()
        val out = ArrayList<Chapter>(count)
        repeat(count) {
            if (raf.filePointer + 9 > chpl.end) return@repeat
            val start100ns = raf.readLong()
            val len = raf.readUnsignedByte()
            if (raf.filePointer + len > chpl.end) return@repeat
            val bytes = ByteArray(len)
            raf.readFully(bytes)
            out.add(Chapter(start100ns / 10_000L, decodeText(bytes).trim()))
        }
        return out
    }

    // ---- 文本解码 ----

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, macRoman())
        }
    }

    private fun macRoman(): Charset =
        runCatching { Charset.forName("x-MacRoman") }.getOrDefault(Charsets.ISO_8859_1)
}
