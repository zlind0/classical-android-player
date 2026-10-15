package com.aurora.music.data.ebook.export

import com.aurora.music.data.ebook.EbookTocEntry
import com.aurora.music.data.ebook.ParsedEbook
import com.aurora.music.tts.Chunker
import com.aurora.music.tts.isSpeakable

/** 导出 TTS 音色选择。 */
sealed interface ExportVoice {
    /** 内置微软离线语音，如 zh-CN-XiaoxiaoNeural。 */
    data class Internal(val voiceCode: String) : ExportVoice

    /** Android 系统 TTS 引擎（null/空 = 系统默认引擎），中文音色自动选。 */
    data class System(val enginePackage: String? = null) : ExportVoice
}

data class ExportSynthSettings(
    val voice: ExportVoice = ExportVoice.Internal("zh-CN-XiaoxiaoNeural"),
    val rate: Float = 1.1f,
    val pitch: Float = 1f,
)

/**
 * 一个输出 M4A 单元：一条目录标题 + 直到下一条目前的全部正文块。
 * [texts] 按阅读顺序的可朗读串；[title] 为章节名（同时用作 MP4 title 与段首播报）。
 */
data class ExportSegment(
    val index: Int, // 全部段中的 0-based 序号
    val total: Int,
    val title: String,
    val texts: List<String>,
) {
    /** 1-based 带前导 0 序号文件名，如 003_第一节.m4a */
    fun fileName(): String = exportFileNameFor(index + 1, total, title)
}

/** 按书内目录切段：toc[i] 覆盖 [start, next start)，空段丢弃。 */
fun segmentEbook(book: ParsedEbook): List<ExportSegment> {
    data class Pos(val ci: Int, val bi: Int, val text: String)
    val flat = book.chapters.flatMapIndexed { ci, c ->
        c.blocks.mapIndexed { bi, b -> Pos(ci, bi, b.text) }
    }
    if (flat.isEmpty()) return emptyList()

    fun tocPos(e: EbookTocEntry): Int {
        val idx = flat.indexOfFirst { it.ci == e.chapterIndex && it.bi >= e.blockIndex }
            .takeIf { it >= 0 }
        if (idx != null) return idx
        // 孤儿章节（本章在目录中无链接）：挂到本章第一块。
        return flat.indexOfFirst { it.ci == e.chapterIndex }.takeIf { it >= 0 } ?: flat.size
    }

    val toc = book.toc
    val ranges: List<Pair<String, IntRange>> = if (toc.isEmpty()) {
        // 回退：每章一段。
        book.chapters.mapIndexed { ci, c ->
            val start = flat.indexOfFirst { it.ci == ci }
            val end = flat.indexOfLast { it.ci == ci }
            if (start < 0) "" to IntRange.EMPTY else c.title to (start..end)
        }.filter { it.second != IntRange.EMPTY }
    } else {
        val starts = toc.map { tocPos(it) }
        toc.mapIndexed { i, e ->
            val s = starts[i].coerceIn(0, flat.size)
            val until = if (i + 1 < toc.size) starts[i + 1].coerceIn(s, flat.size) else flat.size
            e.title to (s until until)
        }.filter { it.second.first < it.second.last }
    }

    val raw = ranges.mapNotNull { (title, r) ->
        val clean = title.trim().ifBlank { "Section" }
        val body = mutableListOf<String>()
        r.forEach { i -> flat.getOrNull(i)?.text?.let { body.add(it) } }
        // 无可读正文（纯图页/分隔页）：跳过，不产只有标题的空文件。
        val speakableBody = body.map { it.trim() }.filter { it.isNotEmpty() && isSpeakable(it) }
        if (speakableBody.isEmpty()) return@mapNotNull null
        val texts = mutableListOf<String>()
        // 段首播报标题（除非第一块本来就是该标题）。
        val firstBlock = flat.getOrNull(r.first)?.text?.trim()
        if (!firstBlock.equals(clean, ignoreCase = true)) texts.add(clean)
        texts.addAll(speakableBody)
        clean to texts
    }
    val total = raw.size
    return raw.mapIndexed { i, (title, texts) -> ExportSegment(i, total, title, texts) }
}

/** 25 章中的第 3 章 → "003"；宽度按总数位数，至少 3 位。 */
fun exportTrackPrefix(number1Based: Int, total: Int): String {
    val width = maxOf(3, total.toString().length)
    return number1Based.toString().padStart(width, '0')
}

fun exportFileNameFor(number1Based: Int, total: Int, title: String): String {
    val name = sanitizeExportFileName(title.trim().ifBlank { "Section $number1Based" })
    return "${exportTrackPrefix(number1Based, total)}_$name.m4a"
}

private val ILLEGAL_FILE_CHARS = Regex("""[/\\:*?"<>|]""")
private val CONTROL_CHARS = Regex("""[\p{Cntrl}]""")

/** 去路径分隔符/ID3 敏感字符/控制字符，压空白，限长。 */
fun sanitizeExportFileName(title: String): String {
    var s = ILLEGAL_FILE_CHARS.replace(title, "_")
    s = CONTROL_CHARS.replace(s, "")
    s = s.trim().replace(Regex("""\s+"""), " ").trim('.', ' ')
    if (s.isEmpty()) s = "Section"
    return if (s.length > 80) s.substring(0, 80).trimEnd() else s
}

enum class ExportPhase { SYNTH, REMUX, TAG, COPY }

data class EbookExportProgress(
    val done: Int,
    val total: Int,
    val currentTitle: String,
    val phase: ExportPhase,
    val installNote: String? = null,
    val batchDone: Int = 0,
    val batchTotal: Int = 0,
    val encodeDone: Int = 0,
    val mergedDone: Int = 0,
)

data class EbookExportResult(
    val done: Int,
    val failed: List<String>,
    val cancelled: Boolean,
    /** 在所选有声书来源下新建的书名子目录。 */
    val outputFolder: String = "",
    /** 已存在而跳过的段数（未勾覆盖时）。 */
    val skipped: Int = 0,
)

/** UI 线程数滑杆边界：1..本机核数。 */
fun clampExportThreads(requested: Int, cores: Int): Int =
    requested.coerceIn(1, maxOf(1, cores))

fun defaultExportThreads(): Int =
    Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

/**
 * 把段文本按块边界打包成 ≤[maxChars] 的合成批次（超长块先按句切分）。
 * 小批次让单次 PCM 恒小（400 字 ≈ 6MB 上限，800 字在 256MB 堆上
 * × 线程数直接 OOM）；打包（相对逐块调用）让系统 TTS binder 开销可接受。
 */
internal fun batchExportTexts(texts: List<String>, maxChars: Int = 400): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    fun flush() {
        val s = cur.toString().trim()
        if (s.isNotEmpty() && isSpeakable(s)) out.add(s)
        cur.clear()
    }
    for (t in texts) {
        for (chunk in Chunker.split(t.trim(), maxChars)) {
            if (cur.length + chunk.length + 1 > maxChars) flush()
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(chunk)
        }
    }
    flush()
    return out
}
