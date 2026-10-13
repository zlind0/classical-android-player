package com.aurora.music.playback

import androidx.media3.common.util.UnstableApi
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DrivingMode

/**
 * 音乐链与听书链共用的 DSP 参数装配：同一份 AudioPrefs 进来，
 * 两条链算出完全一致的 DspParams（auto headroom / driving 预设覆盖逻辑单点维护）。
 * TTS 没有单曲响度数据，driveGainDb 传 0（压缩/限幅等效果本身照常生效）。
 *
 * v0.7 模块开关：开关关掉的模块强制中性值参与混音（prefs 里的用户值原样保留，
 * 打开即恢复），不做多余动作。
 */
@UnstableApi
fun buildDspParams(
    ap: AudioPrefs,
    correctionMaxGainDb: Float,
    correctionTrimDb: Float,
    driveGainDb: Float,
): DspParams {
    val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(ap.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
    val graphic = FloatArray(layout.freqs.size) { i ->
        if (ap.dspGraphicEnabled) ap.dspGraphicBands.getOrElse(i) { 0f } else 0f
    }
    val parametric = if (ap.dspParametricEnabled) {
        ap.dspParametric.map { DspBand(it.freqHz, it.gainDb, it.q, it.type) }
    } else {
        emptyList()
    }
    // v0.5 auto headroom (plan §28): preamp covers the max positive gain of
    // user EQ + correction FIR; manual preamp trims on top.
    // 只看实际参与混音的曲线；增益模块关掉时整段中性（校正 trim 是校正模块自己的事，保留）。
    val userPeak = DspCoeffBuilder.eqPeakDb(
        DspParams(graphic = graphic, graphicFreqs = layout.freqs, graphicQ = layout.q), 48000,
    )
    val combinedPeak = maxOf(userPeak, correctionMaxGainDb)
    val autoPre = if (ap.dspGainEnabled && ap.dspAutoHeadroom) -combinedPeak.coerceAtLeast(0f) else 0f
    // v0.6 driving presets override the static compressor (plan §35)
    val compEff = when (ap.dspDriveMode) {
        DrivingMode.NATURAL -> floatArrayOf(-20f, 1.5f, 80f, 400f, 6f)
        DrivingMode.BALANCED -> floatArrayOf(-24f, 2f, 60f, 400f, 6f)
        DrivingMode.STRONG -> floatArrayOf(-28f, 3.5f, 40f, 300f, 3f)
        else -> null
    }
    val compOn = compEff != null || ap.dspCompEnabled
    val gainOn = ap.dspGainEnabled
    val spatialOn = ap.dspSpatialEnabled
    val harmOn = ap.dspHarmEnabled
    val alignOn = ap.dspAlignEnabled
    return DspParams(
        graphic = graphic,
        graphicFreqs = layout.freqs,
        graphicQ = layout.q,
        parametric = parametric,
        preampDb = correctionTrimDb + (if (gainOn) ap.dspPreampDb + autoPre else 0f),
        balance = if (gainOn) ap.dspBalance else 0f,
        width = if (spatialOn) ap.dspWidth else 1f,
        crossfeed = if (spatialOn) ap.dspCrossfeed else 0f,
        saturation = if (harmOn) ap.dspSaturation else 0f,
        delayLeftMs = if (alignOn) ap.dspDelayLeftMs else 0f,
        delayRightMs = if (alignOn) ap.dspDelayRightMs else 0f,
        trimLeftDb = if (alignOn) ap.dspTrimLeftDb else 0f,
        trimRightDb = if (alignOn) ap.dspTrimRightDb else 0f,
        limiterEnabled = ap.dspLimiterEnabled,
        limiterCeilingDb = ap.dspLimiterCeilingDb,
        compEnabled = compOn,
        compThreshDb = compEff?.get(0) ?: ap.dspCompThreshDb,
        compRatio = compEff?.get(1) ?: ap.dspCompRatio,
        compAttackMs = compEff?.get(2) ?: ap.dspCompAttackMs,
        compReleaseMs = compEff?.get(3) ?: ap.dspCompReleaseMs,
        compKneeDb = compEff?.get(4) ?: ap.dspCompKneeDb,
        // 压缩没开时 makeup 强制中性，避免自动补偿的残余增益污染直通
        compMakeupDb = if (compOn) ap.dspCompMakeupDb else 0f,
        makeupAuto = compOn && ap.dspMakeupAuto,
        driveGainDb = driveGainDb,
    )
}

fun isDrivingOn(ap: AudioPrefs): Boolean = ap.dspDriveMode != DrivingMode.OFF

/**
 * 装配后的参数是否全中性：是则调用方应令 AuroraDspProcessor.enabled = false，
 * 走 memcpy 直通，省掉每采样点 43 段 biquad + 限幅器等全套计算。
 */
fun DspParams.isNeutral(): Boolean =
    graphic.all { it == 0f } &&
        parametric.isEmpty() &&
        preampDb == 0f &&
        balance == 0f &&
        width == 1f &&
        crossfeed == 0f &&
        saturation == 0f &&
        delayLeftMs == 0f &&
        delayRightMs == 0f &&
        trimLeftDb == 0f &&
        trimRightDb == 0f &&
        !limiterEnabled &&
        !compEnabled &&
        compMakeupDb == 0f &&
        !makeupAuto &&
        driveGainDb == 0f
