package com.aurora.music.data.cast.receiver

/**
 * Unified cast-receiver state model (plan §3.3).
 *
 * The AirPlay receiver is the first protocol implementation; Chromecast and
 * Miracast slots stay in the enum so the UI and the arbiter do not need to
 * change when they are added.
 */
enum class CastProtocol(val label: String) {
    AIRPLAY("AirPlay"),
    CHROMECAST("Chromecast"),
    MIRACAST("Miracast"),
    LEGACY("投屏"),
}

sealed interface CastReceiverState {
    data object Idle : CastReceiverState

    data class Connecting(
        val protocol: CastProtocol,
        val senderName: String = "",
    ) : CastReceiverState

    data class Playing(
        val protocol: CastProtocol,
        val senderName: String,
        val media: CastMediaInfo,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val capabilities: CastCapabilities,
        val video: CastVideoInfo? = null,
        /** True while the receiver is rendering the sender's screen. */
        val mirroring: Boolean = video != null,
    ) : CastReceiverState
}

data class CastMediaInfo(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** file://, content:// or http(s)://; consumed by Coil. */
    val artworkUri: String = "",
)

data class CastCapabilities(
    val canPlayPause: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val canStop: Boolean = true,
)

data class CastVideoInfo(
    val codec: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

/** Receiver-side status shown in Settings; never gates receiving. */
data class CastReceiverStatus(
    val airPlayListening: Boolean = false,
    val airPlayPort: Int = 0,
    val airPlayError: String = "",
    val videoListening: Boolean = false,
    val activeProtocol: CastProtocol? = null,
    val activeSender: String = "",
    val activeTitle: String = "",
)
