package com.aurora.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay

private const val CHARS_PER_SEC = 4f
private const val EDGE_DWELL_MS = 900L

/**
 * 超长单行标题往返滚动：一起始对齐边停留 → 线性滚到另一边 → 停留 → 线性滚回，一直循环。
 * 速度约 4 字符/秒：用“总字符数/实测总宽度”换算成 px/s。不超长时就是普通单行文本。
 */
@Composable
fun MarqueeText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily?,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Center,
) {
    var boxPx by remember { mutableIntStateOf(0) }
    // 用无限宽约束实测文本宽（Box 内 onTextLayout 拿到的会被压到容器宽，超长永远测不出来）
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current
    val textStyle = remember(color, fontSize, lineHeight, fontFamily, fontWeight, baseStyle) {
        baseStyle.merge(
            TextStyle(
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily,
                fontWeight = fontWeight,
            ),
        )
    }
    val textPx = remember(text, textStyle, measurer, density) {
        runCatching {
            measurer.measure(
                text = text.ifBlank { " " },
                style = textStyle,
                overflow = TextOverflow.Visible,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = Constraints.Infinity),
            ).size.width
        }.getOrDefault(0)
    }
    val offset = remember(text) { Animatable(0f) }
    val overflow = boxPx > 0 && textPx > boxPx

    LaunchedEffect(text, boxPx, textPx) {
        if (!overflow) {
            offset.snapTo(0f)
            return@LaunchedEffect
        }
        val dist = (textPx - boxPx).toFloat()
        val pxPerChar = textPx.toFloat() / text.length.coerceAtLeast(1)
        val speedPxPerSec = pxPerChar * CHARS_PER_SEC
        val ms = (dist / speedPxPerSec * 1000).toInt().coerceIn(300, 20000)
        offset.snapTo(0f)
        while (true) {
            delay(EDGE_DWELL_MS)
            offset.animateTo(dist, tween(ms, easing = LinearEasing))
            delay(EDGE_DWELL_MS)
            offset.animateTo(0f, tween(ms, easing = LinearEasing))
        }
    }

    Box(modifier.onSizeChanged { boxPx = it.width }.clipToBounds()) {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = -offset.value },
        )
    }
}
