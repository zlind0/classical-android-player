package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.aurora.music.data.AlarmPrefs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.PlaybackPrefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun PlaybackSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val prefs by store.playbackPrefs.collectAsStateWithLifecycle(initialValue = PlaybackPrefs())
    val alarm by store.alarmPrefs.collectAsStateWithLifecycle(initialValue = AlarmPrefs())
    val scope = rememberCoroutineScope()

    // Hoisted: stringResource is @Composable-only and illegal in LazyColumn DSL / plain lambdas.
    val strPlaybackSection = stringResource(R.string.playback_section_playback)
    val strCrossfade = stringResource(R.string.playback_crossfade)
    val strSpeedSection = stringResource(R.string.playback_section_speed)
    val strSpeedLabel = stringResource(R.string.playback_speed_label)
    val strAlarmSection = stringResource(R.string.playback_alarm_section)
    val strAlarmTitle = stringResource(R.string.playback_alarm_title)
    val strAlarmSub = stringResource(R.string.playback_alarm_sub, formatTime(alarm.hour, alarm.minute))
    val strAlarmTime = stringResource(R.string.playback_alarm_time)

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.playback_title), onBack)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {

            item { SettingsSectionTitle(stringResource(R.string.playback_section_output)) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.HighQuality, stringResource(R.string.playback_hires), stringResource(R.string.playback_hires_sub), prefs.preferHighRes) { v ->
                        scope.launch { store.setPreferHighRes(v) }
                    }
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        Icons.Filled.Usb,
                        stringResource(R.string.playback_usb),
                        stringResource(R.string.playback_usb_sub),
                        prefs.bitPerfectUsb,
                    ) { v ->
                        scope.launch { store.setBitPerfectUsb(v) }
                        // claim usb up front so driver avoids the per-plug attach prompt
                        if (v) runCatching {
                            val dev = com.decent.usbaudio.UsbAudioDevice.getInstance(context)
                            dev.findUsbAudioDevice()?.let { if (!dev.hasPermission(it)) dev.requestPermission(it) {} }
                        }
                    }
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        Icons.Filled.Devices,
                        stringResource(R.string.playback_independent),
                        stringResource(R.string.playback_independent_sub),
                        prefs.independentOutput,
                    ) { v -> scope.launch { store.setIndependentOutput(v) } }
                }
            }
            item { SignalPathCard() }

            item { SettingsSectionTitle(strPlaybackSection) }
            item {
                SettingsSliderRow(
                    strCrossfade,
                    if (prefs.crossfadeSec == 0) stringResource(R.string.common_off) else "${prefs.crossfadeSec}s",
                    prefs.crossfadeSec.toFloat(), 0f..12f, steps = 11,
                ) { v -> scope.launch { store.setCrossfade(v.roundToInt()) } }
            }
            item {
                SettingsGroup {
                    SettingsSwitchRow(Icons.Filled.Audiotrack, stringResource(R.string.playback_gapless), stringResource(R.string.playback_gapless_sub), prefs.gapless) { v -> scope.launch { store.setGapless(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.GraphicEq, stringResource(R.string.playback_skip_silence), stringResource(R.string.playback_skip_silence_sub), prefs.skipSilence) { v -> scope.launch { store.setSkipSilence(v) } }
                    SettingsRowDivider()
                    SettingsSwitchRow(Icons.Filled.Headphones, stringResource(R.string.playback_mono), stringResource(R.string.playback_mono_sub), prefs.monoAudio) { v -> scope.launch { store.setMono(v) } }
                }
            }

            item { SettingsSectionTitle(strSpeedSection) }
            item {
                SettingsSliderRow(strSpeedLabel, "${"%.2f".format(prefs.defaultSpeed)}x", prefs.defaultSpeed, 0.5f..2.0f, steps = 5) { v ->
                    scope.launch { store.setDefaultSpeed(v) }
                }
            }

            item { SettingsSectionTitle(strAlarmSection) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(
                        Icons.Filled.Alarm,
                        strAlarmTitle,
                        strAlarmSub,
                        alarm.enabled,
                    ) { v -> scope.launch { store.setAlarm(v, alarm.hour, alarm.minute) } }
                    SettingsRowDivider()
                    SettingsNavRow(
                        Icons.Filled.Alarm, strAlarmTime, value = formatTime(alarm.hour, alarm.minute),
                    ) {
                        android.app.TimePickerDialog(
                            context, { _, h, m -> scope.launch { store.setAlarm(alarm.enabled, h, m) } },
                            alarm.hour, alarm.minute, false,
                        ).show()
                    }
                }
            }
        }
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    val h12 = when { hour % 12 == 0 -> 12; else -> hour % 12 }
    val ampm = if (hour < 12) "AM" else "PM"
    return "%d:%02d %s".format(h12, minute, ampm)
}

@Composable
private fun SignalPathCard() {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val sp by container.signalPath.collectAsStateWithLifecycle()
    if (!sp.active) return
    val strBpTitle = stringResource(R.string.playback_bp_title)
    val strSignalTitle = stringResource(R.string.playback_signal_title)
    val strStereo = stringResource(R.string.playback_ch_stereo)
    val strMono = stringResource(R.string.playback_ch_mono)
    val good = sp.bitPerfect
    val accent = if (good) Color(0xFF28D572) else MaterialTheme.colorScheme.onSurfaceVariant
    val parts = buildList {
        if (sp.codec.isNotBlank()) add(sp.codec)
        if (sp.sampleRateHz > 0) add("%.1f kHz".format(sp.sampleRateHz / 1000f))
        if (sp.bitDepth > 0) add("${sp.bitDepth}-bit")
        if (sp.channels == 2) add(strStereo) else if (sp.channels == 1) add(strMono)
    }.joinToString(" · ")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (good) Icons.Filled.Verified else Icons.Filled.GraphicEq, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (good) strBpTitle else strSignalTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent)
        }
        Spacer(Modifier.height(6.dp))
        Text("$parts  →  ${sp.output}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (sp.note.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(sp.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
