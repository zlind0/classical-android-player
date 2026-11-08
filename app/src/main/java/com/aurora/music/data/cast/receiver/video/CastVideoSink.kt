package com.aurora.music.data.cast.receiver.video

import android.view.Surface
import com.aurora.music.data.cast.receiver.CastVideoInfo

/**
 * Video hook for the cast receiver (plan §3.1 / §13.5). AirPlay mirroring is
 * the first implementation that actually feeds it; the interface deliberately
 * knows nothing about AirPlay so Chromecast/Miracast can share it later.
 */
interface CastVideoSink {
    /** null detaches the surface (UI destroyed / rotation), decoding may continue or pause. */
    fun setSurface(surface: Surface?)

    /** Annex-B access unit, presentation timestamp in microseconds. */
    fun submitAccessUnit(data: ByteArray, ptsUs: Long, keyFrame: Boolean)

    /** Raw (no start code) H.264 SPS/PPS from the sender. */
    fun setCodecConfig(spsNal: ByteArray, ppsNal: ByteArray)

    fun flush()

    fun release()
}

interface CastVideoListener {
    fun onVideoInfo(info: CastVideoInfo)
    fun onVideoError(message: String)
}
