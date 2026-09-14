package com.aurora.music.playback

import com.aurora.music.data.CORRECTION_BANDS
import com.aurora.music.data.ParamBand
import com.aurora.music.data.correctionFreqs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

// Classical fork v0.5 Filter Compiler (plan §26): any correction source becomes
// 128 log-spaced gains, then zero-phase FIR taps for the correction convolver.
object CorrectionCompiler {

    const val FIR_TAPS = 255
    private const val FFT_N = 512

    /** Parametric bands (AutoEQ / squig / manual) evaluated with exact realtime math. */
    fun parametricToGains(bands: List<ParamBand>, fs: Int = 48000): FloatArray {
        val freqs = correctionFreqs()
        val out = FloatArray(CORRECTION_BANDS)
        for (b in bands) {
            if (b.gainDb == 0f) continue
            for (i in out.indices) {
                out[i] += DspCoeffBuilder.bandMagnitudeDb(b.type, b.freqHz, b.gainDb, b.q, freqs[i].toDouble(), fs).toFloat()
            }
        }
        return out
    }

    /**
     * 128 log gains → linear-phase FIR. Desired magnitude is interpolated onto a
     * 512-pt spectrum (log interp below Nyquist, hold last value above), inverse
     * FFT gives zero-phase taps; circular-shift to causal linear phase, Hann
     * window, truncate to FIR_TAPS.
     */
    fun gainsToFir(gainsDb: FloatArray, sampleRate: Int): FloatArray {
        val n = FFT_N
        val freqs = correctionFreqs()
        val re = FloatArray(n)
        val im = FloatArray(n)
        // positive spectrum 0..N/2
        for (k in 0..n / 2) {
            val f = k * sampleRate.toDouble() / n
            val g = if (f <= 0.0) gainsDb.first().toDouble()
            else gainAtLog(gainsDb, freqs, f.coerceAtMost(freqs.last().toDouble()))
            val lin = 10.0.pow(g / 20.0)
            re[k] = lin.toFloat()
        }
        // mirror to negative frequencies (zero phase => real even spectrum)
        for (k in n / 2 + 1 until n) re[k] = re[n - k]
        Fft(n).transform(re, im, true)
        // circular shift by (taps/2) to causal linear phase, Hann window, truncate
        val half = FIR_TAPS / 2
        val out = FloatArray(FIR_TAPS)
        for (i in out.indices) {
            var idx = (i - half) % n
            if (idx < 0) idx += n
            val x = re[idx]
            // Hann window over the tap range
            val w = 0.5f * (1f - cos(2f * PI.toFloat() * i / (FIR_TAPS - 1)))
            out[i] = (x * w).toFloat()
        }
        return out
    }

    private fun gainAtLog(gains: FloatArray, freqs: FloatArray, f: Double): Double {
        if (f <= freqs.first()) return gains.first().toDouble()
        if (f >= freqs.last()) return gains.last().toDouble()
        var lo = 0
        while (lo < freqs.size - 2 && freqs[lo + 1] < f) lo++
        val f0 = freqs[lo].toDouble(); val f1 = freqs[lo + 1].toDouble()
        val t = ln(f / f0) / ln(f1 / f0)
        return gains[lo] + (gains[lo + 1] - gains[lo]) * t
    }

    fun isFlat(gains: List<Float>): Boolean = gains.isEmpty() || gains.all { it == 0f }
}
