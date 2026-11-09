package com.aurora.music.playback

import androidx.media3.common.MediaItem

/** 统一会话当前呈现的通道。 */
enum class UnifiedChannel { MUSIC, BOOK, CAST }

/** 单个通道的装载/播放/待恢复状态。 */
data class ChannelState(
    val loaded: Boolean = false,
    val playing: Boolean = false,
    /** 暂停前在播：全局播放时恢复。卸载会清掉它。 */
    val armed: Boolean = false,
)

/** 音乐卸载时的现场，用于用户手动重新装载。 */
data class MusicSnapshot(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val speed: Float,
    val pitch: Float,
)

/**
 * 统一播放状态：悬浮窗、通知、UI 都读这一份。
 *
 * 音乐字段在卸载后仍保留最后一首的元数据，方便悬浮窗继续显示专辑封面；
 * 是否真的装载看 [music].loaded。
 */
data class UnifiedState(
    val music: ChannelState = ChannelState(),
    val book: ChannelState = ChannelState(),
    /** 投屏接收音乐：与音乐/听书同级的第三个通道，只受统一暂停/恢复控制。 */
    val cast: ChannelState = ChannelState(),
    val foreground: UnifiedChannel = UnifiedChannel.MUSIC,
    val musicTitle: String = "",
    val musicArtist: String = "",
    val musicArtUri: String = "",
    val bookTitle: String = "",
    val bookSection: String = "",
    val bookSentence: String = "",
    val bookCover: String = "",
)
