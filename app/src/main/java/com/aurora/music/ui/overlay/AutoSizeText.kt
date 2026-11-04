package com.aurora.music.ui.overlay

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/**
 * 在给定区域内用尽可能大的字号铺满文本，保证不 overflow。
 *
 * Compose BOM 2024.12 还没有 TextAutoSize，这里用 TextMeasurer 二分字号：
 * 12 次测量足够，只在文本/尺寸变化时计算。
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontFamily: FontFamily = FontFamily.Default,
    minSp: Float = 8f,
    maxSp: Float = 200f,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        val chosen = remember(text, maxW, maxH, minSp, maxSp) {
            if (text.isBlank() || maxW <= 0 || maxH <= 0) {
                minSp
            } else {
                var lo = minSp
                var hi = maxSp
                repeat(12) {
                    val mid = (lo + hi) / 2f
                    val layout = measurer.measure(
                        text = text,
                        style = TextStyle(
                            fontSize = mid.sp,
                            lineHeight = (mid * 1.15f).sp,
                            fontFamily = fontFamily,
                        ),
                        constraints = Constraints(maxWidth = maxW, maxHeight = maxH),
                    )
                    if (layout.size.height <= maxH && layout.size.width <= maxW) lo = mid else hi = mid
                }
                lo
            }
        }
        Text(
            text = text,
            color = color,
            fontSize = chosen.sp,
            lineHeight = (chosen * 1.15f).sp,
            fontFamily = fontFamily,
            textAlign = TextAlign.Center,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
