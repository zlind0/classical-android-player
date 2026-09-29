package com.aurora.music.tts

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.UnifiedTtsPrefs
import com.aurora.music.playback.EbookTtsPlayer
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** TTS 请求方：听书与歌曲介绍各走各的播放器，合成侧（引擎/缓存/代际）共用。 */
enum class TtsOwner { EBOOK, INTRO }

/** append 一段文本后返回：播完后的实测总时长 + 首块开播时刻（elapsedRealtime 系，供 UI 计时翻页）。 */
data class TtsAppendResult(val totalMs: Long, val startedAtElapsed: Long)

/** 内存缓存键：引擎+音色+语速+音调+有效音量+文本 SHA-256（纯函数，可单测）。 */
fun ttsChunkKey(
    engine: String,
    voice: String,
    rate100: Int,
    pitch100: Int,
    text: String,
    vol100: Int = 100,
): String {
    val md = MessageDigest.getInstance("SHA-256")
    val body = md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    return "${engine.ifBlank { "sysdef" }}|${voice.ifBlank { "auto" }}|r${rate100}|p${pitch100}|v${vol100}|$body"
}

/**
 * 听书音量在合成侧的有效增益（纯函数，可单测）：
 * 设置保留 0.2~2.0 全量；系统引擎 synthesizeToFile 没有音量参数，
 * 只能靠 PCM 后增益，且 >100% 部分砍掉（切回内置即生效）。
 */
fun ttsEffectiveGain(prefs: UnifiedTtsPrefs): Float =
    if (prefs.isInternal) prefs.volume.coerceIn(0.2f, 2f)
    else prefs.volume.coerceIn(0.2f, 2f).coerceAtMost(1f)

/** 系统音色精简描述（脱离 Android Voice 类型，纯函数可单测）。 */
data class VoiceId(val name: String, val language: String, val country: String)

/**
 * 统一选音规则（纯函数，可单测）：显式音色命中即用（只 setVoice，不 setLanguage，
 * 否则部分引擎会把音色重置回语言默认）；自动按 简中→繁中/其它中文→null（调用方跟随系统）。
 */
fun pickSystemVoiceName(voices: List<VoiceId>, requested: String?): String? {
    if (!requested.isNullOrBlank() && voices.any { it.name == requested }) return requested
    return voices.firstOrNull { it.language == "zh" && it.country == "CN" }?.name
        ?: voices.firstOrNull { it.language == "zh" }?.name
}

/** 合成产物内存 LRU（只留当前窗口，32MB ≈ 170 秒 48k 立体声；线程安全，可单测）。 */
class TtsMemCache(private val maxBytes: Long = 32L * 1024 * 1024) {
    private val map = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var bytes: Long = 0

    @Synchronized
    fun get(key: String): ByteArray? = map[key]

    @Synchronized
    fun put(key: String, value: ByteArray): ByteArray {
        val prev = map.remove(key)
        if (prev != null) bytes -= prev.size
        map[key] = value
        bytes += value.size
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            // 刚放入的 key 极大时也允许短暂超限，只淘汰更早的
            if (e.key == key) break
            bytes -= e.value.size
            it.remove()
        }
        return value
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }

    @Synchronized
    fun sizeBytes(): Long = bytes
}

/** 按 owner 单调递增的代际：切段/切歌时旧代即废，过期合成与回调一律丢弃（可单测）。 */
class TtsGenerations {
    private val lock = Any()
    private val cur = mutableMapOf<TtsOwner, Long>()

    fun next(owner: TtsOwner): Long = synchronized(lock) {
        ((cur[owner] ?: 0L) + 1).also { cur[owner] = it }
    }

    fun isCurrent(owner: TtsOwner, gen: Long): Boolean = synchronized(lock) {
        (cur[owner] ?: 0L) == gen
    }
}

/**
 * 统一 TTS Worker（App 作用域单例）：
 * - 合成只产内存字节（48k 立体声 16bit 完整 WAV，含 44 字节头），播完即丢，零磁盘留存；
 * - 内置走 MS 离线（按需单语音释放），系统走 synthesizeToFile（cacheDir 中转、读回即删）；
 * - 流式：首块 WAV 就绪即入播放器队列开播，后续边合边追；
 * - 代际仲裁：同 owner 新流自动废掉旧流（切段/换句），跨 owner 互不干扰（各用各的播放器）。
 */
class TtsWorker(
    context: Context,
    private val settings: SettingsStore,
    private val ebookPlayer: EbookTtsPlayer,
    private val introPlayer: EbookTtsPlayer,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val msEngine = MsEmbeddedEngine(appContext)
    private val cache = TtsMemCache()
    private val gens = TtsGenerations()

    /** 内置语音释放进度：null = 不需要显示；(done, total, name)。两处 UI 共用。 */
    private val _installing = MutableStateFlow<Triple<Int, Int, String>?>(null)
    val installing: StateFlow<Triple<Int, Int, String>?> = _installing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun takeNotice(): String? {
        val v = _notice.value
        _notice.value = null
        return v
    }

    private val sysLocks = mapOf(TtsOwner.EBOOK to Mutex(), TtsOwner.INTRO to Mutex())
    private data class SysSlot(val tts: TextToSpeech, val pkg: String)
    private val sysSlots = mutableMapOf<TtsOwner, SysSlot>()
    private val stopFlags = mapOf(TtsOwner.EBOOK to AtomicBoolean(false), TtsOwner.INTRO to AtomicBoolean(false))
    private val utterConts = ConcurrentHashMap<String, kotlin.coroutines.Continuation<Boolean>>()

    // ---- 播放器路由（owner 各用各的，互不抢占） ----

    private fun playerOf(owner: TtsOwner): EbookTtsPlayer =
        if (owner == TtsOwner.EBOOK) ebookPlayer else introPlayer

    private val playerGen = mutableMapOf<TtsOwner, Long>()
    private val playerEndedGen = mutableMapOf<TtsOwner, Long>()
    private val endWaiters = mutableMapOf<Pair<TtsOwner, Long>, kotlin.coroutines.Continuation<Unit>>()
    private val waitLock = Any()

    init {
        ebookPlayer.onQueueEnded = { onPlayerEnded(TtsOwner.EBOOK) }
        introPlayer.onQueueEnded = { onPlayerEnded(TtsOwner.INTRO) }
    }

    private fun onPlayerEnded(owner: TtsOwner) {
        val g: Long?
        val w: kotlin.coroutines.Continuation<Unit>?
        synchronized(waitLock) {
            g = playerGen[owner]
            playerEndedGen[owner] = g ?: -1L
            w = if (g == null) null else endWaiters.remove(owner to g)
        }
        if (w != null) runCatching { w.resume(Unit) }
    }

    // ---- 对外：流 ----

    /** 开新流：废掉同 owner 旧流并停其播放器。调用方切段/换句/重播时调它，旧流的 append/await 自动失效。 */
    suspend fun openStream(owner: TtsOwner): TtsStream {
        val gen = gens.next(owner)
        stopFlags[owner]?.set(false)
        withContext(Dispatchers.Main) { playerOf(owner).stop() }
        synchronized(waitLock) { playerGen[owner] = gen }
        return TtsStream(owner, gen, this)
    }

    /** 停指定 owner（null = 全停）：代际作废 + 引擎急停 + 播放器停 + 唤醒等待者。任意线程可调。 */
    fun stop(owner: TtsOwner? = null) {
        val targets = if (owner == null) TtsOwner.values().toList() else listOf(owner)
        targets.forEach { o ->
            gens.next(o)
            stopFlags[o]?.set(true)
            sysSlots[o]?.let { runCatching { it.tts.stop() } }
        }
        scope.launch {
            targets.forEach { o ->
                runCatching { playerOf(o).stop() }
                val ws: List<kotlin.coroutines.Continuation<Unit>>
                synchronized(waitLock) {
                    ws = endWaiters.entries.filter { it.key.first == o }.map { it.value }
                    endWaiters.entries.removeIf { it.key.first == o }
                }
                ws.forEach { w -> runCatching { w.resume(Unit) } }
            }
        }
    }

    internal fun isCurrent(owner: TtsOwner, gen: Long): Boolean = gens.isCurrent(owner, gen)

    internal fun playerActiveGen(owner: TtsOwner): Long? = synchronized(waitLock) { playerGen[owner] }

    // ---- 对外：设置/查询（两处设置页共用） ----

    /** 播放前解析有效设置；内置不可用自动降级系统默认并附带提示标记。 */
    suspend fun resolvePrefs(): Pair<UnifiedTtsPrefs, Boolean> {
        val tp = settings.unifiedTts.first()
        if (tp.isInternal && msInternalAvailable()) return tp to false
        if (tp.isInternal) {
            _notice.value = "内置语音不可用，已切换到系统 TTS"
            return tp.copy(engine = "") to true
        }
        return tp to false
    }

    suspend fun listSystemEngines(): List<TtsEngineInfo> = withContext(Dispatchers.IO) {
        com.aurora.music.tts.listSystemEngines(appContext)
    }

    suspend fun systemVoices(enginePkg: String?): List<Voice> = withContext(Dispatchers.IO) {
        systemVoicesOf(appContext, enginePkg?.takeIf { it.isNotBlank() })
            .sortedWith(compareBy({ it.locale.toString() }, { it.name }))
    }

    fun clearMemory() = cache.clear()

    /** 预热内存缓存（下一段后台合成，只写缓存不进播放器）。 */
    suspend fun warmCache(text: String, prefs: UnifiedTtsPrefs, owner: TtsOwner, gen: Long) {
        for (chunk in Chunker.split(text)) {
            if (!isCurrent(owner, gen)) return
            runCatching { synthChunk(chunk, prefs, owner, gen) }.getOrNull() ?: return
        }
    }

    // ---- 流内：合成 + 入队 ----

    internal suspend fun appendInternal(
        owner: TtsOwner,
        gen: Long,
        streamStarted: Boolean,
        text: String,
        prefs: UnifiedTtsPrefs,
    ): Pair<TtsAppendResult, Boolean> {
        val chunks = Chunker.split(text)
        if (chunks.isEmpty()) return (TtsAppendResult(0, SystemClock.elapsedRealtime()) to streamStarted)
        var total = 0L
        var startedAt = 0L
        var started = streamStarted
        chunks.forEachIndexed { i, chunk ->
            coroutineContext.ensureActive()
            if (!isCurrent(owner, gen)) throw CancellationException("tts superseded")
            val wav = synthChunk(chunk, prefs, owner, gen)
                ?: throw CancellationException("tts aborted")
            total += (wav.size - 44).coerceAtLeast(0)
            withContext(Dispatchers.Main) {
                if (!isCurrent(owner, gen)) throw CancellationException("tts superseded")
                val p = playerOf(owner)
                if (!started) {
                    p.startWavStream(wav)
                    started = true
                    startedAt = SystemClock.elapsedRealtime()
                    synchronized(waitLock) { playerGen[owner] = gen }
                } else {
                    p.appendWav(wav)
                }
            }
            if (i == 0 && startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        }
        return (TtsAppendResult((total / 192).coerceAtLeast(0), startedAt) to started)
    }

    internal suspend fun awaitInternal(owner: TtsOwner, gen: Long, enqueued: Boolean) {
        if (!enqueued) return
        if (!isCurrent(owner, gen)) return
        synchronized(waitLock) { if (playerEndedGen[owner] == gen) return }
        suspendCancellableCoroutine<Unit> { cont ->
            synchronized(waitLock) {
                // 二次检查：等待挂载前已播完则直接返回
                if (playerEndedGen[owner] == gen || !isCurrent(owner, gen)) {
                    runCatching { cont.resume(Unit) }
                    return@suspendCancellableCoroutine
                }
                endWaiters[owner to gen] = cont
            }
            cont.invokeOnCancellation {
                synchronized(waitLock) {
                    if (endWaiters[owner to gen] === cont) endWaiters.remove(owner to gen)
                }
            }
        }
    }

    internal suspend fun synthChunk(text: String, prefs: UnifiedTtsPrefs, owner: TtsOwner, gen: Long): ByteArray? {
        if (!isCurrent(owner, gen)) return null
        val gain = ttsEffectiveGain(prefs)
        val key = ttsChunkKey(
            prefs.engine, prefs.voice,
            (prefs.rate * 100).toInt(), (prefs.pitch * 100).toInt(),
            text, (gain * 100).toInt(),
        )
        cache.get(key)?.let { return it }
        val wav = sysLocks[owner]!!.withLock {
            if (!isCurrent(owner, gen)) return@withLock null
            if (prefs.isInternal) synthInternal(text, prefs, owner)
            else synthSystem(text, prefs, owner)
        } ?: return null
        if (!isCurrent(owner, gen)) return null
        return cache.put(key, wav)
    }

    private suspend fun synthInternal(text: String, prefs: UnifiedTtsPrefs, owner: TtsOwner): ByteArray? {
        val voice = MsVoices.byCode(prefs.voice.ifBlank { MsVoices.DEFAULT })
            ?: MsVoices.byCode(MsVoices.DEFAULT)!!
        if (!msEngine.isInstalled(voice.code)) {
            _installing.value = Triple(0, 1, voice.code)
            try {
                msEngine.ensureVoice(voice.code) { done, total, name ->
                    _installing.value = Triple(done, total, name)
                }
            } finally {
                _installing.value = null
            }
        }
        val flag = stopFlags[owner] ?: AtomicBoolean(false)
        val mono24k = msEngine.synthesizeStreaming(
            text, voice,
            prefs.rate.coerceIn(0.5f, 2f),
            prefs.pitch.coerceIn(0.5f, 2f),
            isStopped = { flag.get() },
        )
        if (mono24k.isEmpty() || flag.get()) return null
        var stereo48k = TtsWav.mono24kToStereo48k(mono24k)
        if (stereo48k.isEmpty()) return null
        stereo48k = TtsWav.applyGainStereo16(stereo48k, ttsEffectiveGain(prefs))
        return TtsWav.encodeWav(48000, 2, 16, stereo48k)
    }

    private suspend fun synthSystem(text: String, prefs: UnifiedTtsPrefs, owner: TtsOwner): ByteArray? {
        val tts = ensureSysTts(owner, prefs.systemEnginePkg) ?: throw IllegalStateException("系统 TTS 不可用")
        tts.setSpeechRate(prefs.rate.coerceIn(0.5f, 2f))
        tts.setPitch(prefs.pitch.coerceIn(0.5f, 2f))
        // 显式音色只 setVoice，不随后 setLanguage（后者在部分引擎上会把音色重置回语言默认）
        val picked = pickSystemVoiceName(
            tts.voices.orEmpty().map { VoiceId(it.name, it.locale.language, it.locale.country) },
            prefs.voice,
        )
        if (picked != null) {
            runCatching { tts.voice = tts.voices?.firstOrNull { it.name == picked } }
        } else if (prefs.voice.isBlank()) {
            runCatching { tts.language = Locale.getDefault() }
        }
        val tmp = withContext(Dispatchers.IO) { File.createTempFile("tts", ".wav", appContext.cacheDir) }
        try {
            val id = "${owner.name}-${UUID.randomUUID()}"
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                utterConts[id] = cont
                cont.invokeOnCancellation { utterConts.remove(id) }
                val listener = object : UtteranceProgressListener() {
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) utterConts.remove(id)?.resume(true)
                    }

                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) utterConts.remove(id)?.resume(false)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId == id) utterConts.remove(id)?.resume(false)
                    }

                    override fun onStart(utteranceId: String?) {}
                }
                tts.setOnUtteranceProgressListener(listener)
                val r = tts.synthesizeToFile(text, Bundle(), tmp, id)
                if (r != TextToSpeech.SUCCESS) {
                    utterConts.remove(id)
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation { runCatching { tts.stop() } }
            }
            if (!ok || !tmp.exists() || tmp.length() <= 44) return null
            val bytes = withContext(Dispatchers.IO) { tmp.readBytes() }
            val pcm = runCatching { TtsWav.decodeWav(bytes) }.getOrNull() ?: return null
            if (pcm.data.isEmpty()) return null
            var norm = TtsWav.normalize48kStereo16(pcm)
            if (norm.isEmpty()) return null
            // 系统引擎无音量参数：>100% 部分砍掉（ttsEffectiveGain 已钳位），设置值保留
            norm = TtsWav.applyGainStereo16(norm, ttsEffectiveGain(prefs))
            return TtsWav.encodeWav(48000, 2, 16, norm)
        } finally {
            withContext(Dispatchers.IO) { runCatching { tmp.delete() } }
        }
    }

    /** 同 owner 串行复用的系统 TTS 实例（换引擎重建；"" = 系统默认引擎）。 */
    private suspend fun ensureSysTts(owner: TtsOwner, pkg: String?): TextToSpeech? {
        val key = pkg ?: ""
        sysSlots[owner]?.takeIf { it.pkg == key }?.let { return it.tts }
        sysSlots[owner]?.let { runCatching { it.tts.shutdown() } }
        sysSlots.remove(owner)
        val tts = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var t: TextToSpeech? = null
            t = if (key.isNotBlank()) {
                TextToSpeech(appContext, { status ->
                    if (status == TextToSpeech.SUCCESS && cont.isActive) cont.resume(t)
                    else if (cont.isActive) cont.resume(null)
                }, key)
            } else {
                TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS && cont.isActive) cont.resume(t)
                    else if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { runCatching { t?.shutdown() } }
        } ?: return null
        sysSlots[owner] = SysSlot(tts, key)
        return tts
    }
}

/**
 * 同 owner 的一次播报流：首个 append 首块即开播，后续追块；
 * [append] 只等合成+入队（不等播完，播与合流水），[awaitDone] 等播完。
 * 切段/换句时调用方丢掉旧流、[TtsWorker.openStream] 开新流，旧流自动失效。
 */
class TtsStream internal constructor(
    private val owner: TtsOwner,
    private val gen: Long,
    private val worker: TtsWorker,
) {
    private var started = false
    private var enqueued = false

    /** 本流代际：后台预热等附带任务凭它判断是否过期。 */
    val generation: Long get() = gen

    suspend fun append(text: String, prefs: UnifiedTtsPrefs): TtsAppendResult {
        val (res, st) = worker.appendInternal(owner, gen, started, text, prefs)
        started = st
        if (res.totalMs > 0) enqueued = true
        return res
    }

    suspend fun awaitDone() = worker.awaitInternal(owner, gen, enqueued)

    fun isAlive(): Boolean = worker.isCurrent(owner, gen)
}
