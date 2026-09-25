package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SongIntroPrefs
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5CheckRow
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import kotlinx.coroutines.launch

@Composable
fun SongIntroSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    confirm: (String) -> Unit = {},
    onOpenPrompt: () -> Unit = {},
    onOpenVoice: () -> Unit = {},
    onOpenLog: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val intro = container.songIntro
    val prefs by store.songIntroPrefs.collectAsStateWithLifecycle(initialValue = SongIntroPrefs())
    val logCount by container.songIntroLog.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var endpoint by remember(prefs) { mutableStateOf(prefs.llmEndpoint) }
    var apiKey by remember(prefs) { mutableStateOf(prefs.llmApiKey) }
    var model by remember(prefs) { mutableStateOf(prefs.llmModel) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    Ios5SettingsPage("歌曲介绍", onBack) {
        ios5Section("解说接口（OpenAI 兼容）") {
            Ios5TextRow(
                title = "接口地址",
                value = endpoint,
                placeholder = "https://api.openai.com/v1",
                onValueChange = { endpoint = it; scope.launch { store.setIntroEndpoint(it) } },
            )
            Ios5CellDivider()
            Ios5TextRow(
                title = "API Key",
                value = apiKey,
                placeholder = "sk-…（本地无鉴权可留空）",
                onValueChange = { apiKey = it; scope.launch { store.setIntroApiKey(it) } },
            )
            Ios5CellDivider()
            Ios5TextRow(
                title = "模型",
                value = model,
                placeholder = "手动填写，或下方获取后勾选",
                onValueChange = { model = it; scope.launch { store.setIntroModel(it) } },
            )
            Ios5CellDivider()
            Ios5ActionRow(
                title = if (fetching) "正在获取模型列表…" else "获取端点中的模型列表",
                onClick = {
                    if (fetching) return@Ios5ActionRow
                    fetching = true
                    scope.launch {
                        intro.fetchModels()
                            .onSuccess { models = it; confirm("获取到 ${it.size} 个模型") }
                            .onFailure { confirm("获取失败：${it.message}") }
                        fetching = false
                    }
                },
            )
            models?.let { list ->
                if (list.isNotEmpty()) {
                    Ios5CellDivider()
                    Ios5StaticText("当前：${prefs.llmModel.ifBlank { "未选择" }}")
                    list.take(200).forEach { m ->
                        Ios5CellDivider()
                        Ios5CheckRow(
                            title = m,
                            checked = m == prefs.llmModel,
                            onClick = { scope.launch { store.setIntroModel(m) } },
                        )
                    }
                }
            }
            Ios5CellDivider()
            Ios5ActionRow(
                title = if (testing) "正在测试连接…" else "测试连接",
                onClick = {
                    if (testing) return@Ios5ActionRow
                    testing = true
                    scope.launch {
                        intro.testConnection()
                            .onSuccess { confirm("连接正常：$it") }
                            .onFailure { confirm("连接失败：${it.message}") }
                        testing = false
                    }
                },
            )
        }

        ios5Section("提示词") {
            Ios5NavRow(
                title = "系统提示词",
                subtitle = if (prefs.promptCustomized) "已自定义，点击编辑" else "默认，点击编辑",
                onClick = onOpenPrompt,
            )
        }

        ios5Section("语音（系统 TTS）") {
            Ios5SliderRow(
                title = "语速",
                valueLabel = String.format("%.2f", prefs.ttsRate) + "x",
                value = prefs.ttsRate,
                range = 0.5f..2.0f,
                steps = 15,
                onValueChange = { scope.launch { store.setIntroTtsRate(it) } },
            )
            Ios5CellDivider()
            Ios5NavRow(
                title = "语音引擎与音色",
                subtitle = prefs.ttsVoice.ifBlank { "系统默认" },
                onClick = onOpenVoice,
            )
            Ios5CellDivider()
            Ios5ActionRow(
                title = "试听当前语音",
                onClick = {
                    intro.testTts("这里是歌曲介绍试听。如果能听到这句话，说明语音与语速设置已经生效。")
                },
            )
        }

        ios5Section("记录") {
            Ios5NavRow(
                title = "解说记录",
                subtitle = if (logCount.isEmpty()) "暂无" else "最近 ${logCount.size} 条",
                onClick = onOpenLog,
            )
        }

        ios5FootNote("播放页循环与喜欢之间的问号即解说键；播报时变为停止键，点击中断并继续放音乐。朗读用系统语音，首个标点出现即逐句开播。")
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}
