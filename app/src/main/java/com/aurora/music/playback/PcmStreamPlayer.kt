package com.aurora.music.playback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Direct PCM driver for RTP protocols (plan §5.4).
 *
 * We deliberately do not write an AudioTrack loop: [androidx.media3.exoplayer.audio.DefaultAudioSink]
 * already has a playback thread and blocks in [AudioSink.handleBuffer] when its
 * buffer is full, which gives us pacing for free and keeps the DSP chain
 * byte-for-byte identical to the music path.
 *
 * The sink must come from [DspAudioChain.createSink] (processors + visualizer tap).
 */
@UnstableApi
class PcmStreamPlayer(
    context: Context,
    private val sink: AudioSink,
    private val sessionId: Int,
    private val onFocusLoss: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var sampleRate = 44100
    private var started = false
    private var paused = false
    private var framesWritten = 0L
    private var directBuffer: ByteBuffer? = null
    private var focusHeld = false
    private var focusRequest: AudioFocusRequest? = null

    private val sinkListener = object : AudioSink.Listener {
        override fun onPositionDiscontinuity() = Unit
        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
    }

    init {
        runCatching { sink.setListener(sinkListener) }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                paused = true
                runCatching { sink.pause() }
                if (change == AudioManager.AUDIOFOCUS_LOSS) onFocusLoss()
            }
        }
    }

    fun start(sampleRate: Int, channels: Int = 2) {
        if (started && this.sampleRate == sampleRate) return
        if (started) runCatching { sink.reset() }
        this.sampleRate = sampleRate.coerceAtLeast(8000)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(this.sampleRate)
            .setChannelCount(channels)
            .build()
        runCatching { sink.setAudioSessionId(sessionId) }
        runCatching {
            sink.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
            )
        }
        sink.configure(format, 0, null)
        sink.play()
        started = true
        paused = false
        framesWritten = 0L
        requestFocus()
    }

    fun writePcm(pcm: ByteArray, length: Int = pcm.size, channels: Int = 2) {
        if (!started || paused || length <= 0) return
        val frames = length / (2 * channels)
        if (frames <= 0) return
        val buffer = directBuffer?.takeIf { it.capacity() >= length }
            ?: ByteBuffer.allocateDirect(maxOf(length, 8192))
                .order(ByteOrder.nativeOrder())
                .also { directBuffer = it }
        buffer.clear()
        buffer.put(pcm, 0, length)
        buffer.flip()
        val ptsUs = framesWritten * 1_000_000L / sampleRate
        runCatching { sink.handleBuffer(buffer, ptsUs, 1) }
            .onFailure { Log.w(TAG, "sink write failed: ${it.message}") }
        framesWritten += frames
    }

    fun writeSilence(frames: Int, channels: Int = 2) {
        if (frames <= 0) return
        writePcm(ByteArray(frames * 2 * channels), frames * 2 * channels, channels)
    }

    fun pause() {
        paused = true
        runCatching { sink.pause() }
    }

    fun resume() {
        if (!started) return
        // AirPlay keeps pushing while we are paused: jump to the newest packet.
        runCatching { sink.flush() }
        paused = false
        runCatching { sink.play() }
        requestFocus()
    }

    fun setVolume(volume: Float) {
        runCatching { sink.setVolume(volume.coerceIn(0f, 1f)) }
    }

    val isPaused: Boolean get() = paused

    fun stop() {
        started = false
        paused = false
        runCatching { sink.pause() }
        runCatching { sink.flush() }
        runCatching { sink.reset() }
        abandonFocus()
    }

    fun release() {
        stop()
        runCatching { sink.release() }
    }

    private fun requestFocus() {
        if (focusHeld) return
        val res = if (Build.VERSION.SDK_INT >= 26) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        focusHeld = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!focusHeld && focusRequest == null) return
        focusHeld = false
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
        focusRequest = null
    }

    companion object {
        private const val TAG = "PcmStreamPlayer"
    }
}
