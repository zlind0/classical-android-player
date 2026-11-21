package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.llm.LlmDownloadSources
import com.aurora.music.data.llm.LlmModelCatalog
import com.aurora.music.data.llm.LlmModelSpec
import com.aurora.music.data.llm.LlmModelStore
import com.aurora.music.data.llm.TranslationCache
import com.aurora.music.data.llm.llmGpuCrashMarker
import com.aurora.music.ui.ios5.Ios5ButtonTone
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5SmallButton
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5Section
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 设置 → 有声书 → 多模态大模型。
 *
 * LiteRT-LM 运行时随 APK；Gemma 4 / Gemma 3n 权重按需从 HF / hf-mirror 下载。
 * 这里选择激活模型、下载源，以及翻译相关的目标时长和推理参数。
 */
@Composable
fun MultimodalSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val context = LocalContext.current
    val prefs by container.settingsStore.multimodalPrefs.collectAsStateWithLifecycle(
        initialValue = SettingsStore.MultimodalPrefs(),
    )
    val states by container.llmModels.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var translationCacheBytes by remember {
        mutableIntStateOf(TranslationCache.bytes(context).toInt())
    }
    val gpuCrashed = remember(prefs.useGpu) {
        prefs.useGpu && llmGpuCrashMarker(context.applicationContext).exists()
    }

    val activeSpec = remember(prefs.modelId, states) {
        container.llmModels.pickUsable(prefs.modelId)
    }

    Ios5SettingsPage(title = "多模态大模型", onBack = onBack) {
        ios5Section("模型") {
            Ios5StaticText(
                if (activeSpec != null) {
                    "当前使用：${activeSpec.title}（${activeSpec.sizeGb}）"
                } else {
                    "还没有可用的模型。下载一个 Gemma 4 后才能在播放页启用翻译。"
                },
            )
            LlmModelCatalog.all.forEach { spec ->
                Ios5CellDivider()
                LlmModelRow(
                    spec = spec,
                    state = states[spec.id] ?: if (container.llmModels.isInstalled(spec)) {
                        LlmModelStore.ModelState.Installed
                    } else {
                        LlmModelStore.ModelState.NotInstalled
                    },
                    active = activeSpec?.id == spec.id,
                    onSelect = {
                        scope.launch { container.settingsStore.setMultimodalModelId(spec.id) }
                    },
                    onDownload = { container.llmModels.startDownload(spec, prefs.source) },
                    onCancel = { container.llmModels.cancel() },
                    onDelete = {
                        scope.launch {
                            container.llmModels.delete(spec)
                            if (prefs.modelId == spec.id) {
                                container.settingsStore.setMultimodalModelId("")
                            }
                        }
                    },
                )
            }
        }

        ios5Section("下载源") {
            LlmDownloadSources.all.forEach { src ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { scope.launch { container.settingsStore.setMultimodalSource(src.id) } }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        src.label,
                        color = Ios5Colors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (prefs.source == src.id) {
                        Icon(
                            Icons.Filled.Check,
                            null,
                            tint = Ios5Colors.IosBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Ios5CellDivider()
            }
        }

        ios5Section("翻译") {
            Ios5SliderRow(
                title = "目标翻译时长",
                valueLabel = "${prefs.targetAudioSec} 秒 / 段",
                value = prefs.targetAudioSec.toFloat(),
                range = 5f..30f,
                steps = 4,
                onValueChangeFinished = {},
                onValueChange = { v ->
                    scope.launch {
                        container.settingsStore.setMultimodalTargetAudioSec((v / 5).roundToInt() * 5)
                    }
                },
            )
            Ios5CellDivider()
            LanguageRows(
                current = prefs.targetLanguage,
                onSelect = { scope.launch { container.settingsStore.setMultimodalTargetLanguage(it) } },
            )
            Ios5CellDivider()
            Ios5StaticText(
                "Gemma 4 原生音频输入最长 30 秒/段。拼到目标时长时，如果下一句放不下，" +
                    "就少放这一句，绝不截断；单个 VAD 段本身超过 30 秒时会拆成多段分别翻译。",
            )
        }

        ios5Section("翻译缓存") {
            Ios5StaticText(
                if (translationCacheBytes <= 0) {
                    "还没有翻译完成的片段。"
                } else {
                    "占用 ${(translationCacheBytes / 1024).coerceAtLeast(1)} KB（含切块区间与译文）。"
                },
            )
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Ios5SmallButton(
                    text = "清除全部翻译缓存",
                    onClick = {
                        TranslationCache.clear(context)
                        translationCacheBytes = TranslationCache.bytes(context).toInt()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    tone = Ios5ButtonTone.Red,
                )
            }
        }

        ios5Section("推理参数") {
            Ios5SliderRow(
                title = "最大输出 token",
                valueLabel = "${prefs.maxOutputTokens}",
                value = prefs.maxOutputTokens.toFloat(),
                range = 128f..4096f,
                steps = 30,
                onValueChangeFinished = {},
                onValueChange = { v ->
                    scope.launch {
                        container.settingsStore.setMultimodalMaxOutputTokens((v / 128).roundToInt() * 128)
                    }
                },
            )
            Ios5CellDivider()
            Ios5SliderRow(
                title = "上下文 token",
                valueLabel = "${prefs.contextTokens}",
                value = prefs.contextTokens.toFloat(),
                range = 2048f..32768f,
                steps = 14,
                onValueChangeFinished = {},
                onValueChange = { v ->
                    scope.launch {
                        container.settingsStore.setMultimodalContextTokens((v / 2048).roundToInt() * 2048)
                    }
                },
            )
            Ios5CellDivider()
            Ios5SliderRow(
                title = "采样温度",
                valueLabel = String.format("%.1f", prefs.temperature),
                value = prefs.temperature,
                range = 0f..1.5f,
                steps = 14,
                onValueChangeFinished = {},
                onValueChange = { v ->
                    scope.launch { container.settingsStore.setMultimodalTemperature((v * 10).roundToInt() / 10f) }
                },
            )
            Ios5CellDivider()
            Ios5SwitchRow(
                title = "使用 GPU 加速",
                subtitle = "默认开启。部分 Adreno 机型的 Vulkan 驱动会直接把 :llm 进程带崩；" +
                    "真崩过一次后会暂时改用 CPU，重新开关本项即可再试 GPU。",
                checked = prefs.useGpu,
                onCheckedChange = { scope.launch { container.settingsStore.setMultimodalUseGpu(it) } },
            )
            if (gpuCrashed) {
                Ios5CellDivider()
                Ios5StaticText(
                    "上次 GPU 初始化把 :llm 进程带崩了，这一轮会自动用 CPU。" +
                        "重新关掉再打开上面的开关，可以清掉标记、再试一次 GPU。",
                )
            }
            Ios5CellDivider()
            Ios5SwitchRow(
                title = "调试信息",
                subtitle = "打开后在播放页翻译层底部显示模型状态、当前段和下一段的起止时间与进度，" +
                    "同时输出诊断日志（logcat 过滤 TranslationSession / LlmService / LlmRuntime / AudioSliceStore）。",
                checked = prefs.debugInfo,
                onCheckedChange = { v ->
                    com.aurora.music.data.llm.LlmDebug.enabled = v
                    scope.launch { container.settingsStore.setMultimodalDebugInfo(v) }
                },
            )
        }

        ios5Section("占用") {
            Ios5StaticText(
                "已下载模型占用 ${String.format("%.1f", container.llmModels.installedBytes() / 1_000_000_000.0)} GB。",
            )
            Ios5CellDivider()
            Ios5StaticText(
                "模型只在本机推理，音频不会上传。加载大模型需要几秒到十几秒，" +
                    "播放页的译文会像普通 LLM 一样流式出现。模型运行在独立的 :llm 服务进程，" +
                    "关掉翻译层或 App 切到后台会自动卸载。",
            )
        }
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}

@Composable
private fun LanguageRows(current: String, onSelect: (String) -> Unit) {
    val options = listOf("简体中文", "繁體中文", "English", "日本語", "한국어")
    Column(Modifier.fillMaxWidth()) {
        Text(
            "目标语言",
            color = Ios5Colors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        options.forEachIndexed { index, label ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onSelect(label) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = Ios5Colors.TextPrimary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (current == label) {
                    Icon(Icons.Filled.Check, null, tint = Ios5Colors.IosBlue, modifier = Modifier.size(18.dp))
                }
            }
            if (index != options.lastIndex) Ios5CellDivider()
        }
    }
}

@Composable
private fun LlmModelRow(
    spec: LlmModelSpec,
    state: LlmModelStore.ModelState,
    active: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = state is LlmModelStore.ModelState.Installed
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .background(if (active) Ios5Colors.IosBlue else Ios5Colors.TextSecondary.copy(alpha = 0.25f))
                    .clickable(enabled = installed, onClick = onSelect),
                contentAlignment = Alignment.Center,
            ) {
                if (active) {
                    Icon(
                        Icons.Filled.Check,
                        null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    spec.title,
                    color = Ios5Colors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${spec.sizeGb} · ${spec.minRamGb} GB 内存起步",
                    color = Ios5Colors.TextSecondary,
                    fontSize = 12.sp,
                )
                Text(
                    spec.note,
                    color = Ios5Colors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is LlmModelStore.ModelState.Downloading -> {
                    val pct = if (state.totalBytes > 0) {
                        (state.doneBytes * 100 / state.totalBytes).coerceIn(0, 99)
                    } else 0
                    Text(
                        "$pct% · ${state.doneBytes / 1_000_000}/${state.totalBytes / 1_000_000} MB",
                        color = Ios5Colors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Ios5SmallButton(text = "取消", onClick = onCancel, tone = Ios5ButtonTone.Grey)
                }
                is LlmModelStore.ModelState.Failed -> {
                    Text(
                        state.message,
                        color = Ios5Colors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Ios5SmallButton(text = "重试", onClick = onDownload, tone = Ios5ButtonTone.Blue)
                }
                is LlmModelStore.ModelState.Installed -> {
                    Text(
                        if (active) "使用中" else "已安装",
                        color = if (active) Ios5Colors.IosBlue else Ios5Colors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Ios5SmallButton(text = "删除", onClick = onDelete, tone = Ios5ButtonTone.Grey)
                }
                else -> {
                    Spacer(Modifier.weight(1f))
                    Ios5SmallButton(text = "下载", onClick = onDownload, tone = Ios5ButtonTone.Blue)
                }
            }
        }
    }
}
