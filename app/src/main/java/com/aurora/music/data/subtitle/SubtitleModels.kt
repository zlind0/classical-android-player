package com.aurora.music.data.subtitle

/**
 * VAD 切出来的一个语音片段（章节内相对秒）。
 * [text] 为空表示这段音频被 VAD 判成人声、但 ASR 没出字——片段照样存盘，
 * 表示「这里已经识别过、没有可显示的内容」，回头不用重解。
 */
data class SubtitleSegment(val start: Double, val end: Double, val text: String)

/**
 * 播放页字幕遮罩的开关状态（进程内记忆）。
 * 进出播放页、切书再回来都保持上次的选择，用户不用每次重新点开。
 */
object SubtitleUiMemory {
    var overlayOpen: Boolean = false
}
