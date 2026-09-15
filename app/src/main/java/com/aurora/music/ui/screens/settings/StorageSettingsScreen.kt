package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R

@Composable
fun StorageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalContextApp()
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val bytes = remember(downloads) { container.downloadManager.totalBytes() }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.storage_title), onBack)
        Column(Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DownloadDone, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.storage_downloaded_fmt, downloads.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.storage_used_fmt, formatBytes(bytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ReplayGain scan measures on-device files (EBU R128) to level playback volume.
            run {
                val rg by container.replayGainScanner.progress.collectAsStateWithLifecycle()
                SettingsSectionTitle(stringResource(R.string.storage_volume_leveling))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(enabled = !rg.running) { container.replayGainScanner.scan() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (rg.running) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (rg.running) stringResource(R.string.storage_scanning_rg) else stringResource(R.string.storage_scan_rg),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            when {
                                rg.running -> stringResource(R.string.storage_rg_progress, rg.done, rg.total, rg.current)
                                container.replayGainStore.size > 0 -> {
                                    val avg = container.replayGainStore.avgLufs()?.let { stringResource(R.string.storage_rg_avg, it) } ?: ""
                                    stringResource(R.string.storage_rg_done, container.replayGainStore.size, avg)
                                }
                                else -> stringResource(R.string.storage_rg_hint)
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    if (rg.running) {
                        Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { container.replayGainScanner.cancel() }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                Text(
                    stringResource(R.string.storage_rg_applies),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            SettingsSectionTitle(stringResource(R.string.storage_manage))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = downloads.isNotEmpty()) { container.downloadManager.clearAll() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.storage_remove_all), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.storage_private_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LocalContextApp() =
    (LocalContext.current.applicationContext as AuroraApplication).container

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
