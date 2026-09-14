package com.aurora.music.data

import com.aurora.music.playback.CorrectionCompiler
import kotlin.math.ln

// Classical fork v0.5.1: import a correction from a .txt file, e.g. AutoEq's
// fixed-band "GraphicEQ: 20 -9.5; 21 -9.5; ..." exports or Equalizer APO
// parametric "Preamp: / Filter N: ON PK ..." files. Result is always a
// 128-band CorrectionProfile (resampled with log interpolation).
object EqTextImport {

    data class Imported(val name: String, val preampDb: Float, val gains: List<Float>)

    fun parse(fileName: String, text: String): Imported? {
        val body = text.trim()
        if (body.isEmpty()) return null
        graphicEq(body)?.let { (preamp, gains) ->
            return Imported(displayName(fileName), preamp, gains)
        }
        // fall back to APO parametric (shared parser with the AutoEQ fetcher)
        val parsed = runCatching { EqTextParser.parse(body) }.getOrNull()
        if (parsed != null && parsed.bands.isNotEmpty()) {
            return Imported(
                displayName(fileName),
                parsed.preampDb,
                CorrectionCompiler.parametricToGains(parsed.bands).toList(),
            )
        }
        return null
    }

    private fun displayName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        return base.substringBeforeLast('.').ifBlank { base }.trim().ifBlank { "Imported EQ" }
    }

    // "GraphicEQ: 20 -9.5; 21 -9.5; ..." → (preamp 0, 128 log gains)
    private fun graphicEq(body: String): Pair<Float, List<Float>>? {
        val head = body.lineSequence().firstOrNull { it.contains("GraphicEQ", ignoreCase = true) } ?: return null
        val pairs = head.substringAfter(':').split(';').mapNotNull { tok ->
            val parts = tok.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val f = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val g = parts[1].toFloatOrNull() ?: return@mapNotNull null
            if (!f.isFinite() || !g.isFinite() || f <= 0f) return@mapNotNull null
            f to g
        }.sortedBy { it.first }
        if (pairs.size < 2 || pairs.size > 500) return null
        val freqs = correctionFreqs()
        val gains = freqs.map { f -> interpLog(pairs, f) }
        return 0f to gains
    }

    private fun interpLog(pairs: List<Pair<Float, Float>>, f: Float): Float {
        if (f <= pairs.first().first) return pairs.first().second
        if (f >= pairs.last().first) return pairs.last().second
        var lo = 0
        while (lo < pairs.size - 2 && pairs[lo + 1].first < f) lo++
        val (f0, g0) = pairs[lo]; val (f1, g1) = pairs[lo + 1]
        if (f1 <= f0) return g0
        val t = (ln((f / f0).toDouble()) / ln((f1 / f0).toDouble())).toFloat()
        return g0 + (g1 - g0) * t
    }
}
