package com.aurora.music.data.cast.receiver.airplay

import com.aurora.music.playback.KWeightingFilter
import com.aurora.music.playback.energyToLufs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AirPlayTimelineBufferLookaheadTest {

    private val sr = 48000
    private val ch = 2

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

    private fun writeSineChunk(
        buf: AirPlayTimelineBuffer,
        frames: Int,
        amp: Float,
        ptsStartFrames: Long,
        sampleOffset: Long,
    ) {
        val pcm = ShortArray(frames * ch)
        for (f in 0 until frames) {
            val v = (amp * sin(2.0 * PI * 1000.0 * (sampleOffset + f) / sr) * 32767.0)
                .toInt()
                .coerceIn(-32768, 32767)
                .toShort()
            pcm[f * 2] = v
            pcm[f * 2 + 1] = v
        }
        val ptsNs = ptsStartFrames * 1_000_000_000L / sr
        buf.write(pcm, pcm.size, ptsNs)
    }

    @Test
    fun peekLookahead_coversFullBufferFor800msAnd1500ms_andClearsOnFlush() {
        val unitLufs = refLufsOfUnitSine()
        val quietAmp = Math.pow(10.0, (-40f - unitLufs) / 20.0).toFloat()
        val loudAmp = Math.pow(10.0, (-12f - unitLufs) / 20.0).toFloat()

        val buf = AirPlayTimelineBuffer(sr, ch, staticCushionMs = 800)
        val chunkFrames = sr / 10 // 100 ms
        var framesWritten = 0L

        // Write 500 ms (< 800 ms cushion -> still priming)
        repeat(5) {
            writeSineChunk(buf, chunkFrames, quietAmp, framesWritten, framesWritten)
            framesWritten += chunkFrames
        }
        val out = ShortArray((sr / 100) * ch) // 10 ms read
        buf.read(out, sr / 100)
        assertNull("Should return null while priming", buf.peekLookahead())

        // Write another 400 ms quiet + 200 ms loud (total 1100 ms buffered > 800 ms cushion)
        repeat(4) {
            writeSineChunk(buf, chunkFrames, quietAmp, framesWritten, framesWritten)
            framesWritten += chunkFrames
        }
        repeat(2) {
            writeSineChunk(buf, chunkFrames, loudAmp, framesWritten, framesWritten)
            framesWritten += chunkFrames
        }

        // Unblock priming
        assertTrue(buf.read(out, sr / 100))
        val snap800 = buf.peekLookahead()
        assertNotNull(snap800)
        snap800!!
        assertTrue("Expected >= 10 buckets covering ~1.1s buffer, got ${snap800.count}", snap800.count >= 10)
        assertTrue("First bucket should be quiet (~ -40 LUFS), got ${snap800.lufs[0]}", snap800.lufs[0] < -35f)
        assertTrue("Tail bucket should be loud (~ -12 LUFS), got ${snap800.lufs[snap800.count - 1]}", snap800.lufs[snap800.count - 1] > -15f)
        assertEquals(0.1f, snap800.bucketSec, 0.001f)

        // Expand cushion to 1500 ms and verify full 1.5s+ horizon
        buf.flushAndReprime()
        assertNull(buf.peekLookahead())
        buf.updateCushionMs(1500)
        repeat(15) {
            writeSineChunk(buf, chunkFrames, quietAmp, framesWritten, framesWritten)
            framesWritten += chunkFrames
        }
        repeat(2) {
            writeSineChunk(buf, chunkFrames, loudAmp, framesWritten, framesWritten)
            framesWritten += chunkFrames
        }
        assertTrue(buf.read(out, sr / 100))
        val snap1500 = buf.peekLookahead()
        assertNotNull(snap1500)
        snap1500!!
        assertTrue("Expected >= 16 buckets covering ~1.7s buffer, got ${snap1500.count}", snap1500.count >= 16)
        assertTrue(snap1500.lufs[0] < -35f)
        assertTrue(snap1500.lufs[snap1500.count - 1] > -15f)
    }
}
