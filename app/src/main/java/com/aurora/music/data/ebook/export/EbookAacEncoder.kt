package com.aurora.music.data.ebook.export

import java.io.File

/** AAC-LC 48kHz 单声道的 CSD（objectType 2, freqIdx 3, chanCfg 1）。 */
val EXPORT_CSD_48K_MONO = byteArrayOf(0x11.toByte(), 0x88.toByte())

/**
 * 单批 → 单个 ADTS 分片文件（vendored vo-aacenc，VisualOn Apache 2.0；
 * AAC-LC 128kbps mono 48kHz，编码器直接写 ADTS 头）。
 * 每次调用独立 native 编码器实例、无共享状态，分片编码可全并行。
 * 失败抛 [IllegalStateException]（带可读信息）。
 */
object EbookAacEncoder {
    init {
        System.loadLibrary("aurora_aac")
    }

    @JvmStatic
    external fun encodeFile(pcmPath: String, aacPath: String): Boolean

    fun encodeToAdts(pcm: File, aac: File): ByteArray {
        if (!pcm.isFile || pcm.length() == 0L) throw IllegalStateException("无 PCM 数据")
        if (!encodeFile(pcm.absolutePath, aac.absolutePath) || aac.length() == 0L) {
            throw IllegalStateException("AAC 编码无输出")
        }
        return EXPORT_CSD_48K_MONO
    }
}
