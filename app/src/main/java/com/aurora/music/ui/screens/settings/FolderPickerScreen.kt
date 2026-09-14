package com.aurora.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.music.AuroraApplication
import com.aurora.music.data.StorageType
import com.aurora.music.data.StorageVolume
import com.aurora.music.data.hasStorageRead
import com.aurora.music.data.storageReadPermission
import java.io.File

// Classical fork v0.3 (plan §4/§56): traditional file-system folder picker.
// The user browses one volume at a time; confirming adds exactly that directory
// as a MusicRoot — nothing above it is ever scanned.
@Composable
fun FolderPickerScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPicked: (path: String, displayName: String, type: StorageType) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val volumes = remember { container.volumeManager.volumes() }
    var volume by remember { mutableStateOf(volumes.firstOrNull()) }
    var current by remember(volume) { mutableStateOf(volume?.rootPath ?: "") }

    // plan §64-65: listing needs read permission, and browsing shared folders on
    // API 30+ additionally needs All-files access
    val ctx = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    resumeTick
    val hasRead = remember(resumeTick) { hasStorageRead(ctx) }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    val dirs = remember(current) { container.volumeManager.listDirs(current) ?: emptyList() }
    val canSelect = remember(current) {
        val f = File(current)
        runCatching { f.isDirectory && f.canRead() }.getOrDefault(false)
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar("Select music folder", onBack)
        if (!hasRead) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    "Storage permission not granted — the folder list will be empty.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { readLauncher.launch(storageReadPermission()) }) {
                    Text("Grant read access")
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            item { SettingsSectionTitle("Storage") }
            items(volumes, key = { it.id }) { v ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        volume = v
                        current = v.rootPath
                    }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(v.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            v.rootPath + if (!v.available) " (unavailable)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (v.id == volume?.id) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            item { SettingsSectionTitle("Folder") }
            item {
                // breadcrumb: jump to any ancestor (never above the volume root)
                val root = volume?.rootPath.orEmpty()
                val crumbs = remember(current) { crumbs(current, root) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Up",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = current != root) {
                            current = File(current).parent ?: root
                        }.padding(8.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.ifBlank { "(no storage found)" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (crumbs.size > 1) {
                            Text(
                                crumbs.joinToString(" / "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (dirs.isEmpty()) {
                item {
                    Text(
                        if (current.isBlank()) "No readable storage volumes found."
                        else "No subfolders — you can select this folder itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(dirs, key = { it.absolutePath }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { current = d.absolutePath }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        Button(
            onClick = {
                val v = volume ?: return@Button
                val name = File(current).name.ifBlank { v.label }
                onPicked(current, "$name (${v.label})", v.type)
            },
            enabled = canSelect && current.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
            Text("Select this folder")
        }
    }
}

private fun crumbs(current: String, root: String): List<String> {
    if (current.isBlank() || root.isBlank()) return emptyList()
    val out = ArrayList<String>()
    var f: File? = File(current)
    while (f != null && f.absolutePath.startsWith(root)) {
        out.add(if (f.absolutePath == root) "(root)" else f.name)
        if (f.absolutePath == root) break
        f = f.parentFile
    }
    return out.reversed()
}
