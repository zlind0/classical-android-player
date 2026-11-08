package com.aurora.music.data.cast.receiver.airplay

/** Text metadata carried by `application/x-dmap-tagged` and `text/parameters`. */
data class AirPlayNowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val artwork: ByteArray? = null,
    val progressStart: Long = 0,
    val progressCurrent: Long = 0,
    val progressEnd: Long = 0,
) {
    val isEmpty: Boolean
        get() = title.isEmpty() && artist.isEmpty() && album.isEmpty() &&
            artwork == null && progressEnd <= 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AirPlayNowPlaying) return false
        return title == other.title && artist == other.artist && album == other.album &&
            durationMs == other.durationMs && artwork.contentEquals(other.artwork) &&
            progressStart == other.progressStart && progressCurrent == other.progressCurrent &&
            progressEnd == other.progressEnd
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        result = 31 * result + progressStart.hashCode()
        result = 31 * result + progressCurrent.hashCode()
        result = 31 * result + progressEnd.hashCode()
        return result
    }
}

/**
 * DMAP (Digital Media Access Protocol) parser used by AirPlay metadata.
 *
 * Wire format: repeated `tag(4) length(4, BE) value`, containers (`mlit`)
 * recursively containing the same format. Only fields the UI needs are kept.
 */
object AirPlayMetadata {

    private const val TAG_ITEM = "mlit"
    private const val TAG_TITLE = "minm"
    private const val TAG_ARTIST = "asar"
    private const val TAG_ALBUM = "asal"
    private const val TAG_DURATION_MS = "astm"
    private const val TAG_ARTWORK = "covr"

    fun parseDmap(data: ByteArray): AirPlayNowPlaying? {
        var offset = 0
        while (offset + 8 <= data.size) {
            val tag = String(data, offset, 4, Charsets.US_ASCII)
            val length = readUInt32(data, offset + 4).toInt()
            offset += 8
            if (length < 0 || offset + length > data.size) break
            if (tag == TAG_ITEM) {
                val nested = parseItems(data, offset, offset + length)
                if (nested != null) return nested
            }
            offset += length
        }
        return null
    }

    private fun parseItems(data: ByteArray, start: Int, end: Int): AirPlayNowPlaying? {
        var title = ""
        var artist = ""
        var album = ""
        var durationMs = 0L
        var artwork: ByteArray? = null
        var offset = start
        var any = false
        while (offset + 8 <= end) {
            val tag = String(data, offset, 4, Charsets.US_ASCII)
            val length = readUInt32(data, offset + 4).toInt()
            offset += 8
            if (length < 0 || offset + length > end) break
            when (tag) {
                TAG_TITLE -> { title = String(data, offset, length, Charsets.UTF_8); any = true }
                TAG_ARTIST -> { artist = String(data, offset, length, Charsets.UTF_8); any = true }
                TAG_ALBUM -> { album = String(data, offset, length, Charsets.UTF_8); any = true }
                TAG_DURATION_MS -> {
                    if (length in 1..8) {
                        durationMs = readUInt(data, offset, length)
                        any = true
                    }
                }
                TAG_ARTWORK -> { artwork = data.copyOfRange(offset, offset + length); any = true }
                TAG_ITEM -> {
                    val nested = parseItems(data, offset, offset + length)
                    if (nested != null) {
                        if (nested.title.isNotEmpty()) title = nested.title
                        if (nested.artist.isNotEmpty()) artist = nested.artist
                        if (nested.album.isNotEmpty()) album = nested.album
                        if (nested.durationMs > 0) durationMs = nested.durationMs
                        if (nested.artwork != null) artwork = nested.artwork
                        any = true
                    }
                }
            }
            offset += length
        }
        return if (any) AirPlayNowPlaying(title, artist, album, durationMs, artwork) else null
    }

    /**
     * `SET_PARAMETER` with `text/parameters`:
     * `title: x`, `artist: x`, `album: x`, `progress: start/current/end`.
     * Returns null when the body carries nothing we understand.
     */
    fun parseTextParameters(text: String): AirPlayNowPlaying? {
        var title = ""
        var artist = ""
        var album = ""
        var start = 0L
        var current = 0L
        var end = 0L
        var any = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "title" -> { title = value; any = true }
                "artist" -> { artist = value; any = true }
                "album" -> { album = value; any = true }
                "progress" -> {
                    val parts = value.split('/')
                    if (parts.size == 3) {
                        start = parts[0].trim().toLongOrNull() ?: 0
                        current = parts[1].trim().toLongOrNull() ?: 0
                        end = parts[2].trim().toLongOrNull() ?: 0
                        any = true
                    }
                }
            }
        }
        return if (any) AirPlayNowPlaying(title, artist, album, 0, null, start, current, end) else null
    }

    /** `volume: -20.5` (dB) -> linear gain, clamped to [0,1]. */
    fun dbToLinear(db: Double): Float =
        Math.pow(10.0, db / 20.0).toFloat().coerceIn(0f, 1f)

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun readUInt(data: ByteArray, offset: Int, length: Int): Long {
        var v = 0L
        for (i in 0 until length) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        return v
    }
}
