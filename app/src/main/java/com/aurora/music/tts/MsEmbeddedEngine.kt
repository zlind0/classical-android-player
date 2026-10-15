package com.aurora.music.tts

import android.content.Context
import com.microsoft.cognitiveservices.speech.AudioDataStream
import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 微软离线直驱引擎：`EmbeddedSpeechConfig.fromPath(voiceDir)` +
 * `setSpeechSynthesisVoice(展示名, license)` + `Raw48Khz16BitMonoPcm`（原生 48k，免重采样）+
 * `SpeakSsml` 分块 + `AudioDataStream` 取 PCM。全程不走系统 TTS。
 *
 * 部分离线语音模型仅支持 24k 输出：此时自动回落到 24k + 带限内插上采样到 48k，
 * 对外恒为 48k 单声道 16bit PCM（调用方只做单声道→立体声，不再重采样）。
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
     * 按需单拷：只释放本次要用的语音（默认晓晓），其余语音等用户真选才拷。
     * assets 仍随 APK 全量带（离线开箱），但 filesDir 里只有在用的一份。
     * onProgress(doneVoices, totalVoices, currentVoice)，单拷时 total 恒为 1。
     */
    suspend fun ensureVoice(code: String, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        if (isInstalled(code)) return
        withContext(Dispatchers.IO) {
            onProgress(0, 1, code)
            copyAssetVoice(code)
            onProgress(1, 1, code)
        }
    }

    /**
     * 首次运行把 assets/msvoice 下语音拷到应用私有目录（保留兼容：逐个走 [ensureVoice]）。
     * 新代码优先调 [ensureVoice] 单拷，避免一次释放 4 个语音（~173MB）。
     */
    suspend fun installIfNeeded(onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        val total = MsVoices.ALL.size
        var done = 0
        for (v in MsVoices.ALL) {
            if (!isInstalled(v.code)) {
                onProgress(done, total, v.code)
                ensureVoice(v.code)
            }
            done++
            onProgress(done, total, v.code)
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

    /** 各语音原生是否支持 48k 输出（首合探测后缓存，避免不支持时每次都双合）。 */
    private val native48k = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun requestStop() {
        stopFlag = true
    }

    /**
     * 流式合成一段文本：按句切块，每出一块 PCM 就回调一次（边合边播），
     * 返回的完整 PCM 与回调内容一致。rate/pitch 1.0 = 原速原调。
     *
     * 返回恒为 48kHz 单声道 16bit PCM：优先原生 48k 输出；离线模型不支持时
     * 回落 24k + 带限内插到 48k（见 [TtsWav.upsample24kTo48kMono]）。
     * 注意回落路径下 [onPcmChunk] 不回调（只有完整返回），原生 48k 路径才逐块回调。
     * @param isStopped 按调用域的停止谓词（默认全局 [stopFlag]，兼容老调用方；
     *   TtsWorker 传入代际 flag，避免并发/切段时互相踩）。
     */
    suspend fun synthesizeStreaming(
        text: String,
        voice: MsVoice,
        rate: Float = 1f,
        pitch: Float = 1f,
        onPcmChunk: ((ByteArray) -> Unit)? = null,
        isStopped: () -> Boolean = { stopFlag },
    ): ByteArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "文本为空" }
        require(isInstalled(voice.code)) { "语音 ${voice.code} 尚未安装" }
        val chunks = Chunker.split(text)
        require(chunks.isNotEmpty()) { "文本为空" }

        stopFlag = false
        if (native48k[voice.code] != false) {
            val pcm48 = synthWithFormat(
                chunks, voice, rate, pitch,
                SpeechSynthesisOutputFormat.Raw48Khz16BitMonoPcm,
                onPcmChunk, isStopped,
            )
            if (pcm48.isNotEmpty()) {
                native48k[voice.code] = true
                return@withContext pcm48
            }
            // 用户主动停止：直接返回空，不触发回落（否则一次停止会再全量合一遍 24k）
            if (isStopped()) return@withContext ByteArray(0)
            native48k[voice.code] = false
        }
        val pcm24 = synthWithFormat(
            chunks, voice, rate, pitch,
            SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm,
            null, isStopped,
        )
        if (pcm24.isEmpty()) return@withContext ByteArray(0)
        TtsWav.upsample24kTo48kMono(pcm24)
    }

    /** 打开复用式合成会话（调用方负责 close，实例线程独占，绝不跨线程共享）。 */
    fun openSession(voice: MsVoice, rate: Float = 1f, pitch: Float = 1f): Session =
        Session(voice, rate, pitch)

    /**
     * 复用式合成会话（导出管线用）：一个 SpeechSynthesizer 服务多批次，
     * 整书结束才关。导出的高频建/关实例会让 MS SDK native 堆单调上涨
     * 直至 OOM，所以这里建一次复用到底。
     * 48k/24k 格式只在首批探测一次（跨导出记住决定），之后整会话沿用。
     */
    inner class Session(
        val voice: MsVoice,
        private val rate: Float = 1f,
        private val pitch: Float = 1f,
    ) : Closeable {
        private var cfg: EmbeddedSpeechConfig? = null
        private var synth: SpeechSynthesizer? = null
        /** null = 首批待探测；true = 原生 48k；false = 24k 回落。 */
        private var use48k: Boolean? = null
        private var closed = false

        init {
            require(isInstalled(voice.code)) { "语音 ${voice.code} 尚未安装" }
            // 跨导出已知的格式决定直接沿用，跳过首批探测。
            if (native48k[voice.code] == true) {
                use48k = true
                openSynth(SpeechSynthesisOutputFormat.Raw48Khz16BitMonoPcm)
            } else if (native48k[voice.code] == false) {
                use48k = false
                openSynth(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
            }
        }

        /**
         * 合成一批文本，返回 48kHz 单声道 16bit PCM。
         * 原生 48k 路径按块回调 [onPcmChunk] 后只返回成功哨兵（非空即成功，
         * 不攒全量包，省 10MB+ 级瞬时内存）；无回调时才返回完整 PCM。
         * 24k 回落路径不回调，只有完整返回。
         */
        fun synthBatch(
            text: String,
            onPcmChunk: ((ByteArray) -> Unit)? = null,
            isStopped: () -> Boolean = { false },
        ): ByteArray {
            check(!closed) { "会话已关闭" }
            require(text.isNotBlank()) { "文本为空" }
            val chunks = Chunker.split(text)
            require(chunks.isNotEmpty()) { "文本为空" }
            if (synth == null) {
                // 首批：先试 48k，不行再 24k，之后整会话沿用，不再每批双试。
                openSynth(SpeechSynthesisOutputFormat.Raw48Khz16BitMonoPcm)
                val pcm48 = synthChunks(chunks, onPcmChunk, isStopped)
                if (pcm48.isNotEmpty()) {
                    use48k = true
                    native48k[voice.code] = true
                    return pcm48
                }
                if (isStopped()) return ByteArray(0)
                closeSynth()
                native48k[voice.code] = false
                use48k = false
                openSynth(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
            }
            return if (use48k == true) {
                synthChunks(chunks, onPcmChunk, isStopped)
            } else {
                val pcm24 = synthChunks(chunks, null, isStopped)
                if (pcm24.isEmpty()) ByteArray(0) else TtsWav.upsample24kTo48kMono(pcm24)
            }
        }

        /**
         * 故障后重建句柄（保留已探测的格式决定，不重探）。
         * 下次 [synthBatch] 按需打开；use48k 已知时 [reset] 内直接打开。
         */
        fun reset() {
            check(!closed) { "会话已关闭" }
            closeSynth()
            if (use48k == false) {
                openSynth(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
            }
        }

        private fun synthChunks(
            chunks: List<String>,
            onPcmChunk: ((ByteArray) -> Unit)?,
            isStopped: () -> Boolean,
        ): ByteArray {
            val s = synth ?: throw IllegalStateException("合成器未打开")
            // 流式回调路径只透传不攒包（一批 800 字 ≈ 12MB，攒包 × 线程数
            // 在 256MB 堆上直接 OOM）；用字节计数代替，>0 即成功哨兵。
            // 调用方只在 streamed==0 时用返回值，无回调路径才需要完整 PCM。
            val streaming = onPcmChunk != null
            val pcm = if (streaming) null else ByteArrayOutputStream()
            var streamedBytes = 0
            val buf = ByteArray(9600)
            for (chunk in chunks) {
                if (isStopped()) break
                val ssml = SsmlBuilder.build(voice, chunk, rate, pitch)
                val result = s.SpeakSsml(ssml)
                try {
                    if (result.reason != ResultReason.SynthesizingAudioCompleted) break
                    val stream = AudioDataStream.fromResult(result)
                    try {
                        stream.setPosition(0)
                        while (!isStopped()) {
                            val n = stream.readData(buf).toInt()
                            if (n <= 0) break
                            val piece = buf.copyOf(n)
                            if (streaming) {
                                onPcmChunk?.invoke(piece)
                                streamedBytes += n
                            } else {
                                pcm?.write(piece)
                            }
                        }
                    } finally {
                        runCatching { stream.close() }
                    }
                } finally {
                    runCatching { result.close() }
                }
            }
            return if (streaming) {
                if (streamedBytes > 0) byteArrayOf(1) else ByteArray(0)
            } else {
                pcm?.toByteArray() ?: ByteArray(0)
            }
        }

        private fun openSynth(format: SpeechSynthesisOutputFormat) {
            closeSynth()
            val c = EmbeddedSpeechConfig.fromPath(voiceDirOf(voice.code).absolutePath)
            try {
                c.setSpeechSynthesisVoice(voice.displayName, MsVoices.licenseFor(voice))
                c.setSpeechSynthesisOutputFormat(format)
                synth = SpeechSynthesizer(c, null)
                cfg = c
            } catch (t: Throwable) {
                runCatching { c.close() }
                throw t
            }
        }

        private fun closeSynth() {
            runCatching { synth?.close() }
            synth = null
            val c = cfg
            cfg = null
            runCatching { c?.close() }
        }

        override fun close() {
            closed = true
            closeSynth()
        }
    }

    /** 指定输出格式合全部分块，返回该格式下的原始 PCM（空 = 被停止或该格式不支持）。 */
    private fun synthWithFormat(
        chunks: List<String>,
        voice: MsVoice,
        rate: Float,
        pitch: Float,
        format: SpeechSynthesisOutputFormat,
        onPcmChunk: ((ByteArray) -> Unit)?,
        isStopped: () -> Boolean,
    ): ByteArray {
        val cfg = EmbeddedSpeechConfig.fromPath(voiceDirOf(voice.code).absolutePath)
        try {
            cfg.setSpeechSynthesisVoice(voice.displayName, MsVoices.licenseFor(voice))
            cfg.setSpeechSynthesisOutputFormat(format)
            val synth = SpeechSynthesizer(cfg, null)
            try {
                val pcm = ByteArrayOutputStream()
                val buf = ByteArray(9600)
                for (chunk in chunks) {
                    if (isStopped()) break
                    val ssml = SsmlBuilder.build(voice, chunk, rate, pitch)
                    val result = synth.SpeakSsml(ssml)
                    try {
                        if (result.reason != ResultReason.SynthesizingAudioCompleted) break
                        val stream = AudioDataStream.fromResult(result)
                        try {
                            stream.setPosition(0)
                            while (!isStopped()) {
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
                return pcm.toByteArray()
            } finally {
                runCatching { synth.close() }
            }
        } finally {
            runCatching { cfg.close() }
        }
    }
}
