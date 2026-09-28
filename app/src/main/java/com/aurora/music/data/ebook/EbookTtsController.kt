package com.aurora.music.data.ebook

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.aurora.music.playback.EbookTtsPlayer
import com.aurora.music.tts.Chunker
import com.aurora.music.tts.SentenceSplitter
import com.aurora.music.tts.MsEmbeddedEngine
import com.aurora.music.tts.MsVoice
import com.aurora.music.tts.MsVoices
import com.aurora.music.tts.TtsWav
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
 * - 双引擎都产出 48k 立体声 WAV → 同一个 [EbookTtsPlayer]（完整 DSP 链）播放：
 *   内置走 MS 离线合成（分句流式，首块即播）；系统走 synthesizeToFile。
 * - 预合成下一段（lookahead 1），连续听无断档；合成文件按书 md5+引擎+音色+语速
 *   +切片单位内容寻址缓存，重听秒播。
 */
class EbookTtsController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: EbookPrefs
    private var _player: EbookTtsPlayer? = null
    var player: EbookTtsPlayer?
        get() = _player
        set(v) {
            _player = v
            v?.onQueueEnded = { scope.launch { onQueueEnded() } }
        }
    private val msEngine = MsEmbeddedEngine(appContext)

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

    /** 内置语音首次释放进度：null = 不需要显示；(done, total, name)。 */
    private val _installing = MutableStateFlow<Triple<Int, Int, String>?>(null)
    val installing: StateFlow<Triple<Int, Int, String>?> = _installing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun takeNotice(): String? {
        val v = _notice.value
        _notice.value = null
        return v
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
    @Volatile private var sysTts: TextToSpeech? = null
    @Volatile private var sysTtsReady: Boolean = false

    // ---- 书 ----

    fun setBook(md5: String, book: ParsedEbook) {
        if (bookMd5 == md5 && paras.isNotEmpty()) return
        stop()
        bookMd5 = md5
        lastBook = book
        unit = runCatching { prefs.tts.value.unit }.getOrDefault(EbookTtsUnit.PARA)
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

    /** 停止：什么都不读，高亮清除。没有暂停状态。睡眠定时按会话生效，手动停止即清除。 */
    fun stop() {
        playJob?.cancel()
        playJob = null
        msEngine.requestStop()
        player?.stop()
        _playing.value = false
        _paraTiming.value = null
        _position.value = null
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
        playJob?.cancel()
        msEngine.requestStop()
        player?.stop()
        // 已过期的定时直接清除，避免下次起读播一句即停
        _sleepTimer.value?.let { if (android.os.SystemClock.elapsedRealtime() >= it.deadlineElapsed) _sleepTimer.value = null }
        val job = scope.launch(Dispatchers.IO) {
            try {
                _playing.value = true
                // 切片单位以控制器实际建表的为准（设置页先停播再切，DataStore 回写有延迟，这里强制对齐）
                playLoop(index, movePage, resolveEngineForPlay().copy(unit = unit))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "朗读失败"
                _playing.value = false
            }
        }
        playJob = job
    }

    // 主循环：合成一段 → 交给 player 播 → 播完下一段。合成与播放流水线化：
    // 第 N+1 段在第 N 段播放时预合成好（lookahead 1）。
    private suspend fun playLoop(startIndex: Int, movePage: Boolean, tp: EbookTtsPrefs) {
        var idx = startIndex
        var first = true
        // 预取：下一段的文件
        var staged: Pair<Int, List<File>>? = null
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
            // 预取的是完整单位合成，起点单位带偏移时不能复用
            val files = if (staged?.first == idx && para == paras[idx]) staged.second
            else synthesizePara(para, tp)
            if (files.isEmpty()) {
                idx++
                continue
            }
            // 播放时后台预合成下一段
            val nextIdx = idx + 1
            val prefetch = if (nextIdx < paras.size) {
                scope.launch(Dispatchers.IO) {
                    runCatching { synthesizePara(paras[nextIdx], tp) }
                        .onSuccess { staged = nextIdx to it }
                }
            } else null
            _position.value = para
            positionIdx = idx
            _sectionTitle.value = sectionTitleFor(para.chapter, para.block)
            if (first && movePage || !first) _turn.value = Triple(para.chapter, para.block, para.startChar)
            first = false
            // 实测时长（48k 立体声 16bit：192000 字节/秒，去 44 字节头）
            val durMs = files.sumOf { (it.length() - 44).coerceAtLeast(0) } / 192
            _paraTiming.value = ParaTiming(
                chapter = para.chapter,
                block = para.block,
                startChar = para.startChar,
                endChar = para.endChar.coerceAtMost(para.text.length),
                totalChars = para.readText.length.coerceAtLeast(1),
                durationMs = durMs.coerceAtLeast(1000),
                startedAt = android.os.SystemClock.elapsedRealtime(),
            )
            withContext(Dispatchers.Main) { player?.playFiles(files) }
            // 等播完（onQueueEnded 推进）或被取消/切段
            val done = suspendCancellableCoroutine<Boolean> { cont ->
                pendingDone = { if (cont.isActive) cont.resume(true) }
                cont.invokeOnCancellation { pendingDone = null }
            }
            prefetch?.cancelAndJoin()
            if (!done) return // 被 pause/stop/切段取消
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
            staged = null
        }
        _paraTiming.value = null
        _playing.value = false
    }

    @Volatile private var pendingDone: (() -> Unit)? = null

    private fun onQueueEnded() {
        pendingDone?.invoke()
        pendingDone = null
    }

    // ---- 合成 ----

    private fun cacheKey(tp: EbookTtsPrefs, voiceId: String): String {
        val v = voiceId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
        val r = (tp.rate * 100).toInt()
        val p = (tp.pitch * 100).toInt()
        return "${tp.engine.name}_${v}_r${r}_p${p}_u${tp.unit.name}"
    }

    private fun paraDir(tp: EbookTtsPrefs, voiceId: String): File =
        File(appContext.filesDir, "ebook_tts/$bookMd5/${cacheKey(tp, voiceId)}")

    private suspend fun synthesizePara(para: Para, tp: EbookTtsPrefs): List<File> {
        val dir = paraDir(tp, tp.voice.ifBlank { "auto" })
        dir.mkdirs()
        val chunks = Chunker.split(para.readText)
        if (chunks.isEmpty()) return emptyList()
        val offTag = if (para.startChar > 0) "_o${para.startChar}" else ""
        val out = mutableListOf<File>()
        chunks.forEachIndexed { i, chunk ->
            val f = File(dir, "c${para.chapter}b${para.block}${offTag}_$i.wav")
            if (!f.exists() || f.length() <= 44) {
                val wav = if (tp.engine == EbookTtsEngine.INTERNAL) {
                    synthesizeInternal(chunk, tp)
                } else {
                    synthesizeSystem(chunk, tp)
                } ?: return emptyList()
                f.writeBytes(wav)
            }
            out.add(f)
        }
        return out
    }

    // ---- 内置引擎 ----

    val internalAvailable: Boolean
        get() = runCatching {
            // so 缺失直接抛 UnsatisfiedLinkError
            Class.forName("com.microsoft.cognitiveservices.speech.SpeechSynthesizer")
            org.nobody.multitts.tts.jni.SpeexBridge.getLicense(0)
            true
        }.getOrDefault(false)

    private suspend fun synthesizeInternal(chunk: String, tp: EbookTtsPrefs): ByteArray? {
        val voice = MsVoices.byCode(tp.voice.ifBlank { MsVoices.DEFAULT })
            ?: MsVoices.byCode(MsVoices.DEFAULT)!!
        if (!msEngine.isInstalled(voice.code)) {
            _installing.value = Triple(0, MsVoices.ALL.size, voice.code)
            try {
                msEngine.installIfNeeded { done, total, name ->
                    _installing.value = Triple(done, total, name)
                }
            } finally {
                _installing.value = null
            }
        }
        // 流式合成：首块 PCM 到达即归一化落盘，player 可在段尾前就绪
        // （此处仍整段合成完再播：块≤450字，本地合成极快；真流式边合边播见下）
        val mono24k = msEngine.synthesizeStreaming(chunk, voice, tp.rate, tp.pitch)
        if (mono24k.isEmpty()) return null
        val stereo48k = TtsWav.mono24kToStereo48k(mono24k)
        return TtsWav.encodeWav(48000, 2, 16, stereo48k)
    }

    // ---- 系统引擎 ----

    private suspend fun ensureSystemTts(): TextToSpeech? {
        sysTts?.takeIf { sysTtsReady }?.let { return it }
        sysTts?.shutdown()
        sysTtsReady = false
        val tts = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var t: TextToSpeech? = null
            t = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS && cont.isActive) cont.resume(t)
                else if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { t?.shutdown() } }
        } ?: return null
        sysTts = tts
        sysTtsReady = true
        runCatching { _systemVoices.value = tts.voices.orEmpty().sortedWith(compareBy({ it.locale.toString() }, { it.name })) }
        return tts
    }

    fun refreshSystemVoices() {
        scope.launch(Dispatchers.IO) { ensureSystemTts() }
    }

    private suspend fun synthesizeSystem(chunk: String, tp: EbookTtsPrefs): ByteArray? {
        val tts = ensureSystemTts() ?: throw IllegalStateException("系统 TTS 不可用")
        tts.setSpeechRate(tp.rate.coerceIn(0.5f, 2f))
        tts.setPitch(tp.pitch.coerceIn(0.5f, 2f))
        val wantVoice = tp.voice.ifBlank { null }
        val voice = wantVoice?.let { n -> tts.voices?.firstOrNull { it.name == n } }
        if (voice != null) {
            runCatching { tts.voice = voice }
            runCatching { tts.language = voice.locale }
        } else {
            // 自动：优先中文（简中→繁中），都没有就跟随系统
            val vs = tts.voices.orEmpty()
            val zh = vs.firstOrNull { it.locale.language == "zh" && it.locale.country == "CN" }
                ?: vs.firstOrNull { it.locale.language == "zh" }
            if (zh != null) {
                runCatching { tts.voice = zh }
                runCatching { tts.language = zh.locale }
            } else {
                runCatching { tts.language = Locale.getDefault() }
            }
        }
        val tmp = withContext(Dispatchers.IO) {
            File.createTempFile("systts", ".wav", appContext.cacheDir)
        }
        try {
            val id = UUID.randomUUID().toString()
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                val listener = object : UtteranceProgressListener() {
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(true)
                    }

                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id && cont.isActive) cont.resume(false)
                    }

                    override fun onStart(utteranceId: String?) {}
                }
                // 单例串行使用，旧 listener 无需恢复
                tts.setOnUtteranceProgressListener(listener)
                val r = tts.synthesizeToFile(chunk, Bundle(), tmp, id)
                if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                cont.invokeOnCancellation { runCatching { tts.stop() } }
            }
            if (!ok || !tmp.exists() || tmp.length() <= 44) return null
            val bytes = withContext(Dispatchers.IO) { tmp.readBytes() }
            val pcm = runCatching { TtsWav.decodeWav(bytes) }.getOrNull() ?: return null
            if (pcm.data.isEmpty()) return null
            val norm = TtsWav.normalize48kStereo16(pcm)
            if (norm.isEmpty()) return null
            return TtsWav.encodeWav(48000, 2, 16, norm)
        } finally {
            withContext(Dispatchers.IO) { runCatching { tmp.delete() } }
        }
    }

    // ---- 引擎可用性（UI 用） ----

    suspend fun effectiveEngine(): EbookTtsEngine = withContext(Dispatchers.IO) {
        val want = prefs.tts.value.engine
        if (want == EbookTtsEngine.INTERNAL && internalAvailable) return@withContext EbookTtsEngine.INTERNAL
        if (want == EbookTtsEngine.INTERNAL) {
            withContext(Dispatchers.Main) { _notice.value = "内置语音不可用，已切换到系统 TTS" }
        }
        EbookTtsEngine.SYSTEM
    }

    /** 播放前解析一次有效引擎；内置不可用自动降级并提示。 */
    suspend fun resolveEngineForPlay(): EbookTtsPrefs {
        val tp = prefs.tts.value
        return tp.copy(engine = effectiveEngine())
    }
}
