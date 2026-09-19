package com.aurora.music.ui.ios5

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS5 settings primitives. Every settings sub-page keeps its store logic and
 * string resources; only the presentation moves onto grouped linen cards with
 * a brushed-metal nav bar.
 */

@Composable
fun Ios5SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = title, onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            content = content,
        )
    }
}

/** Section wrapper: title + grouped card. */
fun LazyListScope.ios5Section(
    title: String,
    body: @Composable () -> Unit,
) {
    item { Ios5SectionTitle(title) }
    item {
        Ios5Group(Modifier.padding(horizontal = 12.dp)) { body() }
    }
}

@Composable
fun Ios5SwitchRow(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ios5Colors.IosBlue,
                checkedBorderColor = Ios5Colors.IosBlueDark,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFC7CCD4),
                uncheckedBorderColor = Color(0xFFAEB4BE),
            ),
        )
    }
}

@Composable
fun Ios5SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(valueLabel, color = Ios5Colors.TextSecondary, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Ios5Colors.IosBlue,
                activeTrackColor = Ios5Colors.IosBlue,
                inactiveTrackColor = Color.Black.copy(alpha = 0.15f),
            ),
        )
    }
}

/** Chevron row navigating deeper. */
@Composable
fun Ios5NavRow(
    title: String,
    subtitle: String = "",
    value: String = "",
    onClick: () -> Unit,
) {
    Ios5Cell(title = title, subtitle = subtitle, count = value, onClick = onClick)
}

/** Centered tappable action text (blue, or red for danger). */
@Composable
fun Ios5ActionRow(
    title: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = if (danger) Color(0xFFD63A3A) else Ios5Colors.IosBlue,
            fontSize = 16.sp, fontWeight = FontWeight.Medium,
        )
    }
}

/** Checkmark row for single-choice lists. */
@Composable
fun Ios5CheckRow(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (checked) {
            Text("✓", color = Ios5Colors.IosBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Inline text field row. */
@Composable
fun Ios5TextRow(
    title: String,
    value: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Ios5Colors.GroupBg,
                unfocusedContainerColor = Ios5Colors.GroupBg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

/** iOS segmented control. */
@Composable
fun Ios5SegmentRow(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFD6DAE0))
                .border(1.dp, Color(0xFFAEB4BE), RoundedCornerShape(8.dp))
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { i, opt ->
                val active = i == selected
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (active) Modifier.background(Ios5Colors.glossBrush) else Modifier)
                        .clickable { onSelect(i) }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = if (active) Color.White else Ios5Colors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Small gray explanatory footer. */
fun LazyListScope.ios5FootNote(text: String) {
    item {
        Text(
            text,
            color = Ios5Colors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun Ios5StaticText(text: String) {
    Text(
        text,
        color = Ios5Colors.TextPrimary,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
