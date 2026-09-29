package com.aurora.music.data

/** 内置微软离线引擎在统一 TTS 设置中的标识。 */
const val TTS_ENGINE_INTERNAL = "internal"

/**
 * 统一 TTS 设置：电子书听书与歌曲介绍共用同一份存储，两处设置页都是修改入口。
 * - engine: "internal" = 内置微软离线；"" = 系统默认引擎；否则为系统引擎包名。
 * - voice: "" = 自动；内置 = 语音 code；系统 = 语音 name。
 * - volume: 听书音量倍率 0.2~2.0（设置保留全量；系统引擎执行侧钳到 1.0，见 TtsWorker）。
 * - duckOthers: 听书时是否请求 MAY_DUCK 焦点、压低站外音乐（降多少由对方 App 决定）。
 * - ownMusicLevel: 站内音乐在听书时的保留音量 0.05~1.0（PlaybackService 手动压，精确可调）。
 */
data class UnifiedTtsPrefs(
    val engine: String = TTS_ENGINE_INTERNAL,
    val voice: String = "",
    val rate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val duckOthers: Boolean = false,
    val ownMusicLevel: Float = 0.2f,
) {
    val isInternal: Boolean get() = engine == TTS_ENGINE_INTERNAL

    /** 系统引擎包名；内置与系统默认都返回 null（后者表示用系统默认引擎实例）。 */
    val systemEnginePkg: String? get() = if (isInternal || engine.isBlank()) null else engine
}
