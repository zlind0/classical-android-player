package com.aurora.music.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.video.VideoFeedState
import com.aurora.music.data.video.applyVideoOrientation
import com.aurora.music.ui.components.MarqueeText
import com.aurora.music.ui.ios5.Ios5ButtonTone
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GradientIcon
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5Slider
import com.aurora.music.ui.ios5.Ios5SmallButton
import com.aurora.music.ui.player.SleepBubble
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer

/**
 * 短视频播放页：整页浮层，不占 NavHost 返回栈。
 *
 * 布局（需求 docs/layout.md）：
 * - 视频铺满整屏（标题栏 → 三键导航条都盖住）；
 * - 顶部是 iOS5 深色高光玻璃一体面板：第一行蓝色返回键 / marquee 标题 / 全屏键，
 *   第二行进度条；竖屏把字幕/音轨/循环/随机/月亮放在进度条下面，横屏放在右边；
 * - 底部 iOS5 玻璃控制栏：上一条 / 播放暂停 / 下一条（无音量条）；
 * - 点一下显示/隐藏控件，双击切换普通比例 ↔ 居中裁切；
 * - 上下滑切换视频（VerticalPager），每次切换从随机位置开始，下一页预加载 15 秒。
 */
@Composable
fun VideoPlayerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val feed by container.videoFeed.state.collectAsStateWithLifecycle()
    val loop by container.videoFeed.loop.collectAsStateWithLifecycle()
    val shuffle by container.videoFeed.shuffle.collectAsStateWithLifecycle()
    val fill by container.videoFeed.fill.collectAsStateWithLifecycle()
    val state = feed ?: return
    if (state.items.isEmpty()) return

    val activity = remember(context) { context.findActivity() }
    val pagerState = rememberPagerState(initialPage = state.index.coerceAtLeast(0)) { state.items.size }
    val scope = rememberCoroutineScope()

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(0f) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var currentPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var trackMenu by remember { mutableStateOf(TrackMenu.None) }
    val sleepTimer by container.sleepTimer.timer.collectAsStateWithLifecycle()
    var sleepBubble by remember { mutableStateOf(false) }
    var videoSleepDeadline by remember { mutableLongStateOf(0L) }
    val playerNow by rememberUpdatedState(currentPlayer)

    BackHandler {
        if (sleepBubble) sleepBubble = false else onClose()
    }

    // Pager 与播放现场双向同步：滑动改 index；底部按钮改 index 时把 Pager 跟过去。
    LaunchedEffect(pagerState.currentPage) {
        container.videoFeed.setIndex(pagerState.currentPage)
    }
    LaunchedEffect(state.index) {
        if (pagerState.currentPage != state.index) pagerState.animateScrollToPage(state.index)
    }

    // 全屏：横屏内容转横屏（系统自动旋转开着时不锁定方向），竖屏内容保持竖屏。
    DisposableEffect(Unit) {
        onDispose { applyVideoOrientation(activity, landscape = false, fullscreen = false) }
    }
    LaunchedEffect(fullscreen, aspect) {
        applyVideoOrientation(activity, landscape = aspect > 1.05f, fullscreen = fullscreen)
        val controller = activity?.let {
            WindowInsetsControllerCompat(it.window, it.window.decorView)
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 睡眠定时：全局后台只负责音乐/站外媒体，视频这边自己盯到点暂停。
    // 单独存一个 deadline，避免后台到点清 timer 时把暂停协程一起取消。
    LaunchedEffect(sleepTimer?.deadlineElapsed) {
        val latest = sleepTimer?.deadlineElapsed ?: 0L
        if (latest > 0L) {
            videoSleepDeadline = latest
        } else if (videoSleepDeadline > SystemClock.elapsedRealtime() + 1_000L) {
            // 到点前被手动取消：撤掉暂停协程；到点后后台清 timer 则保留，
            // 让已经醒来的暂停协程把视频停下。
            videoSleepDeadline = 0L
        }
    }
    LaunchedEffect(videoSleepDeadline) {
        val deadline = videoSleepDeadline
        if (deadline <= 0L) return@LaunchedEffect
        delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        runCatching { playerNow?.pause() }
        videoSleepDeadline = 0L
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun requestFullscreen() {
        fullscreen = !fullscreen
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight

        // 视频面：点击切换控件，双击切换画面比例
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { container.videoFeed.setFill(!fill) },
                )
            },
        ) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                pageSpacing = 0.dp,
            ) { page ->
                val item = state.items.getOrNull(page)
                if (item == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    val url = remember(item.path, state.deviceId) {
                        container.remoteLibrary.videoStreamUrl(state.deviceId, item.path).orEmpty()
                    }
                    VideoFeedPage(
                        url = url,
                        isCurrent = page == pagerState.currentPage,
                        isPreload = page == pagerState.currentPage + 1,
                        fill = fill,
                        loop = loop,
                        randomStart = state.shuffled,
                        onAspect = { value -> if (page == pagerState.currentPage) aspect = value },
                        onProgress = { pos, dur, isPlaying ->
                            if (page == pagerState.currentPage) {
                                positionMs = pos
                                if (dur > 0) durationMs = dur
                                playing = isPlaying
                            }
                        },
                        onEnded = {
                            if (page == pagerState.currentPage) {
                                if (page < state.items.size - 1) {
                                    container.videoFeed.next()
                                } else if (state.shuffled) {
                                    // 本批播完：补一批新的随机视频接着滑
                                    scope.launch {
                                        val more = container.remoteLibrary.fetchRandomVideos(
                                            state.deviceId, state.rootKey, state.dir, 20,
                                        )
                                        if (more.isNullOrEmpty()) {
                                            container.videoFeed.close()
                                        } else {
                                            container.videoFeed.append(more)
                                            container.videoFeed.next()
                                        }
                                    }
                                } else {
                                    container.videoFeed.close()
                                }
                            }
                        },
                        onPlayerChanged = { player ->
                            if (page == pagerState.currentPage) currentPlayer = player
                        },
                    )
                }
            }
        }

        // ---------------- 控件层 ----------------
        if (controlsVisible) {
            val subtitleActive = (currentPlayer?.spuTrack ?: -1) >= 0
            val audioActive = (currentPlayer?.audioTracks?.size ?: 0) > 1

            // 顶部一体面板：iOS5 深色高光渐变玻璃，标题栏 + 进度条 + 开关在同一块里。
            Column(
                Modifier.fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .ios5VideoGlass()
                    .padding(top = statusTop)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controlsVisible = false },
            ) {
                // 第一行：蓝色返回键 / 标题（超长 marquee）/ 黑色全屏键（右上+左下箭头）。
                // 标题放在整行 Box 的中心，两侧按钮叠加在两端，避免被按钮宽度推偏。
                Box(Modifier.fillMaxWidth().height(44.dp)) {
                    MarqueeText(
                        text = state.current?.displayTitle.orEmpty(),
                        color = Color.White,
                        fontSize = if (landscape) 12.sp else 14.sp,
                        fontFamily = Ios5Sans,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 76.dp),
                    )
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Ios5SmallButton(
                            text = "返回",
                            onClick = onClose,
                            tone = Ios5ButtonTone.Blue,
                        )
                        Ios5SmallButton(
                            text = "",
                            onClick = { requestFullscreen() },
                            tone = Ios5ButtonTone.Black,
                            leading = {
                                Ios5FullscreenGlyph(
                                    expanded = !fullscreen,
                                    tint = Color.White,
                                    description = if (fullscreen) "退出全屏" else "全屏",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }

                if (landscape) {
                    // 横屏：进度条在左，字幕/音轨/循环/随机/月亮在右。
                    Row(
                        Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VideoProgressRow(
                            positionMs = positionMs,
                            durationMs = durationMs,
                            scrubbing = scrubbing,
                            scrubFraction = scrubFraction,
                            onScrub = {
                                scrubbing = true
                                scrubFraction = it
                            },
                            onScrubFinished = {
                                currentPlayer?.let { player ->
                                    val length = if (durationMs > 0) durationMs else player.length
                                    if (length > 0) player.setTime((length * scrubFraction).toLong())
                                }
                                scrubbing = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        VideoTrackToggles(
                            loop = loop,
                            shuffle = shuffle,
                            subtitleActive = subtitleActive,
                            audioActive = audioActive,
                            moonActive = sleepTimer != null,
                            hasPlayer = currentPlayer != null,
                            onLoop = { container.videoFeed.setLoop(!loop) },
                            onShuffle = { container.videoFeed.setShuffle(!shuffle) },
                            onSubtitle = { trackMenu = TrackMenu.Subtitle },
                            onAudio = { trackMenu = TrackMenu.Audio },
                            onMoon = { sleepBubble = true },
                        )
                    }
                } else {
                    // 竖屏：进度条一行，开关一行居中。
                    VideoProgressRow(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        scrubbing = scrubbing,
                        scrubFraction = scrubFraction,
                        onScrub = {
                            scrubbing = true
                            scrubFraction = it
                        },
                        onScrubFinished = {
                            currentPlayer?.let { player ->
                                val length = if (durationMs > 0) durationMs else player.length
                                if (length > 0) player.setTime((length * scrubFraction).toLong())
                            }
                            scrubbing = false
                        },
                    )
                    VideoTrackToggles(
                        loop = loop,
                        shuffle = shuffle,
                        subtitleActive = subtitleActive,
                        audioActive = audioActive,
                        moonActive = sleepTimer != null,
                        hasPlayer = currentPlayer != null,
                        onLoop = { container.videoFeed.setLoop(!loop) },
                        onShuffle = { container.videoFeed.setShuffle(!shuffle) },
                        onSubtitle = { trackMenu = TrackMenu.Subtitle },
                        onAudio = { trackMenu = TrackMenu.Audio },
                        onMoon = { sleepBubble = true },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        evenly = true,
                    )
                }
            }

            // 底部播放控制栏：iOS5 深色高光渐变玻璃，无音量条。
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = navBottom + 16.dp)
                    .widthIn(min = 248.dp)
                    .ios5VideoGlass(RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable {
                    if (pagerState.currentPage > 0) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SkipPrevious, "上一个", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Box(Modifier.size(46.dp).clip(CircleShape).clickable {
                    val player = currentPlayer ?: return@clickable
                    if (player.isPlaying) player.pause() else player.play()
                }, contentAlignment = Alignment.Center) {
                    if (durationMs == 0L && playing) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable {
                    if (pagerState.currentPage < state.items.size - 1) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.SkipNext, "下一个", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }

        // 字幕/音轨菜单
        TrackMenuDropdown(
            menu = trackMenu,
            player = currentPlayer,
            onDismiss = { trackMenu = TrackMenu.None },
        )

        // 睡眠定时气泡（与音乐/听书共用同一个后台）
        if (sleepBubble) {
            SleepBubble(onDismiss = { sleepBubble = false })
        }
    }
}

private enum class TrackMenu { None, Subtitle, Audio }

/** 顶栏/控制栏用的 iOS5 深色玻璃渐变：带一点顶部高光，整体半透明。 */
private val Ios5VideoGlassBrush = Brush.verticalGradient(
    0f to Color(0xFF6B6B6B).copy(alpha = 0.84f),
    0.07f to Color(0xFF3A3A3A).copy(alpha = 0.84f),
    0.50f to Color(0xFF151515).copy(alpha = 0.86f),
    0.52f to Color(0xFF080808).copy(alpha = 0.88f),
    1f to Color(0xFF000000).copy(alpha = 0.82f),
)

/** 时间标签固定宽度：容得下 4 位数分钟 + 2 位数秒（例如 9999:59）。 */
private val VideoTimeLabelWidth = 64.dp

private fun Modifier.ios5VideoGlass(
    shape: Shape = RectangleShape,
): Modifier = this
    .clip(shape)
    .background(Ios5VideoGlassBrush)
    .drawBehind {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f),
                0.10f to Color.White.copy(alpha = 0.05f),
                0.50f to Color.Transparent,
            ),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.24f),
            start = Offset(0f, 1f),
            end = Offset(size.width, 1f),
            strokeWidth = 1.dp.toPx(),
        )
    }
    .border(1.dp, Color.Black.copy(alpha = 0.65f), shape)

@Composable
private fun VideoProgressRow(
    positionMs: Long,
    durationMs: Long,
    scrubbing: Boolean,
    scrubFraction: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = when {
        scrubbing -> scrubFraction
        durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val shownPosition = if (scrubbing) (durationMs * scrubFraction).toLong() else positionMs
    val remaining = (durationMs - shownPosition).coerceAtLeast(0L)
    val timeStyle = TextStyle(
        color = Color.White.copy(alpha = 0.92f),
        fontSize = 12.sp,
        fontFamily = Ios5Sans,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
        shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f)),
        textAlign = TextAlign.End,
    )

    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatVideoTime(shownPosition),
            style = timeStyle,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(VideoTimeLabelWidth),
        )
        Ios5Slider(
            value = fraction,
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            compact = true,
        )
        Text(
            "-${formatVideoTime(remaining)}",
            style = timeStyle.copy(textAlign = TextAlign.Start),
            maxLines = 1,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(VideoTimeLabelWidth),
        )
    }
}

@Composable
private fun VideoTrackToggles(
    loop: Boolean,
    shuffle: Boolean,
    subtitleActive: Boolean,
    audioActive: Boolean,
    moonActive: Boolean,
    hasPlayer: Boolean,
    onLoop: () -> Unit,
    onShuffle: () -> Unit,
    onSubtitle: () -> Unit,
    onAudio: () -> Unit,
    onMoon: () -> Unit,
    modifier: Modifier = Modifier,
    evenly: Boolean = false,
) {
    Row(
        modifier.padding(horizontal = if (evenly) 0.dp else 8.dp, vertical = 2.dp),
        horizontalArrangement = if (evenly) {
            Arrangement.SpaceEvenly
        } else {
            Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoToggle(Icons.Filled.Subtitles, "字幕", subtitleActive, hasPlayer, onSubtitle)
        VideoToggle(Icons.Filled.Audiotrack, "音轨", audioActive, hasPlayer, onAudio)
        VideoToggle(Icons.Filled.Repeat, "循环", loop, true, onLoop)
        VideoToggle(Icons.Filled.Shuffle, "随机", shuffle, true, onShuffle)
        VideoToggle(Icons.Filled.Bedtime, "睡眠定时", moonActive, true, onMoon)
    }
}

/** 开关键：不垫任何背景色；激活时只把图标本身换成主页 tab 同款浅蓝渐变。 */
@Composable
private fun VideoToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(38.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Ios5GradientIcon(
                icon = icon,
                contentDescription = label,
                top = Ios5Colors.TabIconOnTop,
                bottom = Ios5Colors.TabIconOnBottom,
                size = 21.dp,
            )
        } else {
            Icon(
                icon,
                label,
                tint = Color.White.copy(alpha = if (enabled) 0.72f else 0.3f),
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

/**
 * 示例图里的全屏图标：右上 + 左下两个斜向箭头。
 * [expanded] = true 时箭头朝外（进入全屏），false 时箭头朝内（退出全屏）。
 */
@Composable
private fun Ios5FullscreenGlyph(
    expanded: Boolean,
    tint: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        val inset = w * 0.10f
        val gap = w * 0.08f
        val arm = w * 0.26f
        val stroke = w * 0.11f
        val topRightOuter = Offset(w - inset, inset)
        val topRightInner = Offset(w / 2f + gap, h / 2f - gap)
        val bottomLeftOuter = Offset(inset, h - inset)
        val bottomLeftInner = Offset(w / 2f - gap, h / 2f + gap)

        drawLine(tint, topRightInner, topRightOuter, stroke, StrokeCap.Round)
        drawLine(tint, bottomLeftInner, bottomLeftOuter, stroke, StrokeCap.Round)

        fun barb(tip: Offset, dx: Float, dy: Float) {
            drawLine(tint, tip, Offset(tip.x + dx, tip.y), stroke, StrokeCap.Round)
            drawLine(tint, tip, Offset(tip.x, tip.y + dy), stroke, StrokeCap.Round)
        }

        if (expanded) {
            barb(topRightOuter, -arm, arm)
            barb(bottomLeftOuter, arm, -arm)
        } else {
            barb(topRightInner, arm, -arm)
            barb(bottomLeftInner, -arm, arm)
        }
    }
}

@Composable
private fun TrackMenuDropdown(
    menu: TrackMenu,
    player: MediaPlayer?,
    onDismiss: () -> Unit,
) {
    val tracks = remember(menu, player) {
        when (menu) {
            TrackMenu.None -> emptyList()
            TrackMenu.Subtitle -> player?.spuTracks?.toList().orEmpty()
            TrackMenu.Audio -> player?.audioTracks?.toList().orEmpty()
        }
    }
    DropdownMenu(
        expanded = menu != TrackMenu.None && tracks.isNotEmpty(),
        onDismissRequest = onDismiss,
    ) {
        tracks.forEach { track ->
            DropdownMenuItem(
                text = { Text(track.name ?: track.id.toString()) },
                onClick = {
                    when (menu) {
                        TrackMenu.Subtitle -> player?.setSpuTrack(track.id)
                        TrackMenu.Audio -> player?.setAudioTrack(track.id)
                        TrackMenu.None -> Unit
                    }
                    onDismiss()
                },
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
