package com.aurora.music.data

// Classical fork v0.5 (plan §25-26, §30): correction + audio profiles.
//
// CorrectionProfile is the 128-band device-correction layer. Any source
// (bundled AutoEq presets, custom measurement) compiles to
// the same 128 log-spaced gains, which the Filter Compiler turns into FIR
// coefficients for the correction convolver (plan §26).
object CorrectionSource {
    const val FLAT = 0
    const val AUTOEQ = 1
    const val SQUIG = 2
    const val CUSTOM = 3
    fun label(v: Int) = when (v) {
        AUTOEQ -> "AutoEQ"
        SQUIG -> "squig.link"
        CUSTOM -> "Custom"
        else -> "Flat"
    }
}

const val CORRECTION_BANDS = 128
const val CORRECTION_FMIN = 20f
const val CORRECTION_FMAX = 20000f

fun correctionFreqs(): FloatArray = FloatArray(CORRECTION_BANDS) { i ->
    (CORRECTION_FMIN * Math.pow((CORRECTION_FMAX / CORRECTION_FMIN).toDouble(), i / (CORRECTION_BANDS - 1.0))).toFloat()
}

data class CorrectionProfile(
    val id: String = "flat",
    val name: String = "Flat",
    val deviceName: String = "",
    val source: Int = CorrectionSource.FLAT,
    // manual trim on top of auto headroom, dB
    val preampDb: Float = 0f,
    // 128 log-spaced gains 20Hz..20kHz, dB
    val gains: List<Float> = emptyList(),
    val enabled: Boolean = true,
    // v0.5.1: application strength 0..120%. Applied in the LOG domain
    // (dB gains are multiplied), which keeps the curve shape perceptually
    // consistent; scaling linear amplitude instead would warp it.
    val strengthPct: Float = 100f,
) {
    val maxGain: Float get() = scaledGains().maxOrNull() ?: 0f
    fun scaledGains(): List<Float> {
        val s = (strengthPct / 100f).coerceIn(0f, 1.2f)
        if (s == 1f) return gains
        return gains.map { it * s }
    }
    fun gainAt(freqHz: Float, freqs: FloatArray = correctionFreqs()): Float =
        interpGains(scaledGains(), freqs, freqHz)
    /** Same interpolation over externally shaped gains (e.g. cutoff-applied). */
    fun gainAtCut(freqHz: Float, cutGains: List<Float>, freqs: FloatArray = correctionFreqs()): Float =
        interpGains(cutGains, freqs, freqHz)
}

/**
 * Frequency cutoff for a correction curve (Hz, 0 = off). Outside the
 * [lowcutHz, highcutHz] window the curve holds the edge value instead of
 * dropping to zero: a correction usually carries an overall reduction, so
 * zeroing would tear the curve. Values exactly on the grid are held verbatim;
 * off-grid edges are log-interpolated.
 */
fun applyCorrectionCutoffs(
    gains: List<Float>,
    freqs: FloatArray = correctionFreqs(),
    lowcutHz: Float = 0f,
    highcutHz: Float = 0f,
): List<Float> {
    if (gains.isEmpty() || gains.size != freqs.size) return gains
    val lo = if (lowcutHz > 0f) lowcutHz else freqs.first()
    val hi = if (highcutHz > 0f) highcutHz else freqs.last()
    if (lo <= freqs.first() && hi >= freqs.last()) return gains
    val loVal = interpGains(gains, freqs, lo.coerceIn(freqs.first(), freqs.last()))
    val hiVal = interpGains(gains, freqs, hi.coerceIn(freqs.first(), freqs.last()))
    return gains.mapIndexed { i, g ->
        when {
            freqs[i] < lo -> loVal
            freqs[i] > hi -> hiVal
            else -> g
        }
    }
}

private fun interpGains(gains: List<Float>, freqs: FloatArray, freqHz: Float): Float {
    val g = gains
    if (g.isEmpty()) return 0f
    if (freqHz <= freqs.first()) return g.first()
    if (freqHz >= freqs.last()) return g.last()
    var lo = 0
    while (lo < freqs.size - 2 && freqs[lo + 1] < freqHz) lo++
    val f0 = freqs[lo]; val f1 = freqs[lo + 1]
    val t = (Math.log((freqHz / f0).toDouble()) / Math.log((f1 / f0).toDouble())).toFloat()
    return g[lo] + (g[lo + 1] - g[lo]) * t
}

// Classical fork v0.5 (plan §30): one named snapshot of the whole DSP chain.
data class AudioProfile(
    val id: String = "",
    val name: String = "",
    val correctionId: String = "flat",
    val graphic: List<Float> = emptyList(),
    val graphicLayout: Int = 3,
    val parametric: List<ParamBand> = emptyList(),
    val convEnabled: Boolean = false,
    val convIrPath: String = "",
    val convIrName: String = "",
    val convMakeupDb: Float = 0f,
    val compEnabled: Boolean = false,
    val compThreshDb: Float = -18f,
    val compRatio: Float = 2f,
    // v0.6 driving + compressor detail
    val driveMode: Int = DrivingMode.OFF,
    val driveTargetDb: Float = -16f,
    val compAttackMs: Float = 80f,
    val compReleaseMs: Float = 400f,
    val compKneeDb: Float = 6f,
    val compMakeupDb: Float = 0f,
    val makeupAuto: Boolean = true,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -0.3f,
    val replayGain: Int = 0,
)

fun AudioProfile.describe(): String = buildList {
    if (correctionId.isNotBlank() && correctionId != "flat") add("correction")
    if (graphic.any { it != 0f }) add("16-band")
    if (parametric.isNotEmpty()) add("${parametric.size} param")
    if (convEnabled) add("IR")
    if (driveMode != DrivingMode.OFF) add(DrivingMode.label(driveMode))
    else if (compEnabled) add("comp")
    if (limiterEnabled) add("limit")
    if (replayGain > 0) add(if (replayGain == 1) "RG track" else "RG album")
}.ifEmpty { listOf("flat") }.joinToString(" · ")
