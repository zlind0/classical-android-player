package com.aurora.music.ui.ios5

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.ui.components.LocalRemoteAvailable
import com.aurora.music.model.Song
import com.aurora.music.navigation.ios5Tabs
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.components.formatTime
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Full-screen linen backdrop every iOS5 page sits on. */
@Composable
fun Ios5Backdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ios5Colors.linenBrush)) { content() }
}

// 横向合并顶栏的上报通道：NavHost 内的页面把自己的栏信息交出来，App 拼整条栏。
data class TopBarSpec(
    val title: String,
    val onBack: (() -> Unit)? = null,
    val onSearch: (() -> Unit)? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

class TopBarHost {
    var spec by mutableStateOf<TopBarSpec?>(null)
}

val LocalTopBarHost = compositionLocalOf<TopBarHost?> { null }

/**
 * Brushed-metal navigation bar (PSD "bar top", 44pt). Centered title, optional
 * back / search keys and a right-side text action.
 */
@Composable
fun Ios5NavBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    // 横向合并模式：自己不上栏，把标题/返回/搜索上报给 App 顶整条栏统一渲染
    val host = LocalTopBarHost.current
    if (host != null) {
        SideEffect { host.spec = TopBarSpec(title, onBack, onSearch, actionLabel, onAction) }
        return
    }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        Modifier.fillMaxWidth()
            .background(Ios5Colors.barBrush)
            .padding(top = topInset),
    ) {
        Box(
            Modifier.fillMaxWidth().height(Ios5Dimens.NavHeight).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Title is always centered on the bar (iOS5), independent of side keys.
            Text(
                title,
                color = Color.White,
                fontSize = Ios5Type.NavTitle,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black.copy(alpha = 0.4f),
                        offset = Offset(0f, 1f),
                    ),
                ),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 52.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    Ios5BarKey(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "返回", tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (actionLabel != null && onAction != null) {
                    Ios5BarKey(onClick = onAction) {
                        Text(
                            actionLabel,
                            color = Color.White,
                            fontSize = Ios5Type.BarButton,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Ios5Sans,
                            maxLines = 1,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.6f),
                                    offset = Offset(0f, 1f),
                                ),
                            ),
                        )
                    }
                }
                if (onSearch != null) {
                    if (actionLabel != null && onAction != null) Spacer(Modifier.width(6.dp))
                    Ios5BarKey(onClick = onSearch) {
                        Icon(Icons.Filled.Search, "搜索", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        // 1px dark bottom keyline
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3B4553)))
    }
}

/** Grey-blue glossy key used for back / search / action in the bar. */
@Composable
fun Ios5BarKey(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.height(Ios5Dimens.BarButtonHeight)
            .clip(RoundedCornerShape(Ios5Dimens.CornerButton))
            .background(Ios5Colors.barButtonBrush)
            .border(1.dp, Color(0xFF3F4A5B), RoundedCornerShape(Ios5Dimens.CornerButton))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

private val tabIcons: Map<String, ImageVector> = mapOf(
    "首页" to Icons.Filled.Home,
    "歌单" to Icons.AutoMirrored.Filled.QueueMusic,
    "艺人" to Icons.Filled.Mic,
    "电子书" to Icons.Filled.Book,
    "有声书" to Icons.Filled.Headset,
    "更多" to Icons.Filled.GridView,
    "设置" to Icons.Filled.Settings,
)

/** Icon painted with a vertical gradient (iOS5 tab glyphs). */
@Composable
private fun Ios5GradientIcon(
    icon: ImageVector,
    contentDescription: String?,
    top: Color,
    bottom: Color,
    size: Dp,
) {
    Box(
        Modifier.size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = Brush.verticalGradient(0f to top, 1f to bottom), blendMode = BlendMode.SrcIn)
            },
    ) {
        Icon(icon, contentDescription, tint = Color.Black, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Opaque iOS5 tab bar (PSD 644x101, 49pt). Solid gradient drawn into the system
 * nav area; icons use the PSD grey / blue gradients.
 */
@Composable
fun Ios5TabBar(currentRoute: String?, onNavigate: (String) -> Unit, showTopDivider: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        if (showTopDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.35f)))
        }
        Row(
            Modifier.fillMaxWidth().height(Ios5Dimens.TabHeight),
        ) {
            ios5Tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .drawBehind {
                            if (selected) {
                                val ix = 3.dp.toPx()
                                val iy = 2.dp.toPx()
                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.10f),
                                    topLeft = Offset(ix, iy),
                                    size = Size(size.width - ix * 2, size.height - iy * 2),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                                )
                            }
                        }
                        .clickable { onNavigate(tab.route) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (selected) {
                            Ios5GradientIcon(
                                tabIcons[tab.label] ?: Icons.Filled.Home,
                                tab.label,
                                Ios5Colors.TabIconOnTop,
                                Ios5Colors.TabIconOnBottom,
                                22.dp,
                            )
                        } else {
                            Ios5GradientIcon(
                                tabIcons[tab.label] ?: Icons.Filled.Home,
                                tab.label,
                                Ios5Colors.TabIconOffTop,
                                Ios5Colors.TabIconOffBottom,
                                22.dp,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            fontSize = Ios5Type.TabLabel,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            fontFamily = Ios5Sans,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color.White else Ios5Colors.TabLabelOff,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.75f),
                                    offset = Offset(0f, 1f),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Rounded grouped list card (PSD "Tables"): #F7F7F7 fill, 1px #ABABAB keyline. */
@Composable
fun Ios5Group(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(Ios5Dimens.CornerGroup))
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
        color = Ios5Colors.TableHeaderInk,
        fontSize = Ios5Type.SectionTitle,
        fontWeight = FontWeight.Bold,
        fontFamily = Ios5Sans,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        style = androidx.compose.ui.text.TextStyle(
            shadow = androidx.compose.ui.graphics.Shadow(
                color = Color.White.copy(alpha = 0.85f),
                offset = Offset(0f, 1f),
            ),
        ),
    )
}

data class Ios5HeaderAction(
    val text: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val danger: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 分组标题行：左侧标题，右侧一排浅色小按钮。
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
            color = Ios5Colors.TableHeaderInk,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.White.copy(alpha = 0.85f),
                    offset = Offset(0f, 1f),
                ),
            ),
        )
        actions.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            Ios5MiniButton(text = a.text, icon = a.icon, onClick = a.onClick, danger = a.danger, enabled = a.enabled)
        }
    }
}

/** Compact light gloss key used in group headers. */
@Composable
fun Ios5MiniButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val ink = if (danger) Color(0xFFC03232) else Color(0xFF3E444D)
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color.White,
                    0.5f to Color(0xFFF1F2F4),
                    1f to Color(0xFFD5D8DD),
                ),
            )
            .border(1.dp, Color(0xFF9AA0A8), shape)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Grey iOS5 disclosure chevron (PSD 21x30 @2x -> 11x15dp). */
@Composable
fun Ios5Chevron(modifier: Modifier = Modifier, color: Color = Ios5Colors.Chevron) {
    Canvas(modifier.size(width = 11.dp, height = 15.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        drawPath(
            Path().apply {
                moveTo(w * 0.15f, h * 0.08f)
                lineTo(w * 0.85f, h * 0.5f)
                lineTo(w * 0.15f, h * 0.92f)
            },
            color = color,
            style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
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
    val available = LocalRemoteAvailable.current
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = Ios5Dimens.CellHeight)
            .alpha(if (available) 1f else 0.45f)
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // iOS5 列表主标题统一粗体（cell textLabel = Helvetica-Bold）
            Text(
                title,
                color = Ios5Colors.TextPrimary,
                fontSize = Ios5Type.CellTitle,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = Ios5Colors.TextSecondary,
                    fontSize = Ios5Type.CellDetail,
                    fontFamily = Ios5Sans,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (count.isNotBlank()) {
            Text(count, color = Ios5Colors.IosBlue, fontSize = Ios5Type.CellTitle, fontFamily = Ios5Sans)
            if (showChevron) Spacer(Modifier.width(6.dp))
        }
        trailing?.invoke()
        if (showChevron) Ios5Chevron()
    }
}

@Composable
fun Ios5CellDivider(startInset: Dp = 12.dp) {
    Box(Modifier.fillMaxWidth().padding(start = startInset).height(1.dp).background(Ios5Colors.CellDivider))
}

/**
 * Per-row card chrome for virtualized grouped lists.
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
 * Virtualized grouped rows: same look as [Ios5Group], each row its own lazy item.
 */
fun <T> LazyListScope.ios5Rows(
    data: List<T>,
    key: ((T) -> Any)? = null,
    dividers: Boolean = true,
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

/** Glossy blue iOS5 button (PSD "Button (Blue)" 61x30.5pt, R5). */
@Composable
fun Ios5GlossButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Ios5SmallButton(text = text, onClick = onClick, modifier = modifier, icon = icon, tone = Ios5ButtonTone.Blue)
}

enum class Ios5ButtonTone { Blue, Grey, Red, Black }

/**
 * Standard glossy iOS5 button, 30pt tall, R5. Gradient + white outer glow +
 * black 2px inset shadow, exactly as the PSD bar/standard buttons.
 */
@Composable
fun Ios5SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Ios5ButtonTone = Ios5ButtonTone.Grey,
) {
    val brush = when (tone) {
        Ios5ButtonTone.Blue -> Ios5Colors.buttonBlueBrush
        Ios5ButtonTone.Grey -> Ios5Colors.buttonGreyBrush
        Ios5ButtonTone.Red -> Ios5Colors.buttonRedBrush
        Ios5ButtonTone.Black -> Ios5Colors.buttonBlackBrush
    }
    val shape = RoundedCornerShape(Ios5Dimens.CornerButton)
    Row(
        modifier.height(Ios5Dimens.BarButtonHeight)
            .clip(shape)
            .background(brush)
            .border(1.dp, Color.Black.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            color = Color.White,
            fontSize = Ios5Type.Button,
            fontWeight = FontWeight.Bold,
            fontFamily = Ios5Sans,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black.copy(alpha = 0.6f),
                    offset = Offset(0f, 1f),
                ),
            ),
        )
    }
}

enum class Ios5LargeTone { Light, Dark, Green, Red }

/** Large iOS5 action button (PSD 558x93 @2x = 279x46.5pt), R10 with #464951 edge. */
@Composable
fun Ios5LargeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: Ios5LargeTone = Ios5LargeTone.Light,
) {
    val brush = when (tone) {
        Ios5LargeTone.Light -> Ios5Colors.largeLightBrush
        Ios5LargeTone.Dark -> Ios5Colors.largeDarkBrush
        Ios5LargeTone.Green -> Ios5Colors.largeGreenBrush
        Ios5LargeTone.Red -> Ios5Colors.largeRedBrush
    }
    val ink = when (tone) {
        Ios5LargeTone.Light -> Color.Black
        else -> Color.White
    }
    val shape = RoundedCornerShape(Ios5Dimens.CornerLarge)
    Box(
        modifier.fillMaxWidth().height(46.dp)
            .clip(shape)
            .background(Ios5Colors.LargeEdge)
            .padding(3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(brush)
            .border(1.dp, Ios5Colors.LargeEdge, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = ink,
            fontSize = Ios5Type.LargeButton,
            fontWeight = FontWeight.Bold,
            fontFamily = Ios5Sans,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = if (ink == Color.Black) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f),
                    offset = Offset(0f, 1f),
                ),
            ),
        )
    }
}

/** iOS5 grey search bar (PSD 644x91): silver bar + white pill field. */
@Composable
fun Ios5SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
) {
    Row(
        modifier.fillMaxWidth().background(Ios5Colors.greyBarBrush).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).height(31.dp)
                .clip(RoundedCornerShape(Ios5Dimens.CornerSearchField))
                .background(Color.White)
                .drawBehind {
                    // inner top shadow, as in the PSD field
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.18f),
                            0.35f to Color.Transparent,
                        ),
                    )
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = Ios5Colors.SearchIcon, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.Black,
                    fontSize = Ios5Type.Search,
                    fontFamily = Ios5Sans,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Ios5Colors.IosBlueBright),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                color = Ios5Colors.SearchPlaceholder,
                                fontSize = Ios5Type.Search,
                                fontFamily = Ios5Sans,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/**
 * iOS5 alert dialog (PSD 554x301): navy panel, gloss top, two grey gradient keys.
 */
@Composable
fun Ios5AlertDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String?,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .width(270.dp)
            .clip(RoundedCornerShape(Ios5Dimens.CornerDialog))
            .background(Ios5Colors.alertBrush),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp)) {
            Text(
                title,
                color = Color.White,
                fontSize = Ios5Type.AlertTitle,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f)),
                ),
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    color = Color.White,
                    fontSize = Ios5Type.AlertBody,
                    fontFamily = Ios5Sans,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f)),
                    ),
                )
            }
            if (content != null) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dismissText != null) {
                    Ios5AlertKey(dismissText, Ios5Colors.alertCancelBrush, Modifier.weight(1f)) { onDismiss?.invoke() }
                    Spacer(Modifier.width(10.dp))
                }
                Ios5AlertKey(confirmText, Ios5Colors.alertOkBrush, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

/** iOS5 confirm dialog with two keys (Cancel + OK). */
@Composable
fun Ios5ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelText: String = "取消",
) {
    Ios5AlertDialog(
        title = title,
        message = message,
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = cancelText,
        onDismiss = onCancel,
    )
}

/** iOS5 dialog with a single-line text input (create / rename). */
@Composable
fun Ios5ConfirmTextInput(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    placeholder: String = "",
    cancelText: String = "取消",
) {
    Ios5AlertDialog(
        title = title,
        message = "",
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = cancelText,
        onDismiss = onCancel,
        content = {
            Box(
                Modifier.fillMaxWidth().height(32.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.Black,
                        fontSize = Ios5Type.CellTitle,
                        fontFamily = Ios5Sans,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ios5Colors.IosBlueBright),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(placeholder, color = Ios5Colors.SearchPlaceholder, fontSize = 14.sp)
                            }
                            inner()
                        }
                    },
                )
            }
        },
    )
}

/** Modal host for iOS5 dialogs: transparent platform window + tap-outside scrim. */
@Composable
fun Ios5Dialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun Ios5AlertKey(text: String, brush: Brush, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Ios5Dimens.CornerButton)
    Box(
        modifier.height(34.dp).clip(shape).background(brush)
            .border(1.dp, Color.Black.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = Ios5Type.AlertButton,
            fontWeight = FontWeight.Bold,
            fontFamily = Ios5Sans,
            maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f)),
            ),
        )
    }
}

/** iOS5 blue progress bar (PSD "loading bar (bottom)" base + progress). */
@Composable
fun Ios5ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.fillMaxWidth().height(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Ios5Colors.progressTrackBrush)
            .border(1.dp, Color(0xFF1D2B44), RoundedCornerShape(3.dp)),
    ) {
        val f = progress.coerceIn(0f, 1f)
        val fillW = maxWidth * f
        Box(
            Modifier.height(10.dp).width(fillW).padding(start = 1.dp, top = 1.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ios5Colors.progressFillBrush),
        )
    }
}

/** iOS5 indeterminate loading bar (activity-style sliding fill). */
@Composable
fun Ios5IndeterminateBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ios5indet")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "ios5indetX",
    )
    BoxWithConstraints(
        modifier.fillMaxWidth().height(12.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Ios5Colors.progressTrackBrush)
            .border(1.dp, Color(0xFF1D2B44), RoundedCornerShape(3.dp)),
    ) {
        val barW = maxWidth * 0.35f
        val start = (maxWidth + barW) * x - barW
        Box(
            Modifier.offset(x = start).height(10.dp).width(barW).padding(top = 1.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ios5Colors.progressFillBrush),
        )
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
    remote: Boolean = false,
    onClick: () -> Unit,
) {
    val remoteAvailable = LocalRemoteAvailable.current
    val available = song.available && remoteAvailable
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = available, onClick = onClick)
            .alpha(if (available) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .heightIn(min = Ios5Dimens.CellHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (remote) {
            com.aurora.music.ui.components.Ios5RemoteStatusIcon()
            Spacer(Modifier.width(8.dp))
        }
        if (showArtwork) {
            Artwork(song.artworkUrl, song.accent, Modifier.size(42.dp), corner = 4.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title.ifBlank { "未知曲目" },
                color = if (isCurrent) Ios5Colors.IosBlue else Ios5Colors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSubtitle) {
                val sub = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" — ")
                if (sub.isNotBlank()) {
                    Text(sub, color = Ios5Colors.TextSecondary, fontSize = 13.sp, fontFamily = Ios5Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * iOS5 滑杆（PSD 442x44）：深顶浅底的蓝色左填充 + 银灰槽 + 白瓷圆钮。
 */
@Composable
fun Ios5Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onValueChangeFinished: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
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
        modifier.fillMaxWidth().height(if (compact) 24.dp else 30.dp),
    ) {
        val density = LocalDensity.current
        val thumbD = if (compact) 18.dp else Ios5Dimens.SliderThumb
        val trackH = if (compact) 6.dp else Ios5Dimens.SliderTrack
        val thumbPx = with(density) { thumbD.toPx() }
        val wPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val centerX = thumbPx / 2 + frac * (wPx - thumbPx)
        Box(
            Modifier.fillMaxSize()
                .pointerInput(range, steps, wPx) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(snap(range.start + (range.endInclusive - range.start) * f))
                        onValueChangeFinished()
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
                        val release = snap(range.start + (range.endInclusive - range.start) * dragFrac)
                        scope.launch {
                            onValueChange(release)
                            onValueChangeFinished()
                        }
                    },
                ),
        ) {
            // 槽：银灰渐变，两端圆形（PSD greybar 22px 高，端头半圆）
            Box(
                Modifier.align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(trackH)
                    .clip(RoundedCornerShape(trackH / 2))
                    .background(Ios5Colors.sliderTrackBrush)
                    .border(1.dp, Color(0xFF8E8E8E), RoundedCornerShape(trackH / 2)),
            )
            // 左半蓝填充（深顶浅底），左端同样圆形
            if (centerX > 1f) {
                Box(
                    Modifier.align(Alignment.CenterStart)
                        .height(trackH)
                        .width(with(density) { centerX.toDp() })
                        .clip(RoundedCornerShape(trackH / 2))
                        .background(Ios5Colors.sliderFillBrush),
                )
            }
            // 圆钮：白瓷渐变 + 阴影
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset { IntOffset((centerX - thumbPx / 2).roundToInt(), 0) }
                    .size(thumbD)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Ios5Colors.sliderThumbBrush)
                    .border(1.dp, Color(0xFF8E8E8E), CircleShape),
            ) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.75f),
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
