package com.aurora.music.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.aurora.music.data.remote.LlmClient
import com.aurora.music.model.Song
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class SongIntroPrefs(
    val llmEndpoint: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "",
    val systemPrompt: String = DEFAULT_SONG_INTRO_PROMPT,
    val promptCustomized: Boolean = false,
    val ttsRate: Float = 1.0f,
    val ttsVoice: String = "", // "" = 系统默认中文语音
    val ttsEngine: String = "", // TTS 引擎包名，"" = 系统默认引擎
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
        runCatching { ttsRef?.stop() }
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
        suspend fun ensurePaused() {
            if (!pausedByIntro && wasPlaying) {
                music?.pause()
                pausedByIntro = true
            }
        }
        var lastModel = ""
        var lastInput = ""
        var lastPartial = ""
        try {
            val tts = ensureTts(prefs)
                ?: throw IllegalStateException("系统语音引擎初始化失败")
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
            try {
                for (s in sentences) {
                    ensurePaused()
                    _state.value = SongIntroState(active = true, speaking = true)
                    speakAndWait(tts, s)
                    fullText.append(s)
                    lastPartial = fullText.toString()
                    spoken++
                }
                producer.join()
            } finally {
                producer.cancel()
            }
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
            runCatching { ttsRef?.stop() }
            _state.value = SongIntroState()
            // 用户在播报期间手动暂停过就不再抢播；仍在暂停态才恢复
            if (pausedByIntro && wasPlaying && music?.isPlaying() == false) music?.resume()
        }
    }

    /** 中断本次播报；音乐之前是被介绍暂停的就恢复（由任务收尾统一处理）。 */
    fun cancel() {
        activeSongId = ""
        job?.cancel()
        job = null
        runCatching { ttsRef?.stop() }
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
        val prefs = store.songIntroPrefs.first()
        val tts = ensureTts(prefs) ?: return
        _voices.value = tts.voices.orEmpty()
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

    /** 系统全部 TTS 引擎（需要一个默认实例来查询）。 */
    suspend fun listEngines(): List<TtsEngineInfo> {
        val tts = ensureTts() ?: return emptyList()
        return withContext(Dispatchers.Main) {
            // 通道1：TextToSpeech.getEngines()；通道2：PackageManager 直查 TTS_SERVICE。
            // 有些引擎（如 MultiTTS）在前者里拿不到 label 甚至整条缺失，两边取并集兜底。
            val merged = LinkedHashMap<String, String>()
            runCatching { tts.engines.orEmpty() }.getOrDefault(emptyList())
                .forEach { merged[it.name] = it.label?.takeIf { s -> s.isNotBlank() } ?: it.name }
            runCatching {
                val pm = appContext.packageManager
                val q = pm.queryIntentServices(
                    android.content.Intent("android.intent.action.TTS_SERVICE"), 0
                )
                q.forEach { r ->
                    val pkg = r.serviceInfo?.packageName ?: return@forEach
                    val label = runCatching { r.loadLabel(pm)?.toString() }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: pkg
                    merged.merge(pkg, label) { old, new -> if (old == pkg) new else old }
                }
            }
            val def = runCatching { tts.defaultEngine }.getOrNull()
            android.util.Log.d("SongIntro", "engines=$merged default=$def")
            merged.map { TtsEngineInfo(it.key, it.value) }
                .sortedWith(compareBy<TtsEngineInfo> { it.packageName != def }.thenBy { it.label })
        }
    }

    suspend fun engineLabel(packageName: String): String {
        if (packageName.isBlank()) return "系统默认"
        return listEngines().firstOrNull { it.packageName == packageName }?.label ?: packageName
    }

    /** 切换引擎：存偏好、重建 TTS 实例、刷新该引擎的音色列表。 */
    suspend fun selectEngine(packageName: String): Boolean {
        if (packageName.isNotBlank()) {
            val ok = listEngines().any { it.packageName == packageName }
            if (!ok) {
                _events.emit("该语音引擎不可用")
                return false
            }
        }
        store.setIntroTtsEngine(packageName)
        dropTts()
        refreshVoices()
        return true
    }

    fun testTts(sample: String) {
        if (_state.value.active) {
            scope.launch { _events.emit("介绍播报中，请先停止再试听") }
            return
        }
        scope.launch {
            val prefs = store.songIntroPrefs.first()
            val tts = ensureTts(prefs) ?: run {
                _events.emit("系统语音引擎初始化失败")
                return@launch
            }
            runCatching { tts.stop() }
            tts.speak(sample, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    // ---- TTS ----

    @Volatile private var ttsRef: TextToSpeech? = null
    @Volatile private var ttsEngineUsed: String? = null
    private val utteranceConts = ConcurrentHashMap<String, kotlin.coroutines.Continuation<Unit>>()

    private fun dropTts() {
        runCatching { ttsRef?.stop() }
        runCatching { ttsRef?.shutdown() }
        ttsRef = null
        ttsEngineUsed = null
    }

    private suspend fun ensureTts(prefs: SongIntroPrefs? = null): TextToSpeech? {
        val p = prefs ?: store.songIntroPrefs.first()
        ttsRef?.let {
            if (ttsEngineUsed == p.ttsEngine) {
                applyTtsPrefs(it, p)
                return it
            }
            dropTts()
        }
        val engine = p.ttsEngine.trim().takeIf { it.isNotEmpty() }
        val tts = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var t: TextToSpeech? = null
            t = if (engine != null) TextToSpeech(appContext, { status ->
                if (status == TextToSpeech.SUCCESS && cont.isActive) cont.resume(t)
                else if (cont.isActive) cont.resume(null)
            }, engine) else TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS && cont.isActive) cont.resume(t)
                else if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { runCatching { t?.shutdown() } }
        } ?: return null
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                utteranceConts.remove(utteranceId)?.resume(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceConts.remove(utteranceId)?.resume(Unit)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceConts.remove(utteranceId)?.resume(Unit)
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceConts.remove(utteranceId)?.resume(Unit)
            }
        })
        ttsRef = tts
        ttsEngineUsed = p.ttsEngine
        if (!applyTtsPrefs(tts, p)) {
            // 中文语音缺失不致命：用系统默认语言继续
        }
        return tts
    }

    /** @return 中文语音是否可用 */
    private fun applyTtsPrefs(tts: TextToSpeech, prefs: SongIntroPrefs): Boolean {
        tts.setSpeechRate(prefs.ttsRate.coerceIn(0.5f, 2.0f))
        val saved: Voice? = prefs.ttsVoice.takeIf { it.isNotBlank() }
            ?.let { name -> tts.voices?.firstOrNull { it.name == name } }
        if (saved != null) {
            tts.voice = saved
            return true
        }
        val chinese = tts.voices.orEmpty()
            .filter { it.locale.language == "zh" || it.locale.language == "cmn" }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
            .firstOrNull()
        if (chinese != null) tts.voice = chinese
        val langStatus = tts.setLanguage(chinese?.locale ?: Locale.SIMPLIFIED_CHINESE)
        if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.language = Locale.getDefault()
        }
        return chinese != null
    }

    private suspend fun speakAndWait(tts: TextToSpeech, text: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            val id = UUID.randomUUID().toString()
            utteranceConts[id] = cont
            cont.invokeOnCancellation { utteranceConts.remove(id) }
            val rc = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            if (rc != TextToSpeech.SUCCESS) {
                utteranceConts.remove(id)
                if (cont.isActive) cont.resume(Unit)
            }
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

const val DEFAULT_SONG_INTRO_PROMPT: String = """你是一名专业的古典音乐电台节目主持人。

你的任务是：根据输入的音乐 ID3 信息，为正在播放的古典音乐做一段简短、自然、有知识含量的电台式介绍。

输入是音乐文件的 ID3 字段（键值对），空字段已省略；注释、编码器、网址、路径之类的无关字段已在上游过滤掉，如果仍然看到乱码、乱数或明显无关的内容，请直接忽略，不要复述也不要据此发挥。

一、介绍目标

你不是在写音乐评论，也不是在给这首音乐打分。

你的目标是让听众在音乐播放过程中，获得一些具体、有趣、与这首作品直接相关的知识。

优先介绍：

这是什么作品、属于什么体裁
作曲家以及作品创作时期
作品为什么值得了解，具体原因是什么
作品的结构、乐章或舞曲形式
创作背景或历史背景
如果作品属于某种音乐传统，可以解释它与该传统的关系
如果存在特别值得注意的音乐特点，可以用具体的音乐现象说明
如果这是一个大型作品中的一个乐章或一个舞曲，可以说明它在整个作品中的位置

不要为了“显得有感情”而强行评价音乐。

二、严格避免空泛套话

禁止使用或尽量避免以下类型的表达：

“这是一首充满情感的作品”
“旋律优美动人”
“令人陶醉”
“扣人心弦”
“充满感染力”
“展现了作曲家的深厚功力”
“是一部不可多得的经典之作”
“带给听众美妙的音乐体验”
“让人仿佛置身于……”
“值得细细品味”
“每一次聆听都会有新的感受”
“这首作品具有独特的魅力”

除非能够立即说明具体是什么音乐现象导致这种感觉，否则不要使用这些表达。

例如不要说：

“这首作品充满巴洛克音乐的优雅气质。”

应该说：

“这首组曲沿用了当时常见的舞曲结构，把德国传统与法国舞曲形式结合在一起。”

不要写泛泛的主观感受，要尽可能提供可以验证的音乐知识。

三、不要过度百科化

虽然你应该提供准确的音乐知识，但不要像 Wikipedia 一样罗列资料。

不要连续堆砌：

“创作于1720年，属于巴洛克时期，调性为E小调，BWV编号为810，属于英国组曲第五号……”

而应该把信息组织成自然的主播语言：

“现在播放的是巴赫《第五英国组曲》，E小调，BWV 810。这套组曲大约创作于18世纪初，由一系列舞曲组成。所谓‘英国组曲’，其实并不是因为它专门为英国创作，而是因为它的开头采用了带有英国风格的前奏曲。”

四、像电台主播一样说话

语气应该：

自然
平静
专业但不学究
像一个真正懂古典音乐的人在主持节目
可以有轻微的口语感
不要使用新闻播报式语言
不要使用标题、项目符号或 Markdown
不要说“接下来我将介绍……”
不要重复歌曲标题很多次

可以自然地使用：

“现在听到的是……”
“这首作品有一个很有意思的地方……”
“这里值得注意的是……”
“其实……”
“很多人第一次看到这个名字时可能会……”
“有意思的是……”

但不要每次都用相同的开场方式。

五、根据作品类型调整介绍重点

如果是：

交响曲

作曲背景
作品在作曲家创作生涯中的位置
乐章结构
主题之间的关系
首演或历史背景

协奏曲

独奏乐器与乐队的关系
乐章结构
独奏部分的特点
作曲背景

歌剧

剧情中的位置
人物
咏叹调或场景的作用
文本背景

室内乐

编制
不同乐器之间的关系
作品结构
创作背景

钢琴曲、前奏曲、练习曲、无伴奏作品等

作品集的背景
曲式
技术或音乐结构上的特点
在整个作品集中的位置

组曲、舞曲

舞曲来源
舞曲的基本形式和历史背景
不同舞曲之间的关系

宗教音乐

礼仪或宗教背景
文本来源
编制
创作背景

六、准确性要求

只能陈述你有较高把握的事实。

如果 ID3 信息不足以确定作品，不要猜测。

如果标题存在多个可能的作品对应关系，应优先根据作曲家、BWV、K.、Op.、D.、Hob.、RV 等作品编号判断。

如果仍然无法确定，就只介绍能够确定的信息，不要编造创作年份、首演地点、委托人、故事或轶闻。

尤其不要为了让节目“有故事”而制造所谓的创作背景。

ID3 与你的常识冲突时，以 ID3 中的作曲家与作品编号为准，不要用常识覆盖；绝不编造年份、首演、委托人、轶闻。

七、长度

每次介绍控制在 100～180个汉字左右。

如果作品本身具有非常丰富的背景，可以稍微超过这个长度，但不要写成长篇文章。

这是音乐播放器中的即时介绍，因此应该让听众在几十秒内听完，并且不会妨碍音乐本身。

即使输入信息很少，也只说能确定的内容，不要为了凑长度而发挥。

八、输出

只输出最终的电台主播口播稿。

不要输出：

分析过程
数据来源
ID3 字段解释
Markdown
标题
项目符号
“以下是介绍”
“希望你喜欢”
任何与节目主持无关的说明"""
