package com.aurora.music.data.cast

import com.aurora.music.data.cast.receiver.CastReceiverHub
import com.aurora.music.data.cast.receiver.CastReceiverState

/** 投屏接收的两种来源。优先级：自有协议 > AirPlay（同一时刻只应有一个活跃会话）。 */
enum class ReceiveSource {
    POD,
    AIRPLAY,
    ;

    val label: String
        get() = when (this) {
            POD -> "Pod 局域网串流"
            AIRPLAY -> "AirPlay"
        }
}

/**
 * 「正在播放」的投屏接收快照：悬浮窗、耳机键、系统媒体键都读这一份，
 * 由 [ReceiveNowPlayingController] 统一路由控制。
 */
data class ReceiveNowPlaying(
    val source: ReceiveSource,
    val senderName: String = "",
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUri: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    /** 自有协议："OFF" / "ONE" / "ALL"；AirPlay 恒为 OFF。 */
    val repeat: String = "OFF",
    val liked: Boolean = false,
    val canPlayPause: Boolean = true,
    val canNext: Boolean = true,
    val canPrevious: Boolean = true,
    val canSeek: Boolean = false,
    val canLike: Boolean = false,
    val canShuffleRepeat: Boolean = false,
)

/**
 * 把 Pod 自有协议投屏和 AirPlay 音频接收压成同一个「正在播放」入口。
 *
 * 只处理音频：电子书投屏、AirPlay 视频镜像不是音乐，返回 null，
 * 悬浮窗和媒体键继续走本机播放。
 */
class ReceiveNowPlayingController(
    private val pod: CastReceiverController,
    private val airPlay: CastReceiverHub,
) {
    fun snapshot(): ReceiveNowPlaying? {
        (pod.state.value as? CastReceiveState.Music)?.let { s ->
            return ReceiveNowPlaying(
                source = ReceiveSource.POD,
                senderName = s.senderName,
                id = s.song.id,
                title = s.song.title,
                artist = s.song.artist,
                album = s.song.album,
                artUri = s.song.artworkUrl,
                isPlaying = s.playing,
                positionMs = s.positionMs,
                durationMs = s.durationMs,
                shuffle = s.shuffle,
                repeat = s.repeat,
                liked = s.liked,
                canPlayPause = true,
                canNext = true,
                canPrevious = true,
                canSeek = true,
                canLike = true,
                canShuffleRepeat = true,
            )
        }
        val air = airPlay.state.value as? CastReceiverState.Playing ?: return null
        if (air.video != null) return null
        return ReceiveNowPlaying(
            source = ReceiveSource.AIRPLAY,
            senderName = air.senderName,
            id = air.media.id,
            title = air.media.title,
            artist = air.media.artist,
            album = air.media.album,
            artUri = air.media.artworkUri,
            isPlaying = air.playing,
            positionMs = air.positionMs,
            durationMs = air.durationMs,
            shuffle = false,
            repeat = "OFF",
            liked = false,
            canPlayPause = true,
            canNext = air.capabilities.canNext,
            canPrevious = air.capabilities.canPrevious,
            canSeek = air.capabilities.canSeek,
            canLike = false,
            canShuffleRepeat = false,
        )
    }

    fun togglePlay() {
        (pod.state.value as? CastReceiveState.Music)?.let {
            pod.togglePlay()
            return
        }
        val air = airPlay.state.value as? CastReceiverState.Playing ?: return
        if (air.video != null) return
        if (air.playing) airPlay.pause() else airPlay.play()
    }

    fun setPlaying(playing: Boolean) {
        (pod.state.value as? CastReceiveState.Music)?.let { s ->
            if (s.playing != playing) pod.togglePlay()
            return
        }
        val air = airPlay.state.value as? CastReceiverState.Playing ?: return
        if (air.video != null || air.playing == playing) return
        if (playing) airPlay.play() else airPlay.pause()
    }

    fun next() {
        if (pod.state.value is CastReceiveState.Music) {
            pod.next()
            return
        }
        if (airPlay.state.value is CastReceiverState.Playing) airPlay.next()
    }

    fun previous() {
        if (pod.state.value is CastReceiveState.Music) {
            pod.previous()
            return
        }
        if (airPlay.state.value is CastReceiverState.Playing) airPlay.previous()
    }

    fun seekFraction(fraction: Float) {
        if (pod.state.value is CastReceiveState.Music) {
            pod.seekFraction(fraction, commit = true)
            return
        }
        val s = snapshot() ?: return
        if (!s.canSeek || s.durationMs <= 0L) return
        airPlay.seekTo((s.durationMs * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun toggleLike() {
        if (pod.state.value is CastReceiveState.Music) pod.toggleLike()
    }

    fun toggleShuffle() {
        if (pod.state.value is CastReceiveState.Music) pod.toggleShuffle()
    }

    fun cycleRepeat() {
        if (pod.state.value is CastReceiveState.Music) pod.cycleRepeat()
    }
}
