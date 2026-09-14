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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
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

    if (picking) {
        FolderPickerScreen(
            contentPadding = contentPadding,
            onBack = { picking = false },
            onPicked = { path, name, type ->
                scope.launch {
                    val added = container.musicRoots.addRoot(path, name, type)
                    if (added == null) {
                        confirm("That folder is already a music source")
                    } else {
                        picking = false
                        confirm("Added — scanning…")
                        scanJob?.cancel()
                        scanJob = scope.launch {
                            container.rootScanner.scan(added) { container.musicRoots.progress.value = it }
                            val n = container.musicRoots.songsOf(added.id).size
                            confirm(if (n > 0) "Found $n tracks" else "No audio files in that folder")
                        }
                    }
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Music sources", onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle("Scan roots") }
            if (roots.isEmpty()) {
                item {
                    Text(
                        "No folders yet. Add the directories holding your music — " +
                            "internal storage, SD card or USB — and only those will be scanned.",
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
                            confirm("Scan finished")
                        }
                    },
                    onToggle = { v -> scope.launch { container.musicRoots.setEnabled(root.id, v) } },
                    onRemove = {
                        scanJob?.cancel()
                        scope.launch {
                            container.musicRoots.removeRoot(root.id)
                            confirm("Removed")
                        }
                    },
                    onClean = {
                        scope.launch {
                            val n = container.musicRoots.cleanMissing(root.id)
                            confirm(if (n > 0) "Removed $n missing files" else "Nothing missing")
                        }
                    },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Add, "Add music folder", "Internal, SD card or USB") { picking = true }
                }
            }
            item {
                Text(
                    "Scanning stays inside the folders above. Files elsewhere on the device are ignored.",
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
                        "$count tracks" + if (root.lastScanTime > 0) " · " + fmtTime(root.lastScanTime) else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = root.enabled, onCheckedChange = onToggle)
            }
            if (progress != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                Text(
                    "Scanning… ${progress.found} files · ${progress.current}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            SettingsRowDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
                RootAction(Icons.Filled.PlayArrow, "Play", Modifier.weight(1f), onPlay)
                RootAction(Icons.Filled.Refresh, "Scan", Modifier.weight(1f), onScan)
                RootAction(Icons.Filled.Delete, "Clean", Modifier.weight(1f), onClean)
                Text(
                    "Remove",
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
