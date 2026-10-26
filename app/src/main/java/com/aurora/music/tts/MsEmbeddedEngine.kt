package com.aurora.music.tts

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.microsoft.cognitiveservices.speech.AudioDataStream
import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
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

    /** 各语音原生是否支持 48k 输出（首合探测后缓存，避免不支持时每次都双合）。 */
    private val native48k = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    // ---- 合成线程 + 常驻会话 ----

    /**
     * 全进程唯一合成线程（懒建，导出实例碰不到它）：
     * MS SDK 的 SpeechSynthesizer 在这里建一次、跨句复用到底。
     * 以前听书每句 build/close 一次（老的一次性 synthWithFormat 路径），原生侧每次都起停线程池
     * 并重载模型，导致「每读一句一批 pthread_start」+ native 堆单调上涨。
     * 听书与歌曲介绍共用这一条线程：一个人一只耳朵，两者本来不能同时出声，天然排队。
     */
    private val synthThread by lazy {
        HandlerThread("tts-synth", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    }
    private val synthHandler by lazy { Handler(synthThread.looper) }
    private val synthDispatcher by lazy { synthHandler.asCoroutineDispatcher() }

    /** 当前常驻会话（只在 tts-synth 线程读写，无需加锁）。 */
    private var live: Session? = null
    private var liveVoiceCode: String = ""

    /** 供 [requestStop] 跨线程打断用（由 Session 的 openSynth/closeSynth 维护）。 */
    @Volatile private var liveSynth: SpeechSynthesizer? = null

    private val closeIdle = Runnable { closeLive() }

    /**
     * 合成一段文本，返回 48kHz 单声道 16bit PCM（调用方只做单声道→立体声）。
     * rate/pitch 走 SSML prosody，不参与会话缓存键，所以拖语速滑杆不会重建实例。
     * @param isStopped 调用域的停止谓词（块间检查）；native 侧的即时打断走 [requestStop]。
     */
    suspend fun synth(
        text: String,
        voice: MsVoice,
        rate: Float,
        pitch: Float,
        isStopped: () -> Boolean,
    ): ByteArray = withContext(synthDispatcher) {
        require(text.isNotBlank()) { "文本为空" }
        require(isInstalled(voice.code)) { "语音 ${voice.code} 尚未安装" }
        val session = sessionFor(voice)
        synthHandler.removeCallbacks(closeIdle)
        try {
            session.synthBatch(text, rate, pitch, onPcmChunk = null, isStopped = isStopped)
        } catch (t: Throwable) {
            // 与导出管线同策略：实例可能已坏，保留 48k/24k 探测结论重置一次，
            // 异常照旧抛给上层（下一次合成会用新实例重试）。
            if (!isStopped()) runCatching { session.reset() }
            throw t
        } finally {
            synthHandler.postDelayed(closeIdle, IDLE_RELEASE_MS)
        }
    }

    /** 取常驻会话：音色变了才关旧建新（48k/24k 结论跨会话保留，不重探）。 */
    private fun sessionFor(voice: MsVoice): Session {
        live?.takeIf { liveVoiceCode == voice.code }?.let { return it }
        closeLive()
        liveVoiceCode = voice.code
        return Session(voice).also { live = it }
    }

    private fun closeLive() {
        runCatching { live?.close() }
        live = null
        liveVoiceCode = ""
    }

    /**
     * 立刻打断 native 合成（任意线程可调）：正在阻塞的 SpeakSsml 会尽快返回，
     * 停止键不必再等当前块合完。
     */
    fun requestStop() {
        runCatching { liveSynth?.StopSpeakingAsync() }
    }

    /** 主动释放常驻模型（开始整书导出抢内存 / 切回系统引擎时调）；空闲 5 分钟也会自动放。 */
    fun releaseSession() {
        if (live == null) return
        synthHandler.post { closeLive() }
    }

    private companion object {
        /** 空闲这么久没有新合成才释放模型（103MB 的晓晓常驻，停 5 分钟基本确定不听了）。 */
        const val IDLE_RELEASE_MS = 5 * 60 * 1000L
    }

    /** 打开复用式合成会话（调用方负责 close，实例线程独占，绝不跨线程共享）。 */
    fun openSession(voice: MsVoice): Session = Session(voice)

    /**
     * 复用式合成会话：一个 SpeechSynthesizer 服务多批次，整段结束才关。
     * 高频建/关实例会让 MS SDK 每次起停原生线程池并重载模型（CPU 尖峰 + native 堆无法回落），
     * 所以听书路径由 [synth] 常驻复用，导出管线每个工作线程各持一个。
     * 48k/24k 格式只在首批探测一次（跨会话记住决定），之后整会话沿用。
     * rate/pitch 每批传入（走 SSML），换音色/换语速都不需要重建实例。
     */
    inner class Session(
        val voice: MsVoice,
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
         * rate/pitch 每批传入：SSML prosody 生效，换语速不重建实例。
         */
        fun synthBatch(
            text: String,
            rate: Float = 1f,
            pitch: Float = 1f,
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
                val pcm48 = synthChunks(chunks, rate, pitch, onPcmChunk, isStopped)
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
                synthChunks(chunks, rate, pitch, onPcmChunk, isStopped)
            } else {
                val pcm24 = synthChunks(chunks, rate, pitch, null, isStopped)
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
            rate: Float,
            pitch: Float,
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
                val s = SpeechSynthesizer(c, null)
                synth = s
                liveSynth = s
                cfg = c
            } catch (t: Throwable) {
                runCatching { c.close() }
                throw t
            }
        }

        private fun closeSynth() {
            val s = synth
            runCatching { s?.close() }
            if (liveSynth === s) liveSynth = null
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
}
