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
import androidx.compose.foundation.layout.heightIn
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
            .heightIn(min = Ios5Dimens.CellHeight)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = Ios5Type.CellTitle, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, fontFamily = Ios5Sans, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Ios5Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * iOS5 拨杆开关（PSD switch on/off, 156x56 @2x = 78x28pt）：
 * 银灰 / 果冻蓝底 + 白色圆钮（贴边、满高），ON/OFF 字居中于露出的轨道区。
 */
@Composable
fun Ios5Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackW = Ios5Dimens.SwitchWidth
    val trackH = Ios5Dimens.SwitchHeight
    val thumb = trackH - 1.dp
    val exposed = trackW - thumb
    val thumbX by animateDpAsState(
        targetValue = if (checked) trackW - thumb - 0.5.dp else 0.5.dp,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "ios5switch",
    )
    val blueAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(200),
        label = "ios5switchBlue",
    )
    val shape = RoundedCornerShape(trackH / 2)
    Box(
        modifier
            .size(trackW, trackH)
            .clip(shape)
            .background(Ios5Colors.switchOffBrush)
            .border(1.dp, Color(0xFF1D2627), shape)
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
                .background(Ios5Colors.switchOnBrush),
        )
        // 顶部内阴影（PSD InSh #000 70% 2px blur6）
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.45f to Color.Transparent,
                    ),
                ),
        )
        // ON 字居中于露出蓝区 / OFF 字居中于露出灰区（PSD：字在轨道上，不在钮里）
        Box(
            Modifier.align(Alignment.CenterStart).size(exposed, trackH)
                .graphicsLayer { alpha = blueAlpha },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "ON",
                color = Color.White,
                fontSize = Ios5Type.SwitchLabel,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f)),
                ),
            )
        }
        Box(
            Modifier.align(Alignment.CenterEnd).size(exposed, trackH)
                .graphicsLayer { alpha = 1f - blueAlpha },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "OFF",
                color = Color(0xFF8A8A8A),
                fontSize = Ios5Type.SwitchLabel,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
            )
        }
        // 白色圆钮：贴边、满高（PSD handle 54x55）
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = thumbX)
                .size(thumb)
                .shadow(1.dp, CircleShape)
                .clip(CircleShape)
                .background(Ios5Colors.switchHandleBrush)
                .border(1.dp, Color(0xFF7C7C7C), CircleShape),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.85f),
                        0.5f to Color.Transparent,
                    ),
                ),
            )
        }
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
            Text(title, color = Ios5Colors.TextPrimary, fontSize = Ios5Type.CellTitle, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans, modifier = Modifier.weight(1f))
            Text(valueLabel, color = Ios5Colors.TextSecondary, fontSize = Ios5Type.CellDetail, fontFamily = Ios5Sans)
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
        Modifier.fillMaxWidth().heightIn(min = Ios5Dimens.CellHeight).clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = if (danger) Color(0xFFD63A3A) else Ios5Colors.IosBlue,
            fontSize = Ios5Type.CellTitle, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
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
        Modifier.fillMaxWidth()
            .heightIn(min = Ios5Dimens.CellHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = Ios5Type.CellTitle, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, fontFamily = Ios5Sans, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** iOS5 经典分段控件（PSD "grey tabs bar"：211x61 @2x = 105x30.5pt，R5）。 */
@Composable
fun Ios5SegmentRow(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth()
                .height(Ios5Dimens.SegmentHeight)
                .clip(RoundedCornerShape(Ios5Dimens.CornerSegmented))
                .background(Ios5Colors.segOffBrush)
                .border(1.dp, Color(0xFF8E9AA6), RoundedCornerShape(Ios5Dimens.CornerSegmented)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { i, opt ->
                val active = i == selected
                val shape = when {
                    options.size == 1 -> RoundedCornerShape(4.dp)
                    i == 0 -> RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                    i == options.lastIndex -> RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                    else -> RectangleShape
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(shape)
                        .background(if (active) Ios5Colors.segOnBrush else Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Transparent),
                        ))
                        .clickable { onSelect(i) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt,
                        fontSize = Ios5Type.Segment,
                        fontFamily = Ios5Sans,
                        fontWeight = FontWeight.Bold,
                        color = if (active) Ios5Colors.SegInkActive else Ios5Colors.SegInkIdle,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = Shadow(
                                color = if (active) Color(0xFF2A4A94) else Color.White,
                                offset = Offset(0f, 1f),
                            ),
                        ),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // 段间分隔线：只在两边都没选中时画银灰线，挨着选中段画实测的深蓝边
                if (i != options.lastIndex) {
                    val besideActive = active || i + 1 == selected
                    Box(
                        Modifier.width(1.dp).fillMaxHeight()
                            .background(
                                if (besideActive) Ios5Colors.segDividerBrush
                                else Brush.verticalGradient(listOf(Color(0xFFBFBFBF), Color(0xFFBFBFBF))),
                            ),
                    )
                }
            }
        }
    }
}

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
