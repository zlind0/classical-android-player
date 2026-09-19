package com.aurora.music.ui.screens.settings

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section

@Composable
fun StorageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalContextApp()
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val bytes = remember(downloads) { container.downloadManager.totalBytes() }

    val strTitle = stringResource(R.string.storage_title)
    val strDownloaded = stringResource(R.string.storage_downloaded_fmt, downloads.size)
    val strUsed = stringResource(R.string.storage_used_fmt, formatBytes(bytes))
    val strVolumeLeveling = stringResource(R.string.storage_volume_leveling)
    val strManage = stringResource(R.string.storage_manage)
    val strRemoveAll = stringResource(R.string.storage_remove_all)
    val strPrivateNote = stringResource(R.string.storage_private_note)
    val strRgApplies = stringResource(R.string.storage_rg_applies)
    val bottomPad = contentPadding.calculateBottomPadding()

    // ReplayGain scan measures on-device files (EBU R128) to level playback volume.
    val rg by container.replayGainScanner.progress.collectAsStateWithLifecycle()
    val strScanningRg = stringResource(R.string.storage_scanning_rg)
    val strScanRg = stringResource(R.string.storage_scan_rg)
    val strRgHint = stringResource(R.string.storage_rg_hint)
    val strCancel = stringResource(R.string.common_cancel)
    val rgRunning = rg.running
    val rgHeadline = if (rgRunning) strScanningRg else strScanRg
    val rgProgressLine = if (rgRunning) stringResource(R.string.storage_rg_progress, rg.done, rg.total, rg.current) else ""
    val rgAvg = container.replayGainStore.avgLufs()?.let { stringResource(R.string.storage_rg_avg, it) } ?: ""
    val rgDoneLine = stringResource(R.string.storage_rg_done, container.replayGainStore.size, rgAvg)
    val rgSub = when {
        rgRunning -> rgProgressLine
        container.replayGainStore.size > 0 -> rgDoneLine
        else -> strRgHint
    }
    val downloadsEmpty = downloads.isEmpty()

    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        ios5Section(strTitle) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(strDownloaded, color = Ios5Colors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(strUsed, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
            }
        }
        ios5Section(strVolumeLeveling) {
            Column(
                Modifier.fillMaxWidth().clickable(enabled = !rgRunning) { container.replayGainScanner.scan() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rgHeadline, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(rgSub, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (rgRunning) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            strCancel, fontSize = 15.sp, color = Ios5Colors.IosBlue,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { container.replayGainScanner.cancel() }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Ios5CellDivider()
            Ios5StaticText(strRgApplies)
        }
        ios5Section(strManage) {
            if (downloadsEmpty) {
                Ios5StaticText(strRemoveAll)
            } else {
                Ios5ActionRow(title = strRemoveAll, danger = true, onClick = { container.downloadManager.clearAll() })
            }
        }
        ios5FootNote(strPrivateNote)
        item { Spacer(Modifier.height(bottomPad)) }
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
