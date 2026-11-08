package com.aurora.music.data.cast.receiver.airplay

import kotlin.math.max
import kotlin.math.min

/**
 * Port of the reference receiver's timeline-synchronized playout buffer
 * (`timeline_buffer.h` from android-airplay-server / UxPlay).
 *
 * The AirPlay sender stamps every RTP packet with a sample-clock timestamp.
 * Wi-Fi delivery is bursty and occasionally reordered, so writing PCM straight
 * into the audio sink either underruns or accumulates latency. This buffer:
 *
 *  - absorbs jitter with an adaptive cushion derived from arrival-vs-PTS delay,
 *  - recreates sender gaps (packet loss, pauses) as exact-length silence,
 *  - drops packets whose presentation slot has already passed,
 *  - trims a backlog that stayed over the cushion for a while, and
 *  - primes before the first read so the sink does not start with a gap.
 *
 * One producer (RTP decode thread) and one consumer (PCM writer thread); all
 * state is guarded by [lock] because the consumer is not a realtime callback.
 */
class AirPlayTimelineBuffer(
    sampleRate: Int,
    channels: Int,
    private val staticCushionMs: Int = 0,
    private val percentilePct: Int = 95,
) {
    private val lock = Any()
    private val sampleRate = sampleRate.coerceAtLeast(8000)
    private val channels = channels.coerceAtLeast(1)

    private val tracker = DelayTracker(this.sampleRate, this.channels, staticCushionMs, percentilePct)
    private val ring = ShortArray(ringCapacity(tracker.ceilSamples()))
    private var writePos = 0L
    private var readPos = 0L
    private var expectedPtsNs = 0L
    private var started = false

    private var priming = true
    private var primeSilenceFrames = 0L
    private var lastTrimBlockNs = 0L
    private var aboveCapSinceNs = 0L
    private var underran = false
    private var running = true

    /** Producer: interleaved 16-bit PCM at [ptsNs], `lengthBytes` must be even. */
    fun write(pcm: ByteArray, lengthBytes: Int, ptsNs: Long) {
        val samples = lengthBytes / 2
        if (samples <= 0) return
        val shorts = ShortArray(samples) { i ->
            ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
        }
        write(shorts, samples, ptsNs)
    }

    fun write(pcm: ShortArray, samples: Int, ptsNs: Long) {
        if (samples <= 0) return
        val durNs = samples * 1_000_000_000L / channels / sampleRate
        synchronized(lock) {
            if (!running) return
            if (!started) {
                started = true
                expectedPtsNs = ptsNs
            }
            if (underran) {
                underran = false
                expectedPtsNs = ptsNs
            }
            val gapNs = ptsNs - expectedPtsNs
            val discontinuity = gapNs > MAX_GAP_NS || gapNs < -MAX_GAP_NS
            if (discontinuity) tracker.reanchor()
            tracker.observe(ptsNs, System.nanoTime(), durNs)

            when {
                discontinuity -> {
                    // New sound after a long silence (or a clock resync): start
                    // immediately; the tracker rebuilds its cushion.
                }
                gapNs > SLACK_NS -> {
                    val silenceFrames = (gapNs * sampleRate / 1_000_000_000L).toInt()
                    writeSilenceFrames(silenceFrames)
                    tracker.countSilence()
                }
                gapNs < -SLACK_NS -> {
                    // Slot already passed (late/overlap): drop.
                    tracker.countDrop()
                    return
                }
            }
            ringWrite(pcm, samples)
            expectedPtsNs = ptsNs + durNs
        }
    }

    /**
     * Consumer: read exactly [frames] frames, zero-filling on underrun.
     * Returns true when the read was fully satisfied from buffered audio.
     */
    fun read(out: ShortArray, frames: Int): Boolean {
        val need = frames * channels
        if (need <= 0) return true
        synchronized(lock) {
            if (!running) {
                java.util.Arrays.fill(out, 0, min(need, out.size), 0)
                return false
            }
            val tuned = tracker.targetSamples()
            val now = System.nanoTime()

            val available = ringAvailable()
            if (available > capOf(tuned)) {
                if (aboveCapSinceNs == 0L) aboveCapSinceNs = now
            } else {
                aboveCapSinceNs = 0L
            }
            if (aboveCapSinceNs != 0L && now - aboveCapSinceNs >= TRIM_SUSTAIN_NS &&
                now - lastTrimBlockNs >= TRIM_THROTTLE_NS
            ) {
                ringSkip(available - tuned)
                tracker.countTrim()
                lastTrimBlockNs = now
                aboveCapSinceNs = 0L
            }

            if (priming) {
                val buffered = ringAvailable()
                if (buffered == 0L) {
                    primeSilenceFrames = 0
                } else {
                    primeSilenceFrames += frames
                }
                val starveFrames = 2L * tuned / channels
                val starved = buffered > 0 && primeSilenceFrames >= starveFrames
                if (buffered == 0L || (buffered < tuned && !starved)) {
                    java.util.Arrays.fill(out, 0, min(need, out.size), 0)
                    return false
                }
                priming = false
                primeSilenceFrames = 0
            }

            val got = ringRead(out, need)
            if (got < need) {
                java.util.Arrays.fill(out, got, need, 0)
                priming = true
                lastTrimBlockNs = now
                underran = true
                tracker.countUnderrun()
                return false
            }
            return true
        }
    }

    fun flushAndReprime() {
        synchronized(lock) {
            ringSkip(ringAvailable())
            priming = true
            primeSilenceFrames = 0
            expectedPtsNs = 0
            started = false
        }
    }

    fun noteOutputBufferFrames(frames: Int) {
        synchronized(lock) { tracker.noteOutputBufferFrames(frames) }
    }

    fun availableMs(): Int = synchronized(lock) {
        (ringAvailable() / channels * 1000 / sampleRate).toInt()
    }

    fun stop() {
        synchronized(lock) {
            running = false
            ringSkip(ringAvailable())
        }
    }

    fun debugInfo(): Debug {
        synchronized(lock) {
            return Debug(
                backlogMs = (ringAvailable() / channels * 1000 / sampleRate).toInt(),
                tunedCushionMs = (tracker.targetSamples() / channels * 1000 / sampleRate).toInt(),
                trims = tracker.trims,
                drops = tracker.drops,
                silences = tracker.silences,
                underruns = tracker.underruns,
            )
        }
    }

    data class Debug(
        val backlogMs: Int,
        val tunedCushionMs: Int,
        val trims: Int,
        val drops: Int,
        val silences: Int,
        val underruns: Int,
    )

    // ------------------------------------------------------------- ring buffer

    private fun ringAvailable(): Long = writePos - readPos

    private fun ringWrite(src: ShortArray, count: Int) {
        val free = ring.size - ringAvailable()
        val n = min(count.toLong(), free).toInt()
        if (n <= 0) return
        val wi = (writePos % ring.size).toInt()
        val first = min(n, ring.size - wi)
        System.arraycopy(src, 0, ring, wi, first)
        if (n > first) System.arraycopy(src, first, ring, 0, n - first)
        writePos += n
    }

    private fun ringRead(dst: ShortArray, count: Int): Int {
        val n = min(count.toLong(), ringAvailable()).toInt()
        if (n <= 0) return 0
        val ri = (readPos % ring.size).toInt()
        val first = min(n, ring.size - ri)
        System.arraycopy(ring, ri, dst, 0, first)
        if (n > first) System.arraycopy(ring, 0, dst, first, n - first)
        readPos += n
        return n
    }

    private fun ringSkip(count: Long) {
        val n = min(count, ringAvailable())
        if (n > 0) readPos += n
    }

    private fun writeSilenceFrames(frames: Int) {
        if (frames <= 0) return
        var remaining = frames.toLong() * channels
        val zeros = ShortArray(min(remaining, 1024L).toInt())
        while (remaining > 0) {
            val n = min(remaining, zeros.size.toLong()).toInt()
            ringWrite(zeros, n)
            remaining -= n
        }
    }

    private fun capOf(cushionSamples: Int): Int =
        max(cushionSamples * 2, sampleRate * TRIM_FLOOR_MS / 1000 * channels)

    private fun ringCapacity(ceilSamples: Int): Int {
        val cap = max(ceilSamples * 2, sampleRate * TRIM_FLOOR_MS / 1000 * channels)
        val capacity = 2L * cap + sampleRate.toLong() * TRIM_THROTTLE_MS / 1000 * channels
        return capacity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Adaptive cushion tuner, NetEQ-inspired: a decaying histogram of packet
     * jitter; cushion = percentile of the histogram + one output-buffer margin.
     */
    private class DelayTracker(
        private val sampleRate: Int,
        private val channels: Int,
        staticCushionMs: Int,
        private val percentilePct: Int,
    ) {
        private val adaptive = staticCushionMs <= 0
        private val floor = msToSamples(if (adaptive) MIN_CUSHION_MS else staticCushionMs)
        private val ceil = msToSamples(if (adaptive) MAX_CUSHION_MS else staticCushionMs)
        private val hist = LongArray(NBUCKETS)
        private val tuned = LongArray(1) { floor.toLong() }

        private var baseNs = 0L
        private var haveBase = false
        private var outputBufferNs = 0L

        var trims = 0; private set
        var drops = 0; private set
        var silences = 0; private set
        var underruns = 0; private set

        fun observe(ptsNs: Long, arrivalNs: Long, durNs: Long) {
            if (!adaptive) return
            val raw = arrivalNs - ptsNs
            if (!haveBase) {
                baseNs = raw
                haveBase = true
            } else if (raw < baseNs) {
                baseNs = raw
            } else {
                baseNs += (raw - baseNs) shr BASE_CREEP_SHIFT
            }
            var relative = raw - baseNs
            if (relative < 0) relative = 0
            var bin = (relative / BUCKET_NS).toInt()
            if (bin >= NBUCKETS) bin = NBUCKETS - 1

            var sum = 0L
            for (i in 0 until NBUCKETS) {
                hist[i] -= (hist[i] + (1L shl FORGET_SHIFT) - 1) shr FORGET_SHIFT
                sum += hist[i]
            }
            hist[bin] += ONE shr FORGET_SHIFT
            sum += ONE shr FORGET_SHIFT

            val threshold = sum * percentilePct / 100
            var cumulative = 0L
            var b = 0
            while (b < NBUCKETS - 1) {
                cumulative += hist[b]
                if (cumulative >= threshold) break
                b++
            }
            val marginNs = max(durNs, outputBufferNs)
            val targetNs = (b + 1).toLong() * BUCKET_NS + marginNs
            var samples = (targetNs * sampleRate / NS_PER_SEC).toInt() * channels
            if (samples < floor) samples = floor
            if (samples > ceil) samples = ceil
            tuned[0] = samples.toLong()
        }

        fun reanchor() {
            haveBase = false
        }

        fun targetSamples(): Int = tuned[0].toInt()

        fun ceilSamples(): Int = ceil

        fun noteOutputBufferFrames(frames: Int) {
            outputBufferNs = frames.toLong() * NS_PER_SEC / sampleRate
        }

        fun countTrim() { trims++ }
        fun countDrop() { drops++ }
        fun countSilence() { silences++ }
        fun countUnderrun() { underruns++ }

        private fun msToSamples(ms: Int): Int = sampleRate * ms / 1000 * channels

        private companion object {
            const val MIN_CUSHION_MS = 0
            const val MAX_CUSHION_MS = 1000
            const val BUCKET_MS = 5
            const val NBUCKETS = MAX_CUSHION_MS / BUCKET_MS
            const val BUCKET_NS = BUCKET_MS * 1_000_000L
            const val FORGET_SHIFT = 8
            const val BASE_CREEP_SHIFT = 10
            const val ONE = 1L shl 20
            const val NS_PER_SEC = 1_000_000_000L
        }
    }

    private companion object {
        const val SLACK_NS = 10_000_000L
        const val MAX_GAP_NS = 750_000_000L
        const val TRIM_FLOOR_MS = 30
        const val TRIM_THROTTLE_MS = 2000
        const val TRIM_THROTTLE_NS = TRIM_THROTTLE_MS * 1_000_000L
        const val TRIM_SUSTAIN_NS = 2000 * 1_000_000L
    }
}
