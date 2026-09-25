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
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5CheckRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
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
    val scope = rememberCoroutineScope()

    var engines by remember { mutableStateOf<List<SongIntroController.TtsEngineInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        engines = intro.listEngines()
        intro.refreshVoices()
    }

    fun engineLabel(pkg: String): String =
        if (pkg.isBlank()) "系统默认" else engines.firstOrNull { it.packageName == pkg }?.label ?: pkg

    Ios5SettingsPage("语音引擎与音色", onBack) {
        ios5Section("引擎") {
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
                title = "系统默认",
                subtitle = "中文语音优先",
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

        ios5FootNote("切换引擎后音色列表会刷新为该引擎的音色。")
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}
