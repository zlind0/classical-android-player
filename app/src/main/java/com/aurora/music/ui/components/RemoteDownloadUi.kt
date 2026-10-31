package com.aurora.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.RemoteDownloadManager
import com.aurora.music.data.cast.RemoteDownloadStatus
import com.aurora.music.data.cast.RemoteDownloadUi
import com.aurora.music.ui.ios5.Ios5Colors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 右滑露出下载按钮时，行内容左端图标按这个比例淡出。 */
val LocalIos5SwipeReveal = compositionLocalOf { 0f }

/** 当前行的下载状态（由 [Ios5SwipeRow] 提供给状态图标）。 */
val LocalRemoteDownloadState = compositionLocalOf { RemoteDownloadUi() }

/**
 * 远程条目的左侧状态图标：未下载 = WiFi，下载中 = 进度环，已下载 = 蓝色勾。
 * 右滑时会淡出，把位置让给蓝色下载按钮。
 */
@Composable
fun Ios5RemoteStatusIcon(modifier: Modifier = Modifier) {
    val state = LocalRemoteDownloadState.current
    val reveal = LocalIos5SwipeReveal.current
    val a = (1f - reveal).coerceIn(0f, 1f)
    when (state.status) {
        RemoteDownloadStatus.DOWNLOADED -> Icon(
            Icons.Filled.Check,
            "已下载",
            tint = Ios5Colors.IosBlue,
            modifier = modifier.size(16.dp).alpha(a),
        )

        RemoteDownloadStatus.DOWNLOADING -> Ios5DownloadRing(
            progress = state.progress,
            diameter = 16.dp,
            color = Ios5Colors.IosBlue,
            modifier = modifier.alpha(a),
        )

        RemoteDownloadStatus.IDLE -> Ios5WifiIcon(modifier.alpha(a))
    }
}

/** 圆环进度：蓝色底环 + 白色/蓝色已完成弧，填满一圈表示下载完毕。 */
@Composable
fun Ios5DownloadRing(
    progress: Float,
    diameter: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(diameter)) {
        val stroke = size.minDimension * 0.16f
        val inset = stroke / 2f
        drawCircle(
            color = color.copy(alpha = 0.28f),
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * iOS5 左右滑动操作行：
 * - 向右拖：内容右移，左侧露出蓝色“下载”按钮；点击后按钮原位变成进度环；
 * - 向左拖：内容左移，右侧露出红色“删除”按钮；
 * - 行框架（分组卡片）保持不变，只有内容层平移。
 */
@Composable
fun Ios5SwipeRow(
    state: RemoteDownloadUi,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val actionWidth = 78.dp
    val actionPx = with(density) { actionWidth.toPx() }.coerceAtLeast(1f)
    val canDownload = state.status != RemoteDownloadStatus.DOWNLOADED
    val canDelete = state.status == RemoteDownloadStatus.DOWNLOADED
    val maxLeft = if (canDelete) actionPx else 0f
    val maxRight = if (canDownload) actionPx else 0f
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 下载完成 → 自动收回，露出标题左侧的勾。
    LaunchedEffect(state.status) {
        if (state.status == RemoteDownloadStatus.DOWNLOADED && offset.value != 0f) {
            offset.animateTo(0f, tween(180))
        }
    }
    LaunchedEffect(canDownload, canDelete) {
        val v = offset.value
        if ((v > 0f && !canDownload) || (v < 0f && !canDelete)) offset.animateTo(0f, tween(160))
    }

    Box(modifier.fillMaxWidth().clipToBounds()) {
        // 底层操作按钮：左侧下载，右侧删除。
        Row(Modifier.matchParentSize()) {
            if (canDownload) {
                Box(
                    Modifier.fillMaxHeight().width(actionWidth)
                        .background(Ios5Colors.buttonBlueBrush)
                        .clickable(enabled = state.status == RemoteDownloadStatus.IDLE) { onDownload() },
                    contentAlignment = Alignment.Center,
                ) {
                    when (state.status) {
                        RemoteDownloadStatus.DOWNLOADING -> Ios5DownloadRing(
                            progress = state.progress,
                            diameter = 30.dp,
                            color = Color.White,
                        )

                        else -> Text(
                            "下载",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    offset = Offset(0f, 1f),
                                ),
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (canDelete) {
                Box(
                    Modifier.fillMaxHeight().width(actionWidth)
                        .background(Ios5Colors.buttonRedBrush)
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "删除",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.55f),
                                offset = Offset(0f, 1f),
                            ),
                        ),
                    )
                }
            }
        }

        // 上层内容：水平平移；竖直滚动交给 LazyColumn。
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(Ios5Colors.CellBg)
                .pointerInput(canDownload, canDelete, actionPx) {
                    if (!canDownload && !canDelete) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val target = (offset.value + dragAmount).coerceIn(-maxLeft, maxRight)
                            scope.launch { offset.snapTo(target) }
                        },
                        onDragEnd = {
                            val target = when {
                                offset.value > actionPx / 2f -> maxRight
                                offset.value < -actionPx / 2f -> -maxLeft
                                else -> 0f
                            }
                            scope.launch { offset.animateTo(target, tween(180)) }
                        },
                        onDragCancel = {
                            scope.launch { offset.animateTo(0f, tween(160)) }
                        },
                    )
                },
        ) {
            CompositionLocalProvider(
                LocalRemoteDownloadState provides state,
                LocalIos5SwipeReveal provides (offset.value / actionPx).coerceIn(0f, 1f),
            ) {
                content()
            }
        }
    }
}

/**
 * 带下载状态/删除动作的远程行。调用方只需要给 kind + 设备 + 远端 key，
 * 下载完成/删除后状态由 [RemoteDownloadManager] 统一推给所有同名条目。
 */
@Composable
fun RemoteDownloadSwipeRow(
    kind: String,
    deviceId: String,
    remoteKey: String,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val downloads = (context.applicationContext as AuroraApplication).container.remoteDownloads
    val all by downloads.ui.collectAsStateWithLifecycle()
    val state = all[downloads.stateKey(kind, deviceId, remoteKey)] ?: RemoteDownloadUi()
    Ios5SwipeRow(
        state = state,
        onDownload = onDownload,
        onDelete = { downloads.delete(kind, deviceId, remoteKey) },
        modifier = modifier,
        content = content,
    )
}
