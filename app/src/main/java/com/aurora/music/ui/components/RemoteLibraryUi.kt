package com.aurora.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 局域网资料库标记，严格按参考图的几何：
 * 以底部中点为圆心，画 90° 扇形（225°→315°）；
 * 半径均分 6 份，填充 1-2、3-4、5-6 三段，2-3、4-5 留空。
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
            // 每份半径的宽度；用描边圆弧等效地画出同心圆环
            val band = radius / 6f
            val stroke = band

            fun filledBand(fromSixths: Float, toSixths: Float) {
                val mid = (fromSixths + toSixths) / 2f * band
                drawArc(
                    color = Color.Black,
                    startAngle = 225f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(cx - mid, cy - mid),
                    size = Size(mid * 2f, mid * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            // 从内到外：1-2、3-4、5-6
            filledBand(1f, 2f)
            filledBand(3f, 4f)
            filledBand(5f, 6f)
        }
    }
}
