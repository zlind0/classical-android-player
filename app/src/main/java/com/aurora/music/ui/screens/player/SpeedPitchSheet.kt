package com.aurora.music.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Dimens
import com.aurora.music.ui.ios5.Ios5LargeButton
import com.aurora.music.ui.ios5.Ios5LargeTone
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5Slider
import com.aurora.music.ui.ios5.Ios5Switch
import com.aurora.music.ui.ios5.Ios5Type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedPitchSheet(
    speed: Float,
    pitch: Float,
    matchPitch: Boolean,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onMatchPitch: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ios5Colors.Linen,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().background(Ios5Colors.linenBrush).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Column(Modifier.fillMaxWidth().height(Ios5Dimens.NavHeight), verticalArrangement = Arrangement.Center) {
                Text(
                    stringResource(R.string.sheet_playback_controls),
                    color = Ios5Colors.TextPrimary,
                    fontSize = Ios5Type.NavTitle,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Ios5Sans,
                )
            }
            Spacer(Modifier.height(12.dp))

            ControlBlock(
                icon = { Icon(Icons.Filled.Speed, null, tint = Ios5Colors.TextPrimary) },
                title = stringResource(R.string.sheet_speed),
                value = "${"%.2f".format(speed)}x",
            ) {
                Ios5Slider(
                    value = speed,
                    onValueChange = onSpeed,
                    range = 0.5f..2.0f,
                    steps = 29,
                )
                QuickPicks(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f), speed) { onSpeed(it) }
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sheet_match_pitch), color = Ios5Colors.TextPrimary, fontSize = Ios5Type.CellTitle, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans)
                    Text(stringResource(R.string.sheet_pitch_follows), color = Ios5Colors.TextSecondary, fontSize = 13.sp, fontFamily = Ios5Sans)
                }
                Ios5Switch(
                    checked = matchPitch,
                    onCheckedChange = onMatchPitch,
                )
            }

            if (!matchPitch) {
                Spacer(Modifier.height(20.dp))
                ControlBlock(
                    icon = { Icon(Icons.Filled.GraphicEq, null, tint = Ios5Colors.TextPrimary) },
                    title = stringResource(R.string.sheet_pitch),
                    value = "${if (pitch >= 0) "+" else ""}${"%.1f".format(pitch)} st",
                ) {
                    Ios5Slider(
                        value = pitch,
                        onValueChange = onPitch,
                        range = -6f..6f,
                        steps = 11,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            Ios5LargeButton(
                text = stringResource(R.string.sheet_reset_default),
                onClick = onReset,
                tone = Ios5LargeTone.Light,
            )
        }
    }
}

@Composable
private fun ControlBlock(
    icon: @Composable () -> Unit,
    title: String,
    value: String,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(title, color = Ios5Colors.TextPrimary, fontSize = Ios5Type.CellTitle, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans, modifier = Modifier.weight(1f))
        Text(value, color = Ios5Colors.TextSecondary, fontSize = Ios5Type.CellTitle, fontFamily = Ios5Sans)
    }
    Spacer(Modifier.height(6.dp))
    content()
}

@Composable
private fun QuickPicks(options: List<Float>, selected: Float, onPick: (Float) -> Unit) {
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            val active = kotlin.math.abs(opt - selected) < 0.001f
            val shape = RoundedCornerShape(Ios5Dimens.CornerButton)
            Box(
                Modifier
                    .weight(1f)
                    .height(Ios5Dimens.BarButtonHeight)
                    .clip(shape)
                    .background(if (active) Ios5Colors.buttonBlueBrush else Brush.verticalGradient(
                        0f to Color.White, 0.5f to Color(0xFFF1F2F4), 1f to Color(0xFFD5D8DD),
                    ))
                    .border(1.dp, if (active) Color.Black.copy(alpha = 0.45f) else Color(0xFF9AA0A8), shape)
                    .clickable { onPick(opt) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    formatMultiplier(opt),
                    fontSize = 12.sp,
                    fontFamily = Ios5Sans,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Color.White else Color(0xFF3E444D),
                )
            }
        }
    }
}

private fun formatMultiplier(v: Float): String {
    val s = if (v % 1f == 0f) v.toInt().toString() else v.toString().trimEnd('0').trimEnd('.')
    return "${s}x"
}
