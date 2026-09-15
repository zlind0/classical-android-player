package com.aurora.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backup & restore: export settings + local playlists/likes + history to a JSON file, or import one. */
@Composable
fun BackupScreen(contentPadding: PaddingValues, onBack: () -> Unit, confirm: (String) -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }

    // Hoisted: confirm() lambdas below run in non-composable coroutine scopes.
    val strExported = ctx.getString(R.string.backup_exported)
    val strRestored = ctx.getString(R.string.backup_restored)
    val strReadFailed = ctx.getString(R.string.backup_read_failed)
    val strExport = stringResource(R.string.backup_export)
    val strExportSub = stringResource(R.string.backup_export_sub)
    val strRestore = stringResource(R.string.backup_restore)
    val strRestoreSub = stringResource(R.string.backup_restore_sub)
    val strNoAudioNote = stringResource(R.string.backup_no_audio_note)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pending; pending = null
        if (uri != null && text != null) scope.launch(Dispatchers.IO) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
            confirm(if (ok) strExported else ctx.getString(R.string.msg_export_failed))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            val ok = json != null && container.backupManager.import(json)
            confirm(if (ok) strRestored else strReadFailed)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.settings_backup), onBack)
        Column(Modifier.fillMaxWidth().padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            SettingsGroup {
                ActionRow(Icons.Filled.Backup, strExport, strExportSub) {
                    scope.launch {
                        pending = container.backupManager.export(System.currentTimeMillis())
                        exportLauncher.launch("aurora-backup.json")
                    }
                }
                SettingsRowDivider()
                ActionRow(Icons.Filled.Restore, strRestore, strRestoreSub) {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }
            }
            Text(
                strNoAudioNote,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
