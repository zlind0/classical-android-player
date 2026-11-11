package com.aurora.music.ui.ios5

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** 箭头在弹出窗口的哪一侧：Up = 箭头朝上（窗口在锚点下方），Down = 箭头朝下（窗口在锚点上方）。 */
enum class Ios5ArrowSide { Up, Down }

/**
 * iOS5 风格的带指示箭头弹出窗口（半透明深色面板）。
 *
 * 箭头始终指向 [anchor]（锚点按钮在窗口坐标系里的位置），窗口默认放在锚点下方，
 * 下方空间不够时自动翻到上方。面板内容由 [content] 提供，通常配合
 * [Ios5PopoverItem] / [Ios5PopoverDivider] 使用。
 *
 * @param anchor 触发按钮在窗口坐标系中的位置，用 `boundsInWindow()` 获取。
 * @param width 面板固定宽度；固定宽度才能在不测量内容的情况下把箭头对准锚点。
 * @param side 强制箭头方向；null 表示按上下可用空间自动选择。
 */
@Composable
fun Ios5ArrowPopup(
    anchor: Rect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    width: Dp = 240.dp,
    side: Ios5ArrowSide? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val windowWidth = view.width
    val windowHeight = view.height
    val widthPx = with(density) { width.roundToPx() }
    val marginPx = with(density) { 8.dp.roundToPx() }
    val gapPx = with(density) { 8.dp.roundToPx() }
    val arrowWidthPx = with(density) { 18.dp.toPx() }
    val arrowHeightPx = with(density) { 9.dp.toPx() }
    val cornerPx = with(density) { Ios5Dimens.CornerDialog.toPx() }

    val pointingUp = when (side) {
        Ios5ArrowSide.Up -> true
        Ios5ArrowSide.Down -> false
        null -> windowHeight - anchor.bottom >= anchor.top
    }

    // 面板水平居中于锚点，并夹在窗口安全边距内；箭头再按面板实际位置反推。
    val popupX = (anchor.center.x - widthPx / 2f).coerceIn(
        marginPx.toFloat(),
        (windowWidth - widthPx - marginPx).coerceAtLeast(marginPx).toFloat(),
    )
    val arrowX = (anchor.center.x - popupX).coerceIn(
        cornerPx + arrowWidthPx / 2f,
        (widthPx - cornerPx - arrowWidthPx / 2f).coerceAtLeast(cornerPx + arrowWidthPx / 2f),
    )

    val shape = Ios5ArrowShape(
        arrowX = arrowX,
        arrowWidth = arrowWidthPx,
        arrowHeight = arrowHeightPx,
        corner = cornerPx,
        pointingUp = pointingUp,
    )

    Popup(
        popupPositionProvider = Ios5ArrowPopupPositionProvider(
            x = popupX.toInt(),
            anchor = anchor,
            pointingUp = pointingUp,
            gapPx = gapPx,
            marginPx = marginPx,
        ),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            modifier
                .width(width)
                .drawBehind {
                    // 先描一圈深色边，再用面板填充盖住内侧，得到包住箭头的 1dp 描边。
                    val outline = shape.createOutline(size, layoutDirection, this)
                    if (outline is Outline.Generic) {
                        drawPath(
                            outline.path,
                            Color.Black.copy(alpha = 0.72f),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                .clip(shape)
                .background(Ios5Colors.popoverBrush),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        top = if (pointingUp) 9.dp else 0.dp,
                        bottom = if (pointingUp) 0.dp else 9.dp,
                    ),
            ) {
                if (title != null) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = Ios5Type.AlertTitle,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Ios5Sans,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                        style = TextStyle(
                            shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f)),
                        ),
                    )
                    Ios5PopoverDivider()
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
            }
        }
    }
}

/** 弹出窗口里的一行：Helvetica 粗体白字，选中时右侧显示蓝色对勾。 */
@Composable
fun Ios5PopoverItem(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = Ios5Type.CellTitle,
            fontWeight = FontWeight.Bold,
            fontFamily = Ios5Sans,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f)),
            ),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Check,
                "已选中",
                tint = Ios5Colors.PopoverCheck,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 弹出窗口里的分隔线。 */
@Composable
fun Ios5PopoverDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ios5Colors.PopoverDivider))
}

/**
 * 面板 + 箭头的联合轮廓。箭头和圆角矩形在同一个 Path 里，描边才会绕过箭头而不是横穿箭根。
 */
private class Ios5ArrowShape(
    private val arrowX: Float,
    private val arrowWidth: Float,
    private val arrowHeight: Float,
    private val corner: Float,
    private val pointingUp: Boolean,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path()
        val bodyTop = if (pointingUp) arrowHeight else 0f
        val bodyBottom = if (pointingUp) size.height else size.height - arrowHeight
        path.addRoundRect(
            RoundRect(
                left = 0f,
                top = bodyTop,
                right = size.width,
                bottom = bodyBottom,
                cornerRadius = CornerRadius(corner, corner),
            ),
        )
        val half = arrowWidth / 2f
        val cx = arrowX.coerceIn(
            corner + half,
            (size.width - corner - half).coerceAtLeast(corner + half),
        )
        if (pointingUp) {
            path.moveTo(cx, 0f)
            path.lineTo(cx - half, bodyTop)
            path.lineTo(cx + half, bodyTop)
        } else {
            path.moveTo(cx, size.height)
            path.lineTo(cx - half, bodyBottom)
            path.lineTo(cx + half, bodyBottom)
        }
        path.close()
        return Outline.Generic(path)
    }
}

private class Ios5ArrowPopupPositionProvider(
    private val x: Int,
    private val anchor: Rect,
    private val pointingUp: Boolean,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val y = if (pointingUp) {
            anchor.bottom.toInt() + gapPx
        } else {
            anchor.top.toInt() - gapPx - popupContentSize.height
        }
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        return IntOffset(x, y.coerceIn(marginPx, maxY))
    }
}
