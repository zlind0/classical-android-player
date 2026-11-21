package com.aurora.music.data.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LiteRT-LM 的薄封装：一个进程内只保留一个已加载引擎，按需换模型 / 释放。
 *
 * 有声书翻译是「一段 30 秒音频 → 一段文本」，不需要保留多轮上下文，
 * 因此每段都新建 Conversation、用完立即关闭，避免 KV 缓存越积越大。
 */
class LlmRuntime(private val context: Context) {

    private data class Loaded(val key: String, val engine: Engine, val gpu: Boolean)

    private val mutex = Mutex()
    private var loaded: Loaded? = null

    @OptIn(ExperimentalApi::class)
    suspend fun translate(
        spec: LlmModelSpec,
        prefs: LlmGenerationPrefs,
        wavBytes: ByteArray,
        prompt: String,
        onModelReady: () -> Unit = {},
        onToken: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        // 诊断用：等运行时锁（上一段收尾）/ 取引擎 / 建会话 / 音频编码到首个 token。
        val t0 = SystemClock.elapsedRealtime()
        var tLock = t0
        var tEngine = t0
        var tConv = t0
        val tFirst = AtomicLong(0)
        mutex.withLock {
            tLock = SystemClock.elapsedRealtime()
            val engine = ensureEngine(spec, prefs)
            tEngine = SystemClock.elapsedRealtime()
            val conversation = engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = prefs.topK,
                        topP = prefs.topP.toDouble(),
                        temperature = prefs.temperature.toDouble(),
                    ),
                    maxOutputToken = prefs.maxOutputTokens
                        .coerceAtMost((prefs.contextTokens - 1024).coerceAtLeast(128)),
                    extraContext = mapOf("enable_thinking" to false),
                ),
            )
            tConv = SystemClock.elapsedRealtime()
            onModelReady()

            val finished = AtomicBoolean(false)
            val settled = CompletableDeferred<Unit>()
            val acc = StringBuilder()
            val text = try {
                val contents = Contents.of(
                    listOf(
                        Content.AudioBytes(wavBytes),
                        Content.Text(prompt),
                    ),
                )
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation {
                        runCatching { conversation.cancelProcess() }
                    }
                    conversation.sendMessageAsync(
                        contents,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val delta = message.toString()
                                if (delta.isEmpty()) return
                                tFirst.compareAndSet(0, SystemClock.elapsedRealtime())
                                acc.append(delta)
                                if (!cont.isCancelled) onToken(delta)
                            }

                            override fun onDone() {
                                if (!finished.compareAndSet(false, true)) return
                                settled.complete(Unit)
                                if (cont.isActive) cont.resume(acc.toString().trim())
                            }

                            override fun onError(error: Throwable) {
                                if (!finished.compareAndSet(false, true)) return
                                settled.complete(Unit)
                                if (cont.isActive) cont.resumeWithException(error)
                            }
                        },
                    )
                }
            } finally {
                // 关键：不能一收到 done/cancel 就 close。LiteRT-LM 的 callback_thread
                // 可能还在收尾，立刻 close 会在 native 里空指针（SIGSEGV）。
                withContext(NonCancellable) {
                    if (!finished.get()) {
                        runCatching { conversation.cancelProcess() }
                        withTimeoutOrNull(3_000) { settled.await() }
                    }
                    // 让 callback_thread 真正从 onDone/onError 返回后再 close。
                    delay(150)
                    runCatching { conversation.close() }
                }
            }
            // 这次 GPU 推理完整跑完了，说明驱动没被这组权重/这条路径带崩；
            // 清掉标记，下次冷启动可以继续用 GPU。
            if (loaded?.gpu == true) runCatching { llmGpuCrashMarker(context).delete() }
            val tEnd = SystemClock.elapsedRealtime()
            val first = tFirst.get()
            LlmDebug.i(
                TAG,
                "translate wait=${tLock - t0}ms engine=${tEngine - tLock}ms " +
                    "conv=${tConv - tEngine}ms " +
                    "first=${if (first > 0) first - t0 else -1}ms " +
                    "total=${tEnd - t0}ms out=${text.length}字",
            )
            text
        }
    }

    /** 当前常驻的引擎是否正好是这套配置（模型 + 上下文长度 + 后端）。 */
    suspend fun isLoaded(spec: LlmModelSpec, prefs: LlmGenerationPrefs): Boolean =
        mutex.withLock { loaded?.key == keyOf(spec, prefs) }

    @OptIn(ExperimentalApi::class)
    private suspend fun ensureEngine(spec: LlmModelSpec, prefs: LlmGenerationPrefs): Engine {
        val key = keyOf(spec, prefs)
        loaded?.takeIf { it.key == key }?.let { return it.engine }
        loaded?.let { old ->
            loaded = null
            runCatching { old.engine.close() }
        }
        val file = File(llmModelDir(context, spec), spec.fileName)
        check(file.isFile && file.length() > 0) { "模型文件不存在，请重新下载" }
        val cacheDir = File(context.cacheDir, "litert-lm").apply { mkdirs() }
        // 上一次 GPU 尝试把整个进程带崩了（native SIGSEGV 无法 catch）：
        // 这次直接走 CPU，至少让翻译能用。
        val useGpu = prefs.useGpu && !llmGpuCrashMarker(context).exists()
        if (prefs.useGpu && !useGpu) {
            Log.w(TAG, "previous GPU attempt crashed the process, using CPU instead")
        }
        var engineUsesGpu = useGpu
        val engine = try {
            if (useGpu) {
                markGpuAttempt()
                loadEngine(spec, file, prefs, cacheDir, useGpu = true)
            } else {
                loadEngine(spec, file, prefs, cacheDir, useGpu = false)
            }
        } catch (gpuError: Throwable) {
            if (!useGpu) throw gpuError
            Log.w(TAG, "GPU init failed, retrying on CPU", gpuError)
            // 可捕获的初始化失败不是崩溃，清掉标记，下次仍按用户设置重试 GPU。
            runCatching { llmGpuCrashMarker(context).delete() }
            engineUsesGpu = false
            loadEngine(spec, file, prefs, cacheDir, useGpu = false)
        }
        loaded = Loaded(key, engine, engineUsesGpu)
        return engine
    }

    /**
     * 碰 GPU 之前先落盘。Vulkan 驱动崩溃时进程直接死掉，Java 的 catch 和
     * finally 都不会执行，只有提前写好的标记能在下次启动时告诉我们「别再试 GPU」。
     */
    private fun markGpuAttempt() {
        runCatching {
            llmGpuCrashMarker(context).apply {
                parentFile?.mkdirs()
                writeText("pid=${android.os.Process.myPid()}")
            }
        }
    }

    /** 显式预加载：点开翻译按钮时调用，提前把权重读进内存。 */
    suspend fun ensureLoaded(spec: LlmModelSpec, prefs: LlmGenerationPrefs) {
        withContext(Dispatchers.IO) {
            mutex.withLock { ensureEngine(spec, prefs) }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun loadEngine(
        spec: LlmModelSpec,
        file: File,
        prefs: LlmGenerationPrefs,
        cacheDir: File,
        useGpu: Boolean,
    ): Engine {
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = if (useGpu) Backend.GPU() else Backend.CPU(),
            audioBackend = Backend.CPU(),
            maxNumTokens = prefs.contextTokens,
            cacheDir = cacheDir.absolutePath,
        )
        val engine = Engine(config)
        try {
            engine.initialize()
        } catch (t: Throwable) {
            runCatching { engine.close() }
            throw t
        }
        LlmDebug.i(TAG, "loaded ${spec.id} backend=${if (useGpu) "GPU" else "CPU"} ctx=${prefs.contextTokens}")
        return engine
    }

    suspend fun release() {
        mutex.withLock {
            val old = loaded ?: return@withLock
            loaded = null
            withContext(Dispatchers.IO) { runCatching { old.engine.close() } }
        }
    }

    private companion object {
        const val TAG = "LlmRuntime"

        fun keyOf(spec: LlmModelSpec, prefs: LlmGenerationPrefs): String =
            "${spec.id}|${prefs.contextTokens}|${prefs.useGpu}"
    }
}

/** 一次生成用到的采样 / 长度参数（从设置页快照过来，避免会话中途变化）。 */
data class LlmGenerationPrefs(
    val maxOutputTokens: Int = 1024,
    val contextTokens: Int = 8192,
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val temperature: Float = 0.3f,
    val useGpu: Boolean = true,
)

/** Float PCM（-1..1，16 kHz 单声道）转 16-bit WAV；LiteRT-LM 的 AudioBytes 吃 WAV。 */
object WavEncoder {
    const val HEADER_BYTES = 44

    fun encode(pcm: FloatArray, sampleRate: Int = 16000): ByteArray {
        val dataSize = pcm.size * 2
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(header(dataSize, sampleRate))
        for (v in pcm) buffer.putShort(toShort(v))
        return buffer.array()
    }

    /** 直接把 PCM 写成 WAV 文件，避免再分配一份等大的 ByteArray。 */
    fun writeFile(file: File, pcm: FloatArray, sampleRate: Int = 16000) {
        WavFileWriter(file, sampleRate).use { it.write(pcm) }
    }

    fun header(dataSize: Int, sampleRate: Int = 16000): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('R'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.put('F'.code.toByte())
        buffer.putInt(36 + dataSize)
        buffer.put('W'.code.toByte())
        buffer.put('A'.code.toByte())
        buffer.put('V'.code.toByte())
        buffer.put('E'.code.toByte())
        buffer.put('f'.code.toByte())
        buffer.put('m'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put(' '.code.toByte())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put('d'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.put('t'.code.toByte())
        buffer.put('a'.code.toByte())
        buffer.putInt(dataSize)
        return buffer.array()
    }

    private fun toShort(v: Float): Short =
        (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}

/**
 * 流式 WAV 写入：先占 44 字节头，边写 PCM 边统计长度，关闭时回填头。
 * 适合把解码/切片的音频直接落盘，不用先在内存里攒一整个 ByteArray。
 */
class WavFileWriter(file: File, private val sampleRate: Int = 16000) : AutoCloseable {
    private val raf = java.io.RandomAccessFile(file, "rw")
    private var dataSize = 0
    private val buf = ByteArray(32 * 1024)

    init {
        raf.setLength(0)
        raf.write(ByteArray(WavEncoder.HEADER_BYTES))
    }

    fun write(pcm: FloatArray, count: Int = pcm.size) {
        var i = 0
        while (i < count) {
            val n = minOf(buf.size / 2, count - i)
            for (j in 0 until n) {
                val s = (pcm[i + j].coerceIn(-1f, 1f) * 32767f).toInt()
                buf[j * 2] = (s and 0xFF).toByte()
                buf[j * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            raf.write(buf, 0, n * 2)
            dataSize += n * 2
            i += n
        }
    }

    override fun close() {
        try {
            raf.seek(0)
            raf.write(WavEncoder.header(dataSize, sampleRate))
            // 中途失败时截掉多余尾巴，避免留下半截数据。
            raf.setLength(WavEncoder.HEADER_BYTES + dataSize.toLong())
        } finally {
            raf.close()
        }
    }
}
