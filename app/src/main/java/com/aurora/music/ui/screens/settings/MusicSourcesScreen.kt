package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.MusicRoot
import com.aurora.music.data.ScanProgress
import com.aurora.music.data.StorageType
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

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.sources_title), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            // plan §64: permission first — without it the picker lists nothing and scans find nothing
            if (!fullOk) {
                item {
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                if (!readOk) stringResource(R.string.sources_need_read_title)
                                else stringResource(R.string.sources_need_full_title),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (!readOk) stringResource(R.string.sources_need_read_sub)
                                else stringResource(R.string.sources_need_full_sub),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()) {
                                if (!readOk) {
                                    Button(onClick = { readLauncher.launch(com.aurora.music.data.storageReadPermission()) }) {
                                        Text(stringResource(R.string.sources_grant_read))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (com.aurora.music.data.needsAllFilesRow()) {
                                    Button(onClick = { com.aurora.music.data.openAllFilesSettings(ctx) }) {
                                        Text(stringResource(R.string.sources_open_full))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { SettingsSectionTitle(stringResource(R.string.sources_scan_roots)) }
            if (roots.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.sources_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            items(roots, key = { it.id }) { root ->
                val count = counts[root.id] ?: 0
                RootCard(
                    root = root,
                    count = count,
                    progress = if (progress.running && progress.rootId == root.id) progress else null,
                    onPlay = { onPlayRoot(root.id) },
                    onScan = {
                        scanJob?.cancel()
                        scanJob = scope.launch {
                            container.rootScanner.scan(root) { container.musicRoots.progress.value = it }
                            confirm(ctx.getString(R.string.msg_scan_finished))
                        }
                    },
                    onToggle = { v -> scope.launch { container.musicRoots.setEnabled(root.id, v) } },
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
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Add, stringResource(R.string.sources_add), stringResource(R.string.sources_add_sub)) { picking = true }
                }
            }
            item {
                Text(
                    stringResource(R.string.sources_footnote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
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
    onRemove: () -> Unit,
    onClean: () -> Unit,
) {
    val icon = when (root.storageType) {
        StorageType.INTERNAL -> Icons.Filled.Storage
        StorageType.SD_CARD -> Icons.Filled.Folder
        StorageType.USB -> Icons.Filled.Usb
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(root.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(root.rootPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        stringResource(R.string.sources_tracks, count) + if (root.lastScanTime > 0) " · " + fmtTime(root.lastScanTime) else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = root.enabled, onCheckedChange = onToggle)
            }
            if (progress != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                Text(
                    stringResource(R.string.sources_scanning, progress.found, progress.current),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            SettingsRowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
                RootAction(Icons.Filled.PlayArrow, stringResource(R.string.sources_play), Modifier.weight(1f), onPlay)
                RootAction(Icons.Filled.Refresh, stringResource(R.string.sources_scan), Modifier.weight(1f), onScan)
                RootAction(Icons.Filled.Delete, stringResource(R.string.sources_clean), Modifier.weight(1f), onClean)
                Text(
                    stringResource(R.string.sources_remove),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).clickable(onClick = onRemove).padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RootAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

private fun fmtTime(ms: Long): String = runCatching {
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
}.getOrDefault("")
