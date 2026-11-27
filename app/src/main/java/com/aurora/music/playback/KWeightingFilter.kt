package com.aurora.music.playback

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.tan

const val BS1770_OFFSET_DB = -0.691

fun energyToLufs(energy: Double): Float =
    if (energy <= 1e-12) -100f else (BS1770_OFFSET_DB + 10.0 * log10(energy)).toFloat()

/**
 * BS.1770 K-weighting pre-filter (high-shelf + high-pass) derived for the
 * actual sample rate rather than just the 48 kHz spec table.
 */
class KWeightingFilter(sampleRate: Int) {
    private val fs = sampleRate.toDouble()
    private val b1 = shelfNum()
    private val a1 = shelfDen()
    private val b2 = doubleArrayOf(1.0, -2.0, 1.0)
    private val a2 = highpassDen()

    private var s1x1 = 0.0
    private var s1x2 = 0.0
    private var s1y1 = 0.0
    private var s1y2 = 0.0
    private var s2x1 = 0.0
    private var s2x2 = 0.0
    private var s2y1 = 0.0
    private var s2y2 = 0.0

    private fun shelfK() = tan(PI * 1681.974450955533 / fs)

    private fun shelfA0(): Double {
        val k = shelfK()
        return 1.0 + k / 0.7071752369554196 + k * k
    }

    private fun shelfNum(): DoubleArray {
        val k = shelfK()
        val q = 0.7071752369554196
        val a0 = shelfA0()
        val vh = Math.pow(10.0, 3.999843853973347 / 20.0)
        val vb = Math.pow(vh, 0.4996667741545416)
        return doubleArrayOf(
            (vh + vb * k / q + k * k) / a0,
            2.0 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
        )
    }

    private fun shelfDen(): DoubleArray {
        val k = shelfK()
        val q = 0.7071752369554196
        val a0 = shelfA0()
        return doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private fun highpassDen(): DoubleArray {
        val q = 0.5003270373238773
        val k = tan(PI * 38.13547087602444 / fs)
        val denom = 1.0 + k / q + k * k
        return doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / denom, (1.0 - k / q + k * k) / denom)
    }

    fun process(input: Double): Double {
        val s1 = b1[0] * input + b1[1] * s1x1 + b1[2] * s1x2 - a1[1] * s1y1 - a1[2] * s1y2
        s1x2 = s1x1; s1x1 = input; s1y2 = s1y1; s1y1 = s1
        val s2 = b2[0] * s1 + b2[1] * s2x1 + b2[2] * s2x2 - a2[1] * s2y1 - a2[2] * s2y2
        s2x2 = s2x1; s2x1 = s1; s2y2 = s2y1; s2y1 = s2
        return s2
    }

    fun reset() {
        s1x1 = 0.0; s1x2 = 0.0; s1y1 = 0.0; s1y2 = 0.0
        s2x1 = 0.0; s2x2 = 0.0; s2y1 = 0.0; s2y2 = 0.0
    }
}
