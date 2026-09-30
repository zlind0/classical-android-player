package com.aurora.music.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.aurora.music.data.AppContainer
import com.aurora.music.data.ebook.EbookTtsController
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 双播时系统命令对音乐的操作预留口（用户后面再实现）。
 *
 * direction: +1 = 下一曲，-1 = 上一曲。
 * 返回 true 表示已处理（不再动书），false 表示未处理（只动书）。
 */
interface DualMusicControl {
    fun isMusicPlaying(): Boolean

    /** 双播时系统上/下一曲切音乐：默认不处理，调用方只动书。 */
    fun switchMusicFromSystem(direction: Int): Boolean = false
}

/** 统一会话当前对外呈现的模式：单服务单会话，靠换 player 实现。 */
enum class UnifiedSessionMode {
    /** 只播音乐：会话 player = 音乐 ExoPlayer，一切与原来一样。 */
    MUSIC,

    /** 只播书：会话 player = [UnifiedPlayer]（无 dualControl），语义与原来听书会话一致。 */
    BOOK,

    /** 双播：会话 player = [UnifiedPlayer]（带 dualControl），上/下曲只动书，暂停/播放双动。 */
    DUAL,
}

/**
 * 会话模式纯函数（可单测）：
 * - 双播一旦进入（dualArmed）就保持 DUAL，直到用户亲手停掉其中一路；
 *   外部暂停（耳机/来电/系统暂停键）只停播放，不退出 DUAL，这样系统播放键才能双恢复。
 * - 非 DUAL 时谁在播就呈现谁。
 */
fun computeUnifiedMode(
    musicPlaying: Boolean,
    bookPlaying: Boolean,
    dualArmed: Boolean,
): UnifiedSessionMode = when {
    dualArmed && (musicPlaying || bookPlaying) -> UnifiedSessionMode.DUAL
    // 双播被系统/外部暂停后两路都停，但 dualArmed 还在：仍呈现 DUAL（暂停态，播放键双恢复）。
    dualArmed -> UnifiedSessionMode.DUAL
    bookPlaying -> UnifiedSessionMode.BOOK
    else -> UnifiedSessionMode.MUSIC
}

/** 系统上/下曲路由纯函数（可单测）：双播只动书（音乐切换走 [DualMusicControl] 预留口）。 */
enum class SystemNavTarget { BOOK, MUSIC }

fun routeSystemNav(
    dualArmed: Boolean,
    musicPlaying: Boolean,
    bookActive: Boolean,
): SystemNavTarget = when {
    !bookActive -> SystemNavTarget.MUSIC
    dualArmed && musicPlaying -> SystemNavTarget.BOOK
    !musicPlaying -> SystemNavTarget.BOOK
    else -> SystemNavTarget.MUSIC
}

/**
 * 统一听书会话播放器：单服务单会话方案里 BOOK / DUAL 两种模式的会话 player。
 *
 * - 本体是影子播放器，不发声：状态镜像 + 命令转接 App 作用域的 [EbookTtsController]
 *  （prev/next/stop/playFrom），合成与播放逻辑一律不碰。
 * - 听书没有暂停，只有播放/停止：系统下达暂停一律走 [EbookTtsController.stop]，
 *   并向系统回報已暂停；系统下达播放，从最近一次停止的段落继续。
 * - DUAL 模式（[dualControl] 非空）额外联动音乐：
 *   暂停/停止时先调 [onDualSystemPause]（服务负责快照 + 停音乐），再停书；
 *   播放时先调 [onDualSystemResume]（服务负责恢复音乐），再从断点续书；
 *   上/下曲只调书的 prev/next，音乐是否联动走 [DualMusicControl.switchMusicFromSystem] 预留口。
 * - 通知栏标题用当前段落预览、作者用当前所在的目录标题（与目录页高亮同口径）。
 *
 * 时间线用「三窗占位」（prev/cur/next 共用同一份元数据、当前窗恒为中间）：
 * 单窗时间线下 seekToNext/Previous 到头会被框架吞掉，有三窗才能让通知栏与
 * 耳机的上一节/下一节稳定到达 [handleSeek]。
 */
@UnstableApi
class UnifiedPlayer(
    looper: Looper,
    private val container: AppContainer,
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tts: EbookTtsController get() = container.ebookTts

    /** DUAL 模式的音乐联动口；BOOK 模式为 null。由服务在模式切换时设置。 */
    @Volatile var dualControl: DualMusicControl? = null

    /** DUAL 模式系统暂停：服务先快照 + 停音乐，本播放器再停书。BOOK 模式不设置。 */
    @Volatile var onDualSystemPause: (() -> Unit)? = null

    /** DUAL 模式系统播放：服务先恢复音乐，本播放器再从断点续书。BOOK 模式不设置。 */
    @Volatile var onDualSystemResume: (() -> Unit)? = null

    /** DUAL 模式下音乐侧是否在播（服务推送），用于会话 playing 态展示。 */
    @Volatile private var dualMusicPlaying = false

    fun setDualMusicPlaying(playing: Boolean) {
        if (dualMusicPlaying == playing) return
        dualMusicPlaying = playing
        invalidateState()
    }

    // 以下只在主线程读写（SimpleBasePlayer 要求应用线程）。
    private var bookPlaying = false

    /** 最近一次非空的朗读位置：stop() 会清掉控制器的 position，这里留着给「继续播放」用。 */
    private var lastPara: EbookTtsController.Para? = null

    /** 与 lastPara 同期的部分标题（通知栏作者栏用），切书时一起丢掉。 */
    private var lastSection = ""
    private var lastParaBookPath: String? = null
    private var topPath: String? = null
    private var bookTitle = ""
    private var bookCover = ""
    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        scope.launch {
            tts.playing.collect { p ->
                bookPlaying = p
                invalidateState()
            }
        }
        scope.launch {
            tts.position.collect { pos ->
                if (pos != null) {
                    lastPara = pos
                    if (lastParaBookPath == null) lastParaBookPath = topPath
                }
                invalidateState()
            }
        }
        scope.launch {
            tts.sectionTitle.collect { s ->
                lastSection = s
                invalidateState()
            }
        }
        scope.launch {
            container.ebookStore.recents.collect { recents ->
                val top = recents.firstOrNull()
                topPath = top?.path
                bookTitle = top?.displayTitle.orEmpty()
                bookCover = top?.coverPath.orEmpty()
                // 切书：丢掉上一本书的断点，避免按播放读错书。
                if (lastPara != null) {
                    if (lastParaBookPath == null) {
                        lastParaBookPath = topPath
                    } else if (topPath != null && topPath != lastParaBookPath) {
                        lastPara = null
                        lastSection = ""
                        lastParaBookPath = null
                    }
                }
                invalidateState()
            }
        }
    }

    fun hasContent(): Boolean = hasItem()

    /** 断点是否还在（外部暂停快照/系统播放双恢复用）。 */
    fun hasBookmark(): Boolean = lastPara != null

    private fun hasItem(): Boolean = lastPara != null || bookPlaying

    private fun titleText(): String =
        lastPara?.readText?.trim().orEmpty().take(80)
            .ifBlank { bookTitle }.ifBlank { "听书" }

    private fun artistText(): String =
        lastSection.ifBlank { bookTitle }.ifBlank { "听书" }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .build()
        // DUAL 下任一路在播都算播；暂停态通知栏显示播放键，按下走双恢复。
        val playing = bookPlaying || (dualControl != null && dualMusicPlaying)
        val b = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(false)
        if (!hasItem()) {
            // 从未朗读过：空时间线，不弹通知，但会话仍在。
            b.setPlaybackState(Player.STATE_IDLE)
            return b.build()
        }
        b.setPlaybackState(Player.STATE_READY)
        val item = MediaItem.Builder()
            .setMediaId(MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(titleText())
                    .setArtist(artistText())
                    .setAlbumTitle(bookTitle)
                    .setIsPlayable(true)
                    .apply { if (bookCover.isNotBlank()) setArtworkUri(Uri.parse(bookCover)) }
                    .build(),
            )
            .build()
        // 三窗占位：三窗元数据完全一致，当前窗恒为中间窗。
        val data = listOf(UID_PREV, UID_CUR, UID_NEXT).map { uid ->
            MediaItemData.Builder(uid)
                .setMediaItem(item)
                .setDefaultPositionUs(0)
                .setDurationUs(C.TIME_UNSET)
                .build()
        }
        b.setPlaylist(data)
            .setCurrentMediaItemIndex(INDEX_CUR)
            .setContentPositionMs(0)
        return b.build()
    }

    // 暂停即停止：调 stop()，状态保持 paused（通知栏变回播放键）。
    // DUAL 下先让服务快照 + 停音乐（快照先行，服务侧才能区分用户单停）。
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val dual = dualControl
        if (playWhenReady) {
            if (dual != null) {
                onDualSystemResume?.invoke()
                val p = lastPara
                if (p != null && !tts.playing.value) {
                    bookPlaying = true
                    tts.playFrom(p.chapter, p.block, p.startChar)
                } else if (tts.playing.value) {
                    bookPlaying = true
                }
            } else {
                if (tts.playing.value) {
                    bookPlaying = true
                } else {
                    val p = lastPara
                    if (p != null) {
                        bookPlaying = true
                        tts.playFrom(p.chapter, p.block, p.startChar)
                    }
                }
            }
        } else {
            if (dual != null) onDualSystemPause?.invoke()
            bookPlaying = false
            tts.stop()
        }
        return Futures.immediateFuture(null)
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleStop(): ListenableFuture<*> {
        if (dualControl != null) onDualSystemPause?.invoke()
        bookPlaying = false
        tts.stop()
        return Futures.immediateFuture(null)
    }

    // 上一节 / 下一节：原样转给控制器（未朗读时控制器内部直接返回，与 App 内一致）。
    // DUAL 下音乐是否联动走预留口，默认只动书。
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val dual = dualControl
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                if (dual?.switchMusicFromSystem(+1) != true) tts.next()
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                if (dual?.switchMusicFromSystem(-1) != true) tts.prev()
            }
        }
        return Futures.immediateFuture(null)
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateFuture(null)
    }

    companion object {
        private const val MEDIA_ID = "ebook_tts"
        private const val UID_PREV = "ebook_tts_prev"
        private const val UID_CUR = "ebook_tts_cur"
        private const val UID_NEXT = "ebook_tts_next"
        private const val INDEX_CUR = 1
    }
}
