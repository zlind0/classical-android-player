package com.aurora.music.ui.screens.player

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aurora.music.R

private data class OutputDevice(val id: Int, val label: String, val icon: ImageVector)

@Composable
fun PlayerCastButton(modifier: Modifier = Modifier) {
    // Chromecast removed in the local-only fork; the output sheet covers device choice.
    Spacer(modifier)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OutputDeviceSheet(currentId: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val automaticLabel = stringResource(R.string.sheet_output_automatic)
    val devices = remember(automaticLabel) {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val list = mutableListOf(OutputDevice(0, automaticLabel, Icons.Filled.Check))
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in USEFUL_TYPES }
            .forEach { list.add(OutputDevice(it.id, deviceLabel(context, it), deviceIcon(it.type))) }
        list
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.sheet_play_on), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            devices.forEach { d ->
                val selected = d.id == currentId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onSelect(d.id); onDismiss() }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Icon(d.icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(d.label, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SleepTimerSheet(
    currentMinutes: Int,
    endOfTrack: Boolean,
    onSelect: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        0 to stringResource(R.string.sheet_sleep_off),
        5 to stringResource(R.string.sheet_sleep_5),
        15 to stringResource(R.string.sheet_sleep_15),
        30 to stringResource(R.string.sheet_sleep_30),
        45 to stringResource(R.string.sheet_sleep_45),
        60 to stringResource(R.string.sheet_sleep_60),
    )
    val status = when {
        endOfTrack -> stringResource(R.string.sheet_sleep_end_of_track)
        currentMinutes > 0 -> stringResource(R.string.sheet_sleep_in, currentMinutes)
        else -> stringResource(R.string.sheet_sleep_hint)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.sheet_sleep_timer), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 4.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                options.forEach { (min, label) ->
                    val selected = !endOfTrack && min == currentMinutes
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onSelect(min); onDismiss() }.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (endOfTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onEndOfTrack(); onDismiss() }.padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.sheet_end_of_track), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (endOfTrack) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

private val USEFUL_TYPES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_DOCK,
)

private fun deviceLabel(context: android.content.Context, d: AudioDeviceInfo): String {
    val product = d.productName?.toString()?.trim().orEmpty()
    return when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> context.getString(R.string.sheet_output_speaker)
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.sheet_output_wired)
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
            if (product.isNotBlank()) context.getString(R.string.sheet_output_usb, product) else context.getString(R.string.sheet_output_usb_generic)
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> product.ifBlank { context.getString(R.string.sheet_output_bluetooth) }
        AudioDeviceInfo.TYPE_HEARING_AID -> context.getString(R.string.sheet_output_hearing_aid)
        AudioDeviceInfo.TYPE_DOCK -> context.getString(R.string.sheet_output_dock)
        else -> product.ifBlank { context.getString(R.string.sheet_output_other) }
    }
}

private fun deviceIcon(type: Int): ImageVector = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_DOCK -> Icons.Filled.Speaker
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Icons.Filled.Usb
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> Icons.Filled.Bluetooth
    else -> Icons.Filled.Headphones
}
