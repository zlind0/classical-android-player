package com.aurora.music.data.cast.receiver.airplay.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * MediaCodec decoder + scheduled rendering for AirPlay mirroring, ported from
 * the reference receiver.
 *
 * Frames are decoded into [VideoPipeline]'s persistent surface; output buffers
 * are released at the VSYNC matching their NTP timestamp instead of immediately,
 * which is what keeps cadence smooth on 60 Hz panels. A codec is only (re)started
 * on a keyframe, because a stale reference frame decodes to corruption.
 */
internal class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 60
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var currentCodecSet = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false

    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true

    private var framesThisSec = 0
    private var bytesThisSec = 0L
    private var lastStatReset = 0L
    private val frameIntervalsNs = LongArray(120)
    private var frameIntervalIdx = 0
    private var frameIntervalCount = 0
    private var lastOutputFrameNs = 0L
    private var ptsBaseUs = Long.MIN_VALUE
    private var wallBaseNs = 0L

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean?): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265 != false) selector.hevc(avcDecoder, w, h, fps, force = h265 == true) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
        stopCodec()
    }

    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    fun startSession() = synchronized(lock) { resetStats() }

    fun stopSession() = synchronized(lock) { stopCodec() }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return
            if (!currentCodecSet || codec == null || isH265 != currentH265) {
                if (!isKeyframe(data, isH265)) return
                stopCodec()
                currentH265 = isH265
                currentCodecSet = true
            }
            try {
                if (codec == null) startCodec(isH265)
                feedToCodec(data, ntpTimeNs)
                drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        resetStats()
    }

    private fun updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - lastStatReset >= 1000) {
            fps = framesThisSec
            bitrateBps = bytesThisSec * 8
            framePacingJitterUs = computeFramePacingJitterUs()
            framesThisSec = 0
            bytesThisSec = 0
            lastStatReset = now
        }
        framesThisSec++
        bytesThisSec += size
        frameCount++
    }

    private fun feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
                firstFrameQueued = true
                return
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame, drops=$droppedFrames")
    }

    private fun isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")
        firstFrameQueued = false
        try {
            startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = buildFormat(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun buildFormat(mime: String, info: MediaCodecInfo) =
        MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
            if (selector.adaptive(info, mime)) {
                setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
                setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
            if (enforceSdr) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
            }
        }

    private fun startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        frameIntervalIdx = 0
        frameIntervalCount = 0
        lastOutputFrameNs = 0L
        ptsBaseUs = Long.MIN_VALUE
        wallBaseNs = 0L
        codec?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        codec = null
        currentCodecSet = false
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            recordOutputFrameTime()
            if (scheduledOutputBufferRelease) {
                val ptsUs = info.presentationTimeUs
                if (ptsBaseUs == Long.MIN_VALUE) {
                    ptsBaseUs = ptsUs
                    wallBaseNs = System.nanoTime()
                }
                c.releaseOutputBuffer(idx, wallBaseNs + (ptsUs - ptsBaseUs) * 1000L)
            } else {
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    private fun resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        framesThisSec = 0; bytesThisSec = 0
    }

    private fun recordOutputFrameTime() {
        val now = System.nanoTime()
        if (lastOutputFrameNs > 0) {
            frameIntervalsNs[frameIntervalIdx % frameIntervalsNs.size] = now - lastOutputFrameNs
            frameIntervalIdx++
            frameIntervalCount++
        }
        lastOutputFrameNs = now
    }

    private fun computeFramePacingJitterUs(): Long {
        val count = frameIntervalCount.coerceAtMost(frameIntervalsNs.size)
        if (count < 2) return 0
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    private companion object {
        const val TAG = "AirPlayVideoRenderer"
        const val FEED_WAIT_US = 20_000L
        const val FEED_RETRIES = 10
        const val FIRST_FEED_RETRIES = 50
    }
}
