package com.aurora.music.ui.video

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.aurora.music.AuroraApplication
import kotlinx.coroutines.delay
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.max
import kotlin.random.Random

/**
 * VerticalPager 的一页：一个 libVLC 播放器 + 一个 VLCVideoLayout 画面。
 *
 * 播放器跟着页面走，离开组合就释放，所以最多只有相邻的 3 页同时存在。
 * - 当前页：有声播放；第一次开始播放时跳到随机位置（短视频的“每次切换都从随机点开始”）。
 * - 下一页：静音预加载 15 秒后暂停；被滑到时取消暂停并恢复音量，接着播。
 * - 其他页：暂停，只留画面。
 */
@Composable
internal fun VideoFeedPage(
    url: String,
    isCurrent: Boolean,
    isPreload: Boolean,
    fill: Boolean,
    loop: Boolean,
    randomStart: Boolean,
    onAspect: (Float) -> Unit,
    onProgress: (positionMs: Long, durationMs: Long, playing: Boolean) -> Unit,
    onEnded: () -> Unit,
    onPlayerChanged: (MediaPlayer?) -> Unit,
) {
    val context = LocalContext.current
    val engine = (context.applicationContext as AuroraApplication).container.videoEngine
    val player = remember(url) { engine.newPlayer() }
    val loopNow by rememberUpdatedState(loop)
    val endedNow by rememberUpdatedState(onEnded)
    val aspectNow by rememberUpdatedState(onAspect)
    val progressNow by rememberUpdatedState(onProgress)
    val currentNow by rememberUpdatedState(isCurrent)
    val preloadNow by rememberUpdatedState(isPreload)
    val randomNow by rememberUpdatedState(randomStart)

    // 随机起点：随机模式（目录右侧的随机按钮）里每个视频只取一次随机位置，
    // 预加载时就可以先跳过去，滑到它时画面不会先从头放两秒再跳。
    // 普通目录顺序播放不动播放位置。
    var randomApplied by remember(url) { mutableStateOf(false) }
    var started by remember(url) { mutableStateOf(false) }
    var positioned by remember(url) { mutableStateOf(false) }

    fun applyRandomStartIfNeeded() {
        if (!randomNow || randomApplied) return
        if (!currentNow && !preloadNow) return
        val length = player.length
        if (length <= 30_000L) return
        randomApplied = true
        player.setTime((length * (0.05f + Random.nextFloat() * 0.55f)).toLong())
    }

    DisposableEffect(player) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    if (!positioned) {
                        positioned = true
                        aspectNow(engine.videoAspect(player))
                    }
                    applyRandomStartIfNeeded()
                    progressNow(player.time, player.length, true)
                }
                MediaPlayer.Event.LengthChanged -> {
                    if (!positioned) aspectNow(engine.videoAspect(player))
                    applyRandomStartIfNeeded()
                    progressNow(player.time, player.length, player.isPlaying)
                }
                MediaPlayer.Event.TimeChanged ->
                    progressNow(player.time, player.length, player.isPlaying)
                MediaPlayer.Event.Paused ->
                    progressNow(player.time, player.length, false)
                MediaPlayer.Event.EndReached -> {
                    if (loopNow) {
                        player.setTime(0)
                        player.play()
                    } else {
                        endedNow()
                    }
                }
                else -> Unit
            }
        }
        player.setEventListener(listener)
        onDispose {
            runCatching { player.setEventListener(null) }
            runCatching { if (!player.isReleased) player.detachViews() }
            runCatching { if (!player.isReleased) player.release() }
            onPlayerChanged(null)
        }
    }

    LaunchedEffect(url, isCurrent, isPreload) {
        when {
            isCurrent -> {
                onPlayerChanged(player)
                if (!started) {
                    started = true
                    engine.play(player, url, muted = false)
                } else {
                    player.volume = 100
                    if (!player.isPlaying) player.play()
                }
                // 预加载过来的页在成为当前页时长度早已就绪，直接补一次随机起点
                applyRandomStartIfNeeded()
            }
            isPreload -> {
                if (!started) {
                    started = true
                    engine.play(player, url, muted = true)
                } else {
                    player.volume = 0
                    if (!player.isPlaying) player.play()
                }
                // 预加载 15 秒；被滑到当前页时这个 effect 会被取消，继续正常播放。
                delay(15_000L)
                if (!isCurrent) runCatching { player.pause() }
            }
            else -> {
                if (started && player.isPlaying) runCatching { player.pause() }
            }
        }
    }

    // 双击缩放：普通比例 ↔ 保持纵横比的居中放大裁切。
    // 注意不能用 SURFACE_FILL：那个是不管原始比例的拉伸铺满，会把画面拉变形。
    LaunchedEffect(fill) {
        if (!player.isReleased) {
            runCatching {
                player.setVideoScale(
                    if (fill) MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                    else MediaPlayer.ScaleType.SURFACE_BEST_FIT,
                )
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            VLCVideoLayout(ctx).also { layout ->
                layout.post {
                    if (!player.isReleased) {
                        runCatching { player.attachViews(layout, null, true, false) }
                    }
                }
            }
        },
    )
}

/** 把毫秒格式化成 `m:ss` / `h:mm:ss`。 */
internal fun formatVideoTime(ms: Long): String {
    val total = max(0L, ms) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
