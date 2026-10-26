package com.aurora.music.data.ebook.export

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aurora.music.tts.MsEmbeddedEngine
import com.aurora.music.tts.MsVoice
import com.aurora.music.tts.MsVoices
import com.aurora.music.tts.TtsWav
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * 离线合成基块。线程规则：
 * - [ensureExportVoice] 只在工作线程启动前串行跑一次。
 * - 每个工作线程独占一个 [ExportWorkerSession]（MsEmbeddedEngine /
 *   TextToSpeech 实例绝不跨线程共享）。
 * - 输出为裸 PCM（48kHz 单声道 16bit，无 WAV 头），流式写 [out]；
 *   单线程内存占用恒定，与章节长度无关。
 */
interface ExportWorkerSession : Closeable {
    /** 把一批次文本合成为单声道 PCM 追加到 [out]，返回写入帧数。 */
    suspend fun synthOne(batch: String, out: OutputStream, cancelled: AtomicBoolean): Long

    override fun close() {}
}

/** 一次性串行安装内置语音（并发安装会互相踩文件）。 */
suspend fun ensureExportVoice(
    context: Context,
    voiceCode: String,
    onInstall: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
): MsVoice = withContext(Dispatchers.IO) {
    val voice = MsVoices.byCode(voiceCode) ?: MsVoices.byCode(MsVoices.DEFAULT)!!
    val engine = MsEmbeddedEngine(context)
    if (!engine.isInstalled(voice.code)) {
        onInstall(0, 1, voice.code)
        engine.ensureVoice(voice.code) { d, t, n -> onInstall(d, t, n) }
        onInstall(1, 1, voice.code)
    }
    voice
}

/** 每工作线程一个内置引擎会话：SpeechSynthesizer 建一次复用到底（多批次），
 *  整书结束才关。高频建/关实例会让 MS SDK native 堆单调上涨直至 OOM。
 *  一个实例独占一线程，永不共享。 */
class InternalExportSession(
    context: Context,
    private val voice: MsVoice,
    private val rate: Float,
    private val pitch: Float,
) : ExportWorkerSession {
    private val engine = MsEmbeddedEngine(context.applicationContext)
    private var session: MsEmbeddedEngine.Session = engine.openSession(voice)

    override suspend fun synthOne(
        batch: String,
        out: OutputStream,
        cancelled: AtomicBoolean,
    ): Long = withContext(Dispatchers.IO) {
        var frames = 0L
        // 奇字节进位：PCM 块应为偶长，但不信任它。
        var carry = -1
        var streamed = 0L
        fun writeMono(bytes: ByteArray, len: Int) {
            var start = 0
            var n = len
            if (carry >= 0) {
                if (n <= 0) return
                out.write(carry)
                out.write(bytes[0].toInt())
                frames++
                start = 1
                n--
                carry = -1
            }
            val even = n - (n and 1)
            if (even > 0) {
                out.write(bytes, start, even)
                frames += even / 2
            }
            carry = if (even < n) bytes[start + even].toInt() and 0xFF else -1
        }
        coroutineContext.ensureActive()
        if (cancelled.get()) return@withContext frames
        fun once(): ByteArray = session.synthBatch(
            batch,
            rate,
            pitch,
            onPcmChunk = { chunk ->
                if (chunk.isNotEmpty()) {
                    writeMono(chunk, chunk.size)
                    streamed += chunk.size
                }
            },
            isStopped = { cancelled.get() },
        )
        // 原生 48k 路径按块回调 onPcmChunk；24k 回落路径不回调，
        // 直接返回整段 PCM（长度受批次上限约束）。
        val full = try {
            once()
        } catch (e: Exception) {
            if (cancelled.get()) throw e
            coroutineContext.ensureActive()
            // 合成器实例可能已坏：重建一次再试（保留已探测的 48k/24k 决定），
            // 仍失败才上报。
            runCatching { session.reset() }
            once()
        }
        if (streamed == 0L && full.isNotEmpty()) {
            writeMono(full, full.size)
            carry = -1 // 丢掉尾部奇字节，凑不成一个样点
        }
        frames
    }

    override fun close() {
        runCatching { session.close() }
    }
}

/** 每工作线程一个系统 TTS 会话，一个 TextToSpeech 独占一线程，永不共享。 */
class SystemExportSession private constructor(
    private val appContext: Context,
    private val tts: TextToSpeech,
) : ExportWorkerSession {

    companion object {
        suspend fun create(context: Context, enginePkg: String?, rate: Float, pitch: Float): SystemExportSession {
            val app = context.applicationContext
            val tts = suspendCancellableCoroutine<TextToSpeech?> { cont ->
                var t: TextToSpeech? = null
                val listener = TextToSpeech.OnInitListener { status ->
                    if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) t else null)
                }
                t = if (!enginePkg.isNullOrBlank()) TextToSpeech(app, listener, enginePkg)
                else TextToSpeech(app, listener)
                cont.invokeOnCancellation { runCatching { t?.shutdown() } }
            } ?: throw IllegalStateException("系统 TTS 不可用")
            tts.setSpeechRate(rate.coerceIn(0.5f, 2f))
            tts.setPitch(pitch.coerceIn(0.5f, 2f))
            pickVoice(tts)?.let { runCatching { tts.voice = it } }
                ?: runCatching { tts.language = Locale.getDefault() }
            return SystemExportSession(app, tts)
        }

        /** 自动选音色：zh-CN 优先，其次任意 zh，否则引擎默认。 */
        private fun pickVoice(tts: TextToSpeech): android.speech.tts.Voice? {
            val voices = tts.voices.orEmpty()
            if (voices.isEmpty()) return null
            return voices.firstOrNull { it.locale.language == "zh" && it.locale.country == "CN" }
                ?: voices.firstOrNull { it.locale.language == "zh" }
        }
    }

    override suspend fun synthOne(
        batch: String,
        out: OutputStream,
        cancelled: AtomicBoolean,
    ): Long = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (cancelled.get()) return@withContext 0L
        val tmp = File.createTempFile("ebookutt", ".wav", appContext.cacheDir)
        try {
            if (!synthToFile(batch, tmp)) return@withContext 0L
            wavFileToMono48kBytes(tmp, out)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private suspend fun synthToFile(text: String, tmp: File): Boolean =
        withContext(Dispatchers.IO) {
            val id = "ebook-export-${UUID.randomUUID()}"
            suspendCancellableCoroutine<Boolean> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(true)
                    }
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }
                    override fun onStart(utteranceId: String?) {}
                })
                val r = tts.synthesizeToFile(text, Bundle(), tmp, id)
                if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                cont.invokeOnCancellation { runCatching { tts.stop() } }
            }
        }

    override fun close() {
        runCatching { tts.shutdown() }
    }
}

/**
 * 系统 TTS 的 WAV 文件 → 48kHz 单声道 16bit PCM 并流式写 [out]。
 * 批次 ≤800 字，文件很小（几 MB 内），整读 + 下混 + 线性重采样即可。
 */
private fun wavFileToMono48kBytes(wav: File, out: OutputStream): Long {
    val bytes = wav.readBytes()
    val pcm = TtsWav.decodeWav(bytes)
    val frames = when (pcm.bits) {
        8 -> pcm.data.size / pcm.channels
        else -> pcm.data.size / (pcm.channels * 2)
    }.coerceAtLeast(0)
    require(frames > 0) { "WAV 无 data 块" }
    fun sampleMono(f: Int): Int {
        var sum = 0
        for (c in 0 until pcm.channels) {
            sum += if (pcm.bits == 8) {
                ((pcm.data[f * pcm.channels + c].toInt() and 0xFF) - 128) shl 8
            } else {
                val o = (f * pcm.channels + c) * 2
                if (o + 1 >= pcm.data.size) 0
                else (pcm.data[o].toInt() and 0xFF) or (pcm.data[o + 1].toInt() shl 8)
            }
        }
        return sum / pcm.channels
    }
    // 已是目标格式：直拷。
    if (pcm.sampleRate == 48000 && pcm.channels == 1 && pcm.bits == 16) {
        out.write(pcm.data)
        out.flush()
        return frames.toLong()
    }
    val dstFrames = (frames.toLong() * 48000 / pcm.sampleRate.coerceAtLeast(1)).toInt()
    val ob = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
    // ByteBuffer 非 direct 无 array 保证？allocate 给的就是堆内数组，可写后按 position 刷出。
    var buffered = 0
    var written = 0L
    fun emit(s: Int) {
        if (buffered + 2 > ob.capacity()) {
            out.write(ob.array(), 0, buffered)
            buffered = 0
        }
        ob.putShort(buffered, s.toShort())
        buffered += 2
    }
    var k = 0
    while (k < dstFrames) {
        val pos = k.toDouble() * frames / dstFrames
        val i0 = pos.toInt().coerceIn(0, frames - 1)
        val i1 = (i0 + 1).coerceIn(0, frames - 1)
        val frac = (pos - i0).toFloat()
        val s = (sampleMono(i0) * (1 - frac) + sampleMono(i1) * frac).toInt()
            .coerceIn(-32768, 32767)
        emit(s)
        written++
        k++
    }
    if (buffered > 0) out.write(ob.array(), 0, buffered)
    out.flush()
    return written
}
