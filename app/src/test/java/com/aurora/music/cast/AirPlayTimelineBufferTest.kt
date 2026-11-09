package com.aurora.music.cast

import com.aurora.music.data.cast.receiver.airplay.AirPlayConfig
import com.aurora.music.data.cast.receiver.airplay.AirPlayTimelineBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTimelineBufferTest {

    private val sampleRate = 44100
    private val channels = 2
    private val frameSamples = sampleRate * 8 / 1000 // ~8ms ALAC frame

    @Test
    fun fixedCushionWaitsForConfiguredBufferBeforePlaying() {
        val buffer = AirPlayTimelineBuffer(
            sampleRate = sampleRate,
            channels = channels,
            staticCushionMs = 800,
        )
        val out = ShortArray(sampleRate / 100 * channels)

        assertFalse("no data yet must return silence", buffer.read(out, sampleRate / 100))

        var pts = 0L
        val chunk = ShortArray(frameSamples * channels) { 100 }
        // Feed less than the 800ms cushion.
        repeat(50) {
            buffer.write(chunk, chunk.size, pts)
            pts += frameSamples * 1_000_000_000L / sampleRate
        }
        assertFalse("priming must not finish below the cushion", buffer.read(out, sampleRate / 100))

        // Cross the 800ms threshold.
        repeat(60) {
            buffer.write(chunk, chunk.size, pts)
            pts += frameSamples * 1_000_000_000L / sampleRate
        }
        assertTrue("cushion reached, read must be satisfied", buffer.read(out, sampleRate / 100))
        assertEquals(800, buffer.debugInfo().tunedCushionMs)
    }

    @Test
    fun shortPtsGapUsesPlcInsteadOfSilence() {
        val buffer = AirPlayTimelineBuffer(
            sampleRate = sampleRate,
            channels = channels,
            staticCushionMs = 800,
            plcEnabled = true,
            plcMaxMs = 30,
        )
        val chunk = ShortArray(frameSamples * channels) { 1000 }
        var pts = 0L
        repeat(100) {
            buffer.write(chunk, chunk.size, pts)
            pts += frameSamples * 1_000_000_000L / sampleRate
        }
        // Skip two frames: 16ms > SLACK but below plcMaxMs.
        pts += 2 * frameSamples * 1_000_000_000L / sampleRate
        buffer.write(chunk, chunk.size, pts)

        val debug = buffer.debugInfo()
        assertEquals("short gap should be concealed", 1, debug.conceals)
        assertEquals("short gap must not be written as silence", 0, debug.silences)
    }

    @Test
    fun configClampsAndAutoWaitFollowsBuffer() {
        val config = AirPlayConfig(bufferMs = 2750, resendWaitMs = 500)
            .sanitized()
        assertEquals(AirPlayConfig.MAX_BUFFER_MS, config.bufferMs)
        assertEquals(
            AirPlayConfig.AUTO_WAIT_CAP_MS,
            config.copy(bufferMs = 800).effectiveResendWaitMs,
        )
        assertEquals(45, config.copy(bufferMs = 65, resendAuto = true).effectiveResendWaitMs)
    }
}
