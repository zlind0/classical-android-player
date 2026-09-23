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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.aurora.music.AuroraApplication
import com.aurora.music.data.lookupMergedTitle
import com.aurora.music.data.mergeEnabledFor
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

    // 合并标题：扫描预计算表 + 内存曲目表同步查出，无 IO、无协程、无分词
    val container = (context.applicationContext as AuroraApplication).container
    val roots by container.musicRoots.roots.collectAsStateWithLifecycle(initialValue = emptyList())
    val libMerges by container.localLibrary.albumMerges.collectAsStateWithLifecycle()
    val merged = remember(song.id, song.albumId, libMerges, roots) {
        val tracks = container.localLibrary.albumTracksSorted(song.albumId)
        if (tracks.isEmpty() || !mergeEnabledFor(roots, tracks)) null
        else lookupMergedTitle(libMerges[song.albumId], tracks, song.id)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFFF4F4F6)),
    ) {
        // 精确分配：三块最小高度先留出来，封面取能放下的最大正方形，
        // 剩下的 leftover 才是真富余，40% 给顶栏（三等分），60% 给底栏。
        // 等式恒成立：top + gray + cover + bottom = H，永远不溢出不留缝
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val topBase = statusTop + 83.dp
        val grayBase = 100.dp
        val bottomBase = 144.dp + navBottom
        val coverSide = minOf(maxWidth, maxHeight - topBase - grayBase - bottomBase).coerceAtLeast(0.dp)
        val leftover = (maxHeight - topBase - grayBase - coverSide - bottomBase).coerceAtLeast(0.dp)
        val topExtra = leftover * 0.4f
        val bottomExtra = leftover - topExtra
        val third = topExtra / 3f
        Column(Modifier.fillMaxSize()) {
        // 黑顶栏：返回 | 艺人 | 队列 + 曲名单行
        Column(
            Modifier.fillMaxWidth()
                .background(iPodBlack)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + third),
        ) {
            // 第一行：返回 | 艺人/大标题 | 队列（同高对齐）
            Row(
                Modifier.fillMaxWidth().height(44.dp + third).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IpodBarButton(onClick = onCollapse) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "返回", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                val firstLine = merged?.first ?: song.artist.ifBlank { " " }
                Text(
                    firstLine,
                    color = if (merged != null) Color.White else Color(0xFF9AA0AB),
                    fontSize = if (merged != null) 17.sp else 13.sp,
                    fontWeight = if (merged != null) FontWeight.Bold else FontWeight.Normal,
                    fontFamily = Ios5Sans,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                IpodBarButton(onClick = onOpenQueue) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "队列", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            // 第二行：曲名/小标题独占一行
            val secondLine = merged?.second?.ifBlank { song.title } ?: song.title.ifBlank { "未在播放" }
            Text(
                secondLine,
                color = if (merged != null) Color(0xFFB9BEC7) else Color.White,
                fontSize = if (merged != null) 14.sp else 19.sp,
                fontWeight = if (merged != null) FontWeight.Normal else FontWeight.Bold,
                fontFamily = Ios5Sans,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp + third),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
        }

        // 灰条： scrub + 序号 + 循环/喜欢/随机（上松下紧）
        Column(
            Modifier.fillMaxWidth().background(iPodGray).padding(horizontal = 12.dp).padding(top = 10.dp, bottom = 6.dp),
        ) {
            Text(
                posLabel,
                color = Color(0xFF6B7280), fontSize = 11.sp, lineHeight = 13.sp, fontFamily = Ios5Sans,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(state.positionSec.toInt()),
                    color = Color(0xFF3E444D), fontSize = 12.sp, fontFamily = Ios5Sans,
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
                    color = Color(0xFF3E444D), fontSize = 12.sp, fontFamily = Ios5Sans,
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
