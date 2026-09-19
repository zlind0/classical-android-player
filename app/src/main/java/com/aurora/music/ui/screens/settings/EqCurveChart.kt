package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.CorrectionProfile
import com.aurora.music.data.ParamBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.ui.ios5.Ios5Colors
import kotlin.math.log10

// Classical fork v0.5 (plan §54): combined frequency-response chart.
// Draws correction + user EQ + combined curves over 20Hz..20kHz at ±15 dB.
// iOS5 paper look: white card, gray grid, blue curve. Drawing math unchanged.
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
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color.White)
            .border(1.dp, Color(0xFFD4D9E0), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.eq_curve_title), color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
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
            // grid: octave lines + 0 dB, iOS5 light gray on paper white
            val gridC = Color(0xFFCBD1D9)
            var f = 20.0
            while (f <= 20000.0) {
                drawLine(gridC, Offset(x(f), 0f), Offset(x(f), h))
                f *= 2.0
            }
            for (db in listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) {
                drawLine(
                    if (db == 0.0) Color(0xFF9AA0AB) else gridC,
                    Offset(0f, y(db)), Offset(w, y(db)),
                )
            }
            fun pathOf(sel: (CurvePoint) -> Double, color: Color, width: Float) {
                val p = Path()
                points.forEachIndexed { i, pt ->
                    val px = x(pt.freq); val py = y(sel(pt))
                    if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                }
                drawPath(p, color, style = Stroke(width))
            }
            val combined = Ios5Colors.IosBlue
            val correctionC = Ios5Colors.IosBlue.copy(alpha = 0.55f)
            val userC = Color(0xFF9AA0AB)
            if (points.any { it.correction != 0.0 }) {
                pathOf({ it.correction }, correctionC, 2f)
            }
            if (points.any { it.user != 0.0 }) {
                pathOf({ it.user }, userC, 2f)
            }
            pathOf({ it.combined }, combined, 4f)
        }
        Text(
            stringResource(R.string.eq_curve_legend),
            color = Ios5Colors.TextSecondary,
            fontSize = 12.sp,
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
