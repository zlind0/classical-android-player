package com.aurora.titlemerge

/**
 * 古典专辑乐章标题合并引擎。纯 Kotlin/JVM，无 Android 依赖。
 *
 * 输入已排序曲目，输出合并行：贪心从左往右，每次取公共词前缀最长的，
 * 词数相同时把共享该前缀的连续曲子全部收进来（取最大的 k）。
 *
 * 规则（按优先级）：
 * 1. 全体共有的前缀可能是噪音（"Bach -"），也可能是正文（"Symphony"）：
 *    剥掉重并一次，只计全局之外的词，谁多用谁；打平用原版。
 * 2. 大标题 ≥2 个非垃圾词直接并；只剩 1 个时必须半数覆盖（含恰好一半）；
 *    0 个永不并（纯垃圾桩）。
 * 3. 垃圾词：从第 0 位起逐位置统计，某词出现次数严格大于半数
 *    （同位置样本 ≥3 才统计），标点不算词。
 * 4. 每一首合并后都必须剩点东西（重名文件不并）。
 * 5. 展示去戳：大标题以全局前缀开头、且去掉后还剩 ≥2 词的，去掉。
 */
data class MergeInput(val id: String, val title: String)

data class MergedItem(val index: Int, val minor: String)

sealed interface MergedRow {
    data class Single(val index: Int) : MergedRow
    data class Group(val major: String, val items: List<MergedItem>) : MergedRow
}

private fun wordsOf(t: String): List<String> =
    t.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } }

private fun commonLen(ws: List<List<String>>, from: Int, to: Int): Int {
    var n = ws[from].size
    for (j in from + 1..to) {
        val a = ws[from]
        val b = ws[j]
        var k = 0
        while (k < n && k < b.size && a[k].lowercase() == b[k].lowercase()) k++
        n = k
        if (n == 0) break
    }
    return n
}

private fun commonAll(raw: List<List<String>>): Int {
    if (raw.isEmpty()) return 0
    var g = raw[0].size
    for (w in raw.drop(1)) {
        var k = 0
        while (k < g && k < w.size && raw[0][k].lowercase() == w[k].lowercase()) k++
        g = k
        if (g == 0) break
    }
    return g
}

private fun garbageWords(all: List<List<String>>): Set<String> {
    if (all.isEmpty()) return emptySet()
    val out = mutableSetOf<String>()
    val maxLen = all.maxOf { it.size }
    for (p in 0 until maxLen) {
        val present = all.filter { it.size > p }
        if (present.isEmpty()) break
        if (present.size < 3) continue
        present.groupingBy { it[p].lowercase() }.eachCount()
            .forEach { (w, c) -> if (c * 2 > present.size) out += w }
    }
    return out
}

private fun prefixOverlap(a: List<String>, b: List<String>): Int {
    var k = 0
    while (k < a.size && k < b.size && a[k].lowercase() == b[k].lowercase()) k++
    return k
}

private fun qualityBeyondGlobal(rows: List<MergedRow>, global: List<String>): Int =
    rows.sumOf { row ->
        if (row is MergedRow.Group) {
            val majorW = row.major.split(Regex("\\s+"))
            (majorW.size - prefixOverlap(majorW, global)).coerceAtLeast(0) * row.items.size
        } else 0
    }

private fun displayMajor(major: String, global: List<String>): String {
    val w = major.split(Regex("\\s+"))
    var drop = 0
    while (drop < w.size && drop < global.size &&
        w[drop].lowercase() == global[drop].lowercase() &&
        w.size - (drop + 1) >= 2
    ) drop++
    return if (drop == 0) major else w.drop(drop).joinToString(" ")
}

fun mergeTracks(tracks: List<MergeInput>): List<MergedRow> {
    if (tracks.size < 2) return tracks.indices.map { MergedRow.Single(it) }
    val raw = tracks.map { wordsOf(it.title) }
    val garbage = garbageWords(raw)
    val rawRows = greedy(raw, garbage)
    val g = commonAll(raw)
    val chosen = if (g > 0 && raw.all { it.size > g }) {
        val global = raw[0].take(g)
        val strippedRows = greedy(raw.map { it.drop(g) }, garbage)
        if (qualityBeyondGlobal(strippedRows, global) > qualityBeyondGlobal(rawRows, global)) {
            strippedRows
        } else rawRows
    } else rawRows
    return chosen.map { row ->
        if (row is MergedRow.Group) row.copy(major = displayMajor(row.major, raw[0].take(g))) else row
    }
}

private fun greedy(ws: List<List<String>>, garbage: Set<String>): List<MergedRow> {
    val out = ArrayList<MergedRow>(ws.size)
    var i = 0
    while (i < ws.size) {
        var bestK = -1
        var bestLen = 0
        var k = i + 1
        while (k < ws.size) {
            val cp = commonLen(ws, i, k)
            if (cp == 0) break
            if (cp >= bestLen && cp >= 1) {
                bestLen = cp
                bestK = k
            }
            k++
        }
        val size = bestK - i + 1
        val solid = if (bestK < 0) 0
        else ws[i].take(bestLen).count { it.lowercase() !in garbage }
        val ok = bestK >= 0 &&
            (i..bestK).all { ws[it].size > bestLen } &&
            (solid >= 2 || (solid >= 1 && size * 2 >= ws.size))
        if (ok) {
            out += MergedRow.Group(
                major = ws[i].take(bestLen).joinToString(" "),
                items = (i..bestK).map { j ->
                    MergedItem(j, ws[j].drop(bestLen).joinToString(" "))
                },
            )
            i = bestK + 1
        } else {
            out += MergedRow.Single(i)
            i++
        }
    }
    return out
}

/** 某首歌所在分组的（大标题，小标题），没被合并返回 null。 */
fun mergedTitleOf(rows: List<MergedRow>, ids: List<String>, songId: String): Pair<String, String>? {
    val pos = ids.indexOf(songId)
    if (pos < 0) return null
    for (row in rows) {
        if (row is MergedRow.Group) {
            row.items.firstOrNull { it.index == pos }
                ?.let { return row.major to it.minor }
        }
    }
    return null
}
