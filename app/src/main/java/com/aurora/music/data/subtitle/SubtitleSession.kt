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
 * 目标：字幕始终领先播放位置 [lookAheadSec] 秒（默认 60 s），
 * 但不整章预转写——播放 1 秒只识别 1 秒多一点的音频。
 *
 * 数据流：ChapterPcmReader（按需解码）→ SubtitleEngine（VAD + ASR）→ cues + 磁盘缓存。
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
    private val cues = ArrayList<SubtitleCue>()
    private var engine: SubtitleEngine? = null
    private var reader: ChapterPcmReader? = null

    /** 当前解码管线的起点（章节秒），VAD 时间戳的基准。 */
    private var anchor = 0.0

    /** 管线已喂入 VAD 的音频终点（章节秒）。 */
    private var decodedUntil = 0.0

    /** 续接缓存时，这条时间线之前的 cue 视为重复（会丢弃）。 */
    private var duplicateFloor = 0.0
    private var exhausted = false
    private var gotPosition = false
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            val cached = store.load(bookId, chapterKey)?.takeIf { it.modelId == spec.id }
            if (cached != null && cached.cues.isNotEmpty()) {
                cues.clear()
                cues.addAll(cached.cues)
                decodedUntil = cached.cues.last().end
                duplicateFloor = 0.0
                publish(Status.GENERATING, "")
            }
            while (isActive) {
                if (!gotPosition) {
                    delay(200)
                    continue
                }
                val pos = position.value.toDouble()
                // 向前跳得比已生成内容还远（用户拖进度条）：从新位置重开管线
                if (!exhausted && pos > decodedUntil + max(10.0, lookAheadSec * 0.5)) {
                    restartAt(max(0.0, pos - 2.0))
                }
                // 向后跳到没有字幕覆盖的地方：也要从那里重开，否则一直空着
                if (pos + 1.5 < anchor && (cues.isEmpty() || cues.last().end < pos + 1.0)) {
                    restartAt(max(0.0, pos - 2.0))
                }
                if (decodedUntil >= chapterLen - 1.0 && _state.value.status != Status.READY) {
                    publish(Status.READY, "")
                }
                val target = min(chapterLen, pos + lookAheadSec)
                if (!exhausted && decodedUntil + 0.2 < target) {
                    step()
                } else {
                    if (exhausted) publish(Status.READY, "")
                    delay(400)
                }
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
            exhausted = true
            appendCues(eng.feed(FloatArray(0), flush = true))
            persist()
            publish(Status.READY, "")
            return
        }
        val pcm = if (n == buf.size) buf else buf.copyOf(n)
        appendCues(eng.feed(pcm))
        decodedUntil += n / 16000.0
        persist()
        publish(Status.GENERATING, "")
    }

    private suspend fun openEngine(): SubtitleEngine? {
        publish(Status.LOADING, "")
        val vadFile = runCatching { models.ensureVadModel() }.getOrNull()
        if (vadFile == null || !vadFile.isFile) {
            exhausted = true
            publish(Status.ERROR, "无法下载语音切句模型（VAD），请检查网络后重试")
            return null
        }
        val e = SubtitleEngine(spec, asrModelDir(context, spec), vadFile, threads)
        if (!e.prepare()) {
            e.close()
            exhausted = true
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
        // decodedUntil 在续接缓存时已经是「上一条字幕结束处」，这里只留 0.5 s 重叠
        val base = decodedUntil
        val start = max(0.0, base - 0.5)
        anchor = start
        decodedUntil = start
        duplicateFloor = base
        eng.reset(start)
        val r = ChapterPcmReader(
            path = chapter.path,
            startMs = chapter.startMs + (start * 1000).toLong(),
            endMs = chapter.endMs,
        )
        if (!r.open()) {
            exhausted = true
            publish(Status.ERROR, "无法读取章节音频")
            return null
        }
        reader = r
        publish(Status.GENERATING, "")
        return r
    }

    private fun restartAt(sec: Double) {
        runCatching { reader?.close() }
        reader = null
        anchor = sec
        decodedUntil = sec
        duplicateFloor = sec
        exhausted = false
    }

    private fun appendCues(newCues: List<SubtitleCue>) {
        if (newCues.isEmpty()) return
        for (c in newCues) {
            if (c.end <= duplicateFloor + 0.05) continue
            if (cues.any { abs(it.start - c.start) < 0.05 && it.text == c.text }) continue
            cues.add(c)
        }
        cues.sortBy { it.start }
    }

    private fun persist() {
        if (cues.isEmpty()) return
        val until = cues.last().end.coerceAtMost(chapterLen)
        store.save(bookId, chapterKey, spec.id, cues.toList(), until)
    }

    private fun publish(status: Status, message: String) {
        _state.value = UiState(status = status, cues = cues.toList(), message = message)
    }
}
