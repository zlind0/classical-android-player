package com.aurora.music.data.subtitle

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * 把有声书某一章解成 16 kHz 单声道 float PCM，供 VAD + ASR 消费。
 *
 * - 支持 m4b 内嵌章节：按 [startMs]/[endMs] 裁切；用 SEEK_TO_PREVIOUS_SYNC + 输出 PTS
 *   丢掉同步点之前的多余采样。
 * - 边解边降混、线性重采样，不把整章 PCM 攒在内存里（30 分钟 16k 单声道 ≈ 115 MB）。
 * - 与播放器完全独立：播放器暂停/快进都不影响解码（只是多花一点 CPU）。
 */
class ChapterPcmReader(
    private val path: String,
    private val startMs: Long,
    private val endMs: Long,
    private val targetRate: Int = 16000,
) : AutoCloseable {

    private val startUs = startMs.coerceAtLeast(0L) * 1000L
    private val endUs = if (endMs > 0L) endMs * 1000L else Long.MAX_VALUE

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var info: MediaCodec.BufferInfo? = null
    private var sampleRate = 48000
    private var channels = 2
    private var sawInputEos = false
    private var sawOutputEos = false
    private var formatReady = false
    private var started = false

    // 源采样率下的单声道待重采样缓冲 + 分数读指针
    private var pending = FloatArray(0)
    private var pos = 0.0

    /** 初始化成功返回 true。 */
    fun open(): Boolean {
        if (started) return true
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(path)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; fmt = f; break
                }
            }
            if (track < 0 || fmt == null) {
                ex.release(); return false
            }
            ex.selectTrack(track)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            sampleRate = runCatching { fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48000)
            channels = runCatching { fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(2)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            c.configure(fmt, null, null, 0)
            c.start()
            extractor = ex
            codec = c
            info = MediaCodec.BufferInfo()
            started = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "open $path@$startMs failed: ${t.message}")
            close()
            false
        }
    }

    /**
     * 读取最多 [out].size 个 16 kHz 单声道采样，返回实际个数；0 表示本章读完。
     */
    fun read(out: FloatArray): Int {
        if (!open()) return 0
        var produced = 0
        while (produced == 0) {
            // 先榨干重采样缓冲
            produced = take(out)
            if (produced > 0) break
            if (sawOutputEos) break
            if (!pumpOnce()) break
        }
        return produced
    }

    /** 解一块输入并取一块输出；返回是否还有数据。 */
    private fun pumpOnce(): Boolean {
        val c = codec ?: return false
        val ex = extractor ?: return false
        val bi = info ?: return false

        if (!sawInputEos) {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx)!!
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) {
                    c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sawInputEos = true
                } else {
                    val ptsUs = ex.sampleTime
                    if (endUs != Long.MAX_VALUE && ptsUs > endUs) {
                        c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        c.queueInputBuffer(inIdx, 0, size, ptsUs, 0)
                        ex.advance()
                    }
                }
            }
        }

        while (true) {
            val outIdx = c.dequeueOutputBuffer(bi, 10_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = c.outputFormat
                sampleRate = runCatching { nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(sampleRate)
                channels = runCatching { nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(channels)
                formatReady = true
                continue
            }
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) return !sawOutputEos
            if (outIdx < 0) continue
            if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            if (bi.size > 0) {
                val buf = c.getOutputBuffer(outIdx)
                if (buf != null) {
                    buf.position(bi.offset)
                    buf.limit(bi.offset + bi.size)
                    val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = sb.remaining() / channels.coerceAtLeast(1)
                    val mono = ShortArray(sb.remaining())
                    sb.get(mono)
                    val outFrames = trimToWindow(mono, frames, bi.presentationTimeUs)
                    appendMono(mono, outFrames)
                }
            }
            c.releaseOutputBuffer(outIdx, false)
            if (bi.size > 0) return true
            if (sawOutputEos) return false
            if (sawInputEos) continue
            return true
        }
    }

    /** 按 PTS 裁掉章节起点之前 / 终点之后的部分，返回要保留的帧数。 */
    private fun trimToWindow(interleaved: ShortArray, frames: Int, ptsUs: Long): Int {
        if (frames <= 0) return 0
        val ch = channels.coerceAtLeast(1)
        val frameUs = 1_000_000.0 / sampleRate.coerceAtLeast(1)
        var from = 0
        var to = frames
        if (ptsUs < startUs) {
            from = (((startUs - ptsUs) / frameUs).toInt()).coerceIn(0, frames)
        }
        if (endUs != Long.MAX_VALUE) {
            val endFrame = ((endUs - ptsUs) / frameUs).toInt()
            if (endFrame < to) to = endFrame.coerceAtLeast(0)
        }
        if (from >= to) return 0
        if (from > 0) {
            // 把 [from,to) 挪到数组开头，保持交错布局
            System.arraycopy(interleaved, from * ch, interleaved, 0, (to - from) * ch)
        }
        return to - from
    }

    private fun appendMono(interleaved: ShortArray, frames: Int) {
        if (frames <= 0) return
        val ch = channels.coerceAtLeast(1)
        val old = pending.size
        val merged = FloatArray(old + frames)
        System.arraycopy(pending, 0, merged, 0, old)
        var w = old
        if (ch == 1) {
            for (i in 0 until frames) merged[w++] = interleaved[i] / 32768f
        } else {
            for (i in 0 until frames) {
                val base = i * ch
                var sum = 0f
                for (c in 0 until ch) sum += interleaved[base + c] / 32768f
                merged[w++] = sum / ch
            }
        }
        pending = merged
    }

    private fun take(out: FloatArray): Int {
        if (pending.size < 2) return 0
        val step = sampleRate.toDouble() / targetRate
        var n = 0
        while (n < out.size && pos + 1 < pending.size) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            out[n++] = pending[i] * (1f - frac) + pending[i + 1] * frac
            pos += step
        }
        val drop = min(pos.toInt(), pending.size)
        if (drop > 0) {
            pending = pending.copyOfRange(drop, pending.size)
            pos -= drop
        }
        return n
    }

    override fun close() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor?.release() }
        codec = null
        extractor = null
        pending = FloatArray(0)
        pos = 0.0
    }

    private companion object {
        const val TAG = "ChapterPcmReader"
    }
}
