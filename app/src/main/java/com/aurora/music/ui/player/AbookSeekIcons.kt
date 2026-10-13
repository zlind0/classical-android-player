package com.aurora.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.aurora.music.ui.ios5.Ios5Sans

// 播客式圆圈秒数图标：细圆环（顶部留缺口）+ 转向箭头 + 中央秒数。
// 秒数是动态步长（5..120），Material 自带的 Replay30 等固定数字图标覆盖不了，自己画。
@Composable
fun SeekCircleIcon(
    seconds: Int,
    forward: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (forward) {
                withTransform({ scale(-1f, 1f, center) }) { drawRewindRing(color) }
            } else {
                drawRewindRing(color)
            }
        }
        Text(
            "$seconds",
            color = color,
            fontSize = if (seconds >= 100) 12.sp else 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Ios5Sans,
            maxLines = 1,
        )
    }
}

private fun DrawScope.drawRewindRing(color: Color) {
    val stroke = 2.5.dp.toPx()
    val pad = 6.dp.toPx()
    val r = ((size.minDimension - stroke) / 2 - pad).coerceAtLeast(1f)
    val c = center
    val left = c.x - r
    val top = c.y - r
    // 圆环从 12 点（270°）顺时针画满 300° 到 210°，起点在 12 点接住三角底边；
    // 顶部缺口（210°..270°）留给箭头
    drawArc(
        color = color,
        startAngle = 270f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
        style = Stroke(width = stroke),
    )
    // 箭头：底边（垂直边）中点落在 12 点接住圆环，尖头水平朝左悬在缺口上、与环有空隙
    // （前进键靠镜像翻转：底边仍在 12 点，尖头朝右）
    val len = 11.dp.toPx()
    val halfW = 7.dp.toPx()
    val tip = c + Offset(-len, -r)
    val dir = Offset(-1f, 0f)
    val base = tip - dir * len
    val normal = Offset(-dir.y, dir.x)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + normal.x * halfW, base.y + normal.y * halfW)
        lineTo(base.x - normal.x * halfW, base.y - normal.y * halfW)
        close()
    }
    drawPath(path, color)
}

// 中央大播放/暂停：暂停=两根粗圆头竖条（对齐参考图），播放=圆角三角。
@Composable
fun AbookPlayPauseIcon(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier) {
        if (playing) {
            val barW = size.width * 0.20f
            val gap = size.width * 0.18f
            val h = size.height * 0.58f
            val top = (size.height - h) / 2
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x - gap / 2 - barW, top),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x + gap / 2, top),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.30f, size.height * 0.22f)
                lineTo(size.width * 0.30f, size.height * 0.78f)
                lineTo(size.width * 0.78f, size.height * 0.5f)
                close()
            }
            drawPath(path, color)
            // 同色粗描边 + 圆角连接，钝化三角三个角
            drawPath(
                path,
                color,
                style = Stroke(width = 5.dp.toPx(), join = StrokeJoin.Round),
            )
        }
    }
}
