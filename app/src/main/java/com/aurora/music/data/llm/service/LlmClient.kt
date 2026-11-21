package com.aurora.music.data.llm.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.aurora.music.data.llm.LlmDebug
import com.aurora.music.data.llm.LlmGenerationPrefs
import com.aurora.music.data.llm.LlmModelSpec
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class LlmLoadStatus { IDLE, LOADING, READY, ERROR }

data class LlmClientState(
    val connected: Boolean = false,
    val load: LlmLoadStatus = LlmLoadStatus.IDLE,
    val message: String = "",
)

/**
 * 主进程侧的 :llm 服务客户端。
 *
 * 模型驻留靠心跳维持：翻译界面打开且 App 在前台时，每 [PING_INTERVAL_MS] 给
 * 服务发一次 Ping（翻译请求本身也算活跃）。翻译界面退出、或 App 切到后台后
 * 心跳停止，服务端 30 秒收不到任何 Ping / 翻译请求就自行卸载模型。
 * 服务端是唯一决定「什么时候卸载」的一方，客户端不维护卸载计时。
 */
class LlmClient(private val context: Context) {

    private data class Request(
        val deferred: CompletableDeferred<String>,
        val onReady: () -> Unit,
        val onToken: (String) -> Unit,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(LlmClientState())
    val state: StateFlow<LlmClientState> = _state.asStateFlow()

    private val lock = Any()
    private var serviceMessenger: Messenger? = null
    private var binding = false
    private val pending = ArrayList<Message>()

    private val requests = ConcurrentHashMap<Long, Request>()
    private val nextId = AtomicLong(1)

    // ---- 模型驻留（心跳）状态 ----
    private val heartbeatLock = Any()
    private var heartbeatJob: Job? = null
    private var lastSpec: LlmModelSpec? = null
    private var lastPrefs: LlmGenerationPrefs? = null
    @Volatile private var translationActive = false
    @Volatile private var appForeground = true

    /** :llm 进程被 native 崩溃带走后，系统会自动重建进程；这里负责自动补发一次加载。 */
    @Volatile private var reloadAfterReconnect = false

    /** 防止「加载 → 崩溃 → 重连 → 再加载」无限循环；READY 后恢复额度。 */
    private var crashReloadBudget = 2

    private val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        onServiceMessage(msg)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val messenger = service?.let { Messenger(it) }
            val toSend: List<Message>
            synchronized(lock) {
                serviceMessenger = messenger
                binding = false
                toSend = pending.toList()
                pending.clear()
            }
            _state.value = _state.value.copy(connected = messenger != null)
            toSend.forEach { msg ->
                try {
                    messenger?.send(msg)
                } catch (e: RemoteException) {
                    handleSendFailure(msg)
                }
            }
            if (reloadAfterReconnect) {
                reloadAfterReconnect = false
                // 服务端看到 GPU 崩溃标记后会自动改用 CPU，这里补发加载即可自愈。
                reloadIfNeeded()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                serviceMessenger = null
                binding = false
            }
            _state.value = LlmClientState(
                connected = false,
                load = LlmLoadStatus.ERROR,
                message = "模型服务已断开",
            )
            failAll("模型服务已断开")
            if (crashReloadBudget > 0) {
                crashReloadBudget--
                reloadAfterReconnect = true
            }
        }
    }

    /**
     * 翻译界面开着且 App 在前台：只有这种状态下才值得继续提交翻译请求，
     * 后台里继续推理会把刚空闲下来的模型又拉起来。
     */
    val isActive: Boolean
        get() = translationActive && appForeground

    /**
     * 翻译界面是否正在显示（Compose 侧按组合生命周期调用）。
     *
     * 界面打开 = 开始每 5 秒的心跳；界面关闭 = 停止心跳，服务端 30 秒后自动卸载。
     * 切章 / 改设置导致会话重建时界面仍然开着，心跳不会中断，模型也不会重载。
     */
    fun setTranslationActive(active: Boolean) {
        if (translationActive == active) return
        translationActive = active
        // 打开界面时由 TranslationSession.start() 发起加载（会用当前设置里的模型）；
        // 这里只负责心跳，不按上一次的旧配置抢先加载。
        updateHeartbeat()
    }

    /** App 回到前台：恢复心跳；翻译界面还开着就把被卸载的模型重新加载回来。 */
    fun onAppForeground() {
        if (appForeground) return
        appForeground = true
        updateHeartbeat()
        if (translationActive) reloadIfNeeded()
    }

    /** App 切到后台：停止心跳，服务端 30 秒内收不到 Ping 就自己卸载。 */
    fun onAppBackground() {
        if (!appForeground) return
        appForeground = false
        updateHeartbeat()
    }

    /**
     * 提前加载模型（点开翻译按钮时调用）。不阻塞调用线程。
     * 已有常驻引擎时服务端会直接复用，不会重新读权重。
     */
    fun load(spec: LlmModelSpec, prefs: LlmGenerationPrefs) {
        lastSpec = spec
        lastPrefs = prefs
        // 顺带确保心跳在跑（界面打开、App 在前台才需要）。
        updateHeartbeat()
        _state.value = _state.value.copy(
            load = LlmLoadStatus.LOADING,
            message = "正在加载模型…",
        )
        send(Message.obtain(null, LlmProtocol.MSG_LOAD).apply {
            replyTo = replyMessenger
            data = prefsBundle(spec, prefs)
        })
    }

    /**
     * 翻译界面在前台时维持心跳；否则停掉，让服务端自然超时卸载。
     * Ping 只是「别卸载」的许可，不负责触发加载。
     */
    private fun updateHeartbeat() {
        val shouldPing = translationActive && appForeground
        synchronized(heartbeatLock) {
            if (!shouldPing) {
                heartbeatJob?.cancel()
                heartbeatJob = null
                return
            }
            if (heartbeatJob?.isActive == true) return
            heartbeatJob = scope.launch {
                while (true) {
                    send(Message.obtain(null, LlmProtocol.MSG_PING).apply {
                        replyTo = replyMessenger
                    })
                    delay(PING_INTERVAL_MS)
                }
            }
        }
    }

    /** 服务端模型不在了（首次 / 空闲卸载 / 进程重启）就按最后的配置重新加载。 */
    private fun reloadIfNeeded() {
        if (!translationActive || !appForeground) return
        val spec = lastSpec ?: return
        val prefs = lastPrefs ?: return
        when (_state.value.load) {
            LlmLoadStatus.READY, LlmLoadStatus.LOADING -> return
            else -> load(spec, prefs)
        }
    }

    /**
     * 请求翻译一段已经切好的 WAV 文件。模型没加载时服务会先加载再推理。
     *
     * 音频文件由调用方准备好（通常是 [com.aurora.music.data.llm.AudioSliceStore]），
     * 不再经过 `ByteArray` 中转，省掉一次整段音频的拷贝。
     *
     * @param deleteAfter 服务读完就删（临时文件）；切片缓存传 false，由缓存自己淘汰。
     * @param onReady 模型/会话就绪、开始接收音频时回调。
     * @param onToken 流式增量。
     */
    suspend fun translate(
        spec: LlmModelSpec,
        prefs: LlmGenerationPrefs,
        audioFile: File,
        prompt: String,
        deleteAfter: Boolean = false,
        onReady: () -> Unit,
        onToken: (String) -> Unit,
    ): String {
        lastSpec = spec
        lastPrefs = prefs
        // 翻译请求本身就是服务端的活跃信号，这里不需要额外的计时处理。
        val requestId = nextId.getAndIncrement()
        val deferred = CompletableDeferred<String>()
        val request = Request(deferred, onReady, onToken)
        requests[requestId] = request

        val msg = Message.obtain(null, LlmProtocol.MSG_TRANSLATE).apply {
            replyTo = replyMessenger
            data = prefsBundle(spec, prefs).apply {
                putLong(LlmProtocol.KEY_REQUEST_ID, requestId)
                putString(LlmProtocol.KEY_AUDIO_PATH, audioFile.absolutePath)
                putBoolean(LlmProtocol.KEY_AUDIO_DELETE, deleteAfter)
                putString(LlmProtocol.KEY_PROMPT, prompt)
            }
        }

        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                requests.remove(requestId)
                send(Message.obtain(null, LlmProtocol.MSG_CANCEL).apply {
                    data = Bundle().apply {
                        putLong(LlmProtocol.KEY_REQUEST_ID, requestId)
                    }
                })
            }
        }

        try {
            send(msg)
            return deferred.await()
        } finally {
            cancelHandle?.dispose()
            requests.remove(requestId)
            // 切片缓存的文件归 AudioSliceStore 管，不能在这里删，否则下一段可能还要复用。
            if (deleteAfter) {
                withContext(Dispatchers.IO) { runCatching { audioFile.delete() } }
            }
        }
    }

    private fun prefsBundle(spec: LlmModelSpec, prefs: LlmGenerationPrefs): Bundle =
        Bundle().apply {
            putString(LlmProtocol.KEY_MODEL_ID, spec.id)
            putInt(LlmProtocol.KEY_CONTEXT_TOKENS, prefs.contextTokens)
            putInt(LlmProtocol.KEY_MAX_OUTPUT_TOKENS, prefs.maxOutputTokens)
            putInt(LlmProtocol.KEY_TOP_K, prefs.topK)
            putFloat(LlmProtocol.KEY_TOP_P, prefs.topP)
            putFloat(LlmProtocol.KEY_TEMPERATURE, prefs.temperature)
            putBoolean(LlmProtocol.KEY_USE_GPU, prefs.useGpu)
            // :llm 是独立进程，调试开关得随请求一起带过去。
            putBoolean(LlmProtocol.KEY_DEBUG, LlmDebug.enabled)
        }

    private fun send(msg: Message) {
        val messenger = synchronized(lock) {
            val current = serviceMessenger
            if (current == null) {
                pending += msg
                ensureBoundLocked()
            }
            current
        }
        if (messenger == null) return
        try {
            messenger.send(msg)
        } catch (e: RemoteException) {
            handleSendFailure(msg)
        }
    }

    private fun ensureBoundLocked() {
        if (binding) return
        binding = true
        val intent = Intent(context, LlmService::class.java)
        val ok = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            binding = false
            _state.value = LlmClientState(
                connected = false,
                load = LlmLoadStatus.ERROR,
                message = "无法启动模型服务",
            )
            failAll("无法启动模型服务")
        }
    }

    private fun onServiceMessage(msg: Message) {
        val data = msg.data ?: Bundle()
        when (msg.what) {
            LlmProtocol.CB_STATE -> {
                val state = data.getInt(LlmProtocol.KEY_STATE, LlmProtocol.STATE_IDLE)
                val message = data.getString(LlmProtocol.KEY_MESSAGE).orEmpty()
                val status = when (state) {
                    LlmProtocol.STATE_LOADING -> LlmLoadStatus.LOADING
                    LlmProtocol.STATE_READY -> LlmLoadStatus.READY
                    LlmProtocol.STATE_ERROR -> LlmLoadStatus.ERROR
                    else -> LlmLoadStatus.IDLE
                }
                if (status == LlmLoadStatus.READY) crashReloadBudget = 2
                _state.value = LlmClientState(
                    connected = true,
                    load = status,
                    message = message,
                )
            }
            LlmProtocol.CB_READY -> {
                val id = data.getLong(LlmProtocol.KEY_REQUEST_ID)
                requests[id]?.onReady?.invoke()
            }
            LlmProtocol.CB_TOKEN -> {
                val id = data.getLong(LlmProtocol.KEY_REQUEST_ID)
                val token = data.getString(LlmProtocol.KEY_TOKEN).orEmpty()
                if (token.isNotEmpty()) requests[id]?.onToken?.invoke(token)
            }
            LlmProtocol.CB_DONE -> {
                val id = data.getLong(LlmProtocol.KEY_REQUEST_ID)
                val text = data.getString(LlmProtocol.KEY_TEXT).orEmpty()
                requests.remove(id)?.deferred?.complete(text)
            }
            LlmProtocol.CB_ERROR -> {
                val id = data.getLong(LlmProtocol.KEY_REQUEST_ID)
                val message = data.getString(LlmProtocol.KEY_MESSAGE).orEmpty().ifBlank { "翻译失败" }
                requests.remove(id)?.deferred?.completeExceptionally(IllegalStateException(message))
            }
        }
    }

    private fun handleSendFailure(msg: Message) {
        if (msg.what == LlmProtocol.MSG_TRANSLATE) {
            val id = msg.data?.getLong(LlmProtocol.KEY_REQUEST_ID) ?: return
            requests.remove(id)?.deferred?.completeExceptionally(
                IllegalStateException("模型服务不可用"),
            )
        }
        synchronized(lock) { serviceMessenger = null }
        _state.value = LlmClientState(
            connected = false,
            load = LlmLoadStatus.ERROR,
            message = "模型服务不可用",
        )
    }

    private fun failAll(message: String) {
        val snapshot = requests.values.toList()
        requests.clear()
        snapshot.forEach { it.deferred.completeExceptionally(IllegalStateException(message)) }
    }

    private companion object {
        /** 翻译界面在前台时的心跳间隔。 */
        const val PING_INTERVAL_MS = 5_000L
    }
}
