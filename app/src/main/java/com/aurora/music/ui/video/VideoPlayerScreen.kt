package com.aurora.music.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.video.VideoFeedState
import com.aurora.music.data.video.applyVideoOrientation
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer

/**
 * 短视频播放页：整页浮层，不占 NavHost 返回栈。
 *
 * 布局（需求 docs/layout.md）：
 * - 视频铺满整屏（标题栏 → 三键导航条都盖住）；
 * - 顶部半透明遮罩主要放进度条，竖屏把字幕/音轨/循环/随机放在进度条下面，
 *   横屏放在进度条右边；
 * - 底部半透明胶囊：上一条 / 播放暂停 / 下一条；
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

    BackHandler { onClose() }

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
            // 顶部遮罩：标题栏 + 进度条
            Column(
                Modifier.fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(top = statusTop)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controlsVisible = false },
            ) {
                if (!fullscreen) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .clickable { onClose() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBackIos,
                                "返回",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            state.current?.displayTitle.orEmpty(),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        )
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .clickable { requestFullscreen() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Fullscreen, "全屏", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }

                val fraction = if (scrubbing) {
                    scrubFraction
                } else if (durationMs > 0) {
                    (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Slider(
                            value = fraction,
                            onValueChange = {
                                scrubbing = true
                                scrubFraction = it
                            },
                            onValueChangeFinished = {
                                currentPlayer?.let { player ->
                                    val length = if (durationMs > 0) durationMs else player.length
                                    if (length > 0) player.setTime((length * scrubFraction).toLong())
                                }
                                scrubbing = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White.copy(alpha = 0.9f),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.fillMaxWidth().height(24.dp),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatVideoTime(if (scrubbing) (durationMs * scrubFraction).toLong() else positionMs), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                            Text(formatVideoTime(durationMs), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                        }
                    }
                    if (landscape) {
                        Spacer(Modifier.width(12.dp))
                        TrackToggles(
                            loop = loop,
                            shuffle = shuffle,
                            hasPlayer = currentPlayer != null,
                            onLoop = { container.videoFeed.setLoop(!loop) },
                            onShuffle = { container.videoFeed.setShuffle(!shuffle) },
                            onSubtitle = { trackMenu = TrackMenu.Subtitle },
                            onAudio = { trackMenu = TrackMenu.Audio },
                        )
                    }
                }
                if (!landscape) {
                    TrackToggles(
                        loop = loop,
                        shuffle = shuffle,
                        hasPlayer = currentPlayer != null,
                        onLoop = { container.videoFeed.setLoop(!loop) },
                        onShuffle = { container.videoFeed.setShuffle(!shuffle) },
                        onSubtitle = { trackMenu = TrackMenu.Subtitle },
                        onAudio = { trackMenu = TrackMenu.Audio },
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }

            // 底部胶囊：上一条 / 播放暂停 / 下一条
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = navBottom + 18.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
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

            if (fullscreen) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = statusTop + 8.dp, end = 12.dp)
                        .size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
                        .clickable { requestFullscreen() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.FullscreenExit, "退出全屏", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }

        // 字幕/音轨菜单
        TrackMenuDropdown(
            menu = trackMenu,
            player = currentPlayer,
            onDismiss = { trackMenu = TrackMenu.None },
        )
    }
}

private enum class TrackMenu { None, Subtitle, Audio }

@Composable
private fun TrackToggles(
    loop: Boolean,
    shuffle: Boolean,
    hasPlayer: Boolean,
    onLoop: () -> Unit,
    onShuffle: () -> Unit,
    onSubtitle: () -> Unit,
    onAudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleIcon(Icons.Filled.Subtitles, "字幕", true, hasPlayer, onSubtitle)
        ToggleIcon(Icons.Filled.Audiotrack, "音轨", true, hasPlayer, onAudio)
        ToggleIcon(Icons.Filled.Repeat, "循环", loop, true, onLoop)
        ToggleIcon(Icons.Filled.Shuffle, "随机", shuffle, true, onShuffle)
    }
}

@Composable
private fun ToggleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(36.dp).clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            label,
            tint = if (active) Color.White else Color.White.copy(alpha = if (enabled) 0.7f else 0.3f),
            modifier = Modifier.size(20.dp),
        )
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
