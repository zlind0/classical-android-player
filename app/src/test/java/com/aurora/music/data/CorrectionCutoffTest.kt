package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionCutoffTest {

    private val freqs = floatArrayOf(20f, 40f, 80f, 160f, 320f)

    @Test
    fun offIsPassthrough() {
        val gains = listOf(-4f, -3f, -2f, -1f, 0f)
        assertEquals(gains, applyCorrectionCutoffs(gains, freqs))
        assertEquals(gains, applyCorrectionCutoffs(gains, freqs, 0f, 0f))
    }

    @Test
    fun degenerateInputPassthrough() {
        assertEquals(emptyList<Float>(), applyCorrectionCutoffs(emptyList(), freqs, 40f, 160f))
        val gains = listOf(1f, 2f)
        assertEquals(gains, applyCorrectionCutoffs(gains, freqs, 40f, 160f))
    }

    @Test
    fun lowcutHoldsEdgeValueInsteadOfZero() {
        // overall reduction: everything negative; cut must hold -3, not 0
        val gains = listOf(-4f, -3f, -2f, -1f, 0f)
        val cut = applyCorrectionCutoffs(gains, freqs, lowcutHz = 40f)
        assertEquals(-3f, cut[0], 0.001f)
        assertTrue("held value must not be zero", cut[0] != 0f)
        assertEquals(-3f, cut[1], 0.001f)
        assertEquals(-2f, cut[2], 0.001f)
        assertEquals(0f, cut[4], 0.001f)
    }

    @Test
    fun lowcutOffGridEdgeInterpolates() {
        val gains = listOf(-2f, -1f, 0f, 0f, 0f)
        val cut = applyCorrectionCutoffs(gains, freqs, lowcutHz = 30f)
        // t = ln(30/20)/ln(40/20) ≈ 0.585
        assertEquals(-2f + 0.585f, cut[0], 0.01f)
        assertEquals(-1f, cut[1], 0.001f)
    }

    @Test
    fun highcutHolds() {
        val gains = listOf(0f, 1f, 2f, 3f, 4f)
        val cut = applyCorrectionCutoffs(gains, freqs, highcutHz = 160f)
        assertEquals(3f, cut[4], 0.001f)
        assertEquals(3f, cut[3], 0.001f)
        assertEquals(2f, cut[2], 0.001f)
    }

    @Test
    fun bothCutoffs() {
        val gains = listOf(-4f, -3f, -2f, -1f, 0f)
        val cut = applyCorrectionCutoffs(gains, freqs, lowcutHz = 40f, highcutHz = 160f)
        assertEquals(listOf(-3f, -3f, -2f, -1f, -1f), cut)
    }

    @Test
    fun outOfRangeEdgesChangeNothing() {
        val gains = listOf(-4f, -3f, -2f, -1f, 0f)
        assertEquals(gains, applyCorrectionCutoffs(gains, freqs, lowcutHz = 10f, highcutHz = 30000f))
    }
}
