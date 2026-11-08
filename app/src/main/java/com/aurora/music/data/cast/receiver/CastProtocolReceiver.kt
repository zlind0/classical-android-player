package com.aurora.music.data.cast.receiver

import android.view.Surface

/**
 * Protocol receiver contract (plan §3.4). The Hub is the only owner of the
 * public state; receivers post events here and never touch the UI.
 */
interface CastProtocolReceiver {
    val protocol: CastProtocol

    fun start(listener: Listener)
    fun stop()

    /** Stop the currently active session (new local playback / other protocol won). */
    fun stopSession()

    /** Attach/detach the UI surface for protocols with video (AirPlay mirroring). */
    fun setVideoSurface(surface: Surface?) = Unit

    interface Listener {
        fun onSessionStarted(session: CastProtocolSession)
        fun onMediaChanged(session: CastProtocolSession, media: CastMediaInfo)
        fun onProgress(session: CastProtocolSession, positionMs: Long, durationMs: Long, playing: Boolean)
        fun onCapabilities(session: CastProtocolSession, capabilities: CastCapabilities)
        fun onVideo(session: CastProtocolSession, info: CastVideoInfo?)
        fun onSessionEnded(session: CastProtocolSession)
    }
}

interface CastProtocolSession {
    val protocol: CastProtocol
    val senderName: String
    val capabilities: CastCapabilities

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun stop()
}
