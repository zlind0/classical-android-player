package com.aurora.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

// reads with absolute indexing never mutates the buffer so playback is unaffected
@OptIn(UnstableApi::class)
class TappingAudioSink(
    delegate: AudioSink,
    private val controller: VisualizerController,
) : ForwardingAudioSink(delegate) {

    private var encoding: Int = C.ENCODING_PCM_16BIT
    private var sampleRate: Int = 48000
    private var channelCount: Int = 2

    // handleBuffer may be called repeatedly with the same buffer until the
    // delegate consumes it fully; tap each buffer only once.
    private var pushedBuffer: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        if (inputFormat.pcmEncoding != Format.NO_VALUE) encoding = inputFormat.pcmEncoding
        if (inputFormat.sampleRate != Format.NO_VALUE) sampleRate = inputFormat.sampleRate
        if (inputFormat.channelCount != Format.NO_VALUE) channelCount = inputFormat.channelCount
        pushedBuffer = null
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (controller.active && buffer !== pushedBuffer) {
            controller.pushPcm(buffer, encoding, channelCount, sampleRate)
            pushedBuffer = buffer
        }
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (handled) pushedBuffer = null
        return handled
    }
}
