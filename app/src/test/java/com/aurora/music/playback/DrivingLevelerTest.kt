package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class DrivingLevelerTest {

    private val sr = 48000

    private fun refLufsOfUnitSine(sampleRate: Int = sr): Float {
        val kwL = KWeightingFilter(sampleRate)
        val kwR = KWeightingFilter(sampleRate)
        var sumSq = 0.0
        val warm = sampleRate / 10
        for (i in 0 until sampleRate + warm) {
            val s = sin(2.0 * PI * 1000.0 * i / sampleRate)
            val kl = kwL.process(s)
            val kr = kwR.process(s)
            if (i >= warm) sumSq += kl * kl + kr * kr
        }
        return energyToLufs(sumSq / sampleRate)
    }

    private fun ampForLufs(targetLufs: Float, unitSineLufs: Float): Float =
        Math.pow(10.0, (targetLufs - unitSineLufs) / 20.0).toFloat()

    private fun feedSine(
        leveler: DrivingLeveler,
        seconds: Float,
        amp: Float,
        startSample: Int = 0,
        sampleRate: Int = sr,
        onStep100ms: ((elapsedSec: Float) -> Unit)? = null,
    ): Float {
        val total = (seconds * sampleRate).toInt()
        val step = sampleRate / 10
        var maxOut = 0f
        for (i in 0 until total) {
            if (onStep100ms != null && i % step == 0) {
                onStep100ms(i.toFloat() / sampleRate)
            }
            val s = (amp * sin(2.0 * PI * 1000.0 * (startSample + i) / sampleRate)).toFloat()
            leveler.processSample(s, s)
            val p = maxOf(abs(leveler.outL), abs(leveler.outR))
            if (p > maxOut) maxOut = p
        }
        leveler.publishMeter()
        return maxOut
    }

    @Test
    fun quietBelowFloor_waitsHoldSeconds_thenBoostsAndStopsAtFloor() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 5f,
                    boostRateDbPerSec = 2f,
                    maxBoostDb = 20f,
                ),
            )
        }
        val amp = ampForLufs(-36f, unitLufs)

        // 4.5s is shorter than 1s fill + 5s hold -> boost should still be ~0 dB
        feedSine(leveler, 4.5f, amp)
        assertTrue("Expected no boost before hold duration, got ${leveler.currentBoostDb}", leveler.currentBoostDb < 0.2f)

        // Feed another 8.5s (total 13s): ramps at 2 dB/s and stops at +8 dB (-36 + 8 = -28 LUFS floor)
        feedSine(leveler, 8.5f, amp, startSample = (4.5f * sr).toInt())
        assertEquals(8f, leveler.currentBoostDb, 0.8f)
    }

    @Test
    fun quietVeryLow_capsAtMaxBoost() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 2f,
                    boostRateDbPerSec = 4f,
                    maxBoostDb = 20f,
                ),
            )
        }
        val amp = ampForLufs(-50f, unitLufs)
        feedSine(leveler, 12f, amp)
        assertEquals(20f, leveler.currentBoostDb, 0.5f)
    }

    @Test
    fun holdZone_freezesBoostWithoutBreathing() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 2f,
                    boostRateDbPerSec = 3f,
                    maxBoostDb = 20f,
                ),
            )
        }
        // Boost -36 LUFS to +8 dB
        feedSine(leveler, 8f, ampForLufs(-36f, unitLufs))
        val boosted = leveler.currentBoostDb
        assertEquals(8f, boosted, 0.8f)

        // Now feed -28 LUFS -> effective = -20 LUFS, inside [-28, -16] deadband
        feedSine(leveler, 5f, ampForLufs(-28f, unitLufs))
        assertEquals(boosted, leveler.currentBoostDb, 0.5f)
    }

    @Test
    fun loudAboveTarget_pullsBackToZeroNeverNegative() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 2f,
                    boostRateDbPerSec = 4f,
                    maxBoostDb = 20f,
                    downRateDbPerSec = 8f,
                ),
            )
        }
        feedSine(leveler, 7f, ampForLufs(-36f, unitLufs))
        assertTrue(leveler.currentBoostDb > 6f)

        feedSine(leveler, 3f, ampForLufs(-12f, unitLufs))
        assertTrue("Expected pull-back near 0 dB, got ${leveler.currentBoostDb}", leveler.currentBoostDb in 0f..0.4f)
    }

    @Test
    fun silenceBelowGate_doesNotBoost_andDecaysAfterPause() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 2f,
                    boostRateDbPerSec = 4f,
                    maxBoostDb = 20f,
                    silenceGateDb = -55f,
                ),
            )
        }
        feedSine(leveler, 8f, ampForLufs(-65f, unitLufs))
        assertEquals(0f, leveler.currentBoostDb, 0.05f)

        feedSine(leveler, 7f, ampForLufs(-38f, unitLufs))
        assertTrue(leveler.currentBoostDb > 8f)

        feedSine(leveler, 10f, ampForLufs(-65f, unitLufs))
        assertTrue("Expected decay toward 0 dB in silence, got ${leveler.currentBoostDb}", leveler.currentBoostDb < 1.5f)
    }

    @Test
    fun microLookahead_preventsTransientClipping() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 1f,
                    boostRateDbPerSec = 6f,
                    maxBoostDb = 20f,
                    microLookaheadMs = 100f,
                    peakCeilingDb = -1f,
                ),
            )
        }
        feedSine(leveler, 6f, ampForLufs(-46f, unitLufs))
        assertTrue("Expected significant boost before transient, got ${leveler.currentBoostDb}", leveler.currentBoostDb > 14f)

        // Sudden -2 dBFS sine burst
        val burstAmp = Math.pow(10.0, -2.0 / 20.0).toFloat()
        val maxOut = feedSine(leveler, 0.4f, burstAmp)
        val outPeakDb = 20.0 * log10(maxOut.toDouble())
        assertTrue("Expected output peak <= -0.5 dBFS, got $outPeakDb dBFS", outPeakDb <= -0.5)
        assertTrue(leveler.currentBoostDb >= 0f)
    }

    @Test
    fun loudnessMapLookahead_rampsDownBeforeLoudSection() {
        val unitLufs = refLufsOfUnitSine()
        val lufs = FloatArray(20) { idx -> if (idx < 16) -40f else -12f }
        val peaks = FloatArray(20) { idx -> if (idx < 16) -30f else -2f }
        val map = TrackLoudnessMap("/fake/symphony.flac", lufs, peaks)

        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 2f,
                    boostRateDbPerSec = 3f,
                    maxBoostDb = 20f,
                    lookaheadSec = 3f,
                    lookaheadDownRateDbPerSec = 4f,
                    peakCeilingDb = -1f,
                ),
            )
            setLoudnessMap(map)
        }

        val quietAmp = ampForLufs(-40f, unitLufs)
        // t = 0 .. 12.0s (loudness jump at 16s is beyond 3s lookahead)
        feedSine(leveler, 12.0f, quietAmp) { elapsed -> leveler.setPositionSec(elapsed) }
        val boostBeforeLookahead = leveler.currentBoostDb
        assertTrue("Expected boost > 8 dB at 12s, got $boostBeforeLookahead", boostBeforeLookahead > 8f)

        // t = 12.0 .. 16.0s: audio fed is STILL quiet (-40 LUFS), but map lookahead ramps down to ~0 dB
        feedSine(leveler, 4.0f, quietAmp, startSample = 12 * sr) { elapsed -> leveler.setPositionSec(12.0f + elapsed) }
        assertTrue("Expected lookahead pre-ramp to <= 0.5 dB by 16s, got ${leveler.currentBoostDb}", leveler.currentBoostDb <= 0.5f)
    }

    @Test
    fun liveLookahead_rampsDownAcrossFullBuffer() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 2f,
                    boostRateDbPerSec = 4f,
                    maxBoostDb = 20f,
                    lookaheadSec = 3f,
                    lookaheadDownRateDbPerSec = 4f,
                    peakCeilingDb = -1f,
                ),
            )
        }
        val quietAmp = ampForLufs(-42f, unitLufs)
        feedSine(leveler, 7f, quietAmp)
        assertTrue("Expected significant boost before live lookahead, got ${leveler.currentBoostDb}", leveler.currentBoostDb > 12f)

        // Simulate a 1.5 s AirPlay buffer (15 x 100 ms buckets) where loud audio sits at the tail
        // and shifts closer every 100 ms as playback advances.
        for (step in 0 until 14) {
            val count = 15
            val lufs = FloatArray(count) { idx -> if (idx >= 14 - step) -12f else -42f }
            val peaks = FloatArray(count) { idx -> if (idx >= 14 - step) -2f else -30f }
            leveler.setLiveLookahead(LiveLookahead(lufs, peaks, count, 0.1f, 0f))
            assertTrue(leveler.hasActiveMap)
            feedSine(leveler, 0.1f, quietAmp, startSample = (7f * sr).toInt() + step * (sr / 10))
        }
        assertTrue(
            "Expected live lookahead to pre-ramp boost <= 0.5 dB before loud tail arrives, got ${leveler.currentBoostDb}",
            leveler.currentBoostDb <= 0.5f,
        )
    }

    @Test
    fun liveLookahead_disabledWhenLookaheadSecZero() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(
                DrivingParams(
                    enabled = true,
                    targetLoudnessDb = -16f,
                    floorOffsetDb = -12f,
                    holdSeconds = 2f,
                    boostRateDbPerSec = 4f,
                    maxBoostDb = 20f,
                    lookaheadSec = 0f,
                ),
            )
        }
        val quietAmp = ampForLufs(-40f, unitLufs)
        feedSine(leveler, 6f, quietAmp)
        val before = leveler.currentBoostDb
        assertTrue(before > 8f)

        val loudLufs = FloatArray(8) { -12f }
        val loudPeak = FloatArray(8) { -2f }
        leveler.setLiveLookahead(LiveLookahead(loudLufs, loudPeak, 8, 0.1f, 0f))
        feedSine(leveler, 0.5f, quietAmp, startSample = 6 * sr)
        assertEquals(before, leveler.currentBoostDb, 2.5f)
    }

    @Test
    fun reset_clearsBoostAndDelay() {
        val unitLufs = refLufsOfUnitSine()
        val leveler = DrivingLeveler().apply {
            configure(sr)
            update(DrivingParams(enabled = true, holdSeconds = 1f, boostRateDbPerSec = 5f))
        }
        feedSine(leveler, 5f, ampForLufs(-40f, unitLufs))
        assertTrue(leveler.currentBoostDb > 5f)

        leveler.reset()
        assertEquals(0f, leveler.currentBoostDb, 0f)
        leveler.processSample(0f, 0f)
        assertEquals(0f, leveler.outL, 0f)
        assertEquals(0f, leveler.outR, 0f)
    }
}
