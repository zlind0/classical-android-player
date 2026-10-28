package com.aurora.music.data.ebook

import android.content.Context
import android.net.Uri
import com.aurora.music.R
import com.aurora.music.data.TrackArtworkCache
import com.aurora.music.model.Song
import com.google.gson.Gson
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 电子书投屏：手机当遥控器，网页 / HDMI 显示整本书的“大屏排版”。
// - 网页服务从程序启动就是常驻的：离开阅读页、结束投屏都不会关，网页永远在线。
//   没有打开书时网页显示“音乐播放界面”（大封面 + 当前清单），有书时显示阅读画面。
// - 进度锚点统一用“章:段”（EbookAnchorCode）。两套驱动方向：
//   · 遥控页打开（state.remote = true）：遥控端翻页 → 大屏翻页 → 大屏把左上角第一个字
//     的 (章, 段, 字) 报回手机，手机跳到该字所在页并落盘（大屏驱动手机）；
//   · 遥控页没开：手机阅读进度是唯一真相，每次翻页把大屏定位到同一“章:段:字”
//     （手机驱动大屏），大屏的上报一律忽略。
// - 手机起一个极简只读 HTTP 服务（原生 ServerSocket，无第三方依赖）；/state 拉设置与指令，
//   /chapter 按章取正文，/queue 取当前播放清单，/font 供阅读字体，
//   /audio 流式音乐（Range 可拖），/art 封面。
// - 是否由网页出声由播放页音量条右侧的 airplay 键决定（+ 当前有没有网页连接）。
// - Presentation 模式复用同一网页，通过 Android Presentation 输出到 HDMI 副屏。

enum class EbookCastMode { OFF, WEB, PRESENTATION }

/** 投屏进度锚点：章/段/段内字（与手机阅读进度同一套下标，显示时用 EbookAnchorCode）。 */
data class EbookCastAnchor(val chapter: Int, val block: Int, val char: Int = 0)

data class EbookCastState(
    val mode: EbookCastMode = EbookCastMode.OFF,
    val starting: Boolean = false,
    /** 手机遥控页/网页提示用的地址；Presentation 模式是 127.0.0.1。 */
    val url: String = "",
    /** 局域网地址（网页端用），HDMI 模式可能为空。 */
    val lanUrl: String = "",
    /** Presentation 模式的目标显示器名称。 */
    val displayName: String = "",
    val error: String = "",
    val bookTitle: String = "",
    val chapterTitle: String = "",
    /** 当前大屏/手机同步到的锚点（遥控页显示“章:段”）。 */
    val anchor: EbookCastAnchor? = null,
    /** 手机遥控页累计的翻页指令；大屏端按差值翻同样多的页，避免连点丢指令。 */
    val turnOffset: Int = 0,
    /** 网页服务是否常驻可用（启动即开，不随阅读页/投屏状态关闭）。 */
    val webRunning: Boolean = false,
    /** 是否已有网页（或 HDMI 内嵌网页）在轮询：播放页据此显示 airplay 键。 */
    val clientsConnected: Boolean = false,
    /** 遥控页是否打开：打开 = 遥控端驱动大屏进度；关闭 = 手机阅读进度驱动大屏。 */
    val remote: Boolean = false,
)

data class EbookCastMusic(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val hasAudio: Boolean = false,
    val hasArt: Boolean = false,
)

/** 当前播放清单里的一首歌（网页端“当前清单”只读展示）。 */
data class EbookCastTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

private data class WireFocus(val chapter: Int, val block: Int, val char: Int, val gen: Long)

private data class WireQueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

private data class WireQueue(val gen: Int, val tracks: List<WireQueueTrack>)

private data class WireMusic(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val hasArt: Boolean,
)

private data class WireState(
    val bookTitle: String,
    val bookAuthor: String,
    val chapterCount: Int,
    val focus: WireFocus?,
    val turnOffset: Int,
    val theme: String,
    val fontSizePx: Int,
    val maxColumnWidthPx: Int,
    /** 大屏是否显示音乐播放条。 */
    val showMusic: Boolean,
    /** 手机端 seek 序号：变化时网页把音频跳到 positionMs。 */
    val seekGen: Int,
    /** 翻页一次滚动的距离："SCREEN" 一屏 / "COLUMN" 一栏。 */
    val turnUnit: String,
    /** 阅读字体地址；空 = 系统字体 / 跟随阅读字体。 */
    val fontUrl: String,
    /** 是否由网页出声（airplay 键打开 + 有网页连接 + 有本地音频可播）。 */
    val audio: Boolean,
    /** 网页出声的前置放大（0..1）：手机播放页的音量条在投屏出声时控制它。 */
    val preamp: Float,
    val music: WireMusic,
    /** 是否打开了书：未打开时网页显示音乐播放界面（大封面 + 当前清单）。 */
    val hasBook: Boolean,
    /** 当前清单序号：变化时网页重新拉 /queue。 */
    val queueGen: Int,
    val queueIndex: Int,
    val queueLength: Int,
)

private data class WireBlock(val level: Int, val text: String)

private data class WireChapter(val index: Int, val title: String, val blocks: List<WireBlock>)

class EbookCastController(
    context: Context,
    private val prefs: EbookPrefs,
    /**
     * 投屏画面出声时静音手机本地音乐（真正的声音从网页 / HDMI 设备出来），
     * 关掉后手机自己播；停止投屏一律恢复。
     */
    private val onWebAudioMuted: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val _state = MutableStateFlow(EbookCastState())
    val state: StateFlow<EbookCastState> = _state.asStateFlow()

    /**
     * 大屏端上报的进度锚点：阅读页收集后跳页并落盘。
     * 采集端要等整章分页，大屏连翻时缓冲会满；必须丢最旧的、保最新的一条，
     * 否则手机进度会停在更早的页（投屏结束回到阅读页就退回旧位置）。
     */
    private val _remoteAnchors = MutableSharedFlow<EbookCastAnchor>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val remoteAnchors: SharedFlow<EbookCastAnchor> = _remoteAnchors.asSharedFlow()

    private val serverLock = Any()
    @Volatile private var server: EbookCastHttpServer? = null
    /** 最近一次拿到的局域网地址（网页地址）；网络切换后由 serveLoop 刷新。 */
    @Volatile private var webLanUrl: String = ""
    @Volatile private var book: ParsedEbook? = null
    /** 手机阅读页当前页首（章/段/字），开投时作为大屏的初始定位。 */
    @Volatile private var readerAnchor: EbookCastAnchor? = null
    /** 大屏端当前锚点；网页刷新/重新打开时从它恢复。不在遥控页时跟随手机阅读进度。 */
    @Volatile private var focus: EbookCastAnchor? = null
    @Volatile private var focusGen: Long = 0
    @Volatile private var turnOffset: Int = 0
    /** 遥控页是否打开（决定进度由谁驱动，见文件头注释）。 */
    @Volatile private var remote: Boolean = false
    /** 最近一次收到网页请求的时刻（elapsedRealtime）；轮询间断超过阈值即视为已断开。 */
    @Volatile private var lastClientAt: Long = 0L
    @Volatile private var clientsConnected: Boolean = false
    /** 当前是否真的由网页出声（airplay 键 + 有网页连接 + 有本地音频）。 */
    @Volatile private var webAudioActive: Boolean = false

    // 当前播放清单（网页端“当前清单”）：id 列表没变就不重发，只更新序号
    @Volatile private var queue: List<EbookCastTrack> = emptyList()
    @Volatile private var queueIds: List<String> = emptyList()
    /** 上次喂进来的队列列表实例：同一实例（只是进度 tick）直接跳过。 */
    @Volatile private var queueSource: List<Song>? = null
    @Volatile private var queueIndex: Int = 0
    @Volatile private var queueGen: Int = 0
    @Volatile private var music = EbookCastMusic()
    /** 手机端拖动进度条的次数：网页端只在它变化时跳转，不会被播放漂移带着乱跳。 */
    @Volatile private var seekGen: Int = 0
    @Volatile private var lastPosSec: Float = -1f
    @Volatile private var lastPosAt: Long = 0L
    @Volatile private var lastPosSongId: String = ""
    /** 最近一次音乐快照的时刻（elapsedRealtime）：/state 据此把进度外推到“现在”。 */
    @Volatile private var musicAt: Long = 0L
    @Volatile private var trackPath: String? = null
    /** 上次解析过本地路径的歌曲 id（同曲不重复 stat）。 */
    @Volatile private var lastPathSongId: String = ""
    @Volatile private var artUri: String? = null
    @Volatile private var artTrackId: String = ""
    /** App 默认封面（PNG 编码）缓存。 */
    @Volatile private var defaultArtBytes: ByteArray? = null
    @Volatile private var displayPrefs = EbookCastDisplayPrefs()
    @Volatile private var templateBytes: ByteArray? = null
    @Volatile private var fontBytes: ByteArray? = null
    @Volatile private var fontCacheKey: String = ""
    /** start/stop 竞态保护：stop 或后一次 start 会让旧协程的启动结果作废。 */
    private val generation = AtomicLong(0)

    init {
        scope.launch {
            prefs.castPrefs.collect {
                displayPrefs = it
                // 播放页 / 遥控端切换“音乐在投屏设备播 / 手机播”后立刻生效
                syncAudioRoute()
            }
        }
        // 网页服务常驻：程序启动即开，没有局域网时等网络回来再开
        scope.launch { serveLoop() }
        scope.launch { clientWatcher() }
    }

    /** 阅读页打开时注入整本正文，供大屏按章取内容；关闭时清空 → 网页回到音乐界面。 */
    fun setBook(book: ParsedEbook?) {
        this.book = book
        if (book == null) {
            readerAnchor = null
            focus = null
            // gen +1：网页不会再守着上一本书的锚点，切到音乐界面
            focusGen += 1
            turnOffset = 0
            _state.value = _state.value.copy(
                bookTitle = "",
                chapterTitle = "",
                anchor = null,
                turnOffset = 0,
            )
        } else {
            _state.value = _state.value.copy(bookTitle = book.title)
        }
    }

    /**
     * 遥控页打开/关闭。打开时把大屏定位到手机当前页，之后由遥控翻页驱动；
     * 关闭时大屏重新跟随手机阅读进度。
     */
    fun setRemote(on: Boolean) {
        if (remote == on) return
        remote = on
        val a = readerAnchor ?: focus
        focus = a
        focusGen += 1
        turnOffset = 0
        _state.value = _state.value.copy(
            remote = on,
            anchor = a,
            chapterTitle = a?.let { chapterTitleOf(it.chapter) }.orEmpty(),
            turnOffset = 0,
        )
    }

    /**
     * 手机阅读页当前页首锚点。不在遥控页时它就是唯一真相：
     * 每次翻页都把大屏定位到同一“章:段:字”，网页进度 = 手机阅读进度。
     */
    fun setReaderAnchor(anchor: EbookCastAnchor) {
        readerAnchor = anchor
        // 遥控页开着时由遥控端驱动，不拿手机翻页打断大屏
        if (remote) return
        if (focus == anchor) return
        focus = anchor
        focusGen += 1
        turnOffset = 0
        _state.value = _state.value.copy(
            anchor = anchor,
            chapterTitle = chapterTitleOf(anchor.chapter),
            turnOffset = 0,
        )
    }

    /** 遥控翻页：只累计指令，由大屏端按差值执行（连点不丢页）。 */
    fun turn(delta: Int) {
        if (delta == 0 || !remote) return
        turnOffset += delta
        _state.value = _state.value.copy(turnOffset = turnOffset)
    }

    /**
     * 遥控端从「进度历史」恢复位置：把大屏重新定位到该“章:段”（focus gen +1，
     * 网页看到新 gen 会跳过去），并让手机阅读页跟着跳到位、按同一锚点落盘。
     */
    fun restoreAnchor(anchor: EbookCastAnchor) {
        if (!remote) return
        val b = book ?: return
        if (anchor.chapter !in b.chapters.indices) return
        val blocks = b.chapters[anchor.chapter].blocks
        if (blocks.isEmpty()) return
        val safeBlock = anchor.block.coerceIn(0, blocks.size - 1)
        val safeChar = anchor.char.coerceIn(0, blocks[safeBlock].text.length)
        val a = EbookCastAnchor(anchor.chapter, safeBlock, safeChar)
        focus = a
        focusGen += 1
        _state.value = _state.value.copy(
            anchor = a,
            chapterTitle = chapterTitleOf(a.chapter),
        )
        _remoteAnchors.tryEmit(a)
    }

    /**
     * 大屏端翻页后上报的左上角锚点。更新遥控页显示的“章:段”，并通知阅读页跳页落盘；
     * 同时记成 focus，网页刷新时从当前位置恢复。
     * 遥控页没开时上报一律忽略：那时网页是被手机阅读进度推着走的，不能让回传把手机拽回去。
     */
    fun onClientAnchor(chapter: Int, block: Int, char: Int) {
        if (!remote) return
        val b = book ?: return
        if (chapter !in b.chapters.indices) return
        val blocks = b.chapters[chapter].blocks
        if (blocks.isEmpty()) return
        val safeBlock = block.coerceIn(0, blocks.size - 1)
        val safeChar = char.coerceIn(0, blocks[safeBlock].text.length)
        val a = EbookCastAnchor(chapter, safeBlock, safeChar)
        focus = a
        _state.value = _state.value.copy(
            anchor = a,
            chapterTitle = chapterTitleOf(chapter),
        )
        _remoteAnchors.tryEmit(a)
    }

    /**
     * 进入投屏（遥控页）。网页服务本身常驻，这里只是把遥控方向切过来：
     * 网页端需要局域网地址；Presentation 只需要本机 loopback（页面仍由同一服务提供）。
     * 启动失败只写 state.error，不抛异常。
     */
    fun start(mode: EbookCastMode, displayName: String = "") {
        if (mode == EbookCastMode.OFF) {
            stop()
            return
        }
        if (_state.value.starting) return
        val gen = generation.incrementAndGet()
        _state.value = _state.value.copy(
            starting = true,
            error = "",
            bookTitle = book?.title.orEmpty(),
            chapterTitle = "",
            anchor = null,
            turnOffset = 0,
            remote = true,
        )
        scope.launch {
            val lan = localIpv4() ?: webLanUrl.takeIf { it.isNotBlank() }
            if (mode == EbookCastMode.WEB && lan == null) {
                if (generation.get() == gen) {
                    _state.value = _state.value.copy(
                        starting = false,
                        remote = false,
                        error = "没有可用的局域网地址，请先连接 Wi-Fi 或热点",
                        webRunning = server != null,
                    )
                }
                return@launch
            }
            val srv = ensureServerRunning()
            if (generation.get() != gen) return@launch
            if (srv == null) {
                _state.value = _state.value.copy(
                    starting = false,
                    remote = false,
                    error = "无法启动网页服务",
                    webRunning = false,
                )
                return@launch
            }
            if (lan != null && lan != webLanUrl) webLanUrl = lan
            val url = "http://${if (mode == EbookCastMode.WEB) lan!! else "127.0.0.1"}:${srv.port}/"
            val initial = readerAnchor ?: book?.chapters?.takeIf { it.isNotEmpty() }?.let {
                EbookCastAnchor(0, 0, 0)
            }
            focus = initial
            focusGen += 1
            turnOffset = 0
            remote = true
            _state.value = EbookCastState(
                mode = mode,
                starting = false,
                url = url,
                lanUrl = if (webLanUrl.isBlank()) url else "http://$webLanUrl:${srv.port}/",
                displayName = displayName,
                error = "",
                bookTitle = book?.title.orEmpty(),
                chapterTitle = initial?.let { chapterTitleOf(it.chapter) }.orEmpty(),
                anchor = initial,
                turnOffset = 0,
                webRunning = true,
                clientsConnected = clientsConnected,
                remote = true,
            )
            syncAudioRoute()
        }
    }

    /**
     * 结束投屏 = 退出遥控页。网页服务不关（网页永远在线），只是方向换回“手机驱动大屏”：
     * 有书时网页跟着手机阅读进度，没书时显示音乐播放界面。
     */
    fun stop() {
        generation.incrementAndGet()
        remote = false
        focus = null
        turnOffset = 0
        _state.value = idleState()
        syncAudioRoute()
    }

    private fun idleState(): EbookCastState {
        val url = webUrlNow()
        return EbookCastState(
            mode = EbookCastMode.OFF,
            url = url,
            lanUrl = url,
            webRunning = server != null,
            clientsConnected = clientsConnected,
            remote = false,
        )
    }

    /** 网页地址（局域网 IP + 当前端口）；服务没起来或没有局域网时为空。 */
    private fun webUrlNow(): String {
        val srv = server ?: return ""
        val host = webLanUrl.ifBlank { localIpv4() ?: "" }
        return if (host.isBlank()) "" else "http://$host:${srv.port}/"
    }

    // ---- 常驻网页服务 / 网页连接 / 出声路由 ----

    /** 网页服务没起来就起一个（幂等）；失败返回 null，由 serveLoop 稍后重试。 */
    private fun ensureServerRunning(): EbookCastHttpServer? {
        synchronized(serverLock) {
            server?.let { return it }
            val srv = runCatching { EbookCastHttpServer(this) }.getOrNull() ?: return null
            server = srv
            val lan = webUrlNow()
            // HDMI 投屏的 WebView 用 loopback 地址，别被局域网地址顶掉
            if (_state.value.mode == EbookCastMode.PRESENTATION) {
                _state.value = _state.value.copy(webRunning = true, lanUrl = lan)
            } else {
                _state.value = _state.value.copy(webRunning = true, url = lan, lanUrl = lan)
            }
            return srv
        }
    }

    /**
     * 启动即开 + 断网/换网后自愈：每 5 秒检查一次。
     * 没有局域网（飞行模式/没连 Wi-Fi）就等，连上后自动起服务并刷新地址。
     */
    private suspend fun serveLoop() {
        while (true) {
            val lan = localIpv4()
            val srv = server
            if (srv == null) {
                if (lan != null) {
                    webLanUrl = lan
                    ensureServerRunning()
                }
            } else if (lan != null && lan != webLanUrl) {
                // 换网（换 Wi-Fi / 热点）：地址变了，遥控页显示的网址跟着变
                webLanUrl = lan
                val url = webUrlNow()
                if (_state.value.mode != EbookCastMode.PRESENTATION) {
                    _state.value = _state.value.copy(url = url, lanUrl = url)
                } else {
                    _state.value = _state.value.copy(lanUrl = url)
                }
            }
            kotlinx.coroutines.delay(5_000)
        }
    }

    /**
     * 网页每 300ms 轮询 /state：超过 8 秒没有任何请求即视为断开。
     * 阈值放宽是给后台标签页留余量（浏览器会节流定时器），否则一换标签页手机就跟着出声。
     */
    private suspend fun clientWatcher() {
        while (true) {
            val alive = lastClientAt > 0 &&
                android.os.SystemClock.elapsedRealtime() - lastClientAt < 8_000
            if (alive != clientsConnected) {
                clientsConnected = alive
                _state.value = _state.value.copy(clientsConnected = alive)
                syncAudioRoute()
            }
            kotlinx.coroutines.delay(1_000)
        }
    }

    /** 服务线程收到任意请求时调用：刷新连接时间戳，断→连时通知 UI 显示 airplay 键。 */
    internal fun onClientRequest() {
        lastClientAt = android.os.SystemClock.elapsedRealtime()
        if (!clientsConnected) {
            clientsConnected = true
            _state.value = _state.value.copy(clientsConnected = true)
            syncAudioRoute()
        }
    }

    /**
     * 出声路由：airplay 键打开 + 有网页连接 + 有本地音频 → 网页出声、手机本地静音；
     * 否则手机自己播。任何一项变化都走这里，保证不会出现“两边都不响”或“两边同时响”。
     */
    private fun syncAudioRoute() {
        val mode = _state.value.mode
        val wantWeb = displayPrefs.musicOnCast(mode != EbookCastMode.PRESENTATION)
        val active = wantWeb && clientsConnected && music.hasAudio && music.id.isNotBlank()
        if (active == webAudioActive) return
        webAudioActive = active
        onWebAudioMuted(active)
    }

    /**
     * 当前播放清单（播放页 / 阅读页的音乐气泡同源）。id 列表没变就不再重发，
     * 只更新序号：网页端靠 /state 的 queueIndex 高亮当前曲。
     */
    fun updateQueue(tracks: List<Song>, currentIndex: Int) {
        val idx = if (tracks.isEmpty()) 0 else currentIndex.coerceIn(0, tracks.size - 1)
        if (idx != queueIndex) queueIndex = idx
        // 每 200ms 一次的 tick：队列列表没换（同一个实例）就直接跳过，别每次都 map 一遍
        if (tracks === queueSource) return
        queueSource = tracks
        val ids = tracks.map { it.id }
        if (ids == queueIds) return
        queueIds = ids
        queue = tracks.map {
            EbookCastTrack(
                id = it.id,
                title = it.title,
                artist = it.artist,
                album = it.album,
                durationMs = (it.durationSec * 1000L).coerceAtLeast(0L),
            )
        }
        queueGen += 1
    }

    /**
     * 音乐状态（播放页/阅读页每 200ms 的 tick 会带过来）。只保存不可变快照，
     * 服务线程读到的是一致的一份；换曲时才异步解析封面。
     */
    fun updateMusic(song: Song, positionSec: Float, playing: Boolean) {
        if (song.id.isBlank()) {
            music = EbookCastMusic()
            trackPath = null
            lastPathSongId = ""
            musicAt = 0L
            syncAudioRoute()
            return
        }
        // 每 200ms 一次的 tick：同一首歌不必反复 stat 文件
        val path = if (song.id == lastPathSongId) trackPath else localPathOf(song)
        lastPathSongId = song.id
        trackPath = path
        // 进度相对“上次位置 + 已过时间”突然跳变 = 用户在手机上拖了进度条
        val now = android.os.SystemClock.elapsedRealtime()
        if (song.id == lastPosSongId && lastPosSec >= 0f) {
            val expected = if (playing) lastPosSec + (now - lastPosAt) / 1000f else lastPosSec
            if (kotlin.math.abs(positionSec - expected) > 0.8f) seekGen++
        }
        lastPosSongId = song.id
        lastPosSec = positionSec
        lastPosAt = now
        musicAt = now
        // 换曲时先清掉旧封面：网页在解析完成前看到 hasArt=false，不会请求到上一首的图
        val artChanged = song.id != artTrackId
        if (artChanged) {
            artTrackId = song.id
            artUri = null
            scope.launch {
                val resolved = runCatching { TrackArtworkCache.resolve(appContext, song) }
                    .getOrNull().orEmpty()
                if (artTrackId == song.id) {
                    artUri = resolved.takeIf { it.isNotBlank() }
                    music = music.copy(hasArt = artUri != null)
                }
            }
        }
        music = EbookCastMusic(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = (song.durationSec * 1000L).coerceAtLeast(0L),
            positionMs = (positionSec * 1000f).toLong().coerceAtLeast(0L),
            playing = playing,
            hasAudio = path != null,
            hasArt = artUri != null,
        )
        syncAudioRoute()
    }

    // ---- 供 HTTP 服务调用的只读快照 ----

    internal fun htmlTemplate(): ByteArray {
        templateBytes?.let { return it }
        val bytes = runCatching {
            appContext.resources.openRawResource(R.raw.ebook_cast).use { it.readBytes() }
        }.getOrDefault(ByteArray(0))
        templateBytes = bytes
        return bytes
    }

    internal fun stateJson(): ByteArray {
        val st = _state.value
        val b = book
        val f = focus
        val m = music
        val hasBook = b != null && b.chapters.isNotEmpty()
        // 遥控页关着（OFF）时网页仍在服务同一套画面，按网页的一份设置排版
        val isWebMode = st.mode != EbookCastMode.PRESENTATION
        val wire = WireState(
            bookTitle = b?.title.orEmpty(),
            bookAuthor = b?.author.orEmpty(),
            chapterCount = b?.chapters?.size ?: 0,
            // 没书时不给锚点：网页据此显示音乐界面，也不会拿旧锚点去定位正文
            focus = if (!hasBook) null else f?.let { WireFocus(it.chapter, it.block, it.char, focusGen) },
            turnOffset = turnOffset,
            theme = displayPrefs.theme.name,
            // 网页投屏和 HDMI 投屏各用各的字号/栏宽（网页常驻时也按网页的一份排版）
            fontSizePx = displayPrefs.fontSizePx(isWebMode),
            maxColumnWidthPx = displayPrefs.maxColumnWidthPx(isWebMode),
            showMusic = displayPrefs.showMusic(isWebMode),
            seekGen = seekGen,
            turnUnit = displayPrefs.turnUnit(isWebMode).name,
            fontUrl = fontUrl(),
            // 是否由网页出声：airplay 键 + 有网页连接 + 有本地音频（见 syncAudioRoute）
            audio = webAudioActive,
            // 前置放大只有网页出声时才有意义，没出声按满幅发，避免页面上残留小音量
            preamp = if (webAudioActive) displayPrefs.webPreamp.coerceIn(0f, 1f) else 1f,
            music = WireMusic(
                id = m.id,
                title = m.title,
                artist = m.artist,
                album = m.album,
                durationMs = m.durationMs,
                positionMs = extrapolatedPositionMs(m),
                playing = m.playing,
                hasArt = m.hasArt,
            ),
            hasBook = hasBook,
            queueGen = queueGen,
            queueIndex = queueIndex,
            queueLength = queue.size,
        )
        return gson.toJson(wire).toByteArray(Charsets.UTF_8)
    }

    /**
     * 快照时刻 → 现在的推进量：快照可能已经放了一会儿没人更新（锁屏后 Activity 被回收、
     * 进程被节流），直接把死值发给网页，网页会反复把音频拽回同一个位置（几秒一循环）。
     * 按 duration 夹住，暂停时不外推。
     */
    private fun extrapolatedPositionMs(m: EbookCastMusic): Long {
        val base = m.positionMs.coerceAtLeast(0L)
        if (!m.playing || musicAt <= 0L) return base
        val elapsed = (android.os.SystemClock.elapsedRealtime() - musicAt).coerceAtLeast(0L)
        val pos = base + elapsed
        return if (m.durationMs > 0L) pos.coerceAtMost(m.durationMs) else pos
    }

    /** 当前播放清单（只读展示）：网页端发现 queueGen 变化时来取一次。 */
    internal fun queueJson(): ByteArray {
        val wire = WireQueue(
            gen = queueGen,
            tracks = queue.map {
                WireQueueTrack(it.id, it.title, it.artist, it.album, it.durationMs)
            },
        )
        return gson.toJson(wire).toByteArray(Charsets.UTF_8)
    }

    internal fun chapterJson(index: Int): ByteArray? {
        val b = book ?: return null
        val ch = b.chapters.getOrNull(index) ?: return null
        val wire = WireChapter(
            index = index,
            title = chapterTitleOf(index),
            blocks = ch.blocks.map { WireBlock(it.level, it.text) },
        )
        return gson.toJson(wire).toByteArray(Charsets.UTF_8)
    }

    private fun chapterTitleOf(index: Int): String {
        val ch = book?.chapters?.getOrNull(index) ?: return ""
        return ch.title.trim().ifBlank { "第 ${index + 1} 章" }
    }

    /** 投屏字体永远跟随阅读页字体，保证两边显示一致。 */
    private fun effectiveFontPath(): String = prefs.prefs.value.fontPath

    private fun fontUrl(): String {
        val f = File(effectiveFontPath())
        if (!f.isFile) return ""
        return "/font?v=${f.length()}-${f.lastModified()}"
    }

    internal fun sendFont(out: OutputStream) {
        val path = effectiveFontPath()
        val f = File(path)
        if (path.isBlank() || !f.isFile || f.length() <= 0 || f.length() > 24L * 1024 * 1024) {
            HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "no font")
            return
        }
        val key = "$path:${f.length()}:${f.lastModified()}"
        val bytes = fontBytes.takeIf { fontCacheKey == key }
            ?: runCatching { f.readBytes() }.getOrNull().also {
                if (it != null) {
                    fontBytes = it
                    fontCacheKey = key
                }
            }
        if (bytes == null) {
            HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "no font")
            return
        }
        HttpUtil.sendBytes(out, 200, "OK", HttpUtil.fontMimeOf(path), bytes)
    }

    internal fun sendAudio(out: OutputStream, headers: Map<String, String>) {
        val path = trackPath
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
            HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "no audio")
            return
        }
        val total = file.length()
        if (total <= 0) {
            HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "empty audio")
            return
        }
        val range = headers["range"]
        var start = 0L
        var end = total - 1
        var status = 200
        var statusText = "OK"
        if (!range.isNullOrBlank() && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-').trim()
            if (a.isNotEmpty()) start = a.toLongOrNull() ?: 0L
            if (b.isNotEmpty()) end = b.toLongOrNull() ?: end
            if (a.isEmpty() && b.isNotEmpty()) {
                // bytes=-N：最后 N 字节
                val n = b.toLongOrNull() ?: 0L
                start = (total - n).coerceAtLeast(0L)
                end = total - 1
            }
            if (start !in 0 until total || end < start) {
                HttpUtil.sendHeaders(
                    out, 416, "Range Not Satisfiable", "text/plain; charset=utf-8", 0,
                    extra = listOf("Content-Range" to "bytes */$total"),
                )
                return
            }
            end = end.coerceAtMost(total - 1)
            status = 206
            statusText = "Partial Content"
        }
        val len = end - start + 1
        val extra = if (status == 206) listOf("Content-Range" to "bytes $start-$end/$total") else emptyList()
        HttpUtil.sendHeaders(out, status, statusText, HttpUtil.mimeOf(file.name), len, extra)
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = len
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    internal fun sendArt(out: OutputStream) {
        val uriStr = artUri
        if (uriStr.isNullOrBlank()) {
            HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "no art")
            return
        }
        val bytes = runCatching {
            when {
                uriStr.startsWith("content://") -> appContext.contentResolver
                    .openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
                uriStr.startsWith("file://") -> Uri.parse(uriStr).path?.let { File(it).readBytes() }
                uriStr.startsWith("/") -> File(uriStr).readBytes()
                else -> null
            }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) {
            HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "no art")
            return
        }
        val mime = runCatching {
            if (uriStr.startsWith("content://")) appContext.contentResolver.getType(Uri.parse(uriStr)) else null
        }.getOrNull() ?: sniffImageMime(bytes)
        HttpUtil.sendBytes(out, 200, "OK", mime, bytes)
    }

    /**
     * App 自带的默认封面（没有内嵌封面的曲目用）。drawable 里的 webp 有可能被 AAPT 处理过，
     * 统一解码后压成 PNG 再发，保证浏览器一定能渲染；结果缓存一份。
     */
    internal fun sendDefaultArt(out: OutputStream) {
        val bytes = defaultArtBytes ?: runCatching {
            val bmp = android.graphics.BitmapFactory.decodeResource(
                appContext.resources, R.drawable.default_cover,
            ) ?: return@runCatching null
            java.io.ByteArrayOutputStream().use { bos ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "no art")
            return
        }
        defaultArtBytes = bytes
        HttpUtil.sendBytes(out, 200, "OK", "image/png", bytes)
    }

    /** content:// 封面常常没有扩展名，按魔数决定 MIME，避免浏览器拒绝渲染。 */
    private fun sniffImageMime(bytes: ByteArray): String {
        fun at(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            at(0) == 0x42 && at(1) == 0x4D -> "image/bmp"
            else -> "image/jpeg"
        }
    }

    private fun localPathOf(song: Song): String? {
        val raw = song.path.ifBlank { song.streamUrl }
        if (raw.isBlank()) return null
        val p = when {
            raw.startsWith("file://") -> Uri.parse(raw).path
            raw.startsWith("/") -> raw
            else -> null
        }
        return p?.takeIf { File(it).isFile }
    }

    private fun localIpv4(): String? {
        val ifaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrDefault(emptyList())
        val candidates = ArrayList<Pair<String, String>>()
        ifaces.forEach { iface ->
            val up = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!up) return@forEach
            val name = iface.name.orEmpty()
            if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("ppp")) return@forEach
            runCatching { Collections.list(iface.inetAddresses) }.getOrDefault(emptyList()).forEach { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    addr.hostAddress?.let { candidates += name to it }
                }
            }
        }
        fun score(name: String, ip: String): Int {
            var s = 0
            if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) s += 100
            if (name.startsWith("eth")) s += 80
            if (ip.startsWith("192.168.")) s += 50
            if (ip.startsWith("10.")) s += 30
            if (ip.startsWith("172.")) s += 20
            return s
        }
        return candidates.maxByOrNull { score(it.first, it.second) }?.second
    }

    /** 供遥控页直接展示的局域网地址（服务未启动时也尽量给出）。 */
    fun lanAddress(): String? = localIpv4()
}

// ---- 极简只读 HTTP 服务 ----

private class EbookCastHttpServer(private val cast: EbookCastController) {

    private val running = AtomicBoolean(true)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ebook-cast-http").apply { isDaemon = true }
    }

    private val serverSocket: ServerSocket = run {
        for (p in 8080..8099) {
            val s = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
            }.getOrNull()
            if (s != null) return@run s
        }
        ServerSocket(0)
    }

    val port: Int get() = serverSocket.localPort

    init {
        Thread({
            while (running.get()) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching { pool.execute { handle(socket) } }
            }
        }, "ebook-cast-accept").apply { isDaemon = true }.start()
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket.close() }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        runCatching {
            socket.use { s ->
                s.soTimeout = 20_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return@use
                val parts = requestLine.split(' ')
                if (parts.size < 2) return@use
                val method = parts[0].uppercase()
                var line: String?
                val headers = HashMap<String, String>()
                while (true) {
                    line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                // 任意请求都算“网页在线”：播放页据此显示 airplay 键、决定由谁出声
                cast.onClientRequest()
                val out = BufferedOutputStream(s.getOutputStream())
                if (method != "GET" && method != "HEAD") {
                    HttpUtil.sendText(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "read only")
                } else {
                    when (path) {
                        "/", "/index.html" ->
                            HttpUtil.sendBytes(out, 200, "OK", "text/html; charset=utf-8", cast.htmlTemplate())
                        "/state" ->
                            HttpUtil.sendBytes(out, 200, "OK", "application/json; charset=utf-8", cast.stateJson())
                        "/chapter" -> {
                            val i = query["i"]?.toIntOrNull()
                            val body = i?.let { cast.chapterJson(it) }
                            if (body == null) {
                                HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "no chapter")
                            } else {
                                HttpUtil.sendBytes(out, 200, "OK", "application/json; charset=utf-8", body)
                            }
                        }
                        "/anchor" -> {
                            val c = query["c"]?.toIntOrNull()
                            val b = query["b"]?.toIntOrNull()
                            val ch = query["char"]?.toIntOrNull() ?: 0
                            if (c != null && b != null) cast.onClientAnchor(c, b, ch)
                            HttpUtil.sendText(out, 200, "OK", "text/plain; charset=utf-8", "ok")
                        }
                        "/queue" ->
                            HttpUtil.sendBytes(out, 200, "OK", "application/json; charset=utf-8", cast.queueJson())
                        "/font" -> cast.sendFont(out)
                        "/audio" -> cast.sendAudio(out, headers)
                        "/art" -> cast.sendArt(out)
                        "/defaultart" -> cast.sendDefaultArt(out)
                        "/favicon.ico" ->
                            HttpUtil.sendBytes(out, 200, "OK", "image/x-icon", ByteArray(0))
                        else ->
                            HttpUtil.sendText(out, 404, "Not Found", "text/plain; charset=utf-8", "not found")
                    }
                }
                out.flush()
            }
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            val key = runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
            val value = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            key to value
        }.toMap()
    }
}

private object HttpUtil {

    fun sendText(
        out: OutputStream,
        status: Int,
        statusText: String,
        mime: String,
        text: String,
    ) = sendBytes(out, status, statusText, mime, text.toByteArray(Charsets.UTF_8))

    fun sendBytes(
        out: OutputStream,
        status: Int,
        statusText: String,
        mime: String,
        bytes: ByteArray,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        sendHeaders(out, status, statusText, mime, bytes.size.toLong(), extra)
        if (bytes.isNotEmpty()) out.write(bytes)
    }

    fun sendHeaders(
        out: OutputStream,
        status: Int,
        statusText: String,
        mime: String,
        length: Long,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        val sb = StringBuilder(160)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n")
        extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun fontMimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    fun mimeOf(nameOrPath: String): String =
        when (nameOrPath.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aif", "aiff" -> "audio/aiff"
            "wma" -> "audio/x-ms-wma"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
}
