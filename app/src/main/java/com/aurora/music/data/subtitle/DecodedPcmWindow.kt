package com.aurora.music.data.subtitle

import kotlin.math.abs
import kotlin.math.min

/**
 * 按章节时间索引的「已解码 PCM」窗口（16 kHz 单声道 float）。
 *
 * SubtitleSession 解码音频喂 VAD/ASR 时顺手写入这里，翻译层直接从窗口里切块：
 * 同一段音频只解一次，等价于 ffmpeg 一遍解码、按 `-ss/-to` 切出多段，
 * 而不是每切一段都重新 seek + 新建 MediaCodec。
 *
 * 只保留最近 [retentionSec] 秒。窗口没覆盖满（刚打开翻译层、往回拖太远等）
 * 时 [read] 返回 null，调用方回退到按需解码这一小段。
 *
 * 线程安全：写入来自字幕解码线程，读取来自翻译 worker。
 */
class DecodedPcmWindow(private val retentionSec: Double = 120.0) {

    private class Block(val start: Double, val samples: FloatArray) {
        val end: Double get() = start + samples.size / SAMPLE_RATE
    }

    private val lock = Any()
    private val blocks = ArrayDeque<Block>()

    /** 追加一段从 [startSec] 开始的连续 PCM（解码管线的顺序输出）。 */
    fun append(startSec: Double, pcm: FloatArray) {
        if (pcm.isEmpty()) return
        synchronized(lock) {
            val last = blocks.lastOrNull()
            if (last != null) {
                val delta = startSec - last.end
                if (delta > GAP_TOLERANCE) {
                    // 解码管线跳到别的区间（拖进度条 / 补空洞）：旧样本不再连续。
                    blocks.clear()
                } else if (delta < -RESTART_TOLERANCE) {
                    // 往回跳得太多，说明开始解码另一个时间线了；旧窗口留着只会
                    // 妨碍后续 append，直接丢掉。
                    blocks.clear()
                } else if (delta < -GAP_TOLERANCE) {
                    // 重开管线时多解的一小段（约 1 秒）与已有窗口重叠：丢掉前缀。
                    val skip = ((-delta) * SAMPLE_RATE).toInt().coerceIn(0, pcm.size)
                    if (skip >= pcm.size) return
                    blocks.addLast(Block(last.end, pcm.copyOfRange(skip, pcm.size)))
                    trim()
                    return
                }
            }
            blocks.addLast(Block(startSec, pcm.copyOf()))
            trim()
        }
    }

    /** 返回 [startSec, endSec) 的样本；窗口没完整覆盖则返回 null。 */
    fun read(startSec: Double, endSec: Double): FloatArray? {
        if (endSec <= startSec) return null
        synchronized(lock) {
            if (blocks.isEmpty()) return null
            var i = blocks.indexOfLast { it.start <= startSec + EPS }
            if (i < 0) return null
            var block = blocks[i]
            if (startSec < block.start - EPS) return null

            val total = ((endSec - startSec) * SAMPLE_RATE).toInt().coerceAtLeast(1)
            val out = FloatArray(total)
            var written = 0
            var cursor = startSec
            while (written < total) {
                val offset = ((cursor - block.start) * SAMPLE_RATE).toInt()
                if (offset >= block.samples.size) {
                    i++
                    if (i >= blocks.size) return null
                    block = blocks[i]
                    // 连续块之间不应该有缝；有缝说明覆盖不完整。
                    if (abs(block.start - cursor) > EPS) return null
                    continue
                }
                val take = min(block.samples.size - offset, total - written)
                block.samples.copyInto(out, written, offset, offset + take)
                written += take
                cursor += take / SAMPLE_RATE
            }
            return out
        }
    }

    /** 只保留最近 [retentionSec] 秒，避免整章 PCM 常驻（30 分钟 ≈ 115 MB）。 */
    private fun trim() {
        val newestEnd = blocks.lastOrNull()?.end ?: return
        val floor = newestEnd - retentionSec
        while (blocks.size > 1 && blocks.first().end <= floor) blocks.removeFirst()
    }

    private companion object {
        const val SAMPLE_RATE = 16000.0

        /** 认为两段音频连续的容差（秒）。 */
        const val GAP_TOLERANCE = 0.05

        /** 往回超过这么多秒就当成换了时间线（正常重开只多解约 1 秒）。 */
        const val RESTART_TOLERANCE = 2.0
        const val EPS = 1e-3
    }
}
