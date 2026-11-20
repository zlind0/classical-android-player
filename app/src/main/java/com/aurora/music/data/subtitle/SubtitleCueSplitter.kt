package com.aurora.music.data.subtitle

import kotlin.math.max

/**
 * 把 ASR 的一段文字切成「一句一条」的字幕，并按字数分摊时间。
 *
 * SenseVoice 一次给一整段（VAD 段，最长 12 s），Whisper 也常一次给多句。
 * 直接整段显示会太长，切成句级才适合「上句 / 本句 / 下句」三行展示。
 * 时间只是按字数比例分摊，不额外调用对齐模型。
 */
object SubtitleCueSplitter {

    private val SENTENCE_END = Regex("(?<=[.!?;:])\\s+")
    private val CJK_END = Regex("(?<=[。！？；：])")
    private val COMMA = Regex(",\\s+")

    fun split(text: String, start: Double, end: Double): List<SubtitleCue> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()

        var parts = SENTENCE_END.split(clean)
            .flatMap { CJK_END.split(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 超长且完全没有标点：退一步按逗号切，避免一屏塞不下
        if (parts.size == 1 && clean.length > 120) {
            parts = COMMA.split(clean).map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (parts.isEmpty()) parts = listOf(clean)

        val weights = parts.map { max(it.length, 1) }
        val total = weights.sum().toDouble().coerceAtLeast(1.0)
        val dur = (end - start).coerceAtLeast(0.2)
        var t = start
        return parts.mapIndexed { i, p ->
            val d = dur * weights[i] / total
            val cue = SubtitleCue(t, (t + d).coerceAtMost(start + dur), p)
            t += d
            cue
        }
    }
}
