package com.aurora.music.ui.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    /** 竖屏放大时允许的最大裁切比例（宽/高）；0 表示不限制（横屏）。 */
    maxAspect: Float,
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
    val fillNow by rememberUpdatedState(fill)

    // 随机起点：随机模式（目录右侧的随机按钮）里每个视频只取一次随机位置，
    // 预加载时就可以先跳过去，滑到它时画面不会先从头放两秒再跳。
    // 普通目录顺序播放不动播放位置。
    var randomApplied by remember(url) { mutableStateOf(false) }
    var started by remember(url) { mutableStateOf(false) }
    var positioned by remember(url) { mutableStateOf(false) }
    var videoLayout by remember(url) { mutableStateOf<VLCVideoLayout?>(null) }
    // 本页视频自身的宽高比；和下面 maxAspect 一起决定放大时画面盒子的比例。
    var videoAspect by remember(url) { mutableFloatStateOf(0f) }

    /**
     * 双击缩放/上次退出时保存的画面比例。
     *
     * 关键点是它不能只在 [fill] 变化时落一次：播放器刚创建时视频输出（vout）和视频尺寸都还没就绪，
     * 这时 `setVideoScale` 是空操作；等 vout 建立后比例又会被 libVLC 重置回默认。
     * 所以每次 vout 建立（Vout/Playing/LengthChanged）以及重新 attach 之后都要再落一次。
     */
    fun applyVideoScale() {
        if (player.isReleased) return
        runCatching {
            player.setVideoScale(
                if (fillNow) MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                else MediaPlayer.ScaleType.SURFACE_BEST_FIT,
            )
        }
    }

    /**
     * 画面被系统回收（切后台、离屏页的 SurfaceView 被销毁）后，libVLC 会把 views 整个 detach，
     * 回到前台/重新可见时不会自动挂回去，表现就是黑屏或只剩声音。这里在需要时补一次 attach。
     */
    fun ensureViewsAttached() {
        val layout = videoLayout ?: return
        if (player.isReleased) return
        if (player.vlcVout.areViewsAttached()) return
        runCatching { player.attachViews(layout, null, true, false) }
        // 新 attach 出来的 VideoHelper 默认是 BEST_FIT，补一次当前的缩放。
        applyVideoScale()
    }

    /**
     * 有些文件 libVLC 会把默认音轨留在 -1（禁用），表现就是画面正常但完全没声音。
     * 这里在播放开始/跳转后兜底选上第一条真实音轨。
     */
    fun ensureAudioTrackSelected() {
        if (player.isReleased) return
        if (player.audioTrack >= 0) return
        val first = player.audioTracks?.firstOrNull { it.id >= 0 } ?: return
        runCatching { player.audioTrack = first.id }
    }

    fun applyRandomStartIfNeeded() {
        if (!randomNow || randomApplied) return
        if (!currentNow && !preloadNow) return
        val length = player.length
        if (length <= 30_000L) return
        randomApplied = true
        player.setTime((length * (0.05f + Random.nextFloat() * 0.55f)).toLong())
        // 跳转后音轨可能被重置，补一次选择。
        ensureAudioTrackSelected()
    }

    DisposableEffect(player) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    val aspect = engine.videoAspect(player)
                    if (aspect > 0f) videoAspect = aspect
                    if (!positioned) {
                        positioned = true
                        aspectNow(aspect)
                    }
                    ensureAudioTrackSelected()
                    // 音量在音频输出真正建立后再落一次，避免预加载静音后恢复时偶发没声音。
                    when {
                        currentNow -> player.volume = 100
                        preloadNow -> player.volume = 0
                    }
                    applyRandomStartIfNeeded()
                    // vout 建立后 libVLC 会重置画面比例，这里补一次。
                    applyVideoScale()
                    progressNow(player.time, player.length, true)
                }
                MediaPlayer.Event.LengthChanged -> {
                    val aspect = engine.videoAspect(player)
                    if (aspect > 0f) videoAspect = aspect
                    if (!positioned) aspectNow(aspect)
                    applyRandomStartIfNeeded()
                    // 视频尺寸已知后 `setVideoScale` 才会走 Android View 布局这条路。
                    applyVideoScale()
                    progressNow(player.time, player.length, player.isPlaying)
                }
                MediaPlayer.Event.Vout -> applyVideoScale()
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
                ensureViewsAttached()
                if (!started) {
                    started = true
                    engine.play(player, url, muted = false)
                } else {
                    if (!player.isPlaying) player.play()
                    player.volume = 100
                }
                // 预加载过来的页在成为当前页时长度早已就绪，直接补一次随机起点
                applyRandomStartIfNeeded()
                ensureAudioTrackSelected()
            }
            isPreload -> {
                ensureViewsAttached()
                if (!started) {
                    started = true
                    engine.play(player, url, muted = true)
                } else {
                    if (!player.isPlaying) player.play()
                    player.volume = 0
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

    // 切后台再回来：SurfaceView 的 surface 被销毁后 libVLC 不会自己重新挂载，这里补挂。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) ensureViewsAttached()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 双击缩放：普通比例 ↔ 保持纵横比的居中放大裁切。
    // 注意不能用 SURFACE_FILL：那个是不管原始比例的拉伸铺满，会把画面拉变形。
    LaunchedEffect(fill) {
        applyVideoScale()
    }

    // 竖屏放大时把画面限制在一个居中的盒子里：
    // 盒子比例 = min(视频自身比例, 用户选择的最大比例)。
    // 横屏视频（比例更大）会裁左右；竖屏视频（比例更小）盒子等于视频比例，绝不裁上下。
    val videoBoxAspect = if (fill && maxAspect > 0f && videoAspect > 0f) {
        minOf(videoAspect, maxAspect)
    } else {
        0f
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = if (videoBoxAspect > 0f) {
                Modifier.aspectRatio(videoBoxAspect)
            } else {
                Modifier.fillMaxSize()
            },
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    videoLayout = layout
                    layout.post { ensureViewsAttached() }
                }
            },
            onRelease = { layout ->
                if (videoLayout === layout) videoLayout = null
            },
        )
    }
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
