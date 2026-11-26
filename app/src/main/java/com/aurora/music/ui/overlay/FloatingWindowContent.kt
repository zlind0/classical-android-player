package com.aurora.music.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.MarqueeText
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.viewmodel.RepeatMode
import kotlin.math.abs

data class FloatingUiState(
    val title: String = "",
    val artist: String = "",
    val artUri: String = "",
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val liked: Boolean = false,
    val hasTrack: Boolean = false,
    /** 歌曲介绍（问号）正在进行：显示停止键。 */
    val introActive: Boolean = false,
    /** 书在播：中间封面区域盖同色遮罩并显示当前朗读句。 */
    val bookActive: Boolean = false,
    val bookSentence: String = "",
    val bookCover: String = "",
)

/**
 * 后台方形悬浮窗：底层封面，顶/底半透明栏浮在封面上。
 *
 * 手势层是整窗的祖先（祖先一定能收到事件；重叠兄弟只有最上层能收到，
 * 所以手势不能做在封面下层）：点按封面 → 回 app，拖动 → 挪窗口，
 * 双指捏合 → 缩放（受 pinchEnabled 门控）。栏上按钮消费点按，不会误触回 app。
 * 大小/位置只在放手回调里写 DataStore，手势过程中只调 WindowManager。
 */
@Composable
fun FloatingWindowContent(
    state: FloatingUiState,
    sizeDp: Dp,
    pinchEnabled: Boolean,
    onClose: () -> Unit,
    onReturnToApp: () -> Unit,
    onDrag: (dxPx: Float, dyPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    onZoom: (factor: Float) -> Unit,
    onZoomEnd: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onIntroClick: () -> Unit,
) {
    val tScale = (sizeDp.value / 280f).coerceIn(0.62f, 1.5f)
    val fontScale = (sizeDp.value / 280f).coerceIn(0.8f, 1.25f)
    Box(
        Modifier.size(sizeDp).clip(RoundedCornerShape(18.dp)).background(Color.Black)
            .overlayGestures(
                pinchEnabled = pinchEnabled,
                onTap = onReturnToApp,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onZoom = onZoom,
                onZoomEnd = onZoomEnd,
            ),
    ) {
        Artwork(
            url = state.artUri,
            accent = Color.Transparent,
            modifier = Modifier.fillMaxSize(),
            corner = 0.dp,
            fullQuality = true,
        )
        Column(Modifier.fillMaxSize()) {
            // 顶栏：关闭钮 + 艺人/标题（栏背景吞掉点按，只有露出的封面点按才回 app）
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF14161B).copy(alpha = 0.62f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppleCloseButton(size = 30.dp * tScale, onClick = onClose)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.artist.ifBlank { " " },
                        color = Color(0xFFB9BEC7),
                        fontSize = (11.sp * fontScale),
                        lineHeight = (13.sp * fontScale),
                        fontWeight = FontWeight.Bold,
                        fontFamily = Ios5Sans,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MarqueeText(
                        text = state.title.ifBlank { " " },
                        color = Color.White,
                        fontSize = (14.sp * fontScale),
                        lineHeight = (17.sp * fontScale),
                        fontFamily = Ios5Sans,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // 中间：书在播时盖同色遮罩，用尽可能大的字号铺满当前朗读句。
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .background(
                        if (state.bookActive) Color(0xFF14161B).copy(alpha = 0.62f)
                        else Color.Transparent,
                    )
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.bookActive && state.bookSentence.isNotBlank()) {
                    AutoSizeText(
                        text = state.bookSentence,
                        modifier = Modifier.fillMaxSize(),
                        color = Color.White,
                        fontFamily = Ios5Sans,
                    )
                }
            }
            // 底栏：不在听书状态时显示 问号 / 上一首 / 播放 / 下一首（space-around）。
            // 听书时中间是朗读句，不显示音乐走带键。
            if (!state.bookActive) {
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF14161B).copy(alpha = 0.62f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        )
                        .padding(vertical = 4.dp),
                ) {
                    FloatingTransportBar(
                        isPlaying = state.isPlaying,
                        introActive = state.introActive,
                        onIntroClick = onIntroClick,
                        onTogglePlay = onTogglePlay,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        scale = tScale,
                    )
                }
            }
        }
    }
}

/** 悬浮窗底栏：问号 / 上一首 / 播放暂停 / 下一首，四组按 space-around 铺开。 */
@Composable
private fun FloatingTransportBar(
    isPlaying: Boolean,
    introActive: Boolean,
    onIntroClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    scale: Float,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        Icon(
            if (introActive) Icons.Filled.Stop else Icons.Outlined.HelpOutline,
            if (introActive) "停止介绍" else "歌曲介绍",
            tint = if (introActive) Color(0xFFD63A3A) else Color(0xFF8E8E93),
            modifier = Modifier.size(34.dp * scale).clip(CircleShape)
                .clickable(onClick = onIntroClick).padding(6.dp * scale),
        )
        Icon(
            Icons.Filled.SkipPrevious, "上一首", tint = Color.White,
            modifier = Modifier.size(38.dp * scale).clip(CircleShape)
                .clickable(onClick = onPrevious).padding(6.dp * scale),
        )
        Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (isPlaying) "暂停" else "播放", tint = Color.White,
            modifier = Modifier.size(50.dp * scale).clip(CircleShape)
                .clickable(onClick = onTogglePlay).padding(6.dp * scale),
        )
        Icon(
            Icons.Filled.SkipNext, "下一首", tint = Color.White,
            modifier = Modifier.size(38.dp * scale).clip(CircleShape)
                .clickable(onClick = onNext).padding(6.dp * scale),
        )
    }
}

@Composable
private fun AppleCloseButton(size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.95f), Color(0xFFC6CBD3).copy(alpha = 0.95f)),
                ),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close, "关闭悬浮窗",
            tint = Color(0xFF3E444D),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

private fun Modifier.overlayGestures(
    pinchEnabled: Boolean,
    onTap: () -> Unit,
    onDrag: (dxPx: Float, dyPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    onZoom: (factor: Float) -> Unit,
    onZoomEnd: () -> Unit,
): Modifier = pointerInput(pinchEnabled) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        android.util.Log.d("FloatingWindow", "gesture down")
        var pastSlop = false
        var panned = false
        var zoomed = false
        // 子层（按钮/栏背景）消费过的手势是按钮点按，末尾不能再当回 app 的点按
        var childConsumed = false
        // 位移按整笔手势累积再判 slop：逐帧增量只有几 px，永远超不过 slop，
        // 慢速拖/捏会被当成点按
        var totalPan = Offset.Zero
        var totalZoom = 1f
        var events = 0
        do {
            val event: PointerEvent = awaitPointerEvent()
            events++
            if (event.changes.any { it.isConsumed }) childConsumed = true
            if (event.changes.all { !it.pressed }) break
            if (childConsumed) continue
            val pressed = event.changes.filter { it.pressed }
            var pan = Offset.Zero
            var zoom = 1f
            var span = 0f
            if (pressed.size >= 2) {
                val a = pressed[0]
                val b = pressed[1]
                val cur = (a.position - b.position).getDistance()
                val prev = (a.previousPosition - b.previousPosition).getDistance()
                span = cur
                if (prev > 0f) zoom = cur / prev
                pan = ((a.position - a.previousPosition) + (b.position - b.previousPosition)) / 2f
            } else if (pressed.size == 1) {
                val c = pressed[0]
                pan = c.position - c.previousPosition
            }
            if (!pastSlop) {
                totalPan += pan
                totalZoom *= zoom
                val zoomMotion = if (pressed.size >= 2) abs(1f - totalZoom) * span else 0f
                if (totalPan.getDistance() > slop || zoomMotion > slop) {
                    pastSlop = true
                    android.util.Log.d(
                        "FloatingWindow",
                        "pastSlop pan=${totalPan.getDistance()} zoomMotion=$zoomMotion slop=$slop fingers=${pressed.size}",
                    )
                }
            }
            if (pastSlop) {
                if (pan != Offset.Zero) {
                    onDrag(pan.x, pan.y)
                    panned = true
                }
                if (pinchEnabled && pressed.size >= 2 && zoom != 1f) {
                    onZoom(zoom)
                    zoomed = true
                }
                event.changes.forEach { it.consume() }
            }
        } while (true)
        // 放手才通知持久化，手势过程中调用方只调 WindowManager；
        // 纯点按（子层没消费）才回 app，按钮点按不回
        android.util.Log.d("FloatingWindow", "gesture end events=$events panned=$panned zoomed=$zoomed child=$childConsumed")
        if (panned) onDragEnd()
        else if (zoomed) onZoomEnd()
        else if (!childConsumed) onTap()
    }
}
