package com.aurora.music.playback

import com.aurora.music.data.AudioDecoder
import kotlin.math.abs
import kotlin.math.log10

/**
 * Per-second BS.1770 LUFS and raw sample-peak profile for a local track,
 * used by [DrivingLeveler] to ramp boost down smoothly before loud passages.
 */
data class TrackLoudnessMap(
    val path: String,
    val lufsPerSec: FloatArray,
    val peakDbPerSec: FloatArray,
)

object TrackLoudnessScanner {
    private const val MAX_CACHE_ENTRIES = 32

    private val cache = object : LinkedHashMap<String, TrackLoudnessMap>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackLoudnessMap>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    fun getCached(path: String): TrackLoudnessMap? = synchronized(cache) { cache[path] }

    fun scanOrGet(path: String, isCancelled: () -> Boolean): TrackLoudnessMap? {
        if (path.isBlank()) return null
        getCached(path)?.let { return it }

        var state: MapBuilder? = null
        val ok = AudioDecoder.decode(
            path,
            onFormat = { sr, ch -> state = MapBuilder(sr, ch) },
            onPcm = { pcm, len -> state?.add(pcm, len) },
            isCancelled = isCancelled,
        )
        if (!ok || isCancelled()) return null
        val map = state?.finish(path) ?: return null
        if (map.lufsPerSec.isEmpty()) return null
        synchronized(cache) { cache[path] = map }
        return map
    }

    private class MapBuilder(private val sampleRate: Int, private val channels: Int) {
        private val filters = Array(channels) { KWeightingFilter(sampleRate) }
        private val weights = DoubleArray(channels) { if (it == 3 || it == 4) 1.41 else 1.0 }
        private val channelSumSq = DoubleArray(channels)
        private val lufs = ArrayList<Float>(512)
        private val peaks = ArrayList<Float>(512)
        private var frameCount = 0
        private var maxAbs = 0f

        fun add(pcm: ShortArray, length: Int) {
            var i = 0
            while (i + channels <= length) {
                for (ch in 0 until channels) {
                    val raw = pcm[i + ch] / 32768.0
                    val a = abs(raw).toFloat()
                    if (a > maxAbs) maxAbs = a
                    val y = filters[ch].process(raw)
                    channelSumSq[ch] += y * y
                }
                i += channels
                if (++frameCount >= sampleRate) flushSlice()
            }
        }

        private fun flushSlice() {
            if (frameCount <= 0) return
            var z = 0.0
            for (ch in 0 until channels) {
                z += weights[ch] * (channelSumSq[ch] / frameCount.toDouble())
            }
            lufs.add(energyToLufs(z))
            peaks.add(if (maxAbs > 1e-6f) (20.0 * log10(maxAbs.toDouble())).toFloat() else -100f)
            channelSumSq.fill(0.0)
            frameCount = 0
            maxAbs = 0f
        }

        fun finish(path: String): TrackLoudnessMap {
            if (frameCount >= (sampleRate / 10).coerceAtLeast(1)) {
                flushSlice()
            }
            return TrackLoudnessMap(path, lufs.toFloatArray(), peaks.toFloatArray())
        }
    }
}
