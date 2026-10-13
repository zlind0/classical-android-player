package com.aurora.music.data.ebook.export

/**
 * 最小 ADTS 封装（raw AAC，MPEG-4 LC，48kHz 单声道）：拼接/合并的基本单位，
 * 行为等价于 ffmpeg concat demuxer。纯函数。
 */
object ExportAdts {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val SAMPLES_PER_FRAME = 1024

    /** 单个 AAC payload 的 7 字节 ADTS 头（无 CRC）。 */
    fun header(payloadLen: Int): ByteArray {
        require(payloadLen >= 0) { "payload 为负" }
        val frameLen = payloadLen + 7
        require(frameLen <= 0x1FFF) { "帧过大" }
        return byteArrayOf(
            0xFF.toByte(),
            // syncword 低 4 位 + ID(0=MPEG-4) + layer(00) + protection_absent(1)
            0xF1.toByte(),
            // profile(01=LC) + freqIdx(0011=48k) + private(0) + chanCfg 高位(0)
            (((1 and 3) shl 6) or ((3 and 15) shl 2) or ((1 shr 2) and 1)).toByte(),
            // chanCfg 低 2 位 + 帧长 bit12..11
            (((1 and 3) shl 6) or ((frameLen shr 11) and 3)).toByte(),
            // 帧长 bit10..3
            ((frameLen shr 3) and 0xFF).toByte(),
            // 帧长 bit2..0 + buffer fullness(0x7FF, VBR) 高 5 位
            (((frameLen and 7) shl 5) or 0x1F).toByte(),
            // buffer fullness 低 6 位 + raw blocks(0)
            0xFC.toByte(),
        )
    }

    /** 解析 7 字节 ADTS 头，返回整帧长度（头 + payload）。 */
    fun parseFrameLength(hdr: ByteArray): Int {
        require(hdr.size >= 7) { "ADTS 头不足 7 字节" }
        require(hdr[0] == 0xFF.toByte() && (hdr[1].toInt() and 0xF0) == 0xF0) {
            "ADTS 同步字错误"
        }
        require((hdr[1].toInt() and 0x01) == 0x01) { "不支持带 CRC 的 ADTS" }
        val frameLen = ((hdr[3].toInt() and 3) shl 11) or
            ((hdr[4].toInt() and 0xFF) shl 3) or
            ((hdr[5].toInt() and 0xFF) shr 5)
        require(frameLen >= 7) { "ADTS 帧长错误" }
        return frameLen
    }
}
