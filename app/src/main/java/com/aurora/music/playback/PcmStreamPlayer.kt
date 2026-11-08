package com.aurora.music.playback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

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
    @Volatile private var paused = false
    private var framesWritten = 0L
    private var directBuffer: ByteBuffer? = null
    private var focusHeld = false
    private var focusRequest: AudioFocusRequest? = null
    private var firstWriteLogged = false

    /**
     * Media3's DefaultAudioSink captures `Looper.myLooper()` as its playback
     * looper in `configure()` and asserts that AudioCapabilities callbacks run
     * on that same looper. The AirPlay RTP thread has no Looper, so the sink
     * must be created/configured/driven on a dedicated looper thread.
     */
    private val sinkThread = HandlerThread("PcmSink").apply { start() }
    private val sinkHandler = Handler(sinkThread.looper)

    private fun <T> onSink(block: () -> T): T {
        if (Looper.myLooper() === sinkThread.looper) return block()
        val task = FutureTask(Callable { block() })
        if (!sinkHandler.post(task)) throw IllegalStateException("PcmSink thread is not running")
        return try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private val sinkListener = object : AudioSink.Listener {
        override fun onPositionDiscontinuity() = Unit
        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
    }

    init {
        runCatching { onSink { sink.setListener(sinkListener) } }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                paused = true
                // Focus callbacks arrive on the main thread; never block it on
                // the sink thread, just queue the pause behind the current write.
                sinkHandler.post { runCatching { sink.pause() } }
                if (change == AudioManager.AUDIOFOCUS_LOSS) onFocusLoss()
            }
        }
    }

    fun start(sampleRate: Int, channels: Int = 2) {
        if (started && this.sampleRate == sampleRate) return
        if (started) runCatching { onSink { sink.reset() } }
        this.sampleRate = sampleRate.coerceAtLeast(8000)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(this.sampleRate)
            .setChannelCount(channels)
            .build()
        runCatching { onSink { sink.setAudioSessionId(sessionId) } }
        runCatching {
            onSink {
                sink.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                )
            }
        }
        // Two seconds of 16-bit PCM, computed from the actual stream format.
        // A time-based buffer absorbs Wi-Fi jitter without hard-coding a byte
        // count that becomes too small at higher sample rates.
        val bufferBytes = this.sampleRate * channels * 2 * 2
        onSink { sink.configure(format, bufferBytes, null) }
        onSink { sink.play() }
        started = true
        paused = false
        framesWritten = 0L
        firstWriteLogged = false
        Log.i(TAG, "sink started: rate=$sampleRate ch=$channels")
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
        if (!firstWriteLogged) {
            firstWriteLogged = true
            var nonZero = 0
            var i = 0
            while (i + 1 < length && i < 8192) {
                if (pcm[i].toInt() != 0 || pcm[i + 1].toInt() != 0) nonZero++
                i += 2
            }
            Log.i(TAG, "first PCM write: $length bytes, $frames frames, nonZeroSamples=$nonZero")
        }
        val ptsUs = framesWritten * 1_000_000L / sampleRate
        // DefaultAudioSink.handleBuffer is non-blocking: when the AudioTrack is
        // full it returns false and keeps the same ByteBuffer. The contract
        // requires resubmitting that exact buffer until it is fully consumed,
        // so never overwrite it here. Overwriting used to drop the un-consumed
        // tail and produced audible gaps whenever the sink was full.
        var accepted = false
        runCatching {
            while (!accepted && !paused) {
                accepted = onSink { sink.handleBuffer(buffer, ptsUs, 1) }
                if (!accepted) Thread.sleep(1)
            }
        }.onFailure { Log.w(TAG, "sink write failed: ${it.cause?.message ?: it.message}") }
        if (accepted) framesWritten += frames
    }

    fun writeSilence(frames: Int, channels: Int = 2) {
        if (frames <= 0) return
        writePcm(ByteArray(frames * 2 * channels), frames * 2 * channels, channels)
    }

    fun pause() {
        paused = true
        runCatching { onSink { sink.pause() } }
    }

    fun resume() {
        if (!started) return
        // AirPlay keeps pushing while we are paused: jump to the newest packet.
        runCatching { onSink { sink.flush() } }
        framesWritten = 0L
        paused = false
        runCatching { onSink { sink.play() } }
        requestFocus()
    }

    fun setVolume(volume: Float) {
        Log.i(TAG, "setVolume: $volume")
        runCatching { onSink { sink.setVolume(volume.coerceIn(0f, 1f)) } }
    }

    val isPaused: Boolean get() = paused

    fun stop() {
        started = false
        // Break any in-flight handleBuffer retry loop before pausing the sink;
        // otherwise a paused AudioTrack never drains and the loop would spin.
        paused = true
        runCatching { onSink { sink.pause() } }
        runCatching { onSink { sink.flush() } }
        runCatching { onSink { sink.reset() } }
        abandonFocus()
    }

    fun release() {
        stop()
        runCatching { onSink { sink.release() } }
        sinkThread.quitSafely()
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
