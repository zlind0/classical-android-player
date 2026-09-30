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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.aurora.music.R
import com.aurora.music.data.CorrectionProfile
import com.aurora.music.data.ParamBand
import com.aurora.music.data.applyCorrectionCutoffs
import com.aurora.music.data.correctionFreqs
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
    lowcutHz: Float = 0f,
    highcutHz: Float = 0f,
) {
    val points = remember(correction, graphicFreqs, graphicQ, graphicGains, parametric, preampDb, lowcutHz, highcutHz) {
        buildCurvePoints(correction, graphicFreqs, graphicQ, graphicGains, parametric, preampDb, lowcutHz, highcutHz)
    }
    val textMeasurer = rememberTextMeasurer()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color.White)
            .border(1.dp, Color(0xFFD4D9E0), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.eq_curve_title), color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Canvas(Modifier.fillMaxWidth().height(170.dp).padding(top = 4.dp)) {
            val labelC = Color(0xFF9AA0AB)
            val labelStyle = TextStyle(fontSize = 10.sp, color = labelC)
            val leftPad = 30.dp.toPx()
            val bottomPad = 16.dp.toPx()
            val pw = size.width - leftPad
            val ph = size.height - bottomPad
            fun x(f: Double): Float {
                val t = (log10(f) - log10(20.0)) / (log10(20000.0) - log10(20.0))
                return (leftPad + t * pw).toFloat()
            }
            fun y(db: Double): Float {
                val t = (15.0 - db.coerceIn(-15.0, 15.0)) / 30.0
                return (t * ph).toFloat()
            }
            // grid: octave lines + 0 dB, iOS5 light gray on paper white
            val gridC = Color(0xFFCBD1D9)
            var f = 20.0
            while (f <= 20000.0) {
                drawLine(gridC, Offset(x(f), 0f), Offset(x(f), ph))
                f *= 2.0
            }
            for (db in listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) {
                drawLine(
                    if (db == 0.0) Color(0xFF9AA0AB) else gridC,
                    Offset(leftPad, y(db)), Offset(size.width, y(db)),
                )
                val tag = textMeasurer.measure(if (db > 0) "+${db.toInt()}" else "${db.toInt()}", labelStyle)
                drawText(tag, color = labelC, topLeft = Offset(leftPad - 4.dp.toPx() - tag.size.width, y(db) - tag.size.height / 2))
            }
            for ((lf, lt) in X_LABELS) {
                val tag = textMeasurer.measure(lt, labelStyle)
                drawText(tag, color = labelC, topLeft = Offset(x(lf) - tag.size.width / 2, ph + 3.dp.toPx()))
            }
            // cutoff markers: the curves below already hold the edge value outside the window
            val markC = Color(0xFFD63A3A).copy(alpha = 0.65f)
            if (lowcutHz > 0f) {
                val mx = x(lowcutHz.toDouble())
                drawLine(markC, Offset(mx, 0f), Offset(mx, ph))
            }
            if (highcutHz > 0f) {
                val mx = x(highcutHz.toDouble())
                drawLine(markC, Offset(mx, 0f), Offset(mx, ph))
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

private val X_LABELS = listOf(
    20.0 to "20", 50.0 to "50", 100.0 to "100", 200.0 to "200", 500.0 to "500",
    1000.0 to "1k", 2000.0 to "2k", 5000.0 to "5k", 10000.0 to "10k", 20000.0 to "20k",
)

private fun buildCurvePoints(
    correction: CorrectionProfile?,
    graphicFreqs: FloatArray,
    graphicQ: Float,
    graphicGains: List<Float>,
    parametric: List<ParamBand>,
    preampDb: Float,
    lowcutHz: Float,
    highcutHz: Float,
): List<CurvePoint> {
    val n = 120
    val out = ArrayList<CurvePoint>(n)
    val corr = correction?.takeIf { it.enabled }
    val cutGains = corr?.let { applyCorrectionCutoffs(it.scaledGains(), correctionFreqs(), lowcutHz, highcutHz) }
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
        val c = if (corr != null && cutGains != null) corr.gainAtCut(f.toFloat(), cutGains).toDouble() else 0.0
        out.add(CurvePoint(f, c, user, (c + user + preampDb).coerceIn(-15.0, 15.0)))
    }
    return out
}
