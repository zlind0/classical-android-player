package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    username: String,
    server: String,
    onBack: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenEq: () -> Unit,
    onOpenVisualizer: () -> Unit,
    onOpenSonic: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenGestures: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.aurora.music.AuroraApplication).container
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.settings_title), onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onOpenProfile).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!session?.imageUrl.isNullOrBlank()) {
                            com.aurora.music.ui.components.Artwork(session!!.imageUrl, MaterialTheme.colorScheme.primary, Modifier.matchParentSize(), corner = 28.dp)
                        } else {
                            Text(username.take(2).uppercase().ifBlank { "ME" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(username.ifBlank { stringResource(R.string.common_listener) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.settings_view_profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(stringResource(R.string.settings_badge_local), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.settings_section_audio)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.PlayCircle, stringResource(R.string.settings_playback_quality), stringResource(R.string.settings_playback_sub), onClick = onOpenPlayback)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Tune, stringResource(R.string.settings_eq), stringResource(R.string.settings_eq_sub), onClick = onOpenEq)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.GraphicEq, stringResource(R.string.settings_visualizer), stringResource(R.string.settings_visualizer_sub), onClick = onOpenVisualizer)
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.settings_section_discovery)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.AutoAwesome, stringResource(R.string.settings_sonic), stringResource(R.string.settings_sonic_sub), onClick = onOpenSonic)
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.settings_section_library)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.MergeType, stringResource(R.string.settings_music_sources), stringResource(R.string.settings_music_sources_sub), onClick = onOpenSources)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Download, stringResource(R.string.settings_downloads_storage), stringResource(R.string.settings_downloads_sub, downloads.size), onClick = onOpenDownloads)
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.settings_section_interface)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Palette, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_sub), onClick = onOpenAppearance)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.TouchApp, stringResource(R.string.settings_gestures), stringResource(R.string.settings_gestures_sub), onClick = onOpenGestures)
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.settings_section_connections)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Extension, stringResource(R.string.settings_integrations), stringResource(R.string.settings_integrations_sub), onClick = onOpenIntegrations)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Lock, stringResource(R.string.settings_permissions), stringResource(R.string.settings_permissions_sub), onClick = onOpenPermissions)
                    SettingsRowDivider()
                    SettingsNavRow(Icons.Filled.Info, stringResource(R.string.settings_about), value = "v1.0", onClick = onOpenAbout)
                }
            }

            item { SettingsSectionTitle(stringResource(R.string.settings_section_data)) }
            item {
                SettingsGroup {
                    SettingsNavRow(Icons.Filled.Backup, stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_sub), onClick = onOpenBackup)
                }
            }
        }
    }
}
