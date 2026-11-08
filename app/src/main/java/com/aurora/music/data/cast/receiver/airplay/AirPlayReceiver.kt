package com.aurora.music.data.cast.receiver.airplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.aurora.music.data.cast.receiver.CastCapabilities
import com.aurora.music.data.cast.receiver.CastMediaInfo
import com.aurora.music.data.cast.receiver.CastProtocol
import com.aurora.music.data.cast.receiver.CastProtocolReceiver
import com.aurora.music.data.cast.receiver.CastProtocolSession
import com.aurora.music.data.cast.receiver.CastSessionArbiter
import com.aurora.music.data.cast.receiver.CastVideoInfo
import com.aurora.music.data.cast.receiver.video.CastVideoListener
import com.aurora.music.data.cast.receiver.video.MediaCodecVideoSink
import java.io.File
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AirPlay 1 receiver: RAOP audio plus screen mirroring (H.264 + AAC-ELD/ALAC).
 *
 * The RTSP server owns protocol negotiation and hands sockets to this class;
 * audio is decoded into the shared DSP chain via [AirPlayAudioOutput] (timeline
 * jitter buffer + PcmStreamPlayer), video into [MediaCodecVideoSink] which
 * renders straight to a Surface through a persistent GL pipeline.
 */
@UnstableApi
class AirPlayReceiver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val deviceId: String,
    private val audioSessionId: Int,
    private val sinkFactory: () -> AudioSink,
) : CastProtocolReceiver {

    override val protocol: CastProtocol = CastProtocol.AIRPLAY

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private var listener: CastProtocolReceiver.Listener? = null

    private var mdns: AirPlayMdns? = null
    private var server: AirPlayRtspServer? = null
    private var session: AirPlaySession? = null
    private var audioReceiver: AirPlayAudioReceiver? = null
    private var audioOutput: AirPlayAudioOutput? = null
    private var dacp: AirPlayDacp? = null
    private var videoReceiver: AirPlayVideoReceiver? = null
    private var videoSink: MediaCodecVideoSink? = null
    private var videoSurface: Surface? = null
    private var watchdog: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var lastAudioPacketMs = 0L
    @Volatile private var pausedByUser = false
    @Volatile private var hasVideo = false
    @Volatile private var mirroring = false
    @Volatile private var audioSampleRate = 44100
    @Volatile private var senderLabel = ""
    private var pendingInfoMs = 0L
    private var pendingDurationMs = 0L
    private var lastMedia = CastMediaInfo()
    private var artworkPath = ""

    val port: Int get() = server?.port ?: 0

    override fun start(listener: CastProtocolReceiver.Listener) {
        if (!running.compareAndSet(false, true)) return
        this.listener = listener
        val rtsp = AirPlayRtspServer(deviceName, deviceId, 5000, RtspListener())
        server = rtsp
        val actualPort = rtsp.start()
        mdns = AirPlayMdns(appContext, deviceName, deviceId, actualPort).also { it.start() }
        dacp = AirPlayDacp(appContext) { ready ->
            val s = session
            if (s != null) listener?.onCapabilities(s, s.capabilities)
            Log.i(TAG, "DACP ready=$ready")
        }
        registerNetworkCallback()
        startWatchdog()
        Log.i(TAG, "AirPlay receiver started on $actualPort")
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        watchdog?.cancel()
        watchdog = null
        unregisterNetworkCallback()
        mdns?.stop()
        mdns = null
        dacp?.release()
        dacp = null
        endSessionInternal(notify = false)
        server?.stop()
        server = null
        listener = null
    }

    override fun stopSession() {
        if (!running.get()) return
        endSessionInternal(notify = true)
        server?.stopSession()
    }

    override fun setVideoSurface(surface: Surface?) {
        videoSurface = surface
        videoSink?.setSurface(surface)
    }

    // ------------------------------------------------------------- RTSP events

    private inner class RtspListener : AirPlayRtspServer.Listener {
        override fun displaySize(): Pair<Int, Int> {
            val metrics = appContext.resources.displayMetrics
            return metrics.widthPixels to metrics.heightPixels
        }

        override fun onSenderIdentified(name: String, mirroring: Boolean) {
            senderLabel = name
            this@AirPlayReceiver.mirroring = mirroring
            val current = session
            if (current == null) {
                val s = AirPlaySession(name, mirroring)
                session = s
                listener?.onCapabilities(s, s.capabilities)
                listener?.onSessionStarted(s)
            }
        }

        override fun onAudioStream(
            format: AirPlayAudioFormat,
            data: DatagramSocket,
            control: DatagramSocket,
        ) {
            startAudio(format, data, control)
        }

        override fun onMirrorStream(streamConnectionId: Long, aesKey: ByteArray, aesIv: ByteArray): Int {
            startVideo(streamConnectionId, aesKey)
            return videoReceiver?.localPort ?: 0
        }

        override fun onRecord() {
            val s = session ?: return
            listener?.onProgress(s, pendingInfoMs, pendingDurationMs, true)
        }

        override fun onFlush() {
            audioReceiver?.flush()
            audioOutput?.flush()
        }

        override fun onTeardown() {
            endSessionInternal(notify = true)
        }

        override fun onMetadata(nowPlaying: AirPlayNowPlaying) {
            val s = session ?: return
            if (nowPlaying.durationMs > 0) pendingDurationMs = nowPlaying.durationMs
            if (nowPlaying.progressEnd > 0) {
                val rate = audioSampleRate.coerceAtLeast(8000)
                pendingDurationMs = nowPlaying.progressEnd * 1000L / rate
                pendingInfoMs = (nowPlaying.progressCurrent - nowPlaying.progressStart) * 1000L / rate
            }
            nowPlaying.artwork?.let { saveArtwork(it) }
            val media = CastMediaInfo(
                id = "airplay:${senderLabel.hashCode()}",
                title = nowPlaying.title.ifBlank { lastMedia.title },
                artist = nowPlaying.artist.ifBlank { lastMedia.artist },
                album = nowPlaying.album.ifBlank { lastMedia.album },
                artworkUri = artworkPath,
            )
            if (media != lastMedia) {
                lastMedia = media
                listener?.onMediaChanged(s, media)
            }
            listener?.onProgress(s, pendingInfoMs, pendingDurationMs, true)
        }

        override fun onVolume(volume: Float) {
            audioOutput?.setVolume(volume)
        }

        override fun onDacpId(dacpId: String, activeRemote: String) {
            dacp?.update(dacpId, activeRemote)
            val s = session
            if (s != null) listener?.onCapabilities(s, s.capabilities)
        }

        override fun onProgress(positionMs: Long, durationMs: Long) {
            val s = session ?: return
            pendingInfoMs = positionMs
            if (durationMs > 0) pendingDurationMs = durationMs
            listener?.onProgress(s, pendingInfoMs, pendingDurationMs, true)
        }
    }

    // ---------------------------------------------------------------- audio

    private fun startAudio(format: AirPlayAudioFormat, data: DatagramSocket, control: DatagramSocket) {
        Log.i(
            TAG,
            "audio stream: codec=${format.codec} rate=${format.sampleRate} ch=${format.channels} " +
                "encrypted=${format.encrypted} alac=${format.alacConfig} " +
                "key=${format.aesKey.hex()} iv=${format.aesIv.hex()}",
        )
        endAudio()
        pausedByUser = false
        val output = AirPlayAudioOutput(appContext, sinkFactory(), audioSessionId) {
            // Audio focus lost: keep the session but stop output.
        }
        output.start(format.sampleRate, format.channels)
        audioOutput = output
        lastAudioPacketMs = System.currentTimeMillis()
        val receiver = AirPlayAudioReceiver(
            format = format,
            onPcm = { pcm, length, ptsNs ->
                lastAudioPacketMs = System.currentTimeMillis()
                output.writePcm(pcm, length, ptsNs)
            },
            onFirstPacket = {
                session?.let { s ->
                    listener?.onProgress(s, 0, pendingDurationMs, true)
                }
            },
            onError = { Log.w(TAG, "audio stream error: $it") },
        )
        audioReceiver = receiver
        audioSampleRate = format.sampleRate
        receiver.start(data, control)
    }

    private fun endAudio() {
        audioReceiver?.stop()
        audioReceiver = null
        audioOutput?.stop()
        audioOutput = null
    }

    // ---------------------------------------------------------------- video

    private fun startVideo(streamConnectionId: Long, aesKey: ByteArray) {
        endVideo()
        hasVideo = true
        val sink = MediaCodecVideoSink(appContext, object : CastVideoListener {
            override fun onVideoInfo(info: CastVideoInfo) {
                val s = session ?: return
                listener?.onVideo(s, info)
            }

            override fun onVideoError(message: String) {
                Log.w(TAG, "video decoder: $message")
            }
        }).also { it.setSurface(videoSurface) }
        videoSink = sink
        val receiver = AirPlayVideoReceiver(
            streamConnectionId = streamConnectionId,
            audioAesKey = aesKey,
            sink = sink,
            onFrameSize = { width, height ->
                Log.i(TAG, "video size: ${width}x$height")
            },
            onError = { Log.w(TAG, "video stream error: $it") },
        )
        receiver.start(0)
        videoReceiver = receiver
        Log.i(TAG, "mirroring video listening on ${receiver.localPort}")
    }

    private fun endVideo() {
        videoReceiver?.stop()
        videoReceiver = null
        videoSink?.release()
        videoSink = null
        hasVideo = false
    }

    // -------------------------------------------------------------- lifecycle

    private fun endSessionInternal(notify: Boolean) {
        val current = session
        session = null
        endAudio()
        endVideo()
        dacp?.reset()
        pendingInfoMs = 0
        pendingDurationMs = 0
        lastMedia = CastMediaInfo()
        artworkPath = ""
        senderLabel = ""
        mirroring = false
        pausedByUser = false
        if (notify && current != null) listener?.onSessionEnded(current)
    }

    private fun saveArtwork(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            val dir = File(appContext.filesDir, "cast_art")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "airplay_${Integer.toHexString(bytes.contentHashCode())}.img")
            if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
            artworkPath = "file://${file.absolutePath}"
        }.onFailure { Log.w(TAG, "artwork save failed: ${it.message}") }
    }

    private fun startWatchdog() {
        watchdog = scope.launch {
            while (isActive && running.get()) {
                delay(5_000)
                if (session == null) continue
                if (pausedByUser) continue
                val last = lastAudioPacketMs
                if (last > 0 && System.currentTimeMillis() - last > 45_000) {
                    Log.i(TAG, "no audio for 45s, ending AirPlay session")
                    endSessionInternal(notify = true)
                    server?.stopSession()
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Re-register mDNS after Wi-Fi reconnects; ports stay fixed.
                    val p = port
                    if (p > 0) {
                        mdns?.stop()
                        mdns = AirPlayMdns(appContext, deviceName, deviceId, p).also { it.start() }
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        }.onFailure { Log.w(TAG, "network callback unavailable: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching {
            (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(cb)
        }
    }

    private inner class AirPlaySession(
        override val senderName: String,
        val mirroring: Boolean,
    ) : CastProtocolSession {
        override val protocol: CastProtocol = CastProtocol.AIRPLAY
        override val capabilities: CastCapabilities
            get() = CastCapabilities(
                canPlayPause = true,
                // DACP is the reverse-control channel: only advertise
                // next/previous once the sender's control endpoint resolved.
                canNext = dacp?.isReady == true,
                canPrevious = dacp?.isReady == true,
                canSeek = false,
                canStop = true,
            )

        override fun play() {
            // Control the sender first so it resumes its timeline, then let the
            // local sink jump to the newest packet.
            dacp?.play()
            pausedByUser = false
            lastAudioPacketMs = System.currentTimeMillis()
            audioOutput?.resume()
            listener?.onProgress(this, pendingInfoMs, pendingDurationMs, true)
        }

        override fun pause() {
            dacp?.pause()
            pausedByUser = true
            audioOutput?.pause()
            listener?.onProgress(this, pendingInfoMs, pendingDurationMs, false)
        }

        override fun seekTo(positionMs: Long) = Unit

        override fun next() {
            dacp?.nextItem()
        }

        override fun previous() {
            dacp?.previousItem()
        }

        override fun stop() {
            this@AirPlayReceiver.stopSession()
            listener?.onSessionEnded(this)
        }
    }

    companion object {
        private const val TAG = "AirPlayReceiver"

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
