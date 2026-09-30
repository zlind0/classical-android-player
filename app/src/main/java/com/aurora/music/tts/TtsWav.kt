package com.aurora.music.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 最小 WAV 编解码 + 归一化：TTS 各路音频统一成 48kHz 立体声 16bit WAV 再进播放链。 */
object TtsWav {
    data class Pcm(val sampleRate: Int, val channels: Int, val bits: Int, val data: ByteArray)

    fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"

    fun decodeWav(bytes: ByteArray): Pcm {
        require(isWav(bytes)) { "不是 WAV" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12)
        var sampleRate = 16000
        var channels = 1
        var bits = 16
        var pcm = ByteArray(0)
        while (buf.remaining() >= 8) {
            val id = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val size = buf.int
            if (size < 0 || size > buf.remaining()) break
            val chunk = ByteArray(size).also { buf.get(it) }
            when (id) {
                "fmt " -> {
                    val b = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                    if (chunk.size >= 16) {
                        channels = b.getShort(2).toInt()
                        sampleRate = b.getInt(4)
                        bits = b.getShort(14).toInt()
                    }
                }
                "data" -> pcm = chunk
            }
            if (size % 2 == 1 && buf.hasRemaining()) buf.get()
        }
        require(pcm.isNotEmpty()) { "WAV 无 data 块" }
        return Pcm(sampleRate, channels, bits, pcm)
    }

    fun encodeWav(sampleRate: Int, channels: Int, bits: Int, pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * bits / 8)
        header.putShort((channels * bits / 8).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    /**
     * 归一化到 48kHz 立体声 16bit（播放链 DSP 只吃这个格式）：
     * 8bit 无符号→16bit；单声道→双声道复制；任意采样率→线性插值重采样。
     */
    fun normalize48kStereo16(pcm: Pcm): ByteArray {
        val frames = when (pcm.bits) {
            8 -> pcm.data.size / pcm.channels
            else -> pcm.data.size / (pcm.channels * 2)
        }.coerceAtLeast(0)
        if (frames == 0) return ByteArray(0)
        fun sample16(f: Int, ch: Int): Int {
            val c = ch.coerceIn(0, pcm.channels - 1)
            return if (pcm.bits == 8) {
                ((pcm.data[f * pcm.channels + c].toInt() and 0xFF) - 128) shl 8
            } else {
                val o = (f * pcm.channels + c) * 2
                if (o + 1 >= pcm.data.size) 0
                else (pcm.data[o].toInt() and 0xFF) or (pcm.data[o + 1].toInt() shl 8)
            }
        }
        val outFrames = if (pcm.sampleRate == 48000) frames
        else ((frames.toLong() * 48000) / pcm.sampleRate.coerceAtLeast(1)).toInt()
        val out = ByteArray(outFrames * 4)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outFrames) {
            val pos = if (pcm.sampleRate == 48000) i.toDouble() else i.toDouble() * frames / outFrames
            val i0 = pos.toInt().coerceIn(0, frames - 1)
            val i1 = (i0 + 1).coerceIn(0, frames - 1)
            val frac = (pos - i0).toFloat()
            val l = (sample16(i0, 0) * (1 - frac) + sample16(i1, 0) * frac).toInt().coerceIn(-32768, 32767)
            val r = (sample16(i0, 1) * (1 - frac) + sample16(i1, 1) * frac).toInt().coerceIn(-32768, 32767)
            ob.putShort(l.toShort())
            ob.putShort(r.toShort())
        }
        return out
    }

    /**
     * 24k→48k 2 倍上采样内插系数：截断 sinc（截止 12kHz）× Blackman 窗，奇样点用 14 抽头 FIR；
     * 偶样点直通。直流增益归一到 1（常数信号原样通过）。
     */
    private val UPSAMPLE_HALF_TAPS: FloatArray = run {
        val k = 7
        val taps = FloatArray(2 * k)
        for (i in taps.indices) {
            val d = (2 * (i - k) + 1).toDouble() // ±1, ±3, …, ±13（输出样点单位）
            val sinc = sin(PI * d / 2.0) / (PI * d / 2.0)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (2 * k - 1)) + 0.08 * cos(4 * PI * i / (2 * k - 1))
            taps[i] = (sinc * w).toFloat()
        }
        val sum = taps.sum()
        for (i in taps.indices) taps[i] /= sum
        taps
    }

    /** 24k 单声道 16bit → 48k 单声道：带限内插（偶样点直通，奇样点 FIR），抑制 12kHz 以上镜像。 */
    fun upsample24kTo48kMono(mono24k: ByteArray): ByteArray {
        val frames = mono24k.size / 2
        if (frames == 0) return ByteArray(0)
        val taps = UPSAMPLE_HALF_TAPS
        val k = taps.size / 2
        val ib = ByteBuffer.wrap(mono24k).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(frames * 2 * 2)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            ob.putShort(ib.getShort(i * 2)) // 偶样点：原样直通
            var acc = 0.0
            for (j in taps.indices) {
                val src = (i - (k - 1) + j).coerceIn(0, frames - 1)
                acc += ib.getShort(src * 2).toInt() * taps[j]
            }
            ob.putShort(acc.roundToInt().coerceIn(-32768, 32767).toShort())
        }
        return out
    }

    /** 48k 单声道 16bit → 48k 立体声：左右声道复制，无重采样（内置引擎原生 48k 主路径）。 */
    fun mono48kToStereo48k(mono: ByteArray): ByteArray {
        val frames = mono.size / 2
        if (frames == 0) return ByteArray(0)
        val ib = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteArray(frames * 4)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = ib.getShort(i * 2)
            ob.putShort(s)
            ob.putShort(s)
        }
        return out
    }

    /**
     * 24k 单声道 16bit → 48k 立体声（内置引擎回落路径：离线模型不支持原生 48k 输出时用；
     * 带限内插，非线性平均，听感明显好于直接 ×2）。
     */
    fun mono24kToStereo48k(mono: ByteArray): ByteArray =
        mono48kToStereo48k(upsample24kTo48kMono(mono))

    /**
     * 48k 立体声 16bit PCM 数字增益（听书音量 0.2~2.0 用；gain==1 原样返回）。
     * 超过 0dB 的部分硬钳到 ±32767，后续 DSP 链的 limiter 会再兜一层。
     */
    fun applyGainStereo16(pcm: ByteArray, gain: Float): ByteArray {
        if (gain == 1f || pcm.isEmpty()) return pcm
        val out = ByteArray(pcm.size)
        val ib = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ib.getShort(i).toInt()
            ob.putShort(i, (s * gain).toInt().coerceIn(-32768, 32767).toShort())
            i += 2
        }
        if (i < pcm.size) out[i] = pcm[i]
        return out
    }
}
