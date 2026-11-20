package com.aurora.music.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.data.subtitle.SubtitleCue
import com.aurora.music.data.subtitle.SubtitleSession
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5SmallButton
import kotlinx.coroutines.delay

/** 当前正在念的那一句：最后一条已开始的 cue。 */
fun currentCueIndex(cues: List<SubtitleCue>, positionSec: Float): Int {
    if (cues.isEmpty()) return -1
    var lo = 0
    var hi = cues.size - 1
    var ans = -1
    val t = positionSec.toDouble()
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].start <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/**
 * 字幕遮罩：盖住正方形封面区，80% 黑底，显示上一句 / 这一句 / 下一句。
 * 长按任意一行复制该句。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudiobookSubtitleOverlay(
    sub: SubtitleSession.UiState,
    positionSec: Float,
    lookAheadSec: Int,
    modelTitle: String,
    hasModel: Boolean,
    unavailableReason: String = "",
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf("") }

    LaunchedEffect(copied) {
        if (copied.isNotEmpty()) {
            delay(1200)
            copied = ""
        }
    }

    fun copy(text: String) {
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        copied = text
        android.widget.Toast.makeText(context, "已复制这一句", android.widget.Toast.LENGTH_SHORT).show()
    }

    val idx = currentCueIndex(sub.cues, positionSec)
    val prev = if (idx > 0) sub.cues[idx - 1].text else ""
    val cur = if (idx >= 0) sub.cues[idx].text else ""
    val next = when {
        idx >= 0 && idx + 1 < sub.cues.size -> sub.cues[idx + 1].text
        idx < 0 && sub.cues.isNotEmpty() -> sub.cues[0].text
        else -> ""
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .combinedClickable(onClick = {}, onLongClick = { copy(cur) }),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                unavailableReason.isNotBlank() -> Text(
                    unavailableReason,
                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 18.sp,
                    fontFamily = Ios5Sans, textAlign = TextAlign.Center,
                )
                !hasModel -> MissingModelHint(onOpenSettings)
                sub.status == SubtitleSession.Status.ERROR -> {
                    Text(
                        sub.message.ifBlank { "字幕生成失败" },
                        color = Color(0xFFFF8A80), fontSize = 13.sp, fontFamily = Ios5Sans,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Ios5SmallButton(text = "打开字幕设置", onClick = onOpenSettings)
                }
                sub.cues.isEmpty() -> {
                    CircularProgressIndicator(
                        color = Color.White, strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "正在准备字幕…",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = Ios5Sans,
                    )
                }
                else -> {
                    SubtitleLine(
                        text = prev,
                        color = Color.White.copy(alpha = 0.45f),
                        size = 13,
                        onLongPress = { copy(prev) },
                    )
                    Spacer(Modifier.height(10.dp))
                    SubtitleLine(
                        text = cur,
                        color = Color.White,
                        size = 22,
                        bold = true,
                        onLongPress = { copy(cur) },
                    )
                    Spacer(Modifier.height(10.dp))
                    SubtitleLine(
                        text = next,
                        color = Color.White.copy(alpha = 0.45f),
                        size = 16,
                        onLongPress = { copy(next) },
                    )
                }
            }
        }

        // 底部状态：模型 / 提前量 / 生成进度 / 复制提示
        Text(
            when {
                copied.isNotEmpty() -> "已复制"
                unavailableReason.isNotBlank() -> "无法生成字幕"
                !hasModel -> "字幕模型未下载"
                sub.status == SubtitleSession.Status.LOADING -> "加载模型…"
                sub.status == SubtitleSession.Status.GENERATING ->
                    "$modelTitle"
                sub.status == SubtitleSession.Status.READY ->
                    "$modelTitle"
                else -> "$modelTitle"
            },
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp,
            fontFamily = Ios5Sans,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubtitleLine(
    text: String,
    color: Color,
    size: Int,
    bold: Boolean = false,
    onLongPress: () -> Unit,
) {
    Text(
        text = text.ifBlank { " " },
        color = color,
        fontSize = size.sp,
        lineHeight = (size * 1.35f).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        fontFamily = Ios5Sans,
        textAlign = TextAlign.Center,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    )
}

@Composable
private fun MissingModelHint(onOpenSettings: () -> Unit) {
    Text(
        "还没有下载字幕模型",
        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "离线识别需要先下载一个模型（100–400 MB），下载后本机转写、不联网。",
        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp,
        fontFamily = Ios5Sans, textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
    Spacer(Modifier.height(12.dp))
    Ios5SmallButton(text = "去下载模型", onClick = onOpenSettings)
    Spacer(Modifier.height(4.dp))
}
