package com.aurora.music.ui.screens.settings

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.aurora.music.data.STORAGE_MAX_CHILDREN
import com.aurora.music.data.StorageNode
import com.aurora.music.data.StorageReport
import com.aurora.music.data.fileLabelRes
import com.aurora.music.data.scanAppStorage
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5NavRow
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

    // 存储明细：IO 线程递归统计，进页即扫
    val appContext = LocalContext.current.applicationContext
    var storageReport by remember { mutableStateOf<StorageReport?>(null) }
    var storageScanGen by remember { mutableStateOf(0) }
    var storageExpanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(storageScanGen) {
        storageReport = scanAppStorage(appContext)
    }
    val strBreakdown = stringResource(R.string.storage_breakdown)

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
                        Text(rgHeadline, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
        ios5Section(strBreakdown) {
            StorageBreakdownContent(
                report = storageReport,
                expanded = storageExpanded,
                onToggle = { rel ->
                    storageExpanded = if (rel in storageExpanded) storageExpanded - rel else storageExpanded + rel
                },
                onRescan = {
                    storageExpanded = emptySet()
                    storageScanGen++
                },
            )
        }
        ios5FootNote(strPrivateNote)
        item { Spacer(Modifier.height(bottomPad)) }
    }
}

@Composable
private fun LocalContextApp() =
    (LocalContext.current.applicationContext as AuroraApplication).container

/** 存储明细：递归展开到每个文件的大头定位器（section 卡片内的全部内容）。 */
@Composable
private fun StorageBreakdownContent(
    report: StorageReport?,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onRescan: () -> Unit,
) {
    val strRescan = stringResource(R.string.storage_rescan)
    val strScanning = stringResource(R.string.storage_scanning)
    val strRootFiles = stringResource(R.string.storage_root_files)
    val strRootCache = stringResource(R.string.storage_root_cache)

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (report == null) {
            Text(strScanning, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
        } else {
            Text(
                stringResource(R.string.storage_total_fmt, formatBytes(report.totalBytes)),
                color = Ios5Colors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            )
            val filesBytes = report.root("files")?.bytes ?: 0L
            val cacheBytes = report.root("cache")?.bytes ?: 0L
            Text(
                "$strRootFiles ${formatBytes(filesBytes)} · $strRootCache ${formatBytes(cacheBytes)}",
                color = Ios5Colors.TextSecondary, fontSize = 13.sp,
            )
        }
    }
    if (report != null) {
        report.roots.forEachIndexed { i, root ->
            if (i > 0) Ios5CellDivider()
            StorageNodeRows(node = root, depth = 0, expanded = expanded, onToggle = onToggle)
        }
        Ios5CellDivider()
    }
    Ios5ActionRow(title = strRescan, onClick = onRescan)
}

@Composable
private fun StorageNodeRows(
    node: StorageNode,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    val title = nodeTitle(node)
    val size = formatBytes(node.bytes)
    val indent = Modifier.padding(start = (depth * 14).dp)
    if (node.isDir) {
        val isOpen = node.relPath in expanded
        Box(indent) {
            Ios5NavRow(
                title = if (isOpen) "$title ▾" else "$title ▸",
                subtitle = if (node.files > 0) stringResource(R.string.storage_files_fmt, node.files) else "",
                value = size,
                onClick = { onToggle(node.relPath) },
            )
        }
        if (isOpen) {
            node.children.forEach { child ->
                Ios5CellDivider()
                StorageNodeRows(child, depth + 1, expanded, onToggle)
            }
            if (node.omitted > 0) {
                Ios5CellDivider()
                Box(Modifier.padding(start = ((depth + 1) * 14).dp)) {
                    Ios5StaticText(
                        stringResource(R.string.storage_more_fmt, node.omitted, STORAGE_MAX_CHILDREN),
                    )
                }
            }
        }
    } else {
        Box(indent) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, color = Ios5Colors.TextPrimary, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(size, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun nodeTitle(node: StorageNode): String {
    val res = node.labelRes ?: fileLabelRes(node.name)
    return when {
        res != null && node.labelArg != null -> stringResource(res, node.labelArg!!)
        res != null -> stringResource(res)
        else -> node.name
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1) return "$bytes B"
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
