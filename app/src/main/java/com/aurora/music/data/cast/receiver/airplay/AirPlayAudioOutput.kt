package com.aurora.music.data.cast.receiver.airplay

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.aurora.music.playback.PcmStreamPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AirPlay audio playout: timeline buffer (jitter + PTS) in front of the shared
 * Media3/DSP sink. The reference receiver drives Oboe with a pull callback; the
 * Media3 sink is push-based, so a dedicated writer thread pulls fixed 10 ms
 * chunks and lets `handleBuffer` block when the sink is full.
 */
@UnstableApi
class AirPlayAudioOutput(
    context: Context,
    sink: AudioSink,
    sessionId: Int,
    onFocusLoss: () -> Unit,
) {
    private val player = PcmStreamPlayer(context, sink, sessionId, onFocusLoss) { hasFocus ->
        // 焦点丢失期间 timeline 还在收包；恢复后先丢掉旧数据，否则会先播放
        // 一段几百毫秒前的积压，听感上像“恢复后卡一下”。
        if (hasFocus) timeline?.flushAndReprime()
        AirPlayDiagnostics.log(
            AirPlayDiagnostics.Level.INFO,
            "Focus",
            if (hasFocus) "audio focus regained; output resumed" else "audio focus lost; output paused",
        )
        publishState()
    }
    private val running = AtomicBoolean(false)
    private var timeline: AirPlayTimelineBuffer? = null
    private var thread: Thread? = null
    private var appliedConfig: AirPlayConfig? = null

    @Volatile private var sampleRate = 44100
    @Volatile private var channels = 2
    private var scratch = ShortArray(0)
    private var bytes = ByteArray(0)
    /** 发送端下发的音量与本地解说 duck 系数分开保存，避免互相覆盖。 */
    @Volatile private var baseVolume = 1f
    @Volatile private var duckFactor = 1f

    fun start(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8000)
        this.channels = channels.coerceAtLeast(1)
        val config = AirPlayTuning.config
        appliedConfig = config
        val tl = AirPlayTimelineBuffer(
            this.sampleRate,
            this.channels,
            staticCushionMs = config.bufferMs,
            plcEnabled = config.plcEnabled,
            plcMaxMs = config.plcMaxMs,
        )
        timeline = tl
        // The timeline buffer is the jitter absorber; the output buffer only has
        // to cover writer scheduling and DSP latency, so keep it small to avoid
        // adding lip-sync offset during screen mirroring.
        player.setBufferSizeMs(80)
        player.start(this.sampleRate, this.channels)
        tl.noteOutputBufferFrames(this.sampleRate * 80 / 1000)
        val frames = (this.sampleRate / 100).coerceAtLeast(64)
        scratch = ShortArray(frames * this.channels)
        bytes = ByteArray(frames * this.channels * 2)
        running.set(true)
        thread = Thread({ writerLoop(tl, frames) }, "AirPlayPcmWriter").apply {
            priority = Thread.NORM_PRIORITY + 2
            isDaemon = true
            start()
        }
        AirPlayDiagnostics.log(
            AirPlayDiagnostics.Level.INFO,
            "Output",
            "audio output started rate=$sampleRate ch=$channels " +
                "buffer=${config.bufferMs}ms plc=${config.plcEnabled}/${config.plcMaxMs}ms",
        )
        publishState()
    }

    /** 设置页修改缓冲 / PLC 后实时应用。 */
    fun applyTuning(config: AirPlayConfig) {
        val tl = timeline ?: return
        val previous = appliedConfig
        if (previous == config) return
        appliedConfig = config
        tl.updateCushionMs(config.bufferMs)
        tl.updatePlc(config.plcEnabled, config.plcMaxMs)
        // 只有缓冲时间真正变化时才重新预缓冲；单独改 PLC / 重传参数不打断声音。
        if (previous == null || previous.bufferMs != config.bufferMs) {
            tl.flushAndReprime()
        }
        AirPlayDiagnostics.log(
            AirPlayDiagnostics.Level.INFO,
            "Output",
            "tuning applied: buffer=${config.bufferMs}ms plc=${config.plcEnabled}/${config.plcMaxMs}ms",
        )
        publishState()
    }

    fun writePcm(pcm: ByteArray, length: Int, ptsNs: Long) {
        timeline?.write(pcm, length, ptsNs)
    }

    fun flush() {
        timeline?.flushAndReprime()
        player.resume()
        AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, "Output", "flush")
        publishState()
    }

    fun resume() {
        timeline?.flushAndReprime()
        player.resume()
        AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, "Output", "resumed")
        publishState()
    }

    fun setVolume(volume: Float) {
        baseVolume = volume.coerceIn(0f, 1f)
        applyVolume()
        AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, "Output", "sender volume=${"%.2f".format(baseVolume)}")
        publishState()
    }

    /** 本地解说 TTS 期间压低发送端音频（25%），不暂停也不改发送端音量。 */
    fun setDuckFactor(factor: Float) {
        duckFactor = factor.coerceIn(0f, 1f)
        player.setDuckHold(factor < 1f)
        applyVolume()
        publishState()
    }

    private fun applyVolume() {
        player.setVolume((baseVolume * duckFactor).coerceIn(0f, 1f))
    }

    val isPaused: Boolean get() = player.isPaused

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        timeline?.stop()
        timeline = null
        thread?.join(500)
        thread = null
        player.release()
        AirPlayDiagnostics.log(AirPlayDiagnostics.Level.INFO, "Output", "audio output stopped")
        AirPlayDiagnostics.updateOutput(
            AirPlayDiagnostics.OutputInfo(running = false, paused = true),
        )
    }

    private fun publishState() {
        val d = player.debugState()
        AirPlayDiagnostics.updateOutput(
            AirPlayDiagnostics.OutputInfo(
                running = running.get(),
                paused = d.paused,
                userPaused = d.userPaused,
                volume = baseVolume * duckFactor,
                duckFactor = duckFactor,
                sinkBufferMs = d.bufferSizeMs,
                sinkBlockedWrites = d.sinkBlockedWrites,
                sinkBlockedRetries = d.sinkBlockedRetries,
                sinkBlockedMs = d.sinkBlockedMs,
                sinkUnderruns = d.sinkUnderruns,
                framesWritten = d.framesWritten,
                focusHeld = d.focusHeld,
                focusPaused = d.focusPaused,
                writes = d.writes,
                lastWriteAtMs = d.lastWriteAtMs,
            ),
        )
        timeline?.debugInfo()?.let { AirPlayDiagnostics.updateTimeline(it) }
    }

    private fun writerLoop(tl: AirPlayTimelineBuffer, frames: Int) {
        val channels = this.channels
        val out = scratch
        val encoded = bytes
        var publishCounter = 0
        while (running.get()) {
            if (player.isPaused) {
                Thread.sleep(10)
                // 暂停时仍刷新一次诊断，方便看到「会话正常但输出被暂停」。
                if (++publishCounter >= 50) {
                    publishCounter = 0
                    publishState()
                }
                continue
            }
            val full = tl.read(out, frames)
            if (!full && !hasProducerData(tl)) {
                // Priming/underrun: still submit silence so the sink drains at
                // real-time pace, then wait a little before trying again.
                Thread.sleep(5)
            }
            var j = 0
            for (i in 0 until frames * channels) {
                val v = out[i].toInt()
                encoded[j++] = (v and 0xFF).toByte()
                encoded[j++] = ((v shr 8) and 0xFF).toByte()
            }
            player.writePcm(encoded, j, channels)
            // 10 ms 一块，约每 250 ms 刷新一次快照，避免 UI 轮询读到旧值。
            if (++publishCounter >= 25) {
                publishCounter = 0
                publishState()
            }
        }
    }

    private fun hasProducerData(tl: AirPlayTimelineBuffer): Boolean = tl.availableMs() > 0

    companion object {
        private const val TAG = "AirPlayAudioOutput"
    }
}
