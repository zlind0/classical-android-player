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
    staticCushionMs: Int = AirPlayConfig.DEFAULT_BUFFER_MS,
    plcEnabled: Boolean = true,
    plcMaxMs: Int = AirPlayConfig.DEFAULT_PLC_MAX_MS,
) {
    private val lock = Any()
    private val sampleRate = sampleRate.coerceAtLeast(8000)
    private val channels = channels.coerceAtLeast(1)

    private val tracker = DelayTracker(this.sampleRate, this.channels, staticCushionMs)
    // 物理大小固定 2s：缓冲时间允许 0.5–1.5s，留下 0.5s 给突发/裁剪。
    private val ring = ShortArray(this.sampleRate * AirPlayConfig.RING_MS / 1000 * this.channels)
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

    // ---- packet loss concealment (PLC) ----
    private var plcEnabled = plcEnabled
    private var plcMaxMs = plcMaxMs
    /** 最近 20ms 的真实音频，用来在短缺口里循环衰减填充。 */
    private val plcHistory = ShortArray(this.sampleRate * PLC_HISTORY_MS / 1000 * this.channels)
    private var plcHistoryPos = 0
    private var plcHistoryCount = 0
    /** 缺口结束后，下一块真实音频的前几毫秒做淡入，避免硬切。 */
    private var fadeInFrames = 0

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
                    val gapFrames = (gapNs * sampleRate / 1_000_000_000L).toInt()
                    val gapMs = gapNs / 1_000_000L
                    if (plcEnabled && gapMs <= plcMaxMs && plcHistoryCount > 0) {
                        // 短缺口：用上一段音频循环并线性衰减，听感是“糊一下”，
                        // 而不是直接把波形切断到 0。
                        writePlcFrames(gapFrames)
                        tracker.countConceal()
                    } else {
                        writeSilenceFrames(gapFrames)
                        tracker.countSilence()
                    }
                    fadeInFrames = FADE_IN_MS * sampleRate / 1000
                }
                gapNs < -SLACK_NS -> {
                    // Slot already passed (late/overlap): drop.
                    tracker.countDrop()
                    return
                }
            }
            applyFadeIn(pcm, samples)
            ringWrite(pcm, samples)
            rememberHistory(pcm, samples)
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
            fadeInFrames = 0
            plcHistoryCount = 0
            plcHistoryPos = 0
            aboveCapSinceNs = 0
            underran = false
            lastTrimBlockNs = 0
        }
    }

    /** 固定缓冲时间变化时调用；下次 read 会按新目标重新预缓冲。 */
    fun updateCushionMs(ms: Int) {
        synchronized(lock) { tracker.setCushion(ms.coerceIn(AirPlayConfig.MIN_BUFFER_MS, AirPlayConfig.MAX_BUFFER_MS)) }
    }

    fun updatePlc(enabled: Boolean, maxMs: Int) {
        synchronized(lock) {
            plcEnabled = enabled
            plcMaxMs = maxMs.coerceIn(AirPlayConfig.MIN_PLC_MAX_MS, AirPlayConfig.MAX_PLC_MAX_MS)
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
                conceals = tracker.conceals,
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
        val conceals: Int,
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

    /** 记录最近 20ms 真实音频，供短缺口 PLC 循环使用。 */
    private fun rememberHistory(src: ShortArray, count: Int) {
        if (plcHistory.isEmpty() || count <= 0) return
        var remaining = count
        var offset = 0
        while (remaining > 0) {
            val n = min(remaining, plcHistory.size - plcHistoryPos)
            System.arraycopy(src, offset, plcHistory, plcHistoryPos, n)
            plcHistoryPos = (plcHistoryPos + n) % plcHistory.size
            plcHistoryCount = min(plcHistory.size, plcHistoryCount + n)
            offset += n
            remaining -= n
        }
    }

    private fun writePlcFrames(frames: Int) {
        if (frames <= 0 || plcHistoryCount <= 0) return
        val need = frames * channels
        val scratch = ShortArray(min(need, 2048))
        var written = 0
        var cursor = ((plcHistoryPos - plcHistoryCount) % plcHistory.size + plcHistory.size) %
            plcHistory.size
        while (written < need) {
            val n = min(need - written, scratch.size)
            for (i in 0 until n) {
                val progress = if (need > 1) written.toFloat() / (need - 1) else 0f
                // 全衰减到 0 又变成静音，保留一点尾部能量，再由下一块淡入接上。
                val gain = 1f - 0.85f * progress
                scratch[i] = (plcHistory[cursor].toInt() * gain).toInt().toShort()
                cursor++
                if (cursor >= plcHistory.size) cursor = 0
            }
            ringWrite(scratch, n)
            written += n
        }
    }

    private fun applyFadeIn(pcm: ShortArray, samples: Int) {
        if (fadeInFrames <= 0) return
        val frames = samples / channels
        val n = min(frames, fadeInFrames)
        if (n <= 0) return
        for (f in 0 until n) {
            val gain = (f + 1).toFloat() / (n + 1)
            val base = f * channels
            for (c in 0 until channels) {
                pcm[base + c] = (pcm[base + c] * gain).toInt().toShort()
            }
        }
        fadeInFrames = 0
    }

    private fun capOf(cushionSamples: Int): Int {
        val margin = sampleRate * CAP_MARGIN_MS / 1000 * channels
        return max(cushionSamples * 2, sampleRate * TRIM_FLOOR_MS / 1000 * channels)
            .coerceAtMost((ring.size - margin).coerceAtLeast(cushionSamples))
    }

    /** 固定 cushion：不再需要自适应直方图，目标样本数就是配置的毫秒数。 */
    private class DelayTracker(
        private val sampleRate: Int,
        private val channels: Int,
        staticCushionMs: Int,
    ) {
        private var cushionSamples = msToSamples(staticCushionMs)
        private val tuned = LongArray(1) { cushionSamples.toLong() }

        var trims = 0; private set
        var drops = 0; private set
        var silences = 0; private set
        var conceals = 0; private set
        var underruns = 0; private set

        fun observe(ptsNs: Long, arrivalNs: Long, durNs: Long) = Unit

        fun reanchor() = Unit

        fun setCushion(ms: Int) {
            cushionSamples = msToSamples(ms)
            tuned[0] = cushionSamples.toLong()
        }

        fun targetSamples(): Int = tuned[0].toInt()

        fun noteOutputBufferFrames(frames: Int) = Unit

        fun countTrim() { trims++ }
        fun countDrop() { drops++ }
        fun countSilence() { silences++ }
        fun countConceal() { conceals++ }
        fun countUnderrun() { underruns++ }

        private fun msToSamples(ms: Int): Int = sampleRate * ms / 1000 * channels
    }

    private companion object {
        const val SLACK_NS = 3_000_000L
        const val MAX_GAP_NS = 750_000_000L
        const val TRIM_FLOOR_MS = 30
        const val TRIM_THROTTLE_MS = 2000
        const val TRIM_THROTTLE_NS = TRIM_THROTTLE_MS * 1_000_000L
        const val TRIM_SUSTAIN_NS = 2000 * 1_000_000L
        const val PLC_HISTORY_MS = 20
        const val FADE_IN_MS = 3
        const val CAP_MARGIN_MS = 50
    }
}
