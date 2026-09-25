package com.aurora.music.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.aurora.music.AuroraApplication
import com.aurora.music.data.LibrarySource
import com.aurora.music.data.lookupMergedTitle
import com.aurora.music.data.mergeEnabledFor
import com.aurora.music.model.Song
import com.aurora.music.ui.components.MarqueeText
import com.aurora.music.ui.components.TrackArtwork
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5Slider
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.RepeatMode
import kotlin.math.roundToInt

private val iPodBlack = Brush.verticalGradient(
    0f to Color(0xFF3D434C),
    1f to Color(0xFF14161B),
)
private val iPodGray = Brush.verticalGradient(
    0f to Color(0xFFE8EAEE),
    1f to Color(0xFFC6CBD3),
)

// ---------------- Mini 条（Tab 栏上方常驻，点按翻转进播放页） ----------------

@Composable
fun Ios5MiniStrip(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
) {
    val song = state.current
    Row(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF7F8FA), Color(0xFFDDE1E7)),
                ),
            )
            .clickable(onClick = onExpand)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtwork(song, Modifier.size(38.dp), corner = 8.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title.ifBlank { "未在播放" },
                fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1D22), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = Ios5Sans,
            )
            val sub = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" — ")
            if (sub.isNotBlank()) {
                Text(
                    sub, fontSize = 11.sp, lineHeight = 13.sp, color = Color(0xFF6B7280),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Ios5Sans,
                )
            }
        }
        Icon(
            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (state.isPlaying) "暂停" else "播放",
            tint = Color(0xFF0A60D6),
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onTogglePlay).padding(8.dp),
        )
        Icon(
            Icons.Filled.SkipNext, "下一首", tint = Color(0xFF0A60D6),
            modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onNext).padding(8.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFD9DCE1)))
}

// ---------------- iPod 式整页播放器（翻转进入） ----------------

// 合并标题共用逻辑：竖屏整页与横向侧栏都查同一份预计算表
@Composable
fun rememberMergedTitle(song: Song): Pair<String, String>? {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val source by container.librarySource.collectAsStateWithLifecycle(initialValue = LibrarySource.MEDIastore)
    val roots by container.musicRoots.roots.collectAsStateWithLifecycle(initialValue = emptyList())
    val libMerges by container.localLibrary.albumMerges.collectAsStateWithLifecycle()
    val fileMerges by container.musicRoots.fileMerges.collectAsStateWithLifecycle()
    return remember(song.id, song.albumId, libMerges, fileMerges, roots, source) {
        if (source == LibrarySource.FILE) {
            val tracks = container.musicRoots.albumTracksSorted(song.albumId)
            if (tracks.isEmpty() || !mergeEnabledFor(roots, tracks)) null
            else lookupMergedTitle(fileMerges[song.albumId], tracks, song.id)
        } else {
            val tracks = container.localLibrary.albumTracksSorted(song.albumId)
            if (tracks.isEmpty()) null
            else lookupMergedTitle(libMerges[song.albumId], tracks, song.id)
        }
    }
}

@Composable
fun Ios5PlayerPage(
    state: PlayerUiState,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val song = state.current
    val context = LocalContext.current

    // 黑顶栏下状态栏图标切白，离开还原
    DisposableEffect(Unit) {
        val window = (context as Activity).window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val prev = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = prev }
    }

    val audio = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val volMax = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    LaunchedEffect(state.hasTrack) {
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
    }

    val remaining = (state.durationSec - state.positionSec.toInt()).coerceAtLeast(0)
    val posLabel = "${state.currentIndex + 1} of ${state.queue.size.coerceAtLeast(1)}"

    // 合并标题：扫描预计算表 + 内存曲目表同步查出，无 IO、无协程、无分词；
    // 两栈各查各的表（MEDIastore 查 localLibrary，FILE 查 musicRoots），永不串台
    val merged = rememberMergedTitle(song)

    BoxWithConstraints(
        // 根吃掉所有点按：背景不挡触摸，没有这层，点封面会漏到后面内容面的列表行上导致切歌
        Modifier.fillMaxSize().background(Color(0xFFF4F4F6))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
    ) {
        // 精确分配：三块最小高度先留出来，封面取能放下的最大正方形，
        // 剩下的 leftover 才是真富余，40% 给顶栏（三等分），60% 给底栏。
        // 等式恒成立：top + gray + cover + bottom = H，永远不溢出不留缝
        // 标题区高度锁定：单行/大小标题两行共用同一固定区域，不随 merged 变化，
        // 其他区域（封面/灰条/底栏）仍按 leftover 自适应，不固定。
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // 标题文本区固定高度：从艺人行(44→36)与底部空隙(8→4)省出 12dp 全给标题区，
        // 单/双行共用同一高度，标题栏不随 merged 变化。36(艺人行) + 46(标题区) + 4 + 1(分割线) = 87
        val titleAreaH = 46.dp
        val topBase = statusTop + 87.dp
        val grayBase = 100.dp
        val bottomBase = 144.dp + navBottom
        val coverSide = minOf(maxWidth, maxHeight - topBase - grayBase - bottomBase).coerceAtLeast(0.dp)
        val leftover = (maxHeight - topBase - grayBase - coverSide - bottomBase).coerceAtLeast(0.dp)
        val topExtra = leftover * 0.4f
        val bottomExtra = leftover - topExtra
        val third = topExtra / 3f
        Column(Modifier.fillMaxSize()) {
        // 黑顶栏：返回 | 艺人 | 队列 + 标题区（合并时大/小标题两行占标题位）
        Column(
            Modifier.fillMaxWidth()
                .background(iPodBlack)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + third),
        ) {
            // 第一行：返回 | 艺人 | 队列（同高对齐，永远显示歌手；行高压到刚好包住 36dp 按钮）
            Row(
                Modifier.fillMaxWidth().height(36.dp + third).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IpodBarButton(onClick = onCollapse) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "返回", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Text(
                    song.artist.ifBlank { " " },
                    color = Color(0xFF9AA0AB),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Ios5Sans,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IpodBarButton(onClick = onOpenQueue) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "队列", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            // 标题区：固定高度锁定区域，单行时垂直居中，两行时压缩塞进同一空间。
            // 只优化原来标题的空间：艺人行/底边距不动，两行靠压行高+去间距塞下。
            Box(
                Modifier.fillMaxWidth().height(titleAreaH).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (merged != null) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MarqueeText(
                            merged.first,
                            color = Color.White,
                            fontSize = 15.sp, lineHeight = 19.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Ios5Sans,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(2.dp))
                        MarqueeText(
                            merged.second.ifBlank { song.title },
                            color = Color(0xFFB9BEC7),
                            fontSize = 12.sp, lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Ios5Sans,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    MarqueeText(
                        song.title.ifBlank { "未在播放" },
                        color = Color.White, fontSize = 19.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(4.dp + third))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
        }

        // 灰条： scrub + 序号 + 循环/喜欢/随机（上松下紧）
        Column(
            Modifier.fillMaxWidth().background(iPodGray).padding(horizontal = 12.dp).padding(top = 10.dp, bottom = 6.dp),
        ) {
            Text(
                posLabel,
                color = Color(0xFF6B7280), fontSize = 11.sp, lineHeight = 13.sp,
                fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(state.positionSec.toInt()),
                    color = Color(0xFF3E444D), fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                    modifier = Modifier.width(44.dp),
                )
                Ios5Slider(
                    value = state.progress.coerceIn(0f, 1f),
                    onValueChange = onSeek,
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                Text(
                    "-${formatTime(remaining)}",
                    color = Color(0xFF3E444D), fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                    textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Icon(
                    when (state.repeat) {
                        RepeatMode.ONE -> Icons.Filled.RepeatOne
                        else -> Icons.Filled.Repeat
                    },
                    "循环",
                    tint = if (state.repeat == RepeatMode.OFF) Color(0xFF6B7280) else Color(0xFF0A60D6),
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onCycleRepeat,
                        ).padding(9.dp),
                )
                Icon(
                    if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    "喜欢",
                    tint = if (state.isCurrentLiked) Color(0xFFD63A3A) else Color(0xFF6B7280),
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onToggleLike,
                        ).padding(9.dp),
                )
                Icon(
                    Icons.Filled.Shuffle, "随机",
                    tint = if (state.shuffle) Color(0xFF0A60D6) else Color(0xFF6B7280),
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onToggleShuffle,
                        ).padding(9.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF9AA0A8)))

        // 巨型封面：能铺满就铺满全宽，矮屏自动缩小，始终正方形居中裁切
        Box(
            Modifier.fillMaxWidth().height(coverSide),
            contentAlignment = Alignment.Center,
        ) {
            TrackArtwork(
                song,
                Modifier.size(coverSide),
                corner = 0.dp,
                fullQuality = true,
            )
        }

        // 黑底：走带键 + 音量，高度 = 最小高度 + 分到的富余，控件收拢居中
        Column(
            Modifier.fillMaxWidth().background(iPodBlack).padding(horizontal = 20.dp)
                .padding(
                    top = 8.dp + bottomExtra / 2,
                    bottom = 18.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + bottomExtra / 2,
                ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Icon(
                    Icons.Filled.SkipPrevious, "上一首", tint = Color.White,
                    modifier = Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onPrevious).padding(8.dp),
                )
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (state.isPlaying) "暂停" else "播放", tint = Color.White,
                    modifier = Modifier.size(72.dp).clip(CircleShape).clickable(onClick = onTogglePlay).padding(8.dp),
                )
                Icon(
                    Icons.Filled.SkipNext, "下一首", tint = Color.White,
                    modifier = Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onNext).padding(8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Ios5Slider(
                value = volume,
                onValueChange = {
                    volume = it
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.roundToInt(), 0)
                },
                range = 0f..volMax.toFloat(),
                steps = volMax - 1,
            )
        }
    }
    }
}

@Composable
private fun IpodBarButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(width = 52.dp, height = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.Black.copy(alpha = 0.3f)),
                ),
            )
            .border(1.dp, Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

// 横向固定侧栏的宽度；内部封面按剩余高度自适应，上下紧凑无富余分配
private val LandscapeSideWidth = 340.dp

// ---------------- 横向侧边播放面板（固定宽度，内容上下紧凑） ----------------
// 面板只有进度 + 封面；标题搬进顶部整条栏，走带键拼进底部整条栏，空间尽量让给封面。

@Composable
fun Ios5LandscapeSidePlayer(
    state: PlayerUiState,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    // 调用方按中部可用高度传入，保证封面永远正方形（宽 = 高）
    panelWidth: Dp = LandscapeSideWidth,
) {
    val song = state.current
    val remaining = (state.durationSec - state.positionSec.toInt()).coerceAtLeast(0)
    // 右侧系统三按钮控制条不穿透：面板右边缘预留手势/按键区
    val layoutDir = LocalLayoutDirection.current
    val navEnd = WindowInsets.navigationBars.asPaddingValues().calculateEndPadding(layoutDir)

    BoxWithConstraints(
        // 根宽 = 内容panelWidth + 右侧系统键区：内容区与顶/底栏对齐，三条分割线同 x
        modifier.width(panelWidth + navEnd).fillMaxHeight().background(Color.Black)
            .padding(end = navEnd),
    ) {
        // 封面刚好正方形铺满面板宽度；进度条半透明浮在封面底部，不占高度
        val coverSide = minOf(maxWidth, maxHeight).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize()) {
            if (coverSide > 0.dp) {
                Box(
                    Modifier.fillMaxWidth().height(coverSide),
                    contentAlignment = Alignment.Center,
                ) {
                    TrackArtwork(song, Modifier.size(coverSide), corner = 0.dp, fullQuality = true)
                    Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(Color(0xFF14161B).copy(alpha = 0.62f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatTime(state.positionSec.toInt()),
                            color = Color(0xFFD7DAE0), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                            modifier = Modifier.width(40.dp),
                        )
                        Ios5Slider(
                            value = state.progress.coerceIn(0f, 1f),
                            onValueChange = onSeek,
                            modifier = Modifier.weight(1f),
                            compact = true,
                        )
                        Text(
                            "-${formatTime(remaining)}",
                            color = Color(0xFFD7DAE0), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                            textAlign = TextAlign.End, modifier = Modifier.width(40.dp),
                        )
                    }
                }
            } else {
                // 高度极端不足时退化为普通进度条，保证可操作
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF14161B).copy(alpha = 0.62f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatTime(state.positionSec.toInt()),
                        color = Color(0xFFD7DAE0), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                        modifier = Modifier.width(40.dp),
                    )
                    Ios5Slider(
                        value = state.progress.coerceIn(0f, 1f),
                        onValueChange = onSeek,
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                    Text(
                        "-${formatTime(remaining)}",
                        color = Color(0xFFD7DAE0), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                        textAlign = TextAlign.End, modifier = Modifier.width(40.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

// 横向底部整条栏右侧的走带键：喜欢在上一首左侧垂直居中，循环/随机在下一首右侧上下排列
//
// 走带键核心（横屏底栏与后台悬浮窗共用，保证同构）：悬浮窗按窗口边长传 scale 等比缩放图标。
@Composable
fun Ios5TransportControls(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    isLiked: Boolean,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    // 横屏底栏铺开整宽五组均分；悬浮窗居中紧凑
    spread: Boolean = false,
) {
    Row(
        if (spread) modifier.fillMaxWidth() else modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (spread) Arrangement.SpaceEvenly else Arrangement.Center,
    ) {
        Icon(
            if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            "喜欢",
            tint = if (isLiked) Color(0xFFD63A3A) else Color(0xFF8E8E93),
            modifier = Modifier.size(28.dp * scale).clip(CircleShape).clickable(onClick = onToggleLike).padding(4.dp * scale),
        )
        if (!spread) Spacer(Modifier.width(2.dp * scale))
        Icon(
            Icons.Filled.SkipPrevious, "上一首", tint = Color.White,
            modifier = Modifier.size(36.dp * scale).clip(CircleShape).clickable(onClick = onPrevious).padding(6.dp * scale),
        )
        Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (isPlaying) "暂停" else "播放", tint = Color.White,
            modifier = Modifier.size(48.dp * scale).clip(CircleShape).clickable(onClick = onTogglePlay).padding(6.dp * scale),
        )
        Icon(
            Icons.Filled.SkipNext, "下一首", tint = Color.White,
            modifier = Modifier.size(36.dp * scale).clip(CircleShape).clickable(onClick = onNext).padding(6.dp * scale),
        )
        if (!spread) Spacer(Modifier.width(2.dp * scale))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                when (repeat) {
                    RepeatMode.ONE -> Icons.Filled.RepeatOne
                    else -> Icons.Filled.Repeat
                },
                "循环",
                tint = if (repeat == RepeatMode.OFF) Color(0xFF8E8E93) else Color(0xFF0A60D6),
                modifier = Modifier.size(22.dp * scale).clip(CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onCycleRepeat,
                    ).padding(3.dp * scale),
            )
            Icon(
                Icons.Filled.Shuffle, "随机",
                tint = if (shuffle) Color(0xFF0A60D6) else Color(0xFF8E8E93),
                modifier = Modifier.size(22.dp * scale).clip(CircleShape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onToggleShuffle,
                    ).padding(3.dp * scale),
            )
        }
    }
}

@Composable
fun Ios5LandscapeTransport(
    state: PlayerUiState,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Ios5TransportControls(
        isPlaying = state.isPlaying,
        shuffle = state.shuffle,
        repeat = state.repeat,
        isLiked = state.isCurrentLiked,
        onTogglePlay = onTogglePlay,
        onNext = onNext,
        onPrevious = onPrevious,
        onToggleLike = onToggleLike,
        onToggleShuffle = onToggleShuffle,
        onCycleRepeat = onCycleRepeat,
        modifier = modifier,
        spread = true,
    )
}
