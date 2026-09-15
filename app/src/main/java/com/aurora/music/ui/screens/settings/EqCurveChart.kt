package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.aurora.music.R
import com.aurora.music.data.CorrectionProfile
import com.aurora.music.data.ParamBand
import com.aurora.music.playback.DspCoeffBuilder
import kotlin.math.log10

// Classical fork v0.5 (plan §54): combined frequency-response chart.
// Draws correction + user EQ + combined curves over 20Hz..20kHz at ±15 dB.
@Composable
fun EqCurveChart(
    correction: CorrectionProfile?,
    graphicFreqs: FloatArray,
    graphicQ: Float,
    graphicGains: List<Float>,
    parametric: List<ParamBand>,
    preampDb: Float,
    modifier: Modifier = Modifier,
) {
    val points = remember(correction, graphicFreqs, graphicQ, graphicGains, parametric, preampDb) {
        buildCurvePoints(correction, graphicFreqs, graphicQ, graphicGains, parametric, preampDb)
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.eq_curve_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 4.dp)) {
            val w = size.width
            val h = size.height
            fun x(f: Double): Float {
                val t = (log10(f) - log10(20.0)) / (log10(20000.0) - log10(20.0))
                return (t * w).toFloat()
            }
            fun y(db: Double): Float {
                val t = (15.0 - db.coerceIn(-15.0, 15.0)) / 30.0
                return (t * h).toFloat()
            }
            // grid: octave lines + 0 dB
            val gridC = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.25f)
            var f = 20.0
            while (f <= 20000.0) {
                drawLine(gridC, Offset(x(f), 0f), Offset(x(f), h))
                f *= 2.0
            }
            for (db in listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) {
                drawLine(
                    if (db == 0.0) androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.6f) else gridC,
                    Offset(0f, y(db)), Offset(w, y(db)),
                )
            }
            fun pathOf(sel: (CurvePoint) -> Double, color: androidx.compose.ui.graphics.Color, width: Float) {
                val p = Path()
                points.forEachIndexed { i, pt ->
                    val px = x(pt.freq); val py = y(sel(pt))
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                drawPath(p, color, style = Stroke(width))
            }
            val primary = androidx.compose.ui.graphics.Color(0xFFFF2E7E)
            val secondary = androidx.compose.ui.graphics.Color(0xFF00E5FF)
            if (points.any { it.correction != 0.0 }) {
                pathOf({ it.correction }, secondary.copy(alpha = 0.8f), 2f)
            }
            if (points.any { it.user != 0.0 }) {
                pathOf({ it.user }, androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.8f), 2f)
            }
            pathOf({ it.combined }, primary, 4f)
        }
        Text(
            stringResource(R.string.eq_curve_legend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class CurvePoint(val freq: Double, val correction: Double, val user: Double, val combined: Double)

private fun buildCurvePoints(
    correction: CorrectionProfile?,
    graphicFreqs: FloatArray,
    graphicQ: Float,
    graphicGains: List<Float>,
    parametric: List<ParamBand>,
    preampDb: Float,
): List<CurvePoint> {
    val n = 120
    val out = ArrayList<CurvePoint>(n)
    val corr = correction?.takeIf { it.enabled }
    for (i in 0 until n) {
        val f = 20.0 * Math.pow(1000.0, i / (n - 1.0))
        var user = 0.0
        for (g in graphicFreqs.indices) {
            val gain = graphicGains.getOrElse(g) { 0f }
            if (gain != 0f) user += DspCoeffBuilder.bandMagnitudeDb(0, graphicFreqs[g], gain, graphicQ, f)
        }
        for (b in parametric) {
            if (b.gainDb != 0f) user += DspCoeffBuilder.bandMagnitudeDb(b.type, b.freqHz, b.gainDb, b.q, f)
        }
        val c = corr?.gainAt(f.toFloat())?.toDouble() ?: 0.0
        out.add(CurvePoint(f, c, user, (c + user + preampDb).coerceIn(-15.0, 15.0)))
    }
    return out
}
