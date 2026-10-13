package com.aurora.music.data.ebook.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Concat-copy 合并：有序 ADTS 分片 → 单个 M4A，不重编码。
 * 等价于 `ffmpeg -f concat -i list.txt -c copy output.m4a`。
 */
object ExportAdtsRemuxer {

    /** 48kHz 下累计 [samples] 个样点的 PTS。纯函数。 */
    fun ptsUsForSamples(samples: Long): Long =
        samples * 1_000_000L / ExportAdts.SAMPLE_RATE
}

/**
 * 有序增量提交器（reorder buffer）：工作线程按完成顺序提交分片序号，
 * 连续就绪前缀即时追加进 muxer；最后一个分片落地时只剩收尾工作。
 * 与全部 join 后再 bulk remux 的输出字节一致。
 *
 * 单 owner 规则：全部 muxer 操作在 [mutex] 下执行，提交方只调
 * [submit]/[finish]/[close]。
 */
class ExportSegmentMerger(
    private val segDir: File,
    private val batchCount: Int,
    outM4a: File,
    private val onCommit: (merged: Int) -> Unit,
) {
    private val mutex = Mutex()
    private val ready = BooleanArray(batchCount)
    private var next = 0
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var totalSamples = 0L
    private var frames = 0L
    private var closed = false
    private val info = MediaCodec.BufferInfo()
    private val outFile = outM4a

    /** 标记分片 [index] 完成（含编码器 CSD）并提交就绪前缀。 */
    suspend fun submit(index: Int, csd: ByteArray) = mutex.withLock {
        check(!closed) { "合并器已关闭" }
        require(index in 0 until batchCount) { "分片序号越界" }
        if (muxer == null) {
            require(csd.isNotEmpty()) { "无编码配置" }
            val m = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, ExportAdts.SAMPLE_RATE, ExportAdts.CHANNELS,
            )
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            track = m.addTrack(format)
            m.start()
            muxer = m
        }
        ready[index] = true
        val committed = advanceExportPrefix(ready, next)
        while (next < committed) {
            appendPart(next)
            next++
            onCommit(next)
        }
    }

    /** 全部提交完毕：停 muxer。缺分片时抛异常。 */
    suspend fun finish() = mutex.withLock {
        check(!closed) { "合并器已关闭" }
        closed = true
        if (next < batchCount) throw IllegalStateException("分片缺失")
        if (frames == 0L) throw IllegalStateException("无 AAC 帧可合并")
        val m = muxer ?: throw IllegalStateException("无 AAC 帧可合并")
        try {
            m.stop()
        } finally {
            m.release()
            muxer = null
        }
    }

    /** 失败/取消路径的尽力释放（幂等，不 stop）。 */
    suspend fun close() = mutex.withLock {
        if (!closed) {
            closed = true
            runCatching { muxer?.release() }
            muxer = null
        }
    }

    private fun appendPart(j: Int) {
        val part = File(segDir, "part_$j.aac")
        if (!part.isFile || part.length() == 0L) throw IllegalStateException("分片缺失")
        val m = muxer!!
        FileInputStream(part).use { input ->
            val hdr = ByteArray(7)
            while (true) {
                val got = readExportFullyOrEof(input, hdr, 7)
                if (got == 0) break // 分片正常结束
                if (got < 7) throw IllegalStateException("分片截断：${part.name}")
                val frameLen = ExportAdts.parseFrameLength(hdr)
                val payloadLen = frameLen - 7
                val payload = ByteArray(payloadLen)
                if (readExportFullyOrEof(input, payload, payloadLen) < payloadLen) {
                    throw IllegalStateException("分片截断：${part.name}")
                }
                info.set(0, payloadLen, ExportAdtsRemuxer.ptsUsForSamples(totalSamples), 0)
                m.writeSampleData(track, ByteBuffer.wrap(payload), info)
                totalSamples += ExportAdts.SAMPLES_PER_FRAME
                frames++
            }
        }
        part.delete()
    }
}

/** 推进连续就绪前缀。纯函数。 */
internal fun advanceExportPrefix(ready: BooleanArray, from: Int): Int {
    var n = from
    while (n < ready.size && ready[n]) n++
    return n
}

/** 填充 [buf]（至多 [len]）；仅干净 EOF 返回 0。 */
private fun readExportFullyOrEof(input: InputStream, buf: ByteArray, len: Int): Int {
    var off = 0
    while (off < len) {
        val n = input.read(buf, off, len - off)
        if (n < 0) break
        off += n
    }
    return off
}
