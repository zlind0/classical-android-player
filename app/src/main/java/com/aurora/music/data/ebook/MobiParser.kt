package com.aurora.music.data.ebook

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * MOBI / AZW / AZW3 解析：PalmDB 容器里的 MOBI7（KF7）、KF8，以及
 * “混合档”（MOBI7 段 + EXTH 121 分界之后的 KF8 段）。
 *
 * 算法移植自 foliate-js 的 mobi.js（MIT）：结构偏移、PalmDOC/HUFF/CDIC 解压、
 * INDX+TAGX+cncx 索引、skel+frag 骨架组装、loadRaw 前后端按需加载都照它来。
 * DRM 文件拒开；KF8 组装失败退化成“全量文本按分页符切章”，宁可粗糙也不崩。
 * 产物进 EbookStore 的 parsed.json 缓存，二次打开秒开。
 *
 * 纯 java.io / java.nio / kotlin，阻塞式，调用方自行放 IO 线程。
 */
object MobiParser {

    data class Meta(val title: String, val author: String)

    /** 与 `parseFull` 同一入口的简写（新调度器若按 `parse` 分发可直接换用）。 */
    fun parse(file: File): ParsedEbook? = parseFull(file)

    /**
     * 扫描/导入用：只读记录 0 的头与 EXTH，任何失败都回空值，绝不抛
     * （DRM 书的元数据照样可读，进书架不依赖正文解密）。
     */
    fun parseMeta(file: File): Meta = runCatching {
        if (file.length() < 80) return@runCatching Meta("", "")
        PdbReader(file).use { reader ->
            val rec0 = reader.record(0)
            if (!magicAt(rec0, 16, "MOBI")) return@runCatching Meta("", "")
            val headers = parseHeaders(rec0)
            Meta(titleOf(headers), authorOf(headers))
        }
    }.getOrDefault(Meta("", ""))

    /**
     * 正文入口：
     *  - 不是 MOBI → null（交给上层判定）；
     *  - DRM → IllegalStateException("电子书受 DRM 保护，无法打开")；
     *  - 读不出正文 → IllegalStateException("无法解析该电子书")。
     * 调用方（EbookStore）用 runCatching 包着，抛出也只是拿不到书。
     */
    fun parseFull(file: File): ParsedEbook? {
        try {
            if (file.length() < 80) return null
            PdbReader(file).use { reader -> return buildBook(reader, file) }
        } catch (e: IllegalStateException) {
            throw e // DRM 拒开 / 无法解析：语义性失败原样上抛
        } catch (e: NotPdb) {
            return null // 连 PDB 结构都读不出来 = 根本不是 MOBI
        } catch (e: Exception) {
            // 坏文件/半截数据一律收敛成可读的失败，不外泄底层异常
            throw IllegalStateException("无法解析该电子书", e)
        }
    }

    // ---------------------------------------------------------------- 主流程

    // 选部件（MOBI7 / KF8 / 混合档的 KF8 段）→ 取正文 → 组块组章 → 书名作者
    private fun buildBook(reader: PdbReader, file: File): ParsedEbook? {
        val rec0 = reader.record(0)
        if (!magicAt(rec0, 16, "MOBI")) return null // 不是 MOBI，返回 null 让上层处理
        val rec0Headers = parseHeaders(rec0)
        checkDrm(rec0Headers, rec0)

        var headers = rec0Headers
        var start = 0
        var isKf8 = rec0Headers.version >= 8
        if (!isKf8) {
            // 混合档：MOBI7 段后面还挂着 KF8 段，EXTH 121 给出分界记录号。
            // 分界处打不开就整个文件当 MOBI7 处理（foliate 也是这么回退的）。
            val boundary = rec0Headers.boundary
            if (boundary != null && boundary >= 0 && boundary < reader.numRecords) {
                try {
                    val recB = reader.record(boundary.toInt())
                    val h = parseHeaders(recB)
                    checkDrm(h, recB)
                    headers = h
                    start = boundary.toInt()
                    isKf8 = true
                } catch (e: Exception) {
                    // 保持 MOBI7
                }
            }
        }

        val bookTitle = titleOf(headers).ifBlank { file.nameWithoutExtension }
        val author = authorOf(headers)

        val raws = loadRawSections(reader, headers, start, isKf8, rec0Headers)
        if (raws.isEmpty()) throw IllegalStateException("无法解析该电子书")
        return sectionsToBook(raws, bookTitle, author)
    }

    // 正文获取，带两级退化：KF8 组装失败 → 当前部件按 MOBI7 切；再失败（混合档）
    // → 退回原始 MOBI7 段。任何一级拿到内容就用。
    private fun loadRawSections(
        reader: PdbReader,
        headers: PartHeaders,
        start: Int,
        isKf8: Boolean,
        rec0Headers: PartHeaders,
    ): List<String> {
        if (isKf8) {
            try {
                kf8Sections(reader, headers, start).takeIf { it.isNotEmpty() }?.let { return it }
            } catch (e: Exception) {
                // 退下一级
            }
            try {
                mobi6Sections(reader, headers, start).takeIf { it.isNotEmpty() }?.let { return it }
            } catch (e: Exception) {
                // 退下一级
            }
            if (start != 0) {
                try {
                    mobi6Sections(reader, rec0Headers, 0).takeIf { it.isNotEmpty() }?.let { return it }
                } catch (e: Exception) {
                    // 全部失败
                }
            }
            return emptyList()
        }
        return try {
            mobi6Sections(reader, headers, 0).takeIf { it.isNotEmpty() }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- 书名 / 作者

    // EXTH 503 → MOBI 头里的全名（调用方再兜底文件名）
    private fun titleOf(headers: PartHeaders): String =
        unescapeEntities(headers.exth?.text(503) ?: "").trim()
            .ifBlank {
                unescapeEntities(str(headers.titleBytes, 0, headers.titleBytes.size, headers.charset)).trim()
            }

    // EXTH 100 可重复出现，多个作者用 ", " 连起来
    private fun authorOf(headers: PartHeaders): String =
        headers.exth?.texts(100).orEmpty()
            .map { unescapeEntities(it).trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")

    // ---------------------------------------------------------------- 组章

    /**
     * 节 → 块 → 章：
     *  - 一节一章（MOBI7 的 pagebreak / KF8 的骨架边界 ≈ 章节边界）；
     *  - h1..h6 保留级别成为标题块，目录按 h1/h2 生成 —— 与 EpubParser 的回退一致；
     *  - 章名：首个标题 →（第 0 节退书名，其余“第 N 章”）。
     */
    private fun sectionsToBook(raws: List<String>, bookTitle: String, author: String): ParsedEbook {
        val chapters = ArrayList<EbookChapter>(raws.size)
        val toc = ArrayList<EbookTocEntry>()
        for ((idx, raw) in raws.withIndex()) {
            val blocks = blocksOf(raw)
            if (blocks.isEmpty()) continue // 没字的节（空页/纯图页）跳过
            val title = blocks.firstOrNull { it.level in 1..6 }?.text
                ?: if (idx == 0) bookTitle else "第 ${idx + 1} 章"
            val ci = chapters.size
            chapters.add(EbookChapter(title, blocks))
            var added = false
            blocks.forEachIndexed { bi, b ->
                if (b.level in 1..2 && b.text.isNotBlank()) {
                    toc.add(EbookTocEntry(ci, bi, b.text, b.level))
                    added = true
                }
            }
            if (!added) toc.add(EbookTocEntry(ci, 0, title, 1))
        }
        if (chapters.isEmpty()) {
            // 一节都没留下也得开得出一本书
            chapters.add(EbookChapter(bookTitle, listOf(EbookBlock("本书内容为空。"))))
            toc.add(EbookTocEntry(0, 0, bookTitle, 1))
        }
        return ParsedEbook(title = bookTitle, author = author, chapters = chapters, toc = toc)
    }

    // 正文 HTML → 块（与 EpubParser.parseBodyBlocks 同一套约定：
    // 块级标签成块、h1..h6 带级别、去标签去实体；纯文本兜底按行切）
    private val BLOCK_RE = Regex(
        """<(p|h1|h2|h3|h4|h5|h6|div|li|blockquote|dt|dd|pre|section|article)\b[^>]*>(.*?)</\1\s*>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val INNER_TAG_RE = Regex("""<[^>]+>""")
    private val BLANK_RE = Regex("""[ \t\x0B\f\r]+""")

    private fun blocksOf(html: String): List<EbookBlock> {
        var s = Regex("""<head\b.*?</head\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .replace(html, "")
        s = Regex("""<(script|style)\b.*?</\1\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .replace(s, "")
        s = PAGEBREAK_RE.replace(s, "")
        val out = ArrayList<EbookBlock>()
        BLOCK_RE.findAll(s).forEach { m ->
            val tag = m.groupValues[1]
            val text = unescapeEntities(INNER_TAG_RE.replace(m.groupValues[2], ""))
                .trim().replace(BLANK_RE, " ")
            if (text.isBlank()) return@forEach
            out.add(EbookBlock(text, headingLevel(tag)))
        }
        if (out.isEmpty()) {
            // 没有任何块级标签（松散文本 spine）：按行切
            unescapeEntities(INNER_TAG_RE.replace(s, "")).trim()
                .split(Regex("""\n\s*\n|\n""")).map { it.trim() }.filter { it.isNotBlank() }
                .forEach { out.add(EbookBlock(it, 0)) }
        }
        return out
    }

    private fun headingLevel(tag: String): Int = when (tag.lowercase()) {
        "h1" -> 1
        "h2" -> 2
        "h3" -> 3
        "h4" -> 4
        "h5" -> 5
        "h6" -> 6
        else -> 0
    }

    // ---------------------------------------------------------------- KF8

    private class Skel(val numFrag: Int, val offset: Int, val length: Int)

    private class Frag(
        val insertOffset: Int,
        @Suppress("unused") val selector: String,
        @Suppress("unused") val index: Int,
        val offset: Int,
        val length: Int,
    )

    // KF8：FDST 流表定全文长度，skel/frag 两张 INDX 定骨架与片段，
    // loadRaw 按字节区间把未压缩正文读齐，再把片段插回骨架（foliate KF8.init/loadRaw/loadText）
    private fun kf8Sections(reader: PdbReader, headers: PartHeaders, start: Int): List<String> {
        val kf8 = headers.kf8 ?: throw IllegalArgumentException("Missing KF8 header")
        val ctx = TextContext(reader, headers, start)

        // FDST：每条流的 [起, 止]；最后一条的止 = 全文未压缩总长。拿不到就只从前端读
        val fullRawLength: Int? = try {
            val fdst = ctx.loadRecord(kf8.fdst)
            if (!magicAt(fdst, 0, "FDST")) throw IllegalArgumentException("Missing FDST record")
            val n = u32i(fdst, 8)
            if (n in 1..65535) u32i(fdst, 12 + (n - 1) * 8 + 4) else null
        } catch (e: Exception) {
            null
        }

        val skelData = getIndexData(kf8.skel) { ctx.loadRecord(it) }
        val skelTable = skelData.table.map { e ->
            Skel(
                numFrag = e.tags[1]?.firstOrNull() ?: 0,
                offset = e.tags[6]?.getOrNull(0) ?: 0,
                length = e.tags[6]?.getOrNull(1) ?: 0,
            )
        }
        val fragData = getIndexData(kf8.frag) { ctx.loadRecord(it) }
        val fragTable = fragData.table.map { e ->
            Frag(
                insertOffset = e.name.toIntOrNull() ?: 0,
                selector = fragData.cncx[e.tags[2]?.firstOrNull() ?: -1] ?: "",
                index = e.tags[4]?.firstOrNull() ?: 0,
                offset = e.tags[6]?.getOrNull(0) ?: 0,
                length = e.tags[6]?.getOrNull(1) ?: 0,
            )
        }

        // 未压缩正文没有随机读：按需从前端或后端加载，直到所需字节区间落进内存
        var rawHead = ByteArray(0)
        var rawTail = ByteArray(0)
        var lastHead = -1
        var lastTail = -1
        val numText = headers.numTextRecords
        fun loadRaw(from: Int, to: Int): ByteArray {
            if (from < 0 || to < from) throw IllegalArgumentException("bad text range $from..$to")
            val distHead = to - rawHead.size
            val distEnd = fullRawLength?.let { (it.toLong() - rawTail.size) - from } ?: Long.MAX_VALUE
            if (distHead < 0 || distHead.toLong() < distEnd) {
                // 从前端加载
                while (rawHead.size < to) {
                    val index = ++lastHead
                    if (index < 0 || index >= numText) throw IllegalArgumentException("text records exhausted")
                    rawHead = concat(rawHead, ctx.loadText(index))
                }
                val s = from.coerceIn(0, rawHead.size)
                return rawHead.copyOfRange(s, to.coerceIn(s, rawHead.size))
            }
            // 从后端加载
            val full = fullRawLength ?: throw IllegalArgumentException("no FDST")
            while (full - rawTail.size > from) {
                val index = numText - 1 - (++lastTail)
                if (index < 0 || index >= numText) throw IllegalArgumentException("text records exhausted")
                rawTail = concat(ctx.loadText(index), rawTail)
            }
            val tailStart = full - rawTail.size
            val s = (from - tailStart).coerceIn(0, rawTail.size)
            return rawTail.copyOfRange(s, (to - tailStart).coerceIn(s, rawTail.size))
        }

        val out = ArrayList<String>(skelTable.size)
        var fragStart = 0
        for (skel in skelTable) {
            val fragEnd = fragStart + skel.numFrag
            val frags = if (fragStart in 0..fragTable.size) {
                fragTable.subList(fragStart, fragEnd.coerceIn(fragStart, fragTable.size))
            } else {
                emptyList()
            }
            fragStart = fragEnd
            // 片段数为 0 的是 linear:'no' 的非线性页，不进正文
            if (frags.isEmpty()) continue
            val length = skel.length + frags.sumOf { it.length }
            val raw = loadRaw(skel.offset, skel.offset + length)
            var skeleton = raw.copyOfRange(0, skel.length.coerceIn(0, raw.size))
            for (frag in frags) {
                val ins = (frag.insertOffset - skel.offset).coerceIn(0, skeleton.size)
                val rel = skel.length + frag.offset
                val from = rel.coerceIn(0, raw.size)
                val fragRaw = raw.copyOfRange(from, (rel + frag.length).coerceIn(from, raw.size))
                skeleton = concat(
                    skeleton.copyOfRange(0, ins),
                    fragRaw,
                    skeleton.copyOfRange(ins, skeleton.size),
                )
            }
            out.add(String(skeleton, headers.charset))
        }
        return out
    }

    // ---------------------------------------------------------------- MOBI7

    // 全部文本记录解压拼起来，按 <mbp:pagebreak> 切节。filepos 链接留着不管
    // （块模型没有锚点跳转，标签在组块时会被剥掉，只剩可见文字）。
    private fun mobi6Sections(reader: PdbReader, headers: PartHeaders, start: Int): List<String> {
        val ctx = TextContext(reader, headers, start)
        val bos = ByteArrayOutputStream()
        for (i in 0 until headers.numTextRecords) bos.write(ctx.loadText(i))
        val all = bos.toByteArray()
        if (all.isEmpty()) return emptyList()

        // 1 字节 = 1 字符的“视图”，只为跑正则定位（pagebreak 是 ASCII 标签，
        // 分界必然落在 '<' 上，按书的字符集整段解码不会切开多字节字符）
        val view = String(all, Charsets.ISO_8859_1)
        val breaks = PAGEBREAK_RE.findAll(view).map { it.range.first }.toList()
        val starts = IntArray(breaks.size + 1)
        starts[0] = 0
        for (i in breaks.indices) starts[i + 1] = breaks[i]

        val out = ArrayList<String>(starts.size)
        for (k in starts.indices) {
            val from = starts[k]
            val to = if (k + 1 < starts.size) starts[k + 1] else all.size
            if (to <= from) continue
            val html = String(all, from, to - from, headers.charset).replace(PAGEBREAK_RE, "")
            out.add(html)
        }
        return out
    }

    // ---------------------------------------------------------------- 头结构

    private class Kf8Fields(val fdst: Int, val frag: Int, val skel: Int)

    /** 一个 MOBI 部件（记录 0，或混合档分界处那条记录）解析出的头信息 */
    private class PartHeaders(
        val compression: Int,
        val encryption: Int,
        val numTextRecords: Int,
        val charset: Charset,
        val version: Long,
        val titleBytes: ByteArray,
        val resourceStart: Int,
        val huffcdic: Int,
        val numHuffcdic: Int,
        val trailingFlags: Long,
        val indx: Long,
        val exth: Exth?,
        val kf8: Kf8Fields?,
    ) {
        /** EXTH 121：KF8 段起始记录号（0xFFFFFFFF = 不是混合档） */
        val boundary: Long? get() = exth?.uint(121)
    }

    // EXTH：只留解析要用的几类 —— uint 型（121/201/202）和文本型（100/503）
    private class Exth {
        private val uints = HashMap<Int, Long>()
        private val textMap = HashMap<Int, MutableList<String>>()
        fun uint(type: Int): Long? = uints[type]
        fun putUint(type: Int, value: Long) {
            uints[type] = value
        }

        fun text(type: Int): String? = textMap[type]?.firstOrNull()
        fun texts(type: Int): List<String> = textMap[type] ?: emptyList()
        fun addText(type: Int, value: String) {
            textMap.getOrPut(type) { ArrayList(1) }.add(value)
        }
    }

    private fun parseHeaders(rec: ByteArray): PartHeaders {
        if (!magicAt(rec, 16, "MOBI")) throw IllegalArgumentException("Missing MOBI header")
        val mobiLength = u32i(rec, 20)
        val version = u32(rec, 36)
        val charset = charsetFor(u32(rec, 28))
        val titleOffset = u32i(rec, 84)
        val titleLength = u32i(rec, 88)
        val titleBytes = if (titleOffset >= 0 && titleLength > 0 && titleOffset < rec.size) {
            val end = (titleOffset.toLong() + titleLength).coerceAtMost(rec.size.toLong()).toInt()
            rec.copyOfRange(titleOffset, end)
        } else {
            ByteArray(0)
        }
        val exth = if ((u32(rec, 128) and 0x40L) != 0L) getExth(rec, mobiLength, charset) else null
        val kf8 = if (version >= 8) {
            Kf8Fields(
                fdst = u32i(rec, 192),
                frag = u32i(rec, 248),
                skel = u32i(rec, 252),
            )
        } else {
            null
        }
        return PartHeaders(
            compression = u16(rec, 0),
            encryption = u16(rec, 12),
            numTextRecords = u16(rec, 8),
            charset = charset,
            version = version,
            titleBytes = titleBytes,
            resourceStart = u32i(rec, 108),
            huffcdic = u32i(rec, 112),
            numHuffcdic = u32i(rec, 116),
            // Extra Record Data Flags / INDX 只在长头（≥228/232 字节）里有效，短头读出来是垃圾
            trailingFlags = if (mobiLength >= 228) u32(rec, 240) else 0L,
            indx = if (mobiLength >= 232) u32(rec, 244) else 0xFFFFFFFFL,
            exth = exth,
            kf8 = kf8,
        )
    }

    // EXTH 紧跟在 MOBI 头后面（foliate: buf.slice(mobi.length + 16)）
    private fun getExth(rec: ByteArray, mobiLength: Int, charset: Charset): Exth? {
        if (mobiLength < 0 || 16L + mobiLength + 12 > rec.size) return null
        val start = 16 + mobiLength
        if (!magicAt(rec, start, "EXTH")) return null
        val exth = Exth()
        val count = u32i(rec, start + 4)
        var offset = start + 12
        for (i in 0 until count) {
            if (offset + 8 > rec.size) break
            val type = u32i(rec, offset)
            val length = u32i(rec, offset + 4)
            if (length < 8) break // 长度非法直接收手，防死循环
            when (type) {
                100, 503 -> exth.addText(type, str(rec, offset + 8, length - 8, charset))
                121, 201, 202 -> exth.putUint(type, u32(rec, offset + 8))
            }
            offset += length
        }
        return exth
    }

    // DRM 一律拒开：PalmDOC encryption ≠ 0，或 MOBI 头 DRM Offset（记录 0 偏移 168）≠ 0xFFFFFFFF
    private fun checkDrm(headers: PartHeaders, rec: ByteArray) {
        val drmOffset = if (u32i(rec, 20) >= 156) u32(rec, 168) else 0xFFFFFFFFL
        if (headers.encryption != 0 || drmOffset != 0xFFFFFFFFL) {
            throw IllegalStateException("电子书受 DRM 保护，无法打开")
        }
    }

    private fun charsetFor(encoding: Long): Charset = when (encoding) {
        1252L -> runCatching { Charset.forName("windows-1252") }.getOrElse { Charsets.ISO_8859_1 }
        65001L -> Charsets.UTF_8
        else -> Charsets.UTF_8 // 同 TextDecoder 的缺省
    }

    // ---------------------------------------------------------------- INDX / TAGX

    private class IndexEntry(val name: String, val tags: Map<Int, List<Int>>)
    private class IndexData(val table: List<IndexEntry>, val cncx: Map<Int, String>)

    // tag 表一条控制字：[tag, numValues, mask, end]（MobileRead 规范，逐字节）
    private class TagVal(val tag: Int, val valueCount: Int?, val valueBytes: Int?, val numValues: Int)

    // INDX 主记录 + TAGX 标签表 + cncx 字符串池 → 表项（名称 + tag → 值列表）
    private fun getIndexData(indxIndex: Int, loadRecord: (Int) -> ByteArray): IndexData {
        val indxRecord = loadRecord(indxIndex)
        if (!magicAt(indxRecord, 0, "INDX")) throw IllegalArgumentException("Invalid INDX record")
        val charset = charsetFor(u32(indxRecord, 28))
        val headerLength = u32i(indxRecord, 4)
        val tagxBuffer = if (headerLength in 0..indxRecord.size) {
            indxRecord.copyOfRange(headerLength, indxRecord.size)
        } else {
            ByteArray(0)
        }
        if (!magicAt(tagxBuffer, 0, "TAGX")) throw IllegalArgumentException("Invalid TAGX section")
        val tagxLength = u32i(tagxBuffer, 4)
        val numControlBytes = u32i(tagxBuffer, 8)
        val numTags = ((tagxLength - 12) / 4).coerceAtLeast(0)
        val tagWords = Array(numTags) { i ->
            intArrayOf(
                u8(tagxBuffer, 12 + i * 4),
                u8(tagxBuffer, 13 + i * 4),
                u8(tagxBuffer, 14 + i * 4),
                u8(tagxBuffer, 15 + i * 4),
            )
        }

        val numIndxRecords = u32i(indxRecord, 24)
        val numCncx = u32i(indxRecord, 52)

        // cncx：变长长度 + 字符串拼成的池子，键 = 记录内字节位置 + 每记录 0x10000 的档位
        val cncx = HashMap<Int, String>()
        var cncxRecordOffset = 0
        for (i in 0 until numCncx) {
            val record = loadRecord(indxIndex + numIndxRecords + i + 1)
            var pos = 0
            while (pos < record.size) {
                val index = pos
                val (value, length) = getVarLen(record, pos)
                if (length <= 0) break
                pos += length
                val end = (pos.toLong() + value).coerceAtMost(record.size.toLong()).toInt()
                cncx[cncxRecordOffset + index] = str(record, pos, end - pos, charset)
                pos = end
            }
            cncxRecordOffset += 0x10000
        }

        val table = ArrayList<IndexEntry>()
        for (i in 0 until numIndxRecords) {
            val record = loadRecord(indxIndex + 1 + i)
            if (!magicAt(record, 0, "INDX")) throw IllegalArgumentException("Invalid INDX record")
            val entryCount = u32i(record, 24)
            val idxt = u32i(record, 20)
            for (j in 0 until entryCount) {
                val offset = u16(record, idxt + 4 + 2 * j)
                val nameLen = u8(record, offset)
                val name = str(record, offset + 1, nameLen, charset)
                val startPos = offset + 1 + nameLen
                var controlByteIndex = 0
                var pos = startPos + numControlBytes

                // 1) 先扫 tag 表，从控制字节里把每个 tag 的取值方式解出来
                val tags = ArrayList<TagVal>(tagWords.size)
                for (word in tagWords) {
                    val (tag, numValues, mask, end) = word
                    if ((end and 1) != 0) {
                        controlByteIndex++
                        continue
                    }
                    val value = u8(record, startPos + controlByteIndex) and mask
                    if (value == mask) {
                        if (mask.countOneBits() > 1) {
                            // 多位掩码：真正的值是后缀变长整数，这里存的是它占的字节数
                            val (v, l) = getVarLen(record, pos)
                            tags.add(TagVal(tag, null, v, numValues))
                            pos += l
                        } else {
                            tags.add(TagVal(tag, 1, null, numValues))
                        }
                    } else {
                        tags.add(TagVal(tag, value shr countUnsetEnd(mask), null, numValues))
                    }
                }

                // 2) 再按各自规则把值读成列表
                val tagMap = HashMap<Int, List<Int>>(tags.size)
                for (t in tags) {
                    val values = ArrayList<Int>()
                    val valueCount = t.valueCount
                    if (valueCount != null) {
                        repeat(valueCount * t.numValues) {
                            val (v, l) = getVarLen(record, pos)
                            values.add(v)
                            pos += l
                        }
                    } else {
                        var count = 0
                        while (count < (t.valueBytes ?: 0)) {
                            val (v, l) = getVarLen(record, pos)
                            if (l <= 0) break
                            values.add(v)
                            pos += l
                            count += l
                        }
                    }
                    tagMap[t.tag] = values
                }
                table.add(IndexEntry(name, tagMap))
            }
        }
        return IndexData(table, cncx)
    }

    // ---------------------------------------------------------------- 文本读取与解压

    /**
     * 一个 MOBI 部件（MOBI7 段或 KF8 段）的正文读取：按压缩方式解压 +
     * 按 trailingFlags 剥掉每条记录尾部的附加条目。
     */
    private class TextContext(
        private val reader: PdbReader,
        val headers: PartHeaders,
        private val start: Int,
    ) {
        private val removeTrailing: (ByteArray) -> ByteArray = trailingRemover(headers.trailingFlags)

        val decompress: (ByteArray) -> ByteArray = when (headers.compression) {
            1 -> { b -> b }
            2 -> { b -> decompressPalmDOC(b) }
            17480 -> buildHuffcdic(headers) { index -> loadRecord(index) }
            else -> throw IllegalArgumentException("Unknown compression type: ${headers.compression}")
        }

        fun loadRecord(index: Int): ByteArray = reader.record(start + index)

        fun loadText(index: Int): ByteArray = decompress(removeTrailing(loadRecord(index + 1)))
    }

    // trailingFlags：bit0 = 多字节重叠条目，其余置位数 = 尾部变长长度条目数。
    // 每条从记录末尾倒着读一个 4 字节 7bit 变长量，剥掉对应字节。
    private fun trailingRemover(trailingFlags: Long): (ByteArray) -> ByteArray {
        val multibyte = (trailingFlags and 1L) != 0L
        val numEntries = (trailingFlags ushr 1).countOneBits()
        return { raw ->
            var arr = raw
            repeat(numEntries) {
                if (arr.isEmpty()) return@repeat
                val len = getVarLenFromEnd(arr)
                arr = when {
                    len <= 0 -> arr
                    len >= arr.size -> ByteArray(0)
                    else -> arr.copyOf(arr.size - len)
                }
            }
            if (multibyte && arr.isNotEmpty()) {
                // 末字节低 2 位 = 跨记录多字节数，重叠字节会在下一条记录开头重现
                val n = ((arr[arr.size - 1].toInt() and 0xFF) and 0x3) + 1
                arr = if (n >= arr.size) ByteArray(0) else arr.copyOf(arr.size - n)
            }
            arr
        }
    }

    // PalmDOC LZ77 变体：0 = 字面 0，1..8 = 字面串，0x80..0xBF = 长度-距离对，0xC0.. = 空格+字符
    private fun decompressPalmDOC(src: ByteArray): ByteArray {
        val out = Buf((src.size shl 1) + 16)
        var i = 0
        while (i < src.size) {
            val b = src[i].toInt() and 0xFF
            when {
                b == 0 -> out.add(0)
                b <= 8 -> {
                    val end = minOf(i + b + 1, src.size)
                    var j = i + 1
                    while (j < end) {
                        out.add(src[j].toInt() and 0xFF)
                        j++
                    }
                    i += b
                }
                b <= 0x7F -> out.add(b)
                b <= 0xBF -> {
                    val next = if (i + 1 < src.size) src[i + 1].toInt() and 0xFF else 0
                    val pair = (b shl 8) or next
                    val distance = (pair and 0x3FFF) ushr 3
                    val length = (pair and 7) + 3
                    repeat(length) {
                        out.add(if (distance in 1..out.size) out[out.size - distance].toInt() and 0xFF else 0)
                    }
                    i++
                }
                else -> {
                    out.add(32)
                    out.add(b xor 0x80)
                }
            }
            i++
        }
        return out.out()
    }

    private class DictEntry(var bytes: ByteArray, var decompressed: Boolean)

    // HUFF/CDIC：1 条 HUFF 记录两张判别表 + N 条 CDIC 词典记录，按位流查表解压
    private fun buildHuffcdic(headers: PartHeaders, loadRecord: (Int) -> ByteArray): (ByteArray) -> ByteArray {
        val huff = loadRecord(headers.huffcdic)
        if (!magicAt(huff, 0, "HUFF")) throw IllegalArgumentException("Invalid HUFF record")
        val offset1 = u32i(huff, 8)
        val offset2 = u32i(huff, 12)

        // table1 按首字节索引：[命中位, 初始码长, 值]
        val table1 = Array(256) { i ->
            val x = u32i(huff, offset1 + i * 4)
            intArrayOf(x and 0x80, x and 0x1F, x ushr 8)
        }
        // table2 按码长索引：[0] 占位（foliate 是 [null].concat(32 项)，第 i 项在 offset2 + (i-1)*8）
        val table2 = Array(33) { LongArray(2) }
        for (i in 1..32) {
            val o = offset2 + (i - 1) * 8
            table2[i][0] = u32(huff, o)
            table2[i][1] = u32(huff, o + 4)
        }

        val dictionary = ArrayList<DictEntry>()
        for (i in 1 until headers.numHuffcdic) {
            val record = loadRecord(headers.huffcdic + i)
            if (!magicAt(record, 0, "CDIC")) throw IllegalArgumentException("Invalid CDIC record")
            val length = u32i(record, 4)
            val numEntries = u32i(record, 8)
            val codeLength = u32i(record, 12)
            // numEntries 是所有 CDIC 记录的总词条数，本记录只装剩下的那部分
            val n = minOf(1L shl codeLength.coerceIn(0, 30), (numEntries - dictionary.size).toLong()).toInt()
            val buffer = if (length in 0..record.size) record.copyOfRange(length, record.size) else ByteArray(0)
            var j = 0
            while (j < n) {
                val entryOffset = u16(buffer, j * 2)
                val x = u16(buffer, entryOffset)
                val entryLength = x and 0x7FFF
                val decompressed = (x and 0x8000) != 0
                val valueStart = entryOffset + 2
                val valueEnd = (valueStart.toLong() + entryLength).coerceAtMost(buffer.size.toLong()).toInt()
                val value = if (valueStart <= valueEnd) buffer.copyOfRange(valueStart, valueEnd) else ByteArray(0)
                dictionary.add(DictEntry(value, decompressed))
                j++
            }
        }

        fun decompress(src: ByteArray): ByteArray {
            val out = Buf((src.size shl 1) + 16)
            val bitLength = src.size * 8
            var i = 0
            while (i < bitLength) {
                val bits = read32Bits(src, i)
                val entry = table1[(bits ushr 24).toInt()]
                var codeLength = entry[1]
                var value: Int
                if (entry[0] == 0) {
                    // 首字节没命中：码长顺着 table2 的阈值往长了试
                    while (codeLength in 1..32 && (bits ushr (32 - codeLength)) < table2[codeLength][0]) {
                        codeLength++
                    }
                    if (codeLength !in 1..32) break
                    value = table2[codeLength][1].toInt()
                } else {
                    if (codeLength <= 0) break // 码长 0 会让位流原地打转
                    value = entry[2]
                }
                i += codeLength
                if (i > bitLength) break
                val code = value - (bits ushr (32 - codeLength)).toInt()
                if (code !in dictionary.indices) break
                val e = dictionary[code]
                if (!e.decompressed) {
                    // 词典项本身还是压缩的，递归解出来后缓存住
                    e.bytes = decompress(e.bytes)
                    e.decompressed = true
                }
                out.addAll(e.bytes)
            }
            return out.out()
        }

        return { decompress(it) }
    }

    // ---------------------------------------------------------------- PDB 容器

    /** 连 PalmDB 结构都读不出来的文件：不是 PDB/MOBI，回 null 而不是报错 */
    private class NotPdb : Exception()

    // PalmDB：78 字节头 + 每条 8 字节的偏移表（大端）；记录按需读，LRU 缓存
    //（loadRaw / huffcdic 会反复摸同一批记录）
    private class PdbReader(file: File) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        val numRecords: Int
        private val offsets: IntArray
        private val cache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
                size > MAX_CACHED_RECORDS
        }

        init {
            val head = ByteArray(78)
            raf.seek(0)
            raf.readFully(head)
            numRecords = u16(head, 76)
            if (numRecords <= 0) throw NotPdb()
            val entries = ByteArray(numRecords * 8)
            raf.seek(78)
            // 记录表读不完 = 头部是垃圾（随机文本文件之类），按“不是 MOBI”处理
            try {
                raf.readFully(entries)
            } catch (e: Exception) {
                throw NotPdb()
            }
            offsets = IntArray(numRecords) { u32i(entries, it * 8) }
        }

        fun record(index: Int): ByteArray {
            cache[index]?.let { return it }
            if (index < 0 || index >= numRecords) throw IllegalArgumentException("record out of bounds: $index")
            val start = offsets[index]
            val end = if (index + 1 < numRecords) offsets[index + 1] else raf.length().toInt()
            val length = end - start
            if (start < 0 || length <= 0) throw IllegalArgumentException("bad record bounds: $index")
            val buf = ByteArray(length)
            raf.seek(start.toLong())
            raf.readFully(buf)
            cache[index] = buf
            return buf
        }

        override fun close() {
            raf.close()
        }
    }

    // ---------------------------------------------------------------- 底层字节工具

    // MOBI/PDB 全大端；Java 的 byte 有符号，每次读出先掩成无符号
    private fun u8(b: ByteArray, off: Int): Int = if (off in b.indices) b[off].toInt() and 0xFF else 0
    private fun u16(b: ByteArray, off: Int): Int = (u8(b, off) shl 8) or u8(b, off + 1)
    private fun u32(b: ByteArray, off: Int): Long = (u16(b, off).toLong() shl 16) or u16(b, off + 2).toLong()
    private fun u32i(b: ByteArray, off: Int): Int = u32(b, off).toInt()

    // 越界一律读 0，坏数据交给上层的合法性判断，不在读取层抛异常
    private fun str(b: ByteArray, off: Int, len: Int, cs: Charset = Charsets.UTF_8): String {
        val start = off.coerceIn(0, b.size)
        val n = len.coerceIn(0, b.size - start)
        return String(b, start, n, cs)
    }

    private fun magicAt(b: ByteArray, off: Int, magic: String): Boolean =
        str(b, off, magic.length, Charsets.US_ASCII) == magic

    // 变长整数：每字节 7bit，最高位 = 终止（foliate getVarLen）
    private fun getVarLen(a: ByteArray, i: Int): Pair<Int, Int> {
        var value = 0
        var length = 0
        var pos = i
        while (length < 4 && pos < a.size) {
            val byte = a[pos].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F)
            length++
            pos++
            if ((byte and 0x80) != 0) break
        }
        return value to length
    }

    // 同上，但从末尾倒着读（trailing entries 用，最高位标记值的起点）
    private fun getVarLenFromEnd(a: ByteArray): Int {
        var value = 0
        for (i in maxOf(0, a.size - 4) until a.size) {
            val byte = a[i].toInt() and 0xFF
            if ((byte and 0x80) != 0) value = 0
            value = (value shl 7) or (byte and 0x7F)
        }
        return value
    }

    // 掩码里末尾连续 0 的个数（x = 0 时是 32，正好对上 JS 移位取模的语义）
    private fun countUnsetEnd(x: Int): Int = Integer.numberOfTrailingZeros(x)

    // 从位 i 起取 32 位（大端位流，不足补 0），返回无符号值
    private fun read32Bits(a: ByteArray, from: Int): Long {
        val startByte = from ushr 3
        val end = from + 32
        val endByte = end ushr 3
        var bits = 0L
        for (i in startByte..endByte) {
            bits = (bits shl 8) or u8(a, i).toLong()
        }
        return (bits shr (8 - (end and 7))) and 0xFFFFFFFFL
    }

    private fun concat(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    private fun concat(a: ByteArray, b: ByteArray, c: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size + c.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        System.arraycopy(c, 0, out, a.size + b.size, c.size)
        return out
    }

    // 可增长字节缓冲，解压输出用（直接 ByteArrayOutputStream 会反复拷）
    private class Buf(initial: Int) {
        private var a = ByteArray(initial.coerceAtLeast(16))
        var size = 0
            private set

        private fun ensure(extra: Int) {
            if (size + extra <= a.size) return
            var n = a.size
            while (n < size + extra) n = n shl 1
            a = a.copyOf(n)
        }

        fun add(v: Int) {
            ensure(1)
            a[size++] = v.toByte()
        }

        fun addAll(src: ByteArray) {
            ensure(src.size)
            System.arraycopy(src, 0, a, size, src.size)
            size += src.size
        }

        operator fun get(i: Int): Byte = a[i]

        fun out(): ByteArray = a.copyOf(size)
    }

    // 分页标签（foliate 同款正则）；filepos 链接保留可见文字即可，无锚点跳转
    private val PAGEBREAK_RE = Regex("<\\s*(?:mbp:)?pagebreak[^>]*>", RegexOption.IGNORE_CASE)
    private val ENTITY_RE = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

    private const val MAX_CACHED_RECORDS = 64

    // EXTH 里的书名/作者带着 HTML 实体，还原常见几个（不做全量实体表）
    private fun unescapeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        return ENTITY_RE.replace(s) { m ->
            val body = m.groupValues[1]
            val decoded: String? = when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.toCodePointString()
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.toCodePointString()
                else -> when (body.lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> null
                }
            }
            decoded ?: m.value
        }
    }

    private fun Int.toCodePointString(): String? =
        if (this in 1..0x10FFFF) String(Character.toChars(this)) else null
}
