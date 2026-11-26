package com.aurora.music.data.llm

import android.util.Log
import com.aurora.music.data.audiobook.AudiobookChapter
import com.aurora.music.data.subtitle.ChapterPcmReader
import com.aurora.music.data.subtitle.DecodedPcmWindow
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 内存级音频切片器：按需把章节的 `[startSec, endSec)` 切为 16 kHz 单声道 WAV 字节，不落盘、不做磁盘缓存。
 *
 * 优先从 [DecodedPcmWindow] 里直接切：那段音频已经被字幕管线解码过，这里只做
 * float→16-bit 编码，没有任何重复解码。窗口没覆盖到（刚开始翻译、往回拖得
 * 太远）才回退到 [ChapterPcmReader] 重新解码这一小段。
 */
class AudioSliceStore(
    private val chapter: AudiobookChapter,
) {

    private val mutex = Mutex()

    /**
     * 按需切出 `[startSec, endSec)` 的 16 kHz 单声道 WAV 字节（纯内存，不落盘）。
     * 并发调用由 mutex 串行化，避免多个解码器同时竞争 I/O。
     */
    suspend fun sliceWav(
        key: String,
        startSec: Double,
        endSec: Double,
        window: DecodedPcmWindow?,
    ): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) { build(key, startSec, endSec, window) }
    }

    private fun build(
        key: String,
        startSec: Double,
        endSec: Double,
        window: DecodedPcmWindow?,
    ): ByteArray? {
        val started = System.currentTimeMillis()
        try {
            val pcm = window?.read(startSec, endSec)
            if (pcm != null) {
                if (pcm.isEmpty()) return null
                val wav = WavEncoder.encode(pcm)
                LlmDebug.i(
                    TAG,
                    "slice $key from decoded window " +
                        "dur=${(endSec - startSec).toInt()}s size=${wav.size / 1024}KB " +
                        "in ${System.currentTimeMillis() - started}ms",
                )
                return wav
            }
            val wav = decodeToWavBytes(startSec, endSec) ?: return null
            LlmDebug.i(
                TAG,
                "slice $key decoded from source " +
                    "dur=${(endSec - startSec).toInt()}s size=${wav.size / 1024}KB " +
                    "in ${System.currentTimeMillis() - started}ms",
            )
            return wav
        } catch (t: Throwable) {
            Log.w(TAG, "slice $key failed", t)
            return null
        }
    }

    /** 回退路径：只解这一段（seek 到切片起点，读完即停），直接编码为内存中的 WAV 字节。 */
    private fun decodeToWavBytes(startSec: Double, endSec: Double): ByteArray? {
        if (endSec - startSec < 0.2) return null
        val reader = ChapterPcmReader(
            path = chapter.path,
            startMs = chapter.startMs + (startSec.coerceAtLeast(0.0) * 1000).toLong(),
            endMs = chapter.startMs + (endSec * 1000).toLong(),
        )
        if (!reader.open()) return null
        val estimatedBytes = WavEncoder.HEADER_BYTES +
            ((endSec - startSec).coerceAtLeast(0.2) * 16000.0 * 2.0).toInt()
        val out = ByteArrayOutputStream(estimatedBytes)
        out.write(ByteArray(WavEncoder.HEADER_BYTES))
        var dataSize = 0
        val fbuf = FloatArray(16000)
        val bbuf = ByteArray(fbuf.size * 2)
        try {
            while (true) {
                val n = reader.read(fbuf)
                if (n <= 0) break
                for (i in 0 until n) {
                    val s = (fbuf[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    bbuf[i * 2] = (s and 0xFF).toByte()
                    bbuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                out.write(bbuf, 0, n * 2)
                dataSize += n * 2
            }
        } finally {
            runCatching { reader.close() }
        }
        if (dataSize <= 0) return null
        val wav = out.toByteArray()
        val header = WavEncoder.header(dataSize)
        System.arraycopy(header, 0, wav, 0, WavEncoder.HEADER_BYTES)
        return wav
    }

    private companion object {
        const val TAG = "AudioSliceStore"
    }
}
