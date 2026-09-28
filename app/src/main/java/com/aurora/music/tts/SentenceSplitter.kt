package com.aurora.music.tts

/**
 * 按句切分（纯函数，可单测）：返回块坐标系下的 [start, end) 区间，拼接后与原文完全一致（不丢字）。
 *
 * - 中文按中文标点断句：。！？；…（连续的 ？！/…… 等收拢为一句的句尾）
 * - 英文按英文标点断句：. ! ? ;（连续的 ?! / ... 等收拢为一句的句尾）
 * - 小数点不当句号：数字之间的单个 `.`（如 3.14）不断句；单个 `.` 只有后面跟
 *   空白/结尾/右引号右括号/CJK 时才算句末（顺带避开 e.g. / Mr.Smith 这类缩写）。
 * - 句尾的右引号右括号（”’）》」』】等）与空白归入前一句。
 * - 换行始终断句。无标点 = 整块一句。块首的纯空白断句会被滤掉（只丢空白，不丢字）。
 */
object SentenceSplitter {

    /** 中文句末标点（单个即断）。 */
    private val CN_ENDS = setOf('。', '！', '？', '；', '…')

    /** 英文固定句末标点（单个即断，`.` 另按规则判断）。 */
    private val EN_ENDS = setOf('!', '?', ';')

    /** 句尾后可吸附的右引号/右括号。 */
    private val CLOSERS = setOf(
        '"', '\'', '”', '’', '」', '』', '）', ')', '】', '》', '〉', '>',
    )

    fun split(text: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<IntRange>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val endKind: Int = when {
                c == '\n' -> 1
                c in CN_ENDS -> 2
                c in EN_ENDS -> 2
                c == '.' -> if (isDotEnd(text, i)) 2 else 0
                else -> 0
            }
            if (endKind == 0) {
                i++
                continue
            }
            var j = i + 1
            if (endKind == 2) {
                // 收拢连续句末标点：？！……?!...；; 等（小数点不参与收拢）
                while (j < text.length) {
                    val n = text[j]
                    if (n in CN_ENDS || n in EN_ENDS) {
                        j++
                    } else if (n == '.' && isDotEnd(text, j)) {
                        j++
                    } else {
                        break
                    }
                }
            }
            // 吸附右引号右括号与空白（含换行）到本句，下一句从非空白处起
            while (j < text.length && (text[j] in CLOSERS || text[j].isWhitespace())) j++
            pushSentence(out, start, j)
            start = j
            i = j
        }
        if (start < text.length) pushSentence(out, start, text.length)
        // 丢掉纯空白句（如块首空白独立成段的情况），但保证至少不断字：
        // 纯空白区间已并入相邻句（pushSentence 的连续保证），直接过滤即可
        return out.filter { text.substring(it.first, it.last + 1).isNotBlank() }
    }

    private fun pushSentence(out: MutableList<IntRange>, start: Int, endExclusive: Int) {
        if (endExclusive > start) out.add(start until endExclusive)
    }

    /**
     * 单个 `.` 是否为英文句末：数字之间（如 3.14）不是；
     * 其余情况下，只有后面是空白/结尾/右引号右括号/CJK 才算（避开 e.g. / Mr.Smith 内点）。
     * 连续两个以上 `..` 按省略号算句末。
     */
    private fun isDotEnd(t: String, i: Int): Boolean {
        val prev = t.getOrNull(i - 1)
        val next = t.getOrNull(i + 1)
        // 省略号 .. / ...：算句末（小数点不会连写两个）
        if (prev == '.' || next == '.') return true
        // 小数点：3.14
        if (prev != null && next != null && prev.isDigit() && next.isDigit()) return false
        if (next == null) return true
        if (next.isWhitespace()) return true
        if (next in CLOSERS) return true
        // CJK 紧跟英文句号（如中英混排“word.中文”）也算断句
        return next in '\u4E00'..'\u9FFF' || next in '\u3000'..'\u303F' || next in '\uFF00'..'\uFFEF'
    }
}
