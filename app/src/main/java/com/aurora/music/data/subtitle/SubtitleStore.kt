package com.aurora.music.data.subtitle

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * 字幕缓存：按 (书, 章节) 存 JSON，二次收听零延迟。
 *
 * 存的是「处理结果」而不是「生成到第几秒」：
 *  - [Cached.segments]：VAD 切出来的每个语音片段（含识别文本；没出字的片段 text 为空）。
 *    字幕条由这些片段重新切句得到，同一段音频不会因为拖动进度条被重复识别。
 *  - [Cached.spans]：已经解码 + 识别过的音频区间。跳着听时会形成多段区间，
 *    中间跳过去的空洞不会被算成已生成，回头再听时会重新生成。
 *
 * 位置：filesDir/subtitles/<bookHash>/<chapterHash>.json
 */
class SubtitleStore(private val context: Context) {

    data class Cached(
        val modelId: String,
        val segments: List<SubtitleSegment>,
        val spans: List<SubtitleCoverage.Span>,
        /** 读到章节音频末尾时的时间点；0 表示还没读到末尾。 */
        val eofSec: Double = 0.0,
    )

    private data class Payload(
        val version: Int = 1,
        val modelId: String = "",
        val generatedUntil: Double = 0.0,
        val eof: Double = 0.0,
        val spans: List<SpanDto>? = null,
        val cues: List<CueDto> = emptyList(),
    )

    private data class CueDto(val s: Double, val e: Double, val t: String = "")

    private data class SpanDto(val s: Double, val e: Double)

    private val gson = Gson()

    private fun dirOf(bookId: String): File =
        File(File(context.filesDir, "subtitles"), md5(bookId))

    private fun fileOf(bookId: String, chapterKey: String): File =
        File(dirOf(bookId), md5(chapterKey) + ".json")

    fun load(bookId: String, chapterKey: String): Cached? {
        val f = fileOf(bookId, chapterKey)
        if (!f.isFile) return null
        return try {
            val p = gson.fromJson(f.readText(), Payload::class.java) ?: return null
            val segments = p.cues.map { SubtitleSegment(it.s, it.e, it.t) }
            // v1 没有 spans；v2 的 spans 来自一个有 bug 的测试版本（会记录「已处理」但实际没识别），
            // 所以只信任 v3 及以后写出的区间，旧文件一律从片段反推。
            val trusted = if (p.version >= SPANS_VERSION) {
                (p.spans ?: emptyList())
                    .map { SubtitleCoverage.Span(it.s, it.e) }
                    .filter { it.end > it.start }
                    .sortedBy { it.start }
            } else {
                emptyList()
            }
            val spans = trusted.ifEmpty { legacySpans(segments) }
            Cached(p.modelId, segments, spans, p.eof)
        } catch (_: Throwable) {
            null
        }
    }

    fun save(
        bookId: String,
        chapterKey: String,
        modelId: String,
        segments: List<SubtitleSegment>,
        spans: List<SubtitleCoverage.Span>,
        eofSec: Double,
    ) {
        try {
            val f = fileOf(bookId, chapterKey)
            f.parentFile?.mkdirs()
            val payload = Payload(
                version = VERSION,
                modelId = modelId,
                generatedUntil = spans.maxOfOrNull { it.end } ?: 0.0,
                eof = eofSec,
                spans = spans.map { SpanDto(it.start, it.end) },
                cues = segments.map { CueDto(it.start, it.end, it.text) },
            )
            f.writeText(gson.toJson(payload))
        } catch (_: Throwable) {
            // 缓存失败不影响播放
        }
    }

    fun delete(bookId: String, chapterKey: String) {
        runCatching { fileOf(bookId, chapterKey).delete() }
    }

    fun clearAll() {
        runCatching { File(context.filesDir, "subtitles").deleteRecursively() }
    }

    fun bytes(): Long {
        val root = File(context.filesDir, "subtitles")
        if (!root.isDirectory) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun chapterCount(): Int {
        val root = File(context.filesDir, "subtitles")
        if (!root.isDirectory) return 0
        return root.walkTopDown().count { it.isFile && it.name.endsWith(".json") }
    }

    /** v1 缓存里的 cue 本身就是 VAD 段切出来的句子，按小间隙合并即可还原大致覆盖区间。 */
    private fun legacySpans(segments: List<SubtitleSegment>): List<SubtitleCoverage.Span> =
        SubtitleCoverage.mergePoints(
            segments.map { SubtitleCoverage.Span(it.start, it.end) },
            LEGACY_MERGE_GAP_SEC,
        )

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val VERSION = 3

        /** 从这一版开始 [Payload.spans] 才是可信的「已处理区间」。 */
        const val SPANS_VERSION = 3

        /** 旧缓存里相邻句子之间通常只有换气停顿，用 6 s 合并成一段覆盖区间。 */
        const val LEGACY_MERGE_GAP_SEC = 6.0
    }
}
