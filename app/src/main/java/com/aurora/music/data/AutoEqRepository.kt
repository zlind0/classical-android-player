package com.aurora.music.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.system.Os
import com.aurora.music.playback.DspCoeffBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class EqDeviceKind(val label: String, val description: String, val examples: List<String>) {
    ALL("All devices", "Measured presets for wired and Bluetooth headphones and earbuds.", listOf("Sony WH-1000XM5", "AirPods", "HD 600", "HD 650")),
    HEADPHONES("Headphones / studio", "Over-ear and on-ear headphones, including studio and Bluetooth models.", listOf("HD 600", "ATH-M50x", "DT 770", "WH-1000XM5")),
    IN_EAR("IEMs / wireless buds", "In-ear monitors and sealed wireless earbuds. Match the model and ANC mode.", listOf("AirPods Pro", "Galaxy Buds", "Moondrop", "WF-1000XM5")),
    EARBUDS("Earbuds / open-ear", "Unsealed earbuds and measured open-ear models.", listOf("OpenFit", "OpenRun", "EarPods", "Yuin PK1")),
}

data class EqProfile(
    val id: Long,
    val name: String,
    val source: String,
    /** AutoEq measurement form factor, e.g. "in-ear", "over-ear", "711 in-ear". */
    val form: String,
    /** Relative path inside the AutoEq results tree, kept for display/dedupe. */
    val path: String,
) {
    // AutoEq records the actual measurement form factor, including the rig name.
    val kind: EqDeviceKind get() = when {
        form.contains("in-ear", ignoreCase = true) -> EqDeviceKind.IN_EAR
        form.contains("earbud", ignoreCase = true) -> EqDeviceKind.EARBUDS
        else -> EqDeviceKind.HEADPHONES
    }
}

data class ParsedEq(val preampDb: Float, val bands: List<ParamBand>)

/** One index row: display info plus where its filter text lives in the blobs asset. */
internal data class EqIndexEntry(val profile: EqProfile, val blobOffset: Long, val blobLen: Int)

/** Index decoder (see scripts/build_autoeq_pack.py). Pure function, unit-tested. */
internal fun parseAutoEqIndex(bytes: ByteArray): List<EqIndexEntry> {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(buf.remaining() >= 8) { "index too short" }
    val magic = ByteArray(4).also { buf.get(it) }
    require(magic.contentEquals(INDEX_MAGIC)) { "bad index magic" }
    val count = buf.int
    require(count in 1..200_000) { "bad entry count $count" }
    val out = ArrayList<EqIndexEntry>(count)
    repeat(count) {
        val id = buf.int.toLong() and 0xFFFFFFFFL
        val name = getPackedString(buf)
        val source = getPackedString(buf)
        val form = getPackedString(buf)
        val path = getPackedString(buf)
        val offset = buf.long
        val len = buf.int
        require(offset >= 0 && len in 1..1_000_000) { "bad blob span" }
        out.add(EqIndexEntry(EqProfile(id, name, source, form, path), offset, len))
    }
    return out
}

private val INDEX_MAGIC = byteArrayOf(0x41, 0x45, 0x51, 0x32) // "AEQ2"

private fun getPackedString(buf: ByteBuffer): String {
    val len = buf.int
    require(len in 0..1_000_000 && len <= buf.remaining()) { "bad string length $len" }
    return ByteArray(len).also { buf.get(it) }.toString(Charsets.UTF_8)
}

/**
 * Offline AutoEq preset library. Build-time generated assets
 * (`autoeq_index.aeq` + `autoeq_blobs.aeq`, see scripts/build_autoeq_pack.py)
 * ship inside the APK; the blobs file is stored uncompressed so single presets
 * are read on demand with positioned reads straight from the APK.
 *
 * Nothing is held when the browser UI is closed: the caller pairs [acquire]
 * with [release] (or relies on the one-shot fallback in [search]/[fetch]),
 * and leaving drops the index, the APK file handle and any fallback bytes.
 * No network access happens here.
 */
class AutoEqRepository(private val context: Context) {

    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    private val guard = Any()
    private var refs = 0
    private var index: List<EqIndexEntry>? = null
    private var blobsFd: AssetFileDescriptor? = null
    private var blobsFallback: ByteArray? = null

    /** Browser session start. Pairs with [release]; safe to nest. */
    suspend fun acquire(): List<EqProfile> = withContext(Dispatchers.IO) {
        synchronized(guard) { refs++ }
        try {
            loadLocked().map { it.profile }
        } catch (t: Throwable) {
            synchronized(guard) { refs = (refs - 1).coerceAtLeast(0) }
            throw t
        }
    }

    /** Browser session end. Drops the index and closes the blobs handle. */
    fun release() {
        synchronized(guard) {
            refs = (refs - 1).coerceAtLeast(0)
            if (refs == 0) {
                index = null
                blobsFallback = null
                runCatching { blobsFd?.close() }
                blobsFd = null
            }
        }
    }

    suspend fun search(query: String, kind: EqDeviceKind = EqDeviceKind.ALL, limit: Int = Int.MAX_VALUE): List<EqProfile> =
        withEntries { entries ->
            val q = query.trim().lowercase(Locale.ROOT)
            val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
            val matches = entries.asSequence().map { it.profile }
                .filter { kind == EqDeviceKind.ALL || it.kind == kind }
                .filter { p -> terms.all { term -> p.name.lowercase(Locale.ROOT).contains(term) } }
                .sortedWith(compareByDescending<EqProfile> { p ->
                    if (q.isBlank()) kind.examples.any { p.name.contains(it, ignoreCase = true) }
                    else p.name.startsWith(q, ignoreCase = true)
                }.thenBy { if (q.isBlank()) it.name.lowercase(Locale.ROOT) else "" }.thenBy { it.name.length })
                .distinctBy { it.name.lowercase(Locale.ROOT) + "|" + it.source.lowercase(Locale.ROOT) }
                .toList()
            // Give each example a visible starting point instead of filling the first page with one model's rigs.
            val featured = if (q.isBlank()) kind.examples.mapNotNull { example ->
                matches.filter { it.name.contains(example, ignoreCase = true) }.minByOrNull { it.name.length }
            } else emptyList()
            (featured + matches).distinctBy { it.path }.take(limit)
        }

    suspend fun fetch(profile: EqProfile): ParsedEq? = withEntries { entries ->
        val e = entries.firstOrNull { it.profile.id == profile.id } ?: return@withEntries null
        EqTextParser.parse(readBlob(e.blobOffset, e.blobLen) ?: return@withEntries null)
    }

    /** Uses the session cache when held, otherwise loads for this call only. */
    private suspend fun <T> withEntries(block: (List<EqIndexEntry>) -> T): T {
        if (synchronized(guard) { refs > 0 }) {
            return block(synchronized(guard) { index } ?: emptyList())
        }
        return try {
            synchronized(guard) { refs++ }
            block(loadLocked())
        } finally {
            release()
        }
    }

    private suspend fun loadLocked(): List<EqIndexEntry> = loadMutex.withLock {
        synchronized(guard) { index }?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            appContext.assets.open(INDEX_ASSET).use { parseAutoEqIndex(it.readBytes()) }
        }
        synchronized(guard) { index = loaded }
        loaded
    }

    private fun assetBytes(name: String): ByteArray =
        appContext.assets.open(name).use { it.readBytes() }

    /** Positioned read of one preset straight from the APK; null when out of range. */
    private fun readBlob(offset: Long, len: Int): String? {
        if (len <= 0 || len > 1_000_000 || offset < 0) return null
        blobsAfd()?.let { afd ->
            return runCatching {
                val buf = ByteBuffer.allocate(len)
                var remaining = len
                var pos = afd.startOffset + offset
                while (remaining > 0) {
                    val n = Os.pread(afd.fileDescriptor, buf, pos)
                    if (n <= 0) return null
                    remaining -= n
                    pos += n
                }
                buf.flip()
                ByteArray(len).also { buf.get(it) }.toString(Charsets.UTF_8)
            }.getOrNull()
        }
        // Fallback for a compressed blobs asset: keep the whole file for this session only.
        val all = synchronized(guard) { blobsFallback } ?: runCatching { assetBytes(BLOBS_ASSET) }.getOrNull()?.also {
            synchronized(guard) { if (refs > 0) blobsFallback = it }
        } ?: return null
        if (offset + len > all.size) return null
        return all.copyOfRange(offset.toInt(), (offset + len).toInt()).toString(Charsets.UTF_8)
    }

    private fun blobsAfd(): AssetFileDescriptor? {
        synchronized(guard) {
            blobsFd?.let { return it }
            if (refs == 0) return null
            return runCatching { appContext.assets.openFd(BLOBS_ASSET) }.getOrNull()?.also { blobsFd = it }
        }
    }

    private companion object {
        const val INDEX_ASSET = "autoeq_index.aeq"
        const val BLOBS_ASSET = "autoeq_blobs.aeq"
    }
}

/** Equalizer APO parametric text used by the bundled AutoEq presets. */
internal object EqTextParser {
    fun parse(text: String): ParsedEq? {
        var preamp = 0f
        val bands = ArrayList<ParamBand>()
        val number = "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))"
        val filter = Regex("^Filter\\s+\\d+:\\s+ON\\s+(\\S+)\\s+Fc\\s+$number\\s+Hz\\s+Gain\\s+$number\\s+dB\\s+Q\\s+$number(?:\\s.*)?$", RegexOption.IGNORE_CASE)
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            when {
                t.startsWith("Preamp", true) -> {
                    preamp = Regex("^Preamp:\\s*$number\\s+dB", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
                    if (!preamp.isFinite()) return null
                }
                t.startsWith("Filter", true) && Regex("\\bON\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> {
                    // Never silently drop unsupported filters or positive gains from a correction.
                    val match = filter.matchEntire(t) ?: return null
                    val type = when (match.groupValues[1].uppercase(Locale.ROOT)) {
                        "LSC", "LS" -> BandType.LOW_SHELF
                        "HSC", "HS" -> BandType.HIGH_SHELF
                        "PK" -> BandType.PEAK
                        else -> return null
                    }
                    val fc = match.groupValues[2].toFloatOrNull() ?: return null
                    val gain = match.groupValues[3].toFloatOrNull() ?: return null
                    val q = match.groupValues[4].toFloatOrNull() ?: return null
                    if (!fc.isFinite() || !gain.isFinite() || !q.isFinite() || fc <= 0f || q < 0.1f) return null
                    bands.add(ParamBand(fc, gain, q, type))
                }
            }
        }
        return if (bands.isEmpty() || bands.size > DspCoeffBuilder.MAX_PARAMETRIC) null else ParsedEq(preamp, bands)
    }
}
