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
import com.aurora.music.data.DEFAULT_SONG_INTRO_PROMPT
import com.aurora.music.data.SongIntroPrefs
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import kotlinx.coroutines.launch

@Composable
fun SongIntroPromptScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val prefs by store.songIntroPrefs.collectAsStateWithLifecycle(initialValue = SongIntroPrefs())
    val scope = rememberCoroutineScope()

    var prompt by remember(prefs) { mutableStateOf(prefs.systemPrompt) }

    Ios5SettingsPage("系统提示词", onBack) {
        ios5Section("编辑") {
            Ios5TextRow(
                title = "系统提示词",
                value = prompt,
                singleLine = false,
                onValueChange = { prompt = it; scope.launch { store.setIntroPrompt(it) } },
            )
            Ios5CellDivider()
            Ios5ActionRow(
                title = "恢复默认提示词",
                onClick = { scope.launch { store.setIntroPrompt(DEFAULT_SONG_INTRO_PROMPT) } },
            )
        }

        ios5FootNote("修改即时生效，下次点问号就用新提示词。")
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}
