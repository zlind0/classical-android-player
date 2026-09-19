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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.StorageType
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5CheckRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5Section
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
    // Hoisted: used inside non-composable ifBlank {} / remember {} lambdas below.
    val strNoRoot = stringResource(R.string.picker_no_root)
    val strRootLabel = stringResource(R.string.picker_root_label)
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
    val hasRead = remember(resumeTick) { com.aurora.music.data.hasStorageRead(ctx) }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    val dirs = remember(current) { container.volumeManager.listDirs(current) ?: emptyList() }
    val canSelect = remember(current) {
        val f = File(current)
        runCatching { f.isDirectory && f.canRead() }.getOrDefault(false)
    }

    val strTitle = stringResource(R.string.picker_title)
    val strNoPermission = stringResource(R.string.picker_no_permission)
    val strGrant = stringResource(R.string.picker_grant)
    val strStorage = stringResource(R.string.picker_storage)
    val strFolder = stringResource(R.string.picker_folder)
    val strUnavailable = stringResource(R.string.picker_unavailable)
    val strUp = stringResource(R.string.picker_up)
    val strNoStorage = stringResource(R.string.picker_no_storage)
    val strNoSubfolders = stringResource(R.string.picker_no_subfolders)
    val strSelectThis = stringResource(R.string.picker_select_this)
    val bottomPad = contentPadding.calculateBottomPadding()
    val root = volume?.rootPath.orEmpty()
    val crumbs = remember(current) { crumbs(current, root, strRootLabel) }
    val crumbLine = crumbs.joinToString(" / ")
    val currentLine = current.ifBlank { strNoRoot }
    val emptyHint = if (current.isBlank()) strNoStorage else strNoSubfolders
    val selVolume = volume
    val selName = if (selVolume != null) File(current).name.ifBlank { selVolume.label } else ""
    val selLabel = if (selVolume != null) "$selName (${selVolume.label})" else ""

    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        if (!hasRead) {
            ios5Section(strTitle) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Ios5StaticText(strNoPermission)
                    Spacer(Modifier.height(8.dp))
                    Ios5GlossButton(text = strGrant, onClick = { readLauncher.launch(com.aurora.music.data.storageReadPermission()) })
                }
            }
        }
        ios5Section(strStorage) {
            volumes.forEachIndexed { vi, v ->
                Ios5CheckRow(
                    title = v.label,
                    subtitle = v.rootPath + if (!v.available) strUnavailable else "",
                    checked = v.id == volume?.id,
                    onClick = {
                        volume = v
                        current = v.rootPath
                    },
                )
                if (vi < volumes.size - 1) Ios5CellDivider()
            }
        }
        ios5Section(strFolder) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "‹ $strUp",
                        color = Ios5Colors.IosBlue, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = current != root) {
                            current = File(current).parent ?: root
                        }.padding(vertical = 6.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(currentLine, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
                        if (crumbs.size > 1) {
                            Text(crumbLine, color = Ios5Colors.IosBlue, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (dirs.isEmpty()) {
                Ios5StaticText(emptyHint)
            }
        }
        if (dirs.isNotEmpty()) {
            ios5Section(strFolder) {
                dirs.forEachIndexed { di, d ->
                    Ios5Cell(
                        title = d.name,
                        onClick = { current = d.absolutePath },
                    )
                    if (di < dirs.size - 1) Ios5CellDivider()
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = bottomPad)) {
                if (canSelect && current.isNotBlank()) {
                    Ios5GlossButton(
                        text = strSelectThis,
                        onClick = {
                            val v = selVolume ?: return@Ios5GlossButton
                            onPicked(current, selLabel, v.type)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Ios5StaticText(strSelectThis)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun crumbs(current: String, root: String, rootLabel: String): List<String> {
    if (current.isBlank() || root.isBlank()) return emptyList()
    val out = ArrayList<String>()
    var f: File? = File(current)
    while (f != null && f.absolutePath.startsWith(root)) {
        out.add(if (f.absolutePath == root) rootLabel else f.name)
        if (f.absolutePath == root) break
        f = f.parentFile
    }
    return out.reversed()
}
