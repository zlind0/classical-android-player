package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
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
    val strAnalyzed = stringResource(R.string.sonic_analyzed_fmt, analyzed)
    val strPowers = stringResource(R.string.sonic_powers)
    val strAnalyzeSection = stringResource(R.string.sonic_analyze)
    val strAnalyzeLib = stringResource(R.string.sonic_analyze_lib)
    val strAnalyzing = stringResource(R.string.sonic_analyzing)
    val strProgress = if (progress.running) stringResource(R.string.sonic_progress_fmt, progress.done, progress.total, progress.current) else ""
    val strDoneTap = stringResource(R.string.sonic_done_tap, analyzed)
    val strExtracting = stringResource(R.string.sonic_extracting)
    val strAutomation = stringResource(R.string.sonic_automation)

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.sonic_title), onBack)
        Column(Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(strAnalyzed, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(strPowers, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SettingsSectionTitle(strAnalyzeSection)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = !progress.running) { app.sonicEngine.scan() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (progress.running) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (progress.running) strAnalyzing else strAnalyzeLib,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    )
                    Text(
                        when {
                            progress.running -> strProgress
                            analyzed > 0 -> strDoneTap
                            else -> strExtracting
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress.running) {
                    Text(
                        stringResource(R.string.common_cancel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { app.sonicEngine.cancel() }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            SettingsSectionTitle(strAutomation)
            SettingsGroup {
                SettingsSwitchRow(
                    Icons.Filled.AutoAwesome, stringResource(R.string.sonic_auto),
                    stringResource(R.string.sonic_auto_sub), auto,
                ) { v -> scope.launch { app.settingsStore.setSonicAutoAnalyze(v) } }
            }

            Text(
                stringResource(R.string.sonic_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
