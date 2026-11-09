package com.aurora.music.data.cast.receiver.airplay

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * AirPlay 接收诊断。
 *
 * 「有时候有封面却没声音」「偶发卡顿」这类问题，从 UI 的会话状态看不出来，
 * 必须同时观察三条链路：
 *
 *  1. 网络：RTP 包有没有到、丢包 / 乱序 / 迟到重传、序列号是否重置；
 *  2. 抖动缓冲：当前堆积、自适应 cushion、trim / underrun / 补静音次数；
 *  3. 输出：AudioSink 是否被暂停（焦点丢失）、handleBuffer 是否被阻塞、
 *     实际写入的帧数以及发送端音量。
 *
 * 计数器始终在跑（开销只有几次 volatile 自增），日志与文件落盘由设置页开关
 * 控制 —— 关掉时既不写 Logcat，也不写文件，避免影响音频线程。
 */
object AirPlayDiagnostics {

    private const val TAG = "AirPlayDiag"
    private const val FILE_NAME = "airplay_diagnostics.log"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    const val MAX_MEMORY_LINES = 400

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class LogEntry(
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    data class SessionInfo(
        val active: Boolean = false,
        val sender: String = "",
        val codec: String = "",
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val encrypted: Boolean = false,
        val mirroring: Boolean = false,
        val startedAtMs: Long = 0L,
        val lastPacketAtMs: Long = 0L,
    )

    data class RtpInfo(
        val packets: Long = 0L,
        val bytes: Long = 0L,
        val decodedFrames: Long = 0L,
        val lostPackets: Long = 0L,
        val reorderedPackets: Long = 0L,
        val latePackets: Long = 0L,
        val duplicatePackets: Long = 0L,
        val decodeErrors: Long = 0L,
        val sequenceResyncs: Long = 0L,
        val resendRequests: Long = 0L,
        val resendPackets: Long = 0L,
        val lastPacketAtMs: Long = 0L,
    )

    data class OutputInfo(
        val running: Boolean = false,
        val paused: Boolean = false,
        val userPaused: Boolean = false,
        val volume: Float = 1f,
        val duckFactor: Float = 1f,
        val sinkBufferMs: Int = 0,
        val sinkBlockedWrites: Long = 0L,
        val sinkBlockedRetries: Long = 0L,
        val sinkBlockedMs: Long = 0L,
        val sinkUnderruns: Long = 0L,
        val framesWritten: Long = 0L,
        val focusHeld: Boolean = false,
        val focusPaused: Boolean = false,
        val writes: Long = 0L,
        val lastWriteAtMs: Long = 0L,
    )

    data class Snapshot(
        val loggingEnabled: Boolean,
        val session: SessionInfo,
        val rtp: RtpInfo,
        val output: OutputInfo,
        val timeline: AirPlayTimelineBuffer.Debug?,
        val logs: List<LogEntry>,
        val totalLogEntries: Long,
    )

    // ------------------------------------------------------------- logging

    private val logLock = Any()
    private val logs = ArrayDeque<LogEntry>()
    private val logTotal = AtomicLong()

    @Volatile private var enabled = false
    @Volatile private var appContext: Context? = null

    private val fileWriter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AirPlayDiagFile").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 由设置开关驱动：关闭后不再产生新的日志（计数器仍然工作）。 */
    fun setLogEnabled(value: Boolean) {
        enabled = value
        Log.i(TAG, if (value) "diagnostic logging enabled" else "diagnostic logging disabled")
    }

    val isLogEnabled: Boolean get() = enabled

    fun log(level: Level, tag: String, message: String) {
        if (!enabled) return
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(logLock) {
            logs.addLast(entry)
            while (logs.size > MAX_MEMORY_LINES) logs.removeFirst()
        }
        logTotal.incrementAndGet()
        when (level) {
            Level.ERROR -> Log.e(TAG, "[$tag] $message")
            Level.WARN -> Log.w(TAG, "[$tag] $message")
            Level.INFO -> Log.i(TAG, "[$tag] $message")
            Level.DEBUG -> Log.d(TAG, "[$tag] $message")
        }
        writeFile(entry)
    }

    fun clearLogs() {
        synchronized(logLock) { logs.clear() }
        logTotal.set(0L)
        val ctx = appContext ?: return
        fileWriter.execute { runCatching { File(ctx.filesDir, FILE_NAME).delete() } }
    }

    private fun writeFile(entry: LogEntry) {
        val ctx = appContext ?: return
        fileWriter.execute {
            runCatching {
                val line = "${timeFormat.format(Date(entry.timeMs))} " +
                    "${entry.level.name.first()} [${entry.tag}] ${entry.message}\n"
                val f = File(ctx.filesDir, FILE_NAME)
                if (f.length() > MAX_FILE_BYTES) f.delete()
                f.appendText(line)
            }
        }
    }

    /** 日志文件的绝对路径，供设置页显示 / 分享。 */
    fun logFilePath(): String? = appContext?.let { File(it.filesDir, FILE_NAME).absolutePath }

    // ------------------------------------------------------------- counters

    private val packetCount = AtomicLong()
    private val packetBytes = AtomicLong()
    private val decodedFrames = AtomicLong()
    private val lostPackets = AtomicLong()
    private val reorderedPackets = AtomicLong()
    private val latePackets = AtomicLong()
    private val duplicatePackets = AtomicLong()
    private val decodeErrors = AtomicLong()
    private val sequenceResyncs = AtomicLong()
    private val resendRequests = AtomicLong()
    private val resendPackets = AtomicLong()

    @Volatile private var session = SessionInfo()
    @Volatile private var lastPacketAtMs = 0L
    @Volatile private var output = OutputInfo()
    @Volatile private var timeline: AirPlayTimelineBuffer.Debug? = null

    fun sessionStarted(
        sender: String,
        codec: String,
        sampleRate: Int,
        channels: Int,
        encrypted: Boolean,
        mirroring: Boolean,
    ) {
        packetCount.set(0)
        packetBytes.set(0)
        decodedFrames.set(0)
        lostPackets.set(0)
        reorderedPackets.set(0)
        latePackets.set(0)
        duplicatePackets.set(0)
        decodeErrors.set(0)
        sequenceResyncs.set(0)
        resendRequests.set(0)
        resendPackets.set(0)
        timeline = null
        output = OutputInfo()
        lastPacketAtMs = 0L
        session = SessionInfo(
            active = true,
            sender = sender,
            codec = codec,
            sampleRate = sampleRate,
            channels = channels,
            encrypted = encrypted,
            mirroring = mirroring,
            startedAtMs = System.currentTimeMillis(),
        )
        log(Level.INFO, "Session", "started sender=$sender codec=$codec rate=$sampleRate ch=$channels enc=$encrypted mirror=$mirroring")
    }

    fun sessionEnded(reason: String) {
        if (!session.active) return
        session = session.copy(active = false)
        output = output.copy(running = false, paused = true)
        timeline = null
        lastPacketAtMs = 0L
        log(Level.INFO, "Session", "ended ($reason)")
    }

    fun noteSender(sender: String, mirroring: Boolean) {
        session = session.copy(sender = sender, mirroring = mirroring)
    }

    fun recordPacket(bytes: Int) {
        packetCount.incrementAndGet()
        packetBytes.addAndGet(bytes.toLong())
        lastPacketAtMs = System.currentTimeMillis()
    }

    fun recordDecoded(frames: Long) {
        if (frames > 0) decodedFrames.addAndGet(frames)
    }

    fun recordLost(count: Long) {
        if (count > 0) lostPackets.addAndGet(count)
    }

    fun recordReordered() {
        reorderedPackets.incrementAndGet()
    }

    fun recordLate() {
        latePackets.incrementAndGet()
    }

    fun recordDuplicate() {
        duplicatePackets.incrementAndGet()
    }

    fun recordDecodeError() {
        decodeErrors.incrementAndGet()
    }

    fun recordSequenceResync() {
        sequenceResyncs.incrementAndGet()
    }

    fun recordResendRequest() {
        resendRequests.incrementAndGet()
    }

    fun recordResendReceived() {
        resendPackets.incrementAndGet()
    }

    fun updateTimeline(info: AirPlayTimelineBuffer.Debug) {
        timeline = info
    }

    fun updateOutput(info: OutputInfo) {
        output = info
    }

    fun snapshot(): Snapshot {
        val entries = synchronized(logLock) { logs.toList() }
        return Snapshot(
            loggingEnabled = enabled,
            session = session.copy(lastPacketAtMs = lastPacketAtMs),
            rtp = RtpInfo(
                packets = packetCount.get(),
                bytes = packetBytes.get(),
                decodedFrames = decodedFrames.get(),
                lostPackets = lostPackets.get(),
                reorderedPackets = reorderedPackets.get(),
                latePackets = latePackets.get(),
                duplicatePackets = duplicatePackets.get(),
                decodeErrors = decodeErrors.get(),
                sequenceResyncs = sequenceResyncs.get(),
                resendRequests = resendRequests.get(),
                resendPackets = resendPackets.get(),
                lastPacketAtMs = lastPacketAtMs,
            ),
            output = output,
            timeline = timeline,
            logs = entries,
            totalLogEntries = logTotal.get(),
        )
    }
}
