package com.aurora.music.data.ebook

import android.util.Xml
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

// JVM 单测与设备共用：优先系统实现（与原来完全一致），
// 单测环境（android.jar stub）自动降级到 factory（xpp3 仅 testImplementation）。
private fun newPullParser(): XmlPullParser = try {
    android.util.Xml.newPullParser()
} catch (e: RuntimeException) {
    XmlPullParserFactory.newInstance().newPullParser()
}

// 自研 EPUB 解析（v1）：不引入第三方库，parse 产物进磁盘缓存，二次打开秒开。
// 处理 container.xml → OPF（元数据/ manifest / spine / 封面）→
// NCX 或 EPUB3 nav（嵌套目录 + 级别）→ spine XHTML 正文块（h1..h6 保留级别）。
// 正文图片 v1 只取 alt 文本；容错：任何单章失败跳过该章，不整本失败。

private data class RichBlock(val text: String, val level: Int, val id: String, val links: List<RawLink>)

/** 原始超链接（块坐标，href 未解析）。 */
private data class RawLink(val start: Int, val end: Int, val href: String)

object EpubParser {

    data class Meta(val title: String, val author: String, val cover: ByteArray?)

    fun parseMeta(file: File): Meta = runCatching {
        ZipFile(file).use { zip ->
            val opfPath = findOpf(zip) ?: return Meta("", "", null)
            val base = opfPath.substringBeforeLast('/', "")
            val opfBytes = zip.readText(opfPath) ?: return Meta("", "", null)
            val opf = parseOpf(opfBytes)
            val spineHrefs = opf.spine.mapNotNull { opf.manifest[it]?.href }
            val coverBytes = findCover(zip, base, opf, spineHrefs)?.let { zip.readBytes(it) }
            Meta(opf.title, opf.author, coverBytes)
        }
    }.getOrDefault(Meta("", "", null))

    fun parseFull(file: File): ParsedEbook {
        ZipFile(file).use { zip ->
            val opfPath = findOpf(zip) ?: return emptyBook(file)
            val base = opfPath.substringBeforeLast('/', "")
            val opfText = zip.readText(opfPath) ?: return emptyBook(file)
            val opf = parseOpf(opfText)

            // 章节：spine 顺序，只读 linear（缺 linear 视为 yes）
            val spineHrefs = opf.spine.mapNotNull { opf.manifest[it] }.map { it.href }
            if (spineHrefs.isEmpty()) return emptyBook(file)
            // entry 路径 → spine 序号（与 resolveEntry 同一归一化口径，供超链接定位）
            val entryToSpine = spineHrefs.mapIndexed { i, h ->
                val dec = runCatching { URLDecoder.decode(h.substringBefore('#'), "UTF-8") }
                    .getOrDefault(h.substringBefore('#'))
                normalizeZipPath(if (base.isBlank()) dec else "$base/$dec") to i
            }.toMap()
            val rawChapters = mutableListOf<List<RichBlock>>()
            val chapterEntries = mutableListOf<String>()
            // 无文字 spine（封面页/纯图页）会被跳过，spine 序号 ≠ 章节序号，这里记映射
            val spineToChapter = mutableMapOf<Int, Int>()
            // (spineIndex, blockId) → blockIndex，供目录锚点/超链接定位
            val idIndex = mutableMapOf<Pair<Int, String>, Int>()
            spineHrefs.forEachIndexed { si, href ->
                runCatching {
                    val entry = resolveEntry(zip, base, href) ?: return@runCatching
                    val text = zip.readText(entry) ?: return@runCatching
                    val blocks = parseBodyBlocks(text)
                    if (blocks.isNotEmpty()) {
                        blocks.forEachIndexed { bi, b ->
                            if (b.id.isNotBlank()) idIndex[si to b.id] = bi
                        }
                        spineToChapter[si] = rawChapters.size
                        rawChapters.add(blocks)
                        chapterEntries.add(entry)
                    }
                }
            }
            if (rawChapters.isEmpty()) return emptyBook(file)
            // 超链接解析（此时映射齐了）：站内 → 章/块下标，站外 → url，其余丢弃
            val chapters = rawChapters.mapIndexed { ci, blocks ->
                val title = blocks.firstOrNull { it.level in 1..6 }?.text
                    ?: file.name.substringBeforeLast('.')
                EbookChapter(
                    title,
                    blocks.mapIndexed { bi, b ->
                        EbookBlock(
                            b.text, b.level,
                            b.links.mapNotNull { l ->
                                resolveEbookLink(l.href, chapterEntries[ci], entryToSpine, spineToChapter, idIndex, l.start, l.end)
                            },
                        )
                    },
                )
            }

            val toc = buildToc(zip, base, opf, spineHrefs, spineToChapter, idIndex, chapters)
            return ParsedEbook(
                title = opf.title.ifBlank { file.name.substringBeforeLast('.') },
                author = opf.author,
                chapters = chapters,
                toc = toc,
            )
        }
    }

    /** 超链接 href → 已解析目标。站内：相对当前章节文件定位；锚点对不上就落章首。 */
    private fun resolveEbookLink(
        href: String,
        curEntry: String,
        entryToSpine: Map<String, Int>,
        spineToChapter: Map<Int, Int>,
        idIndex: Map<Pair<Int, String>, Int>,
        start: Int,
        end: Int,
    ): EbookLink? {
        val h = href.trim()
        if (h.isEmpty()) return null
        val scheme = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""").find(h)?.groupValues?.get(1)?.lowercase()
        if (scheme != null) {
            return if (scheme == "http" || scheme == "https") EbookLink(start, end, -1, -1, h) else null
        }
        val filePart = h.substringBefore('#')
        val frag = h.substringAfter('#', "")
        val targetEntry = if (filePart.isBlank()) {
            curEntry
        } else {
            val dec = runCatching { URLDecoder.decode(filePart, "UTF-8") }.getOrDefault(filePart)
            val dir = curEntry.substringBeforeLast('/', "")
            normalizeZipPath(
                when {
                    dec.startsWith("/") -> dec.drop(1)
                    dir.isBlank() -> dec
                    else -> "$dir/$dec"
                },
            )
        }
        val si = entryToSpine[targetEntry] ?: return null
        val ci = spineToChapter[si] ?: return null
        val bi = if (frag.isBlank()) 0 else idIndex[si to frag] ?: 0
        return EbookLink(start, end, ci, bi)
    }

    private fun emptyBook(file: File): ParsedEbook {
        val name = file.name.substringBeforeLast('.')
        val chapter = EbookChapter(name, listOf(EbookBlock("无法解析本书内容。", 0)))
        return ParsedEbook(title = name, author = "", chapters = listOf(chapter), toc = listOf(EbookTocEntry(0, 0, name, 1)))
    }

    // ---- container / opf ----

    private fun findOpf(zip: ZipFile): String? {
        val xml = zip.readText("META-INF/container.xml") ?: return null
        // <rootfile full-path="OEBPS/content.opf" .../>
        return Regex("""<rootfile[^>]*full-path\s*=\s*["']([^"']+)["']""").find(xml)?.groupValues?.get(1)
    }

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)
    private data class Opf(
        val title: String,
        val author: String,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val guideCover: String,
        val metaCoverId: String,
    )

    private fun parseOpf(xml: String): Opf {
        var title = ""
        var author = ""
        var guideCover = ""
        var metaCoverId = ""
        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()
        try {
            val p: XmlPullParser = newPullParser()
            p.setInput(xml.reader())
            var event = p.eventType
            var inMetadata = false
            var text = ""
            var pendingMetaName = ""
            var pendingMetaContent = ""
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (p.name.substringAfter(':')) {
                            "metadata" -> inMetadata = true
                            "item" -> {
                                val id = p.getAttributeValue(null, "id").orEmpty()
                                val href = p.getAttributeValue(null, "href").orEmpty()
                                if (id.isNotBlank() && href.isNotBlank()) {
                                    manifest[id] = ManifestItem(
                                        href = href,
                                        mediaType = p.getAttributeValue(null, "media-type").orEmpty(),
                                        properties = p.getAttributeValue(null, "properties").orEmpty(),
                                    )
                                }
                            }
                            "itemref" -> {
                                val idref = p.getAttributeValue(null, "idref").orEmpty()
                                val linear = p.getAttributeValue(null, "linear").orEmpty()
                                if (idref.isNotBlank() && linear != "no") spine.add(idref)
                            }
                            "reference" -> {
                                if (p.getAttributeValue(null, "type") == "cover") {
                                    guideCover = p.getAttributeValue(null, "href").orEmpty()
                                }
                            }
                            "meta" -> {
                                // <meta name="cover" content="cover-id"/> 兼容 EPUB2 写法
                                pendingMetaName = p.getAttributeValue(null, "name").orEmpty()
                                pendingMetaContent = p.getAttributeValue(null, "content").orEmpty()
                                if (pendingMetaName == "cover" && pendingMetaContent.isNotBlank()) {
                                    metaCoverId = pendingMetaContent
                                }
                            }
                        }
                        text = ""
                    }
                    XmlPullParser.TEXT -> text = p.text ?: ""
                    XmlPullParser.END_TAG -> {
                        val name = p.name.substringAfter(':')
                        if (name == "metadata") inMetadata = false
                        else if (inMetadata) {
                            when (name) {
                                "title" -> if (title.isBlank()) title = text.trim()
                                "creator" -> if (author.isBlank()) author = text.trim()
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
        }
        return Opf(title, author, manifest, spine, guideCover, metaCoverId)
    }

    private fun findCover(zip: ZipFile, base: String, opf: Opf, spineHrefs: List<String> = emptyList()): String? {
        // 1) properties="cover-image"（EPUB3 标准）
        opf.manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }
            ?.let { return resolveEntry(zip, base, it.href) }
        // 2) <meta name="cover" content=id/>
        opf.manifest[opf.metaCoverId]?.let { return resolveEntry(zip, base, it.href) }
        // 3) guide reference type=cover
        if (opf.guideCover.isNotBlank()) return resolveEntry(zip, base, opf.guideCover)
        // 3.5) 第一章（常为封面页）的配图通常就是封面（Calibre 书常见）
        spineHrefs.firstOrNull()?.let { href ->
            runCatching {
                val entry = resolveEntry(zip, base, href) ?: return@runCatching null
                val html = zip.readText(entry) ?: return@runCatching null
                val src = Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.substringBefore('#').orEmpty()
                if (src.isBlank()) return@runCatching null
                // img 相对的是章节文件所在目录，不是 opf 目录
                val dir = entry.substringBeforeLast('/', "")
                val cand = normalizeZipPath(if (dir.isBlank()) src else "$dir/$src")
                if (zip.getEntry(cand) != null) cand else null
            }.getOrNull()?.let { return it }
        }
        // 4) manifest 里第一张图
        opf.manifest.values.firstOrNull { it.mediaType.startsWith("image/") }
            ?.let { return resolveEntry(zip, base, it.href) }
        return null
    }

    private fun resolveEntry(zip: ZipFile, base: String, href: String): String? {
        val raw = URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        if (raw.isBlank()) return null
        val candidates = listOf(
            if (base.isBlank()) raw else "$base/$raw",
            raw,
        )
        for (c in candidates) {
            // 规范化 ../
            val norm = normalizeZipPath(c)
            if (zip.getEntry(norm) != null) return norm
        }
        return null
    }

    private fun normalizeZipPath(p: String): String {
        val out = ArrayDeque<String>()
        p.split('/').forEach {
            when (it) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.add(it)
            }
        }
        return out.joinToString("/")
    }

    // ---- 目录：NCX 优先，其次 EPUB3 nav，都没有则从正文 h1/h2 生成 ----

    private data class RawToc(val href: String, val title: String, val level: Int, val order: Int = 0)

    private fun buildToc(
        zip: ZipFile,
        base: String,
        opf: Opf,
        spineHrefs: List<String>,
        spineToChapter: Map<Int, Int>,
        idIndex: Map<Pair<Int, String>, Int>,
        chapters: List<EbookChapter>,
    ): List<EbookTocEntry> {
        val raw = readNcx(zip, base, opf) ?: readNavDoc(zip, base, opf)
        if (!raw.isNullOrEmpty()) {
            // href → (spineIndex, fragment)
            val spineIndexOf = spineHrefs.mapIndexed { i, h ->
                normalizeZipPath(if (base.isBlank()) h else "$base/$h") to i
            }.toMap()
            val out = mutableListOf<EbookTocEntry>()
            raw.forEach { r ->
                val file = r.href.substringBefore('#')
                val frag = r.href.substringAfter('#', "")
                val entry = resolveEntry(zip, base, file) ?: return@forEach
                val si = spineIndexOf[entry] ?: return@forEach
                // 跳过无文字 spine（封面页）：目录里不要指向不存在的章
                val ci = spineToChapter[si] ?: return@forEach
                val blockCount = chapters.getOrNull(ci)?.blocks?.size ?: 0
                val bi = (if (frag.isNotBlank()) idIndex[si to frag] ?: 0 else 0)
                    .coerceIn(0, (blockCount - 1).coerceAtLeast(0))
                val title = r.title.ifBlank { chapters.getOrNull(ci)?.title.orEmpty() }
                out.add(EbookTocEntry(ci, bi, title, r.level.coerceIn(1, 6)))
            }
            if (out.isNotEmpty()) return out
        }
        // 回退：正文 h1/h2；再没有则每章一条
        val out = mutableListOf<EbookTocEntry>()
        chapters.forEachIndexed { ci, c ->
            var added = false
            c.blocks.forEachIndexed { bi, b ->
                if (b.level in 1..2 && b.text.isNotBlank()) {
                    out.add(EbookTocEntry(ci, bi, b.text, b.level))
                    added = true
                }
            }
            if (!added) out.add(EbookTocEntry(ci, 0, c.title, 1))
        }
        return out
    }

    private fun readNcx(zip: ZipFile, base: String, opf: Opf): List<RawToc>? {
        // 标准声明是 x-dtbncx+xml，但 Calibre 等工具会写成 x-dtbresource+xml，
        // 按 href 后缀兜底认 .ncx
        val ncxItem = opf.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ?: opf.manifest.values.firstOrNull { it.href.substringBefore('#').lowercase().endsWith(".ncx") }
            ?: return null
        val entry = resolveEntry(zip, base, ncxItem.href) ?: return null
        val xml = zip.readText(entry) ?: return null
        val out = mutableListOf<RawToc>()
        // navPoint 可嵌套：用栈保存每层的 (label, src)，内层结束不影响外层。
        // 结束标签是内层先行，所以按开始顺序号重排，保证父条目永远在子条目前面。
        data class Frame(val label: StringBuilder = StringBuilder(), var src: String = "", val order: Int = 0)
        val stack = ArrayDeque<Frame>()
        var inLabel = false
        var seq = 0
        try {
            val p: XmlPullParser = newPullParser()
            p.setInput(xml.reader())
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                        "navPoint" -> stack.add(Frame(order = seq++))
                        "navLabel" -> inLabel = true
                        "content" -> {
                            val top = stack.lastOrNull()
                            if (top != null && top.src.isBlank()) {
                                top.src = p.getAttributeValue(null, "src").orEmpty()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inLabel) stack.lastOrNull()?.label?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name.substringAfter(':')) {
                        "navLabel" -> inLabel = false
                        "navPoint" -> {
                            val frame = if (stack.isNotEmpty()) stack.removeLast() else null
                            if (frame != null && frame.src.isNotBlank()) {
                                out.add(RawToc(frame.src, frame.label.toString().trim(), stack.size + 1, frame.order))
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
            return null
        }
        if (out.isEmpty()) return null
        out.sortBy { it.order }
        return out
    }

    private fun readNavDoc(zip: ZipFile, base: String, opf: Opf): List<RawToc>? {
        val navItem = opf.manifest.values.firstOrNull {
            it.properties.split(' ').contains("nav") || it.properties == "nav"
        } ?: return null
        val entry = resolveEntry(zip, base, navItem.href) ?: return null
        val html = zip.readText(entry) ?: return null
        // 定位 toc nav：<nav ...>…</nav> 取含 toc 标记的那个
        val navBlocks = Regex("""<nav\b[^>]*>(.*?)</nav>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).map { it.groupValues[1] }.toList()
        if (navBlocks.isEmpty()) return null
        val tocNav = navBlocks.firstOrNull {
            it.contains("epub:type=\"toc\"") || it.contains("epub:type='toc'") ||
                it.contains("type=\"toc\"") || Regex("""<ol\b""", RegexOption.IGNORE_CASE).containsMatchIn(it)
        } ?: navBlocks.first()
        // 扁平扫描 li，按 ol 嵌套深度定级
        val out = mutableListOf<RawToc>()
        var olDepth = 0
        val tagRe = Regex("""<(/?)(ol|li|a)\b([^>]*)>|([^<]+)""", RegexOption.IGNORE_CASE)
        var pendingHref = ""
        var pendingText = StringBuilder()
        var inA = false
        var liLevel = 1
        tagRe.findAll(tocNav).forEach { m ->
            val closing = m.groupValues[1]
            val tag = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            val text = m.groupValues[4]
            when {
                tag == "ol" && closing.isEmpty() -> olDepth++
                tag == "ol" -> olDepth = (olDepth - 1).coerceAtLeast(0)
                tag == "li" && closing.isEmpty() -> {
                    liLevel = olDepth.coerceAtLeast(1)
                    pendingHref = ""
                    pendingText = StringBuilder()
                }
                tag == "a" && closing.isEmpty() -> {
                    inA = true
                    pendingHref = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(attrs)?.groupValues?.get(1).orEmpty()
                }
                tag == "a" -> {
                    inA = false
                    if (pendingHref.isNotBlank()) {
                        out.add(RawToc(pendingHref, pendingText.toString().trim(), liLevel))
                        pendingHref = ""
                    }
                }
                text.isNotEmpty() && inA -> pendingText.append(unescape(text))
            }
        }
        return out.ifEmpty { null }
    }

    // ---- 正文块 ----

    private val blockTagRe = Regex(
        """<(p|h1|h2|h3|h4|h5|h6|div|li|blockquote|dt|dd|pre|section|article)\b([^>]*)>(.*?)</\1\s*>""" +
            """|<(br|hr)\b[^>]*/?>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val idRe = Regex("""\bid\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val innerTagRe = Regex("""<[^>]+>""")
    private val imgAltRe = Regex("""<img\b[^>]*\balt\s*=\s*["']([^"']*)["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val aTagRe = Regex("""<a\b([^>]*)>(.*?)</a\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val hrefRe = Regex("""\bhref\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val blankCollapseRe = Regex("""[ \t\x0B\f\r]+""")

    private fun cleanInline(s: String): String = unescape(innerTagRe.replace(s, ""))

    /**
     * 块内 HTML 按 <a> 切段：每段单独去标签+反转义后拼接，超链接区间
     * 直接就是拼接坐标，无需事后对齐。无 href 的纯锚点当普通文本。
     */
    private fun extractLinkPieces(inner: String): Pair<String, List<RawLink>> {
        if (!inner.contains("<a", ignoreCase = true)) return cleanInline(inner) to emptyList()
        val sb = StringBuilder()
        val links = mutableListOf<RawLink>()
        var pos = 0
        aTagRe.findAll(inner).forEach { m ->
            sb.append(cleanInline(inner.substring(pos, m.range.first)))
            val href = hrefRe.find(m.groupValues[1])?.groupValues?.get(1).orEmpty()
            val label = cleanInline(m.groupValues[2])
            if (href.isNotBlank() && label.isNotBlank()) {
                links.add(RawLink(sb.length, sb.length + label.length, href))
            }
            sb.append(label)
            pos = m.range.last + 1
        }
        sb.append(cleanInline(inner.substring(pos)))
        return sb.toString() to links
    }

    /**
     * 与旧管线逐字节一致的 trim + 压空白，并把链接区间重映射过去
     * （旧：unescape(strip).trim().replace([ \t\x0B\f\r]+ → " ")）。
     */
    private fun finalizeBlock(merged: String, links: List<RawLink>): Pair<String, List<RawLink>> {
        // 与旧管线逐字节一致：Kotlin trim() 只去 <= ' ' 的字符，再把 [ \t\x0B\f\r]+ 压成单空格
        fun isTrimChar(c: Char): Boolean = c <= ' '
        var s = 0
        var e = merged.length
        while (s < e && isTrimChar(merged[s])) s++
        while (e > s && isTrimChar(merged[e - 1])) e--
        if (links.isEmpty()) {
            val text = merged.substring(s, e).replace(blankCollapseRe, " ")
            return text to emptyList()
        }
        val out = StringBuilder()
        val map = mutableListOf<Int>() // out 坐标 → merged 坐标
        var i = s
        while (i < e) {
            val c = merged[i]
            if (c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' || c == '\r') {
                out.append(' ')
                map.add(i++)
                while (i < e && (merged[i] == ' ' || merged[i] == '\t' || merged[i] == '\u000B' || merged[i] == '\u000C' || merged[i] == '\r')) i++
            } else {
                out.append(c)
                map.add(i++)
            }
        }
        fun lowerBound(v: Int): Int {
            var lo = 0
            var hi = map.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (map[mid] < v) lo = mid + 1 else hi = mid
            }
            return lo
        }
        val remapped = links.mapNotNull { l ->
            val ns = lowerBound(l.start.coerceIn(s, e))
            val ne = lowerBound(l.end.coerceIn(s, e))
            if (ns < ne) RawLink(ns, ne, l.href) else null
        }
        return out.toString() to remapped
    }

    /** 把链接裁剪到 [from, to) 并平移到以 from 为 0 的坐标。 */
    private fun clipLinks(links: List<RawLink>, from: Int, to: Int, base: Int): List<RawLink> =
        links.mapNotNull { l ->
            val a = maxOf(l.start, from)
            val e = minOf(l.end, to)
            if (a < e) RawLink(a - base, e - base, l.href) else null
        }

    private fun parseBodyBlocks(html: String): List<RichBlock> {
        // 去掉 head/script/style
        var s = Regex("""<head\b.*?</head\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .replace(html, "")
        s = Regex("""<(script|style)\b.*?</\1\s*>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .replace(s, "")
        val out = mutableListOf<RichBlock>()
        blockTagRe.findAll(s).forEach { m ->
            val tag = m.groupValues[1].lowercase()
            if (tag == "br") {
                return@forEach
            }
            if (tag.isEmpty()) return@forEach // br/hr
            val attrs = m.groupValues[2]
            var inner = m.groupValues[3]
            // 图片保留 alt
            imgAltRe.findAll(inner).forEach { im ->
                val alt = im.groupValues[1]
                inner = inner.replace(im.value, if (alt.isBlank()) "" else "［图：$alt］")
            }
            // 先按 <a> 切段（逐段去标签+反转义，超链接区间天然对齐），再整体 trim+压空白
            val (merged, rawLinks) = extractLinkPieces(inner)
            val (text, links) = finalizeBlock(merged, rawLinks)
            if (text.isBlank() || text.length < 2 && tag == "div") return@forEach
            // 过长 div（含整章的容器 div）拆行，避免一页巨块；链接按行裁剪
            if (tag == "div" && text.length > 600) {
                var pos = 0
                text.split('\n').forEach { piece ->
                    val t = piece.trim()
                    if (t.isNotBlank()) {
                        // piece 在 text 中的区间（trim 前后偏移需回扣）
                        val start = text.indexOf(piece, pos)
                        if (start >= 0) {
                            val lead = piece.length - piece.trimStart().length
                            val trail = piece.length - t.length - lead
                            val from = start + lead
                            val to = start + piece.length - trail
                            out.add(RichBlock(t, 0, "", clipLinks(links, from, to, from)))
                            pos = start + piece.length
                        }
                    } else {
                        pos += piece.length + 1
                    }
                }
                return@forEach
            }
            val level = when (tag) {
                "h1" -> 1
                "h2" -> 2
                "h3" -> 3
                "h4" -> 4
                "h5" -> 5
                "h6" -> 6
                else -> 0
            }
            val id = idRe.find(attrs)?.groupValues?.get(1).orEmpty()
            out.add(RichBlock(text, level, id, links))
        }
        // 兜底：没有任何块级标签（纯文本 spine），按行切
        if (out.isEmpty()) {
            val text = unescape(innerTagRe.replace(s, "")).trim()
            text.split(Regex("""\n\s*\n|\n""")).map { it.trim() }.filter { it.isNotBlank() }
                .forEach { out.add(RichBlock(it, 0, "", emptyList())) }
        }
        return out
    }

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        var r = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
        r = Regex("&#(\\d+);").replace(r) {
            runCatching { String(Character.toChars(it.groupValues[1].toInt())) }.getOrDefault(it.value)
        }
        r = Regex("&#x([0-9a-fA-F]+);").replace(r) {
            runCatching { String(Character.toChars(it.groupValues[1].toInt(16))) }.getOrDefault(it.value)
        }
        return r
    }

    private fun ZipFile.readText(entryName: String): String? = runCatching {
        getEntry(entryName)?.let { e ->
            getInputStream(e).bufferedReader(Charsets.UTF_8).readText()
        }
    }.getOrNull()

    private fun ZipFile.readBytes(entryName: String): ByteArray? = runCatching {
        getEntry(entryName)?.let { e -> getInputStream(e).readBytes() }
    }.getOrNull()
}
