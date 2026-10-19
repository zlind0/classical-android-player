package com.aurora.music.ui.screens.player

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Dimens
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5Type

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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ios5Colors.Linen, dragHandle = null) {
        Column(Modifier.fillMaxWidth().background(Ios5Colors.linenBrush).padding(bottom = 28.dp)) {
            Text(
                stringResource(R.string.sheet_play_on),
                color = Ios5Colors.TextPrimary,
                fontSize = Ios5Type.NavTitle,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp),
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Ios5Group {
                    devices.forEachIndexed { index, d ->
                        if (index > 0) Ios5CellDivider(startInset = 46.dp)
                        val selected = d.id == currentId
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(d.id); onDismiss() }.padding(horizontal = 12.dp, vertical = 6.dp).heightIn(min = Ios5Dimens.CellHeight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(if (selected) Ios5Colors.IosBlueBright else Color(0xFFD8DCE2)), contentAlignment = Alignment.Center) {
                                Icon(d.icon, null, tint = if (selected) Color.White else Color(0xFF5A6068), modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                d.label,
                                color = Ios5Colors.TextPrimary,
                                fontSize = Ios5Type.CellTitle,
                                fontWeight = FontWeight.Bold,
                                fontFamily = Ios5Sans,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (selected) Text("✓", color = Ios5Colors.IosBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
