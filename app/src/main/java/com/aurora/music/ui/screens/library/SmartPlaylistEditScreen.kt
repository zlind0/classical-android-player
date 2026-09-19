package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.R
import com.aurora.music.data.SmartPlaylist
import com.aurora.music.data.SmartRule
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5SegmentRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5Section

// keys must match SmartPlaylistEngine
private const val TYPE_TEXT = 0
private const val TYPE_NUMBER = 1
private const val TYPE_BOOL = 2

private data class FieldSpec(val key: String, val type: Int)

private val FIELDS = listOf(
    FieldSpec("title", TYPE_TEXT),
    FieldSpec("artist", TYPE_TEXT),
    FieldSpec("album", TYPE_TEXT),
    FieldSpec("genre", TYPE_TEXT),
    FieldSpec("format", TYPE_TEXT),
    FieldSpec("duration", TYPE_NUMBER),
    FieldSpec("bitrate", TYPE_NUMBER),
    FieldSpec("playCount", TYPE_NUMBER),
    FieldSpec("lastPlayedDays", TYPE_NUMBER),
    FieldSpec("liked", TYPE_BOOL),
    FieldSpec("downloaded", TYPE_BOOL),
)

private val TEXT_OPS = listOf("contains", "notContains", "is", "isNot", "startsWith")
private val NUM_OPS = listOf("gt", "lt", "eq")
private val BOOL_OPS = listOf("isTrue", "isFalse")

private val SORTS = listOf(
    "title", "artist", "album", "duration",
    "playCount", "lastPlayed", "random",
)

private fun fieldSpec(key: String?): FieldSpec = FIELDS.firstOrNull { it.key == key } ?: FIELDS.first()
private fun opsFor(type: Int) = when (type) { TYPE_NUMBER -> NUM_OPS; TYPE_BOOL -> BOOL_OPS; else -> TEXT_OPS }

@Composable
private fun fieldLabel(key: String): String = when (key) {
    "title" -> stringResource(R.string.smart_f_title)
    "artist" -> stringResource(R.string.smart_f_artist)
    "album" -> stringResource(R.string.smart_f_album)
    "genre" -> stringResource(R.string.smart_f_genre)
    "format" -> stringResource(R.string.smart_f_format)
    "duration" -> stringResource(R.string.smart_f_duration)
    "bitrate" -> stringResource(R.string.smart_f_bitrate)
    "playCount" -> stringResource(R.string.smart_f_playcount)
    "lastPlayedDays" -> stringResource(R.string.smart_f_lastplayed)
    "liked" -> stringResource(R.string.smart_f_liked)
    "downloaded" -> stringResource(R.string.smart_f_downloaded)
    else -> key
}

@Composable
private fun opLabel(code: String): String = when (code) {
    "contains" -> stringResource(R.string.smart_op_contains)
    "notContains" -> stringResource(R.string.smart_op_not_contains)
    "is" -> stringResource(R.string.smart_op_is)
    "isNot" -> stringResource(R.string.smart_op_is_not)
    "startsWith" -> stringResource(R.string.smart_op_starts)
    "gt" -> stringResource(R.string.smart_op_gt)
    "lt" -> stringResource(R.string.smart_op_lt)
    "eq" -> stringResource(R.string.smart_op_eq)
    "isTrue" -> stringResource(R.string.smart_op_yes)
    "isFalse" -> stringResource(R.string.smart_op_no)
    else -> code
}

@Composable
private fun sortLabel(code: String): String = when (code) {
    "title" -> stringResource(R.string.smart_sort_title)
    "artist" -> stringResource(R.string.smart_sort_artist)
    "album" -> stringResource(R.string.smart_sort_album)
    "duration" -> stringResource(R.string.smart_sort_duration)
    "playCount" -> stringResource(R.string.smart_sort_playcount)
    "lastPlayed" -> stringResource(R.string.smart_sort_lastplayed)
    "random" -> stringResource(R.string.smart_sort_random)
    else -> code
}

@Composable
fun SmartPlaylistEditScreen(
    contentPadding: PaddingValues,
    playlist: SmartPlaylist,
    isNew: Boolean,
    onUpdate: ((SmartPlaylist) -> SmartPlaylist) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val strTitleNew = stringResource(R.string.smart_title_new)
    val strTitleEdit = stringResource(R.string.smart_title_edit)
    val strName = stringResource(R.string.smart_name)
    val strMatch = stringResource(R.string.smart_match)
    val strAllRules = stringResource(R.string.smart_all_rules)
    val strAnyRule = stringResource(R.string.smart_any_rule)
    val strRules = stringResource(R.string.smart_rules)
    val strAddRule = stringResource(R.string.smart_add_rule)
    val strSort = stringResource(R.string.smart_sort)
    val strLimit = stringResource(R.string.smart_limit)
    val strCreate = stringResource(R.string.smart_create)
    val strSaveChanges = stringResource(R.string.smart_save_changes)
    val strDesc = stringResource(R.string.smart_desc)
    val strAsc = stringResource(R.string.smart_asc)
    val bottomPad = contentPadding.calculateBottomPadding()
    val rules = playlist.rules.orEmpty()
    val sortOptions = listOf(
        sortLabel("title"), sortLabel("artist"), sortLabel("album"), sortLabel("duration"),
        sortLabel("playCount"), sortLabel("lastPlayed"), sortLabel("random"),
    )
    val sortSelected = SORTS.indexOf(playlist.sortBy ?: "title").coerceAtLeast(0)
    val limitText = (playlist.limit ?: 0).takeIf { it > 0 }?.toString() ?: ""

    Ios5SettingsPage(title = if (isNew) strTitleNew else strTitleEdit, onBack = onBack) {
        ios5Section(strName) {
            Ios5TextRow(
                title = strName,
                value = playlist.name.orEmpty(),
                placeholder = strName,
                onValueChange = { v -> onUpdate { it.copy(name = v) } },
            )
        }
        ios5Section(strMatch) {
            Ios5SegmentRow(
                title = strMatch,
                options = listOf(strAllRules, strAnyRule),
                selected = if (playlist.matchAll != false) 0 else 1,
                onSelect = { i -> onUpdate { it.copy(matchAll = i == 0) } },
            )
        }
        ios5Section(strRules) {
            if (rules.isEmpty()) {
                Ios5ActionRow(title = strAddRule, onClick = { onUpdate { it.copy(rules = rules + SmartRule()) } })
            } else {
                rules.forEachIndexed { i, rule ->
                    RuleRow(
                        rule = rule,
                        onChange = { r -> onUpdate { it.copy(rules = rules.toMutableList().apply { set(i, r) }) } },
                        onRemove = { onUpdate { it.copy(rules = rules.toMutableList().apply { removeAt(i) }) } },
                    )
                    if (i < rules.size - 1) Ios5CellDivider()
                }
                Ios5ActionRow(title = strAddRule, onClick = { onUpdate { it.copy(rules = rules + SmartRule()) } })
            }
        }
        ios5Section(strSort) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(strSort, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Dropdown(
                        options = sortOptions,
                        selected = sortSelected,
                        onSelect = { i -> onUpdate { it.copy(sortBy = SORTS[i]) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    val desc = playlist.descending == true
                    Icon(
                        if (desc) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                        if (desc) strDesc else strAsc,
                        tint = Ios5Colors.IosBlue,
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .clickable { onUpdate { it.copy(descending = !desc) } }.padding(7.dp),
                    )
                }
                Ios5CellDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strLimit, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { v -> onUpdate { it.copy(limit = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) } },
                        placeholder = { Text("0") },
                        singleLine = true,
                        modifier = Modifier.width(90.dp),
                    )
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = bottomPad)) {
                if (!playlist.name.isNullOrBlank()) {
                    Ios5GlossButton(
                        text = if (isNew) strCreate else strSaveChanges,
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(strName, color = Ios5Colors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: SmartRule, onChange: (SmartRule) -> Unit, onRemove: () -> Unit) {
    val spec = fieldSpec(rule.field)
    val ops = opsFor(spec.type)
    val strRemoveRule = stringResource(R.string.smart_remove_rule)
    val strNumberHint = stringResource(R.string.smart_number_hint)
    val strTextHint = stringResource(R.string.smart_text_hint)
    val fieldOptions = listOf(
        fieldLabel("title"), fieldLabel("artist"), fieldLabel("album"), fieldLabel("genre"),
        fieldLabel("format"), fieldLabel("duration"), fieldLabel("bitrate"),
        fieldLabel("playCount"), fieldLabel("lastPlayedDays"),
        fieldLabel("liked"), fieldLabel("downloaded"),
    )
    val opOptions = when (spec.type) {
        TYPE_NUMBER -> listOf(opLabel("gt"), opLabel("lt"), opLabel("eq"))
        TYPE_BOOL -> listOf(opLabel("isTrue"), opLabel("isFalse"))
        else -> listOf(opLabel("contains"), opLabel("notContains"), opLabel("is"), opLabel("isNot"), opLabel("startsWith"))
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Ios5Colors.GroupBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                options = fieldOptions,
                selected = FIELDS.indexOfFirst { it.key == spec.key }.coerceAtLeast(0),
                onSelect = { i ->
                    val f = FIELDS[i]
                    // reset op and bool value when field type changes
                    val op = if (opsFor(f.type).any { it == rule.op }) rule.op else opsFor(f.type).first()
                    onChange(rule.copy(field = f.key, op = op, value = if (f.type == TYPE_BOOL) "" else rule.value))
                },
                modifier = Modifier.weight(1f),
            )
            Dropdown(
                options = opOptions,
                selected = ops.indexOf(rule.op).coerceAtLeast(0),
                onSelect = { i -> onChange(rule.copy(op = ops[i])) },
            )
            Icon(
                Icons.Filled.Close, strRemoveRule,
                tint = Ios5Colors.TextSecondary,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove).padding(6.dp),
            )
        }
        if (spec.type != TYPE_BOOL) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = rule.value.orEmpty(),
                onValueChange = { v -> onChange(rule.copy(value = if (spec.type == TYPE_NUMBER) v.filter { it.isDigit() } else v)) },
                placeholder = { Text(if (spec.type == TYPE_NUMBER) strNumberHint else strTextHint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Dropdown(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp))
                .background(Ios5Colors.GroupBg)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                options.getOrElse(selected) { options.first() },
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ios5Colors.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Filled.ArrowDropDown, null, tint = Ios5Colors.TextSecondary, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(i); open = false })
            }
        }
    }
}
