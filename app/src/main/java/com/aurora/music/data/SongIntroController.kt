package com.aurora.music.data

import android.content.Context
import com.aurora.music.data.remote.LlmClient
import com.aurora.music.model.Song
import com.aurora.music.tts.MsVoices
import com.aurora.music.tts.TtsOwner
import com.aurora.music.tts.TtsWorker
import com.aurora.music.tts.isSpeakable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class SongIntroPrefs(
    val llmEndpoint: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "",
    val systemPrompt: String = DEFAULT_SONG_INTRO_PROMPT,
    val promptCustomized: Boolean = false,
    val ttsRate: Float = 1.0f,
    val ttsVoice: String = "", // "" = 自动（内置默认晓晓，系统优先中文）
    val ttsEngine: String = "", // TTS 引擎："internal" = 内置微软离线，"" = 系统默认引擎，否则为引擎包名
    val ttsPitch: Float = 1.0f,
)

data class SongIntroState(
    val active: Boolean = false,   // LLM 请求中或 TTS 朗读中（? 显示为停止键）
    val speaking: Boolean = false, // 已有声音在播
)

data class VoiceInfo(
    val name: String,
    val label: String,
)

/** 播放器接线：控制器只经此接口暂停/恢复音乐，不直接碰 MediaController。 */
interface IntroMusicControl {
    fun isPlaying(): Boolean
    fun pause()
    fun resume()

    /**
     * 接收集成播放器时压低音频而不是暂停。返回 true 表示本次介绍期间由实现接管 duck，
     * 结束时由 [unduckAfterIntro] 恢复；返回 false 走普通 pause/resume。
     */
    fun duckForIntro(): Boolean = false

    fun unduckAfterIntro() {}
}

class SongIntroController(
    context: Context,
    private val store: SettingsStore,
    private val log: SongIntroLogStore? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val llm = LlmClient()

    private val _state = MutableStateFlow(SongIntroState())
    val state: StateFlow<SongIntroState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices.asStateFlow()

    /** 内置语音释放进度：委托 worker，两处 UI 共用同一份。 */
    val installing: StateFlow<Triple<Int, Int, String>?> get() = tts.installing

    /** 统一合成与播放入口（AppContainer 注入）。 */
    lateinit var tts: TtsWorker

    var music: IntroMusicControl? = null

    private var job: Job? = null
    private var activeSongId: String = ""

    // ---- public API ----

    fun toggle(song: Song) {
        if (_state.value.active) cancel() else start(song)
    }

    fun start(song: Song) {
        if (song.id.isBlank()) return
        // 先清掉上一次残留，再开新任务：cancelAndJoin 保证旧任务收尾（含恢复音乐）
        // 完成之后，新任务采样的 wasPlaying 才是准的
        val prev = job
        job = null
        activeSongId = ""
        runCatching { tts.stop(TtsOwner.INTRO) }
        if (_state.value.active) _state.value = SongIntroState()
        job = scope.launch {
            prev?.cancelAndJoin()
            runIntro(song)
        }
    }

    private suspend fun CoroutineScope.runIntro(song: Song) {
        val prefs = store.songIntroPrefs.first()
        if (prefs.llmEndpoint.isBlank() || prefs.llmModel.isBlank()) {
            _events.emit("请先到 设置 → 歌曲介绍 填写接口地址与模型")
            return
        }
        activeSongId = song.id
        _state.value = SongIntroState(active = true, speaking = false)
        val wasPlaying = music?.isPlaying() == true
        var pausedByIntro = false
        var duckedByIntro = false
        suspend fun ensureLowered() {
            if (pausedByIntro || duckedByIntro || !wasPlaying) return
            if (music?.duckForIntro() == true) {
                duckedByIntro = true
            } else {
                music?.pause()
                pausedByIntro = true
            }
        }
        var lastModel = ""
        var lastInput = ""
        var lastPartial = ""
        try {
            // TTS 四项走统一设置（降级提示由 worker 经 notice 透出）；LLM 三件套仍走 songIntroPrefs
            val (ttsPrefs, _) = tts.resolvePrefs()
            val userText = buildUserText(song)
            lastModel = prefs.llmModel
            lastInput = userText
            val buf = StringBuilder()
            val sentences = Channel<String>(Channel.UNLIMITED)
            // producer 绝不对外抛错（取消除外）：网络异常转存，由消费者统一报提示；
            // 否则 async 的失败会绕过 await 直接按 uncaught 打死进程
            val streamError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val producer = async(Dispatchers.IO) {
                try {
                    llm.chatStream(prefs.llmEndpoint, prefs.llmApiKey, prefs.llmModel, prefs.systemPrompt, userText) { delta ->
                        ensureActive()
                        buf.append(delta)
                        drainSentences(buf, sentences)
                        true
                    }
                    val tail = buf.toString().trim()
                    if (tail.isNotEmpty()) sentences.trySend(sanitize(tail))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    streamError.set(t)
                } finally {
                    buf.clear()
                    sentences.close()
                }
            }
            var spoken = 0
            val fullText = StringBuilder()
            // 单流多句：append 只等合成+入队（首句首块即开播），句与句在播放器队列里无缝衔接；
            // LLM 供句抖动由队列吸收，不再每句重建合成器/AudioTrack
            val stream = tts.openStream(TtsOwner.INTRO)
            try {
                try {
                    for (s in sentences) {
                        ensureLowered()
                        _state.value = SongIntroState(active = true, speaking = true)
                        fullText.append(s)
                        lastPartial = fullText.toString()
                        // 纯标点句（如单个 "。"）直接跳过，不进合成器（硬合产不出音频）。
                        if (!isSpeakable(s)) continue
                        val res = stream.append(s, ttsPrefs)
                        if (res.totalMs > 0) spoken++
                    }
                    producer.join()
                } finally {
                    producer.cancel()
                }
                stream.awaitDone()
                if (!stream.isAlive()) throw CancellationException()
                streamError.get()?.let { throw it }
                if (spoken == 0 && streamError.get() == null) _events.emit("没能生成介绍，请稍后重试")
                runCatching {
                    log?.add(
                        SongIntroLogEntry(
                            timeMs = System.currentTimeMillis(),
                            model = prefs.llmModel,
                            input = userText,
                            result = fullText.toString(),
                        )
                    )
                }
            } finally {
                // 释放 TTS 串行闸门：intro 与听书共用一条队列，永不同时发声。
                stream.close()
            }
        } catch (ce: CancellationException) {
            // 中断也留一条部分记录，方便回看说了哪几句
            runCatching {
                log?.add(
                    SongIntroLogEntry(
                        timeMs = System.currentTimeMillis(),
                        model = lastModel,
                        input = lastInput,
                        result = lastPartial,
                        interrupted = true,
                    )
                )
            }
            throw ce
        } catch (t: Throwable) {
            val msg = t.message?.takeIf { it.isNotBlank() }
            runCatching {
                log?.add(
                    SongIntroLogEntry(
                        timeMs = System.currentTimeMillis(),
                        model = lastModel,
                        input = lastInput,
                        result = lastPartial,
                        error = msg ?: "请检查网络与接口设置",
                    )
                )
            }
            _events.emit(if (msg != null) "介绍失败：$msg" else "介绍失败，请检查网络与接口设置")
        } finally {
            _state.value = SongIntroState()
            // 用户在播报期间手动暂停过就不再抢播；仍在暂停态才恢复
            if (duckedByIntro) {
                music?.unduckAfterIntro()
            } else if (pausedByIntro && wasPlaying && music?.isPlaying() == false) {
                music?.resume()
            }
        }
    }

    /** 中断本次播报；音乐之前是被介绍暂停的就恢复（由任务收尾统一处理）。 */
    fun cancel() {
        activeSongId = ""
        job?.cancel()
        job = null
        runCatching { tts.stop(TtsOwner.INTRO) }
        if (_state.value.active) _state.value = SongIntroState()
    }

    /** 切歌时中断本次播报；之前暂停的音乐会被任务收尾恢复，让新歌继续播。 */
    fun onSongChanged(songId: String) {
        if (!_state.value.active) return
        if (songId.isNotBlank() && songId == activeSongId) return
        cancel()
    }

    suspend fun fetchModels(): Result<List<String>> {
        val p = store.songIntroPrefs.first()
        if (p.llmEndpoint.isBlank()) return Result.failure(IllegalStateException("请先填写接口地址"))
        return llm.models(p.llmEndpoint, p.llmApiKey)
    }

    suspend fun testConnection(): Result<String> {
        val p = store.songIntroPrefs.first()
        if (p.llmEndpoint.isBlank() || p.llmModel.isBlank()) {
            return Result.failure(IllegalStateException("请先填写接口地址与模型"))
        }
        return llm.ping(p.llmEndpoint, p.llmApiKey, p.llmModel)
    }

    suspend fun refreshVoices() {
        val tp = store.unifiedTts.first()
        if (tp.isInternal) {
            _voices.value = MsVoices.ALL.map { v -> VoiceInfo(name = v.code, label = v.label()) }
            return
        }
        _voices.value = tts.systemVoices(tp.systemEnginePkg)
            .map { v ->
                val loc = v.locale
                val zh = loc.language == "zh" || loc.language == "cmn" || loc.language == "yue"
                VoiceInfo(
                    name = v.name,
                    label = "${v.name} · ${loc.getDisplayName(Locale.SIMPLIFIED_CHINESE)}${if (v.isNetworkConnectionRequired) " · 需联网" else ""}",
                ) to zh
            }
            .sortedWith(compareByDescending<Pair<VoiceInfo, Boolean>> { it.second }.thenBy { it.first.label })
            .map { it.first }
    }

    data class TtsEngineInfo(val packageName: String, val label: String)

    /** 系统全部 TTS 引擎（与电子书同款，默认排第一；worker 单例复用实例）。 */
    suspend fun listEngines(): List<TtsEngineInfo> =
        withContext(Dispatchers.IO) {
            tts.listSystemEngines()
                .map { TtsEngineInfo(it.packageName, it.label) }
        }

    suspend fun engineLabel(packageName: String): String {
        if (packageName.isBlank()) return "系统默认"
        return listEngines().firstOrNull { it.packageName == packageName }?.label ?: packageName
    }

    /** 切换引擎：存偏好、刷新该引擎的音色列表（实例由 worker 按需重建）。 */
    suspend fun selectEngine(packageName: String): Boolean {
        if (packageName == TTS_ENGINE_INTERNAL) {
            store.setIntroTtsEngine(packageName)
            refreshVoices()
            return true
        }
        if (packageName.isNotBlank()) {
            val ok = listEngines().any { it.packageName == packageName }
            if (!ok) {
                _events.emit("该语音引擎不可用")
                return false
            }
        }
        store.setIntroTtsEngine(packageName)
        refreshVoices()
        return true
    }

    fun testTts(sample: String) {
        if (_state.value.active) {
            scope.launch { _events.emit("介绍播报中，请先停止再试听") }
            return
        }
        scope.launch {
            runCatching {
                val (tp, _) = tts.resolvePrefs()
                val s = tts.openStream(TtsOwner.INTRO)
                s.append(sample, tp)
                s.awaitDone()
            }.onFailure { _events.emit("试听失败：${it.message}") }
        }
    }

    // ---- 文本 ----

    private fun buildUserText(song: Song): String {
        val lines = ArrayList<String>()
        fun add(k: String, v: String) {
            val t = v.trim()
            if (t.isNotEmpty()) lines.add("$k：$t")
        }
        add("标题", song.title)
        add("艺术家", song.artist)
        add("专辑", song.album)
        add("作曲家", song.composer)
        add("流派", song.genre)
        if (song.trackNumber > 0) lines.add("音轨：${song.trackNumber}")
        if (song.discNumber > 0) lines.add("碟号：${song.discNumber}")
        if (song.durationSec > 0) lines.add("时长：${song.durationSec}秒")
        return lines.joinToString("\n")
    }

    private fun sanitize(s: String): String {
        var r = s.trim()
        r = r.replace("*", "").replace("#", "")
        r = r.lines().joinToString("\n") { it.trimStart('-', ' ', '·').trimEnd() }.trim()
        return r
    }

    // 强标点切句；无标点攒到 60 字按逗号/硬切兜底，保证首句低延迟开播
    private fun drainSentences(buf: StringBuilder, out: Channel<String>) {
        var start = 0
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '；' || c == ';' || c == '\n') {
                var j = i + 1
                while (j < buf.length && (buf[j] == '”' || buf[j] == '"' || buf[j] == '\'' || buf[j] == '’' || buf[j] == '♪' || buf[j] == ' ')) j++
                val s = sanitize(buf.substring(start, j))
                if (s.isNotEmpty()) out.trySend(s)
                start = j
                i = j
            } else {
                i++
            }
        }
        if (start > 0) buf.delete(0, start)
        if (buf.length >= 60) {
            val cut = (buf.lastIndexOf('，').takeIf { it >= 20 }
                ?: buf.lastIndexOf(',').takeIf { it >= 20 }
                ?: buf.lastIndexOf('、').takeIf { it >= 20 }
                ?: 60).coerceAtMost(buf.length)
            val s = sanitize(buf.substring(0, cut))
            if (s.isNotEmpty()) out.trySend(s)
            buf.delete(0, cut)
        }
    }
}

const val DEFAULT_SONG_INTRO_PROMPT: String = """你是一位专业古典音乐电台的撰稿人。用户会提供一段古典音乐的元数据，你需要写一段直接用于语音合成（TTS）朗读的导赏词。

### 核心内容原则
最开始用中文讲一下这首曲子是谁的什么作品的什么乐章。
首先，在开始可以花一些篇幅交代作者的生活背景和曲子的创作背景。然后着力讲解这首音乐。
1. 严禁空洞修饰：禁止使用“情感丰富”、“优美动听”、“史诗般”、“大师级”等套话。任何听感描述必须落实到具体的乐器、动机、和声或配器变化上。
2. 听觉导向：只讲听众在接下来的播放中能直接听出来的声音，如果作曲家在这首曲子里用声音象征具体事物，也可以提及，但是大多数曲子或许是不存在这种象征的。
3. 严格防幻觉：元数据可能残缺（如缺少乐团或年份，或出现明显无意义的内容）。绝不编造未提供的乐团名和录音年份。不介绍指挥家和乐团。
4. 如果确实不了解，可以缩短字数，简单介绍。
5. 避免“不是，而是”的句型，避免否定的描述，除非你要否定的是大家的常识。

### TTS 语音适配硬性规范（必须严格遵守）
1. 纯文本段落：绝对禁止使用任何 Markdown 标记、标题、星号、项目符号、数字编号或括号。
2. 消除生硬外文与缩写：将作品号、速度标记、时长全部转化为自然中文口语。例如：不要写“Op. 35”，要写“作品第三十五号”；不要写“Largo e maestoso”，要直接意译为“庄严的广板”；不要写“59秒”，要换算为“将近一分钟”，一般情况下，不用说曲子有多长。
3. 播音节奏：多用短句和自然过渡词（如“开篇先由”、“紧接着”、“进入主体部分后”、“值得注意的是”），避免书面语的长定语，确保朗读时有自然的呼吸停顿。
4. 字数控制：严格控制在 150 到 300 字之间（约 1 分钟朗读时长），直接输出正文，不要有任何开场白或结束语。"""
