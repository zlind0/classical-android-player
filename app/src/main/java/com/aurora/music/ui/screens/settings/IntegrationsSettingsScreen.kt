package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5Section
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun IntegrationsSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val lrclib by container.settingsStore.lrclibEnabled.collectAsStateWithLifecycle(initialValue = true)
    val artistEnrichment by container.settingsStore.artistEnrichment.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()

    val strTitle = stringResource(R.string.integrations_title)
    val strLyricsSection = stringResource(R.string.integrations_section_lyrics)
    val strMetadataSection = stringResource(R.string.integrations_section_metadata)
    val strAcoustIdSection = stringResource(R.string.integrations_acoustid)

    Ios5SettingsPage(strTitle, onBack) {
        ios5Section(strLyricsSection) {
            Ios5SwitchRow(stringResource(R.string.integrations_lrclib), stringResource(R.string.integrations_lrclib_sub), lrclib) { v ->
                scope.launch { container.settingsStore.setLrclibEnabled(v) }
            }
        }
        ios5Section(strMetadataSection) {
            Ios5SwitchRow(stringResource(R.string.integrations_artist_info), stringResource(R.string.integrations_artist_info_sub), artistEnrichment) { v ->
                scope.launch { container.settingsStore.setArtistEnrichment(v) }
            }
        }
        ios5Section(strAcoustIdSection) {
            AcoustIdRow(scope)
        }
    }
}

@Composable
private fun AcoustIdRow(scope: CoroutineScope) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AuroraApplication).container
    val saved by container.settingsStore.acoustIdKey.collectAsStateWithLifecycle(initialValue = "")
    var key by remember(saved) { mutableStateOf(saved) }
    Ios5NavRow(
        stringResource(R.string.integrations_acoustid),
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
    Ios5CellDivider()
    Ios5TextRow(
        title = stringResource(R.string.integrations_acoustid_label),
        value = key,
        placeholder = "",
        singleLine = true,
        onValueChange = { key = it; scope.launch { container.settingsStore.setAcoustIdKey(it.trim()) } },
    )
}
