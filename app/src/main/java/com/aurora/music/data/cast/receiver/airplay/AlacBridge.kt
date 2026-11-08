package com.aurora.music.data.cast.receiver.airplay

import android.util.Log

/**
 * JNI wrapper around Apple's reference ALAC decoder (vendored in `cpp/alac`,
 * Apache-2.0). One instance per AirPlay session; [decode] is not thread-safe
 * and is only called from the session's RTP thread.
 */
class AlacBridge(
    frameLength: Int,
    compatibleVersion: Int,
    bitDepth: Int,
    pb: Int,
    mb: Int,
    kb: Int,
    channels: Int,
    maxRun: Int,
    maxFrameBytes: Int,
    avgBitRate: Int,
    sampleRate: Int,
) : AutoCloseable {

    private var handle: Long = 0L

    /** Samples per channel that one ALAC frame can produce. */
    val maxSamplesPerFrame: Int = frameLength
    val channels: Int = channels
    val sampleRate: Int = sampleRate

    init {
        if (loaded) {
            handle = nativeCreate(
                frameLength, compatibleVersion, bitDepth, pb, mb, kb, channels,
                maxRun, maxFrameBytes, avgBitRate, sampleRate,
            )
            if (handle == 0L) Log.w(TAG, "ALAC decoder init failed (cookie rejected)")
        } else {
            Log.w(TAG, "aurora_alac library unavailable")
        }
    }

    val isAvailable: Boolean get() = handle != 0L

    /**
     * Decodes one ALAC packet into interleaved 16-bit PCM.
     * @return number of samples **per channel**, or 0 on failure.
     */
    fun decode(packet: ByteArray, packetLength: Int, out: ShortArray): Int {
        val h = handle
        if (h == 0L) return 0
        // The ALAC decoder's numSamples argument is the frame length, not the
        // output buffer capacity. Passing the whole scratch buffer made it read
        // past the 352-sample frame and emit silence.
        val maxSamples = maxSamplesPerFrame.coerceAtMost(out.size / channels.coerceAtLeast(1))
        return runCatching { nativeDecode(h, packet, packetLength, out, maxSamples) }
            .getOrDefault(0)
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { nativeDestroy(h) }
    }

    private external fun nativeCreate(
        frameLength: Int,
        compatibleVersion: Int,
        bitDepth: Int,
        pb: Int,
        mb: Int,
        kb: Int,
        numChannels: Int,
        maxRun: Int,
        maxFrameBytes: Int,
        avgBitRate: Int,
        sampleRate: Int,
    ): Long

    private external fun nativeDecode(
        handle: Long,
        packet: ByteArray,
        packetLength: Int,
        out: ShortArray,
        maxSamples: Int,
    ): Int

    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "AirPlayAlac"
        private val loaded: Boolean = runCatching { System.loadLibrary("aurora_alac"); true }
            .getOrDefault(false)
    }
}
