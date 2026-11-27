package com.aurora.music.data

import com.aurora.music.model.Song
import com.aurora.music.playback.KWeightingFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10

// gains can't be written to files so cache by path and overlay onto songs at library build
class ReplayGainScanner(
    private val pool: SongPool,
    private val store: ReplayGainStore,
) {
    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "")

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null

    private companion object {
        const val RG2_REFERENCE_LUFS = -18.0
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val OFFSET = -0.691            // bs.1770 loudness offset
        const val ANALYSIS_VERSION = 1
    }

    fun cancel() { job?.cancel() }

    fun scan(onComplete: () -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch {
            pool.ensureLoaded()
            val tracks = pool.songs.filter { it.path.isNotBlank() }
            _progress.value = Progress(running = true, done = 0, total = tracks.size)

            val albumBlocks = HashMap<String, MutableList<Double>>()
            val trackGain = HashMap<String, Float>()
            val trackLufs = HashMap<String, Float>()
            val trackLra = HashMap<String, Float>()
            val trackPeak = HashMap<String, Float>()
            val pathsByAlbum = HashMap<String, MutableList<String>>()
            var scanned = 0
            try {
                for ((i, song) in tracks.withIndex()) {
                    if (!isActive) break
                    _progress.value = Progress(true, i, tracks.size, song.title)
                    val res = measure(song.path) { isActive }
                    if (res != null && res.blocks.isNotEmpty()) {
                        integrated(res.blocks)?.let { lufs ->
                            trackGain[song.path] = (RG2_REFERENCE_LUFS - lufs).toFloat()
                            trackLufs[song.path] = lufs.toFloat()
                        }
                        trackLra[song.path] = loudnessRange(res.blocks).toFloat()
                        trackPeak[song.path] = res.peak
                        val key = song.albumId.ifBlank { "album:" + song.album }
                        albumBlocks.getOrPut(key) { mutableListOf() }.addAll(res.blocks)
                        pathsByAlbum.getOrPut(key) { mutableListOf() }.add(song.path)
                    }
                    scanned = i + 1
                    _progress.value = Progress(true, scanned, tracks.size, song.title)
                }
            } finally {
                // runs even on cancel so partial results survive
                val entries = HashMap<String, RgEntry>()
                for ((key, paths) in pathsByAlbum) {
                    val albumGain = albumBlocks[key]?.let { integrated(it) }?.let { (RG2_REFERENCE_LUFS - it).toFloat() } ?: 0f
                    for (p in paths) entries[p] = RgEntry(
                        track = trackGain[p] ?: albumGain,
                        album = albumGain,
                        lufs = trackLufs[p],
                        lra = trackLra[p],
                        peak = trackPeak[p],
                        version = ANALYSIS_VERSION,
                    )
                }
                store.putAll(entries)
                _progress.value = Progress(running = false, done = entries.size, total = tracks.size)
            }
            runCatching { pool.refresh() }
            onComplete()
        }
    }

    private data class MeasureResult(val blocks: List<Double>, val peak: Float)

    private fun measure(path: String, active: () -> Boolean): MeasureResult? {
        var state: R128State? = null
        val ok = AudioDecoder.decode(
            path,
            onFormat = { sr, ch -> state = R128State(sr, ch) },
            onPcm = { pcm, len -> state?.add(pcm, len) },
            isCancelled = { !active() },
        )
        if (!ok) return null
        return state?.let { it.finish(); MeasureResult(it.blockEnergies, it.peak) }
    }

    private fun integrated(blocks: List<Double>): Double? {
        val absGated = blocks.filter { it > 0 && OFFSET + 10 * log10(it) > ABSOLUTE_GATE_LUFS }
        if (absGated.isEmpty()) return null
        val relThreshold = OFFSET + 10 * log10(absGated.average()) - 10.0
        val relGated = absGated.filter { OFFSET + 10 * log10(it) > relThreshold }
        if (relGated.isEmpty()) return null
        return OFFSET + 10 * log10(relGated.average())
    }

    // EBU 3342-style loudness range: P95-P10 of abs-gated block loudnesses
    private fun loudnessRange(blocks: List<Double>): Double {
        val lufs = blocks
            .filter { it > 0 }
            .map { OFFSET + 10 * log10(it) }
            .filter { it > ABSOLUTE_GATE_LUFS }
            .sorted()
        if (lufs.size < 4) return 0.0
        fun pct(p: Double): Double {
            val idx = (p / 100.0 * (lufs.size - 1)).coerceIn(0.0, (lufs.size - 1).toDouble())
            val lo = idx.toInt(); val hi = (lo + 1).coerceAtMost(lufs.size - 1)
            return lufs[lo] + (lufs[hi] - lufs[lo]) * (idx - lo)
        }
        return (pct(95.0) - pct(10.0)).coerceAtLeast(0.0)
    }
}

private class R128State(private val sampleRate: Int, private val channels: Int) {
    private val filters = Array(channels) { KWeightingFilter(sampleRate) }
    private val weights = DoubleArray(channels) { if (it == 3 || it == 4) 1.41 else 1.0 }
    private val subblockSamples = (sampleRate / 10).coerceAtLeast(1)
    private val channelSumSq = DoubleArray(channels)
    private var sampleCount = 0
    private val recentSubblocks = ArrayDeque<DoubleArray>()
    val blockEnergies = ArrayList<Double>()
    // sample peak of the raw (unkweighted) input: true-peak approximation
    var peak = 0f
        private set

    fun add(pcm: ShortArray, length: Int) {
        var i = 0
        while (i + channels <= length) {
            for (ch in 0 until channels) {
                val raw = pcm[i + ch] / 32768.0
                val a = kotlin.math.abs(raw).toFloat()
                if (a > peak) peak = a
                val y = filters[ch].process(raw)
                channelSumSq[ch] += y * y
            }
            i += channels
            if (++sampleCount >= subblockSamples) finishSubblock()
        }
    }

    private fun finishSubblock() {
        recentSubblocks.addLast(channelSumSq.copyOf())
        if (recentSubblocks.size > 4) recentSubblocks.removeFirst()
        if (recentSubblocks.size == 4) {
            var z = 0.0
            for (ch in 0 until channels) {
                var sum = 0.0
                for (sb in recentSubblocks) sum += sb[ch]
                val meanSquare = sum / (subblockSamples * 4.0)
                z += weights[ch] * meanSquare
            }
            if (z > 0) blockEnergies.add(z)
        }
        channelSumSq.fill(0.0)
        sampleCount = 0
    }

    fun finish() {
        if (sampleCount > 0 && recentSubblocks.size >= 3) finishSubblock()
    }
}
