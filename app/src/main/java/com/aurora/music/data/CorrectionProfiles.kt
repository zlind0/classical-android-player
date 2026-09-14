package com.aurora.music.data

// Classical fork v0.5 (plan §25-26, §30): correction + audio profiles.
//
// CorrectionProfile is the 128-band device-correction layer. Any source
// (AutoEQ parametric, squig.link generation, custom measurement) compiles to
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
) {
    val maxGain: Float get() = gains.maxOrNull() ?: 0f
    fun gainAt(freqHz: Float, freqs: FloatArray = correctionFreqs()): Float {
        if (gains.isEmpty()) return 0f
        if (freqHz <= freqs.first()) return gains.first()
        if (freqHz >= freqs.last()) return gains.last()
        var lo = 0
        while (lo < freqs.size - 2 && freqs[lo + 1] < freqHz) lo++
        val f0 = freqs[lo]; val f1 = freqs[lo + 1]
        val t = (Math.log((freqHz / f0).toDouble()) / Math.log((f1 / f0).toDouble())).toFloat()
        return gains[lo] + (gains[lo + 1] - gains[lo]) * t
    }
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
