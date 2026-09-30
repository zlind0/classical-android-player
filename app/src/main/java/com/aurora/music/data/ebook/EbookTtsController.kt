package com.aurora.music.data.ebook

import android.content.Context
import android.speech.tts.Voice
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.UnifiedTtsPrefs
import com.aurora.music.playback.EbookTtsPlayer
import com.aurora.music.tts.SentenceSplitter
import com.aurora.music.tts.TtsEngineInfo
import com.aurora.music.tts.TtsOwner
import com.aurora.music.tts.TtsWorker
import com.aurora.music.tts.isSpeakable
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 睡眠定时（读完当前单位再停，一次性）：
 * deadlineElapsed = 到点时刻（elapsedRealtime 系）；
 * totalMinutes = 倒计时分钟（倒计时方式才有）；clockLabel = 定时时钟 "HH:mm"（定时方式才有）。
 */
data class SleepTimer(
    val deadlineElapsed: Long,
    val totalMinutes: Int?,
    val clockLabel: String?,
)

/** 距下一个 HH:mm 的毫秒数（已过则明天，纯函数，可单测）。 */
internal fun delayUntilNextClock(hour: Int, minute: Int, nowMillis: Long): Long {
    val target = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (target.timeInMillis <= nowMillis) target.add(Calendar.DATE, 1)
    return (target.timeInMillis - nowMillis).coerceAtLeast(0)
}

/**
 * 电子书听书控制器（App 作用域，阅读界面驱动）：
 * - 朗读单位由 [EbookTtsUnit] 决定：PARA = 段落（正文块，含标题块），
 *   SENTENCE = 句子（段落按中英文标点再切分）；上下首跨章移动，
 *   句子模式下即上一句/下一句，播完一句自动下一句并翻页。
 * - 合成与播放全权委托 [TtsWorker]：内存字节流式入队（首块即播），
 *   同一 DSP 播放链，合成产物只留内存 LRU，播完即丢；
 *   下一段后台预热内存缓存，连续听无断档。
 * - 本控制器只留：分段建表、位置/翻页/计时、睡眠定时、目录标题。
 */
class EbookTtsController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: EbookPrefs
    /** 统一 TTS 设置（与歌曲介绍共用；引擎/音色/语速/音调由此来，切片单位仍走 prefs）。 */
    lateinit var settings: SettingsStore
    /** 统一合成与播放入口（AppContainer 注入；onQueueEnded 接线由 worker 持有）。 */
    lateinit var tts: TtsWorker
    /** 进度仓库（AppContainer 注入）：TTS 推进时直存章/块/字，UI 不在前台也丢不了。 */
    var store: EbookStore? = null
    /** 当前书的绝对路径（与阅读页 bookPath 同一串，供上面落盘用）。 */
    @Volatile var bookPath: String = ""
    private var _player: EbookTtsPlayer? = null
    var player: EbookTtsPlayer?
        get() = _player
        set(v) {
            _player = v
        }

    data class Para(val chapter: Int, val block: Int, val text: String, val startChar: Int = 0, val endChar: Int = Int.MAX_VALUE) {
        /** 实际朗读文本（页顶可能是段落中间；句子模式下只读本句区间）。 */
        val readText: String get() {
            val s = startChar.coerceIn(0, text.length)
            val e = endChar.coerceIn(s, text.length)
            return text.substring(s, e)
        }
    }

    /** 当前朗读单位计时：UI 按它算出读到第几页并自动翻（duration 为实测音频时长）。 */
    data class ParaTiming(
        val chapter: Int,
        val block: Int,
        val startChar: Int,
        val endChar: Int,
        val totalChars: Int,
        val durationMs: Long,
        val startedAt: Long,
    )

    private val _paraTiming = MutableStateFlow<ParaTiming?>(null)
    val paraTiming: StateFlow<ParaTiming?> = _paraTiming.asStateFlow()

    private var bookMd5: String = ""
    private var paras: List<Para> = emptyList()
    private var lastBook: ParsedEbook? = null
    private var unit: EbookTtsUnit = EbookTtsUnit.PARA

    private val _position = MutableStateFlow<Para?>(null)
    val position: StateFlow<Para?> = _position.asStateFlow()

    /** 当前朗读位置所在的目录标题（目录页 currentEntry 同口径，供通知栏作者栏；无目录时为空，由调用方回退书名）。 */
    private val _sectionTitle = MutableStateFlow("")
    val sectionTitle: StateFlow<String> = _sectionTitle.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** (chapter, block, char)：UI 翻到该字所在页。 */
    private val _turn = MutableStateFlow<Triple<Int, Int, Int>?>(null)
    val turnRequest: StateFlow<Triple<Int, Int, Int>?> = _turn.asStateFlow()

    /** 内置语音释放进度：委托 worker，两处 UI 共用同一份。 */
    val installing: StateFlow<Triple<Int, Int, String>?> get() = tts.installing

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun takeNotice(): String? {
        _notice.value?.let {
            _notice.value = null
            return it
        }
        return tts.takeNotice()
    }

    /** 睡眠定时：到点后读完当前单位（段/句）再停，一次性。 */
    private val _sleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer: StateFlow<SleepTimer?> = _sleepTimer.asStateFlow()

    /** 倒计时：从现在起 minutes 分钟后到点。 */
    fun setSleepMinutes(minutes: Int) {
        if (minutes <= 0) {
            _sleepTimer.value = null
            return
        }
        _sleepTimer.value = SleepTimer(
            deadlineElapsed = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L,
            totalMinutes = minutes,
            clockLabel = null,
        )
    }

    /** 定时：下一个 HH:mm 到点（已过则明天）。 */
    fun setSleepAt(hour: Int, minute: Int) {
        val delayMs = delayUntilNextClock(hour, minute, System.currentTimeMillis())
        _sleepTimer.value = SleepTimer(
            deadlineElapsed = android.os.SystemClock.elapsedRealtime() + delayMs,
            totalMinutes = null,
            clockLabel = "%02d:%02d".format(hour, minute),
        )
    }

    fun cancelSleepTimer() {
        _sleepTimer.value = null
    }

    /** 系统 TTS 可选音色（初始化后可用）。 */
    private val _systemVoices = MutableStateFlow<List<Voice>>(emptyList())
    val systemVoices: StateFlow<List<Voice>> = _systemVoices.asStateFlow()

    @Volatile private var playJob: kotlinx.coroutines.Job? = null
    /** 当前朗读单位在建表里的下标（起点带页顶偏移时 position.startChar 与建表对不上，按它定位）。 */
    @Volatile private var positionIdx: Int = -1
    @Volatile private var pendingStartChar: Int = 0

    // ---- 书 ----

    fun setBook(md5: String, book: ParsedEbook, path: String = "") {
        if (bookMd5 == md5 && paras.isNotEmpty()) {
            if (path.isNotBlank()) bookPath = path
            return
        }
        stop()
        bookMd5 = md5
        if (path.isNotBlank()) bookPath = path
        lastBook = book
        unit = runCatching { prefs.ttsUnit.value }.getOrDefault(EbookTtsUnit.PARA)
        paras = buildUnits(book, unit)
        _position.value = null
        positionIdx = -1
    }

    /** 切换朗读切片单位：停掉当前朗读，按新单位重建（设置页调用）。 */
    fun setUnit(u: EbookTtsUnit) {
        if (u == unit && paras.isNotEmpty()) return
        stop()
        unit = u
        lastBook?.let { paras = buildUnits(it, u) }
        _position.value = null
        positionIdx = -1
    }

    private fun buildUnits(book: ParsedEbook, u: EbookTtsUnit): List<Para> =
        book.chapters.flatMapIndexed { ci, c ->
            c.blocks.flatMapIndexed { bi, b ->
                if (b.text.isBlank()) emptyList()
                else if (u == EbookTtsUnit.SENTENCE) {
                    SentenceSplitter.split(b.text)
                        .map { r -> Para(ci, bi, b.text, r.first, r.last + 1) }
                        .ifEmpty { listOf(Para(ci, bi, b.text)) }
                } else {
                    listOf(Para(ci, bi, b.text))
                }
            }
        }

    fun paraIndexOf(chapter: Int, block: Int): Int =
        paras.indexOfFirst { it.chapter == chapter && it.block >= block }
            .takeIf { it >= 0 } ?: paras.indexOfFirst { it.chapter == chapter }
            .takeIf { it >= 0 } ?: paras.indexOfFirst { it.chapter > chapter }
            .takeIf { it >= 0 } ?: (paras.size - 1).coerceAtLeast(0)

    /** 当前朗读位置所在的目录标题（目录页 currentEntry 同口径），见 [TocSection]。 */
    private fun sectionTitleFor(chapter: Int, block: Int): String {
        val toc = lastBook?.toc.orEmpty()
        return TocSection.currentEntry(toc, chapter, block)
            .takeIf { it >= 0 }?.let { toc[it].title.trim() }.orEmpty()
    }

    // ---- 播放控制 ----

    /** 从指定位置开始读；charOffset = 块内字偏移（页顶是段中时用，句子模式下定位到包含该字的那句）。 */
    fun playFrom(chapter: Int, block: Int, charOffset: Int = 0) {
        // 先精确定位到包含该字的朗读单位（句子模式下一块多句），找不到再按块回退
        var idx = paras.indexOfFirst {
            it.chapter == chapter && it.block == block &&
                charOffset >= it.startChar && charOffset < it.endChar.coerceAtMost(it.text.length)
        }
        if (idx < 0) {
            idx = paras.indexOfFirst {
                it.chapter == chapter && it.block == block && it.endChar.coerceAtMost(it.text.length) > charOffset
            }
        }
        if (idx < 0) idx = paraIndexOf(chapter, block)
        if (idx < 0 || idx >= paras.size) return
        val base = paras[idx]
        // 起点就是屏幕首字（页顶偏移）：句中也从该字起读，不吸附回句首，
        // 否则 turn 会翻回上一页；读完本句后按整句继续
        pendingStartChar = charOffset.coerceIn(0, base.text.length)
        startAt(idx, true)
    }

    /** 停止：什么都不读，高亮清除。没有暂停状态。睡眠定时按会话生效，手动停止即清除。
     * @param savePos 是否把停在哪写盘。阅读页退出时由 UI 按眼睛位置全量落盘，
     *   这里传 false 只停音频，避免耳朵位置后写覆盖眼睛位置。 */
    fun stop(savePos: Boolean = true) {
        // 先把停在哪存下来：position 清掉后就没了，通知栏“继续播放”靠 bridge 的 lastPara，
        // 下次打开靠这里的 DB 行。
        val cur = _position.value
        val bp = bookPath
        if (savePos && cur != null && bp.isNotBlank()) {
            store?.saveTtsPos(bp, cur.chapter, cur.block, cur.startChar)
        }
        playJob?.cancel()
        playJob = null
        runCatching { tts.stop(TtsOwner.EBOOK) }
        player?.stop()
        _playing.value = false
        _paraTiming.value = null
        _position.value = null
        // turn 跨书不保留：旧书残留的翻页请求绝不能在新书打开时执行
        //（新书 handledTurn 会重置为 null，会把旧值当成有效导航）。
        _turn.value = null
        positionIdx = -1
        _sleepTimer.value = null
    }

    fun prev() {
        val idx = currentIndex() ?: return
        val target = (idx - 1).coerceAtLeast(0)
        // 已经是第一段：重播本段
        startAt(if (target == idx) idx else target, true)
    }

    fun next() {
        val idx = currentIndex() ?: return
        val target = (idx + 1).coerceAtMost(paras.size - 1)
        startAt(target, true)
    }

    /**
     * 当前朗读单位的建表下标：优先用主循环记录的 [positionIdx]；
     * 对不上（重建等竞态）再按区间回退——起点可能带页顶偏移（句中），
     * 此时按包含该字的单位定位，不能按块首回退（否则会跳回段首句）。
     */
    private fun currentIndex(): Int? {
        val cur = _position.value ?: return null
        val saved = positionIdx
        if (saved in paras.indices) {
            val p = paras[saved]
            if (p.chapter == cur.chapter && p.block == cur.block) return saved
        }
        val s = cur.startChar
        paras.indexOfFirst {
            it.chapter == cur.chapter && it.block == cur.block &&
                s >= it.startChar && s < it.endChar.coerceAtMost(it.text.length)
        }.takeIf { it >= 0 }?.let { return it }
        return paras.indexOfFirst { it.chapter == cur.chapter && it.block == cur.block }
            .takeIf { it >= 0 }
    }

    private fun startAt(index: Int, movePage: Boolean) {
        // 同步废掉旧流代际（播放器停播由 worker 异步收尾），再开新任务
        runCatching { tts.stop(TtsOwner.EBOOK) }
        playJob?.cancel()
        player?.stop()
        // 已过期的定时直接清除，避免下次起读播一句即停
        _sleepTimer.value?.let { if (android.os.SystemClock.elapsedRealtime() >= it.deadlineElapsed) _sleepTimer.value = null }
        val job = scope.launch(Dispatchers.IO) {
            try {
                _playing.value = true
                // 统一设置播放前现读（设置页先停播再改，无延迟问题；降级提示走 worker notice）
                val (eff, _) = tts.resolvePrefs()
                playLoop(index, movePage, eff)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "朗读失败"
                _playing.value = false
            }
        }
        playJob = job
    }

    // 主循环：一段开一个 worker 流 → 首块即播 → 播完下一段；合成与播放流水线化，
    // 下一段在播本段时后台预热内存缓存。切段/停止时旧流代际作废，过期回调一律丢弃。
    private suspend fun playLoop(startIndex: Int, movePage: Boolean, tp: UnifiedTtsPrefs) {
        var idx = startIndex
        var first = true
        while (idx < paras.size) {
            val base = paras[idx]
            // 只有起点单位可带字偏移（页顶是段中时）；后续单位按建表区间原样读，
            // 句子模式下句首必须保留，不能重置为 0（否则第 N 句会读成段首到第 N 句尾）
            val para = if (idx == startIndex && pendingStartChar > base.startChar) {
                base.copy(startChar = pendingStartChar.coerceIn(base.startChar, base.endChar.coerceAtMost(base.text.length)))
            } else {
                base
            }
            pendingStartChar = 0
            // 纯标点/空白段（如单个 "。"）直接无缝跳过：不开流、不碰播放器、不翻页，
            // 否则合成侧产不出音频，主循环会被假取消卡死。
            if (!isSpeakable(para.readText)) {
                idx++
                continue
            }
            val stream = tts.openStream(TtsOwner.EBOOK)
            _position.value = para
            positionIdx = idx
            _sectionTitle.value = sectionTitleFor(para.chapter, para.block)
            // 每推进一段/一句就直存章/块/字：锁屏后 UI 没了、pager 动不了时靠这行续命
            store?.saveTtsPos(bookPath, para.chapter, para.block, para.startChar)
            if (first && movePage || !first) _turn.value = Triple(para.chapter, para.block, para.startChar)
            first = false
            // 每段现读一次设置：音量/语速等滑杆下一段即生效，不用停播重进
            val segPrefs = runCatching { settings.unifiedTts.first() }.getOrDefault(tp)
            // append 只等合成+入队（首块已开播），返回实测总时长与首播时刻
            val res = stream.append(para.readText, segPrefs)
            if (res.totalMs <= 0) {
                idx++
                continue
            }
            _paraTiming.value = ParaTiming(
                chapter = para.chapter,
                block = para.block,
                startChar = para.startChar,
                endChar = para.endChar.coerceAtMost(para.text.length),
                totalChars = para.readText.length.coerceAtLeast(1),
                durationMs = res.totalMs.coerceAtLeast(1000),
                startedAt = res.startedAtElapsed,
            )
            // 后台预热下一段（只写内存缓存，不碰播放器；过期自动丢弃）
            val nextIdx = idx + 1
            if (nextIdx < paras.size) {
                val gen = stream.generation
                val nextText = paras[nextIdx].readText
                scope.launch(Dispatchers.IO) {
                    runCatching { tts.warmCache(nextText, segPrefs, TtsOwner.EBOOK, gen) }
                }
            }
            stream.awaitDone()
            if (!stream.isAlive()) return // 被 stop/切段作废
            // 睡眠定时：本单位读完后若已到点就停（一次性，读完这段/句再停）
            _sleepTimer.value?.let { st ->
                if (android.os.SystemClock.elapsedRealtime() >= st.deadlineElapsed) {
                    _sleepTimer.value = null
                    _notice.value = "睡眠定时已到，停止朗读"
                    _paraTiming.value = null
                    _playing.value = false
                    return
                }
            }
            idx++
        }
        _paraTiming.value = null
        _playing.value = false
    }

    // ---- 引擎查询（UI 用，委托 worker） ----

    /** 系统引擎列表（内置除外，歌曲介绍同款）。 */
    suspend fun listSystemEngines(): List<TtsEngineInfo> = tts.listSystemEngines()

    fun refreshSystemVoices() {
        scope.launch(Dispatchers.IO) {
            val tp = settings.unifiedTts.first()
            _systemVoices.value = if (tp.isInternal) emptyList()
            else tts.systemVoices(tp.systemEnginePkg)
        }
    }
}
