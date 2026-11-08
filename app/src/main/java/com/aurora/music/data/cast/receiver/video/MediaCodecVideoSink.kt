package com.aurora.music.data.cast.receiver.video

import android.content.Context
import android.util.Log
import android.view.Surface
import com.aurora.music.data.cast.receiver.CastVideoInfo
import com.aurora.music.data.cast.receiver.airplay.video.VideoRenderer

/**
 * H.264/H.265 decoder for AirPlay mirroring.
 *
 * The heavy lifting lives in [VideoRenderer] (ported from the reference
 * receiver): a persistent GL pipeline decodes into an app-owned SurfaceTexture
 * and blits to the current display surface, so rotating or recreating the UI
 * surface never restarts the codec. The sender's SPS/PPS are already prepended
 * to the matching IDR by the RTP parser, so the decoder is configured from the
 * bitstream itself.
 */
class MediaCodecVideoSink(
    context: Context,
    private val listener: CastVideoListener,
) : CastVideoSink {

    private val lock = Any()
    private val renderer = VideoRenderer(context.applicationContext)
    private var surface: Surface? = null
    private var released = false
    private var isH265 = false
    private var width = 0
    private var height = 0
    private var info = CastVideoInfo(codec = "H.264")

    init {
        renderer.selectDecoders(1920, 1080, 60, null)
        renderer.startSession()
    }

    override fun setSurface(surface: Surface?) {
        synchronized(lock) {
            if (released) return
            val old = this.surface
            if (old === surface) return
            this.surface = surface
            if (surface == null) {
                if (old != null) renderer.clearSurface(old)
            } else {
                renderer.setSurface(surface)
            }
        }
    }

    override fun setVideoFormat(codec: String, width: Int, height: Int) {
        synchronized(lock) {
            if (released) return
            isH265 = codec == "H.265"
            val w = if (width > 0) width else 1920
            val h = if (height > 0) height else 1080
            this.width = w
            this.height = h
            renderer.selectDecoders(w, h, 60, isH265)
            renderer.setResolution(w, h)
            val next = CastVideoInfo(codec = if (isH265) "H.265" else "H.264", width = w, height = h)
            if (next != info) {
                info = next
                listener.onVideoInfo(next)
            }
        }
    }

    override fun submitAccessUnit(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        synchronized(lock) {
            if (released) return
            if (width == 0 || height == 0) return
            // VideoRenderer consumes NTP nanoseconds and schedules the output
            // buffer release against the matching VSYNC.
            renderer.feedFrame(data, ptsUs * 1000L, isH265)
        }
    }

    override fun setCodecConfig(spsNal: ByteArray, ppsNal: ByteArray) {
        // Codec config is carried in-band by the access unit; kept for the
        // protocol-agnostic CastVideoSink contract.
    }

    override fun flush() {
        synchronized(lock) {
            if (released) return
            renderer.stopSession()
            renderer.startSession()
        }
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            renderer.release()
            surface = null
        }
    }

    val codecName: String get() = renderer.codecName
    val fps: Int get() = renderer.fps
    val bitrateBps: Long get() = renderer.bitrateBps
    val frameCount: Long get() = renderer.frameCount
    val droppedFrames: Long get() = renderer.droppedFrames
    val framePacingJitterUs: Long get() = renderer.framePacingJitterUs

    private companion object {
        const val TAG = "AirPlayVideoSink"
    }
}
