package com.aurora.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5Slider
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.PlayerViewModel
import com.aurora.music.viewmodel.RepeatMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Finger-following player deck. A single card driven by progress 0 (mini strip)
 * .. 1 (90% floating sheet): card height, margins, corners, the travelling
 * cover, text crossfade and the dimmed backdrop are all pure functions of
 * progress, so tap and drag share one continuous motion.
 */
@Composable
fun Ios5PlayerDeck(
    playerVM: PlayerViewModel,
    state: PlayerUiState,
    onOpenQueue: () -> Unit,
    onNavigateEqualizer: () -> Unit,
    onNavigateAlbum: () -> Unit,
    onNavigateArtist: () -> Unit,
) {
    if (!state.hasTrack) return
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val p = progress.value
    val song = state.current

    suspend fun settle(target: Float) {
        playerVM.setExpanded(target >= 1f)
        progress.animateTo(
            target,
            tween(if (target >= 1f) 380 else 300, easing = FastOutSlowInEasing),
        )
    }

    BackHandler(enabled = p > 0.02f) { scope.launch { settle(0f) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val collapsedH = 56.dp
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val tabH = 56.dp + navBottom
        val bottomPad = lerp(tabH, 12.dp, p)
        val expandedH = maxHeight * 0.9f - 12.dp
        val cardH = lerp(collapsedH, expandedH, p)
        val corner = lerp(0.dp, 18.dp, p)
        val sidePad = lerp(0.dp, 12.dp, p)

        // ---- 暗化背景 ----
        if (p > 0.01f) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f * p))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { if (p > 0.5f) scope.launch { settle(0f) } },
            )
        }

        val travelPx = with(density) { (expandedH - collapsedH).toPx() }.coerceAtLeast(1f)

        // 同一套拖拽状态同时挂在卡片和封面上：在哪开始拖都一样跟手
        val deckDragState = rememberDraggableState { delta ->
            scope.launch {
                progress.snapTo((progress.value - delta / travelPx).coerceIn(0f, 1f))
            }
        }
        fun deckDragStarted() {
            dragging = true
        }
        fun deckDragStopped(velocity: Float) {
            dragging = false
            scope.launch {
                val target = when {
                    velocity < -600f -> 1f
                    velocity > 600f -> 0f
                    progress.value > 0.5f -> 1f
                    else -> 0f
                }
                settle(target)
            }
        }

        Box(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = sidePad)
                .padding(bottom = bottomPad)
                .height(cardH)
                .then(if (p > 0.01f) Modifier.shadow(16.dp, RoundedCornerShape(corner)) else Modifier)
                .clip(RoundedCornerShape(corner))
                .background(Brush.verticalGradient(listOf(Ios5Colors.LinenTop, Ios5Colors.LinenBottom)))
                .then(
                    if (p > 0.01f) Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(corner))
                    else Modifier,
                )
                .draggable(
                    state = deckDragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { deckDragStarted() },
                    onDragStopped = { deckDragStopped(it) },
                ),
        ) {
            // ---- 单张 travelling 封面 ----
            val cardWpx = with(density) { (this@BoxWithConstraints.maxWidth - sidePad * 2).toPx() }
            val smallPx = with(density) { 38.dp.toPx() }
            val bigPx = (cardWpx - with(density) { 56.dp.toPx() }).coerceAtLeast(smallPx)
            val coverPx = lerpF(smallPx, bigPx, p)
            val cardHpx = with(density) { cardH.toPx() }
            val coverX = with(density) { lerp(10.dp, 28.dp, p).toPx() }
            val coverYSmall = cardHpx - with(density) { 47.dp.toPx() }
            val coverYBig = with(density) { 62.dp.toPx() }
            val coverY = lerpF(coverYSmall, coverYBig, p)
            Artwork(
                song.artworkUrl, song.accent,
                Modifier.size(with(density) { coverPx.toDp() })
                    .offset { IntOffset(coverX.roundToInt(), coverY.roundToInt()) }
                    .draggable(
                        state = deckDragState,
                        orientation = Orientation.Vertical,
                        onDragStarted = { deckDragStarted() },
                        onDragStopped = { deckDragStopped(it) },
                    ),
                corner = 8.dp,
                fullQuality = true,
            )

            // ---- Mini 行（底部，p<0.35 可见，可点展开） ----
            val miniAlpha = 1f - (p / 0.35f).coerceIn(0f, 1f)
            if (miniAlpha > 0f) {
                Row(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer { alpha = miniAlpha }
                        .clickable(
                            enabled = p < 0.3f,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { scope.launch { settle(1f) } }
                        .padding(start = 56.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.title.ifBlank { "未在播放" },
                            fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
                            color = Ios5Colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val sub = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" — ")
                        if (sub.isNotBlank()) {
                            Text(sub, fontSize = 11.sp, lineHeight = 13.sp, color = Ios5Colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "暂停" else "播放",
                        tint = Ios5Colors.IosBlue,
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .clickable(onClick = { playerVM.togglePlay() }).padding(8.dp),
                    )
                    Icon(
                        Icons.Filled.SkipNext, "下一首", tint = Ios5Colors.IosBlue,
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .clickable(onClick = { playerVM.next() }).padding(8.dp),
                    )
                }
            }

            // ---- 展开内容（p>0.5 淡入上浮） ----
            // 注意：滚动列的 bounds 被收在封面区域之下（而不是全屏 padding），
            // 否则透明的列会盖住封面、先吃掉落在封面上的拖拽手势。
            val mainAlpha = ((p - 0.5f) / 0.45f).coerceIn(0f, 1f)
            if (mainAlpha > 0f) {
                val shiftPx = with(density) { 24.dp.toPx() * (1f - mainAlpha) }
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = mainAlpha; translationY = shiftPx },
                ) {
                    Row(
                        Modifier.align(Alignment.TopStart)
                            .fillMaxWidth().height(48.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown, "收起",
                            tint = Ios5Colors.IosBlue,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .clickable(onClick = { scope.launch { settle(0f) } }).padding(8.dp),
                        )
                        Text(
                            "正在播放", Modifier.weight(1f),
                            color = Ios5Colors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, fontFamily = Ios5Sans,
                        )
                        Icon(
                            Icons.Filled.QueueMusic, "队列",
                            tint = Ios5Colors.IosBlue,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                .clickable(onClick = onOpenQueue).padding(8.dp),
                        )
                    }
                    val contentTop: Dp = 48.dp + with(density) { (coverYBig + bigPx).toDp() } + 12.dp
                    val contentH = (cardH - contentTop).coerceAtLeast(0.dp)
                    if (contentH > 0.dp) {
                        Column(
                            Modifier.align(Alignment.TopStart)
                                .fillMaxWidth()
                                .offset(y = contentTop)
                                .height(contentH)
                                .verticalScroll(rememberScrollState(), enabled = p > 0.95f && !dragging)
                                .padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                        Text(
                            song.title.ifBlank { "未在播放" },
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                            color = Ios5Colors.TextPrimary, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                song.artist, fontSize = 15.sp, color = Ios5Colors.IosBlue,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { scope.launch { settle(0f) }; onNavigateArtist() },
                            )
                            if (song.artist.isNotBlank() && song.album.isNotBlank()) {
                                Text(" · ", fontSize = 15.sp, color = Ios5Colors.TextSecondary)
                            }
                            Text(
                                song.album, fontSize = 15.sp, color = Ios5Colors.TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { scope.launch { settle(0f) }; onNavigateAlbum() },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Ios5Slider(
                            value = state.progress.coerceIn(0f, 1f),
                            onValueChange = { playerVM.seekTo(it) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatTime(state.positionSec.toInt()), fontSize = 12.sp, color = Ios5Colors.TextSecondary)
                            Text(formatTime(state.durationSec), fontSize = 12.sp, color = Ios5Colors.TextSecondary)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious, "上一首", tint = Ios5Colors.TextPrimary,
                                modifier = Modifier.size(52.dp).clip(CircleShape)
                                    .clickable(onClick = { playerVM.previous() }).padding(10.dp),
                            )
                            Box(
                                Modifier.size(68.dp).clip(CircleShape)
                                    .background(Ios5Colors.glossBrush)
                                    .border(1.dp, Ios5Colors.IosBlueDark, CircleShape)
                                    .clickable(onClick = { playerVM.togglePlay() }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    if (state.isPlaying) "暂停" else "播放",
                                    tint = Color.White, modifier = Modifier.size(34.dp),
                                )
                            }
                            Icon(
                                Icons.Filled.SkipNext, "下一首", tint = Ios5Colors.TextPrimary,
                                modifier = Modifier.size(52.dp).clip(CircleShape)
                                    .clickable(onClick = { playerVM.next() }).padding(10.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Icon(
                                if (state.isCurrentLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                "喜欢",
                                tint = if (state.isCurrentLiked) Color(0xFFD63A3A) else Ios5Colors.TextSecondary,
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                                    .clickable(onClick = { playerVM.toggleLikeCurrent() }).padding(10.dp),
                            )
                            Icon(
                                Icons.Filled.Shuffle, "随机",
                                tint = if (state.shuffle) Ios5Colors.IosBlue else Ios5Colors.TextSecondary,
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                                    .clickable(onClick = { playerVM.toggleShuffle() }).padding(10.dp),
                            )
                            Icon(
                                when (state.repeat) {
                                    RepeatMode.ONE -> Icons.Filled.RepeatOne
                                    else -> Icons.Filled.Repeat
                                },
                                "循环",
                                tint = if (state.repeat == RepeatMode.OFF) Ios5Colors.TextSecondary else Ios5Colors.IosBlue,
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                                    .clickable(onClick = { playerVM.cycleRepeat() }).padding(10.dp),
                            )
                            Icon(
                                Icons.Filled.GraphicEq, "均衡器",
                                tint = Ios5Colors.TextSecondary,
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                                    .clickable(onClick = { scope.launch { settle(0f) }; onNavigateEqualizer() })
                                    .padding(10.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}
