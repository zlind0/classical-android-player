package com.aurora.music.tts

/** 标准微软 SSML 组装（纯函数，可单测）。voice 名用短名如 zh-CN-XiaoxiaoNeural。 */
object SsmlBuilder {
    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** rate/pitch: 1.0 = 原速原调；转为 prosody 百分比。 */
    fun build(voice: MsVoice, text: String, rate: Float = 1f, pitch: Float = 1f): String {
        val r = ((rate - 1f) * 100).toInt()
        val p = ((pitch - 1f) * 100).toInt()
        return "<speak version='1.0' xml:lang='${voice.locale}'>" +
            "<voice name='${voice.code}'>" +
            "<prosody rate='$r%' pitch='$p%'>${escape(text)}</prosody>" +
            "</voice></speak>"
    }
}

/** 按句切分：单块不超 maxChars，优先在句末标点断开。长段落流式合成的基本单位。 */
object Chunker {
    private val BREAKS = setOf('。', '！', '？', '!', '?', '\n', ';', '；')

    fun split(text: String, maxChars: Int = 450): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= maxChars) return listOf(t)
        val out = mutableListOf<String>()
        var start = 0
        var lastBreak = -1
        var i = 0
        while (i < t.length) {
            if (t[i] in BREAKS) lastBreak = i
            if (i - start + 1 >= maxChars) {
                val cut = if (lastBreak > start) lastBreak + 1 else i + 1
                out.add(t.substring(start, cut).trim())
                start = cut
                lastBreak = -1
            }
            i++
        }
        if (start < t.length) out.add(t.substring(start).trim())
        return out.filter { it.isNotEmpty() }
    }
}
