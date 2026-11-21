package com.aurora.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.data.llm.TranslationSession
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5SmallButton

/**
 * 播放页翻译遮罩：盖住封面区，显示当前翻译块。
 *
 * 字体最大 30 sp；实际字号按可用宽高测量后向下收缩，保证长译文不溢出。
 * 生成过程中直接显示流式增量，而不是等整段翻译完。
 */
@Composable
fun AudiobookTranslationOverlay(
    state: TranslationSession.UiState,
    modelTitle: String,
    hasModel: Boolean,
    hasAsr: Boolean,
    unavailableReason: String = "",
    asrError: String = "",
    onOpenSettings: () -> Unit,
    onOpenSubtitleSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    unavailableReason.isNotBlank() -> HintText(unavailableReason)
                    !hasModel -> MissingLlmHint(onOpenSettings)
                    !hasAsr -> MissingAsrHint(onOpenSubtitleSettings)
                    asrError.isNotBlank() -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HintText(asrError, color = Color(0xFFFF8A80))
                            Spacer(Modifier.height(10.dp))
                            Ios5SmallButton(text = "打开字幕设置", onClick = onOpenSubtitleSettings)
                        }
                    }
                    state.status == TranslationSession.Status.ERROR -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HintText(
                                state.message.ifBlank { "翻译失败" },
                                color = Color(0xFFFF8A80),
                            )
                            Spacer(Modifier.height(10.dp))
                            Ios5SmallButton(text = "打开翻译设置", onClick = onOpenSettings)
                        }
                    }
                    state.status == TranslationSession.Status.WAITING -> {
                        LoadingBlock(state.message.ifBlank { "正在准备字幕…" })
                    }
                    state.status == TranslationSession.Status.LOADING -> {
                        LoadingBlock(state.message.ifBlank { "正在加载模型…" })
                    }
                    state.status == TranslationSession.Status.GENERATING && state.text.isBlank() -> {
                        LoadingBlock(state.message.ifBlank { "正在听这一段…" })
                    }
                    state.text.isNotBlank() -> AutoSizeTranslation(state.text)
                    else -> LoadingBlock("正在准备翻译…")
                }
            }
        }

        val status = when {
            unavailableReason.isNotBlank() -> "无法翻译"
            !hasModel -> "多模态模型未下载"
            !hasAsr -> "字幕模型未下载"
            asrError.isNotBlank() -> "字幕生成失败"
            state.status == TranslationSession.Status.LOADING ->
                state.message.ifBlank { "$modelTitle · 准备中…" }
            state.status == TranslationSession.Status.GENERATING -> "$modelTitle · 翻译中…"
            state.status == TranslationSession.Status.READY -> "$modelTitle · 已翻译"
            else -> "$modelTitle"
        }
        Text(
            status,
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp,
            fontFamily = Ios5Sans,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AutoSizeTranslation(text: String) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val base = TextStyle(
            color = Color.White,
            fontFamily = Ios5Sans,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        val size = remember(text, width, height) {
            if (width <= 0 || height <= 0) return@remember 16
            var best = 8
            var lo = 8
            var hi = 30
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val result = measurer.measure(
                    AnnotatedString(text),
                    style = base.copy(
                        fontSize = mid.sp,
                        lineHeight = (mid * 1.3f).sp,
                    ),
                    constraints = Constraints(maxWidth = width),
                )
                if (result.size.height <= height) {
                    best = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            best
        }
        val maxLines = remember(size, height) {
            val lineHeightPx = with(density) { (size * 1.3f).sp.toPx() }.coerceAtLeast(1f)
            (height / lineHeightPx).toInt().coerceAtLeast(1)
        }
        Text(
            text = text,
            color = Color.White,
            fontFamily = Ios5Sans,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            fontSize = size.sp,
            lineHeight = (size * 1.3f).sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LoadingBlock(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp,
            fontFamily = Ios5Sans,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HintText(text: String, color: Color = Color.White.copy(alpha = 0.85f)) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFamily = Ios5Sans,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MissingLlmHint(onOpenSettings: () -> Unit) {
    Text(
        "还没有下载翻译模型",
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = Ios5Sans,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    HintText("去设置里下载 Gemma 4 E2B / E4B（2.6–3.7 GB），下载后本机推理、不上传音频。")
    Spacer(Modifier.height(12.dp))
    Ios5SmallButton(text = "去下载模型", onClick = onOpenSettings)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun MissingAsrHint(onOpenSubtitleSettings: () -> Unit) {
    Text(
        "翻译需要字幕模型来切句",
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = Ios5Sans,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    HintText("先用离线字幕模型把章节切成句子，再把每段语音交给多模态模型翻译。")
    Spacer(Modifier.height(12.dp))
    Ios5SmallButton(text = "去下载字幕模型", onClick = onOpenSubtitleSettings)
    Spacer(Modifier.height(4.dp))
}
