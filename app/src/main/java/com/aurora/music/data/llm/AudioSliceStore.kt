package com.aurora.music.data.llm

import android.content.Context
import android.util.Log
import com.aurora.music.data.audiobook.AudiobookChapter
import com.aurora.music.data.subtitle.ChapterPcmReader
import com.aurora.music.data.subtitle.DecodedPcmWindow
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 每章的翻译切片缓存（16 kHz 单声道 WAV）。
 *
 * 优先从 [DecodedPcmWindow] 里直接切：那段音频已经被字幕管线解码过，这里只做
 * float→16-bit 和落盘，没有任何重复解码。窗口没覆盖到（刚开始翻译、往回拖得
 * 太远）才回退到 [ChapterPcmReader] 重新解码这一小段。
 *
 * 等价于 ffmpeg 的 `-ss A -to B`：能顺序读就不重开输入；必须跳的时候才 seek
 * 到目标点前面再解一点、丢掉多余采样。区别是我们尽量连解码都省掉。
 */
class AudioSliceStore(
    context: Context,
    private val chapter: AudiobookChapter,
) {

    /** 同一 m4b 的多个章节共用 path，目录名要用章节身份做稳定哈希。 */
    private val dir = File(
        File(context.cacheDir, "llm-slices"),
        UUID.nameUUIDFromBytes(chapter.key.toByteArray()).toString(),
    ).apply { mkdirs() }

    private val mutex = Mutex()
    private val created = ArrayDeque<File>()

    init {
        runCatching {
            val all = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            all.filter { it.name.endsWith(".part") }.forEach { it.delete() }
            val wavs = all.filter { it.isFile && it.name.endsWith(".wav") }
            wavs.dropLast(MAX_FILES).forEach { it.delete() }
            created.addAll(wavs.takeLast(MAX_FILES))
        }
    }

    /** 已经切好的文件（不等待、不阻塞）。 */
    fun peek(key: String): File? {
        val f = File(dir, "$key.wav")
        return if (f.isFile && f.length() > WavEncoder.HEADER_BYTES) f else null
    }

    /**
     * 确保 [key] 对应的 [startSec, endSec) 已经切成 WAV，返回文件。
     * 已经存在则直接复用；并发调用由 mutex 串行化。
     */
    suspend fun ensure(
        key: String,
        startSec: Double,
        endSec: Double,
        window: DecodedPcmWindow?,
    ): File? = mutex.withLock {
        peek(key)?.let { return it }
        val file = withContext(Dispatchers.IO) { build(key, startSec, endSec, window) }
        if (file != null) remember(file)
        file
    }

    private fun build(
        key: String,
        startSec: Double,
        endSec: Double,
        window: DecodedPcmWindow?,
    ): File? {
        val started = System.currentTimeMillis()
        val target = File(dir, "$key.wav")
        val tmp = File(dir, "$key.part")
        try {
            val pcm = window?.read(startSec, endSec)
            if (pcm != null) {
                WavEncoder.writeFile(tmp, pcm)
                LlmDebug.i(
                    TAG,
                    "slice $key from decoded window " +
                        "dur=${(endSec - startSec).toInt()}s in ${System.currentTimeMillis() - started}ms",
                )
            } else {
                if (!decodeToWav(tmp, startSec, endSec)) {
                    tmp.delete()
                    return null
                }
                LlmDebug.i(
                    TAG,
                    "slice $key decoded from source " +
                        "dur=${(endSec - startSec).toInt()}s in ${System.currentTimeMillis() - started}ms",
                )
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return target
        } catch (t: Throwable) {
            Log.w(TAG, "slice $key failed", t)
            runCatching { tmp.delete() }
            return null
        }
    }

    /** 回退路径：只解这一段（seek 到切片起点，读完即停），不会重解整章。 */
    private fun decodeToWav(file: File, startSec: Double, endSec: Double): Boolean {
        if (endSec - startSec < 0.2) return false
        val reader = ChapterPcmReader(
            path = chapter.path,
            startMs = chapter.startMs + (startSec.coerceAtLeast(0.0) * 1000).toLong(),
            endMs = chapter.startMs + (endSec * 1000).toLong(),
        )
        if (!reader.open()) return false
        var total = 0
        try {
            WavFileWriter(file).use { writer ->
                val buf = FloatArray(16000)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    writer.write(buf, n)
                    total += n
                }
            }
        } finally {
            runCatching { reader.close() }
        }
        return total > 0
    }

    /** 只保留最近 [MAX_FILES] 个切片，避免 cache 里堆满整章音频。 */
    private fun remember(file: File) {
        created.remove(file)
        created.addLast(file)
        while (created.size > MAX_FILES) {
            val old = created.removeFirst()
            if (old != file) runCatching { old.delete() }
        }
    }

    private companion object {
        const val TAG = "AudioSliceStore"

        /** 每章最多保留的切片文件（30 秒切片约 1 MB）。要够放「提前翻译时间」内的所有切片。 */
        const val MAX_FILES = 32
    }
}
