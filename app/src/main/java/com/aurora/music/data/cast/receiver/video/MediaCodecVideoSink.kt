package com.aurora.music.data.cast.receiver.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.aurora.music.data.cast.receiver.CastVideoInfo
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * H.264 decoder for AirPlay mirroring. Output is rendered straight to the
 * Surface owned by [com.aurora.music.ui.cast.StandardCastOverlay]; no frame
 * copies and no Bitmap allocation.
 *
 * SPS/PPS arrive in unencrypted AirPlay packets (payload type 0x01) before the
 * matching IDR. They become MediaCodec's csd-0/csd-1, so the decoder is (re)built
 * whenever the sender changes resolution or the Surface is recreated.
 */
class MediaCodecVideoSink(
    private val listener: CastVideoListener,
) : CastVideoSink {

    private val lock = Any()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var released = false
    private var pendingInputIndex = -1
    private var lastPtsUs = 0L
    private var info = CastVideoInfo(codec = "H.264")

    private val queue = ArrayDeque<Frame>(8)
    private data class Frame(val data: ByteArray, val ptsUs: Long, val keyFrame: Boolean)

    override fun setSurface(surface: Surface?) {
        synchronized(lock) {
            if (this.surface === surface) return
            this.surface = surface
            if (surface == null) {
                // Keep the decoder alive: rotation recreates the SurfaceView and
                // we simply re-attach by recreating the codec on the next frame.
                releaseCodecLocked()
            } else {
                releaseCodecLocked()
                maybeStartLocked()
            }
        }
    }

    /** Called when an unencrypted SPS+PPS packet arrives. */
    override fun setCodecConfig(spsNal: ByteArray, ppsNal: ByteArray) {
        synchronized(lock) {
            if (released) return
            if (spsNal.contentEquals(sps ?: ByteArray(0)) && ppsNal.contentEquals(pps ?: ByteArray(0))) return
            sps = spsNal
            pps = ppsNal
            releaseCodecLocked()
            maybeStartLocked()
        }
    }

    override fun submitAccessUnit(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        synchronized(lock) {
            if (released) return
            if (codec == null) maybeStartLocked()
            val c = codec
            if (c == null) {
                // Surface not attached yet or config missing: drop, the sender
                // keeps sending keyframes.
                return
            }
            if (pendingInputIndex >= 0) {
                feedLocked(c, pendingInputIndex, Frame(data, ptsUs, keyFrame))
                pendingInputIndex = -1
                return
            }
            if (queue.size >= 12) queue.pollFirst()
            queue.addLast(Frame(data, ptsUs, keyFrame))
        }
    }

    private fun feedLocked(c: MediaCodec, index: Int, frame: Frame) {
        runCatching {
            val buf: ByteBuffer = c.getInputBuffer(index) ?: return
            buf.clear()
            buf.put(frame.data)
            c.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            lastPtsUs = frame.ptsUs
        }.onFailure {
            Log.w(TAG, "queueInputBuffer failed: ${it.message}")
        }
    }

    private fun maybeStartLocked() {
        val s = surface ?: return
        val sSps = sps ?: return
        val sPps = pps ?: return
        if (codec != null) return
        runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sSps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(sPps))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    synchronized(lock) {
                        if (codec !== mc || released) return
                        val frame = queue.pollFirst()
                        if (frame == null) {
                            pendingInputIndex = index
                        } else {
                            feedLocked(mc, index, frame)
                        }
                    }
                }

                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    runCatching { mc.releaseOutputBuffer(index, true) }
                }

                override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                    val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    synchronized(lock) {
                        if (codec !== mc) return
                        if (w > 0 && h > 0 && (w != info.width || h != info.height)) {
                            info = info.copy(width = w, height = h)
                            listener.onVideoInfo(info)
                        }
                    }
                }

                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "decoder error ${e.errorCode}: ${e.message}")
                    listener.onVideoError(e.message ?: "decoder error")
                }
            })
            c.configure(format, s, null, 0)
            c.start()
            codec = c
            listener.onVideoInfo(info)
        }.onFailure {
            Log.w(TAG, "cannot start H.264 decoder: ${it.message}")
            listener.onVideoError(it.message ?: "decoder init failed")
        }
    }

    private fun releaseCodecLocked() {
        pendingInputIndex = -1
        queue.clear()
        val c = codec ?: return
        codec = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    override fun flush() {
        synchronized(lock) {
            queue.clear()
            pendingInputIndex = -1
            codec?.let { runCatching { it.flush() } }
        }
    }

    override fun release() {
        synchronized(lock) {
            released = true
            releaseCodecLocked()
            surface = null
            sps = null
            pps = null
        }
    }

    companion object {
        private const val TAG = "AirPlayVideo"
    }
}
