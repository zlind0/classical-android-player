package com.aurora.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DrivingMode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 drains the processor chain and then flushes it at every gapless media item
 * transition. That flush must not reset the driving leveler's micro-lookahead delay
 * ring, otherwise the last ~100 ms of a track is dropped and the next track starts
 * with 100 ms of silence. A seek flush (no preceding end-of-stream) must still reset.
 */
class DspGaplessFlushTest {

    private val sr = 48000
    private val framesPerChunk = sr / 100 // 10 ms

    private fun newProcessor(): AuroraDspProcessor {
        val proc = AuroraDspProcessor()
        proc.enabled = true
        proc.update(buildDspParams(AudioPrefs(dspDriveMode = DrivingMode.NATURAL), 0f, 0f))
        proc.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        proc.flush()
        return proc
    }

    private fun feed(proc: AuroraDspProcessor, frames: Int, value: Short): ShortArray {
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) {
            input.putShort(value)
            input.putShort(value)
        }
        input.flip()
        proc.queueInput(input)
        val out = proc.getOutput()
        val samples = ShortArray(out.remaining() / 2)
        var i = 0
        while (out.hasRemaining()) samples[i++] = out.short
        return samples
    }

    @Test
    fun gaplessFlush_preservesLevelerDelayTail() {
        val proc = newProcessor()

        // The leveler's 100 ms micro-lookahead means the first chunk is delayed silence.
        val first = feed(proc, framesPerChunk, 8000)
        assertTrue("first chunk must be delayed silence", first.all { it == 0.toShort() })

        // Feed past the 100 ms delay so the ring holds real audio.
        repeat(49) { feed(proc, framesPerChunk, 8000) }

        // Gapless track transition: Media3 queues EOS, drains, then flushes.
        proc.queueEndOfStream()
        proc.flush()

        val next = feed(proc, framesPerChunk, 8000)
        assertTrue(
            "gapless flush must not zero the delay ring (got a silent chunk)",
            next.any { it != 0.toShort() },
        )
    }

    @Test
    fun plainFlush_stillResetsDelayRing() {
        val proc = newProcessor()
        repeat(50) { feed(proc, framesPerChunk, 8000) }

        // Seek / reset flush: no preceding end-of-stream.
        proc.flush()

        val next = feed(proc, framesPerChunk, 8000)
        assertTrue("plain flush must reset the delay ring", next.all { it == 0.toShort() })
    }
}
