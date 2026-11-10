package com.aurora.music.ui.player

import com.aurora.music.data.cast.CastReceiveState
import com.aurora.music.data.cast.receiver.CastReceiverState
import com.aurora.music.model.Song
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.RepeatMode

/** 正在播放页的四种来源：同一套页面骨架，只换数据源与动作。 */
enum class NowPlayingMode {
    MUSIC,
    AUDIOBOOK,
    POD_RECEIVE,
    AIRPLAY_RECEIVE,
    ;

    val isReceive: Boolean get() = this == POD_RECEIVE || this == AIRPLAY_RECEIVE
}

/** Pod 自有协议投屏接收：发送端已经给了完整队列和传输状态。 */
fun CastReceiveState.Music.toPlayerUiState(): PlayerUiState = PlayerUiState(
    current = song,
    queue = queue,
    isPlaying = playing,
    positionSec = positionMs / 1000f,
    shuffle = shuffle,
    repeat = when (repeat) {
        "ONE" -> RepeatMode.ONE
        "ALL" -> RepeatMode.ALL
        else -> RepeatMode.OFF
    },
    likedIds = if (liked) setOf(song.id) else emptySet(),
    currentIndex = index,
)

/** AirPlay 音频接收：没有队列和 like/shuffle/repeat，压成单曲状态给统一页面。 */
fun CastReceiverState.Playing.toPlayerUiState(): PlayerUiState {
    val song = Song(
        id = media.id.ifBlank { "airplay-current" },
        title = media.title.ifBlank { "AirPlay" },
        artist = media.artist,
        album = media.album,
        artworkUrl = media.artworkUri,
        durationSec = (durationMs / 1000L).toInt().coerceAtLeast(0),
        // 发送端没带封面时才用 AirPlay 占位图；有封面仍然优先显示真图
        airplayPlaceholder = media.artworkUri.isBlank(),
    )
    return PlayerUiState(
        current = song,
        queue = listOf(song),
        isPlaying = playing,
        positionSec = positionMs / 1000f,
        shuffle = false,
        repeat = RepeatMode.OFF,
        likedIds = emptySet(),
        currentIndex = 0,
    )
}
