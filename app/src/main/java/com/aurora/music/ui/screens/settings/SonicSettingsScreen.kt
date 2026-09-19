package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import kotlinx.coroutines.launch

@Composable
fun SonicSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = remember { (ctx.applicationContext as AuroraApplication).container }
    val progress by app.sonicEngine.progress.collectAsStateWithLifecycle()
    val analyzed by app.sonicEngine.analyzedCount.collectAsStateWithLifecycle()
    val auto by app.settingsStore.sonicAutoAnalyze.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()

    // Hoisted for the non-composable when/ifBlank branches below.
    val strTitle = stringResource(R.string.sonic_title)
    val strAnalyzed = stringResource(R.string.sonic_analyzed_fmt, analyzed)
    val strPowers = stringResource(R.string.sonic_powers)
    val strAnalyzeSection = stringResource(R.string.sonic_analyze)
    val strAnalyzeLib = stringResource(R.string.sonic_analyze_lib)
    val strAnalyzing = stringResource(R.string.sonic_analyzing)
    val strProgress = if (progress.running) stringResource(R.string.sonic_progress_fmt, progress.done, progress.total, progress.current) else ""
    val strDoneTap = stringResource(R.string.sonic_done_tap, analyzed)
    val strExtracting = stringResource(R.string.sonic_extracting)
    val strAutomation = stringResource(R.string.sonic_automation)
    val strAuto = stringResource(R.string.sonic_auto)
    val strAutoSub = stringResource(R.string.sonic_auto_sub)
    val strCancel = stringResource(R.string.common_cancel)
    val strHint = stringResource(R.string.sonic_hint)

    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        ios5Section(strAnalyzed) {
            Ios5StaticText(strPowers)
        }

        ios5Section(strAnalyzeSection) {
            Ios5NavRow(
                title = if (progress.running) strAnalyzing else strAnalyzeLib,
                subtitle = when {
                    progress.running -> strProgress
                    analyzed > 0 -> strDoneTap
                    else -> strExtracting
                },
                onClick = { if (!progress.running) app.sonicEngine.scan() },
            )
            if (progress.running) {
                Ios5CellDivider()
                Ios5ActionRow(title = strCancel, onClick = { app.sonicEngine.cancel() })
            }
        }

        ios5Section(strAutomation) {
            Ios5SwitchRow(
                title = strAuto,
                subtitle = strAutoSub,
                checked = auto,
                onCheckedChange = { v -> scope.launch { app.settingsStore.setSonicAutoAnalyze(v) } },
            )
        }

        ios5FootNote(strHint)
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}
