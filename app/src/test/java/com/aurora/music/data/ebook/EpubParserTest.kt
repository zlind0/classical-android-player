package com.aurora.music.data.ebook

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// EpubParser 回归测试：合成最小 EPUB，覆盖三个真实坑位——
// 嵌套 NCX 父条目保留、Calibre 式非标准 ncx 声明、无文字封面 spine 跳过后的序号映射、
// 封面回退到首章配图、目录锚点定位。
class EpubParserTest {

    private val coverBytes = byteArrayOf(1, 2, 3, 4, 5)

    private fun buildEpub(): File {
        val f = File.createTempFile("epub_test", ".epub")
        ZipOutputStream(f.outputStream()).use { z ->
            fun add(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            add(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""",
            )
            add(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>合成测试书</dc:title>
                    <dc:creator>合成作者</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbresource+xml"/>
                    <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="img" href="images/cover.jpg" media-type="image/jpeg"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="cover"/>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>""",
            )
            // 嵌套目录：父 A 含子 A.1（锚点），父 B
            add(
                "OEBPS/toc.ncx",
                """<?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="a"><navLabel><text>第一部分</text></navLabel><content src="text/ch1.xhtml"/>
                      <navPoint id="a1"><navLabel><text>第一节</text></navLabel><content src="text/ch1.xhtml#s1"/></navPoint>
                    </navPoint>
                    <navPoint id="b"><navLabel><text>第二部分</text></navLabel><content src="text/ch2.xhtml"/></navPoint>
                  </navMap>
                </ncx>""",
            )
            // 封面页：纯图无文字，应被跳过
            add(
                "OEBPS/text/cover.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>封面</title></head>
                <body><div><img alt="" src="../images/cover.jpg"/></div></body></html>""",
            )
            add(
                "OEBPS/text/ch1.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>一</title></head>
                <body><h1 id="s0">第一部分</h1><p>床前明月光，疑是地上霜。</p><h2 id="s1">第一节</h2><p>举头望明月，低头思故乡。</p></body></html>""",
            )
            add(
                "OEBPS/text/ch2.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>二</title></head>
                <body><h1>第二部分</h1><p>春眠不觉晓，处处闻啼鸟。</p></body></html>""",
            )
            z.putNextEntry(ZipEntry("OEBPS/images/cover.jpg"))
            z.write(coverBytes)
            z.closeEntry()
        }
        return f
    }

    @Test
    fun meta_coverFromFirstSpineImage() {
        val f = buildEpub()
        try {
            val m = EpubParser.parseMeta(f)
            assertEquals("合成测试书", m.title)
            assertEquals("合成作者", m.author)
            assertArrayEquals(coverBytes, m.cover)
        } finally {
            f.delete()
        }
    }

    @Test
    fun full_coverSpineSkippedAndTocMapped() {
        val f = buildEpub()
        try {
            val book = EpubParser.parseFull(f)
            // 封面 spine 无文字被跳过：只剩 2 章
            assertEquals(2, book.chapters.size)
            assertEquals("第一部分", book.chapters[0].title)
            assertEquals("第二部分", book.chapters[1].title)
            // 嵌套 NCX：父条目必须保留（栈修复前父 A 会丢）
            assertEquals(listOf("第一部分", "第一节", "第二部分"), book.toc.map { it.title })
            assertEquals(listOf(1, 2, 1), book.toc.map { it.level })
            // 章序号是 chapters 下标（不是 spine 序号）：B 在第 1 章
            assertEquals(0, book.toc[0].chapterIndex)
            assertEquals(0, book.toc[1].chapterIndex)
            assertEquals(1, book.toc[2].chapterIndex)
            // 锚点 #s1 定位到“第一节”所在块
            val sec = book.toc[1]
            val block = book.chapters[sec.chapterIndex].blocks[sec.blockIndex]
            assertEquals("第一节", block.text)
            assertEquals(2, block.level)
            // 正文无图片残留、有中文
            assertTrue(book.chapters[0].blocks.any { it.text.contains("床前明月光") })
        } finally {
            f.delete()
        }
    }

    // 套装书常见结构：子书封面页是纯图且 linear="no"，「总目录」链接和 NCX 顶级条目
    // 都指向它。这类页不进阅读流，但必须保留 spine 下标，把目标回落到其后第一个可读章节，
    // 否则整本书会出现「总目录链接点不动 + 目录里没有子书标题」。
    @Test
    fun full_nonLinearCoverTargetResolvesToNextChapter() {
        val f = File.createTempFile("epub_nonlinear", ".epub")
        try {
            ZipOutputStream(f.outputStream()).use { z ->
                fun add(name: String, text: String) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(text.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
                add(
                    "META-INF/container.xml",
                    """<?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""",
                )
                add(
                    "OEBPS/content.opf",
                    """<?xml version="1.0" encoding="utf-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>套装测试书</dc:title>
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="subcover" href="text/sub-cover.xhtml" media-type="application/xhtml+xml"/>
                        <item id="tocpage" href="text/toc.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine toc="ncx">
                        <itemref idref="tocpage"/>
                        <itemref idref="subcover" linear="no"/>
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                      </spine>
                    </package>""",
                )
                add(
                    "OEBPS/toc.ncx",
                    """<?xml version="1.0" encoding="utf-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <navMap>
                        <navPoint id="a"><navLabel><text>子书A</text></navLabel><content src="text/sub-cover.xhtml"/>
                          <navPoint id="a1"><navLabel><text>第一章</text></navLabel><content src="text/ch1.xhtml"/></navPoint>
                        </navPoint>
                        <navPoint id="b"><navLabel><text>子书B</text></navLabel><content src="text/ch2.xhtml"/></navPoint>
                      </navMap>
                    </ncx>""",
                )
                add(
                    "OEBPS/text/sub-cover.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><div><img alt="" src="../images/c.jpg"/></div></body></html>""",
                )
                add(
                    "OEBPS/text/toc.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>总目录</h1><div class="c1"><a href="../text/sub-cover.xhtml">子书A</a></div></body></html>""",
                )
                add(
                    "OEBPS/text/ch1.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第一章</h1><p>正文一。</p></body></html>""",
                )
                add(
                    "OEBPS/text/ch2.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第二章</h1><p>正文二。</p></body></html>""",
                )
            }
            val book = EpubParser.parseFull(f)
            // linear=no 的封面页不进阅读流：3 章（总目录 + 两章）
            assertEquals(3, book.chapters.size)
            // 子书标题必须留在目录里，且回落到其后第一个可读章节
            assertEquals(listOf("子书A", "第一章", "子书B"), book.toc.map { it.title })
            assertEquals(listOf(1, 2, 1), book.toc.map { it.level })
            assertEquals(1, book.toc[0].chapterIndex)
            assertEquals(2, book.toc[2].chapterIndex)
            // 「总目录」里的链接必须可点，同样回落到第一章
            val linkBlock = book.chapters[0].blocks.first { it.text == "子书A" }
            val link = linkBlock.links.single()
            assertEquals(1, link.chapter)
            assertEquals(0, link.block)
        } finally {
            f.delete()
        }
    }

    // 手动回归（默认跳过）：用真实大书验证 linear=no 封面页的目录/链接回落。
    // EBOOK_PROBE=/path/to/book.epub ./gradlew :app:testDebugUnitTest --tests '*realBookProbe'
    @Test
    fun realBookProbe() {
        val path = System.getenv("EBOOK_PROBE") ?: return
        val f = File(path)
        if (!f.isFile) return
        val book = EpubParser.parseFull(f)
        val level1 = book.toc.filter { it.level == 1 }
        println("probe: chapters=${book.chapters.size} toc=${book.toc.size} level1=${level1.size}")
        println("probe: level1 titles=${level1.take(6).map { it.title }}")
        val tocChapter = book.chapters.firstOrNull { c ->
            c.blocks.any { b -> b.links.isNotEmpty() && b.text == "幸福" }
        }
        if (tocChapter != null) {
            val items = tocChapter.blocks.filter { it.links.isNotEmpty() }
            println("probe: toc page blocksWithLinks=${items.size}")
            val first = items.first()
            val l = first.links.first()
            println("probe: first link '${first.text}' -> chapter=${l.chapter} block=${l.block} " +
                "head='${book.chapters[l.chapter].blocks.firstOrNull()?.text}'")
            println("probe: all links resolved=${items.count { it.links.isNotEmpty() }}/${items.size}")
            assertEquals("幸福", first.text)
        }
        assertTrue(level1.size >= 2)
        assertTrue(book.toc.any { it.title == "总目录" })
    }

    @Test
    fun entities_unescaped() {
        val f = File.createTempFile("epub_ent", ".epub")
        try {
            ZipOutputStream(f.outputStream()).use { z ->
                fun add(name: String, text: String) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(text.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
                add(
                    "META-INF/container.xml",
                    """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="c.opf" media-type="a/b"/></rootfiles></container>""",
                )
                add(
                    "c.opf",
                    """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>""",
                )
                add(
                    "a.xhtml",
                    """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>A&lt;B&gt;C&amp;D&#20013;&#x6587;&nbsp;E</p></body></html>""",
                )
            }
            val book = EpubParser.parseFull(f)
            assertEquals("A<B>C&D中文 E", book.chapters[0].blocks[0].text)
        } finally {
            f.delete()
        }
    }

    @Test
    fun zipSlipOrMissing_returnsEmptyBook() {
        val f = File.createTempFile("epub_bad", ".epub")
        try {
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("hello.txt"))
                z.write("hi".toByteArray())
                z.closeEntry()
            }
            val book = EpubParser.parseFull(f)
            assertEquals(1, book.chapters.size)
            assertTrue(book.toc.isNotEmpty())
        } finally {
            f.delete()
        }
    }
}
