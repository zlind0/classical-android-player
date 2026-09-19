package com.aurora.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
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
    val strTitle = stringResource(R.string.settings_backup)
    val strSection = stringResource(R.string.settings_backup)
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

    Ios5SettingsPage(strTitle, onBack) {
        ios5Section(strSection) {
            Ios5NavRow(strExport, subtitle = strExportSub, value = "") {
                scope.launch {
                    pending = container.backupManager.export(System.currentTimeMillis())
                    exportLauncher.launch("aurora-backup.json")
                }
            }
            Ios5CellDivider()
            Ios5NavRow(strRestore, subtitle = strRestoreSub, value = "") {
                importLauncher.launch(arrayOf("application/json", "*/*"))
            }
        }
        ios5FootNote(strNoAudioNote)
    }
}
