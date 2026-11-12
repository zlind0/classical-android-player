package com.aurora.music.data.cast.receiver.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
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
    private val onPcm: (ByteArray, Int, Long) -> Unit,
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

    // 解密参数不能是构造时的常量：发送端可以在同一个会话里重新 SETUP 一套
    // FairPlay key（Apple Music 换歌 / 结束后重新投播都会这样）。如果这里还用
    // 旧 key，之后每个包都会解成垃圾 —— 现象正是「包一直在来、解码全失败、
    // 声音消失」。SETUP 处理路径用 [updateKeys] 把新 key 推进来。
    @Volatile private var aesKey: ByteArray = format.aesKey
    @Volatile private var aesIv: ByteArray = format.aesIv
    @Volatile private var encrypted: Boolean = format.encrypted

    /** 连续解码失败的包数，用于限流日志并给出可操作的诊断提示。 */
    private var decodeFailureStreak = 0

    private class KeyMaterial(val key: ByteArray, val iv: ByteArray)

    /** 由 RTSP 线程排队、收包线程消费的待切换 key（见 [updateKeys]）。 */
    @Volatile private var pendingKeys: KeyMaterial? = null

    private val pending = TreeMap<Int, RtpPacket>()
    private var expectedSeq = -1
    private var lastTimestamp = -1L
    private var lastFrameCount = 0
    private var firstRtpTimestamp = -1L
    private var lastArrivalMs = 0L
    private var firstPacketSeen = false
    private var firstDecodeLogged = false
    private var debugPackets = 0
    private var debugDecodes = 0
    private var lastDecodeMs = 0L
    private var seqGapEvents = 0
    /** Sender's control endpoint, learned from its first control packet. */
    @Volatile private var controlTarget: InetSocketAddress? = null
    private var controlSeq = 0

    // Gap / retransmission state. gapSinceMs marks when the current missing
    // sequence range was first noticed; retries are paced by the settings.
    private var gapSinceMs = 0L
    private var lastResendSentMs = 0L
    private var resendAttempts = 0

    // Decode-failure PLC: repeat the last good PCM instead of leaving a hole.
    private var lastPcm: ByteArray? = null
    private var consecutiveDecodeFailures = 0
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
            firstRtpTimestamp = -1
            firstPacketSeen = false
            lastDecodeMs = 0L
            gapSinceMs = 0L
            resendAttempts = 0
            lastPcm = null
            consecutiveDecodeFailures = 0
        }
    }

    /**
     * 同一会话中途换 AES key。
     *
     * 发送端重新 SETUP 时会生成新的 key（和新的 RTP 序列号起点），但 UDP 收包
     * 线程可能还挂在上一条流上。这里把新 key 交给收包线程（避免从 RTSP 线程
     * 改乱序窗口），由它在下个包到达前原子切换并重新对齐，避免「换 key 之后
     * 所有包都解不出来」的死锁状态。
     */
    fun updateKeys(key: ByteArray, iv: ByteArray) {
        pendingKeys = KeyMaterial(key.copyOf(16), iv.copyOf(16))
        AirPlayDiagnostics.log(
            AirPlayDiagnostics.Level.WARN,
            TAG,
            "AES key update queued (key=${fingerprint(key)})",
        )
    }

    /** 由收包线程调用：把 [updateKeys] 排队的 key 应用到一个干净的重对齐状态。 */
    private fun applyPendingKeys() {
        val next = pendingKeys ?: return
        pendingKeys = null
        aesKey = next.key
        aesIv = next.iv
        encrypted = true
        pending.clear()
        expectedSeq = -1
        lastTimestamp = -1
        lastFrameCount = 0
        firstRtpTimestamp = -1
        firstPacketSeen = false
        lastDecodeMs = 0L
        gapSinceMs = 0L
        resendAttempts = 0
        lastPcm = null
        consecutiveDecodeFailures = 0
        decodeFailureStreak = 0
        AirPlayDiagnostics.log(
            AirPlayDiagnostics.Level.WARN,
            TAG,
            "AES key switched mid-stream (key=${fingerprint(next.key)}); decode window reset",
        )
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
                    serviceGap(System.currentTimeMillis())
                }
                // Drain the control socket: resend responses arrive here (RAOP
                // sends retransmitted audio to the control port, not the data
                // port). The sender address is also learned from this socket so
                // gap recovery requests can be sent back.
                val control = synchronized(lock) { controlSocket }
                if (control != null) {
                    try {
                        val cp = DatagramPacket(controlBuf, controlBuf.size)
                        control.receive(cp)
                        handleControlPacket(cp)
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            if (synchronized(lock) { running }) onError(e.message ?: "audio socket error")
        }
    }

    private fun handleControlPacket(packet: DatagramPacket) {
        if (packet.length < 12) return
        controlTarget = InetSocketAddress(packet.address, packet.port)
        val type = packet.data[1].toInt() and 0x7F
        if (type != 0x56) return
        // Retransmitted audio: 4-byte control header followed by the original
        // RTP packet (see UxPlay's raop_rtp.c).
        val copied = packet.data.copyOfRange(4, packet.length)
        val rtp = parseRtpPacket(copied, copied.size) ?: return
        AirPlayDiagnostics.recordResendReceived()
        handleRtp(rtp, resend = true)
    }

    /**
     * Ask the sender to retransmit a missing range on the control socket.
     * Without this every Wi-Fi packet loss becomes a hole in the buffer, which
     * is the usual cause of the intermittent "卡顿".
     */
    private fun requestResend(seq: Int, count: Int) {
        val target = controlTarget ?: return
        val socket = synchronized(lock) { controlSocket } ?: return
        val n = count.coerceIn(1, 64)
        val request = ByteArray(8)
        request[0] = 0x80.toByte()
        request[1] = 0xD5.toByte()
        val ourSeq = controlSeq++
        request[2] = (ourSeq shr 8).toByte()
        request[3] = ourSeq.toByte()
        request[4] = (seq shr 8).toByte()
        request[5] = seq.toByte()
        request[6] = (n shr 8).toByte()
        request[7] = n.toByte()
        runCatching { socket.send(DatagramPacket(request, request.size, target)) }
            .onFailure {
                AirPlayDiagnostics.log(
                    AirPlayDiagnostics.Level.WARN,
                    TAG,
                    "resend request failed: ${it.message}",
                )
            }
        AirPlayDiagnostics.recordResendRequest()
        if (seqGapEvents < 5) {
            AirPlayDiagnostics.log(
                AirPlayDiagnostics.Level.DEBUG,
                TAG,
                "request resend seq=$seq count=$n",
            )
        }
    }

    private fun handleRtp(rtp: RtpPacket, resend: Boolean = false) {
        applyPendingKeys()
        val now = System.currentTimeMillis()
        if (!resend) AirPlayDiagnostics.recordPacket(rtp.payloadLength)
        if (!firstPacketSeen) {
            firstPacketSeen = true
            expectedSeq = rtp.sequence
            lastTimestamp = rtp.timestamp
            lastArrivalMs = now
            AirPlayDiagnostics.log(
                AirPlayDiagnostics.Level.INFO,
                TAG,
                "first RTP packet: seq=${rtp.sequence} ts=${rtp.timestamp} payload=${rtp.payloadLength}",
            )
            onFirstPacket()
        }
        val diff = (rtp.sequence - expectedSeq) and 0xFFFF
        if (diff >= 0x8000) {
            // A packet older than the current window. If nothing has decoded for
            // a while, this is not a late retransmit but the sender restarting its
            // sequence numbers (e.g. after a network hiccup) — resync instead of
            // silently dropping every packet forever. That was one source of
            // "artwork arrives, session looks fine, but there is no sound".
            if (lastDecodeMs > 0 && now - lastDecodeMs >= RESYNC_AFTER_MS) {
                resyncTo(rtp, now)
                return
            }
            AirPlayDiagnostics.recordLate()
            return
        }
        if (expectedSeq == rtp.sequence) {
            decodeAndEmit(rtp)
            expectedSeq = (expectedSeq + 1) and 0xFFFF
            drainContiguous()
            if (pending.isEmpty()) resetGapTracking()
        } else {
            // The receive loop reuses one DatagramPacket buffer, so a packet
            // parked in the reorder window must own a copy of its payload.
            val payload = rtp.data.copyOfRange(
                rtp.payloadOffset,
                rtp.payloadOffset + rtp.payloadLength,
            )
            pending[rtp.sequence] = rtp.copy(data = payload, payloadOffset = 0)
            lastArrivalMs = now
            AirPlayDiagnostics.recordReordered()
            if (gapSinceMs == 0L) {
                gapSinceMs = now
                resendAttempts = 0
                lastResendSentMs = 0L
            }
            serviceGap(now)
        }
    }

    /**
     * Pace retransmission requests and decide when to give up on a gap.
     * The wait budget comes from the settings page (or follows the buffer in
     * auto mode), so 2.4G Wi-Fi gets enough time for the resend to come back.
     */
    private fun serviceGap(now: Long) {
        if (gapSinceMs == 0L) return
        val first = pending.firstEntry() ?: run {
            resetGapTracking()
            return
        }
        val gap = (first.key - expectedSeq) and 0xFFFF
        if (gap <= 0) {
            resetGapTracking()
            return
        }
        val config = AirPlayTuning.config
        val maxAttempts = config.resendMaxRetries + 1
        if (controlTarget != null &&
            resendAttempts < maxAttempts &&
            (resendAttempts == 0 || now - lastResendSentMs >= config.resendRetryMs)
        ) {
            requestResend(expectedSeq, gap)
            resendAttempts++
            lastResendSentMs = now
        }
        if (now - gapSinceMs >= config.effectiveResendWaitMs || pending.size > MAX_PENDING) {
            advanceIfStarving()
        }
    }

    private fun resetGapTracking() {
        gapSinceMs = 0L
        resendAttempts = 0
        lastResendSentMs = 0L
    }

    /**
     * Sender restarted its RTP sequence/timestamp clock. Jump straight to the
     * newest packet and restart the PTS origin so the timeline buffer treats it
     * as a fresh stream instead of dropping everything as "late".
     */
    private fun resyncTo(rtp: RtpPacket, now: Long) {
        val silentMs = if (lastDecodeMs > 0) now - lastDecodeMs else 0L
        expectedSeq = rtp.sequence
        pending.clear()
        resetGapTracking()
        firstRtpTimestamp = rtp.timestamp
        lastTimestamp = rtp.timestamp
        lastFrameCount = 0
        lastArrivalMs = now
        AirPlayDiagnostics.recordSequenceResync()
        AirPlayDiagnostics.log(
            AirPlayDiagnostics.Level.WARN,
            TAG,
            "RTP sequence resync after ${silentMs}ms without decode: seq=${rtp.sequence} ts=${rtp.timestamp}",
        )
        decodeAndEmit(rtp)
        expectedSeq = (expectedSeq + 1) and 0xFFFF
        drainContiguous()
    }

    private fun drainContiguous() {
        while (true) {
            val next = pending.remove(expectedSeq) ?: return
            decodeAndEmit(next)
            expectedSeq = (expectedSeq + 1) and 0xFFFF
        }
    }

    private fun advanceIfStarving() {
        val next = pending.firstEntry() ?: return
        val now = System.currentTimeMillis()
        val waited = if (gapSinceMs > 0) now - gapSinceMs else 0L
        // A real gap: tell the diagnostics page how many packets the sender
        // skipped, and log it at most once per few events so the audio thread
        // does not spend its time formatting strings.
        val gap = (next.key - expectedSeq) and 0xFFFF
        if (gap > 0) {
            AirPlayDiagnostics.recordLost(gap.toLong())
            seqGapEvents++
            if (seqGapEvents <= 5 || seqGapEvents % 20 == 0) {
                AirPlayDiagnostics.log(
                    AirPlayDiagnostics.Level.WARN,
                    TAG,
                    "RTP gap: expected=$expectedSeq got=${next.key} lost=$gap waited=${waited}ms " +
                        "requests=$resendAttempts",
                )
            }
        }
        // The timeline buffer turns the PTS gap into silence/PLC, so just skip
        // to the next available packet here.
        expectedSeq = next.key
        decodeAndEmit(next.value)
        expectedSeq = (expectedSeq + 1) and 0xFFFF
        pending.remove(next.key)
        resetGapTracking()
        drainContiguous()
        if (pending.isEmpty()) resetGapTracking()
    }

    private fun decodeAndEmit(rtp: RtpPacket) {
        val payloadLength = rtp.payloadLength
        if (payloadLength <= 0) return
        // AAC-ELD packets are sent three times with the same timestamp; decode once.
        val duplicate = format.codec == AirPlayCodec.AAC_ELD &&
            lastTimestamp == rtp.timestamp && lastFrameCount > 0
        lastTimestamp = rtp.timestamp
        if (duplicate) {
            AirPlayDiagnostics.recordDuplicate()
            return
        }
        val ptsNs = ptsNs(rtp.timestamp)
        val key = aesKey
        val iv = aesIv
        val decrypted = if (encrypted) {
            AirPlayCrypto.aesCbcDecrypt(
                key, iv, rtp.data, rtp.payloadOffset, payloadLength,
            )
        } else {
            rtp.data.copyOfRange(rtp.payloadOffset, rtp.payloadOffset + payloadLength)
        }
        if (debugPackets < 5) {
            debugPackets++
            AirPlayDiagnostics.log(
                AirPlayDiagnostics.Level.DEBUG,
                TAG,
                "pkt#$debugPackets payload=$payloadLength enc=$encrypted " +
                    "key=${fingerprint(key)} " +
                    "in=${hex(rtp.data, rtp.payloadOffset, minOf(payloadLength, 32))} " +
                    "out=${hex(decrypted, 0, minOf(decrypted.size, 32))}",
            )
        }
        when (format.codec) {
            AirPlayCodec.ALAC -> {
                // UxPlay: a 4-byte "no data marker" replaces the payload for
                // packets that carry no audio (e.g. the first packet after SDP).
                if (isNoDataMarker(decrypted)) {
                    return
                }
                // A malformed/truncated packet (e.g. a partial marker after a
                // pause/resume) makes Apple's decoder read past the bitstream and
                // crash the process; drop anything too short to be a real frame.
                if (decrypted.size < 8) {
                    noteDecodeFailure("ALAC payload too short (${decrypted.size}B)", decrypted, rtp.sequence)
                    return
                }
                val bridge = alac ?: return
                val frames = bridge.decode(decrypted, decrypted.size, pcmScratch)
                lastFrameCount = frames
                if (!firstDecodeLogged && frames > 0) {
                    firstDecodeLogged = true
                    AirPlayDiagnostics.log(
                        AirPlayDiagnostics.Level.INFO,
                        TAG,
                        "first ALAC decode: frames=$frames payload=$payloadLength",
                    )
                }
                if (frames > 0) {
                    lastDecodeMs = System.currentTimeMillis()
                    consecutiveDecodeFailures = 0
                    decodeFailureStreak = 0
                    AirPlayDiagnostics.recordDecoded(frames.toLong())
                    emitShorts(pcmScratch, frames * bridge.channels, ptsNs)
                } else {
                    noteDecodeFailure("ALAC decode returned 0 frames", decrypted, rtp.sequence)
                    // 解码失败不要留下 PTS 洞：用上一块正常 PCM 顶一帧，
                    // 最多连续 3 帧，避免解码器彻底坏掉时一直重复。
                    val previous = lastPcm
                    if (AirPlayTuning.config.plcEnabled &&
                        previous != null &&
                        consecutiveDecodeFailures < 3
                    ) {
                        consecutiveDecodeFailures++
                        onPcm(previous, previous.size, ptsNs)
                    }
                }
            }
            AirPlayCodec.AAC, AirPlayCodec.AAC_ELD -> {
                // 镜像音频的第一批包是 4 字节 no-data marker，AAC 解码器收到它
                // 会进入错误状态并且再也不产出数据；必须在这里过滤掉。
                if (isNoDataMarker(decrypted) || decrypted.size < 4) {
                    return
                }
                var produced = 0
                aac?.decode(decrypted, adts = format.codec == AirPlayCodec.AAC) { pcm, len ->
                    produced += len / 4
                    onPcm(pcm, len, ptsNs)
                }
                lastFrameCount = produced
                if (produced > 0) {
                    lastDecodeMs = System.currentTimeMillis()
                    consecutiveDecodeFailures = 0
                    decodeFailureStreak = 0
                    AirPlayDiagnostics.recordDecoded(produced.toLong())
                } else {
                    noteDecodeFailure("${format.codec} decode produced no PCM", decrypted, rtp.sequence)
                }
            }
            AirPlayCodec.PCM -> {
                lastFrameCount = decrypted.size / (2 * format.channels.coerceAtLeast(1))
                if (lastFrameCount > 0) {
                    lastDecodeMs = System.currentTimeMillis()
                    consecutiveDecodeFailures = 0
                    decodeFailureStreak = 0
                    AirPlayDiagnostics.recordDecoded(lastFrameCount.toLong())
                    onPcm(decrypted, decrypted.size, ptsNs)
                }
            }
            else -> Unit
        }
    }

    /**
     * 记录一次解码失败。
     *
     * 连续失败时只在前几次和每隔若干次输出日志：正常播放 / 静音期间偶发一两个
     * 坏包非常常见，只有「连续几十个包都失败」才说明 key / 编码格式对不上，
     * 这时日志里带上当前 key 指纹和前几个字节，能直接和 `audio stream:` 那行
     * 的 key 对比，不用再靠猜。
     */
    private fun noteDecodeFailure(reason: String, payload: ByteArray, seq: Int) {
        decodeFailureStreak++
        AirPlayDiagnostics.recordDecodeError()
        val streak = decodeFailureStreak
        if (streak <= 3 || streak % 100 == 0) {
            val hint = if (streak >= 20) {
                " — 连续失败通常意味着 AES key 没跟上（换了 key 还在用旧的）" +
                    " 或 codec 与流不匹配，对照上面的 `audio stream:` / `FairPlay key installed`"
            } else {
                ""
            }
            AirPlayDiagnostics.log(
                AirPlayDiagnostics.Level.WARN,
                TAG,
                "$reason: streak=$streak seq=$seq payload=${payload.size}B " +
                    "codec=${format.codec} enc=$encrypted key=${fingerprint(aesKey)} " +
                    "head=${hex(payload, 0, minOf(8, payload.size))}$hint",
            )
        }
    }

    /** RTP 32-bit sample clock -> nanoseconds relative to the first packet. */
    private fun ptsNs(timestamp: Long): Long {
        if (firstRtpTimestamp < 0) firstRtpTimestamp = timestamp
        var delta = (timestamp - firstRtpTimestamp) and 0xFFFFFFFFL
        if (delta >= 0x80000000L) delta -= 0x100000000L
        return delta * 1_000_000_000L / format.sampleRate.coerceAtLeast(1)
    }

    private fun emitShorts(shorts: ShortArray, count: Int, ptsNs: Long) {
        if (debugDecodes < 10) {
            debugDecodes++
            var nz = 0
            for (i in 0 until count) if (shorts[i].toInt() != 0) nz++
            AirPlayDiagnostics.log(
                AirPlayDiagnostics.Level.DEBUG,
                TAG,
                "decode#$debugDecodes shorts=$count nonZero=$nz",
            )
        }
        val bytes = ByteArray(count * 2)
        var j = 0
        for (i in 0 until count) {
            val v = shorts[i].toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        lastPcm = bytes
        onPcm(bytes, bytes.size, ptsNs)
    }

    companion object {
        private const val TAG = "AirPlayAudioRtp"

        /**
         * 连续这么久没有解码出任何音频后，若收到一个「旧」序列号，就认为
         * 发送端重启了序列号而不是迟到的重传，直接重新对齐。
         */
        private const val RESYNC_AFTER_MS = 1_500L

        /** Safety cap only; the real limit is the configured wait time. */
        private const val MAX_PENDING = 128

        /**
         * RAOP 用 4 字节 `00 68 34 00` 表示「这个包没有音频数据」（UxPlay 的
         * `empty_packet_marker`）。它不会被加密，也不是任何编码的有效帧。
         */
        private fun isNoDataMarker(data: ByteArray): Boolean =
            data.size == 4 &&
                data[0] == 0x00.toByte() && data[1] == 0x68.toByte() &&
                data[2] == 0x34.toByte() && data[3] == 0x00.toByte()

        /** 日志里的 key 指纹：只取前 4 字节，够对比，不泄露整把 key。 */
        private fun fingerprint(key: ByteArray): String =
            key.take(4).joinToString("") { "%02x".format(it) }

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
    /** 连续「喂了数据却没有 PCM 输出」的包数，用来识别解码器卡死。 */
    private var silentDecodes = 0
    private var recreateCount = 0
    private var failureLogged = false

    init {
        open()
    }

    private fun open() {
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
            silentDecodes = 0
            failureLogged = false
        }.onFailure {
            Log.w("AirPlayAac", "AAC decoder unavailable: ${it.message}")
        }
    }

    @Synchronized
    fun decode(aacFrame: ByteArray, adts: Boolean, emit: (ByteArray, Int) -> Unit) {
        val c = codec ?: return
        var emitted = 0
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
            emitted = drain(c, emit)
        }.onFailure {
            // MediaCodec 出错后会一直抛 IllegalStateException；不再恢复的话
            // 这条音频流就永远是「包在来、没有声音」。
            if (!failureLogged) {
                failureLogged = true
                AirPlayDiagnostics.log(
                    AirPlayDiagnostics.Level.WARN,
                    "AirPlayAac",
                    "AAC decode error: ${it.message}",
                )
            }
        }
        if (emitted > 0) {
            silentDecodes = 0
        } else {
            silentDecodes++
            if (silentDecodes >= MAX_SILENT_DECODES) {
                silentDecodes = 0
                if (recreateCount < MAX_RECREATES) {
                    recreateCount++
                    AirPlayDiagnostics.log(
                        AirPlayDiagnostics.Level.WARN,
                        "AirPlayAac",
                        "no PCM output for $MAX_SILENT_DECODES packets; recreating AAC decoder " +
                            "(eld=$aacEld rate=$sampleRate ch=$channels attempt=$recreateCount)",
                    )
                    release()
                    open()
                } else if (!failureLogged) {
                    // 不是解码器卡死就是数据本身不对（key / 编码不匹配）：
                    // 不再反复重建，日志交给上层判断。
                    failureLogged = true
                    AirPlayDiagnostics.log(
                        AirPlayDiagnostics.Level.ERROR,
                        "AirPlayAac",
                        "AAC decoder still produces no PCM after $recreateCount recreations; " +
                            "check AES key / stream codec",
                    )
                }
            }
        }
    }

    private fun drain(c: MediaCodec, emit: (ByteArray, Int) -> Unit): Int {
        var emitted = 0
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
                emitted += bytes.size
                emit(bytes, bytes.size)
            }
            c.releaseOutputBuffer(idx, false)
        }
        return emitted
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

    @Synchronized
    fun release() {
        val c = codec ?: return
        codec = null
        runCatching { if (started) c.stop() }
        runCatching { c.release() }
        started = false
    }

    private companion object {
        /** 连续这么多包没有 PCM 输出就认为解码器坏了（正常解码最多延迟一两包）。 */
        const val MAX_SILENT_DECODES = 100

        /** 重建有上限：真问题（key / codec 不匹配）重建多少次都没用。 */
        const val MAX_RECREATES = 3
    }
}

/**
 * AirPlay screen-mirroring video stream (TCP).
 *
 * Header layout (128 bytes), matching UxPlay's `raop_rtp_mirror.c`:
 *   [0:4]   little-endian payload size
 *   [4]     payload type: 0x00 VCL, 0x01 unencrypted codec config, 0x02 heartbeat, 0x05 report
 *   [5]     flags (0x10 marks an IDR VCL packet)
 *   [6]     VCL option (0x56/0x5e = stream suspended, 0x16/0x1e = active)
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
    private var codec: String = ""
    private var pendingPrefix: ByteArray? = null
    private var pendingPrefixTimestamp = -1L
    private var firstTimestamp = -1L
    private var streamWidth = 0
    private var streamHeight = 0
    private var firstHeaderLogged = false
    private var firstVideoHeaderLogged = false
    private var firstVclLogged = false
    private var firstFrameLogged = false
    private var suspended = false

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
                // packet[0:3] is a little-endian payload size; packet[4] is the
                // payload type and packet[5] carries the IDR flag. Do not read
                // a little-endian u16 here: IDR packets are 0x00 0x10.
                val payloadSize = readUInt32LE(header, 0).toInt()
                val type = header[4].toInt() and 0xFF
                if (!firstHeaderLogged) {
                    firstHeaderLogged = true
                    Log.i(
                        TAG,
                        "first mirror header: size=$payloadSize type=$type " +
                            "flags=${header[5].toInt() and 0xFF} opt=${header[6].toInt() and 0xFF} " +
                            "w=${readFloat(header, 56)} h=${readFloat(header, 60)}",
                    )
                }
                if (payloadSize < 0 || payloadSize > 8 * 1024 * 1024) {
                    onError("invalid mirroring payload size $payloadSize")
                    break
                }
                val payload = ByteArray(payloadSize)
                readFully(input, payload, payloadSize)
                if (type == 0 && !firstVideoHeaderLogged) {
                    firstVideoHeaderLogged = true
                    Log.i(
                        TAG,
                        "first video header: size=$payloadSize " +
                            "w=${readFloat(header, 56)} h=${readFloat(header, 60)}",
                    )
                }
                when (type) {
                    0 -> handleVcl(header, payload)
                    1 -> handleCodecConfig(header, payload)
                    2 -> Unit // heartbeat
                    5 -> Unit // streaming report
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
        if (payload.isEmpty()) return
        updateSize(header)
        // HEVC codec data is marked by the 'hvc1' box. Layout follows UxPlay:
        // VPS/SPS/PPS records start at 0x75, each with a 4-byte marker whose
        // last two bytes are a big-endian length, then 5 bytes of header.
        if (payload.size >= 0x7A && payload[4] == 'h'.code.toByte() &&
            payload[5] == 'v'.code.toByte() && payload[6] == 'c'.code.toByte() &&
            payload[7] == '1'.code.toByte()
        ) {
            val parsed = parseHevcConfig(payload)
            if (parsed == null) {
                onError("invalid HEVC VPS/SPS/PPS")
                return
            }
            codec = "H.265"
            pendingPrefix = parsed
            pendingPrefixTimestamp = readNtpRaw(header)
            sink.setVideoFormat(codec, streamWidth, streamHeight)
            Log.i(TAG, "codec config: HEVC vps/sps/pps ${parsed.size} bytes")
            return
        }
        if (payload.size < 12) return
        val spsSize = readUInt16(payload, 6)
        if (spsSize <= 0 || 8 + spsSize + 3 > payload.size) return
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsSize = readUInt16(payload, spsSize + 9)
        if (ppsSize <= 0 || spsSize + 11 + ppsSize > payload.size) return
        val pps = payload.copyOfRange(spsSize + 11, spsSize + 11 + ppsSize)
        codec = "H.264"
        pendingPrefix = annexB(sps) + annexB(pps)
        pendingPrefixTimestamp = readNtpRaw(header)
        sink.setVideoFormat(codec, streamWidth, streamHeight)
        Log.i(TAG, "codec config: AVC sps=${sps.size} pps=${pps.size}")
    }

    /** UxPlay's `hvc1` config: VPS, SPS and PPS records after a 0x75 header. */
    private fun parseHevcConfig(payload: ByteArray): ByteArray? {
        var ptr = 0x75
        if (ptr + 5 > payload.size) return null
        val out = java.io.ByteArrayOutputStream()
        val markers = intArrayOf(0xA0, 0xA1, 0xA2)
        for (marker in markers) {
            if (ptr + 5 > payload.size) return null
            if ((payload[ptr].toInt() and 0xFF) != marker) return null
            val size = readUInt16(payload, ptr + 3)
            ptr += 5
            if (size <= 0 || ptr + size > payload.size) return null
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(payload, ptr, size)
            ptr += size
        }
        return out.toByteArray()
    }

    private fun handleVcl(header: ByteArray, payload: ByteArray) {
        val option = header[6].toInt() and 0xFF
        if (option == 0x56 || option == 0x5E) {
            if (!suspended) {
                suspended = true
                Log.i(TAG, "video stream suspended by sender")
            }
        } else if (option == 0x16 || option == 0x1E) {
            suspended = false
        }
        val decrypted = decryptor.decrypt(payload)
        if (!firstVclLogged) {
            firstVclLogged = true
            Log.i(
                TAG,
                "first VCL: payload=${payload.size} " +
                    "dec=${hex(decrypted, minOf(32, decrypted.size))} " +
                    "len0=${readUInt32(decrypted, 0)}",
            )
        }
        val annexB = convertLengthPrefixedNals(decrypted) ?: return
        val timestamp = readNtpRaw(header)
        val prefix = if (pendingPrefixTimestamp == timestamp) pendingPrefix else null
        pendingPrefix = null
        pendingPrefixTimestamp = -1
        val out = if (prefix == null) annexB else {
            val merged = ByteArray(prefix.size + annexB.size)
            System.arraycopy(prefix, 0, merged, 0, prefix.size)
            System.arraycopy(annexB, 0, merged, prefix.size, annexB.size)
            merged
        }
        val ptsUs = ntpToMicros(header)
        val keyFrame = hasIdrNal(decrypted)
        updateSize(header)
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i(TAG, "first video frame: ${out.size} bytes codec=$codec key=$keyFrame pts=$ptsUs")
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

    private fun readNtpRaw(header: ByteArray): Long =
        (readUInt32(header, 8) shl 32) or readUInt32(header, 12)

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
            val header = data[pos + 4].toInt() and 0xFF
            if (codec == "H.265") {
                val type = (header shr 1) and 0x3F
                if (type == 19 || type == 20 || type == 21) return true
            } else {
                if ((header and 0x1F) == 5) return true
            }
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

        private fun hex(data: ByteArray, length: Int): String =
            data.take(length).joinToString("") { "%02x".format(it) }
    }
}
