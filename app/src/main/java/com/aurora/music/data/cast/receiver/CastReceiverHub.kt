package com.aurora.music.data.cast.receiver

import android.view.Surface
import com.aurora.music.data.cast.receiver.airplay.AirPlayReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the cast receiver state machine (plan §3.1).
 *
 * Only AirPlay is implemented today; the Hub is the single place that knows
 * which session is active so the UI and local playback never race each other.
 */
class CastReceiverHub(
    private val scope: CoroutineScope,
    private val airPlay: AirPlayReceiver,
    /** Called once per new remote session, before any audio is rendered. */
    private val onTakeover: () -> Unit,
) {
    private val _state = MutableStateFlow<CastReceiverState>(CastReceiverState.Idle)
    val state: StateFlow<CastReceiverState> = _state.asStateFlow()

    private val _status = MutableStateFlow(
        CastReceiverStatus(airPlayListening = false, videoListening = true),
    )
    val status: StateFlow<CastReceiverStatus> = _status.asStateFlow()

    private val started = AtomicBoolean(false)
    private var session: CastProtocolSession? = null
    private var media = CastMediaInfo()
    private var capabilities = CastCapabilities()
    private var video: CastVideoInfo? = null
    private var senderName = ""
    private var protocol = CastProtocol.AIRPLAY
    private var positionMs = 0L
    private var durationMs = 0L
    private var playing = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching {
            airPlay.start(object : CastProtocolReceiver.Listener {
                override fun onSessionStarted(session: CastProtocolSession) {
                    this@CastReceiverHub.session = session
                    protocol = session.protocol
                    senderName = session.senderName
                    capabilities = session.capabilities
                    media = CastMediaInfo()
                    video = null
                    positionMs = 0
                    durationMs = 0
                    playing = false
                    _state.value = CastReceiverState.Connecting(protocol, senderName)
                    publishStatus()
                    runCatching { onTakeover() }
                }

                override fun onMediaChanged(session: CastProtocolSession, media: CastMediaInfo) {
                    if (this@CastReceiverHub.session !== session) return
                    this@CastReceiverHub.media = media
                    if (_state.value is CastReceiverState.Playing) publishPlaying()
                    publishStatus()
                }

                override fun onProgress(
                    session: CastProtocolSession,
                    positionMs: Long,
                    durationMs: Long,
                    playing: Boolean,
                ) {
                    if (this@CastReceiverHub.session !== session) return
                    this@CastReceiverHub.positionMs = positionMs
                    if (durationMs > 0) this@CastReceiverHub.durationMs = durationMs
                    this@CastReceiverHub.playing = playing
                    val s = _state.value
                    if (s is CastReceiverState.Playing || playing) publishPlaying()
                }

                override fun onCapabilities(session: CastProtocolSession, capabilities: CastCapabilities) {
                    if (this@CastReceiverHub.session !== session) return
                    this@CastReceiverHub.capabilities = capabilities
                    if (_state.value is CastReceiverState.Playing) publishPlaying()
                }

                override fun onVideo(session: CastProtocolSession, info: CastVideoInfo?) {
                    if (this@CastReceiverHub.session !== session) return
                    video = info
                    if (_state.value is CastReceiverState.Playing) publishPlaying()
                }

                override fun onSessionEnded(session: CastProtocolSession) {
                    if (this@CastReceiverHub.session !== session) return
                    this@CastReceiverHub.session = null
                    media = CastMediaInfo()
                    video = null
                    positionMs = 0
                    durationMs = 0
                    playing = false
                    senderName = ""
                    _state.value = CastReceiverState.Idle
                    publishStatus()
                }
            })
        }.onFailure {
            _status.value = _status.value.copy(
                airPlayListening = false,
                airPlayError = it.message ?: "AirPlay 启动失败",
            )
        }
        _status.value = _status.value.copy(
            airPlayListening = airPlay.port > 0,
            airPlayPort = airPlay.port,
        )
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { airPlay.stop() }
        session = null
        _state.value = CastReceiverState.Idle
        _status.value = CastReceiverStatus()
    }

    fun play() {
        session?.play()
    }

    fun pause() {
        session?.pause()
    }

    fun seekTo(positionMs: Long) {
        session?.seekTo(positionMs)
    }

    fun next() {
        session?.next()
    }

    fun previous() {
        session?.previous()
    }

    /** Ends the active session but keeps listening for the next sender. */
    fun stopSession() {
        runCatching { airPlay.stopSession() }
        session = null
        if (_state.value !is CastReceiverState.Idle) {
            _state.value = CastReceiverState.Idle
            publishStatus()
        }
    }

    /** Stop the current cast because local playback (music/TTS/legacy cast) resumed. */
    fun onLocalPlaybackStarted() {
        if (_state.value !is CastReceiverState.Idle) stopSession()
    }

    fun setVideoSurface(surface: Surface?) {
        runCatching { airPlay.setVideoSurface(surface) }
    }

    /** 本地解说 TTS 期间压低 AirPlay 音频（25%），不暂停发送端。 */
    fun setIntroDuck(ducked: Boolean) {
        runCatching { airPlay.setIntroDuck(ducked) }
    }

    private fun publishPlaying() {
        val s = session ?: return
        _state.value = CastReceiverState.Playing(
            protocol = protocol,
            senderName = senderName,
            media = media,
            positionMs = positionMs,
            durationMs = durationMs,
            playing = playing,
            capabilities = capabilities,
            video = video,
            mirroring = video != null,
        )
        publishStatus()
    }

    private fun publishStatus() {
        val s = _state.value
        _status.value = _status.value.copy(
            airPlayListening = airPlay.port > 0,
            airPlayPort = airPlay.port,
            videoListening = true,
            activeProtocol = when (s) {
                is CastReceiverState.Playing -> s.protocol
                is CastReceiverState.Connecting -> s.protocol
                else -> null
            },
            activeSender = when (s) {
                is CastReceiverState.Playing -> s.senderName
                is CastReceiverState.Connecting -> s.senderName
                else -> ""
            },
            activeTitle = when (s) {
                is CastReceiverState.Playing -> s.media.title
                else -> ""
            },
        )
    }
}
