package com.aurora.music.data.llm.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.aurora.music.data.llm.LlmDebug
import com.aurora.music.data.llm.LlmGenerationPrefs
import com.aurora.music.data.llm.LlmModelCatalog
import com.aurora.music.data.llm.LlmRuntime
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 独立进程（:llm）里的模型服务：只负责加载 / 卸载 / 推理。
 *
 * 主进程通过 [LlmClient] 绑定，模型权重和 native 运行时都留在这个进程。
 * 模型驻留由心跳维持：翻译界面在前台时主进程每 5 秒发一次 [LlmProtocol.MSG_PING]，
 * 翻译请求本身也算活跃；连续 [IDLE_TIMEOUT_MS] 收不到任何 Ping / 请求就自动卸载，
 * 所以关掉翻译页或切到后台后不用客户端再发 UNLOAD。
 */
class LlmService : Service() {

    private val runtime by lazy { LlmRuntime(applicationContext) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = CopyOnWriteArraySet<Messenger>()
    private val jobs = ConcurrentHashMap<Long, Job>()

    private var loadJob: Job? = null
    private var handlerThread: HandlerThread? = null
    private var messenger: Messenger? = null

    @Volatile private var state = LlmProtocol.STATE_IDLE
    @Volatile private var stateMessage = ""

    /** 最近一次收到 Ping / 翻译请求 / 加载指令的时间（elapsedRealtime）。 */
    @Volatile private var lastActivityMs = SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("llm-service").also { it.start() }
        handlerThread = thread
        val handler = Handler(thread.looper, object : Handler.Callback {
            override fun handleMessage(msg: Message): Boolean = onCommand(msg)
        })
        messenger = Messenger(handler)
        startIdleWatchdog()
        LlmDebug.i(TAG, "service created pid=${android.os.Process.myPid()}")
    }

    /**
     * 看门狗：心跳停止后 [IDLE_TIMEOUT_MS] 内没有任何活跃信号就卸载模型。
     *
     * 推理进行中（[jobs] 非空）或正在加载时不算空闲；每完成一次翻译都会刷新
     * 活跃时间，所以 30 秒是从「最后一次真正干活」开始算的。
     */
    private fun startIdleWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val idleFor = SystemClock.elapsedRealtime() - lastActivityMs
                if (idleFor < IDLE_TIMEOUT_MS) continue
                if (jobs.isNotEmpty()) continue
                if (loadJob?.isActive == true) continue
                if (state == LlmProtocol.STATE_IDLE) continue
                LlmDebug.i(TAG, "no ping for ${idleFor}ms, unloading model")
                handleUnload()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = messenger?.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    private fun onCommand(msg: Message): Boolean {
        val reply = msg.replyTo
        if (reply != null) clients += reply
        val data = msg.data ?: Bundle()
        // 调试开关随 LOAD / TRANSLATE 下发；Ping 不带数据，沿用上一次的值。
        if (data.containsKey(LlmProtocol.KEY_DEBUG)) {
            LlmDebug.enabled = data.getBoolean(LlmProtocol.KEY_DEBUG, false)
        }
        when (msg.what) {
            LlmProtocol.MSG_LOAD -> {
                lastActivityMs = SystemClock.elapsedRealtime()
                handleLoad(data)
            }
            LlmProtocol.MSG_TRANSLATE -> {
                lastActivityMs = SystemClock.elapsedRealtime()
                handleTranslate(data, reply)
            }
            LlmProtocol.MSG_CANCEL -> {
                jobs.remove(data.getLong(LlmProtocol.KEY_REQUEST_ID))?.cancel()
            }
            LlmProtocol.MSG_UNLOAD -> handleUnload()
            LlmProtocol.MSG_PING -> {
                // 心跳就是驻留许可：刷新活跃时间，顺便把当前状态同步回客户端。
                lastActivityMs = SystemClock.elapsedRealtime()
                if (reply != null) sendState(reply)
            }
            else -> return false
        }
        return true
    }

    private fun handleLoad(data: Bundle) {
        val spec = LlmModelCatalog.byId(data.getString(LlmProtocol.KEY_MODEL_ID).orEmpty())
        if (spec == null) {
            broadcast(LlmProtocol.STATE_ERROR, "未知模型")
            return
        }
        val prefs = prefsFrom(data)
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                // 引擎已经常驻同一份配置：直接回 READY，不要重新读权重，
                // 也不要闪一下「正在加载模型…」。
                if (runtime.isLoaded(spec, prefs)) {
                    broadcast(LlmProtocol.STATE_READY, "")
                    LlmDebug.i(TAG, "model already resident ${spec.id}")
                    return@launch
                }
                broadcast(LlmProtocol.STATE_LOADING, "正在加载模型…")
                runtime.ensureLoaded(spec, prefs)
                broadcast(LlmProtocol.STATE_READY, "")
                LlmDebug.i(TAG, "model ready ${spec.id}")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "model load failed", t)
                broadcast(LlmProtocol.STATE_ERROR, t.message ?: "模型加载失败")
            }
        }
    }

    private fun handleTranslate(data: Bundle, reply: Messenger?) {
        if (reply == null) return
        val requestId = data.getLong(LlmProtocol.KEY_REQUEST_ID)
        val spec = LlmModelCatalog.byId(data.getString(LlmProtocol.KEY_MODEL_ID).orEmpty())
        val audioPath = data.getString(LlmProtocol.KEY_AUDIO_PATH).orEmpty()
        val deleteAudio = data.getBoolean(LlmProtocol.KEY_AUDIO_DELETE, true)
        val prompt = data.getString(LlmProtocol.KEY_PROMPT).orEmpty()
        if (spec == null || audioPath.isEmpty()) {
            sendError(reply, requestId, "翻译请求参数不完整")
            return
        }
        val prefs = prefsFrom(data)
        val t0 = SystemClock.elapsedRealtime()
        jobs[requestId] = scope.launch {
            try {
                // 正常情况下引擎常驻，这里只是交给客户端一个准确的加载态；
                // 只有首次请求 / 空闲卸载后才会真的走加载。
                if (!runtime.isLoaded(spec, prefs)) {
                    broadcast(LlmProtocol.STATE_LOADING, "正在加载模型…")
                }
                val audio = File(audioPath).readBytes()
                val readMs = SystemClock.elapsedRealtime() - t0
                LlmDebug.i(TAG, "request $requestId audio=${audio.size / 1024}KB read=${readMs}ms")
                val result = runtime.translate(
                    spec = spec,
                    prefs = prefs,
                    wavBytes = audio,
                    prompt = prompt,
                    onModelReady = {
                        broadcast(LlmProtocol.STATE_READY, "")
                        send(reply, LlmProtocol.CB_READY) {
                            putLong(LlmProtocol.KEY_REQUEST_ID, requestId)
                        }
                    },
                    onToken = { delta ->
                        send(reply, LlmProtocol.CB_TOKEN) {
                            putLong(LlmProtocol.KEY_REQUEST_ID, requestId)
                            putString(LlmProtocol.KEY_TOKEN, delta)
                        }
                    },
                )
                send(reply, LlmProtocol.CB_DONE) {
                    putLong(LlmProtocol.KEY_REQUEST_ID, requestId)
                    putString(LlmProtocol.KEY_TEXT, result)
                }
            } catch (c: CancellationException) {
                // 卸载/切后台会取消在跑的推理；通知主进程，避免它的 await 永远挂着。
                sendError(reply, requestId, "翻译已中断（模型已卸载）")
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "translate failed", t)
                sendError(reply, requestId, t.message ?: "翻译失败")
            } finally {
                jobs.remove(requestId)
                // 一次推理也是一次活跃：30 秒空闲从它结束后才开始算。
                lastActivityMs = SystemClock.elapsedRealtime()
                if (deleteAudio) runCatching { File(audioPath).delete() }
            }
        }
    }

    private fun handleUnload() {
        loadJob?.cancel()
        loadJob = null
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        // 先同步把状态切到 IDLE，看门狗下一轮就不会重复触发卸载。
        lastActivityMs = SystemClock.elapsedRealtime()
        broadcast(LlmProtocol.STATE_IDLE, "")
        scope.launch {
            runtime.release()
            LlmDebug.i(TAG, "model released")
        }
    }

    private fun prefsFrom(data: Bundle) = LlmGenerationPrefs(
        maxOutputTokens = data.getInt(LlmProtocol.KEY_MAX_OUTPUT_TOKENS, 1024),
        contextTokens = data.getInt(LlmProtocol.KEY_CONTEXT_TOKENS, 8192),
        topK = data.getInt(LlmProtocol.KEY_TOP_K, 64),
        topP = data.getFloat(LlmProtocol.KEY_TOP_P, 0.95f),
        temperature = data.getFloat(LlmProtocol.KEY_TEMPERATURE, 0.3f),
        useGpu = data.getBoolean(LlmProtocol.KEY_USE_GPU, true),
    )

    private fun broadcast(state: Int, message: String) {
        this.state = state
        this.stateMessage = message
        for (client in clients) {
            sendState(client)
        }
    }

    private fun sendState(client: Messenger) {
        send(client, LlmProtocol.CB_STATE) {
            putInt(LlmProtocol.KEY_STATE, state)
            putString(LlmProtocol.KEY_MESSAGE, stateMessage)
        }
    }

    private fun sendError(client: Messenger, requestId: Long, message: String) {
        send(client, LlmProtocol.CB_ERROR) {
            putLong(LlmProtocol.KEY_REQUEST_ID, requestId)
            putString(LlmProtocol.KEY_MESSAGE, message)
        }
    }

    private fun send(
        client: Messenger,
        what: Int,
        fill: Bundle.() -> Unit,
    ) {
        val msg = Message.obtain(null, what)
        msg.data = Bundle().apply(fill)
        try {
            client.send(msg)
        } catch (e: RemoteException) {
            clients -= client
        } catch (t: Throwable) {
            clients -= client
        }
    }

    override fun onDestroy() {
        LlmDebug.i(TAG, "service destroyed")
        scope.cancel()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        handlerThread?.quitSafely()
        handlerThread = null
        messenger = null
        // 进程可能马上退出；用一个独立作用域尽量把 native 引擎关干净。
        CoroutineScope(Dispatchers.IO).launch { runCatching { runtime.release() } }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "LlmService"

        /** 连续这么久没有 Ping / 翻译请求就卸载模型。 */
        const val IDLE_TIMEOUT_MS = 30_000L
        const val WATCHDOG_TICK_MS = 2_000L
    }
}
