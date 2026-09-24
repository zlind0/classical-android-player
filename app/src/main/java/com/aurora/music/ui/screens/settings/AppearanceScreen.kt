package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.util.AppLocale
import com.aurora.music.data.AccentMode
import com.aurora.music.data.CornerStyle
import com.aurora.music.data.HomeSection
import com.aurora.music.data.SeekStyle
import com.aurora.music.data.ThemeMode
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.UiPrefs
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SegmentRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import com.aurora.music.ui.theme.AccentPresets
import com.aurora.music.ui.theme.LocalUiPrefs
import com.aurora.music.ui.theme.ThemeIdentities
import com.aurora.music.ui.theme.ThemeIdentity
import com.aurora.music.ui.theme.auroraBackdrop
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.theme.auroraShapes
import com.aurora.music.ui.theme.auroraTypography
import com.aurora.music.ui.theme.styleColorScheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AppearanceScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val prefs by store.uiPrefs.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val scope = rememberCoroutineScope()
    val materialYouSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    // labels hoisted: stringResource is @Composable and illegal directly in the LazyColumn DSL scope
    val homeSectionLabels = mapOf(
        HomeSection.HERO to stringResource(R.string.appearance_home_hero),
        HomeSection.RECENT to stringResource(R.string.appearance_home_recent),
        HomeSection.PLAYLISTS to stringResource(R.string.appearance_home_playlists),
        HomeSection.FAVOURITE to stringResource(R.string.appearance_home_favourite),
        HomeSection.MOST to stringResource(R.string.appearance_home_most),
        HomeSection.ARTISTS to stringResource(R.string.appearance_home_artists),
        HomeSection.NEW to stringResource(R.string.appearance_home_new),
    )
    val strTitle = stringResource(R.string.appearance_title)
    val strLangSection = stringResource(R.string.appearance_section_language)
    val strLangSub = stringResource(R.string.appearance_language_sub)
    val strThemeSection = stringResource(R.string.appearance_section_theme)
    val strAccentSection = stringResource(R.string.appearance_section_accent)
    val strDisplaySection = stringResource(R.string.appearance_section_display)
    val strPlayerSection = stringResource(R.string.appearance_section_player)
    val strMiniSection = stringResource(R.string.appearance_section_mini)
    val strLibrarySection = stringResource(R.string.appearance_section_library)
    val strHomeSection = stringResource(R.string.appearance_section_home)
    val strHint = when (prefs.themeMode) {
        ThemeMode.AMOLED -> stringResource(R.string.appearance_hint_amoled)
        ThemeMode.SYSTEM -> stringResource(R.string.appearance_hint_system)
        else -> ""
    }

    Ios5SettingsPage(strTitle, onBack) {
        ios5Section(strLangSection) {
            val ctx = LocalContext.current
            var langTag by remember { mutableStateOf(AppLocale.persistedTag(ctx)) }
            Ios5SegmentRow(
                stringResource(R.string.appearance_language),
                AppLocale.options.map { stringResource(AppLocale.displayName(it)) },
                AppLocale.options.indexOf(langTag).coerceAtLeast(0),
            ) { i ->
                langTag = AppLocale.options[i]
                AppLocale.setTag(ctx, langTag)
            }
        }
        ios5FootNote(strLangSub)

        ios5Section(strThemeSection) {
            ThemeStylePicker(prefs) { style -> scope.launch { store.setThemeStyle(style) } }
            Ios5CellDivider()
            Ios5SegmentRow(
                stringResource(R.string.appearance_mode),
                listOf(
                    stringResource(R.string.appearance_mode_system),
                    stringResource(R.string.appearance_mode_light),
                    stringResource(R.string.appearance_mode_dark),
                    stringResource(R.string.appearance_mode_amoled),
                ),
                prefs.themeMode,
            ) { i ->
                scope.launch { store.setThemeMode(i) }
            }
        }
        if (strHint.isNotBlank()) {
            ios5FootNote(strHint)
        }

        if (prefs.themeStyle == ThemeStyle.AURORA) {
            ios5Section(strAccentSection) {
                Ios5SegmentRow(
                    stringResource(R.string.appearance_source),
                    listOf(
                        stringResource(R.string.appearance_presets),
                        stringResource(R.string.appearance_custom),
                        stringResource(R.string.appearance_material_you),
                    ),
                    prefs.accentMode,
                ) { i ->
                    scope.launch { store.setAccentMode(i) }
                }
                Ios5CellDivider()
                when (prefs.accentMode) {
                    AccentMode.PRESET -> AccentPresetGrid(selected = prefs.accentPreset) { i -> scope.launch { store.setAccentPreset(i) } }
                    AccentMode.CUSTOM -> CustomColorPicker(initialArgb = prefs.accentColor.toInt()) { argb ->
                        scope.launch { store.setAccentColor(argb.toLong() and 0xFFFFFFFFL) }
                    }
                    else -> Ios5StaticText(
                        if (materialYouSupported) stringResource(R.string.appearance_myou_on)
                        else stringResource(R.string.appearance_myou_off),
                    )
                }
            }
        }

        ios5Section(strDisplaySection) {
            Ios5SwitchRow(title = stringResource(R.string.appearance_hide_status_landscape), subtitle = stringResource(R.string.appearance_hide_status_landscape_sub), checked = prefs.hideStatusBarLandscape) { v ->
                scope.launch { store.setHideStatusBarLandscape(v) }
            }
            Ios5CellDivider()
            Ios5SliderRow(stringResource(R.string.appearance_font_size), "${(prefs.fontScale * 100).roundToInt()}%", prefs.fontScale, 0.85f..1.3f) { v ->
                scope.launch { store.setFontScale(v) }
            }
            if (prefs.themeStyle == ThemeStyle.AURORA) {
                Ios5CellDivider()
                Ios5SegmentRow(
                    stringResource(R.string.appearance_corners),
                    listOf(
                        stringResource(R.string.appearance_corner_sharp),
                        stringResource(R.string.appearance_corner_default),
                        stringResource(R.string.appearance_corner_rounded),
                        stringResource(R.string.appearance_corner_pill),
                    ),
                    prefs.cornerStyle,
                ) { i ->
                    scope.launch { store.setCornerStyle(i) }
                }
            }
        }

        ios5Section(strPlayerSection) {
            Ios5SegmentRow(
                stringResource(R.string.appearance_seek_bar),
                listOf(stringResource(R.string.appearance_waveform), stringResource(R.string.appearance_bar)),
                prefs.playerSeekStyle,
            ) { i ->
                scope.launch { store.setPlayerSeekStyle(i) }
            }
            if (prefs.playerSeekStyle == SeekStyle.WAVEFORM) {
                Ios5CellDivider()
                Ios5SliderRow(stringResource(R.string.appearance_waveform_bars), "${prefs.playerWaveBars}", prefs.playerWaveBars.toFloat(), 24f..96f) { v ->
                    scope.launch { store.setPlayerWaveBars(v.roundToInt()) }
                }
            }
            Ios5CellDivider()
            Ios5SliderRow(stringResource(R.string.appearance_artwork_size), "${(prefs.playerArtSize * 100).roundToInt()}%", prefs.playerArtSize, 0.6f..1f) { v ->
                scope.launch { store.setPlayerArtSize(v) }
            }
            if (prefs.themeStyle == ThemeStyle.AURORA) {
                Ios5CellDivider()
                Ios5SliderRow(stringResource(R.string.appearance_gradient), "${(prefs.playerGradient * 100).roundToInt()}%", prefs.playerGradient, 0f..1.5f) { v ->
                    scope.launch { store.setPlayerGradient(v) }
                }
            }
            Ios5CellDivider()
            Ios5SwitchRow(title = stringResource(R.string.appearance_bottom_utils), subtitle = stringResource(R.string.appearance_bottom_utils_sub), checked = prefs.playerShowUtilities) { v ->
                scope.launch { store.setPlayerShowUtilities(v) }
            }
        }

        ios5Section(strMiniSection) {
            Ios5SegmentRow(
                stringResource(R.string.appearance_style),
                listOf(
                    stringResource(R.string.appearance_standard),
                    stringResource(R.string.appearance_compact),
                    stringResource(R.string.appearance_prominent),
                ),
                prefs.miniStyle,
            ) { i ->
                scope.launch { store.setMiniStyle(i) }
            }
            Ios5CellDivider()
            Ios5SegmentRow(
                stringResource(R.string.appearance_progress),
                listOf(
                    stringResource(R.string.appearance_line),
                    stringResource(R.string.appearance_bar_opt),
                    stringResource(R.string.appearance_none),
                ),
                prefs.miniProgress,
            ) { i ->
                scope.launch { store.setMiniProgress(i) }
            }
        }

        ios5Section(strLibrarySection) {
            Ios5SegmentRow(stringResource(R.string.appearance_grid_columns), listOf("2", "3", "4"), (prefs.libraryColumns - 2).coerceIn(0, 2)) { i ->
                scope.launch { store.setLibraryColumns(i + 2) }
            }
        }

        ios5Section(strHomeSection) {
            val homeSections = listOf(
                HomeSection.HERO, HomeSection.RECENT, HomeSection.PLAYLISTS, HomeSection.FAVOURITE,
                HomeSection.MOST, HomeSection.ARTISTS, HomeSection.NEW,
            )
            homeSections.forEachIndexed { idx, id ->
                if (idx > 0) Ios5CellDivider()
                Ios5SwitchRow(title = homeSectionLabels[id] ?: id, checked = id !in prefs.hiddenHomeSections) { v ->
                    scope.launch { store.setHomeSectionHidden(id, !v) }
                }
            }
        }
    }
}

@Composable
private fun ThemeStylePicker(prefs: UiPrefs, onSelect: (Int) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemeIdentities.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { identity ->
                    val selected = identity.id == prefs.themeStyle
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Ios5Colors.CellBg)
                            .border(if (selected) 2.dp else 1.dp, if (selected) Ios5Colors.IosBlue else Ios5Colors.CellDivider, RoundedCornerShape(8.dp))
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(identity.id) })
                            .padding(4.dp),
                    ) {
                        ThemePreview(identity, prefs, dark)
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(themeName(identity.id), color = Ios5Colors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (selected) Icon(Icons.Filled.Check, null, tint = Ios5Colors.IosBlue, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        val current = ThemeIdentities.firstOrNull { it.id == prefs.themeStyle } ?: ThemeIdentities.first()
        Text(themeDescription(current.id), color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(themeDetail(current.id), color = Ios5Colors.TextSecondary, fontSize = 13.sp)
        if (prefs.themeStyle != ThemeStyle.AURORA) {
            Text(stringResource(R.string.appearance_non_aurora_note), color = Ios5Colors.TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun themeName(id: Int): String = stringResource(
    when (id) {
        ThemeStyle.RETRO -> R.string.theme_name_retro
        ThemeStyle.AERO -> R.string.theme_name_aero
        ThemeStyle.GLASS -> R.string.theme_name_glass
        else -> R.string.theme_name_aurora
    }
)

@Composable
private fun themeDescription(id: Int): String = stringResource(
    when (id) {
        ThemeStyle.RETRO -> R.string.theme_desc_retro
        ThemeStyle.AERO -> R.string.theme_desc_aero
        ThemeStyle.GLASS -> R.string.theme_desc_glass
        else -> R.string.theme_desc_aurora
    }
)

@Composable
private fun themeDetail(id: Int): String = stringResource(
    when (id) {
        ThemeStyle.RETRO -> R.string.theme_detail_retro
        ThemeStyle.AERO -> R.string.theme_detail_aero
        ThemeStyle.GLASS -> R.string.theme_detail_glass
        else -> R.string.theme_detail_aurora
    }
)

@Composable
private fun ThemePreview(identity: ThemeIdentity, prefs: UiPrefs, dark: Boolean) {
    CompositionLocalProvider(LocalUiPrefs provides prefs.copy(themeStyle = identity.id)) {
        MaterialTheme(
            colorScheme = styleColorScheme(identity.id, dark),
            typography = auroraTypography(0.85f, identity.id),
            shapes = auroraShapes(CornerStyle.DEFAULT, identity.id),
        ) {
            Column(Modifier.fillMaxWidth().height(122.dp).clip(MaterialTheme.shapes.small).auroraBackdrop().padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("AURORA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(MaterialTheme.shapes.extraSmall).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(22.dp).border(4.dp, MaterialTheme.colorScheme.background.copy(alpha = 0.55f), CircleShape))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth(0.95f).height(4.dp).background(MaterialTheme.colorScheme.onBackground))
                        Box(Modifier.fillMaxWidth(0.65f).height(3.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)))
                    }
                }
                Row(Modifier.fillMaxWidth().height(26.dp).auroraPanel(MaterialTheme.shapes.small, emphasized = true).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.25f, 0.55f, 0.85f, 0.5f, 0.7f, 0.35f, 0.6f, 0.4f).forEach { level ->
                        Box(Modifier.weight(1f).height((level * 18).dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentPresetGrid(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentPresets.chunked(5).forEachIndexed { rowIdx, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { colIdx, preset ->
                    val index = rowIdx * 5 + colIdx
                    val isSel = index == selected
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(8.dp))
                            .background(preset.seed)
                            .then(if (isSel) Modifier.border(3.dp, Ios5Colors.IosBlue, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) {
                            val on = if (preset.seed.luminanceApprox() > 0.5f) Color.Black else Color.White
                            Icon(Icons.Filled.Check, stringResource(R.string.appearance_selected), tint = on, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                // Pad short final row so swatches keep their width.
                repeat(5 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CustomColorPicker(initialArgb: Int, onChange: (Int) -> Unit) {
    val initialHsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var bri by remember { mutableFloatStateOf(initialHsv[2]) }

    fun push() = onChange(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri)))
    val preview = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, bri)))

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(preview).border(1.dp, Ios5Colors.CellDivider, CircleShape))
            Ios5StaticText(stringResource(R.string.appearance_live_preview))
        }
        Ios5SliderRow(stringResource(R.string.appearance_hue), "${hue.roundToInt()}°", hue, 0f..360f) { hue = it; push() }
        Ios5CellDivider()
        Ios5SliderRow(stringResource(R.string.appearance_saturation), "${(sat * 100).roundToInt()}%", sat, 0f..1f) { sat = it; push() }
        Ios5CellDivider()
        Ios5SliderRow(stringResource(R.string.appearance_brightness), "${(bri * 100).roundToInt()}%", bri, 0f..1f) { bri = it; push() }
    }
}

private fun Color.luminanceApprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
