package com.aurora.music.ui.ios5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.model.Song
import com.aurora.music.navigation.ios5Tabs
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.formatTime

/** Full-screen linen backdrop every iOS5 page sits on. */
@Composable
fun Ios5Backdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ios5Colors.linenBrush)) { content() }
}

/** Brushed-metal navigation bar with centered title, optional back + search keys. */
@Composable
fun Ios5NavBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        Modifier.fillMaxWidth()
            .background(Ios5Colors.metalBrush)
            .padding(top = topInset),
    ) {
        Box(
            Modifier.fillMaxWidth().height(Ios5Dimens.NavHeight).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // 两侧等宽槽：标题永远真居中
                Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) {
                    if (onBack != null) {
                        Ios5BarButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Text(
                    title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Ios5Sans,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                    if (onSearch != null) {
                        Ios5BarButton(onClick = onSearch) {
                            Icon(Icons.Filled.Search, "搜索", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
        // highlight + shadow lines for the machined edge
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.55f)))
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.Black.copy(alpha = 0.35f)))
    }
}

@Composable
private fun Ios5BarButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    // 固定尺寸 + 内容居中：箭头不再偏
    Row(
        Modifier.size(width = 44.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.28f)),
                ),
            )
            .border(1.dp, Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

private val tabIcons: Map<String, ImageVector> = mapOf(
    "首页" to Icons.Filled.Home,
    "歌单" to Icons.AutoMirrored.Filled.QueueMusic,
    "艺人" to Icons.Filled.Mic,
    "更多" to Icons.Filled.GridView,
    "设置" to Icons.Filled.Settings,
)

/**
 * Opaque iOS5 tab bar. Drawn solid into the system nav area so the
 * three-button / gesture strip is never hollow.
 */
@Composable
fun Ios5TabBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.4f)))
        Row(
            Modifier.fillMaxWidth().height(Ios5Dimens.TabHeight).padding(horizontal = 2.dp, vertical = 4.dp),
        ) {
            ios5Tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Column(
                    Modifier.weight(1f).fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (selected) Modifier.background(Color.White.copy(alpha = 0.22f))
                                .border(1.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            else Modifier,
                        )
                        .clickable { onNavigate(tab.route) }
                        .padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        tabIcons[tab.label] ?: Icons.Filled.Home,
                        tab.label,
                        tint = if (selected) Ios5Colors.TabSelected else Ios5Colors.TabUnselected,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        tab.label,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        maxLines = 1,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) Color.White else Ios5Colors.TabUnselected,
                    )
                }
            }
        }
    }
}

/** Rounded grouped card, the iOS5 list container. */
@Composable
fun Ios5Group(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(Ios5Dimens.CornerGroup))
            .clip(RoundedCornerShape(Ios5Dimens.CornerGroup))
            .background(Ios5Colors.CellBg)
            .border(1.dp, Ios5Colors.CellDivider, RoundedCornerShape(Ios5Dimens.CornerGroup)),
        content = content,
    )
}

@Composable
fun Ios5SectionTitle(text: String) {
    Text(
        text,
        color = Color(0xFF4A5160),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

data class Ios5HeaderAction(
    val text: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val danger: Boolean = false,
)

/**
 * 分组标题行：左侧标题，右侧一排浅色小按钮。
 * 抽象自专辑详情页的“歌曲 + 播放全部/随机播放”——凡是“列表+操作”的分组都用它，
 * 不要再为每个页面手写一遍。
 */
@Composable
fun Ios5ActionsHeader(
    title: String,
    actions: List<Ios5HeaderAction>,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Color(0xFF4A5160),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            Ios5MiniButton(text = a.text, icon = a.icon, onClick = a.onClick, danger = a.danger)
        }
    }
}

/**
 * 浅色分段质感小按钮：白→浅灰平滑渐变 + 灰描边 + 深灰字，比光泽大按钮扁一号。
 * 用于标题行右侧的操作，不独占行。
 */
@Composable
fun Ios5MiniButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val ink = if (danger) Color(0xFFC03232) else Color(0xFF3E444D)
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color.White,
                    1f to Color(0xFFDDE1E7),
                ),
            )
            .border(1.dp, Color(0xFF9AA0A8), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** One tappable row inside an [Ios5Group]. */
@Composable
fun Ios5Cell(
    title: String,
    subtitle: String = "",
    count: String = "",
    showChevron: Boolean = true,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // iOS5 列表主标题统一粗体（cell textLabel = Helvetica-Bold），副标题常规
            Text(title, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (count.isNotBlank()) {
            Text(count, color = Ios5Colors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
        }
        trailing?.invoke()
        if (showChevron) {
            Text("›", color = Color(0xFF9AA0AB), fontSize = 24.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
fun Ios5CellDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 12.dp).height(1.dp).background(Ios5Colors.CellDivider))
}

/**
 * Per-row card chrome for virtualized grouped lists. MUST be used instead of
 * `item { Ios5Group { list.forEach { ... } } }` for any list that can grow:
 * the forEach-in-item pattern composes every row at once on the UI thread and
 * freezes scrolling the moment the block enters the viewport.
 */
private fun Modifier.ios5RowChrome(isFirst: Boolean, isLast: Boolean): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (isFirst) Ios5Dimens.CornerGroup else 0.dp,
        topEnd = if (isFirst) Ios5Dimens.CornerGroup else 0.dp,
        bottomStart = if (isLast) Ios5Dimens.CornerGroup else 0.dp,
        bottomEnd = if (isLast) Ios5Dimens.CornerGroup else 0.dp,
    )
    return this
        .clip(shape)
        .background(Ios5Colors.CellBg)
        .drawBehind {
            val stroke = 1.dp.toPx()
            val r = Ios5Dimens.CornerGroup.toPx()
            val c = Ios5Colors.CellDivider
            // continuous side edges
            drawLine(c, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), stroke)
            drawLine(c, Offset(size.width - stroke / 2, 0f), Offset(size.width - stroke / 2, size.height), stroke)
            if (isFirst) {
                drawLine(c, Offset(r, stroke / 2), Offset(size.width - r, stroke / 2), stroke)
                drawArc(c, 180f, 90f, false, Offset(0f, 0f), Size(r * 2, r * 2), style = Stroke(stroke))
                drawArc(c, 270f, 90f, false, Offset(size.width - r * 2, 0f), Size(r * 2, r * 2), style = Stroke(stroke))
            }
            if (isLast) {
                val y = size.height - stroke / 2
                drawLine(c, Offset(r, y), Offset(size.width - r, y), stroke)
                drawArc(c, 90f, 90f, false, Offset(0f, size.height - r * 2), Size(r * 2, r * 2), style = Stroke(stroke))
                drawArc(c, 0f, 90f, false, Offset(size.width - r * 2, size.height - r * 2), Size(r * 2, r * 2), style = Stroke(stroke))
            }
        }
}

/**
 * Virtualized grouped rows: same look as [Ios5Group], but each row is its own
 * lazy item so only visible rows compose. Single-item groups keep using [Ios5Group].
 */
fun <T> LazyListScope.ios5Rows(
    data: List<T>,
    key: ((T) -> Any)? = null,
    dividers: Boolean = true,
    // 贴边留白（画在卡片描边之外）：给连续行与相邻卡片之间凑出统一间距用
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    row: @Composable (index: Int, item: T) -> Unit,
) {
    items(data.size, key = key?.let { k -> { i: Int -> k(data[i]) } }) { i ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                .then(if (i == 0 && topInset > 0.dp) Modifier.padding(top = topInset) else Modifier)
                .then(if (i == data.size - 1 && bottomInset > 0.dp) Modifier.padding(bottom = bottomInset) else Modifier)
                .ios5RowChrome(isFirst = i == 0, isLast = i == data.size - 1),
        ) {
            if (dividers && i > 0) Ios5CellDivider()
            row(i, data[i])
        }
    }
}

/** Glossy blue iOS5 button, optional leading icon. */
@Composable
fun Ios5GlossButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(Ios5Colors.glossBrush)
            .border(1.dp, Ios5Colors.IosBlueDark, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Track row used across browse/detail/search lists. */
@Composable
fun Ios5SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    showArtwork: Boolean = true,
    showSubtitle: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArtwork) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(44.dp), corner = 6.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title.ifBlank { "未知曲目" },
                color = if (isCurrent) Ios5Colors.IosBlue else Ios5Colors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSubtitle) {
                val sub = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" — ")
                if (sub.isNotBlank()) {
                    Text(sub, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (isCurrent && isPlaying) {
            Text("♪", color = Ios5Colors.IosBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
        }
        Text(formatTime(song.durationSec), color = Ios5Colors.TextSecondary, fontSize = 13.sp)
    }
}

@Composable
fun Ios5Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        LottieLoader(modifier = Modifier.size(72.dp))
    }
}

/**
 * iOS5 滑杆：横向大槽 + 白色豆豆。左半蓝填充，豆豆可拖、可点跳；
 * steps > 0 时松手/点按吸附到刻度。
 */
@Composable
fun Ios5Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    // 拖动中直接跟手，平时跟外部 value
    val shown = if (dragging) {
        dragFrac * (range.endInclusive - range.start) + range.start
    } else {
        value
    }
    val frac = ((shown - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

    fun snap(v: Float): Float {
        if (steps <= 0) return v.coerceIn(range.start, range.endInclusive)
        val n = steps + 1
        val tick = ((v - range.start) / (range.endInclusive - range.start) * n).roundToInt().coerceIn(0, n)
        return range.start + (range.endInclusive - range.start) * tick / n
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(if (compact) 26.dp else 34.dp),
    ) {
        val density = LocalDensity.current
        val trackH = if (compact) 6.dp else 10.dp
        val thumbD = if (compact) 20.dp else 28.dp
        val thumbPx = with(density) { thumbD.toPx() }
        val wPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val centerX = thumbPx / 2 + frac * (wPx - thumbPx)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(range, steps, wPx) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(snap(range.start + (range.endInclusive - range.start) * f))
                    }
                }
                .draggable(
                    state = rememberDraggableState { delta ->
                        val nf = (dragFrac + delta / wPx).coerceIn(0f, 1f)
                        dragFrac = nf
                        onValueChange(range.start + (range.endInclusive - range.start) * nf)
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        dragging = true
                        dragFrac = frac
                    },
                    onDragStopped = {
                        dragging = false
                        scope.launch { onValueChange(snap(value)) }
                    },
                ),
        ) {
        // 槽：银灰渐变 + 灰描边
        Box(
            Modifier.align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(trackH)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFFC9CED6),
                        0.5f to Color(0xFFE4E7EC),
                        1f to Color(0xFFF4F5F7),
                    ),
                )
                .border(1.dp, Color(0xFF9AA0A8), RoundedCornerShape(5.dp)),
        )
        // 左半蓝填充
        if (centerX > 1f) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .height(trackH)
                    .width(with(density) { centerX.toDp() })
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFF53A7EB),
                            1f to Color(0xFF0B6EDB),
                        ),
                    ),
            )
        }
        // 豆豆：白瓷 + 阴影，高光
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset { IntOffset((centerX - thumbPx / 2).roundToInt(), 0) }
                .size(thumbD)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White,
                        0.5f to Color(0xFFF5F5F7),
                        1f to Color(0xFFD9DCE1),
                    ),
                )
                .border(1.dp, Color(0xFF9AA0A8), CircleShape),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.7f),
                            0.45f to Color.Transparent,
                        ),
                    ),
            )
        }
        }
    }
}

@Composable
fun Ios5Empty(hint: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(hint, color = Ios5Colors.TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}
