package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.aurora.music.data.SongIntroController
import com.aurora.music.data.SongIntroPrefs
import com.aurora.music.data.TTS_ENGINE_INTERNAL
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5CheckRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import kotlinx.coroutines.launch

@Composable
fun SongIntroVoiceScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val intro = container.songIntro
    val prefs by store.songIntroPrefs.collectAsStateWithLifecycle(initialValue = SongIntroPrefs())
    val voices by intro.voices.collectAsStateWithLifecycle()
    val installing by intro.installing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var engines by remember { mutableStateOf<List<SongIntroController.TtsEngineInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        engines = intro.listEngines()
        intro.refreshVoices()
    }

    fun engineLabel(pkg: String): String = when {
        pkg == TTS_ENGINE_INTERNAL -> "内置微软离线"
        pkg.isBlank() -> "系统默认"
        else -> engines.firstOrNull { it.packageName == pkg }?.label ?: pkg
    }

    Ios5SettingsPage("语音引擎与音色", onBack) {
        ios5Section("引擎") {
            Ios5CheckRow(
                title = "内置微软离线",
                subtitle = "随 App 打包，无需联网",
                checked = prefs.ttsEngine == TTS_ENGINE_INTERNAL,
                onClick = { scope.launch { intro.selectEngine(TTS_ENGINE_INTERNAL) } },
            )
            Ios5CellDivider()
            Ios5CheckRow(
                title = "系统默认",
                checked = prefs.ttsEngine.isBlank(),
                onClick = { scope.launch { intro.selectEngine("") } },
            )
            engines.forEach { e ->
                Ios5CellDivider()
                Ios5CheckRow(
                    title = e.label,
                    subtitle = e.packageName,
                    checked = e.packageName == prefs.ttsEngine,
                    onClick = { scope.launch { intro.selectEngine(e.packageName) } },
                )
            }
        }

        ios5Section("音色（${engineLabel(prefs.ttsEngine)}）") {
            Ios5CheckRow(
                title = "自动",
                subtitle = if (prefs.ttsEngine == TTS_ENGINE_INTERNAL) "默认晓晓" else "中文语音优先",
                checked = prefs.ttsVoice.isBlank(),
                onClick = { scope.launch { store.setIntroTtsVoice("") } },
            )
            voices.forEach { v ->
                Ios5CellDivider()
                Ios5CheckRow(
                    title = v.label,
                    checked = v.name == prefs.ttsVoice,
                    onClick = { scope.launch { store.setIntroTtsVoice(v.name) } },
                )
            }
        }

        ios5Section("音调") {
            Ios5SliderRow(
                title = "音调",
                valueLabel = String.format("%.2f", prefs.ttsPitch) + "x",
                value = prefs.ttsPitch,
                range = 0.5f..2.0f,
                steps = 15,
                onValueChange = { scope.launch { store.setIntroTtsPitch(it) } },
            )
            Ios5StaticText("语速在上一页调；语速音调音色与电子书听书共用同一设置。")
        }

        val inst = installing
        if (inst != null) {
            val (done, total, name) = inst
            ios5Section("内置语音") {
                Ios5StaticText("正在准备内置语音 $name（$done/$total），首次需释放语音数据，之后不再等待。")
            }
        }

        ios5FootNote("切换引擎后音色列表会刷新为该引擎的音色。")
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}
