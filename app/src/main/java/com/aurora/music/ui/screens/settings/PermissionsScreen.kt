package com.aurora.music.ui.screens.settings

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aurora.music.R
import com.aurora.music.ui.overlay.canDrawOverlays
import com.aurora.music.ui.overlay.openOverlaySettings
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section

@Composable
fun PermissionsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    // re-read live perm state on resume from system settings
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    refresh // recompute statuses on each bump
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val notifOk = if (Build.VERSION.SDK_INT >= 33) NotificationManagerCompat.from(ctx).areNotificationsEnabled() else true
    val audioOk = ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED
    val batteryOk = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    val exactOk = if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() else true
    val fsOk = if (Build.VERSION.SDK_INT >= 34) (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent() else true
    val overlayOk = remember(refresh) { canDrawOverlays(ctx) }

    val usbDev = remember(refresh) { com.decent.usbaudio.UsbAudioDevice.getInstance(ctx) }
    val dac = remember(refresh) { usbDev.findUsbAudioDevice() }
    val dacOk = dac != null && usbDev.hasPermission(dac)

    fun open(action: String, withPackage: Boolean = false) {
        runCatching {
            val i = Intent(action)
            if (withPackage) i.data = Uri.parse("package:" + ctx.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    val strTitle = stringResource(R.string.perms_title)
    val strIntro = stringResource(R.string.perms_intro)
    val strSection = stringResource(R.string.perms_title)
    val strUsbNote = stringResource(R.string.perms_usb_note)
    val needsAllFiles = com.aurora.music.data.needsAllFilesRow()
    val fullOk = if (needsAllFiles) com.aurora.music.data.hasAllFilesAccess(ctx) else false
    val dacSub = when {
        dac == null -> stringResource(R.string.perms_usb_none)
        dacOk -> stringResource(R.string.perms_usb_granted, dac.productName ?: "the DAC")
        else -> stringResource(R.string.perms_usb_tap, dac.productName ?: "the DAC")
    }

    Ios5SettingsPage(strTitle, onBack) {
        ios5FootNote(strIntro)
        ios5Section(strSection) {
            PermRow(stringResource(R.string.perms_notifications), stringResource(R.string.perms_notifications_sub), notifOk) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            }
            Ios5CellDivider()
            PermRow(stringResource(R.string.perms_music), stringResource(R.string.perms_music_sub), audioOk) {
                audioLauncher.launch(audioPerm)
            }
            if (needsAllFiles) {
                Ios5CellDivider()
                PermRow(
                    stringResource(R.string.perms_all_files),
                    stringResource(R.string.perms_all_files_sub),
                    fullOk,
                ) { com.aurora.music.data.openAllFilesSettings(ctx) }
            }
            Ios5CellDivider()
            PermRow(stringResource(R.string.perms_battery), stringResource(R.string.perms_battery_sub), batteryOk) {
                open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
            }
            Ios5CellDivider()
            PermRow(stringResource(R.string.perms_alarm), stringResource(R.string.perms_alarm_sub), exactOk) {
                if (Build.VERSION.SDK_INT >= 31) open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            }
            Ios5CellDivider()
            PermRow(stringResource(R.string.perms_fs_alarm), stringResource(R.string.perms_fs_alarm_sub), fsOk) {
                if (Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
            }
            Ios5CellDivider()
            PermRow(stringResource(R.string.perms_overlay), stringResource(R.string.perms_overlay_sub), overlayOk) {
                openOverlaySettings(ctx)
            }
            Ios5CellDivider()
            PermRow(stringResource(R.string.perms_usb), dacSub, dacOk, enabled = dac != null) {
                dac?.let { usbDev.requestPermission(it) { refresh++ } }
            }
        }
        ios5FootNote(strUsbNote)
    }
}

@Composable
private fun PermRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    enabled: Boolean = true,
    onGrant: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (granted) {
                Text("✓ " + stringResource(R.string.common_granted), color = Ios5Colors.IosBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            } else if (enabled) {
                Ios5GlossButton(stringResource(R.string.common_grant), onClick = onGrant)
            }
        }
    }
}
