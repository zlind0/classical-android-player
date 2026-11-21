package com.aurora.music.data.llm

import android.os.SystemClock
import android.util.Log
import com.aurora.music.data.llm.service.LlmClient
import com.aurora.music.data.llm.service.LlmLoadStatus
import com.aurora.music.data.subtitle.DecodedPcmWindow
import com.aurora.music.data.subtitle.SubtitleCue
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
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
 * 字幕会话已经用 VAD 把章节切成了句级 cue。时间轴按「VAD 空隙中点」切成固定端点：
 * 相邻两句之间取静音的中点作为分界，这样每句都有自己确定的 [start, end) 区间，
 * 播放位置落在哪个区间就是哪一段，不会因为从空隙哪一侧看过去而变样。
 *
 * 然后从端点到端点把连续的 cue 拼到 [targetAudioSec]（最多 30 秒，Gemma 的音频
 * 输入上限）为一个翻译块，把这一块的原始音频送给多模态模型，流式显示译文；
 * 当前块开始出结果后，立刻后台翻译下一块，只放进缓存，等播放位置走到下一块再显示。
 * 缓存键就是端点毫秒值，端点稳定 → 同一段音频永远命中同一份缓存。
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
) {

    enum class Status { WAITING, LOADING, GENERATING, READY, ERROR }

    /** 调试信息里显示的一段音频的状态。 */
    enum class SliceState { PENDING, SLICED, PREPARING, GENERATING, DONE, FAILED }

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
    private val chosenByStart = HashMap<Long, Chunk>()

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

    /** 根据 cue 提前算好的切片计划（签名用来避免每 250ms 重复提交）。 */
    private var sliceJob: Job? = null
    private var planSignature = ""

    fun start() {
        if (loopJob != null) return
        // 点开翻译就开始在 :llm 进程里加载模型，不用等第一句字幕出来。
        client.load(spec, prefs)
        loopJob = scope.launch { runLoop() }
    }

    fun update(positionSec: Float) {
        gotPosition = true
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
            // 端点固定（取 VAD 空隙中点），位置落在哪个区间就是哪一段：
            // 每 250ms 重算得到的是同一个 Chunk，不会因为从空隙另一侧看过去而变样。
            // 播放还在当前块内就继续用它——最后一句之后的尾巴会在新 cue 到来时延长，
            // 这种「还在进行中」的块不改，避免把正在跑的推理取消掉。
            val current = activeChunk
            val chunk = if (current != null && pos >= current.start && pos < current.end) {
                current
            } else {
                chunkAt(cues, bounds, pos)
            }
            activeChunk = chunk
            if (chunk == null) {
                activeKey = null
                nextSlice = null
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
            if (key != activeKey) {
                activeKey = key
                val cached = cache[key]
                when {
                    cached != null -> {
                        cancelWorker()
                        publish(Status.READY, cached)
                    }
                    workerKey == key && workerJob?.isActive == true -> {
                        // 后台预翻译的正好是这一块：直接转成前台，别重复推理。
                        workerVisible = true
                        publish(Status.GENERATING, workerAcc.toString(), "正在理解语音…")
                    }
                    else -> {
                        cancelWorker()
                        startWorker(chunk, visible = true)
                    }
                }
            }

            // 只要 cue 一到就把后面几块的切片时间点定下来并提前切好音频，
            // 播放走到时只剩发请求，不在播放位置现场解码。
            planAhead(cues, bounds, chunk)

            val next = nextChunk(cues, bounds, chunk)
            nextSlice = next
            // 当前块已经有结果、且没有任务在跑：提前翻译下一块（不展示）。
            if (workerJob?.isActive != true &&
                next != null && cache[next.key] == null && next.key !in failed
            ) {
                startWorker(next, visible = false)
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

    /** 这一段在调度上的位置：没切 / 已切 / 准备中 / 翻译中 / 完成 / 失败。 */
    private fun sliceState(chunk: Chunk): SliceState = when {
        cache.containsKey(chunk.key) -> SliceState.DONE
        chunk.key in failed -> SliceState.FAILED
        workerKey == chunk.key && workerJob?.isActive == true -> workerStage
        slices.peek(chunk.key) != null -> SliceState.SLICED
        else -> SliceState.PENDING
    }

    private fun buildPrompt(): String {
        val lang = targetLanguage.ifBlank { "简体中文" }
        return "请把这段有声书语音翻译成$lang。只输出译文，不要原文、不要解释、不要加引号。"
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
     * 时间轴端点（毫秒）。相邻两句之间取 VAD 空隙的中点：
     * 空隙的前半段算前一句，后半段算后一句，双方看到的是同一个分界点。
     *
     * 端点只由「前后两句的边界」决定，所以只要这两句已经识别出来，端点就不会再变，
     * 缓存键（[Chunk.startMs] / [Chunk.endMs]）也就稳定。
     */
    private fun boundsMs(cues: List<SubtitleCue>): LongArray {
        val n = cues.size
        val out = LongArray(n + 1)
        if (n == 0) return out
        out[0] = (cues[0].start * 1000).roundToLong().coerceAtLeast(0L)
        for (i in 1 until n) {
            val prevEndMs = (cues[i - 1].end * 1000).roundToLong()
            val startMs = (cues[i].start * 1000).roundToLong()
            // 空隙中点。ASR 片段偶尔轻微重叠时，端点最多退到上一段的端点，
            // 保证端点单调、这一段的长度不为负。
            val mid = (prevEndMs + startMs) / 2
            out[i] = mid.coerceIn(out[i - 1], maxOf(startMs, out[i - 1]))
        }
        out[n] = (cues[n - 1].end * 1000).roundToLong().coerceAtLeast(out[n - 1])
        return out
    }

    /**
     * 从 [fromIndex] 这一句所属的端点开始，按端点尽量拼满目标时长。
     *
     * 起点固定为 `bounds[fromIndex]`（空隙中点），终点固定为最后一句之后的端点，
     * 所以同一段区间算多少次都是同一个 [Chunk]；放不下下一句时就切在它前面的端点，
     * 绝不截断某一句。单句加上两侧静音超过 30 秒时只裁掉尾部静音，不会丢语音。
     */
    private fun chunkFor(cues: List<SubtitleCue>, bounds: LongArray, fromIndex: Int): Chunk? {
        if (fromIndex !in cues.indices) return null
        val maxMs = targetAudioSec
            .coerceIn(5, LlmModelCatalog.MAX_AUDIO_SECONDS)
            .toLong() * 1000L
        // 这一句所属的端点到下一句端点的区间；超过上限时砍掉前导静音（语音本身
        // 不超过 30 秒，见 normalizeCues），保证既不超上限、也不切掉语音。
        val end0Ms = bounds[fromIndex + 1]
        var startMs = bounds[fromIndex]
        if (end0Ms - startMs > maxMs) startMs = end0Ms - maxMs
        chosenByStart[startMs]?.let { return it }
        var endMs = end0Ms
        var toIndex = fromIndex
        var i = fromIndex + 1
        while (i < cues.size) {
            // 中间静音太久就不跨段拼：那种地方留着端点切块更自然。
            if (cues[i].start - cues[i - 1].end > MAX_GAP_SEC) break
            // 放不下就停在这里：不裁剪下一段音频。
            if (bounds[i + 1] - startMs > maxMs) break
            endMs = bounds[i + 1]
            toIndex = i
            i++
        }
        val text = StringBuilder(cues[fromIndex].text)
        for (k in fromIndex + 1..toIndex) {
            if (text.isNotEmpty()) text.append(' ')
            text.append(cues[k].text)
        }
        val chunk = Chunk(
            startMs = startMs,
            endMs = endMs,
            sourceText = text.toString(),
            startIndex = fromIndex,
            endIndex = toIndex,
        )
        // 终点落在「最后一句」之后时，那是 ASR 前沿的临时边界（新 cue 一到就会变成
        // 两句之间的中点），先别记住，等它变成真正的端点再缓存。
        if (toIndex < cues.size - 1) chosenByStart[startMs] = chunk
        return chunk
    }

    /**
     * 播放位置所在的那一段：二分找最后一个 `bounds[i] <= pos` 的 i。
     * 端点把时间轴铺满且不重叠，所以空隙里的位置也有唯一归属
     * （前半段归前一句，后半段归后一句），不会出现「换个方向看就变成另一段」。
     */
    private fun chunkAt(cues: List<SubtitleCue>, bounds: LongArray, positionSec: Double): Chunk? {
        if (cues.isEmpty()) return null
        val posMs = (positionSec * 1000).roundToLong()
        var lo = 0
        var hi = cues.size - 1
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
        // ans < 0：第一句之前；没有对应的段。
        if (ans < 0) return null
        return chunkFor(cues, bounds, ans)
    }

    /** 当前块的下一个块：从它的终点端点开始，所以 `next.start == current.end`。 */
    private fun nextChunk(cues: List<SubtitleCue>, bounds: LongArray, current: Chunk): Chunk? {
        val idx = current.endIndex + 1
        if (idx !in cues.indices) return null
        return chunkFor(cues, bounds, idx)
    }

    /**
     * cue 一到就把后面若干块的端点定下来并提前切好音频（端点固定 → 切一次就够，
     * 播放位置走到时不会重新算），播放走到该块时只剩发翻译请求。
     *
     * 切音频优先用字幕管线已经解过的 PCM（[pcmWindow]），窗口没覆盖才回退解码。
     */
    private fun planAhead(cues: List<SubtitleCue>, bounds: LongArray, current: Chunk) {
        val plan = ArrayList<Chunk>(PLAN_CHUNKS)
        var c = current
        while (plan.size < PLAN_CHUNKS) {
            val next = nextChunk(cues, bounds, c) ?: break
            plan += next
            c = next
        }
        val signature = plan.joinToString("|") { it.key }
        if (signature == planSignature) return
        planSignature = signature
        sliceJob?.cancel()
        if (plan.isEmpty()) {
            sliceJob = null
            return
        }
        sliceJob = scope.launch(Dispatchers.IO) {
            for (chunk in plan) {
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
    }
}
