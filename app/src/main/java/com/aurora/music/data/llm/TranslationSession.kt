package com.aurora.music.data.llm

import android.os.SystemClock
import android.util.Log
import com.aurora.music.data.llm.service.LlmClient
import com.aurora.music.data.llm.service.LlmLoadStatus
import com.aurora.music.data.subtitle.DecodedPcmWindow
import com.aurora.music.data.subtitle.SubtitleCue
import java.util.TreeMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 播放页翻译层的开关状态（进程内记忆），与字幕层一样进出播放页不丢失。 */
object TranslationUiMemory {
    var open: Boolean = false
}

/**
 * 有声书「边听边译」调度器。
 *
 * 字幕会话用 VAD 边解边产出句级 cue，这个列表在刚打开的一段时间里只是个「前缀」。
 * 所以时间轴端点只由「已经闭合的上一句的结束」决定（[boundsMs]），一产生就固定，
 * 后到的 cue 不会改变它；每一块规划好之后立即记进 [plan]，之后无论 cue
 * 怎么增长、播放怎么重算，同一段音频都还是同一个 [Chunk]、同一个缓存键。
 *
 * 翻译完成的块会立刻写进 [TranslationStore]（独立 SQLite）。重新播放时整章计划
 * 先读进内存：已持久化的块按时间直接命中，新的块在向后合并时一旦撞上已知端点
 * （也就是下一块的起点）就截断，绝不跨过去——所以端点永远是第一次生成的区间。
 *
 * 从端点到端点把连续的 cue 拼到 [targetAudioSec]（最多 30 秒，Gemma 的音频输入
 * 上限）为一个翻译块，把这一块的原始音频送给多模态模型，流式显示译文；当前块开始
 * 出结果后，立刻后台翻译下一块，只放进缓存，等播放位置走到下一块再显示。
 *
 * 只有在「合并决策不会因为后续 cue 而改变」时才提交下一块（[isSettled]）。
 * 打开翻译 / 切章节 / 拖进度之后，落地的那一块通常等不到推理结束就播完了，
 * 所以直接跳过它、立刻预翻下一块；落地块只在暂停或章节尾时才补翻。
 */
class TranslationSession(
    private val cuesProvider: () -> List<SubtitleCue>,
    private val client: LlmClient,
    private val spec: LlmModelSpec,
    private val prefs: LlmGenerationPrefs,
    private val targetAudioSec: Int,
    private val targetLanguage: String,
    private val slices: AudioSliceStore,
    /** 字幕管线已经解好的 PCM；命中时切片不再重新解码。 */
    private val pcmWindow: DecodedPcmWindow? = null,
    /** 字幕管线已经解码/识别到的章节秒；用来判断 cue 还会不会继续增长。 */
    private val processedUntilProvider: () -> Double = { 0.0 },
    /** 本章是否已经读到结尾（不会再有新 cue）。 */
    private val atEndProvider: () -> Boolean = { false },
    /** 已完成块 + 译文的持久化；端点一旦写入就不再改变。 */
    private val store: TranslationStore? = null,
) {

    enum class Status { WAITING, LOADING, GENERATING, READY, ERROR }

    /** 调试信息里显示的一段音频的状态。 */
    enum class SliceState { PENDING, SLICED, PREPARING, GENERATING, DONE, FAILED, SKIPPED }

    data class SliceInfo(
        val start: Double,
        val end: Double,
        val state: SliceState,
    )

    data class UiState(
        val status: Status = Status.WAITING,
        val text: String = "",
        val message: String = "",
        val modelTitle: String = "",
        /** 调试信息：当前播放位置所在的一段。 */
        val currentSlice: SliceInfo? = null,
        /** 调试信息：已经规划好、接下来要翻译的一段。 */
        val nextSlice: SliceInfo? = null,
    )

    data class Chunk(
        /** 端点毫秒值：缓存键和切片都用它，避免浮点误差导致同一段变出两个 key。 */
        val startMs: Long,
        val endMs: Long,
        val sourceText: String,
        val startIndex: Int,
        val endIndex: Int,
    ) {
        val start: Double get() = startMs / 1000.0
        val end: Double get() = endMs / 1000.0
        val key: String get() = "$startMs-$endMs"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val position = MutableStateFlow(0f)
    private val _state = MutableStateFlow(UiState(modelTitle = spec.shortTitle))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val cache = LinkedHashMap<String, String>()
    private val failed = HashSet<String>()

    /**
     * 已冻结的切块计划，按 startMs 排序。
     * 来源有两个：启动时从 [store] 恢复的历史块，以及本会话规划出来的块。
     * 一旦放进这里，端点就不再变化。
     */
    private val plan = TreeMap<Long, Chunk>()

    private var activeChunk: Chunk? = null
    private var activeKey: String? = null
    private var loopJob: Job? = null
    private var workerJob: Job? = null
    private var workerKey: String? = null
    private var workerVisible = false
    private var workerAcc = StringBuilder()

    /** 调试信息：当前 worker 处在「准备这一段」还是「正在理解语音」。 */
    @Volatile
    private var workerStage = SliceState.PREPARING

    /** 调试信息：下一段（已经算好时间点，可能还没切音频）。 */
    @Volatile
    private var nextSlice: Chunk? = null

    // 上一次发布的状态：调试信息刷新时只换切片部分，不打断正在显示的译文。
    @Volatile private var lastStatus = Status.WAITING
    @Volatile private var lastText = ""
    @Volatile private var lastMessage = ""

    private var gotPosition = false

    /** 打开翻译 / 切章节 / 拖进度之后的落地块：只定位、不翻译。 */
    @Volatile private var skipKey: String? = null

    /** 落地时刻（elapsedRealtime），用来判断「还是没有下一块可追」。 */
    private var skipAt = 0L

    /** 播放位置最后一次变化（elapsedRealtime）：只用来识别「暂停」。 */
    @Volatile private var lastPosChangeAt = SystemClock.elapsedRealtime()

    /** 最后一次拖动/跳转的时刻：跳转后短暂冷却，别每一帧都起任务又取消。 */
    @Volatile private var lastSeekAt = 0L

    /** 位置一次跳了太多（拖进度条 / 跳转按钮），下一轮按「落地」处理。 */
    @Volatile private var seeked = false

    /** 根据 cue 提前算好的切片计划（签名用来避免每 250ms 重复提交）。 */
    private var sliceJob: Job? = null
    private var planSignature = ""

    fun start() {
        if (loopJob != null) return
        // 点开翻译就开始在 :llm 进程里加载模型，不用等第一句字幕出来。
        client.load(spec, prefs)
        loopJob = scope.launch {
            loadPersistedPlan()
            runLoop()
        }
    }

    /**
     * 把本章已经翻译完成的块读进内存。
     *
     * 端点无条件恢复；译文只在模型和目标语言都一致时复用，否则只保留端点、
     * 等重新翻译后覆盖（端点是永久事实，不随模型变）。
     */
    private suspend fun loadPersistedPlan() {
        val s = store ?: return
        val entries = s.load()
        if (entries.isEmpty()) return
        var lastEnd = 0L
        var seeded = 0
        for (e in entries) {
            // 防御：历史数据可能有重叠 / 零长度，保留先出现的那一块。
            if (e.endMs <= e.startMs || e.startMs < lastEnd) continue
            val chunk = Chunk(e.startMs, e.endMs, "", -1, -1)
            plan[e.startMs] = chunk
            lastEnd = e.endMs
            if (e.modelId == spec.id && e.lang == language) cache[chunk.key] = e.text
            seeded++
        }
        LlmDebug.i(
            TAG,
            "loaded $seeded/${entries.size} persisted chunks " +
                "model=${spec.id} lang=$language",
        )
    }

    fun update(positionSec: Float) {
        gotPosition = true
        val prev = position.value
        if (positionSec != prev) {
            val now = SystemClock.elapsedRealtime()
            lastPosChangeAt = now
            if (abs(positionSec - prev) > SEEK_JUMP_SEC) {
                seeked = true
                lastSeekAt = now
            }
        }
        position.value = positionSec
    }

    fun close() {
        loopJob?.cancel()
        workerJob?.cancel()
        sliceJob?.cancel()
        loopJob = null
        workerJob = null
        sliceJob = null
        scope.cancel()
        // 会话结束不等于要卸载模型：模型去留由页面心跳决定（界面开着就继续 Ping），
        // 切章 / 改设置只是重建会话，引擎保持常驻。
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            // 切后台后不再提交新请求，心跳也停了，服务端 30 秒后就能卸载模型；
            // 回到前台（30 秒内引擎还在）时会从这里继续。
            if (!client.isActive) {
                delay(500)
                continue
            }
            if (!gotPosition) {
                delay(250)
                continue
            }
            val cues = normalizeCues(cuesProvider())
            val bounds = boundsMs(cues)
            val pos = position.value.toDouble()
            // 端点固定（取「上一句的结束」），位置落在哪个区间就是哪一段；
            // 端点只由已经闭合的 cue 决定，新 cue 到来不会改变它。
            val prev = activeChunk
            val chunk = if (prev != null && pos >= prev.start && pos < prev.end) {
                prev
            } else {
                chunkAt(cues, bounds, pos)
            }
            // 会话新建 / 位置一次性跳了很远 = 打开翻译、切章节、拖进度条、跳转。
            // 落在同一块里的小幅回拖不算，原来的流式译文继续用。
            val seekedNow = seeked
            seeked = false
            val jumped = chunk != null &&
                (prev == null || (seekedNow && chunk.key != prev.key))
            activeChunk = chunk
            if (chunk == null) {
                activeKey = null
                nextSlice = null
                skipKey = null
                val load = client.state.value
                when {
                    load.load == LlmLoadStatus.ERROR -> publish(
                        Status.ERROR,
                        "",
                        load.message.ifBlank { "模型加载失败" },
                    )
                    load.load == LlmLoadStatus.LOADING -> publish(
                        Status.LOADING,
                        "",
                        "正在加载模型…",
                    )
                    cues.isEmpty() -> publish(Status.WAITING, "", "正在准备字幕…")
                    else -> publish(Status.WAITING, "", "等待下一句…")
                }
                delay(250)
                continue
            }

            val key = chunk.key
            val now = SystemClock.elapsedRealtime()
            if (skipKey != null && skipKey != key) skipKey = null

            if (jumped) {
                // 刚落到新位置：当前块不翻——它通常等不到结果就播完了，直接追下一块。
                skipKey = if (cache[key] == null) key else null
                skipAt = now
            }
            // 落地块要马上有下一块可追，所以这里允许先提交「还没完全定稿」的下一块；
            // 它一旦提交就冻结，不会因为后续 cue 再变，只是可能比目标时长短一点。
            val next = nextChunk(
                cues,
                chunk,
                allowProvisional = jumped || skipKey == key,
            )
            if (jumped) {
                // 旧位置的可见任务已经没有意义；正在为「本块 / 下一块」跑的任务保留。
                val useful = workerJob?.isActive == true &&
                    (workerKey == key || workerKey == next?.key)
                if (!useful) cancelWorker()
            }

            if (key != activeKey) {
                activeKey = key
                val cached = cache[key]
                when {
                    cached != null -> {
                        // 正在为下一块预翻的任务是有效的，留着；其余（旧位置）丢掉。
                        if (workerKey != next?.key) cancelWorker()
                        skipKey = null
                        publish(Status.READY, cached)
                    }
                    workerKey == key && workerJob?.isActive == true -> {
                        // 后台预翻译的正好是这一块：直接转成前台，别重复推理。
                        skipKey = null
                        workerVisible = true
                        publish(Status.GENERATING, workerAcc.toString(), "正在理解语音…")
                    }
                    key == skipKey -> publish(
                        Status.WAITING,
                        "",
                        if (now - lastPosChangeAt < SEEK_SETTLE_MS) {
                            "正在追上播放位置…"
                        } else {
                            "正在准备下一段…"
                        },
                    )
                    else -> {
                        cancelWorker()
                        startWorker(chunk, visible = true)
                    }
                }
            }

            // 只要 cue 一到就把后面几块的切片时间点定下来并提前切好音频，
            // 播放走到时只剩发请求，不在播放位置现场解码。
            planAhead(cues, chunk)

            nextSlice = next
            // 只有真正的拖动/跳转需要冷却，正常播放（位置每 200ms 都在动）不受影响。
            val seekQuiet = now - lastSeekAt >= SEEK_SETTLE_MS
            if (seekQuiet) {
                // 当前块已经有结果、且没有任务在跑：立刻提前翻译下一块（不展示）。
                // 单人模型请求是串行的，所以这里等的就是「上一块正在流式输出」。
                if (workerJob?.isActive != true &&
                    next != null && cache[next.key] == null && next.key !in failed
                ) {
                    startWorker(next, visible = false)
                }
                // 被跳过的落地块没有下一块可追（或用户停在这里不动）时补翻它，
                // 免得章节尾 / 暂停时整段没有译文。
                val skipped = skipKey == key && cache[key] == null && key !in failed
                if (workerJob?.isActive != true && skipped &&
                    (now - lastPosChangeAt >= PAUSE_TRANSLATE_MS ||
                        (chunk.endIndex + 1 >= cues.size && now - skipAt >= SKIP_WAIT_MS))
                ) {
                    skipKey = null
                    startWorker(chunk, visible = true)
                }
            }
            // 调试信息要跟着切片 / worker 状态变，但不能盖掉正在流式显示的译文：
            // 只重新发布同一份状态，切片部分由 emit() 现算。
            if (LlmDebug.enabled) emit()
            delay(250)
        }
    }

    private fun startWorker(chunk: Chunk, visible: Boolean) {
        workerJob?.cancel()
        val key = chunk.key
        val acc = StringBuilder()
        workerKey = key
        workerVisible = visible
        workerAcc = acc
        workerStage = SliceState.PREPARING
        failed.remove(key)
        if (visible && activeKey == key) {
            // 模型通常已经常驻，别把每个新块的准备阶段都显示成「加载模型」。
            val loadingModel = client.state.value.load != LlmLoadStatus.READY
            publish(Status.LOADING, "", if (loadingModel) "正在加载模型…" else "正在准备这一段…")
        }
        // 诊断用：把「准备这一段」拆成 切片 / 等上一段收尾+建会话 / 音频编码+首个 token。
        // 三段各自的实际耗时只打日志，不影响调度。
        val t0 = SystemClock.elapsedRealtime()
        val tSlice = AtomicLong(0)
        val tReady = AtomicLong(0)
        val tFirst = AtomicLong(0)
        val job = scope.launch {
            try {
                // 大概率已经由 planAhead 提前切好；没有才现切（并且优先用已解码 PCM）。
                val audio = slices.ensure(key, chunk.start, chunk.end, pcmWindow)
                    ?: throw IllegalStateException("这一段没有可读取的音频")
                tSlice.set(SystemClock.elapsedRealtime())
                val prompt = buildPrompt()
                val result = client.translate(
                    spec = spec,
                    prefs = prefs,
                    audioFile = audio,
                    prompt = prompt,
                    onReady = {
                        tReady.compareAndSet(0, SystemClock.elapsedRealtime())
                        if (workerKey == key) workerStage = SliceState.GENERATING
                        if (workerVisible && activeKey == key) {
                            publish(Status.GENERATING, acc.toString(), "正在理解语音…")
                        }
                    },
                    onToken = { delta ->
                        tFirst.compareAndSet(0, SystemClock.elapsedRealtime())
                        acc.append(delta)
                        if (workerVisible && activeKey == key) {
                            publish(Status.GENERATING, acc.toString(), "")
                        }
                    },
                )
                cache[key] = result
                // 完成即落盘：端点和译文一起写进独立的 SQLite，下一次播放按原区间命中。
                // 写在 publish 之前，几毫秒的 IO 换「一完成就是永久事实」。
                store?.let { s ->
                    runCatching {
                        s.put(chunk.startMs, chunk.endMs, result, spec.id, language)
                    }.onFailure {
                        Log.w(TAG, "persist chunk $key failed", it)
                    }
                }
                if (workerVisible && activeKey == key) {
                    publish(Status.READY, result)
                }
                logTiming(key, visible, chunk, t0, tSlice, tReady, tFirst, "ok")
            } catch (c: CancellationException) {
                logTiming(key, visible, chunk, t0, tSlice, tReady, tFirst, "cancelled")
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "translate ${chunk.key} failed", t)
                failed += key
                if (workerVisible && activeKey == key) {
                    publish(Status.ERROR, "", t.message ?: "翻译失败")
                }
                logTiming(key, visible, chunk, t0, tSlice, tReady, tFirst, "error=${t.message}")
            }
        }
        workerJob = job
    }

    private fun logTiming(
        key: String,
        visible: Boolean,
        chunk: Chunk,
        t0: Long,
        tSlice: AtomicLong,
        tReady: AtomicLong,
        tFirst: AtomicLong,
        tail: String,
    ) {
        if (!LlmDebug.enabled) return
        val slice = tSlice.get()
        val ready = tReady.get()
        val first = tFirst.get()
        LlmDebug.i(
            TAG,
            "chunk $key visible=$visible dur=${(chunk.end - chunk.start).toInt()}s " +
                "slice=${if (slice > 0) slice - t0 else -1}ms " +
                "ready=${if (ready > 0) ready - t0 else -1}ms " +
                "first=${if (first > 0) first - t0 else -1}ms " +
                "total=${SystemClock.elapsedRealtime() - t0}ms $tail",
        )
    }

    private fun cancelWorker() {
        if (workerVisible && workerJob?.isActive == true) {
            // 播放已经走过这一段、模型还没译完：已生成的部分会被丢掉重来，
            // 这条日志能看出「慢到跟不上播放」发生得有多频繁。
            LlmDebug.i(TAG, "cancel unfinished visible chunk $workerKey")
        }
        workerJob?.cancel()
        workerJob = null
        workerKey = null
        workerVisible = false
    }

    private fun publish(status: Status, text: String, message: String = "") {
        lastStatus = status
        lastText = text
        lastMessage = message
        emit()
    }

    /** 发布当前状态；切片信息每次现算，调试信息开关关掉时完全不碰文件系统。 */
    private fun emit() {
        val debug = LlmDebug.enabled
        _state.value = UiState(
            status = lastStatus,
            text = lastText,
            message = lastMessage,
            modelTitle = spec.shortTitle,
            currentSlice = if (debug) {
                activeChunk?.let { SliceInfo(it.start, it.end, sliceState(it)) }
            } else {
                null
            },
            nextSlice = if (debug) {
                nextSlice?.let { SliceInfo(it.start, it.end, sliceState(it)) }
            } else {
                null
            },
        )
    }

    /** 这一段在调度上的位置：没切 / 已切 / 准备中 / 翻译中 / 完成 / 失败 / 已跳过。 */
    private fun sliceState(chunk: Chunk): SliceState = when {
        chunk.key == skipKey -> SliceState.SKIPPED
        cache.containsKey(chunk.key) -> SliceState.DONE
        chunk.key in failed -> SliceState.FAILED
        workerKey == chunk.key && workerJob?.isActive == true -> workerStage
        slices.peek(chunk.key) != null -> SliceState.SLICED
        else -> SliceState.PENDING
    }

    /** 目标语言（空串按简体中文处理），持久化时也用它。 */
    private val language: String get() = targetLanguage.ifBlank { "简体中文" }

    private fun buildPrompt(): String {
        return "请把这段有声书语音翻译成$language。只输出译文，不要原文、不要解释、不要加引号。"
    }

    /**
     * VAD 片段本身可能超过 30 秒（Whisper/SenseVoice 一次给一整段）。
     * 先把这种超长片段按时间均分成若干子段，保证每段都 <= 30 秒；
     * 这是「拆分」，不会丢掉任何采样。
     */
    private fun normalizeCues(cues: List<SubtitleCue>): List<SubtitleCue> {
        val hardMax = LlmModelCatalog.MAX_AUDIO_SECONDS.toDouble()
        if (cues.none { it.end - it.start > hardMax + 1e-3 }) return cues
        val out = ArrayList<SubtitleCue>(cues.size + 4)
        for (cue in cues) {
            val duration = cue.end - cue.start
            if (duration <= hardMax + 1e-3) {
                out += cue
                continue
            }
            val parts = ceil(duration / hardMax).toInt().coerceAtLeast(2)
            val step = duration / parts
            for (i in 0 until parts) {
                val start = cue.start + step * i
                val end = if (i == parts - 1) cue.end else cue.start + step * (i + 1)
                val from = (cue.text.length * i) / parts
                val to = (cue.text.length * (i + 1)) / parts
                val text = cue.text.substring(
                    from.coerceIn(0, cue.text.length),
                    to.coerceIn(0, cue.text.length),
                ).trim()
                out += SubtitleCue(start, end, text)
            }
        }
        return out
    }

    /**
     * 时间轴端点（毫秒）。相邻两句之间取「上一句的结束」：
     * 空隙静音归下一块，端点只依赖已经闭合的上一句。
     *
     * 这一点很关键：cue 是 VAD 边解边产出的，刚打开时列表只是个前缀。
     * 端点不依赖「下一句什么时候出现」，所以只要上一句已经识别出来，这个值就永久固定，
     * 缓存键（[Chunk.startMs] / [Chunk.endMs]）也就不会因为新 cue 而变。
     */
    private fun boundsMs(cues: List<SubtitleCue>): LongArray {
        val n = cues.size
        val out = LongArray(n + 1)
        if (n == 0) return out
        out[0] = (cues[0].start * 1000).roundToLong().coerceAtLeast(0L)
        for (i in 1 until n) {
            val prevEndMs = (cues[i - 1].end * 1000).roundToLong()
            val startMs = (cues[i].start * 1000).roundToLong()
            // ASR 片段偶尔轻微重叠时，端点最多退到本句起点，
            // 保证端点单调、这一段的长度不为负。
            out[i] = prevEndMs.coerceIn(out[i - 1], maxOf(startMs, out[i - 1]))
        }
        out[n] = (cues[n - 1].end * 1000).roundToLong().coerceAtLeast(out[n - 1])
        return out
    }

    /**
     * 从 [startMs] 开始规划一块。起点可以是 cue 边界、已知块终点，甚至句子中间
     * （ASR 重新生成后句子变了，但历史端点不能动）。
     *
     * 终点受两个硬约束：目标时长，以及「下一个已知端点」[hardEnd]——合并到那里就停，
     * 绝不跨过去。已知端点是以前翻译完成的块写进磁盘的事实，跨过去就会让旧块变样。
     *
     * 纯计算，不提交；调用方决定要不要冻结（写进 [plan]）。
     */
    private fun planFrom(cues: List<SubtitleCue>, startMs: Long): Chunk? {
        if (cues.isEmpty() || startMs < 0) return null
        plan[startMs]?.let { return it }

        val maxMs = targetAudioSec
            .coerceIn(5, LlmModelCatalog.MAX_AUDIO_SECONDS)
            .toLong() * 1000L
        val hardEnd = plan.higherKey(startMs) ?: Long.MAX_VALUE

        // 起点之后（或包含起点）的第一句。cue 已按 start 排序。
        var fromIndex = 0
        while (fromIndex < cues.size &&
            (cues[fromIndex].end * 1000).roundToLong() <= startMs
        ) {
            fromIndex++
        }
        if (fromIndex >= cues.size) return null
        val naturalEnd = (cues[fromIndex].end * 1000).roundToLong()

        // 前导静音太长时只裁掉静音；裁完已经顶到已知端点，说明没有可翻内容。
        var start = startMs
        if (naturalEnd - start > maxMs) start = naturalEnd - maxMs
        if (start >= hardEnd) return null
        plan[start]?.let { return it }

        var endMs = minOf(naturalEnd, hardEnd)
        var toIndex = fromIndex
        var i = fromIndex + 1
        while (i < cues.size) {
            // 中间静音太久就不跨段拼：那种地方留着端点切块更自然。
            if (cues[i].start - cues[i - 1].end > MAX_GAP_SEC) break
            val cand = (cues[i].end * 1000).roundToLong()
            // 撞到已知端点就到此为止；预算放不下也不拼。
            if (cand > hardEnd) break
            if (cand - start > maxMs) break
            endMs = cand
            toIndex = i
            i++
        }
        if (endMs <= start) return null

        val text = StringBuilder(cues[fromIndex].text)
        for (k in fromIndex + 1..toIndex) {
            if (text.isNotEmpty()) text.append(' ')
            text.append(cues[k].text)
        }
        return Chunk(
            startMs = start,
            endMs = endMs,
            sourceText = text.toString(),
            startIndex = fromIndex,
            endIndex = toIndex,
        )
    }

    /**
     * 这一块的合并决策还会不会因为后续 cue 而改变。
     *
     * 合并被已知端点截住 → 定稿（端点是磁盘里的历史事实，不会再变）。
     * 合并循环停在「预算 / 静音」上 → 定稿；停在「cue 用完了」上就不是——后面再冒出来的
     * cue 可能还会拼进来。否则就要等覆盖越过终点 + 拼接窗口 + VAD 最长语音段。
     */
    private fun isSettled(chunk: Chunk, cues: List<SubtitleCue>): Boolean {
        val next = plan.higherKey(chunk.startMs)
        if (next != null && next <= chunk.endMs) return true
        if (chunk.endIndex + 1 < cues.size) return true
        if (atEndProvider()) return true
        return processedUntilProvider() >= chunk.end + SETTLE_SEC
    }

    /** 时间轴端点上「最后一个 `bounds[i] <= pos`」的毫秒值。 */
    private fun boundaryAtOrBefore(bounds: LongArray, posMs: Long): Long? {
        val n = bounds.size - 1
        if (n <= 0) return null
        var lo = 0
        var hi = n - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bounds[mid] <= posMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (ans < 0) null else bounds[ans]
    }

    /**
     * 播放位置所在的那一段。
     *
     * 先按时间查已经持久化 / 已冻结的块（命中就直接复用，不看当前 cue 列表）；
     * 没有命中才用 cue 边界规划新块——新块的起点不能落进前一个已知块的内部，
     * 因为 ASR 重新生成后 cue 边界可能已经变了。
     */
    private fun chunkAt(cues: List<SubtitleCue>, bounds: LongArray, positionSec: Double): Chunk? {
        if (cues.isEmpty()) return null
        val posMs = (positionSec * 1000).roundToLong()
        val floor = plan.floorEntry(posMs)
        if (floor != null && posMs < floor.value.endMs) return floor.value
        val boundary = boundaryAtOrBefore(bounds, posMs) ?: return null
        val startMs = maxOf(boundary, floor?.value?.endMs ?: 0L)
        return planFrom(cues, startMs)
    }

    /**
     * 当前块的下一个块：从它的终点开始。
     * 只有定稿的块才提交并返回；还没定稿就返回 null，等后续 cue 到齐再算，
     * 避免拿一个马上会变长的临时块去切音频 / 推理，把整条时间轴搅乱。
     *
     * [allowProvisional] 用于「刚落地、急需下一块」的情况：允许提交还没完全定稿的块，
     * 提交后照样冻结，只是可能比目标时长短一点。
     */
    private fun nextChunk(
        cues: List<SubtitleCue>,
        current: Chunk,
        allowProvisional: Boolean = false,
    ): Chunk? {
        plan[current.endMs]?.let { return it }
        val candidate = planFrom(cues, current.endMs) ?: return null
        if (!allowProvisional && !isSettled(candidate, cues)) return null
        plan[candidate.startMs] = candidate
        return candidate
    }

    /**
     * cue 一到就把后面若干块的端点定下来并提前切好音频（端点固定 → 切一次就够，
     * 播放位置走到时不会重新算），播放走到该块时只剩发翻译请求。
     *
     * [nextChunk] 只返回定稿的块，所以这里不会把「还会变长」的临时块切下来。
     * 切音频优先用字幕管线已经解过的 PCM（[pcmWindow]），窗口没覆盖才回退解码。
     */
    private fun planAhead(cues: List<SubtitleCue>, current: Chunk) {
        val ahead = ArrayList<Chunk>(PLAN_CHUNKS)
        var c = current
        while (ahead.size < PLAN_CHUNKS) {
            val next = nextChunk(cues, c) ?: break
            ahead += next
            c = next
        }
        val signature = ahead.joinToString("|") { it.key }
        if (signature == planSignature) return
        planSignature = signature
        sliceJob?.cancel()
        if (ahead.isEmpty()) {
            sliceJob = null
            return
        }
        sliceJob = scope.launch(Dispatchers.IO) {
            for (chunk in ahead) {
                slices.ensure(chunk.key, chunk.start, chunk.end, pcmWindow)
            }
        }
    }

    private companion object {
        const val TAG = "TranslationSession"

        /** 提前规划/切片的块数（每块最多 30 秒，3 块约 1.5 分钟）。 */
        const val PLAN_CHUNKS = 3

        /** 两句之间静音超过这么久就不拼进同一块（用端点当分界）。 */
        const val MAX_GAP_SEC = 8.0

        /** 拖动/跳转之后短暂冷却，等位置稳定这么久再启动推理，避免反复起任务又取消。 */
        const val SEEK_SETTLE_MS = 400L

        /** 一次位置变化超过这么久就当成拖进度条 / 跳转，而不是正常播放。 */
        const val SEEK_JUMP_SEC = 2.5f

        /** 落地块没有下一块可追时，等这么久确认，再补翻当前块。 */
        const val SKIP_WAIT_MS = 1200L

        /** 位置停住这么久视为暂停：补翻被跳过的落地块。 */
        const val PAUSE_TRANSLATE_MS = 2500L

        /**
         * 判断「不会再有 cue 拼进来」的覆盖余量：拼接窗口 + VAD 最长语音段 + 余数。
         * VAD 单段最长 12 秒（见 [com.aurora.music.data.subtitle.SubtitleEngine]），
         * 落在合并窗口里的语音到这个时间点一定已经闭合。
         */
        const val SETTLE_SEC = MAX_GAP_SEC + 12.0 + 1.0
    }
}
