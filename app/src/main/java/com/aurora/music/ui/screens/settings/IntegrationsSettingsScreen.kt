package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun IntegrationsSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val lrclib by container.settingsStore.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val artistEnrichment by container.settingsStore.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.integrations_title), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            item { SettingsSectionTitle(stringResource(R.string.integrations_section_lyrics)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Lyrics, stringResource(R.string.integrations_lrclib), stringResource(R.string.integrations_lrclib_sub), lrclib) { v ->
                        scope.launch { container.settingsStore.setLrclibEnabled(v) }
                    }
                }
            }
            item { SettingsSectionTitle(stringResource(R.string.integrations_section_metadata)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Person, stringResource(R.string.integrations_artist_info), stringResource(R.string.integrations_artist_info_sub), artistEnrichment) { v ->
                        scope.launch { container.settingsStore.setArtistEnrichment(v) }
                    }
                }
            }
            item { SettingsGroup { AcoustIdRow(scope) } }
        }
    }
}

@Composable
private fun AcoustIdRow(scope: CoroutineScope) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AuroraApplication).container
    val saved by container.settingsStore.acoustIdKey.collectAsStateWithLifecycle(initialValue = "")
    var key by remember(saved) { mutableStateOf(saved) }
    SettingsNavRow(
        Icons.Filled.Fingerprint, stringResource(R.string.integrations_acoustid),
        subtitle = if (saved.isNotBlank()) stringResource(R.string.integrations_acoustid_on) else stringResource(R.string.integrations_acoustid_off),
        value = "",
    ) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://acoustid.org/new-application"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it; scope.launch { container.settingsStore.setAcoustIdKey(it.trim()) } },
        label = { Text(stringResource(R.string.integrations_acoustid_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
