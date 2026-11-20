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
import com.aurora.music.data.subtitle.AsrModelCatalog
import com.aurora.music.data.subtitle.AsrModelSpec
import com.aurora.music.data.subtitle.AsrModelStore
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5SmallButton
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.Ios5ButtonTone
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 设置 → 有声书设置 → 离线字幕。
 *
 * 运行时（sherpa-onnx）随 APK；模型权重按需下载（HF / hf-mirror），
 * 只保留一个"当前使用"的模型，其余可以随时删。
 */
@Composable
fun SubtitleSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val prefs by container.settingsStore.subtitlePrefs.collectAsStateWithLifecycle(
        initialValue = SettingsStore.SubtitlePrefs(),
    )
    val states by container.asrModels.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val activeSpec = remember(prefs.modelId, states) { container.asrModels.pickUsable(prefs.modelId) }
    var cacheBytes by remember { mutableIntStateOf(container.subtitleStore.bytes().toInt()) }
    var cacheChapters by remember { mutableIntStateOf(container.subtitleStore.chapterCount()) }

    Ios5SettingsPage(title = "离线字幕", onBack = onBack) {
        ios5Section("字幕") {
            Ios5SwitchRow(
                title = "播放页显示字幕按钮",
                subtitle = "关掉后播放页隐藏字幕键，也不再后台转写",
                checked = prefs.enabled,
                onCheckedChange = { v -> scope.launch { container.settingsStore.setSubtitleEnabled(v) } },
            )
            Ios5CellDivider()
            Ios5SliderRow(
                title = "提前识别量",
                valueLabel = "${prefs.lookAheadSec} 秒",
                value = prefs.lookAheadSec.toFloat(),
                range = 20f..300f,
                // 20..300 step 20 → 15 个位置 → steps = 13
                steps = 13,
                onValueChangeFinished = {},
                onValueChange = { v ->
                    scope.launch {
                        container.settingsStore.setSubtitleLookAhead((v / 20).roundToInt() * 20)
                    }
                },
            )
            Ios5StaticText("字幕始终领先播放位置这么多秒。越大越不容易卡顿，代价是 CPU 先干活；默认 60 秒。")
            Ios5CellDivider()
            Ios5SliderRow(
                title = "识别线程",
                valueLabel = "${prefs.threads} 线程",
                value = prefs.threads.toFloat(),
                range = 1f..4f,
                steps = 2,
                onValueChangeFinished = {},
                onValueChange = { v ->
                    scope.launch { container.settingsStore.setSubtitleThreads(v.roundToInt()) }
                },
            )
        }

        ios5Section("模型") {
            Ios5StaticText(
                if (activeSpec != null) "当前使用：${activeSpec.title}（${activeSpec.sizeMb} MB）"
                else "还没有可用的模型，先下载一个（只需下载一次，之后完全离线）。",
            )
            AsrModelCatalog.all.forEach { spec ->
                Ios5CellDivider()
                ModelRow(
                    spec = spec,
                    state = states[spec.id] ?: if (container.asrModels.isInstalled(spec)) {
                        AsrModelStore.ModelState.Installed
                    } else AsrModelStore.ModelState.NotInstalled,
                    active = activeSpec?.id == spec.id,
                    onSelect = { scope.launch { container.settingsStore.setSubtitleModelId(spec.id) } },
                    onDownload = {
                        // App 级作用域：离开本页/切后台下载都继续，进度用 states 驱动
                        container.asrModels.startDownload(spec, prefs.source)
                    },
                    onCancel = { container.asrModels.cancel() },
                    onDelete = {
                        scope.launch {
                            container.asrModels.delete(spec)
                            if (prefs.modelId == spec.id) container.settingsStore.setSubtitleModelId("")
                        }
                    },
                )
            }
        }

        ios5Section("下载源") {
            AsrModelStore.SOURCES.forEach { src ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { scope.launch { container.settingsStore.setSubtitleSource(src.id) } }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        src.label, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (prefs.source == src.id) {
                        Icon(Icons.Filled.Check, null, tint = Ios5Colors.IosBlue, modifier = Modifier.size(18.dp))
                    }
                }
                Ios5CellDivider()
            }
            Ios5StaticText("ModelScope 的 iic/SenseVoiceSmall 只有 PyTorch 权重，Android 需要 sherpa-onnx 的 ONNX，所以这里走 HuggingFace；国内用 hf-mirror 镜像。")
        }

        ios5Section("字幕缓存") {
            Ios5StaticText(
                if (cacheChapters == 0) "还没有生成过字幕。"
                else "已生成 $cacheChapters 章，占用 ${cacheBytes / 1024 / 1024} MB。"
            )
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Ios5SmallButton(
                    text = "清除全部字幕缓存",
                    onClick = {
                        container.subtitleStore.clearAll()
                        cacheBytes = container.subtitleStore.bytes().toInt()
                        cacheChapters = container.subtitleStore.chapterCount()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    tone = Ios5ButtonTone.Red,
                )
            }
        }

        ios5FootNote("字幕在本机生成，不上传音频。每章转好的字幕会缓存，重新听同一章不用再算。")
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}

@Composable
private fun ModelRow(
    spec: AsrModelSpec,
    state: AsrModelStore.ModelState,
    active: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = state is AsrModelStore.ModelState.Installed
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .background(if (active) Ios5Colors.IosBlue else Ios5Colors.TextSecondary.copy(alpha = 0.25f))
                    .clickable(enabled = installed, onClick = onSelect),
                contentAlignment = Alignment.Center,
            ) {
                if (active) Icon(Icons.Filled.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    spec.title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "${spec.sizeMb} MB · ${spec.languages}",
                    color = Ios5Colors.TextSecondary, fontSize = 12.sp,
                )
                Text(spec.note, color = Ios5Colors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is AsrModelStore.ModelState.Downloading -> {
                    val pct = if (state.totalBytes > 0) {
                        (state.doneBytes * 100 / state.totalBytes).coerceIn(0, 99)
                    } else 0
                    Text(
                        "$pct% · ${state.doneBytes / 1024 / 1024}/${state.totalBytes / 1024 / 1024} MB",
                        color = Ios5Colors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    Ios5SmallButton(text = "取消", onClick = onCancel, tone = Ios5ButtonTone.Grey)
                }
                is AsrModelStore.ModelState.Failed -> {
                    Text(
                        state.message, color = Ios5Colors.TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Ios5SmallButton(text = "重试", onClick = onDownload, tone = Ios5ButtonTone.Blue)
                }
                is AsrModelStore.ModelState.Installed -> {
                    Text(
                        if (active) "使用中" else "已安装",
                        color = if (active) Ios5Colors.IosBlue else Ios5Colors.TextSecondary,
                        fontSize = 12.sp, modifier = Modifier.weight(1f),
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
