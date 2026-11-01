package com.aurora.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

private val WifiInk = Color(0xFF000000)
private val CloudLight = Color(0xFFB9BFC5)
private val CloudDark = Color(0xFF5E646A)

/**
 * 局域网资料库标记（iOS 5 款式）：
 * 以底部中点为圆心画 90° 扇形，半径均分 5 份，从圆心向外填充第 1、3、5 份。
 */
@Composable
fun Ios5WifiIcon(
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.94f
            val radius = w * 0.65f
            // 5 等分半径；每份都用一条与份宽相同的描边圆弧画出。
            val band = radius / 5f
            val stroke = band

            fun filledBand(index: Int) {
                val mid = (index - 0.5f) * band
                drawArc(
                    color = WifiInk,
                    startAngle = 225f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(cx - mid, cy - mid),
                    size = Size(mid * 2f, mid * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            // 从圆心向外：第 1、3、5 份有填充。
            filledBand(1)
            filledBand(3)
            filledBand(5)
        }
    }
}

/**
 * 中继资料库标记：空心云。未下载时是浅色线框；下载中按顺时针方向把线框
 * 逐渐改成深色（一圈 = 100%）；下载完成后整朵云保持深色线框。
 */
@Composable
fun Ios5CloudIcon(
    progress: Float,
    completed: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        val target = when {
            completed -> 1f
            progress <= 0f -> 0f
            // 缓冲阶段先走到 92%，等到真正可打开时再补满，避免一直停在原地。
            else -> progress.coerceIn(0f, 0.92f)
        }
        val animated by animateFloatAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = if (completed || target <= 0f) 260 else 2200,
                easing = LinearEasing,
            ),
            label = "relay-cloud-buffer",
        )
        Canvas(Modifier.size(16.dp)) {
            val path = cloudPath(size.width, size.height)
            val stroke = size.minDimension * 0.10f
            val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(path, CloudLight, style = style)
            val p = animated.coerceIn(0f, 1f)
            if (p <= 0f) return@Canvas
            if (p >= 1f) {
                drawPath(path, CloudDark, style = style)
            } else {
                val radius = size.minDimension
                val cx = size.width / 2f
                val cy = size.height / 2f
                val wedge = Path().apply {
                    moveTo(cx, cy)
                    arcTo(
                        Rect(cx - radius, cy - radius, cx + radius, cy + radius),
                        -90f,
                        360f * p,
                        false,
                    )
                    close()
                }
                clipPath(wedge) {
                    drawPath(path, CloudDark, style = style)
                }
            }
        }
    }
}

private fun cloudPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.24f, h * 0.74f)
    cubicTo(w * 0.10f, h * 0.74f, w * 0.08f, h * 0.56f, w * 0.22f, h * 0.51f)
    cubicTo(w * 0.21f, h * 0.31f, w * 0.49f, h * 0.25f, w * 0.60f, h * 0.40f)
    cubicTo(w * 0.72f, h * 0.31f, w * 0.89f, h * 0.44f, w * 0.85f, h * 0.57f)
    cubicTo(w * 0.95f, h * 0.60f, w * 0.93f, h * 0.74f, w * 0.80f, h * 0.74f)
    close()
}
