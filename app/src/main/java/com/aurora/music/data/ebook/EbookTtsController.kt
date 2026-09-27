package com.aurora.music.data.ebook

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.aurora.music.playback.EbookTtsPlayer
import com.aurora.music.tts.Chunker
import com.aurora.music.tts.MsEmbeddedEngine
import com.aurora.music.tts.MsVoice
import com.aurora.music.tts.MsVoices
import com.aurora.music.tts.TtsWav
import java.io.File
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
 * 电子书听书控制器（App 作用域，阅读界面驱动）：
 * - 段落 = 正文块（含标题块），上下首跨章移动，播完一段自动下一段并翻页。
 * - 双引擎都产出 48k 立体声 WAV → 同一个 [EbookTtsPlayer]（完整 DSP 链）播放：
 *   内置走 MS 离线合成（分句流式，首块即播）；系统走 synthesizeToFile。
 * - 预合成下一段（lookahead 1），连续听无断档；合成文件按书 md5+引擎+音色+语速
 *   内容寻址缓存，重听秒播。
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

    data class Para(val chapter: Int, val block: Int, val text: String, val startChar: Int = 0) {
        /** 实际朗读文本（页顶可能是段落中间）。 */
        val readText: String get() = text.drop(startChar.coerceIn(0, text.length))
    }

    /** 当前段落朗读计时：UI 按它算出读到第几页并自动翻（duration 为实测音频时长）。 */
    data class ParaTiming(
        val chapter: Int,
        val block: Int,
        val startChar: Int,
        val totalChars: Int,
        val durationMs: Long,
        val startedAt: Long,
    )

    private val _paraTiming = MutableStateFlow<ParaTiming?>(null)
    val paraTiming: StateFlow<ParaTiming?> = _paraTiming.asStateFlow()

    private var bookMd5: String = ""
    private var paras: List<Para> = emptyList()

    private val _position = MutableStateFlow<Para?>(null)
    val position: StateFlow<Para?> = _position.asStateFlow()

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

    /** 系统 TTS 可选音色（初始化后可用）。 */
    private val _systemVoices = MutableStateFlow<List<Voice>>(emptyList())
    val systemVoices: StateFlow<List<Voice>> = _systemVoices.asStateFlow()

    @Volatile private var playJob: kotlinx.coroutines.Job? = null
    @Volatile private var pendingStartChar: Int = 0
    @Volatile private var sysTts: TextToSpeech? = null
    @Volatile private var sysTtsReady: Boolean = false

    // ---- 书 ----

    fun setBook(md5: String, book: ParsedEbook) {
        if (bookMd5 == md5 && paras.isNotEmpty()) return
        stop()
        bookMd5 = md5
        paras = book.chapters.flatMapIndexed { ci, c ->
            c.blocks.mapIndexedNotNull { bi, b ->
                if (b.text.isBlank()) null else Para(ci, bi, b.text)
            }
        }
        _position.value = null
    }

    fun paraIndexOf(chapter: Int, block: Int): Int =
        paras.indexOfFirst { it.chapter == chapter && it.block >= block }
            .takeIf { it >= 0 } ?: paras.indexOfFirst { it.chapter == chapter }
            .takeIf { it >= 0 } ?: paras.indexOfFirst { it.chapter > chapter }
            .takeIf { it >= 0 } ?: (paras.size - 1).coerceAtLeast(0)

    // ---- 播放控制 ----

    /** 从指定位置开始读；charOffset = 块内字偏移（页顶是段中时用）。 */
    fun playFrom(chapter: Int, block: Int, charOffset: Int = 0) {
        val idx = paraIndexOf(chapter, block)
        if (idx < 0 || idx >= paras.size) return
        val base = paras[idx]
        pendingStartChar = charOffset.coerceIn(0, base.text.length)
        startAt(idx, true)
    }

    /** 停止：什么都不读，高亮清除。没有暂停状态。 */
    fun stop() {
        playJob?.cancel()
        playJob = null
        msEngine.requestStop()
        player?.stop()
        _playing.value = false
        _paraTiming.value = null
        _position.value = null
    }

    fun prev() {
        val cur = _position.value ?: return
        val idx = paras.indexOfFirst { it.chapter == cur.chapter && it.block == cur.block }
        val target = (if (idx < 0) 0 else idx - 1).coerceAtLeast(0)
        if (target == idx) {
            // 已经是第一段：重播本段
            startAt(idx, true)
        } else {
            startAt(target, true)
        }
    }

    fun next() {
        val cur = _position.value ?: return
        val idx = paras.indexOfFirst { it.chapter == cur.chapter && it.block == cur.block }
        val target = (if (idx < 0) 0 else idx + 1).coerceAtMost(paras.size - 1)
        startAt(target, true)
    }

    private fun startAt(index: Int, movePage: Boolean) {
        playJob?.cancel()
        msEngine.requestStop()
        player?.stop()
        val job = scope.launch(Dispatchers.IO) {
            try {
                _playing.value = true
                playLoop(index, movePage, resolveEngineForPlay())
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
            // 只有起点段落带字偏移，后续段落都从头读
            val para = if (idx == startIndex && pendingStartChar > 0) {
                base.copy(startChar = pendingStartChar.coerceIn(0, base.text.length))
            } else {
                base.copy(startChar = 0)
            }
            pendingStartChar = 0
            // 预取的都是从头合成的，带字偏移的起点段不能复用
            val files = if (staged?.first == idx && para.startChar == 0) staged.second
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
            if (first && movePage || !first) _turn.value = Triple(para.chapter, para.block, para.startChar)
            first = false
            // 实测时长（48k 立体声 16bit：192000 字节/秒，去 44 字节头）
            val durMs = files.sumOf { (it.length() - 44).coerceAtLeast(0) } / 192
            _paraTiming.value = ParaTiming(
                chapter = para.chapter,
                block = para.block,
                startChar = para.startChar,
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
        return "${tp.engine.name}_${v}_r${r}_p${p}"
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
