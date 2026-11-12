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
    /** 收到第一段数字静音的时间；发送端暂停时可能会继续推空包。 */
    @Volatile private var silenceSinceMs = 0L
    /** 发送端（iOS）自己的播放状态；暂停完全由发送端控制，本地只跟随。 */
    @Volatile private var senderPaused = false
    @Volatile private var hasVideo = false
    @Volatile private var mirroring = false
    @Volatile private var audioSampleRate = 44100
    @Volatile private var senderLabel = ""
    /** 本地解说 TTS 正在出声：压低接收音频（0.25），而不是暂停。 */
    @Volatile private var introDuck = 1f
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
        AirPlayDiagnostics.init(appContext)
        AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, TAG, "AirPlay receiver started on $actualPort")
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
        AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, TAG, "AirPlay receiver stopped")
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

    /** 本地解说 TTS 的压低开关；只影响音频输出，不改发送端音量。 */
    fun setIntroDuck(ducked: Boolean) {
        introDuck = if (ducked) 0.25f else 1f
        audioOutput?.setDuckFactor(introDuck)
    }

    /** 设置页修改 AirPlay 缓冲 / 重传 / PLC 后调用；重传参数在接收线程实时读取。 */
    fun applyTuning() {
        audioOutput?.applyTuning(AirPlayTuning.config)
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
            AirPlayDiagnostics.noteSender(name, mirroring)
            AirPlayDiagnostics.log(
                AirPlayDiagnostics.Level.INFO,
                TAG,
                "sender identified: name=$name mirroring=$mirroring",
            )
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

        override fun onAudioKeysChanged(key: ByteArray, iv: ByteArray) {
            // 换 key 不重建流：发送端还在往同一对端口发包，只是密文换了。
            val receiver = audioReceiver
            if (receiver != null) {
                receiver.updateKeys(key, iv)
            } else {
                AirPlayDiagnostics.log(
                    AirPlayDiagnostics.Level.WARN,
                    TAG,
                    "FairPlay key replaced but no audio stream is running yet",
                )
            }
        }

        override fun onMirrorStream(streamConnectionId: Long, aesKey: ByteArray, aesIv: ByteArray): Int {
            startVideo(streamConnectionId, aesKey)
            return videoReceiver?.localPort ?: 0
        }

        override fun onRecord() {
            markPlaying()
        }

        override fun onPause() {
            markPaused()
        }

        override fun onFlush() {
            AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, TAG, "FLUSH from sender")
            // RAOP senders also signal a pause by flushing the stream.
            markPaused()
            audioReceiver?.flush()
            audioOutput?.flush()
        }

        override fun onTeardown() {
            AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, TAG, "TEARDOWN from sender")
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
                AirPlayDiagnostics.log(
                    AirPlayDiagnostics.Level.DEBUG,
                    TAG,
                    "metadata: ${media.title} / ${media.artist} / ${media.album}",
                )
                listener?.onMediaChanged(s, media)
            }
            listener?.onProgress(s, pendingInfoMs, pendingDurationMs, !senderPaused)
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
            listener?.onProgress(s, pendingInfoMs, pendingDurationMs, !senderPaused)
        }
    }

    /**
     * 播放状态以发送端为准：RECORD / 恢复收包表示播放，PAUSE / FLUSH 表示暂停。
     * 本地只更新状态，不暂停自己的音频输出（pause 只是把 DACP 命令发给 iOS）。
     */
    private fun markPlaying() {
        val s = session ?: return
        silenceSinceMs = 0L
        if (!senderPaused) {
            // 已经在播放，只需刷新进度，避免每个音频包都回调。
            listener?.onProgress(s, pendingInfoMs, pendingDurationMs, true)
            return
        }
        senderPaused = false
        lastAudioPacketMs = System.currentTimeMillis()
        AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, TAG, "sender resumed playback")
        // 发送端恢复时确保本地输出没被音频焦点等外部原因卡在暂停状态。
        if (audioOutput?.isPaused == true) audioOutput?.resume()
        listener?.onProgress(s, pendingInfoMs, pendingDurationMs, true)
    }

    private fun markPaused() {
        val s = session ?: return
        if (senderPaused) return
        senderPaused = true
        AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, TAG, "sender paused playback")
        listener?.onProgress(s, pendingInfoMs, pendingDurationMs, false)
    }

    /** 16-bit PCM 里是否存在非零样本（用于识别发送端暂停时的数字静音）。 */
    private fun hasAudiblePcm(pcm: ByteArray, length: Int): Boolean {
        var i = 0
        while (i + 1 < length) {
            if (pcm[i].toInt() != 0 || pcm[i + 1].toInt() != 0) return true
            i += 2
        }
        return false
    }

    // ---------------------------------------------------------------- audio

    private fun startAudio(format: AirPlayAudioFormat, data: DatagramSocket, control: DatagramSocket) {
        Log.i(
            TAG,
            "audio stream: codec=${format.codec} rate=${format.sampleRate} ch=${format.channels} " +
                "encrypted=${format.encrypted} alac=${format.alacConfig} " +
                "key=${format.aesKey.hex()} iv=${format.aesIv.hex()}",
        )
        AirPlayDiagnostics.log(
            AirPlayDiagnostics.Level.INFO,
            TAG,
            "audio stream: codec=${format.codec} rate=${format.sampleRate} ch=${format.channels} " +
                "encrypted=${format.encrypted} ptime=${format.ptimeMs}",
        )
        endAudio()
        senderPaused = false
        silenceSinceMs = 0L
        AirPlayDiagnostics.sessionStarted(
            sender = senderLabel,
            codec = format.codec.name,
            sampleRate = format.sampleRate,
            channels = format.channels,
            encrypted = format.encrypted,
            mirroring = mirroring,
        )
        val output = AirPlayAudioOutput(appContext, sinkFactory(), audioSessionId) {
            // Audio focus lost: keep the session but stop output.
        }
        output.start(format.sampleRate, format.channels)
        output.setDuckFactor(introDuck)
        audioOutput = output
        lastAudioPacketMs = System.currentTimeMillis()
        val receiver = AirPlayAudioReceiver(
            format = format,
            onPcm = { pcm, length, ptsNs ->
                val now = System.currentTimeMillis()
                val packetGap = if (lastAudioPacketMs > 0) now - lastAudioPacketMs else Long.MAX_VALUE
                lastAudioPacketMs = now
                if (senderPaused && packetGap >= PAUSE_DETECT_MS) markPlaying()
                if (hasAudiblePcm(pcm, length)) {
                    silenceSinceMs = 0L
                    if (senderPaused) markPlaying()
                } else if (silenceSinceMs == 0L) {
                    silenceSinceMs = now
                }
                output.writePcm(pcm, length, ptsNs)
            },
            onFirstPacket = {
                session?.let { s ->
                    listener?.onProgress(s, 0, pendingDurationMs, !senderPaused)
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
        AirPlayDiagnostics.sessionEnded("audio output released")
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
        AirPlayDiagnostics.log(
            AirPlayDiagnostics.Level.INFO,
            TAG,
            "mirroring video listening on ${receiver.localPort}",
        )
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
        senderPaused = false
        silenceSinceMs = 0L
        introDuck = 1f
        AirPlayDiagnostics.sessionEnded("session torn down")
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
                delay(1_000)
                session ?: continue
                if (audioOutput == null) continue
                val now = System.currentTimeMillis()
                val silentPacketsMs = if (lastAudioPacketMs > 0) now - lastAudioPacketMs else 0L
                val digitalSilenceMs = if (silenceSinceMs > 0) now - silenceSinceMs else 0L
                // iOS 暂停时有时直接停发 RTP，有时继续推数字静音包；两种都要
                // 当成暂停，否则 UI 会一直显示播放，超时还会把会话销毁。
                if (!senderPaused &&
                    (silentPacketsMs >= PAUSE_DETECT_MS || digitalSilenceMs >= SILENCE_DETECT_MS)
                ) {
                    markPaused()
                }
                // A paused sender keeps the RTSP session alive; only clean up
                // after a long idle period. Unpaused silence is a stalled stream.
                val idleMs = maxOf(silentPacketsMs, digitalSilenceMs)
                val timeout = if (senderPaused) PAUSED_STALE_MS else STALL_TIMEOUT_MS
                if (idleMs > timeout) {
                    AirPlayDiagnostics.log(
                        AirPlayDiagnostics.Level.WARN,
                        TAG,
                        "no audio for ${idleMs}ms, ending AirPlay session",
                    )
                    Log.i(TAG, "no audio for ${idleMs}ms, ending AirPlay session")
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
            // 只驱动发送端；本地不恢复“虚拟暂停”的音频，只在输出被焦点等
            // 外部原因卡住时重新拉起。
            dacp?.play()
            senderPaused = false
            silenceSinceMs = 0L
            lastAudioPacketMs = System.currentTimeMillis()
            if (audioOutput?.isPaused == true) audioOutput?.resume()
            listener?.onProgress(this, pendingInfoMs, pendingDurationMs, true)
        }

        override fun pause() {
            // 不做本地虚拟暂停：只把暂停命令发给 iOS，并立即把 UI 状态
            // 置为暂停（iOS 收到 DACP 后会自行暂停）。
            dacp?.pause()
            markPaused()
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

        /** 持续没有音频包多久后认为发送端已暂停。 */
        private const val PAUSE_DETECT_MS = 2_000L

        /** 连续数字静音多久后认为发送端已暂停（有些发送端暂停时仍推空包）。 */
        private const val SILENCE_DETECT_MS = 2_500L

        /** 未暂停但完全收不到音频：视为流已断，结束会话。 */
        private const val STALL_TIMEOUT_MS = 45_000L

        /** 发送端暂停后仍保留会话（可随时恢复），长时间无活动才清理。 */
        private const val PAUSED_STALE_MS = 30 * 60_000L

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
