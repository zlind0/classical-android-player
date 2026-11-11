package com.aurora.music.data.video

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.Settings
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.util.ArrayList

/**
 * 短视频播放的 libVLC 封装。
 *
 * 为什么用 libVLC 而不是 Media3：字幕渲染、HDR、冷门容器（rmvb/vob/mts…）
 * 都由 VLC 内核处理，客户端不需要自己实现。一个进程只建一个 [LibVLC]，
 * 每个页面按需创建 [MediaPlayer]，页面离开时释放，天然满足“最多 3 个活播放器”。
 */
class VideoEngine(context: Context) {

    private val appContext = context.applicationContext

    val libVlc: LibVLC by lazy {
        LibVLC(
            appContext,
            ArrayList(
                listOf(
                    // 音画同步优先，避免倍速/弱网时爆音
                    "--audio-time-stretch",
                    // 有硬件解码就用（HDR / 4K 依赖它）
                    "--avcodec-hw=any",
                ),
            ),
        )
    }

    fun newPlayer(): MediaPlayer = MediaPlayer(libVlc)

    /** 开始播放一个远端视频；[startMs] 在解码器就绪后由调用方 seek。 */
    fun play(player: MediaPlayer, url: String, muted: Boolean) {
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            // 中继/局域网抖动时的缓冲；按需拉流，不预读整段。
            addOption(":network-caching=2000")
            addOption(":http-reconnect=true")
        }
        player.media = media
        media.release()
        player.volume = if (muted) 0 else 100
        player.play()
    }

    /** 视频真实画面比例（含像素宽高比）；未就绪时返回 0。 */
    fun videoAspect(player: MediaPlayer): Float {
        val media = player.media ?: return 0f
        for (index in 0 until media.trackCount) {
            val track = media.getTrack(index)
            if (track is IMedia.VideoTrack && track.width > 0 && track.height > 0) {
                val sar = if (track.sarNum > 0 && track.sarDen > 0) {
                    track.sarNum.toFloat() / track.sarDen
                } else {
                    1f
                }
                val rotated = track.orientation == IMedia.VideoTrack.Orientation.LeftBottom ||
                    track.orientation == IMedia.VideoTrack.Orientation.RightTop
                val aspect = track.width.toFloat() * sar / track.height
                return if (rotated) 1f / aspect else aspect
            }
        }
        return 0f
    }
}

/**
 * 全屏方向：横屏内容用横屏、竖屏内容用竖屏；系统开了自动旋转时用
 * `SENSOR_LANDSCAPE`，不把用户锁在某个固定的横屏方向。
 */
fun applyVideoOrientation(activity: Activity?, landscape: Boolean, fullscreen: Boolean) {
    activity ?: return
    activity.requestedOrientation = when {
        !fullscreen -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        !landscape -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        autoRotateEnabled(activity) -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

fun autoRotateEnabled(context: Context): Boolean = runCatching {
    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
}.getOrDefault(false)
