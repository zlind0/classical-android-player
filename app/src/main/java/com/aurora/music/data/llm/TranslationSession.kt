package com.aurora.music.data.llm

import android.util.Log
import com.aurora.music.data.llm.service.LlmClient
import com.aurora.music.data.llm.service.LlmLoadStatus
import com.aurora.music.data.subtitle.DecodedPcmWindow
import com.aurora.music.data.subtitle.SubtitleCue
import java.util.concurrent.CancellationException
import kotlin.math.ceil
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
 * 字幕会话已经用 VAD 把章节切成了句级 cue。这里从当前句开始，把连续的 cue 拼到
 * [targetAudioSec]（最多 30 秒，Gemma 的音频输入上限）为一个翻译块，把这一块的
 * 原始音频送给多模态模型，流式显示译文；当前块开始出结果后，立刻后台翻译下一块，
 * 只放进缓存，等播放位置走到下一块再显示。
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

    data class UiState(
        val status: Status = Status.WAITING,
        val text: String = "",
        val message: String = "",
        val modelTitle: String = "",
    )

    data class Chunk(
        val start: Double,
        val end: Double,
        val sourceText: String,
        val startIndex: Int,
        val endIndex: Int,
    ) {
        val key: String get() = "${(start * 1000).toLong()}-${(end * 1000).toLong()}"
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
            val pos = position.value.toDouble()
            // 关键：播放中位置会不断前进，不能每 250ms 用「当前句」重新算块，
            // 否则每个句子都会取消上一个 worker，模型永远停在「正在加载」。
            // 只要还在当前块内就继续用同一个块；正常走过块尾才取下一块。
            val current = activeChunk
            val chunk = when {
                current == null -> chunkAt(cues, pos)
                pos < current.start -> chunkAt(cues, pos)
                pos < current.end -> current
                pos - current.end <= 3.0 -> nextChunk(cues, current) ?: current
                else -> chunkAt(cues, pos) // 大跨度拖动进度条
            }
            activeChunk = chunk
            if (chunk == null) {
                activeKey = null
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
            planAhead(cues, chunk)

            // 当前块已经有结果、且没有任务在跑：提前翻译下一块（不展示）。
            if (workerJob?.isActive != true) {
                val next = nextChunk(cues, chunk)
                if (next != null && cache[next.key] == null && next.key !in failed) {
                    startWorker(next, visible = false)
                }
            }
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
        failed.remove(key)
        if (visible && activeKey == key) {
            // 模型通常已经常驻，别把每个新块的准备阶段都显示成「加载模型」。
            val loadingModel = client.state.value.load != LlmLoadStatus.READY
            publish(Status.LOADING, "", if (loadingModel) "正在加载模型…" else "正在准备这一段…")
        }
        val job = scope.launch {
            try {
                // 大概率已经由 planAhead 提前切好；没有才现切（并且优先用已解码 PCM）。
                val audio = slices.ensure(key, chunk.start, chunk.end, pcmWindow)
                    ?: throw IllegalStateException("这一段没有可读取的音频")
                val prompt = buildPrompt()
                val result = client.translate(
                    spec = spec,
                    prefs = prefs,
                    audioFile = audio,
                    prompt = prompt,
                    onReady = {
                        if (workerVisible && activeKey == key) {
                            publish(Status.GENERATING, acc.toString(), "正在理解语音…")
                        }
                    },
                    onToken = { delta ->
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
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "translate ${chunk.key} failed", t)
                failed += key
                if (workerVisible && activeKey == key) {
                    publish(Status.ERROR, "", t.message ?: "翻译失败")
                }
            }
        }
        workerJob = job
    }

    private fun cancelWorker() {
        workerJob?.cancel()
        workerJob = null
        workerKey = null
        workerVisible = false
    }

    private fun publish(status: Status, text: String, message: String = "") {
        _state.value = UiState(
            status = status,
            text = text,
            message = message,
            modelTitle = spec.shortTitle,
        )
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
     * 从当前句开始，尽量拼满目标时长。
     *
     * 下一句放进来会超过目标时长时，宁可少放这一句，也绝不截断它；
     * 单句本身超过 30 秒的情况已经在 [normalizeCues] 里拆成多段。
     */
    private fun chunkFor(cues: List<SubtitleCue>, fromIndex: Int): Chunk? {
        if (fromIndex !in cues.indices) return null
        val startKey = (cues[fromIndex].start * 1000).toLong()
        chosenByStart[startKey]?.let { return it }
        val maxSec = targetAudioSec.coerceIn(5, LlmModelCatalog.MAX_AUDIO_SECONDS).toDouble()
        val start = cues[fromIndex].start
        var end = cues[fromIndex].end
        val text = StringBuilder(cues[fromIndex].text)
        var i = fromIndex + 1
        while (i < cues.size) {
            val next = cues[i]
            // 放不下就停在这里：不裁剪下一段音频。
            if (next.end - start > maxSec + 1e-6) break
            if (next.start - end > 8.0) break // 中间静音太久，不跨段拼
            end = next.end
            if (text.isNotEmpty()) text.append(' ')
            text.append(next.text)
            i++
        }
        val chunk = Chunk(
            start = start,
            end = end,
            sourceText = text.toString(),
            startIndex = fromIndex,
            endIndex = (i - 1).coerceAtLeast(fromIndex),
        )
        chosenByStart[startKey] = chunk
        return chunk
    }

    private fun chunkAt(cues: List<SubtitleCue>, positionSec: Double): Chunk? {
        val idx = currentCueIndex(cues, positionSec)
        return if (idx >= 0) chunkFor(cues, idx) else null
    }

    private fun nextChunk(cues: List<SubtitleCue>, current: Chunk): Chunk? {
        val idx = cues.indexOfFirst { it.start >= current.end - 0.05 }
        if (idx < 0) return null
        return chunkFor(cues, idx)
    }

    /**
     * VAD/ASR 一给出新 cue，就把当前块之后若干块的切片时间点定下来（[chunkFor]
     * 会记住结果，后面实时播放不会再漂移），并提前把音频切好。
     *
     * 切音频优先用字幕管线已经解过的 PCM（[pcmWindow]），窗口没覆盖才回退解码；
     * 播放位置真正走到该块时，只剩下发翻译请求。
     */
    private fun planAhead(cues: List<SubtitleCue>, current: Chunk) {
        val plan = ArrayList<Chunk>(PLAN_CHUNKS)
        var c = current
        while (plan.size < PLAN_CHUNKS) {
            val next = nextChunk(cues, c) ?: break
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

    /** 最后一条 start <= position 的 cue；播放位置在第一句之前返回 -1。 */
    private fun currentCueIndex(cues: List<SubtitleCue>, positionSec: Double): Int {
        if (cues.isEmpty()) return -1
        var lo = 0
        var hi = cues.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= positionSec) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private companion object {
        const val TAG = "TranslationSession"

        /** 提前规划/切片的块数（每块最多 30 秒，3 块约 1.5 分钟）。 */
        const val PLAN_CHUNKS = 3
    }
}
