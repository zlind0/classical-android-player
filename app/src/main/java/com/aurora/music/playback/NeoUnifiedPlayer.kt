package com.aurora.music.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aurora.music.data.AppContainer
import com.aurora.music.data.ebook.EbookTtsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 统一控制层：音乐（含有声书）与电子书（TTS 听书）两个通道。
 *
 * 只做控制/状态协调，不碰音频输出：音乐仍由 [music] 出声，听书仍由
 * [EbookTtsController] 自己的播放器出声，系统负责混音。
 *
 * 会话 player 的切换（音乐 ExoPlayer ↔ 书的影子 player）留在本类内部，
 * 外部只看到一套命令和一份 [state]。
 */
class NeoUnifiedPlayer(
    private val container: AppContainer,
    private val music: ExoPlayer,
    private val bookShadow: UnifiedPlayer,
    /** 投屏接收期间接管会话 player：通知/锁屏显示接收端曲目。 */
    private val cast: CastSessionPlayer,
    private val setSessionPlayer: (Player) -> Unit,
    private val scope: CoroutineScope,
) {
    private val tts: EbookTtsController get() = container.ebookTts

    private val _state = MutableStateFlow(UnifiedState())
    val state: StateFlow<UnifiedState> = _state.asStateFlow()

    // 以下只在主线程读写。
    private var musicLoaded = false
    private var musicPlaying = false
    private var musicArmed = false
    private var bookPlaying = false
    private var bookArmed = false
    // 投屏接收音乐（Pod / AirPlay 音频）。它是和音乐、听书并列的第三个通道：
    // 开始/结束只切换自己，全局暂停/恢复才一起动。
    private var castActive = false
    private var castPlaying = false
    private var castArmed = false
    private var autoResume = false
    private var interrupted = false
    private var introPausedMusic = false
    private var unloading = false
    private var foreground = UnifiedChannel.MUSIC
    private var snapshot: MusicSnapshot? = null

    private var lastPara: EbookTtsController.Para? = null
    private var bookTitle = ""
    private var bookSection = ""
    private var bookCover = ""
    private var musicTitle = ""
    private var musicArtist = ""
    private var musicArtUri = ""

    fun attach() {
        // 音乐真实状态（双播时会话 player 是书，音乐信息以服务快照为准）。
        scope.launch {
            container.musicState.collect { ms ->
                if (ms.mediaId.isNotBlank() && !unloading) {
                    musicLoaded = true
                    musicTitle = ms.title
                    musicArtist = ms.artist
                    musicArtUri = ms.artUri
                }
                publish()
            }
        }
        scope.launch {
            tts.playing.collect { playing ->
                bookPlaying = playing
                if (playing) {
                    bookArmed = false
                    foreground = UnifiedChannel.BOOK
                } else {
                    // 自然读完或用户停：留一个断点给全局播放。
                    if (bookArmed.not()) bookArmed = true
                    // 书停了、音乐还在播：前台回到音乐。
                    if (musicEffectivelyPlaying()) foreground = UnifiedChannel.MUSIC
                }
                publish()
                refreshSession()
            }
        }
        scope.launch {
            tts.position.collect { pos ->
                if (pos != null) {
                    lastPara = pos
                    bookSection = tts.sectionTitle.value
                }
                publish()
            }
        }
        scope.launch {
            tts.sectionTitle.collect { s ->
                bookSection = s
                publish()
            }
        }
        scope.launch {
            tts.bookTitle.collect { t ->
                bookTitle = t
                publish()
            }
        }
        scope.launch {
            container.ebookStore.recents.collect { recents ->
                val top = recents.firstOrNull()
                bookCover = top?.coverPath.orEmpty()
                if (tts.bookTitle.value.isBlank()) bookTitle = top?.displayTitle.orEmpty()
                publish()
            }
        }
        music.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onMusicPlayingChanged(isPlaying)
            }
        })
        // 书的影子 player 的系统命令转给本协调器。
        bookShadow.dualControl = object : DualMusicControl {
            override fun isMusicPlaying(): Boolean = music.isPlaying
            override fun switchMusicFromSystem(direction: Int): Boolean = false
        }
        bookShadow.onDualSystemPause = { pause() }
        bookShadow.onDualSystemResume = {
            play()
            // 书由本协调器恢复，影子 player 不再自己续读，避免双恢复。
            false
        }
        // 投屏接收：作为一个独立通道纳入统一控制。开始/结束不影响听书，
        // 只有全局暂停/恢复会同时作用于两者。
        scope.launch {
            combine(container.castReceiver.state, container.castHub.state) { _, _ ->
                container.receiveNowPlaying.snapshot()
            }.collect { onCastChanged(it) }
        }
        // 投屏会话 player 上的系统走带键也回到统一控制层：
        // 暂停要把听书/音乐一起暂停，播放则一起恢复。
        cast.onSetPlayingRequest = { want -> if (want) play() else pause() }
        cast.onStopRequest = { pause() }
        cast.onNextRequest = { next() }
        cast.onPreviousRequest = { previous() }
        publish()
        refreshSession()
    }

    // ---- 全局命令 ----

    fun play() {
        autoResume = false
        interrupted = false
        var did = false
        if (castArmed && castActive) {
            castArmed = false
            runCatching { container.receiveNowPlaying.setPlaying(true) }
            did = true
        }
        val para = lastPara
        if (bookArmed && para != null) {
            bookArmed = false
            runCatching { tts.playFromBookmark(para.chapter, para.block, para.startChar) }
            did = true
        }
        if (musicArmed && musicLoaded) {
            musicArmed = false
            runCatching { music.play() }
            did = true
        }
        if (!did) {
            // 没有 armed：按前台通道恢复。卸载的音乐不在这里重载。
            if (foreground == UnifiedChannel.BOOK && para != null) {
                runCatching { tts.playFromBookmark(para.chapter, para.block, para.startChar) }
            } else if (musicLoaded) {
                runCatching { music.play() }
            }
        }
        publish()
        refreshSession()
    }

    fun pause() {
        autoResume = false
        interrupted = false
        if (castPlaying) {
            castArmed = true
            runCatching { container.receiveNowPlaying.setPlaying(false) }
        }
        if (musicEffectivelyPlaying()) {
            musicArmed = true
            runCatching { music.pause() }
        }
        if (bookPlaying) {
            bookArmed = true
            runCatching { tts.stop() }
        }
        publish()
        refreshSession()
    }

    /** 统一播放键：任一路在播就全部暂停，否则把暂停前在播的通道一起恢复。 */
    fun toggle() {
        if (isAnyPlaying()) pause() else play()
    }

    fun next() {
        when {
            castActive -> runCatching { container.receiveNowPlaying.next() }
            bookPlaying -> runCatching { tts.next() }
            else -> runCatching { music.seekToNextMediaItem() }
        }
        publish()
        refreshSession()
    }

    fun previous() {
        when {
            castActive -> runCatching { container.receiveNowPlaying.previous() }
            bookPlaying -> runCatching { tts.prev() }
            else -> runCatching {
                if (music.currentPosition > 4000) music.seekTo(0) else music.seekToPreviousMediaItem()
            }
        }
        publish()
        refreshSession()
    }

    fun isAnyPlaying(): Boolean = castPlaying || musicEffectivelyPlaying() || bookPlaying

    /** 缓冲中且 playWhenReady 也算“在播”，与 UI/服务快照同口径。 */
    private fun musicEffectivelyPlaying(): Boolean =
        musicPlaying || (music.playWhenReady && music.playbackState == Player.STATE_BUFFERING)

    // ---- 音乐通道 ----

    fun musicPlay() {
        if (!musicLoaded) loadMusic()
        if (musicLoaded) {
            musicArmed = false
            runCatching { music.play() }
            if (!bookPlaying) foreground = UnifiedChannel.MUSIC
        }
        publish()
        refreshSession()
    }

    /**
     * 用户主动暂停音乐：
     * - 书在播 → 卸载（除非用户手动装载，否则全局播放不再带起音乐）；
     * - 书没播 → 只暂停，保留装载，全局播放可恢复。
     */
    fun musicPause() {
        if (bookPlaying) {
            unloadMusic()
        } else if (musicEffectivelyPlaying()) {
            musicArmed = true
            runCatching { music.pause() }
        }
        publish()
        refreshSession()
    }

    fun musicToggle() {
        if (musicEffectivelyPlaying()) musicPause() else musicPlay()
    }

    fun musicUnload() = unloadMusic()

    private fun unloadMusic() {
        if (!musicLoaded) return
        snapshot = MusicSnapshot(
            items = List(music.mediaItemCount) { music.getMediaItemAt(it) },
            index = music.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = music.currentPosition.coerceAtLeast(0),
            shuffle = music.shuffleModeEnabled,
            repeatMode = music.repeatMode,
            speed = music.playbackParameters.speed,
            pitch = music.playbackParameters.pitch,
        )
        unloading = true
        musicLoaded = false
        musicArmed = false
        musicPlaying = false
        runCatching { music.stop() }
        runCatching { music.clearMediaItems() }
        unloading = false
        if (foreground == UnifiedChannel.MUSIC) foreground = UnifiedChannel.BOOK
        publish()
        refreshSession()
    }

    private fun loadMusic() {
        val snap = snapshot ?: return
        if (snap.items.isEmpty()) return
        runCatching {
            music.setMediaItems(
                snap.items,
                snap.index.coerceIn(0, snap.items.lastIndex),
                snap.positionMs,
            )
            music.shuffleModeEnabled = snap.shuffle
            music.repeatMode = snap.repeatMode
            music.playbackParameters = PlaybackParameters(snap.speed, snap.pitch)
            music.prepare()
        }
        musicLoaded = true
        snapshot = null
        publish()
    }

    /** 歌曲介绍临时暂停音乐：不触发卸载，介绍结束再恢复。 */
    fun pauseMusicForIntro() {
        if (musicPlaying) {
            introPausedMusic = true
            runCatching { music.pause() }
        }
    }

    fun resumeMusicForIntro() {
        if (introPausedMusic) {
            introPausedMusic = false
            if (musicLoaded) runCatching { music.play() }
        }
    }

    // ---- 电子书通道 ----

    fun bookPlay() {
        val para = lastPara
        if (para != null && !bookPlaying) {
            bookArmed = false
            runCatching { tts.playFromBookmark(para.chapter, para.block, para.startChar) }
        }
        foreground = UnifiedChannel.BOOK
        publish()
        refreshSession()
    }

    /**
     * App 内从阅读页起播朗读：书已由调用方从指定位置启动，
     * 这里只负责把 armed 的音乐一起带起来（两者都 arm 时双恢复）。
     */
    fun onBookStartedFromApp() {
        bookArmed = false
        if (musicArmed && musicLoaded) {
            musicArmed = false
            runCatching { music.play() }
        }
        foreground = UnifiedChannel.BOOK
        publish()
        refreshSession()
    }

    fun bookStop() {
        if (bookPlaying) {
            bookArmed = true
            runCatching { tts.stop() }
        }
        publish()
        refreshSession()
    }

    fun bookNext() {
        runCatching { tts.next() }
        publish()
        refreshSession()
    }

    fun bookPrevious() {
        runCatching { tts.prev() }
        publish()
        refreshSession()
    }

    // ---- 服务事件 ----

    fun onMusicPlayingChanged(playing: Boolean) {
        musicPlaying = playing
        if (playing) {
            musicLoaded = true
            musicArmed = false
            if (!bookPlaying) foreground = UnifiedChannel.MUSIC
        } else if (musicLoaded) {
            musicArmed = true
        }
        publish()
        refreshSession()
    }

    /** 阅读页底栏亲手停朗读：音乐一起暂停，两路都 arm，等全局播放双恢复。 */
    fun onBookUserStop() {
        if (musicEffectivelyPlaying()) {
            musicArmed = true
            runCatching { music.pause() }
        }
        bookArmed = true
        publish()
        refreshSession()
    }

    /**
     * 外部打断（来电/拔耳机/系统暂停）：快照式双停。
     * @param autoResume true=瞬态，恢复时自动双恢复；false=常停，等用户/系统播放键。
     * @param pauseMusic 音乐侧是否顺手暂停（已被系统压住时传 false，避免破坏自动恢复）。
     */
    fun onExternalInterruption(autoResume: Boolean, pauseMusic: Boolean) {
        if (interrupted) return
        if (!castPlaying && !musicEffectivelyPlaying() && !bookPlaying) return
        interrupted = true
        this.autoResume = autoResume
        if (castPlaying) {
            castArmed = true
            runCatching { container.receiveNowPlaying.setPlaying(false) }
        }
        if (musicEffectivelyPlaying()) {
            musicArmed = true
            if (pauseMusic) runCatching { music.pause() }
        }
        if (bookPlaying) {
            bookArmed = true
            runCatching { tts.stop() }
        }
        publish()
        refreshSession()
    }

    fun onExternalResume() {
        if (!autoResume) return
        autoResume = false
        interrupted = false
        play()
    }

    /** App 要下发音乐队列前：把会话切回音乐 player，书没在播就退出双播态。 */
    fun takeSessionForMusic() {
        if (!bookPlaying) bookArmed = false
        foreground = UnifiedChannel.MUSIC
        publish()
        // 强制切回音乐 player：即使音乐还没装载，后续 setMediaItems 也要落到音乐 ExoPlayer。
        setSessionPlayer(music)
    }

    /** App 内音乐走带键之后：书没在播就把前台切回音乐。 */
    fun onMusicCommand() {
        if (!bookPlaying) foreground = UnifiedChannel.MUSIC
        publish()
        refreshSession()
    }

    fun onTaskRemoved() {
        musicLoaded = false
        musicPlaying = false
        musicArmed = false
        bookPlaying = false
        bookArmed = false
        castActive = false
        castPlaying = false
        castArmed = false
        autoResume = false
        introPausedMusic = false
        snapshot = null
        lastPara = null
        publish()
    }

    fun hasBookmark(): Boolean = lastPara != null

    fun peekPara(): EbookTtsController.Para? = lastPara

    // ---- 内部 ----

    /**
     * 投屏接收状态变化：
     * - 开始投屏只接管“音乐”这一路（避免两份音乐同时出声），听书继续读；
     * - 结束投屏不自动恢复音乐，也不碰听书，避免开始/结束互相打扰；
     * - 全局暂停时记下 castArmed，恢复时和听书/音乐一起回来。
     */
    private fun onCastChanged(snapshot: com.aurora.music.data.cast.ReceiveNowPlaying?) {
        val wasActive = castActive
        castActive = snapshot != null
        castPlaying = snapshot?.isPlaying == true
        if (castActive && !wasActive) {
            castArmed = false
            if (musicEffectivelyPlaying()) {
                musicArmed = true
                runCatching { music.pause() }
            }
            if (!bookPlaying) foreground = UnifiedChannel.CAST
        } else if (!castActive) {
            castArmed = false
            if (foreground == UnifiedChannel.CAST) {
                foreground = if (bookPlaying) UnifiedChannel.BOOK else UnifiedChannel.MUSIC
            }
        }
        publish()
        refreshSession()
    }

    private fun publish() {
        val s = UnifiedState(
            music = ChannelState(musicLoaded, musicPlaying, musicArmed),
            book = ChannelState(true, bookPlaying, bookArmed),
            cast = ChannelState(castActive, castPlaying, castArmed),
            foreground = foreground,
            musicTitle = musicTitle,
            musicArtist = musicArtist,
            musicArtUri = musicArtUri,
            bookTitle = bookTitle,
            bookSection = bookSection,
            bookSentence = lastPara?.readText.orEmpty(),
            bookCover = bookCover,
        )
        _state.value = s
        container.unifiedState.value = s
    }

    /**
     * 会话 player 选择：
     * - 书在播 → 书的影子（通知栏显示书）；
     * - 音乐在播 → 音乐 ExoPlayer；
     * - 都停 → 书有断点且 armed 用书，否则音乐已装载用音乐，否则保持现状（粘滞）。
     */
    private fun refreshSession() {
        bookShadow.setDualMusicPlaying(musicPlaying)
        // 投屏接收是当前唯一在播的内容：会话交给投屏 player，
        // 系统通知/锁屏/耳机键都看到并控制接收端曲目。
        if (container.receiveNowPlaying.snapshot() != null) {
            setSessionPlayer(cast)
            return
        }
        val target: Player? = when {
            bookPlaying -> bookShadow
            musicPlaying -> music
            bookArmed && lastPara != null -> bookShadow
            musicLoaded -> music
            else -> null
        }
        if (target != null) setSessionPlayer(target)
    }
}
