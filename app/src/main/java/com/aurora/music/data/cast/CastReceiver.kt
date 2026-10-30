package com.aurora.music.data.cast

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.aurora.music.data.ebook.EbookAnchorCode
import com.aurora.music.model.Song
import com.google.gson.Gson
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 接收端界面状态。Idle 时整个投屏浮层不渲染。 */
sealed interface CastReceiveState {
    data object Idle : CastReceiveState

    data class Incoming(val offer: CastOffer) : CastReceiveState

    data class Loading(val senderName: String, val type: CastContentType) : CastReceiveState

    data class Music(
        val senderName: String,
        val baseUrl: String,
        val song: Song,
        val queue: List<Song>,
        val index: Int,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val shuffle: Boolean,
        val repeat: String,
        val liked: Boolean,
    ) : CastReceiveState

    data class Ebook(
        val senderName: String,
        val baseUrl: String,
        val pageUrl: String,
        val bookTitle: String,
        val author: String,
        val chapterTitle: String,
        val anchor: String,
    ) : CastReceiveState
}

/**
 * 接收端控制器：收到 /cast/offer 后弹接受界面；接受后
 * - 音乐：轮询发送端 /state 与 /queue，用独立 ExoPlayer 拉 /audio（Range），
 *   所有操作回传 /cast/command，两边界面同步；
 * - 电子书：WebView 打开发送端网页（排版、底部音乐条都由发送端决定），
 *   本机只负责顶栏（退出 + 阅读信息）。
 */
class CastReceiverController(
    context: Context,
    private val selfId: String,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()

    private val _state = MutableStateFlow<CastReceiveState>(CastReceiveState.Idle)
    val state: StateFlow<CastReceiveState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var pollJob: Job? = null
    private var tickerJob: Job? = null

    @Volatile private var baseUrl: String = ""
    @Volatile private var senderName: String = ""
    private var contentType: CastContentType = CastContentType.MUSIC
    private var queue: List<Song> = emptyList()
    private var queueGen = -1
    private var lastTrackId = ""
    private var failures = 0
    private var lastSeekSentAt = 0L

    /** 收到投屏请求；正在等待用户确认/正在建立连接时返回 false（对方会提示“正忙”）。 */
    fun onOffer(offer: CastOffer): Boolean {
        if (!offer.valid) return false
        if (offer.senderId.isNotBlank() && offer.senderId == selfId) return false
        when (_state.value) {
            is CastReceiveState.Idle -> Unit
            is CastReceiveState.Incoming, is CastReceiveState.Loading -> return false
            // 已经在接收：发送端换了内容/重投，直接替换当前会话（重新弹接受界面）
            else -> clear()
        }
        _state.value = CastReceiveState.Incoming(offer)
        return true
    }

    fun accept() {
        val incoming = _state.value as? CastReceiveState.Incoming ?: return
        val offer = incoming.offer
        senderName = offer.senderName.ifBlank { offer.host }
        baseUrl = offer.baseUrl
        contentType = offer.contentType
        _state.value = CastReceiveState.Loading(senderName, contentType)
        when (contentType) {
            CastContentType.MUSIC -> startMusic()
            CastContentType.EBOOK -> startEbook()
        }
    }

    fun reject() {
        val incoming = _state.value as? CastReceiveState.Incoming ?: return
        val offer = incoming.offer
        scope.launch(Dispatchers.IO) {
            CastHttp.get("${offer.baseUrl}/cast/command?cmd=reject")
        }
        clear()
    }

    /** 本机/发送端结束投屏。 */
    fun stop(sendStop: Boolean = true) {
        if (sendStop && baseUrl.isNotBlank()) {
            val base = baseUrl
            scope.launch(Dispatchers.IO) {
                CastHttp.get("$base/cast/command?cmd=stop")
            }
        }
        clear()
    }

    /** 发送端主动通知结束（/cast/stop）：HTTP 线程回调，切回主线程再动播放器。 */
    fun onSenderStop() {
        scope.launch { clear() }
    }

    // ---- 音乐 ----

    fun togglePlay() {
        val p = player ?: return
        if (p.playWhenReady) p.pause() else p.play()
        sendCommand("toggle")
    }

    fun next() = sendCommand("next")

    fun previous() = sendCommand("prev")

    fun seekFraction(fraction: Float, commit: Boolean) {
        val st = _state.value as? CastReceiveState.Music ?: return
        val ms = (st.durationMs.coerceAtLeast(1L) * fraction.coerceIn(0f, 1f)).toLong()
        player?.seekTo(ms)
        _state.value = st.copy(positionMs = ms)
        val now = SystemClock.elapsedRealtime()
        if (!commit && now - lastSeekSentAt < 250L) return
        lastSeekSentAt = now
        sendCommand("seek", "&pos=$ms")
    }

    fun jumpTo(index: Int) = sendCommand("jump", "&index=$index")

    fun toggleShuffle() = sendCommand("shuffle")

    fun cycleRepeat() = sendCommand("repeat")

    fun toggleLike() = sendCommand("like")

    private fun sendCommand(cmd: String, extra: String = "") {
        val base = baseUrl
        if (base.isBlank()) return
        scope.launch(Dispatchers.IO) {
            CastHttp.get("$base/cast/command?cmd=$cmd$extra")
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(appContext).build()
        p.setAudioAttributes(AudioAttributes.DEFAULT, true)
        p.setHandleAudioBecomingNoisy(true)
        player = p
        return p
    }

    private fun startMusic() {
        val p = ensurePlayer()
        p.stop()
        p.clearMediaItems()
        pollJob?.cancel()
        tickerJob?.cancel()
        queue = emptyList()
        queueGen = -1
        lastTrackId = ""
        failures = 0
        pollJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    CastHttp.fetch("$baseUrl/state?t=${SystemClock.elapsedRealtime()}")
                }
                if (text == null) {
                    failures += 1
                    if (failures >= 8) {
                        clear()
                        break
                    }
                    delay(800)
                    continue
                }
                failures = 0
                val rs = runCatching { gson.fromJson(text, RemoteState::class.java) }.getOrNull()
                if (rs != null) {
                    if (rs.queueGen != queueGen) {
                        val qText = withContext(Dispatchers.IO) {
                            CastHttp.fetch("$baseUrl/queue?v=${rs.queueGen}")
                        }
                        val parsed = qText?.let {
                            runCatching { gson.fromJson(it, RemoteQueue::class.java).tracks }.getOrNull()
                        }
                        if (parsed != null) {
                            queue = parsed.map { t ->
                                Song(
                                    id = t.id,
                                    title = t.title,
                                    artist = t.artist,
                                    album = t.album,
                                    artworkUrl = "",
                                    durationSec = (t.durationMs / 1000L).toInt(),
                                )
                            }
                            queueGen = rs.queueGen
                        }
                    }
                    applyRemote(rs)
                }
                delay(700)
            }
        }
        tickerJob = scope.launch {
            while (isActive) {
                delay(250)
                val st = _state.value as? CastReceiveState.Music ?: continue
                val p2 = player ?: continue
                _state.value = st.copy(
                    positionMs = p2.currentPosition.coerceAtLeast(0L),
                    playing = p2.playWhenReady,
                )
            }
        }
    }

    private fun applyRemote(rs: RemoteState) {
        val p = ensurePlayer()
        val m = rs.music
        if (m.id.isBlank()) return

        val song = Song(
            id = m.id,
            title = m.title,
            artist = m.artist,
            album = m.album,
            artworkUrl = if (m.hasArt) "$baseUrl/art?t=${m.id}" else "",
            durationSec = (m.durationMs / 1000L).toInt(),
        )
        val list = queue.toMutableList()
        val idx = rs.queueIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (list.isEmpty()) {
            list.add(song)
        } else {
            list[idx] = song
        }

        if (m.id != lastTrackId) {
            lastTrackId = m.id
            p.setMediaItem(MediaItem.fromUri("$baseUrl/audio?t=${m.id}"))
            p.prepare()
            p.seekTo(m.positionMs.coerceAtLeast(0L))
            p.playWhenReady = m.playing
        } else {
            p.playWhenReady = m.playing
            // 播放端的进度是权威值；本地只在漂移明显时纠一次，避免每轮都 seek 出爆音
            if (m.playing && abs(p.currentPosition - m.positionMs) > 1_800L) {
                p.seekTo(m.positionMs.coerceAtLeast(0L))
            }
        }

        _state.value = CastReceiveState.Music(
            senderName = senderName,
            baseUrl = baseUrl,
            song = song,
            queue = list,
            index = idx,
            playing = m.playing,
            positionMs = m.positionMs,
            durationMs = m.durationMs.coerceAtLeast(0L),
            shuffle = rs.shuffle,
            repeat = rs.repeat,
            liked = rs.liked,
        )
    }

    // ---- 电子书 ----

    private fun startEbook() {
        pollJob?.cancel()
        tickerJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) {
                    CastHttp.fetch("$baseUrl/state?t=${SystemClock.elapsedRealtime()}")
                }
                if (text == null) {
                    failures += 1
                    if (failures >= 8) {
                        clear()
                        break
                    }
                    delay(1_000)
                    continue
                }
                failures = 0
                val rs = runCatching { gson.fromJson(text, RemoteState::class.java) }.getOrNull()
                if (rs != null) {
                    val anchor = rs.focus?.let {
                        EbookAnchorCode.encode(it.chapter, it.block)
                    }.orEmpty()
                    _state.value = CastReceiveState.Ebook(
                        senderName = senderName,
                        baseUrl = baseUrl,
                        pageUrl = "$baseUrl/?recv=1",
                        bookTitle = rs.bookTitle,
                        author = rs.bookAuthor,
                        chapterTitle = rs.chapterTitle,
                        anchor = anchor,
                    )
                }
                delay(1_000)
            }
        }
    }

    // ---- 清理 ----

    private fun clear() {
        pollJob?.cancel()
        pollJob = null
        tickerJob?.cancel()
        tickerJob = null
        // HTTP 线程也可能进来（新的投屏请求顶掉旧会话）：播放器只能在主线程操作
        val p = player
        if (p != null) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                stopPlayer(p)
            } else {
                scope.launch { stopPlayer(p) }
            }
        }
        baseUrl = ""
        senderName = ""
        queue = emptyList()
        queueGen = -1
        lastTrackId = ""
        failures = 0
        lastSeekSentAt = 0L
        _state.value = CastReceiveState.Idle
    }

    private fun stopPlayer(p: ExoPlayer) {
        runCatching { p.stop() }
        runCatching { p.clearMediaItems() }
    }

    private data class RemoteFocus(
        val chapter: Int = 0,
        val block: Int = 0,
        val char: Int = 0,
    )

    private data class RemoteMusic(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val playing: Boolean = false,
        val hasArt: Boolean = false,
    )

    private data class RemoteState(
        val bookTitle: String = "",
        val bookAuthor: String = "",
        val chapterTitle: String = "",
        val chapterCount: Int = 0,
        val hasBook: Boolean = false,
        val focus: RemoteFocus? = null,
        val queueGen: Int = 0,
        val queueIndex: Int = 0,
        val queueLength: Int = 0,
        val shuffle: Boolean = false,
        val repeat: String = "OFF",
        val liked: Boolean = false,
        val senderId: String = "",
        val senderName: String = "",
        val music: RemoteMusic = RemoteMusic(),
    )

    private data class RemoteQueueTrack(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0L,
    )

    private data class RemoteQueue(val tracks: List<RemoteQueueTrack> = emptyList())
}
