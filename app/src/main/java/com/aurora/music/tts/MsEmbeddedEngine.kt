package com.aurora.music.tts

import android.content.Context
import com.microsoft.cognitiveservices.speech.AudioDataStream
import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 微软离线直驱引擎：`EmbeddedSpeechConfig.fromPath(voiceDir)` +
 * `setSpeechSynthesisVoice(展示名, license)` + `Raw24Khz16BitMonoPcm` +
 * `SpeakSsml` 分块 + `AudioDataStream` 取 PCM。全程不走系统 TTS。
 *
 * 异常语义（调用方直接透出给 UI）：
 * - UnsatisfiedLinkError/noClassDef = 设备无 arm64 runtime（如 x86_64 模拟器）
 * - IllegalStateException("语音尚未安装…") = 资源释放未完成
 */
class MsEmbeddedEngine(private val context: Context) {

    fun voiceDirOf(code: String): File =
        File(File(context.filesDir, "voice/microsoft"), code)

    private fun markerOf(code: String): File = File(voiceDirOf(code), ".installed")

    fun isInstalled(code: String): Boolean =
        markerOf(code).exists() && voiceDirOf(code).isDirectory

    fun installedCount(): Int = MsVoices.ALL.count { isInstalled(it.code) }

    val allInstalled: Boolean get() = MsVoices.ALL.all { isInstalled(it.code) }

    /**
     * 首次运行把 assets/msvoice 下 4 个语音拷到应用私有目录。
     * onProgress(doneVoices, totalVoices, currentVoice)。
     */
    suspend fun installIfNeeded(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        withContext(Dispatchers.IO) {
            val total = MsVoices.ALL.size
            var done = 0
            for (v in MsVoices.ALL) {
                if (!isInstalled(v.code)) {
                    onProgress(done, total, v.code)
                    copyAssetVoice(v.code)
                }
                done++
                onProgress(done, total, v.code)
            }
        }
    }

    private fun copyAssetVoice(code: String) {
        val dest = voiceDirOf(code)
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        copyAssetDir("msvoice/$code", dest)
        markerOf(code).writeText("ok")
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val am = context.assets
        val entries = am.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            // 可能是空目录或文件；尝试当文件打开
            try {
                am.open(assetPath).use { inp ->
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> inp.copyTo(out) }
                }
            } catch (_: Exception) {
                dest.mkdirs()
            }
            return
        }
        dest.mkdirs()
        for (name in entries) {
            copyAssetDir("$assetPath/$name", File(dest, name))
        }
    }

    @Volatile private var stopFlag = false

    fun requestStop() {
        stopFlag = true
    }

    /**
     * 流式合成一段文本：按句切块，每出一块 PCM 就回调一次（边合边播），
     * 返回的完整 PCM 与回调内容一致。rate/pitch 1.0 = 原速原调。
     */
    suspend fun synthesizeStreaming(
        text: String,
        voice: MsVoice,
        rate: Float = 1f,
        pitch: Float = 1f,
        onPcmChunk: ((ByteArray) -> Unit)? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "文本为空" }
        require(isInstalled(voice.code)) { "语音 ${voice.code} 尚未安装" }
        val chunks = Chunker.split(text)
        require(chunks.isNotEmpty()) { "文本为空" }

        stopFlag = false
        val cfg = EmbeddedSpeechConfig.fromPath(voiceDirOf(voice.code).absolutePath)
        try {
            cfg.setSpeechSynthesisVoice(voice.displayName, MsVoices.licenseFor(voice))
            cfg.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
            val synth = SpeechSynthesizer(cfg, null)
            try {
                val pcm = ByteArrayOutputStream()
                val buf = ByteArray(4800)
                for (chunk in chunks) {
                    if (stopFlag) break
                    val ssml = SsmlBuilder.build(voice, chunk, rate, pitch)
                    val result = synth.SpeakSsml(ssml)
                    try {
                        if (result.reason != ResultReason.SynthesizingAudioCompleted) break
                        val stream = AudioDataStream.fromResult(result)
                        try {
                            stream.setPosition(0)
                            while (!stopFlag) {
                                val n = stream.readData(buf).toInt()
                                if (n <= 0) break
                                val piece = buf.copyOf(n)
                                pcm.write(piece)
                                onPcmChunk?.invoke(piece)
                            }
                        } finally {
                            runCatching { stream.close() }
                        }
                    } finally {
                        runCatching { result.close() }
                    }
                }
                pcm.toByteArray()
            } finally {
                runCatching { synth.close() }
            }
        } finally {
            runCatching { cfg.close() }
        }
    }
}
