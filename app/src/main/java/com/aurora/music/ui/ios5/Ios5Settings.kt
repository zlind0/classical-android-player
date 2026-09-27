package com.aurora.music.ui.ios5

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS5 settings primitives. Every settings sub-page keeps its store logic and
 * string resources; only the presentation moves onto grouped linen cards with
 * a brushed-metal nav bar.
 */

@Composable
fun Ios5SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = title, onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            content = content,
        )
    }
}

/** Section wrapper: title + grouped card. */
fun LazyListScope.ios5Section(
    title: String,
    body: @Composable () -> Unit,
) {
    item { Ios5SectionTitle(title) }
    item {
        Ios5Group(Modifier.padding(horizontal = 12.dp)) { body() }
    }
}

@Composable
fun Ios5SwitchRow(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Ios5Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * iOS5 拨杆开关：银灰底 / 果冻蓝底 + 白色滑钮，滑钮带阴影，无 ON/OFF 字。
 */
@Composable
fun Ios5Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackW = 51.dp
    val trackH = 31.dp
    val thumb = 27.dp
    val thumbX by animateDpAsState(
        targetValue = if (checked) trackW - thumb - 2.dp else 2.dp,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "ios5switch",
    )
    val blueAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(200),
        label = "ios5switchBlue",
    )
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(trackW, trackH)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFFD1D1D6),
                    1f to Color(0xFFF2F2F7),
                ),
            )
            .border(1.dp, Color(0xFF8E8E93), shape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        // 蓝层淡入盖住灰底
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = blueAlpha }
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF53A7EB),
                        1f to Color(0xFF0B6EDB),
                    ),
                ),
        )
        // 顶部高光
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.35f),
                        0.5f to Color.Transparent,
                    ),
                ),
        )
        // 白色滑钮
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = thumbX)
                .size(thumb)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White,
                        1f to Color(0xFFE8E8E8),
                    ),
                )
                .border(0.5.dp, Color(0xFFB0B0B0), CircleShape),
        )
    }
}

@Composable
fun Ios5SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    // 刻意放 onValueChange 前面：尾随 lambda 永远绑定 onValueChange，历史调用方零改动。
    onValueChangeFinished: () -> Unit = {},
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(valueLabel, color = Ios5Colors.TextSecondary, fontSize = 14.sp)
        }
        Ios5Slider(
            value = value,
            onValueChange = onValueChange,
            range = range,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/** Chevron row navigating deeper. */
@Composable
fun Ios5NavRow(
    title: String,
    subtitle: String = "",
    value: String = "",
    onClick: () -> Unit,
) {
    Ios5Cell(title = title, subtitle = subtitle, count = value, onClick = onClick)
}

/** Centered tappable action text (blue, or red for danger). */
@Composable
fun Ios5ActionRow(
    title: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = if (danger) Color(0xFFD63A3A) else Ios5Colors.IosBlue,
            fontSize = 16.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** Checkmark row for single-choice lists. */
@Composable
fun Ios5CheckRow(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (checked) {
            Text("✓", color = Ios5Colors.IosBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Inline text field row. */
@Composable
fun Ios5TextRow(
    title: String,
    value: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = singleLine,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.Black, fontSize = 15.sp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
            colors = TextFieldDefaults.colors(
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                disabledTextColor = Color.Black,
                cursorColor = Color.Black,
                focusedContainerColor = Ios5Colors.GroupBg,
                unfocusedContainerColor = Ios5Colors.GroupBg,
                disabledContainerColor = Ios5Colors.GroupBg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedPlaceholderColor = Color(0xFF8E8E93),
                unfocusedPlaceholderColor = Color(0xFF8E8E93),
            ),
        )
    }
}

/** iOS5 经典分段控件：颜色逐像素采自系统截图，无高光分层，选中蓝稳重平滑。 */
@Composable
fun Ios5SegmentRow(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(segmentOffBrush)
                .border(1.dp, Color(0xFFB2B2B2), RoundedCornerShape(9.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { i, opt ->
                val active = i == selected
                val shape = when {
                    options.size == 1 -> RoundedCornerShape(8.dp)
                    i == 0 -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                    i == options.lastIndex -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                    else -> RectangleShape
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(shape)
                        .background(if (active) segmentOnBrush else Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Transparent),
                        ))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (active) Color.White else Color(0xFF727272),
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = Shadow(
                                color = if (active) Color(0xFF2A4A94) else Color.White,
                                offset = Offset(0f, 1f),
                            ),
                        ),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // 段间分隔线：只在两边都没选中时画灰线，挨着选中段画实测的深蓝边
                if (i != options.lastIndex) {
                    val besideActive = active || i + 1 == selected
                    Box(
                        Modifier.width(1.dp).fillMaxHeight()
                            .background(if (besideActive) Color(0xFF4868AF) else Color(0xFFBFBFBF)),
                    )
                }
            }
        }
    }
}

/**
 * 未选中段底（实测中线中值）：顶部近白到底部浅灰，平滑无分层。
 * #FCFCFC → #E9E9E9 → #D1D1D1 → #CBCBCB
 */
private val segmentOffBrush = Brush.verticalGradient(
    0f to Color(0xFFFCFCFC),
    0.4f to Color(0xFFE9E9E9),
    0.7f to Color(0xFFD1D1D1),
    1f to Color(0xFFCBCBCB),
)

/**
 * 选中段底（实测中线中值）：顶部深蓝藏青描边起，到底部亮蓝，平滑无高光带。
 * #334A89 → #405FAF → #4F76D6 → #7BA4F2
 */
private val segmentOnBrush = Brush.verticalGradient(
    0f to Color(0xFF334A89),
    0.08f to Color(0xFF405FAF),
    0.5f to Color(0xFF4E74D4),
    1f to Color(0xFF7BA4F2),
)

/** Small gray explanatory footer. */
fun LazyListScope.ios5FootNote(text: String) {
    item {
        Text(
            text,
            color = Ios5Colors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun Ios5StaticText(text: String) {
    Text(
        text,
        color = Ios5Colors.TextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
