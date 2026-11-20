package com.aurora.music.data.subtitle

/**
 * 播放页字幕遮罩的开关状态（进程内记忆）。
 * 进出播放页、切书再回来都保持上次的选择，用户不用每次重新点开。
 */
object SubtitleUiMemory {
    var overlayOpen: Boolean = false
}
