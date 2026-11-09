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
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 投屏接收期间的系统会话 player：把 Pod / AirPlay 接收包装成标准 Player，
 * 让系统通知、锁屏、蓝牙/耳机都能看到「正在播放」的投屏曲目并直接控制。
 *
 * 状态只读 [AppContainer.receiveNowPlaying]，不碰音频输出；接收开始/结束由
 * [NeoUnifiedPlayer] 负责把会话 player 切进来/切出去。
 */
@UnstableApi
class CastSessionPlayer(
    looper: Looper,
    private val container: AppContainer,
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attached = false

    /**
     * 系统走带键先交给 [NeoUnifiedPlayer]：暂停/播放是“全局”语义，
     * 要同时作用于投屏、听书和音乐。为空时退回只控制投屏接收本身。
     */
    var onSetPlayingRequest: ((Boolean) -> Unit)? = null
    var onStopRequest: (() -> Unit)? = null
    var onNextRequest: (() -> Unit)? = null
    var onPreviousRequest: (() -> Unit)? = null

    fun attach() {
        if (attached) return
        attached = true
        scope.launch { container.castReceiver.state.collect { invalidateState() } }
        scope.launch { container.castHub.state.collect { invalidateState() } }
    }

    override fun getState(): State {
        val s = container.receiveNowPlaying.snapshot()
        val canNext = s?.canNext != false
        val canPrev = s?.canPrevious != false
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .apply {
                if (canNext) {
                    add(Player.COMMAND_SEEK_TO_NEXT)
                    add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                }
                if (canPrev) {
                    add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                if (s?.canSeek == true) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }
            .build()
        val repeat = when (s?.repeat?.uppercase()) {
            "ONE" -> Player.REPEAT_MODE_ONE
            "ALL" -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        val b = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(s?.isPlaying == true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(s?.shuffle == true)
        if (s == null) {
            // 没有投屏接收：空时间线；正常路径下会话 player 会切回音乐/听书。
            b.setPlaybackState(Player.STATE_IDLE)
            return b.build()
        }
        b.setPlaybackState(Player.STATE_READY)
        val item = MediaItem.Builder()
            .setMediaId("cast:${s.source.name}:${s.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(s.title.ifBlank { s.source.label })
                    .setArtist(s.artist.ifBlank { s.senderName })
                    .setAlbumTitle(s.album)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply { s.artUri.toUriOrNull()?.let { setArtworkUri(it) } }
                    .build(),
            )
            .build()
        val durationUs = if (s.durationMs > 0L) s.durationMs * 1000L else C.TIME_UNSET
        b.setPlaylist(
            listOf(
                MediaItemData.Builder(ITEM_UID)
                    .setMediaItem(item)
                    .setDurationUs(durationUs)
                    .build(),
            ),
        )
            .setCurrentMediaItemIndex(0)
        // 位置必须落在 [0, duration]：发送端偶尔会上报越界进度，
        // 直接塞进 State.Builder 会让媒体通知/进度条拿到非法位置。
        val baseMs = s.positionMs.coerceAtLeast(0L)
            .let { if (s.durationMs > 0L) it.coerceAtMost(s.durationMs) else it }
        if (s.isPlaying) {
            val extrapolating = PositionSupplier.getExtrapolating(baseMs, 1f)
            b.setContentPositionMs(
                PositionSupplier {
                    val pos = extrapolating.get()
                    if (s.durationMs > 0L) pos.coerceIn(0L, s.durationMs) else pos.coerceAtLeast(0L)
                },
            )
        } else {
            b.setContentPositionMs(baseMs)
        }
        return b.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val delegate = onSetPlayingRequest
        if (delegate != null) delegate(playWhenReady)
        else container.receiveNowPlaying.setPlaying(playWhenReady)
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleStop(): ListenableFuture<*> {
        val delegate = onStopRequest
        if (delegate != null) delegate()
        else container.receiveNowPlaying.setPlaying(false)
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                val delegate = onNextRequest
                if (delegate != null) delegate() else container.receiveNowPlaying.next()
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                val delegate = onPreviousRequest
                if (delegate != null) delegate() else container.receiveNowPlaying.previous()
            }
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                val s = container.receiveNowPlaying.snapshot() ?: return Futures.immediateFuture(null)
                if (s.durationMs > 0L) {
                    container.receiveNowPlaying.seekFraction(positionMs.toFloat() / s.durationMs)
                }
            }
        }
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateFuture(null)
    }

    private fun String.toUriOrNull(): Uri? {
        val raw = trim()
        if (raw.isEmpty()) return null
        return runCatching {
            if (raw.startsWith("/")) Uri.fromFile(java.io.File(raw)) else Uri.parse(raw)
        }.getOrNull()
    }

    private companion object {
        const val ITEM_UID = "cast_now_playing"
    }
}
