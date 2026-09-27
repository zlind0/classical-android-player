package com.aurora.music.playback

import androidx.media3.common.util.UnstableApi
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DrivingMode

/**
 * 音乐链与听书链共用的 DSP 参数装配：同一份 AudioPrefs 进来，
 * 两条链算出完全一致的 DspParams（auto headroom / driving 预设覆盖逻辑单点维护）。
 * TTS 没有单曲响度数据，driveGainDb 传 0（压缩/限幅等效果本身照常生效）。
 */
@UnstableApi
fun buildDspParams(
    ap: AudioPrefs,
    correctionMaxGainDb: Float,
    correctionTrimDb: Float,
    driveGainDb: Float,
): DspParams {
    val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(ap.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
    val graphic = FloatArray(layout.freqs.size) { ap.dspGraphicBands.getOrElse(it) { 0f } }
    // v0.5 auto headroom (plan §28): preamp covers the max positive gain of
    // user EQ + correction FIR; manual preamp trims on top.
    val userPeak = DspCoeffBuilder.eqPeakDb(
        DspParams(graphic = graphic, graphicFreqs = layout.freqs, graphicQ = layout.q), 48000,
    )
    val combinedPeak = maxOf(userPeak, correctionMaxGainDb)
    val autoPre = if (ap.dspAutoHeadroom) -combinedPeak.coerceAtLeast(0f) else 0f
    // v0.6 driving presets override the static compressor (plan §35)
    val compEff = when (ap.dspDriveMode) {
        DrivingMode.NATURAL -> floatArrayOf(-20f, 1.5f, 80f, 400f, 6f)
        DrivingMode.BALANCED -> floatArrayOf(-24f, 2f, 60f, 400f, 6f)
        DrivingMode.STRONG -> floatArrayOf(-28f, 3.5f, 40f, 300f, 3f)
        else -> null
    }
    return DspParams(
        graphic = graphic,
        graphicFreqs = layout.freqs,
        graphicQ = layout.q,
        parametric = ap.dspParametric.map { DspBand(it.freqHz, it.gainDb, it.q, it.type) },
        preampDb = ap.dspPreampDb + correctionTrimDb + autoPre,
        balance = ap.dspBalance,
        width = ap.dspWidth,
        crossfeed = ap.dspCrossfeed,
        saturation = ap.dspSaturation,
        delayLeftMs = ap.dspDelayLeftMs,
        delayRightMs = ap.dspDelayRightMs,
        trimLeftDb = ap.dspTrimLeftDb,
        trimRightDb = ap.dspTrimRightDb,
        limiterEnabled = ap.dspLimiterEnabled,
        limiterCeilingDb = ap.dspLimiterCeilingDb,
        compEnabled = compEff != null || ap.dspCompEnabled,
        compThreshDb = compEff?.get(0) ?: ap.dspCompThreshDb,
        compRatio = compEff?.get(1) ?: ap.dspCompRatio,
        compAttackMs = compEff?.get(2) ?: ap.dspCompAttackMs,
        compReleaseMs = compEff?.get(3) ?: ap.dspCompReleaseMs,
        compKneeDb = compEff?.get(4) ?: ap.dspCompKneeDb,
        compMakeupDb = ap.dspCompMakeupDb,
        makeupAuto = ap.dspMakeupAuto,
        driveGainDb = driveGainDb,
    )
}

fun isDrivingOn(ap: AudioPrefs): Boolean = ap.dspDriveMode != DrivingMode.OFF
