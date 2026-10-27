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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 电子书投屏：手机当遥控器，网页 / HDMI 显示整本书的“大屏排版”。
// - 大屏端不是镜像手机的一屏，而是按投屏字号 + 一栏最大宽度自行分栏排版，页页填满屏幕。
// - 进度锚点统一用“章:段”（EbookAnchorCode）：手机开投时把当前章:段发给大屏；
//   大屏每次翻页把左上角第一个字的 (章, 段, 字) 报回手机，手机跳到该字所在页并落盘，
//   两边始终同步，退出投屏后手机就停在刚才大屏读到的地方。
// - 手机起一个极简只读 HTTP 服务（原生 ServerSocket，无第三方依赖）；/state 拉设置与指令，
//   /chapter 按章取正文，/font 供阅读字体，/audio 流式音乐（Range 可拖），/art 封面。
// - Presentation 模式复用同一网页，通过 Android Presentation 输出到 HDMI 副屏；
//   声音仍由手机播放器走系统音频路由，网页端不重复出声。

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

private data class WireFocus(val chapter: Int, val block: Int, val char: Int, val gen: Long)

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
    /** 网页端由浏览器出声；HDMI 端由手机播放器直接走系统音频路由。 */
    val audio: Boolean,
    val music: WireMusic,
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

    /** 大屏端上报的进度锚点：阅读页收集后跳页并落盘。 */
    private val _remoteAnchors = MutableSharedFlow<EbookCastAnchor>(extraBufferCapacity = 16)
    val remoteAnchors: SharedFlow<EbookCastAnchor> = _remoteAnchors.asSharedFlow()

    @Volatile private var server: EbookCastHttpServer? = null
    @Volatile private var book: ParsedEbook? = null
    /** 手机阅读页当前页首（章/段/字），开投时作为大屏的初始定位。 */
    @Volatile private var readerAnchor: EbookCastAnchor? = null
    /** 大屏端当前锚点；网页刷新/重新打开时从它恢复。 */
    @Volatile private var focus: EbookCastAnchor? = null
    @Volatile private var focusGen: Long = 0
    @Volatile private var turnOffset: Int = 0
    @Volatile private var music = EbookCastMusic()
    /** 手机端拖动进度条的次数：网页端只在它变化时跳转，不会被播放漂移带着乱跳。 */
    @Volatile private var seekGen: Int = 0
    @Volatile private var lastPosSec: Float = -1f
    @Volatile private var lastPosAt: Long = 0L
    @Volatile private var lastPosSongId: String = ""
    @Volatile private var trackPath: String? = null
    @Volatile private var artUri: String? = null
    @Volatile private var artTrackId: String = ""
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
                // 遥控端切换“音乐在投屏设备播 / 手机播”后立刻生效
                val mode = _state.value.mode
                if (mode != EbookCastMode.OFF) {
                    onWebAudioMuted(it.musicOnCast(mode == EbookCastMode.WEB))
                }
            }
        }
    }

    /** 阅读页打开时注入整本正文，供大屏按章取内容。 */
    fun setBook(book: ParsedEbook?) {
        this.book = book
        if (book == null) {
            readerAnchor = null
            focus = null
        }
    }

    /** 手机阅读页当前页首锚点；开投时用来把大屏定位到同一“章:段”。 */
    fun setReaderAnchor(anchor: EbookCastAnchor) {
        readerAnchor = anchor
    }

    /** 遥控翻页：只累计指令，由大屏端按差值执行（连点不丢页）。 */
    fun turn(delta: Int) {
        if (delta == 0 || _state.value.mode == EbookCastMode.OFF) return
        turnOffset += delta
        _state.value = _state.value.copy(turnOffset = turnOffset)
    }

    /**
     * 遥控端从「进度历史」恢复位置：把大屏重新定位到该“章:段”（focus gen +1，
     * 网页看到新 gen 会跳过去），并让手机阅读页跟着跳到位、按同一锚点落盘。
     */
    fun restoreAnchor(anchor: EbookCastAnchor) {
        if (_state.value.mode == EbookCastMode.OFF) return
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
     */
    fun onClientAnchor(chapter: Int, block: Int, char: Int) {
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
     * 开始投屏。网页端需要局域网地址；Presentation 只需要本机 loopback（页面仍由同一服务提供）。
     * 启动失败只写 state.error，不抛异常。
     */
    fun start(mode: EbookCastMode, displayName: String = "") {
        if (mode == EbookCastMode.OFF) {
            stop()
            return
        }
        if (_state.value.starting) return
        val lan = localIpv4()
        if (mode == EbookCastMode.WEB && lan == null) {
            _state.value = EbookCastState(error = "没有可用的局域网地址，请先连接 Wi-Fi 或热点")
            return
        }
        val gen = generation.incrementAndGet()
        _state.value = _state.value.copy(
            starting = true,
            error = "",
            bookTitle = book?.title.orEmpty(),
            chapterTitle = "",
            anchor = null,
            turnOffset = 0,
        )
        scope.launch {
            val srv = server ?: runCatching { EbookCastHttpServer(this@EbookCastController) }
                .getOrElse { e ->
                    if (generation.get() == gen) {
                        _state.value = EbookCastState(error = "无法启动网页服务：${e.message ?: "未知错误"}")
                    }
                    return@launch
                }
            if (generation.get() != gen) {
                runCatching { srv.close() }
                return@launch
            }
            server = srv
            val host = if (mode == EbookCastMode.WEB) lan!! else "127.0.0.1"
            val url = "http://$host:${srv.port}/"
            val initial = readerAnchor ?: book?.chapters?.takeIf { it.isNotEmpty() }?.let {
                EbookCastAnchor(0, 0, 0)
            }
            focus = initial
            focusGen += 1
            turnOffset = 0
            _state.value = EbookCastState(
                mode = mode,
                starting = false,
                url = url,
                lanUrl = lan?.let { "http://$it:${srv.port}/" }.orEmpty(),
                displayName = displayName,
                error = "",
                bookTitle = book?.title.orEmpty(),
                chapterTitle = initial?.let { chapterTitleOf(it.chapter) }.orEmpty(),
                anchor = initial,
                turnOffset = 0,
            )
            onWebAudioMuted(displayPrefs.musicOnCast(mode == EbookCastMode.WEB))
        }
    }

    fun stop() {
        generation.incrementAndGet()
        val srv = server
        server = null
        if (srv != null) scope.launch { srv.close() }
        onWebAudioMuted(false)
        focus = null
        turnOffset = 0
        _state.value = EbookCastState()
    }

    /**
     * 音乐状态（阅读页每 200ms 的 tick 会带过来）。只保存不可变快照，
     * 服务线程读到的是一致的一份；换曲时才异步解析封面。
     */
    fun updateMusic(song: Song, positionSec: Float, playing: Boolean) {
        if (song.id.isBlank()) {
            music = EbookCastMusic()
            trackPath = null
            return
        }
        val path = localPathOf(song)
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
        val wire = WireState(
            bookTitle = b?.title.orEmpty(),
            bookAuthor = b?.author.orEmpty(),
            chapterCount = b?.chapters?.size ?: 0,
            focus = f?.let { WireFocus(it.chapter, it.block, it.char, focusGen) },
            turnOffset = turnOffset,
            theme = displayPrefs.theme.name,
            // 网页投屏和 HDMI 投屏各用各的字号/栏宽
            fontSizePx = displayPrefs.fontSizePx(st.mode == EbookCastMode.WEB),
            maxColumnWidthPx = displayPrefs.maxColumnWidthPx(st.mode == EbookCastMode.WEB),
            showMusic = displayPrefs.showMusic(st.mode == EbookCastMode.WEB),
            seekGen = seekGen,
            turnUnit = displayPrefs.turnUnit(st.mode == EbookCastMode.WEB).name,
            fontUrl = fontUrl(),
            // 音乐是否由投屏画面出声：网页和 HDMI 各自独立设置
            audio = displayPrefs.musicOnCast(st.mode == EbookCastMode.WEB) && m.hasAudio,
            music = WireMusic(
                id = m.id,
                title = m.title,
                artist = m.artist,
                album = m.album,
                durationMs = m.durationMs,
                positionMs = m.positionMs,
                playing = m.playing,
                hasArt = m.hasArt,
            ),
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
                        "/font" -> cast.sendFont(out)
                        "/audio" -> cast.sendAudio(out, headers)
                        "/art" -> cast.sendArt(out)
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
