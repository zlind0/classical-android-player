package com.aurora.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.MusicRoot
import com.aurora.music.data.ScanProgress
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5MiniButton
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Classical fork v0.3 (plan §55-56): the user's scan roots. Only these
// directories (and their children) ever enter the library (plan §6).
@Composable
fun MusicSourcesScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlayRoot: (Long) -> Unit,
    confirm: (String) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val roots by container.musicRoots.roots.collectAsStateWithLifecycle()
    val counts by container.musicRoots.trackCounts.collectAsStateWithLifecycle()
    val progress by container.musicRoots.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    // re-check access when coming back from system settings
    val owner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val ctx = LocalContext.current
    resumeTick // recompute below on each resume
    val readOk = remember(resumeTick) { com.aurora.music.data.hasStorageRead(ctx) }
    val fullOk = remember(resumeTick) { com.aurora.music.data.canScanStorage(ctx) }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    if (picking) {
        FolderPickerScreen(
            contentPadding = contentPadding,
            onBack = { picking = false },
            onPicked = { path, name, type ->
                scope.launch {
                    val added = container.musicRoots.addRoot(path, name, type)
                    if (added == null) {
                        confirm(ctx.getString(R.string.msg_source_exists))
                    } else {
                        picking = false
                        confirm(ctx.getString(R.string.msg_source_added))
                        scanJob?.cancel()
                        scanJob = scope.launch {
                            container.rootScanner.scan(added) { container.musicRoots.progress.value = it }
                            val n = container.musicRoots.songsOf(added.id).size
                            confirm(if (n > 0) ctx.getString(R.string.msg_found_n_tracks, n) else ctx.getString(R.string.msg_no_audio_in_folder))
                        }
                    }
                }
            },
        )
        return
    }

    val strTitle = stringResource(R.string.sources_title)
    val strNeedReadTitle = stringResource(R.string.sources_need_read_title)
    val strNeedReadSub = stringResource(R.string.sources_need_read_sub)
    val strNeedFullTitle = stringResource(R.string.sources_need_full_title)
    val strNeedFullSub = stringResource(R.string.sources_need_full_sub)
    val strGrantRead = stringResource(R.string.sources_grant_read)
    val strOpenFull = stringResource(R.string.sources_open_full)
    val strScanRoots = stringResource(R.string.sources_scan_roots)
    val strEmptyHint = stringResource(R.string.sources_empty_hint)
    val strAdd = stringResource(R.string.sources_add)
    val strAddSub = stringResource(R.string.sources_add_sub)
    val strFootnote = stringResource(R.string.sources_footnote)
    val permTitle = if (!readOk) strNeedReadTitle else strNeedFullTitle
    val permSub = if (!readOk) strNeedReadSub else strNeedFullSub
    val showGrantRead = !readOk
    val showAllFilesRow = com.aurora.music.data.needsAllFilesRow()
    val bottomPad = contentPadding.calculateBottomPadding()

    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        // plan §64: permission first — without it the picker lists nothing and scans find nothing
        if (!fullOk) {
            ios5Section(permTitle) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Ios5StaticText(permSub)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        if (showGrantRead) {
                            Ios5GlossButton(text = strGrantRead, onClick = { readLauncher.launch(com.aurora.music.data.storageReadPermission()) })
                            Spacer(Modifier.width(8.dp))
                        }
                        if (showAllFilesRow) {
                            Ios5GlossButton(text = strOpenFull, onClick = { com.aurora.music.data.openAllFilesSettings(ctx) })
                        }
                    }
                }
            }
        }
        if (roots.isEmpty()) {
            ios5Section(strScanRoots) {
                Ios5StaticText(strEmptyHint)
            }
        } else {
            roots.forEach { root ->
                val count = counts[root.id] ?: 0
                val active = progress.running && progress.rootId == root.id
                ios5Section(root.displayName) {
                    RootCard(
                        root = root,
                        count = count,
                        progress = if (active) progress else null,
                        onPlay = { onPlayRoot(root.id) },
                        onScan = {
                            scanJob?.cancel()
                            scanJob = scope.launch {
                                container.rootScanner.scan(root) { container.musicRoots.progress.value = it }
                                confirm(ctx.getString(R.string.msg_scan_finished))
                            }
                        },
                        onToggle = { v -> scope.launch { container.musicRoots.setEnabled(root.id, v) } },
                        onMerge = { v -> scope.launch { container.musicRoots.setMergeTitles(root.id, v) } },
                        onRemove = {
                            scanJob?.cancel()
                            scope.launch {
                                container.musicRoots.removeRoot(root.id)
                                confirm(ctx.getString(R.string.msg_source_removed))
                            }
                        },
                        onClean = {
                            scope.launch {
                                val n = container.musicRoots.cleanMissing(root.id)
                                confirm(if (n > 0) ctx.getString(R.string.msg_cleaned_n, n) else ctx.getString(R.string.msg_nothing_missing))
                            }
                        },
                    )
                }
            }
        }
        ios5Section(strAdd) {
            Ios5NavRow(title = strAdd, subtitle = strAddSub, onClick = { picking = true })
        }
        ios5FootNote(strFootnote)
        item { Spacer(Modifier.height(bottomPad)) }
    }
}

@Composable
private fun RootCard(
    root: MusicRoot,
    count: Int,
    progress: ScanProgress?,
    onPlay: () -> Unit,
    onScan: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMerge: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onClean: () -> Unit,
) {
    val strTracks = stringResource(R.string.sources_tracks, count)
    val strScanning = if (progress != null) stringResource(R.string.sources_scanning, progress.found, progress.current) else ""
    val strPlay = stringResource(R.string.sources_play)
    val strScan = stringResource(R.string.sources_scan)
    val strClean = stringResource(R.string.sources_clean)
    val strRemove = stringResource(R.string.sources_remove)
    val timeSuffix = if (root.lastScanTime > 0) " · " + fmtTime(root.lastScanTime) else ""
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(root.rootPath, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(strTracks + timeSuffix, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
        }
        Ios5SwitchRow(
            title = root.displayName,
            subtitle = "",
            checked = root.enabled,
            onCheckedChange = onToggle,
        )
        Ios5CellDivider()
        Ios5SwitchRow(
            title = stringResource(R.string.sources_merge),
            subtitle = stringResource(R.string.sources_merge_sub),
            checked = root.mergeTitles,
            onCheckedChange = onMerge,
        )
        if (progress != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Ios5StaticText(strScanning)
        }
        Ios5CellDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) { Ios5MiniButton(strPlay, Icons.Filled.PlayArrow, onPlay) }
            Box(Modifier.weight(1f)) { Ios5MiniButton(strScan, Icons.Filled.Refresh, onScan) }
            Box(Modifier.weight(1f)) { Ios5MiniButton(strClean, Icons.Filled.CleaningServices, onClean) }
            Box(Modifier.weight(1f)) { Ios5MiniButton(strRemove, Icons.Filled.Delete, onRemove, danger = true) }
        }
    }
}

private fun fmtTime(ms: Long): String = runCatching {
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
}.getOrDefault("")
