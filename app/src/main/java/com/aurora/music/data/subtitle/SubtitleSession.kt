package com.aurora.music.data.subtitle

import android.content.Context
import com.aurora.music.data.audiobook.AudiobookChapter
import com.aurora.music.data.audiobook.abookChapterKey
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 「边听边出字幕」的调度器。
 *
 * 目标：播放位置之后 [lookAheadSec] 秒（默认 60 s）内始终有字幕，
 * 但不整章预转写——播放 1 秒只识别 1 秒多一点的音频。
 *
 * 进度按「已处理区间」记录（见 [SubtitleStore] / [SubtitleCoverage]）：
 * 拖动进度条跳过去的部分不算已生成，回头听时会从空洞开头重新解码，
 * 所以 5 分钟听一会儿跳到 20 分钟、再回到 8 分钟，8–20 分钟照样会补出字幕。
 *
 * 数据流：ChapterPcmReader（按需解码）→ SubtitleEngine（VAD + ASR）→ 片段 + 磁盘缓存。
 */
class SubtitleSession(
    private val context: Context,
    private val store: SubtitleStore,
    private val models: AsrModelStore,
    private val spec: AsrModelSpec,
    private val bookId: String,
    private val chapter: AudiobookChapter,
    private val lookAheadSec: Int,
    private val threads: Int,
    /** 可选：把解码出的 PCM 顺手留给翻译层切片，避免同一段音频解码两次。 */
    private val pcmWindow: DecodedPcmWindow? = null,
) {

    enum class Status { LOADING, GENERATING, READY, ERROR }

    data class UiState(
        val status: Status = Status.LOADING,
        val cues: List<SubtitleCue> = emptyList(),
        val message: String = "",
    )

    private val chapterKey = abookChapterKey(chapter.path, chapter.startMs)
    private val chapterLen = when {
        chapter.durationSec > 0 -> chapter.durationSec.toDouble()
        chapter.endMs > chapter.startMs -> (chapter.endMs - chapter.startMs) / 1000.0
        else -> Double.MAX_VALUE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val position = MutableStateFlow(0)

    /** VAD 片段的原始结果（含静音片段），持久化的主数据。 */
    private val segments = ArrayList<SubtitleSegment>()

    /** 由 [segments] 切句得到的展示用字幕，供 UI 直接读。 */
    private var uiCues: List<SubtitleCue> = emptyList()

    /** 已经解码 + 识别过的区间，有序且已合并。 */
    private val spans = ArrayList<SubtitleCoverage.Span>()

    private var engine: SubtitleEngine? = null
    private var reader: ChapterPcmReader? = null

    /** 当前解码管线的起点（章节秒），VAD 时间戳的基准。 */
    private var anchor = 0.0

    /** 管线已喂入 VAD 的音频终点（章节秒）。 */
    private var decodedUntil = 0.0

    /** 续接时，这条时间线之前的片段视为重复（会丢弃）。 */
    private var duplicateFloor = 0.0

    /** 章节音频的真实终点：读到 EOF 后才知道；未知时为 [Double.MAX_VALUE]。 */
    private var eofSec = Double.MAX_VALUE

    /** 模型/音频打不开就别再重试，否则会每秒重建一次引擎。 */
    private var fatal = false
    private var gotPosition = false
    private var job: Job? = null

    /** 同一句诊断只打一次，避免 400 ms 循环刷屏。 */
    private var lastLog = ""

    private fun dbg(msg: String) {
        if (msg == lastLog) return
        lastLog = msg
        android.util.Log.i(TAG, msg)
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            val loaded = store.load(bookId, chapterKey)
            val cached = loaded?.takeIf { it.modelId == spec.id }
            dbg(
                "load key=$chapterKey model=${spec.id} hit=${cached != null} " +
                    "segments=${loaded?.segments?.size} spans=${loaded?.spans?.size} " +
                    "eof=${loaded?.eofSec} modelInCache=${loaded?.modelId} len=$chapterLen",
            )
            if (cached != null) {
                segments.clear()
                segments.addAll(cached.segments)
                spans.clear()
                spans.addAll(cached.spans.filter { it.end > it.start }.sortedBy { it.start })
                if (cached.eofSec > 0) eofSec = min(cached.eofSec, chapterLen)
                rebuildCues()
                publish(Status.GENERATING, "")
                dbg("cache cues=${uiCues.size} spans=${spans.size} eof=$eofSec")
            }
            while (isActive) {
                if (!gotPosition) {
                    dbg("waiting position")
                    delay(200)
                    continue
                }
                if (fatal) {
                    delay(1000)
                    continue
                }
                val pos = position.value.toDouble().coerceAtLeast(0.0)
                val end = min(chapterLen, eofSec)
                if (end - pos <= 0.5) {
                    // 已经到章节末尾，没什么可生成的了
                    dbg("at end pos=$pos end=$end cues=${uiCues.size}")
                    publish(Status.READY, "")
                    delay(400)
                    continue
                }
                val target = min(end, pos + lookAheadSec)
                val gap = SubtitleCoverage.firstGap(spans, pos, target)
                dbg(
                    "tick pos=$pos end=$end target=$target gap=$gap decoded=$decodedUntil " +
                        "spans=${spans.size} segs=${segments.size} cues=${uiCues.size}",
                )
                if (gap == null) {
                    // 需要的时间段都已经处理过：不重复解码
                    publish(if (target >= end - 1.0) Status.READY else Status.GENERATING, "")
                    delay(400)
                    continue
                }
                // 空洞在管线后方，或远在前方（跳着听）：重开；紧贴在管线前方的继续解
                if (reader == null || SubtitleCoverage.needsRestart(gap, anchor, decodedUntil)) {
                    dbg("restart at ${max(0.0, gap - OVERLAP_SEC)} covered=$gap")
                    restartAt(max(0.0, gap - OVERLAP_SEC), gap)
                }
                step()
            }
        }
    }

    fun update(positionSec: Int) {
        gotPosition = true
        position.value = positionSec
    }

    /**
     * 关闭会话。native 解码不可中断，所以先 cancel 再等当前 step 退出，
     * 之后才释放 recognizer/VAD——否则可能在 decode 中途 free 掉句柄。
     * 清理放后台线程，避免 onDispose 阻塞主线程。
     */
    fun close() {
        val j = job
        val r = reader
        val e = engine
        job = null
        reader = null
        engine = null
        j?.cancel()
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            runCatching { j?.join() }
            runCatching { r?.close() }
            runCatching { e?.close() }
            scope.cancel()
        }
    }

    // ---- internals ----

    private suspend fun step() {
        if (!scope.isActive) return
        val eng = engine ?: openEngine() ?: return
        val r = reader ?: openReader(eng) ?: return

        val buf = FloatArray(16000 * 10)
        val n = r.read(buf)
        if (n <= 0) {
            // 本章读完：把尾巴上的片段冲出来，并把终点记成已处理
            val tail = eng.feed(FloatArray(0), flush = true)
            appendSegments(tail)
            closeReader()
            addSpan(anchor, decodedUntil)
            eofSec = min(eofSec, decodedUntil)
            persist()
            dbg("eof decoded=$decodedUntil tail=${tail.size} segs=${segments.size} cues=${uiCues.size}")
            publish(Status.READY, "")
            return
        }
        val pcm = if (n == buf.size) buf else buf.copyOf(n)
        // 这份 PCM 已经解出来了，顺手给翻译层留着按 VAD 边界切块，别再解一遍。
        pcmWindow?.append(decodedUntil, pcm)
        val got = eng.feed(pcm)
        appendSegments(got)
        decodedUntil += n / 16000.0
        addSpan(anchor, decodedUntil)
        persist()
        dbg("step n=$n decoded=$decodedUntil got=${got.size} segs=${segments.size} cues=${uiCues.size}")
        publish(Status.GENERATING, "")
    }

    private suspend fun openEngine(): SubtitleEngine? {
        publish(Status.LOADING, "")
        val vadFile = runCatching { models.ensureVadModel() }.getOrNull()
        if (vadFile == null || !vadFile.isFile) {
            fatal = true
            dbg("vad missing path=${vadFile?.absolutePath}")
            publish(Status.ERROR, "无法下载语音切句模型（VAD），请检查网络后重试")
            return null
        }
        val e = SubtitleEngine(spec, asrModelDir(context, spec), vadFile, threads)
        if (!e.prepare()) {
            e.close()
            fatal = true
            dbg("engine prepare failed model=${asrModelDir(context, spec).absolutePath}")
            publish(Status.ERROR, "模型加载失败，请到设置里重新下载")
            return null
        }
        if (!scope.isActive) {
            // close() 已经跑过了：别把新引擎挂上去，直接释放
            e.close()
            return null
        }
        engine = e
        return e
    }

    private fun openReader(eng: SubtitleEngine): ChapterPcmReader? {
        val start = anchor
        eng.reset(start)
        val r = ChapterPcmReader(
            path = chapter.path,
            startMs = chapter.startMs + (start * 1000).toLong(),
            endMs = chapter.endMs,
        )
        dbg(
            "openReader start=$start path=${chapter.path} " +
                "chapter=${chapter.startMs}..${chapter.endMs} dur=${chapter.durationSec}",
        )
        if (!r.open()) {
            fatal = true
            dbg("openReader failed start=$start")
            publish(Status.ERROR, "无法读取章节音频")
            return null
        }
        reader = r
        publish(Status.GENERATING, "")
        return r
    }

    /**
     * 从 [startSec] 重开解码管线。[coveredUntil] 之前的时间段已经有结果，
     * 重叠的几百毫秒里识别出的重复片段会被丢弃。
     */
    private fun restartAt(startSec: Double, coveredUntil: Double) {
        closeReader()
        anchor = startSec
        decodedUntil = startSec
        duplicateFloor = coveredUntil
    }

    private fun closeReader() {
        runCatching { reader?.close() }
        reader = null
    }

    private fun addSpan(start: Double, end: Double) {
        SubtitleCoverage.add(spans, start, end)
    }

    private fun appendSegments(newSegs: List<SubtitleSegment>) {
        if (newSegs.isEmpty()) return
        var changed = false
        for (s in newSegs) {
            if (s.end <= duplicateFloor + 0.05) continue
            if (segments.any { abs(it.start - s.start) < 0.6 && it.text == s.text }) continue
            // 起点落在已处理区间、且那里已经有片段：多半是重叠区里切出来的半句，丢掉
            if (s.start < duplicateFloor - 0.05 &&
                segments.any { it.start < duplicateFloor + 0.05 && it.end > s.start + 0.05 }
            ) continue
            segments.add(s)
            changed = true
        }
        if (!changed) return
        segments.sortBy { it.start }
        rebuildCues()
    }

    private fun rebuildCues() {
        uiCues = segments.asSequence()
            .filter { it.text.isNotBlank() }
            .flatMap { SubtitleCueSplitter.split(it.text, it.start, it.end).asSequence() }
            .sortedBy { it.start }
            .toList()
    }

    private fun persist() {
        if (segments.isEmpty() && spans.isEmpty()) return
        val eof = if (eofSec == Double.MAX_VALUE) 0.0 else eofSec
        store.save(bookId, chapterKey, spec.id, segments.toList(), spans.toList(), eof)
    }

    private fun publish(status: Status, message: String) {
        _state.value = UiState(status = status, cues = uiCues, message = message)
    }

    private companion object {
        const val TAG = "SubtitleSession"

        /** 重开管线时往前多解一点，避免 VAD 从半句话中间开始切。 */
        const val OVERLAP_SEC = 1.0
    }
}
