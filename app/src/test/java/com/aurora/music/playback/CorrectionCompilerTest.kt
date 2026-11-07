package com.aurora.music.playback

import com.aurora.music.data.CORRECTION_BANDS
import com.aurora.music.data.correctionFreqs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * The correction convolver must actually reproduce the imported AutoEq curve.
 * A short kernel (the old 255 taps) rolled off the 20-100 Hz region by several
 * dB, which is exactly the "correction is not strong enough" symptom when
 * comparing against Wavelet's 4096-tap convolver.
 */
class CorrectionCompilerTest {

    private val fs = 48000
    private val freqs = correctionFreqs()

    /** Bass shelf +6 dB below 100 Hz, flat above, like a typical AutoEq curve. */
    private fun bassShelf(): FloatArray = FloatArray(CORRECTION_BANDS) { i ->
        val f = freqs[i]
        (6.0 / (1.0 + (f / 100.0).pow(4))).toFloat()
    }

    private fun desiredAt(gains: FloatArray, f: Double): Double {
        if (f <= freqs.first()) return gains.first().toDouble()
        if (f >= freqs.last()) return gains.last().toDouble()
        var lo = 0
        while (lo < freqs.size - 2 && freqs[lo + 1] < f) lo++
        val f0 = freqs[lo].toDouble()
        val f1 = freqs[lo + 1].toDouble()
        val t = ln(f / f0) / ln(f1 / f0)
        return gains[lo] + (gains[lo + 1] - gains[lo]) * t
    }

    private fun responseDb(taps: FloatArray, f: Double): Double {
        val w = 2.0 * PI * f / fs
        var re = 0.0
        var im = 0.0
        for (n in taps.indices) {
            re += taps[n] * cos(w * n)
            im -= taps[n] * sin(w * n)
        }
        return 20.0 * log10(kotlin.math.sqrt(re * re + im * im) + 1e-12)
    }

    @Test
    fun firLengthIsLongEnoughForBass() {
        assertEquals(4095, CorrectionCompiler.FIR_TAPS)
    }

    @Test
    fun bassShelfIsReproducedWithinHalfDb() {
        val gains = bassShelf()
        val taps = CorrectionCompiler.gainsToFir(gains, fs)
        assertEquals(CorrectionCompiler.FIR_TAPS, taps.size)
        for (f in listOf(25.0, 40.0, 60.0, 80.0, 100.0, 150.0, 1000.0, 10000.0)) {
            val want = desiredAt(gains, f)
            val got = responseDb(taps, f)
            assertTrue(
                "at ${f.toInt()} Hz want $want dB got $got dB",
                kotlin.math.abs(got - want) < 0.5,
            )
        }
    }

    @Test
    fun deepBassCutIsNotSoftened() {
        // AutoEq GraphicEQ curves are all-cut; a -8 dB shelf must stay -8 dB.
        val gains = FloatArray(CORRECTION_BANDS) { i ->
            val f = freqs[i]
            (-8.0 / (1.0 + (f / 80.0).pow(4))).toFloat()
        }
        val taps = CorrectionCompiler.gainsToFir(gains, fs)
        val got = responseDb(taps, 30.0)
        val want = desiredAt(gains, 30.0)
        assertTrue("30 Hz want $want dB got $got dB", kotlin.math.abs(got - want) < 0.5)
    }
}
