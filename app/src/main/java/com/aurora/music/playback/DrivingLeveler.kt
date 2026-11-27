package com.aurora.music.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10

/**
 * Configuration for Driving Mode's upward gain rider.
 * Only applies non-negative boost (0 .. +maxBoostDb) to quiet passages;
 * loud passages are pulled back toward 0 dB rather than compressed downward.
 */
data class DrivingParams(
    val enabled: Boolean = false,
    val targetLoudnessDb: Float = -16f,
    val floorOffsetDb: Float = -12f,
    val holdSeconds: Float = 5f,
    val boostRateDbPerSec: Float = 2f,
    val maxBoostDb: Float = 20f,
    val downRateDbPerSec: Float = 6f,
    val lookaheadSec: Float = 3f,
    val lookaheadDownRateDbPerSec: Float = 4f,
    val microLookaheadMs: Float = 100f,
    val peakCeilingDb: Float = -1f,
    val silenceGateDb: Float = -55f,
) {
    val floorDb: Float get() = targetLoudnessDb + floorOffsetDb
}

/**
 * Live lookahead snapshot of already-buffered, not-yet-played PCM (e.g. from AirPlay's
 * timeline jitter buffer), allowing pre-emptive ramp-down across the full buffer horizon.
 */
class LiveLookahead(
    val lufs: FloatArray,
    val peakDb: FloatArray,
    val count: Int,
    val bucketSec: Float,
    val firstBucketOffsetSec: Float,
)

/**
 * Real-time upward leveler with hybrid lookahead:
 * - 1 s sliding K-weighted LUFS window (100 ms steps) drives upward boost when below [DrivingParams.floorDb].
 * - Hysteresis deadband [floorDb, targetLoudnessDb] freezes the current boost without breathing.
 * - Real-time short-window (300 ms) + micro-lookahead delay ring pull boost back before loud transients clip.
 * - Optional [TrackLoudnessMap] lookahead caps gain ahead of upcoming fortissimo sections.
 *
 * Audio-thread hot path performs zero heap allocations.
 */
class DrivingLeveler {
    var outL: Float = 0f
        private set
    var outR: Float = 0f
        private set

    @Volatile private var params: DrivingParams = DrivingParams()
    @Volatile private var loudnessMap: TrackLoudnessMap? = null
    @Volatile private var liveLookahead: LiveLookahead? = null
    @Volatile private var positionSec: Float = -1f
    @Volatile private var resetRequested: Boolean = false

    @Volatile var currentBoostDb: Float = 0f
        private set

    val hasActiveMap: Boolean get() = loudnessMap != null || (liveLookahead?.let { it.count > 0 } == true)
    val isEnabled: Boolean get() = params.enabled

    private var fs: Int = 48000
    private var kwL = KWeightingFilter(48000)
    private var kwR = KWeightingFilter(48000)

    private var maxDelaySamples: Int = 9600
    private var delayBufL = FloatArray(9601)
    private var delayBufR = FloatArray(9601)
    private var delayWrite: Int = 0

    private var subblockSamples: Int = 4800
    private var subSumSq: Double = 0.0
    private var subCount: Int = 0

    private val ring1s = DoubleArray(10)
    private var ringWrite: Int = 0
    private var ringFilled: Int = 0

    private var peakBucketSamples: Int = 240
    private var peakBuckets = FloatArray(45)
    private var peakBucketWrite: Int = 0
    private var curBucketPeak: Float = 0f
    private var curBucketCount: Int = 0
    private var windowPeak: Float = 0f

    private var curGainDb: Float = 0f
    private var targetGainDb: Float = 0f
    private var belowFloorSec: Float = 0f
    private var silenceSec: Float = 0f
    private var boosting: Boolean = false
    private var urgentDownRate: Float = 0f
    private var liveLookaheadRate: Float = 0f
    private var cachedGainDb: Float = 0f
    private var cachedGainLin: Float = 1f

    fun update(p: DrivingParams) {
        val wasEnabled = params.enabled
        params = p
        if (!p.enabled) {
            currentBoostDb = 0f
            if (wasEnabled) resetRequested = true
        } else if (!wasEnabled) {
            resetRequested = true
        }
    }

    fun setLoudnessMap(map: TrackLoudnessMap?) {
        loudnessMap = map
    }

    fun setLiveLookahead(snapshot: LiveLookahead?) {
        liveLookahead = snapshot
    }

    fun setPositionSec(posSec: Float) {
        positionSec = posSec
    }

    fun configure(sampleRate: Int) {
        fs = sampleRate.coerceAtLeast(8000)
        kwL = KWeightingFilter(fs)
        kwR = KWeightingFilter(fs)

        maxDelaySamples = (0.200f * fs).toInt().coerceAtLeast(1)
        delayBufL = FloatArray(maxDelaySamples + 1)
        delayBufR = FloatArray(maxDelaySamples + 1)

        subblockSamples = (fs / 10).coerceAtLeast(1)
        peakBucketSamples = (fs / 200).coerceAtLeast(1)
        val bucketCount = (maxDelaySamples / peakBucketSamples) + 3
        peakBuckets = FloatArray(bucketCount.coerceAtLeast(4))
        reset()
    }

    fun reset() {
        resetRequested = false
        kwL.reset()
        kwR.reset()
        delayBufL.fill(0f)
        delayBufR.fill(0f)
        delayWrite = 0
        subSumSq = 0.0
        subCount = 0
        ring1s.fill(0.0)
        ringWrite = 0
        ringFilled = 0
        peakBuckets.fill(0f)
        peakBucketWrite = 0
        curBucketPeak = 0f
        curBucketCount = 0
        windowPeak = 0f
        curGainDb = 0f
        targetGainDb = 0f
        belowFloorSec = 0f
        silenceSec = 0f
        boosting = false
        urgentDownRate = 0f
        liveLookaheadRate = 0f
        cachedGainDb = 0f
        cachedGainLin = 1f
        currentBoostDb = 0f
    }

    fun processSample(inL: Float, inR: Float) {
        if (resetRequested) reset()
        val p = params
        if (!p.enabled) {
            outL = inL
            outR = inR
            return
        }

        // 1. K-weighting & 100 ms subblock accumulation on raw input
        val kl = kwL.process(inL.toDouble())
        val kr = kwR.process(inR.toDouble())
        subSumSq += kl * kl + kr * kr
        subCount++
        if (subCount >= subblockSamples) {
            stepControl100ms(p)
        }

        // 2. Micro-lookahead delay ring & peak bucket tracking
        val delaySamples = ((p.microLookaheadMs / 1000f) * fs).toInt().coerceIn(0, maxDelaySamples)
        val inPeak = maxOf(abs(inL), abs(inR))
        if (inPeak > curBucketPeak) curBucketPeak = inPeak
        curBucketCount++
        if (curBucketCount >= peakBucketSamples) {
            val bSize = peakBuckets.size
            peakBuckets[peakBucketWrite] = curBucketPeak
            peakBucketWrite = (peakBucketWrite + 1) % bSize
            curBucketPeak = 0f
            curBucketCount = 0
            windowPeak = if (delaySamples <= 0) {
                0f
            } else {
                val activeBuckets = ((delaySamples + peakBucketSamples - 1) / peakBucketSamples)
                    .coerceAtMost(bSize - 1)
                var wp = 0f
                var b = 1
                while (b <= activeBuckets) {
                    val idx = (peakBucketWrite - b + bSize) % bSize
                    val v = peakBuckets[idx]
                    if (v > wp) wp = v
                    b++
                }
                wp
            }
        }

        val dSize = delayBufL.size
        delayBufL[delayWrite] = inL
        delayBufR[delayWrite] = inR
        val readIdx = (delayWrite - delaySamples + dSize) % dSize
        val dL = delayBufL[readIdx]
        val dR = delayBufR[readIdx]
        delayWrite = (delayWrite + 1) % dSize

        // 3. Per-sample smooth gain ramp + urgent micro-lookahead peak safety
        val maxBoost = p.maxBoostDb.coerceAtLeast(0f)
        val tgt = targetGainDb.coerceIn(0f, maxBoost)
        if (curGainDb < tgt) {
            curGainDb = minOf(tgt, curGainDb + p.boostRateDbPerSec.coerceAtLeast(0.1f) / fs)
        } else if (curGainDb > tgt) {
            val downPerSec = maxOf(p.downRateDbPerSec, p.lookaheadDownRateDbPerSec, liveLookaheadRate).coerceAtLeast(0.1f)
            curGainDb = maxOf(tgt, curGainDb - downPerSec / fs)
        }

        val upcomingPeak = if (delaySamples == 0) inPeak else maxOf(windowPeak, curBucketPeak, inPeak)
        if (upcomingPeak > 1e-6f) {
            val peakSafeDb = (p.peakCeilingDb - (20.0 * log10(upcomingPeak.toDouble())).toFloat())
                .coerceAtLeast(0f)
            if (curGainDb > peakSafeDb) {
                if (delaySamples <= 1) {
                    curGainDb = peakSafeDb
                    urgentDownRate = 0f
                } else {
                    val delaySec = delaySamples.toFloat() / fs
                    val reqRate = (curGainDb - peakSafeDb) / (delaySec * 0.5f)
                    if (reqRate > urgentDownRate) urgentDownRate = reqRate
                    val rate = maxOf(p.downRateDbPerSec, urgentDownRate)
                    curGainDb = maxOf(peakSafeDb, curGainDb - rate / fs)
                }
                if (targetGainDb > curGainDb) targetGainDb = curGainDb
                boosting = false
                belowFloorSec = 0f
            } else {
                urgentDownRate = 0f
            }
        } else {
            urgentDownRate = 0f
        }

        curGainDb = curGainDb.coerceIn(0f, maxBoost)
        if (abs(curGainDb - cachedGainDb) > 0.005f) {
            cachedGainLin = Math.pow(10.0, curGainDb / 20.0).toFloat()
            cachedGainDb = curGainDb
        }
        outL = dL * cachedGainLin
        outR = dR * cachedGainLin
    }

    fun publishMeter() {
        currentBoostDb = if (params.enabled) curGainDb else 0f
    }

    private fun stepControl100ms(p: DrivingParams) {
        val blockMean = subSumSq / subCount.toDouble()
        subSumSq = 0.0
        subCount = 0

        ring1s[ringWrite] = blockMean
        ringWrite = (ringWrite + 1) % 10
        if (ringFilled < 10) ringFilled++

        var sum1s = 0.0
        for (i in 0 until ringFilled) sum1s += ring1s[i]
        val raw1sLufs = energyToLufs(sum1s / ringFilled.toDouble())

        val shortCount = minOf(3, ringFilled)
        var sumShort = 0.0
        for (i in 1..shortCount) {
            val idx = (ringWrite - i + 10) % 10
            sumShort += ring1s[idx]
        }
        val rawShortLufs = energyToLufs(sumShort / shortCount.toDouble())

        val eff1sLufs = raw1sLufs + curGainDb
        val effShortLufs = rawShortLufs + curGainDb
        val dt = 0.1f
        val maxBoost = p.maxBoostDb.coerceAtLeast(0f)

        if (raw1sLufs < p.silenceGateDb) {
            silenceSec += dt
            boosting = false
            if (silenceSec >= 3.0f && targetGainDb > 0f) {
                val decayRate = minOf(p.boostRateDbPerSec, 2f).coerceAtLeast(0.1f)
                targetGainDb = (targetGainDb - decayRate * dt).coerceAtLeast(0f)
            }
        } else {
            silenceSec = 0f
            if (effShortLufs > p.targetLoudnessDb) {
                belowFloorSec = 0f
                boosting = false
                val safeTarget = (p.targetLoudnessDb - rawShortLufs).coerceAtLeast(0f)
                targetGainDb = maxOf(safeTarget, targetGainDb - p.downRateDbPerSec * dt)
            } else if (ringFilled >= 10 && eff1sLufs < p.floorDb) {
                belowFloorSec += dt
                if (belowFloorSec >= p.holdSeconds) boosting = true
                if (boosting) {
                    val needed = (p.floorDb - raw1sLufs).coerceIn(0f, maxBoost)
                    targetGainDb = minOf(needed, targetGainDb + p.boostRateDbPerSec * dt).coerceAtMost(maxBoost)
                }
            } else {
                belowFloorSec = 0f
                boosting = false
            }
        }

        targetGainDb = targetGainDb.coerceIn(0f, maxBoost)

        val map = loudnessMap
        val pos = positionSec
        if (map != null && p.lookaheadSec > 0f && pos >= 0f && map.lufsPerSec.isNotEmpty()) {
            val mapCeiling = computeMapCeiling(map, pos, p, maxBoost)
            if (targetGainDb > mapCeiling) {
                targetGainDb = mapCeiling
                boosting = false
                belowFloorSec = 0f
            }
        }

        val live = liveLookahead
        if (live != null && live.count > 0 && p.lookaheadSec > 0f) {
            var minAllowed = maxBoost
            var neededRate = 0f
            for (i in 0 until live.count) {
                val dtAhead = (i * live.bucketSec - live.firstBucketOffsetSec - 0.1f).coerceAtLeast(0f)
                val safeLufs = (p.targetLoudnessDb - live.lufs[i]).coerceAtLeast(0f)
                val safePeak = (p.peakCeilingDb - live.peakDb[i]).coerceAtLeast(0f)
                val safe = minOf(safeLufs, safePeak)
                val allowed = safe + p.lookaheadDownRateDbPerSec * dtAhead
                if (allowed < minAllowed) minAllowed = allowed
                if (allowed < curGainDb && safe < curGainDb) {
                    val reqRate = (curGainDb - safe) / dtAhead.coerceAtLeast(0.05f)
                    if (reqRate > neededRate) neededRate = reqRate
                }
            }
            minAllowed = minAllowed.coerceIn(0f, maxBoost)
            if (targetGainDb > minAllowed) {
                targetGainDb = minAllowed
                boosting = false
                belowFloorSec = 0f
                liveLookaheadRate = neededRate
            } else if (curGainDb <= minAllowed + 0.05f) {
                liveLookaheadRate = 0f
            }
        } else {
            liveLookaheadRate = 0f
        }
    }

    private fun computeMapCeiling(
        map: TrackLoudnessMap,
        posSec: Float,
        p: DrivingParams,
        maxBoost: Float,
    ): Float {
        val lastIdx = map.lufsPerSec.lastIndex
        val startIdx = floor(posSec.toDouble()).toInt().coerceIn(0, lastIdx)
        val endIdx = ceil((posSec + p.lookaheadSec).toDouble()).toInt().coerceIn(startIdx, lastIdx)
        var ceiling = maxBoost
        for (idx in startIdx..endIdx) {
            val dtAhead = (idx.toFloat() - posSec - 0.1f).coerceAtLeast(0f)
            val safeLufs = (p.targetLoudnessDb - map.lufsPerSec[idx]).coerceAtLeast(0f)
            val safePeak = (p.peakCeilingDb - map.peakDbPerSec[idx]).coerceAtLeast(0f)
            val safe = minOf(safeLufs, safePeak)
            val allowed = safe + p.lookaheadDownRateDbPerSec * dtAhead
            if (allowed < ceiling) ceiling = allowed
        }
        return ceiling.coerceIn(0f, maxBoost)
    }
}
