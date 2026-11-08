package com.aurora.music.data.cast.receiver.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap
import kotlin.math.max

/** Minimal RTP header view (RFC 3550). */
data class RtpPacket(
    val sequence: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payloadOffset: Int,
    val payloadLength: Int,
    val data: ByteArray,
)

fun parseRtpPacket(buf: ByteArray, length: Int): RtpPacket? {
    if (length < 12) return null
    val first = buf[0].toInt() and 0xFF
    if (first ushr 6 != 2) return null
    val csrcCount = first and 0x0F
    var offset = 12 + csrcCount * 4
    if (first and 0x10 != 0) { // header extension
        if (length < offset + 4) return null
        val extWords = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
        offset += 4 + extWords * 4
    }
    var end = length
    if (first and 0x20 != 0) { // padding
        end -= (buf[length - 1].toInt() and 0xFF)
    }
    if (offset >= end) return null
    val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
    val ts = ((buf[4].toLong() and 0xFF) shl 24) or
        ((buf[5].toLong() and 0xFF) shl 16) or
        ((buf[6].toLong() and 0xFF) shl 8) or
        (buf[7].toLong() and 0xFF)
    val ssrc = ((buf[8].toLong() and 0xFF) shl 24) or
        ((buf[9].toLong() and 0xFF) shl 16) or
        ((buf[10].toLong() and 0xFF) shl 8) or
        (buf[11].toLong() and 0xFF)
    return RtpPacket(seq, ts, ssrc, offset, end - offset, buf)
}

/**
 * Receives RAOP audio over UDP, decrypts AES-128-CBC, decodes ALAC (or AAC) and
 * hands interleaved 16-bit stereo PCM to [onPcm].
 *
 * A small reorder window absorbs out-of-order Wi-Fi delivery; gaps become
 * silence computed from the RTP timestamp delta so the audio clock never drifts.
 */
class AirPlayAudioReceiver(
    private val format: AirPlayAudioFormat,
    private val onPcm: (ByteArray, Int) -> Unit,
    private val onFirstPacket: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var thread: Thread? = null
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private val alac: AlacBridge?
    private var aac: AacDecoder? = null

    private val pending = TreeMap<Int, RtpPacket>()
    private var expectedSeq = -1
    private var lastTimestamp = -1L
    private var lastFrameCount = 0
    private var lastArrivalMs = 0L
    private var firstPacketSeen = false
    private var firstDecodeLogged = false
    private var debugPackets = 0
    private var debugDecodes = 0
    private var pcmScratch = ShortArray(8192)

    init {
        val cfg = format.alacConfig
        alac = if (format.codec == AirPlayCodec.ALAC && cfg != null) {
            AlacBridge(
                frameLength = cfg.frameLength,
                compatibleVersion = cfg.compatibleVersion,
                bitDepth = cfg.bitDepth,
                pb = cfg.pb,
                mb = cfg.mb,
                kb = cfg.kb,
                channels = cfg.channels.coerceIn(1, 2),
                maxRun = cfg.maxRun,
                maxFrameBytes = cfg.maxFrameBytes,
                avgBitRate = cfg.avgBitRate,
                sampleRate = cfg.sampleRate,
            ).also { pcmScratch = ShortArray(max(4096, cfg.frameLength * 2 * 2)) }
        } else {
            null
        }
        if (format.codec == AirPlayCodec.AAC || format.codec == AirPlayCodec.AAC_ELD) {
            aac = AacDecoder(
                sampleRate = format.sampleRate,
                channels = format.channels,
                aacEld = format.codec == AirPlayCodec.AAC_ELD,
            )
        }
    }

    fun start(data: DatagramSocket, control: DatagramSocket?) {
        synchronized(lock) {
            if (running) return
            running = true
            dataSocket = data
            controlSocket = control
            data.soTimeout = 30
            control?.soTimeout = 1
        }
        thread = Thread({ receiveLoop() }, "AirPlayAudioRtp").apply {
            priority = Thread.NORM_PRIORITY + 3
            isDaemon = true
            start()
        }
    }

    fun flush() {
        synchronized(lock) {
            pending.clear()
            expectedSeq = -1
            lastTimestamp = -1
            lastFrameCount = 0
            firstPacketSeen = false
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            dataSocket?.close()
            controlSocket?.close()
            dataSocket = null
            controlSocket = null
        }
        thread?.join(500)
        thread = null
        aac?.release()
        aac = null
        alac?.close()
    }

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        val controlBuf = ByteArray(512)
        try {
            while (true) {
                val socket = synchronized(lock) { dataSocket } ?: break
                if (!synchronized(lock) { running }) break
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                    val rtp = parseRtpPacket(packet.data, packet.length)
                    if (rtp != null) handleRtp(rtp)
                } catch (_: SocketTimeoutException) {
                    advanceIfStarving()
                }
                // Drain the control socket so the sender's sync packets do not pile up.
                val control = synchronized(lock) { controlSocket }
                if (control != null) {
                    try {
                        control.receive(DatagramPacket(controlBuf, controlBuf.size))
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            if (synchronized(lock) { running }) onError(e.message ?: "audio socket error")
        }
    }

    private fun handleRtp(rtp: RtpPacket) {
        if (!firstPacketSeen) {
            firstPacketSeen = true
            expectedSeq = rtp.sequence
            lastTimestamp = rtp.timestamp
            lastArrivalMs = System.currentTimeMillis()
            Log.i(TAG, "first RTP packet: seq=${rtp.sequence} ts=${rtp.timestamp} payload=${rtp.payloadLength}")
            onFirstPacket()
        }
        val diff = (rtp.sequence - expectedSeq) and 0xFFFF
        if (diff >= 0x8000) {
            // Late packet outside the window.
            return
        }
        if (expectedSeq == rtp.sequence) {
            decodeAndEmit(rtp)
            expectedSeq = (expectedSeq + 1) and 0xFFFF
            drainContiguous()
        } else {
            // The receive loop reuses one DatagramPacket buffer, so a packet
            // parked in the reorder window must own a copy of its payload.
            val payload = rtp.data.copyOfRange(
                rtp.payloadOffset,
                rtp.payloadOffset + rtp.payloadLength,
            )
            pending[rtp.sequence] = rtp.copy(data = payload, payloadOffset = 0)
            lastArrivalMs = System.currentTimeMillis()
            if (pending.size > 24) advanceIfStarving(force = true)
        }
    }

    private fun drainContiguous() {
        while (true) {
            val next = pending.remove(expectedSeq) ?: return
            decodeAndEmit(next)
            expectedSeq = (expectedSeq + 1) and 0xFFFF
        }
    }

    private fun advanceIfStarving(force: Boolean = false) {
        val next = pending.firstEntry() ?: return
        val waited = System.currentTimeMillis() - lastArrivalMs
        if (!force && waited < 60) return
        val delta = ((next.value.timestamp - lastTimestamp) and 0xFFFFFFFFL).toInt()
        val gapFrames = (delta - lastFrameCount).coerceIn(0, format.sampleRate)
        if (gapFrames > 0) emitSilence(gapFrames)
        expectedSeq = next.key
        decodeAndEmit(next.value)
        expectedSeq = (expectedSeq + 1) and 0xFFFF
        pending.remove(next.key)
        drainContiguous()
    }

    private fun decodeAndEmit(rtp: RtpPacket) {
        val payloadLength = rtp.payloadLength
        if (payloadLength <= 0) return
        // AAC-ELD packets are sent three times with the same timestamp; decode once.
        val duplicate = format.codec == AirPlayCodec.AAC_ELD &&
            lastTimestamp == rtp.timestamp && lastFrameCount > 0
        lastTimestamp = rtp.timestamp
        if (duplicate) return
        val decrypted = if (format.encrypted) {
            AirPlayCrypto.aesCbcDecrypt(
                format.aesKey, format.aesIv, rtp.data, rtp.payloadOffset, payloadLength,
            )
        } else {
            rtp.data.copyOfRange(rtp.payloadOffset, rtp.payloadOffset + payloadLength)
        }
        if (debugPackets < 5) {
            debugPackets++
            Log.i(
                TAG,
                "pkt#$debugPackets payload=$payloadLength enc=${format.encrypted} " +
                    "in=${hex(rtp.data, rtp.payloadOffset, minOf(payloadLength, 32))} " +
                    "out=${hex(decrypted, 0, minOf(decrypted.size, 32))}",
            )
        }
        when (format.codec) {
            AirPlayCodec.ALAC -> {
                val bridge = alac ?: return
                val frames = bridge.decode(decrypted, decrypted.size, pcmScratch)
                lastFrameCount = frames
                if (!firstDecodeLogged && frames > 0) {
                    firstDecodeLogged = true
                    Log.i(TAG, "first ALAC decode: frames=$frames payload=$payloadLength")
                }
                if (frames > 0) emitShorts(pcmScratch, frames * bridge.channels)
            }
            AirPlayCodec.AAC, AirPlayCodec.AAC_ELD -> {
                var produced = 0
                aac?.decode(decrypted, adts = format.codec == AirPlayCodec.AAC) { pcm, len ->
                    produced += len / 4
                    onPcm(pcm, len)
                }
                lastFrameCount = produced
            }
            AirPlayCodec.PCM -> {
                lastFrameCount = decrypted.size / (2 * format.channels.coerceAtLeast(1))
                onPcm(decrypted, decrypted.size)
            }
            else -> Unit
        }
    }

    private fun emitShorts(shorts: ShortArray, count: Int) {
        if (debugDecodes < 10) {
            debugDecodes++
            var nz = 0
            for (i in 0 until count) if (shorts[i].toInt() != 0) nz++
            Log.i(TAG, "decode#$debugDecodes shorts=$count nonZero=$nz")
        }
        val bytes = ByteArray(count * 2)
        var j = 0
        for (i in 0 until count) {
            val v = shorts[i].toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        onPcm(bytes, bytes.size)
    }

    private fun emitSilence(frames: Int) {
        if (frames <= 0) return
        val bytes = ByteArray(frames * 2 * 2)
        onPcm(bytes, bytes.size)
    }

    companion object {
        private const val TAG = "AirPlayAudioRtp"

        private fun hex(data: ByteArray, offset: Int, length: Int): String {
            val sb = StringBuilder()
            val end = minOf(offset + length, data.size)
            for (i in offset until end) sb.append("%02x".format(data[i].toInt() and 0xFF))
            return sb.toString()
        }
    }
}

/**
 * ALAC output is 16-bit native-endian; Android is little-endian, so a plain
 * cast to bytes is correct. Kept in [AirPlayAudioReceiver].
 */

/** MediaCodec AAC fallback (AAC-LC via ADTS, AAC-ELD via AudioSpecificConfig). */
private class AacDecoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val aacEld: Boolean,
) {
    private var codec: MediaCodec? = null
    private var started = false
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        runCatching {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            if (aacEld) {
                // ISO 14496-3 AudioSpecificConfig for AAC-ELD 44100/2, frameLengthFlag=0.
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
            } else {
                fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, 1) // AAC-LC
            }
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c
            started = true
        }.onFailure {
            Log.w("AirPlayAac", "AAC decoder unavailable: ${it.message}")
        }
    }

    fun decode(aacFrame: ByteArray, adts: Boolean, emit: (ByteArray, Int) -> Unit) {
        val c = codec ?: return
        runCatching {
            val header = if (adts) buildAdts(aacFrame.size) else ByteArray(0)
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val input = c.getInputBuffer(idx) ?: return
                input.clear()
                input.put(header)
                input.put(aacFrame)
                c.queueInputBuffer(idx, 0, header.size + aacFrame.size, 0, 0)
            }
            drain(c, emit)
        }
    }

    private fun drain(c: MediaCodec, emit: (ByteArray, Int) -> Unit) {
        while (true) {
            val idx = c.dequeueOutputBuffer(bufferInfo, 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (idx < 0) continue
            val out = c.getOutputBuffer(idx)
            if (out != null && bufferInfo.size > 0) {
                val bytes = ByteArray(bufferInfo.size)
                out.position(bufferInfo.offset)
                out.get(bytes)
                emit(bytes, bytes.size)
            }
            c.releaseOutputBuffer(idx, false)
        }
    }

    private fun buildAdts(payloadSize: Int): ByteArray {
        val frameLen = payloadSize + 7
        val freqIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
            32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9
            11025 -> 10; 8000 -> 11; 7350 -> 12
            else -> 4
        }
        val profile = 1 // AAC-LC, ADTS profile is object type - 1
        val h = ByteArray(7)
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte()
        h[2] = (((profile shl 6) or (freqIndex shl 2) or (channels shr 2)) and 0xFF).toByte()
        h[3] = (((channels and 3) shl 6) or ((frameLen shr 11) and 3)).toByte()
        h[4] = ((frameLen shr 3) and 0xFF).toByte()
        h[5] = (((frameLen and 7) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
        return h
    }

    fun release() {
        val c = codec ?: return
        codec = null
        runCatching { if (started) c.stop() }
        runCatching { c.release() }
        started = false
    }
}

/**
 * AirPlay screen-mirroring video stream (TCP).
 *
 * Header layout (128 bytes):
 *   [0:4]   big-endian payload size
 *   [4]     0x00 encrypted VCL NAL(s), 0x01 unencrypted SPS+PPS, 0x05 report
 *   [5]     0x10 marks an IDR
 *   [8:16]  NTP timestamp
 *   [16:64] float geometry (source width/height, offsets, display size)
 *
 * VCL payloads use length-prefixed NAL units; they are converted to Annex-B
 * (4-byte start codes) because that is what MediaCodec expects.
 */
class AirPlayVideoReceiver(
    private val streamConnectionId: Long,
    private val audioAesKey: ByteArray,
    private val sink: com.aurora.music.data.cast.receiver.video.CastVideoSink,
    private val onFrameSize: (width: Int, height: Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val lock = Any()
    private var running = false
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var acceptThread: Thread? = null
    private var readThread: Thread? = null
    private val decryptor = AirPlayVideoDecryptor(
        AirPlayCrypto.deriveVideoKey(streamConnectionId, audioAesKey),
        AirPlayCrypto.deriveVideoIv(streamConnectionId, audioAesKey),
    )
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var firstTimestamp = -1L
    private var streamWidth = 0
    private var streamHeight = 0
    private var firstHeaderLogged = false
    private var firstFrameLogged = false

    fun start(port: Int) {
        synchronized(lock) {
            if (running) return
            running = true
        }
        server = ServerSocket(port)
        server?.soTimeout = 500
        acceptThread = Thread({ acceptLoop() }, "AirPlayMirrorAccept").apply {
            isDaemon = true
            start()
        }
    }

    val localPort: Int get() = server?.localPort ?: 0

    fun stop() {
        synchronized(lock) {
            running = false
            runCatching { client?.close() }
            runCatching { server?.close() }
            client = null
            server = null
        }
        acceptThread?.join(300)
        readThread?.join(300)
        acceptThread = null
        readThread = null
        sink.flush()
    }

    private fun acceptLoop() {
        while (synchronized(lock) { running }) {
            val s = try {
                server?.accept() ?: break
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            synchronized(lock) {
                if (!running) {
                    runCatching { s.close() }
                    return
                }
                runCatching { client?.close() }
                client = s
            }
            Log.i(TAG, "video client connected from ${s.remoteSocketAddress}")
            readThread = Thread({ readLoop(s) }, "AirPlayMirrorRead").apply {
                priority = Thread.NORM_PRIORITY + 2
                isDaemon = true
                start()
            }
        }
    }

    private fun readLoop(socket: Socket) {
        socket.tcpNoDelay = true
        val input: InputStream = socket.getInputStream()
        val header = ByteArray(128)
        try {
            while (synchronized(lock) { running }) {
                readFully(input, header, 128)
                // Mirroring headers are little-endian: u32 payload size,
                // u16 payload type (0=video, 1=codec data, 2=heartbeat).
                val payloadSize = readUInt32LE(header, 0).toInt()
                if (!firstHeaderLogged) {
                    firstHeaderLogged = true
                    Log.i(
                        TAG,
                        "first mirror header: size=$payloadSize type=${readUInt16LE(header, 4)} " +
                            "w=${readFloat(header, 56)} h=${readFloat(header, 60)}",
                    )
                }
                if (payloadSize < 0 || payloadSize > 8 * 1024 * 1024) {
                    onError("invalid mirroring payload size $payloadSize")
                    break
                }
                val payload = ByteArray(payloadSize)
                readFully(input, payload, payloadSize)
                when (readUInt16LE(header, 4)) {
                    0 -> handleVcl(header, payload)
                    1 -> handleCodecConfig(header, payload)
                    2 -> Unit // heartbeat
                    else -> Unit
                }
            }
        } catch (_: EOFException) {
            // Sender closed the stream.
        } catch (e: Exception) {
            if (synchronized(lock) { running }) onError(e.message ?: "video socket error")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleCodecConfig(header: ByteArray, payload: ByteArray) {
        if (payload.size < 12) return
        val spsSize = readUInt16(payload, 6)
        if (spsSize <= 0 || 8 + spsSize + 3 > payload.size) return
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsSize = readUInt16(payload, spsSize + 9)
        if (ppsSize <= 0 || spsSize + 11 + ppsSize > payload.size) return
        val pps = payload.copyOfRange(spsSize + 11, spsSize + 11 + ppsSize)
        pendingSps = annexB(sps)
        pendingPps = annexB(pps)
        updateSize(header)
        Log.i(TAG, "codec config: sps=${sps.size} pps=${pps.size}")
        // MediaCodec csd-0/csd-1 expect Annex-B NALs (start code included).
        sink.setCodecConfig(pendingSps!!, pendingPps!!)
    }

    private fun handleVcl(header: ByteArray, payload: ByteArray) {
        val decrypted = decryptor.decrypt(payload)
        val prefix = buildList<ByteArray> {
            pendingSps?.let { add(it) }
            pendingPps?.let { add(it) }
        }
        val annexB = convertLengthPrefixedNals(decrypted) ?: return
        pendingSps = null
        pendingPps = null
        val out = if (prefix.isEmpty()) annexB else {
            val total = prefix.sumOf { it.size } + annexB.size
            val merged = ByteArray(total)
            var offset = 0
            for (p in prefix) { System.arraycopy(p, 0, merged, offset, p.size); offset += p.size }
            System.arraycopy(annexB, 0, merged, offset, annexB.size)
            merged
        }
        val ptsUs = ntpToMicros(header)
        val keyFrame = hasIdrNal(decrypted)
        updateSize(header)
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i(TAG, "first video frame: ${out.size} bytes key=$keyFrame pts=$ptsUs")
        }
        sink.submitAccessUnit(out, ptsUs, keyFrame)
    }

    private fun updateSize(header: ByteArray) {
        val width = readFloat(header, 56).toInt()
        val height = readFloat(header, 60).toInt()
        if (width > 0 && height > 0 && (width != streamWidth || height != streamHeight)) {
            streamWidth = width
            streamHeight = height
            onFrameSize(width, height)
        }
    }

    private fun ntpToMicros(header: ByteArray): Long {
        val seconds = readUInt32(header, 8)
        val fraction = readUInt32(header, 12)
        val micros = seconds * 1_000_000L + ((fraction.toLong() and 0xFFFFFFFFL) * 1_000_000L ushr 32)
        if (firstTimestamp < 0) firstTimestamp = micros
        return (micros - firstTimestamp).coerceAtLeast(0)
    }

    /** True when any length-prefixed NAL in the access unit is an IDR slice. */
    private fun hasIdrNal(data: ByteArray): Boolean {
        var pos = 0
        while (pos + 4 <= data.size) {
            val len = readUInt32(data, pos).toInt()
            if (len <= 0 || pos + 4 + len > data.size) return false
            if ((data[pos + 4].toInt() and 0x1F) == 5) return true
            pos += 4 + len
        }
        return false
    }

    /** Replaces each 4-byte big-endian length with a start code. */
    private fun convertLengthPrefixedNals(data: ByteArray): ByteArray? {
        if (data.size < 5) return null
        var pos = 0
        val out = data.copyOf()
        while (pos < out.size) {
            if (pos + 4 > out.size) return null
            val len = readUInt32(out, pos).toInt()
            if (len <= 0 || pos + 4 + len > out.size) return null
            out[pos] = 0
            out[pos + 1] = 0
            out[pos + 2] = 0
            out[pos + 3] = 1
            pos += 4 + len
        }
        return out
    }

    private fun annexB(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size + 4)
        out[3] = 1
        System.arraycopy(nal, 0, out, 4, nal.size)
        return out
    }

    private fun readFully(input: InputStream, data: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) throw EOFException()
            read += n
        }
    }

    companion object {
        private const val TAG = "AirPlayVideo"

        private fun readUInt16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        private fun readUInt16LE(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        private fun readUInt32LE(data: ByteArray, offset: Int): Long =
            (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)

        private fun readUInt32(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)

        private fun readFloat(data: ByteArray, offset: Int): Float =
            ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).float
    }
}
