package com.aurora.music.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.AppContainer
import com.aurora.music.data.describe
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DEFAULT_SQUIG_BASE
import com.aurora.music.data.DEFAULT_SQUIG_TARGET
import com.aurora.music.data.DspMode
import com.aurora.music.data.DrivingMode
import com.aurora.music.data.SQUIG_INSTANCES
import com.aurora.music.data.SQUIG_TARGETS
import com.aurora.music.data.EqBinding
import com.aurora.music.data.EqProfile
import com.aurora.music.data.EqDeviceKind
import com.aurora.music.data.EqProvider
import com.aurora.music.data.ParamBand
import com.aurora.music.data.SettingsStore
import com.aurora.music.playback.DspBand
import com.aurora.music.playback.DspCoeffBuilder
import com.aurora.music.playback.DspParams
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5CheckRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SegmentRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5SwitchRow
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as AuroraApplication).container
    val store = container.settingsStore
    val prefs by store.audioPrefs.collectAsStateWithLifecycle(initialValue = AudioPrefs())
    val scope = rememberCoroutineScope()

    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val activeEq by store.activeEqProfile.collectAsStateWithLifecycle(initialValue = "")
    val corrections by store.correctionProfiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeCorrectionId by store.activeCorrectionId.collectAsStateWithLifecycle(initialValue = "flat")
    val audioProfiles by store.audioProfiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val deviceProfiles by store.deviceProfiles.collectAsStateWithLifecycle(initialValue = emptyMap())
    val activeCorrection = corrections.firstOrNull { it.id == activeCorrectionId }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(0) }
    val rgLabels = listOf(stringResource(R.string.eq_rg_off), stringResource(R.string.eq_rg_track), stringResource(R.string.eq_rg_album))

    Ios5SettingsPage(title = stringResource(R.string.eq_title), onBack = onBack) {
        collapsible("profiles", ctx.getString(R.string.eq_profiles), "${audioProfiles.size} saved", expanded) {
            AudioProfilesPanel(container, prefs, activeCorrectionId, audioProfiles, deviceProfiles, store, scope)
        }

        item {
            PillSelector(listOf(stringResource(R.string.eq_tab_correction), stringResource(R.string.eq_tab_user), stringResource(R.string.eq_tab_dynamics)), tab) { tab = it }
        }
        when (tab) {
            0 -> correctionTab(ctx, prefs, activeCorrection, corrections, activeCorrectionId, store, scope, container, expanded)
            1 -> userEqTab(ctx, prefs, activeCorrection, store, scope, container, expanded, activeCorrectionId)
            else -> dynamicsTab(ctx, prefs, store, scope, expanded, container)
        }

        item { Ios5SectionTitle(stringResource(R.string.playback_section_output)) }
        collapsible("rg", ctx.getString(R.string.eq_volume_leveling), ctx.getString(R.string.eq_rg_fmt, rgLabels[prefs.replayGain.coerceIn(0, 2)]), expanded) {
            Ios5SegmentRow("Mode", rgLabels, prefs.replayGain) { i -> scope.launch { store.setReplayGain(i) } }
        }

        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}

private fun LazyListScope.collapsible(
    key: String, title: String, summary: String?,
    expanded: SnapshotStateMap<String, Boolean>, defaultOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    item(key = "${key}_title") { Ios5SectionTitle(title) }
    item(key = key) {
        val open = expanded[key] ?: defaultOpen
        Ios5Group(Modifier.padding(horizontal = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded[key] = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (!summary.isNullOrBlank()) {
                        Text(summary, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                    }
                }
                Text(if (open) "▲" else "▼", color = Ios5Colors.TextSecondary, fontSize = 13.sp)
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Ios5CellDivider()
                    content()
                }
            }
        }
    }
}

@Composable
private fun HeadroomRow(peak: Float, preamp: Float, onAuto: () -> Unit) {
    val over = peak + preamp                 // positive means the curve can clip
    val clip = over > 0.1f
    val color = if (clip) Color(0xFFD63A3A) else Ios5Colors.TextSecondary
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.eq_headroom_title), color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                when {
                    peak <= 0.1f -> stringResource(R.string.eq_headroom_ok_none)
                    clip -> stringResource(R.string.eq_headroom_clipping, peak, over)
                    else -> stringResource(R.string.eq_headroom_ok, peak, -over)
                },
                color = color, fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Ios5GlossButton(text = stringResource(R.string.eq_auto), onClick = onAuto)
    }
}

@Composable
private fun ConvolutionPanel(prefs: AudioPrefs, store: SettingsStore, scope: CoroutineScope) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching {
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "impulse.wav"
                runCatching {
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) name = c.getString(i)
                    }
                }
                val dest = java.io.File(ctx.filesDir, "ir_active.wav")
                ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                store.setDspConvIr(dest.absolutePath, name)
                store.setDspConvEnabled(true)
                scope.launch(Dispatchers.Main) { android.widget.Toast.makeText(ctx, ctx.getString(R.string.eq_toast_ir_loaded, name), android.widget.Toast.LENGTH_SHORT).show() }
            }.onFailure {
                scope.launch(Dispatchers.Main) { android.widget.Toast.makeText(ctx, ctx.getString(R.string.eq_toast_ir_failed), android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Ios5SwitchRow(
            title = stringResource(R.string.eq_conv_enable),
            subtitle = if (prefs.dspConvIrName.isNotBlank()) "IR: ${prefs.dspConvIrName}" else stringResource(R.string.eq_conv_hint),
            checked = prefs.dspConvEnabled,
            onCheckedChange = { v -> scope.launch { store.setDspConvEnabled(v) } },
        )
        Ios5CellDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ios5GlossButton(
                text = if (prefs.dspConvIrName.isBlank()) stringResource(R.string.eq_conv_load) else stringResource(R.string.eq_conv_replace),
                onClick = { runCatching { picker.launch(arrayOf("*/*")) } },
                modifier = Modifier.weight(1f),
            )
            if (prefs.dspConvIrName.isNotBlank()) {
                Ios5GlossButton(
                    text = stringResource(R.string.eq_conv_remove),
                    onClick = { scope.launch { store.setDspConvIr("", ""); store.setDspConvEnabled(false) } },
                )
            }
        }
        DbSliderRow(stringResource(R.string.eq_conv_makeup), prefs.dspConvMakeupDb, -12f..12f) { v -> scope.launch { store.setDspConvMakeup(v) } }
    }
}

@Composable
private fun PillSelector(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Ios5SegmentRow(title = "", options = options, selected = selected, onSelect = onSelect)
}

@Composable
private fun AutoEqPanel(container: AppContainer, prefs: AudioPrefs, store: SettingsStore, scope: CoroutineScope) {
    val active by store.activeEqProfile.collectAsStateWithLifecycle(initialValue = "")
    val autoSwitch by store.autoEqAutoSwitch.collectAsStateWithLifecycle(initialValue = false)
    val bindings by store.eqBindings.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<EqProfile>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableStateOf(20) }
    var source by rememberSaveable { mutableStateOf(0) }   // 0 measured device library, 1 live squig.link
    var categoryName by rememberSaveable { mutableStateOf(EqDeviceKind.ALL.name) }
    val category = EqDeviceKind.entries.firstOrNull { it.name == categoryName } ?: EqDeviceKind.ALL
    val squigBase by store.squigBaseUrl.collectAsStateWithLifecycle(initialValue = DEFAULT_SQUIG_BASE)
    val squigTargetName by store.squigTarget.collectAsStateWithLifecycle(initialValue = DEFAULT_SQUIG_TARGET)
    val outLabel = container.autoEqController.currentOutputLabel()
    val ctx = LocalContext.current
    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    LaunchedEffect(query, source, category, squigBase, squigTargetName) {
        results = emptyList()
        visibleCount = 20
        searchFailed = false
        searching = true
        try {
            if (query.isNotBlank()) delay(220)
            results = if (source == 0) container.autoEq.search(query, category)
                else if (query.trim().length >= 2) container.squigEq.search(query) else emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            searchFailed = true
        } finally {
            searching = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        PillSelector(listOf(stringResource(R.string.eq_source_device), stringResource(R.string.eq_source_squig)), source) { source = it }
        if (source == 0) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 12.dp)) {
                items(EqDeviceKind.entries.size) { i ->
                    val kind = EqDeviceKind.entries[i]
                    PresetChip(eqKindLabel(kind), selected = kind == category) { categoryName = kind.name }
                }
            }
            Text(eqKindDescription(category), color = Ios5Colors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 8.dp, start = 12.dp, end = 12.dp)) {
                items(category.examples.size) { i ->
                    val example = category.examples[i]
                    PresetChip(example, selected = query == example) { query = example }
                }
            }
        }
        if (source == 1) {
            val instIdx = SQUIG_INSTANCES.indexOfFirst { it.second == squigBase }.coerceAtLeast(0)
            PillSelector(SQUIG_INSTANCES.map { it.first }, instIdx) { i -> scope.launch { store.setSquigBaseUrl(SQUIG_INSTANCES[i].second) } }
            val tgtIdx = SQUIG_TARGETS.indexOfFirst { it.second == squigTargetName }.coerceAtLeast(0)
            PillSelector(SQUIG_TARGETS.map { it.first }, tgtIdx) { i -> scope.launch { store.setSquigTarget(SQUIG_TARGETS[i].second) } }
            Text(
                stringResource(R.string.eq_squig_hint, SQUIG_TARGETS.getOrNull(tgtIdx)?.first ?: "Harman"),
                color = Ios5Colors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        if (active.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {
                Text(stringResource(R.string.eq_applied_prefix, active), color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.eq_clear), color = Ios5Colors.IosBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        scope.launch { store.setActiveCorrectionId("flat"); store.setDspPreamp(0f); store.setActiveEqProfile("") }
                    }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Ios5CellDivider()
        }
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text(if (source == 0) stringResource(R.string.eq_search_device) else stringResource(R.string.eq_search_squig)) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Ios5Colors.TextSecondary) },
            trailingIcon = { if (query.isNotEmpty()) Icon(Icons.Filled.Close, stringResource(R.string.eq_clear), tint = Ios5Colors.TextSecondary, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { query = "" }.padding(4.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Ios5Colors.IosBlue,
            ),
        )
        if (working || searching) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.width(36.dp).height(36.dp))
            }
        }
        if (!searching) {
            Text(
                when {
                    searchFailed -> stringResource(R.string.eq_no_presets)
                    source == 1 && query.trim().length < 2 -> stringResource(R.string.eq_squig_min_chars)
                    results.isEmpty() -> stringResource(R.string.eq_no_results)
                    else -> stringResource(R.string.eq_results_fmt, results.size, minOf(visibleCount, results.size))
                },
                color = Ios5Colors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        results.take(visibleCount).forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !working) {
                    scope.launch {
                        working = true
                        val eq = if (p.provider == EqProvider.SQUIG) container.squigEq.generate(p) else container.autoEq.fetch(p)
                        android.util.Log.d("AutoEQ", "apply ${p.name}: ${if (eq == null) "FETCH FAILED" else "preamp=${eq.preampDb} bands=${eq.bands.size}"}")
                        if (eq != null && eq.bands.isNotEmpty()) {
                            // v0.5: parametric result compiles to a 128-band CorrectionProfile (plan §26)
                            val gains = com.aurora.music.playback.CorrectionCompiler.parametricToGains(eq.bands)
                            val profile = com.aurora.music.data.CorrectionProfile(
                                id = "corr_${System.currentTimeMillis()}",
                                name = p.name,
                                deviceName = p.name,
                                source = if (p.provider == EqProvider.SQUIG) com.aurora.music.data.CorrectionSource.SQUIG else com.aurora.music.data.CorrectionSource.AUTOEQ,
                                preampDb = eq.preampDb,
                                gains = gains.toList(),
                            )
                            store.upsertCorrectionProfile(profile)
                            store.setActiveCorrectionId(profile.id)
                            store.setDspMode(DspMode.CUSTOM)
                            store.setActiveEqProfile(p.name)
                            query = ""
                            toast(ctx.getString(R.string.eq_toast_applied, p.name, eq.preampDb))
                        } else {
                            toast(ctx.getString(R.string.eq_toast_apply_failed))
                        }
                        working = false
                    }
                }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Ios5Colors.TextPrimary, fontSize = 15.sp, maxLines = 2)
                    Text("${p.source} · ${eqKindLabel(p.kind)}", color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 2)
                }
                Icon(Icons.Filled.Add, stringResource(R.string.common_apply), tint = Ios5Colors.IosBlue)
            }
        }
        if (results.size > visibleCount) {
            TextLink(stringResource(R.string.eq_show_more)) { visibleCount += 20 }
        }

        Spacer(Modifier.height(6.dp))
        Ios5CellDivider()
        Ios5SwitchRow(
            title = stringResource(R.string.eq_autoswitch),
            subtitle = stringResource(R.string.eq_autoswitch_sub),
            checked = autoSwitch,
            onCheckedChange = { v -> scope.launch { store.setAutoEqAutoSwitch(v) } },
        )
        if (active.isNotBlank()) {
            val activeCorrectionId by store.activeCorrectionId.collectAsStateWithLifecycle(initialValue = "flat")
            Ios5CellDivider()
            Ios5ActionRow(
                title = stringResource(R.string.eq_bind_to, active, outLabel),
                onClick = {
                    scope.launch {
                        store.upsertEqBinding(EqBinding(container.autoEqController.currentOutputKey(), outLabel, active, prefs.dspPreampDb, prefs.dspParametric, correctionId = activeCorrectionId))
                        store.setAutoEqAutoSwitch(true)
                    }
                },
            )
        }
        bindings.forEachIndexed { index, b ->
            if (index > 0 || active.isNotBlank()) Ios5CellDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.deviceLabel, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(b.profileName, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
                Icon(Icons.Filled.Close, "Unbind", tint = Ios5Colors.TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { scope.launch { store.removeEqBinding(b.deviceKey) } }.padding(4.dp))
            }
        }
    }
}

private fun LazyListScope.correctionTab(
    ctx: Context,
    prefs: AudioPrefs,
    activeCorrection: com.aurora.music.data.CorrectionProfile?,
    corrections: List<com.aurora.music.data.CorrectionProfile>,
    activeCorrectionId: String,
    store: SettingsStore,
    scope: CoroutineScope,
    container: AppContainer,
    expanded: SnapshotStateMap<String, Boolean>,
) {
    item {
        val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(prefs.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
        val graphic = (0 until layout.freqs.size).map { prefs.dspGraphicBands.getOrElse(it) { 0f } }
        EqCurveChart(
            correction = activeCorrection,
            graphicFreqs = layout.freqs, graphicQ = layout.q, graphicGains = graphic,
            parametric = prefs.dspParametric, preampDb = prefs.dspPreampDb,
        )
    }
    collapsible("corr_list", ctx.getString(R.string.eq_correction_profile),
        activeCorrection?.name?.ifBlank { ctx.getString(R.string.eq_flat) } ?: ctx.getString(R.string.eq_flat), expanded, defaultOpen = true) {
        CorrectionProfilesPanel(corrections, activeCorrectionId, activeCorrection, store, scope)
    }
    collapsible("autoeq", ctx.getString(R.string.eq_device_presets), ctx.getString(R.string.eq_device_presets_sub), expanded) {
        AutoEqPanel(container, prefs, store, scope)
    }
    collapsible("conv", ctx.getString(R.string.eq_convolution), if (prefs.dspConvEnabled && prefs.dspConvIrName.isNotBlank()) prefs.dspConvIrName else ctx.getString(R.string.eq_conv_off), expanded) {
        ConvolutionPanel(prefs, store, scope)
    }
}

private fun LazyListScope.userEqTab(
    ctx: Context,
    prefs: AudioPrefs,
    activeCorrection: com.aurora.music.data.CorrectionProfile?,
    store: SettingsStore,
    scope: CoroutineScope,
    container: AppContainer,
    expanded: SnapshotStateMap<String, Boolean>,
    activeCorrectionId: String,
) {
    item {
        val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(prefs.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
        val graphic = (0 until layout.freqs.size).map { prefs.dspGraphicBands.getOrElse(it) { 0f } }
        EqCurveChart(
            correction = activeCorrection,
            graphicFreqs = layout.freqs, graphicQ = layout.q, graphicGains = graphic,
            parametric = prefs.dspParametric, preampDb = prefs.dspPreampDb,
        )
    }
    val layout = DspCoeffBuilder.GRAPHIC_LAYOUTS.getOrElse(prefs.dspGraphicLayout) { DspCoeffBuilder.GRAPHIC_LAYOUTS[0] }
    val nBands = layout.freqs.size
    val graphic = (0 until nBands).map { prefs.dspGraphicBands.getOrElse(it) { 0f } }
    val anyGraphic = graphic.any { it != 0f }

    collapsible("c_graphic", ctx.getString(R.string.eq_graphic), "${layout.name}${if (anyGraphic) ctx.getString(R.string.eq_active_suffix) else ""}", expanded, defaultOpen = true) {
        Ios5SegmentRow("Bands", DspCoeffBuilder.GRAPHIC_LAYOUTS.map { it.name }, prefs.dspGraphicLayout) { i ->
            scope.launch { store.setDspGraphicLayout(i); store.setDspGraphicBands(List(DspCoeffBuilder.GRAPHIC_LAYOUTS[i].freqs.size) { 0f }) }
        }
        if (prefs.dspGraphicLayout == DspCoeffBuilder.USER_EQ_LAYOUT) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(USER_EQ_PRESETS.size) { i ->
                    val presetName = when (i) {
                        1 -> stringResource(R.string.eq_preset_classical)
                        2 -> stringResource(R.string.eq_preset_warm)
                        3 -> stringResource(R.string.eq_preset_bright)
                        else -> stringResource(R.string.eq_preset_flat)
                    }
                    PresetChip(presetName, selected = false) {
                        scope.launch { store.setDspGraphicBands(USER_EQ_PRESETS[i].second) }
                    }
                }
            }
        }
        graphic.forEachIndexed { i, g ->
            if (i > 0) Ios5CellDivider()
            DbSliderRow(freqLabel(layout.freqs[i].toInt()), g, -12f..12f) { v ->
                val updated = graphic.toMutableList().also { it[i] = v }
                scope.launch { store.setDspGraphicBands(updated) }
            }
        }
        TextLink(stringResource(R.string.eq_reset_graphic)) { scope.launch { store.setDspGraphicBands(List(nBands) { 0f }) } }
    }

    collapsible("c_param", ctx.getString(R.string.eq_parametric), ctx.getString(R.string.eq_bands_unit, prefs.dspParametric.size, if (prefs.dspParametric.size == 1) "" else "s"), expanded) {
        prefs.dspParametric.forEachIndexed { i, band ->
            if (i > 0) Ios5CellDivider()
            ParametricBandCard(
                band = band,
                onChange = { nb -> scope.launch { store.setDspParametric(prefs.dspParametric.toMutableList().also { it[i] = nb }) } },
                onRemove = { scope.launch { store.setDspParametric(prefs.dspParametric.toMutableList().also { it.removeAt(i) }) } },
            )
        }
        if (prefs.dspParametric.size < DspCoeffBuilder.MAX_PARAMETRIC) {
            TextLink(stringResource(R.string.eq_add_band)) { scope.launch { store.setDspParametric(prefs.dspParametric + ParamBand(1000f, 0f, 1f)) } }
        }
    }

    collapsible("c_gain", ctx.getString(R.string.eq_gain_headroom), ctx.getString(R.string.eq_preamp_fmt, prefs.dspPreampDb), expanded) {
        Ios5SwitchRow(stringResource(R.string.eq_auto_headroom), stringResource(R.string.eq_auto_headroom_sub), prefs.dspAutoHeadroom) { v ->
            scope.launch { store.setDspAutoHeadroom(v) }
        }
        Ios5CellDivider()
        DbSliderRow(stringResource(R.string.eq_preamp_trim), prefs.dspPreampDb, -12f..12f) { v -> scope.launch { store.setDspPreamp(v) } }
        val peak = androidx.compose.runtime.remember(prefs.dspGraphicBands, prefs.dspParametric, prefs.dspGraphicLayout, activeCorrection) {
            val base = DspCoeffBuilder.eqPeakDb(DspParams(graphic = graphic.toFloatArray(), graphicFreqs = layout.freqs, graphicQ = layout.q, parametric = prefs.dspParametric.map { DspBand(it.freqHz, it.gainDb, it.q, it.type) }))
            maxOf(base, activeCorrection?.takeIf { it.enabled }?.maxGain ?: 0f)
        }
        HeadroomRow(peak = peak, preamp = prefs.dspPreampDb) { scope.launch { store.setDspPreamp((-peak).coerceIn(-12f, 0f)) } }
        Ios5CellDivider()
        FloatSliderRow(stringResource(R.string.eq_balance), prefs.dspBalance, -1f..1f, valueText = balanceLabel(prefs.dspBalance)) { v -> scope.launch { store.setDspBalance(v) } }
    }

    val spatial = buildList { if (prefs.dspWidth != 1f) add("Width %.2f×".format(prefs.dspWidth)); if (prefs.dspCrossfeed > 0f) add("Crossfeed ${(prefs.dspCrossfeed * 100).roundToInt()}%") }.joinToString(" · ").ifBlank { ctx.getString(R.string.eq_spatial_off) }
    collapsible("c_spatial", ctx.getString(R.string.eq_spatial), spatial, expanded) {
        FloatSliderRow(stringResource(R.string.eq_width), prefs.dspWidth, 0f..2f, valueText = "%.2f×".format(prefs.dspWidth)) { v -> scope.launch { store.setDspWidth(v) } }
        Ios5CellDivider()
        FloatSliderRow(stringResource(R.string.eq_crossfeed), prefs.dspCrossfeed, 0f..1f, valueText = if (prefs.dspCrossfeed <= 0f) stringResource(R.string.eq_crossfeed_off) else "${(prefs.dspCrossfeed * 100).roundToInt()}%") { v -> scope.launch { store.setDspCrossfeed(v) } }
    }

    collapsible("c_harm", ctx.getString(R.string.eq_harmonics), if (prefs.dspSaturation > 0f) ctx.getString(R.string.eq_tube_fmt, (prefs.dspSaturation * 100).roundToInt()) else ctx.getString(R.string.eq_harm_off), expanded) {
        FloatSliderRow(stringResource(R.string.eq_tube), prefs.dspSaturation, 0f..1f, valueText = if (prefs.dspSaturation <= 0f) stringResource(R.string.eq_harm_off) else "${(prefs.dspSaturation * 100).roundToInt()}%") { v -> scope.launch { store.setDspSaturation(v) } }
    }

    val aligned = prefs.dspDelayLeftMs > 0f || prefs.dspDelayRightMs > 0f || prefs.dspTrimLeftDb != 0f || prefs.dspTrimRightDb != 0f
    collapsible("c_align", ctx.getString(R.string.eq_alignment), if (aligned) ctx.getString(R.string.eq_align_adjusted) else ctx.getString(R.string.eq_align_off), expanded) {
        FloatSliderRow(stringResource(R.string.eq_delay_left), prefs.dspDelayLeftMs, 0f..20f, valueText = "%.1f ms".format(prefs.dspDelayLeftMs)) { v -> scope.launch { store.setDspDelayLeft(v) } }
        Ios5CellDivider()
        FloatSliderRow(stringResource(R.string.eq_delay_right), prefs.dspDelayRightMs, 0f..20f, valueText = "%.1f ms".format(prefs.dspDelayRightMs)) { v -> scope.launch { store.setDspDelayRight(v) } }
        Ios5CellDivider()
        DbSliderRow(stringResource(R.string.eq_trim_left), prefs.dspTrimLeftDb, -12f..0f) { v -> scope.launch { store.setDspTrimLeft(v) } }
        Ios5CellDivider()
        DbSliderRow(stringResource(R.string.eq_trim_right), prefs.dspTrimRightDb, -12f..0f) { v -> scope.launch { store.setDspTrimRight(v) } }
    }
}

private fun LazyListScope.dynamicsTab(
    ctx: Context,
    prefs: AudioPrefs,
    store: SettingsStore,
    scope: CoroutineScope,
    expanded: SnapshotStateMap<String, Boolean>,
    container: AppContainer,
) {
    // v0.6 driving loudness (plan §35, §38)
    collapsible("c_drive", ctx.getString(R.string.eq_driving),
        DrivingMode.label(prefs.dspDriveMode) + if (prefs.dspDriveMode != DrivingMode.OFF) ctx.getString(R.string.eq_drive_target_fmt, prefs.dspDriveTargetDb) else "",
        expanded, defaultOpen = true) {
        val modes = listOf(DrivingMode.OFF, DrivingMode.NATURAL, DrivingMode.BALANCED, DrivingMode.STRONG, DrivingMode.CUSTOM)
        Ios5SegmentRow("Mode", modes.map { DrivingMode.label(it) }, modes.indexOf(prefs.dspDriveMode).coerceAtLeast(0)) { i ->
            scope.launch { store.setDspDriveMode(modes[i]) }
        }
        Text(
            when (prefs.dspDriveMode) {
                DrivingMode.NATURAL -> stringResource(R.string.eq_drive_natural_hint)
                DrivingMode.BALANCED -> stringResource(R.string.eq_drive_balanced_hint)
                DrivingMode.STRONG -> stringResource(R.string.eq_drive_strong_hint)
                DrivingMode.CUSTOM -> stringResource(R.string.eq_drive_custom_hint)
                else -> stringResource(R.string.eq_drive_off_hint)
            },
            color = Ios5Colors.TextSecondary, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (prefs.dspDriveMode == DrivingMode.CUSTOM) {
            FloatSliderRow(stringResource(R.string.eq_target_loudness), prefs.dspDriveTargetDb, -24f..-8f, valueText = "%.0f LUFS".format(prefs.dspDriveTargetDb)) { v -> scope.launch { store.setDspDriveTarget(v) } }
        }
    }

    val dyn = buildList {
        if (prefs.dspDriveMode != DrivingMode.OFF) add(DrivingMode.label(prefs.dspDriveMode))
        if (prefs.dspLimiterEnabled) add(ctx.getString(R.string.eq_lim))
        if (prefs.dspCompEnabled && prefs.dspDriveMode == DrivingMode.OFF) add(ctx.getString(R.string.eq_compressor))
    }.joinToString(" · ").ifBlank { ctx.getString(R.string.eq_mode_off) }
    collapsible("c_dyn", ctx.getString(R.string.eq_compressor), dyn, expanded) {
        if (prefs.dspDriveMode == DrivingMode.OFF) {
            Ios5SwitchRow(stringResource(R.string.eq_compressor), stringResource(R.string.eq_comp_on_sub), prefs.dspCompEnabled) { v -> scope.launch { store.setDspCompEnabled(v) } }
        } else {
            Text(stringResource(R.string.eq_driven_note),
                color = Ios5Colors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        val showParams = prefs.dspCompEnabled && prefs.dspDriveMode == DrivingMode.OFF || prefs.dspDriveMode == DrivingMode.CUSTOM
        if (showParams) {
            Ios5CellDivider()
            FloatSliderRow(stringResource(R.string.eq_threshold), prefs.dspCompThreshDb, -40f..0f, valueText = "%.0f dB".format(prefs.dspCompThreshDb)) { v -> scope.launch { store.setDspCompThresh(v) } }
            Ios5CellDivider()
            FloatSliderRow(stringResource(R.string.eq_ratio), prefs.dspCompRatio, 1f..10f, valueText = "%.1f:1".format(prefs.dspCompRatio)) { v -> scope.launch { store.setDspCompRatio(v) } }
            Ios5CellDivider()
            FloatSliderRow(stringResource(R.string.eq_attack), prefs.dspCompAttackMs, 1f..200f, valueText = "%.0f ms".format(prefs.dspCompAttackMs)) { v -> scope.launch { store.setDspCompAttack(v) } }
            Ios5CellDivider()
            FloatSliderRow(stringResource(R.string.eq_release), prefs.dspCompReleaseMs, 50f..1000f, valueText = "%.0f ms".format(prefs.dspCompReleaseMs)) { v -> scope.launch { store.setDspCompRelease(v) } }
            Ios5CellDivider()
            FloatSliderRow(stringResource(R.string.eq_knee), prefs.dspCompKneeDb, 0f..12f, valueText = "%.0f dB".format(prefs.dspCompKneeDb)) { v -> scope.launch { store.setDspCompKnee(v) } }
            Ios5CellDivider()
            Ios5SwitchRow(stringResource(R.string.eq_makeup_auto), stringResource(R.string.eq_makeup_auto_sub), prefs.dspMakeupAuto) { v -> scope.launch { store.setDspMakeupAuto(v) } }
            if (!prefs.dspMakeupAuto) {
                Ios5CellDivider()
                DbSliderRow(stringResource(R.string.eq_makeup), prefs.dspCompMakeupDb, -6f..12f) { v -> scope.launch { store.setDspCompMakeup(v) } }
            }
        }
        GainReductionMeter(container)
    }

    collapsible("c_lim", ctx.getString(R.string.eq_lim),
        if (prefs.dspLimiterEnabled) "%.1f dBTP".format(prefs.dspLimiterCeilingDb) else ctx.getString(R.string.eq_mode_off), expanded) {
        Ios5SwitchRow(stringResource(R.string.eq_lim), stringResource(R.string.eq_lim_sub), prefs.dspLimiterEnabled) { v -> scope.launch { store.setDspLimiterEnabled(v) } }
        if (prefs.dspLimiterEnabled) {
            Ios5CellDivider()
            FloatSliderRow(stringResource(R.string.eq_tp_ceiling), prefs.dspLimiterCeilingDb, -6f..0f, valueText = "%.1f dBTP".format(prefs.dspLimiterCeilingDb)) { v -> scope.launch { store.setDspCeiling(v) } }
            Text(stringResource(R.string.eq_tp_hint),
                color = Ios5Colors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        GainReductionMeter(container)
    }
}

@Composable
private fun GainReductionMeter(container: AppContainer) {
    val meters by container.dspMeters.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.DspMeters())
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        GrBar(stringResource(R.string.eq_gr), meters.compGrDb)
        Spacer(Modifier.height(6.dp))
        GrBar(stringResource(R.string.eq_lim), meters.limGrDb)
    }
}

@Composable
private fun GrBar(label: String, grDb: Float) {
    // grDb is 0..-30; bar fills leftwards from 0
    val frac = (-grDb / 30f).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ios5Colors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Box(
            Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50))
                .background(Color(0xFFD6DAE0)),
        ) {
            Box(
                Modifier.fillMaxWidth(frac).height(10.dp).clip(RoundedCornerShape(50))
                    .background(if (grDb < -0.5f) Ios5Colors.IosBlue else Color(0xFF9AA0AB).copy(alpha = 0.4f)),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(if (grDb > -0.05f) "0.0" else "%.1f".format(grDb),
            color = Ios5Colors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text, color = Ios5Colors.IosBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun ParametricBandCard(band: ParamBand, onChange: (ParamBand) -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFD4D9E0), RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(freqLabel(band.freqHz.toInt()), color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.eq_band_fmt, band.gainDb, band.q), color = Ios5Colors.TextSecondary, fontSize = 12.sp)
            Icon(
                Icons.Filled.Close, stringResource(R.string.eq_remove_band),
                tint = Ios5Colors.TextSecondary,
                modifier = Modifier.width(28.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRemove).padding(start = 8.dp),
            )
        }
        FloatSliderRow(stringResource(R.string.eq_freq_label), band.freqHz, 20f..20000f, valueText = freqLabel(band.freqHz.toInt())) { v -> onChange(band.copy(freqHz = v)) }
        DbSliderRow(stringResource(R.string.eq_gain_label), band.gainDb, -15f..15f) { v -> onChange(band.copy(gainDb = v)) }
        FloatSliderRow(stringResource(R.string.eq_q_label), band.q, 0.3f..8f, valueText = "%.2f".format(band.q)) { v -> onChange(band.copy(q = v)) }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) Ios5Colors.IosBlue else Color.White)
            .border(1.dp, if (selected) Ios5Colors.IosBlueDark else Color(0xFFC7CCD4), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Ios5Colors.IosBlue)
    }
}

@Composable
private fun DbSliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Ios5SliderRow(title = label, valueLabel = "%+.1f".format(value), value = value, range = range, onValueChange = onChange)
}

@Composable
private fun FloatSliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    Ios5SliderRow(title = title, valueLabel = valueText, value = value, range = range, onValueChange = onChange)
}

@Composable
private fun balanceLabel(b: Float): String = when {
    b < -0.01f -> "L ${(-b * 100).roundToInt()}%"
    b > 0.01f -> "R ${(b * 100).roundToInt()}%"
    else -> "Center"
}

private fun freqLabel(hz: Int): String = when {
    hz <= 0 -> "—"
    hz >= 1000 -> if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)
    else -> "$hz Hz"
}

// ---- v0.5: 16-band user EQ presets (plan §27) ----
private val USER_EQ_PRESETS: List<Pair<String, List<Float>>> = listOf(
    "Flat" to List(16) { 0f },
    "Classical Neutral" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 0f, -0.5f, -1f),
    "Warm" to listOf(2f, 1.5f, 1f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f, -0.5f, -0.5f, -1f, -1f, -1.5f, -2f),
    "Bright" to listOf(-1f, -0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 1f, 1f, 1.5f, 1.5f, 2f),
)

// ---- v0.5: correction profile list (plan §25) ----
@Composable
private fun CorrectionProfilesPanel(
    corrections: List<com.aurora.music.data.CorrectionProfile>,
    activeId: String,
    active: com.aurora.music.data.CorrectionProfile?,
    store: SettingsStore,
    scope: CoroutineScope,
) {
    val ctx = LocalContext.current
    var importMsg by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val name = runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "imported.txt"
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val imported = text?.let { com.aurora.music.data.EqTextImport.parse(name, it) }
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                if (imported == null) {
                    importMsg = ctx.getString(R.string.eq_import_failed)
                } else {
                    val profile = com.aurora.music.data.CorrectionProfile(
                        id = "corr_${System.currentTimeMillis()}",
                        name = imported.name,
                        deviceName = imported.name,
                        source = com.aurora.music.data.CorrectionSource.CUSTOM,
                        preampDb = imported.preampDb,
                        gains = imported.gains,
                    )
                    store.upsertCorrectionProfile(profile)
                    store.setActiveCorrectionId(profile.id)
                    store.setDspMode(DspMode.CUSTOM)
                    store.setActiveEqProfile(profile.name)
                    importMsg = null
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        CorrectionRow(stringResource(R.string.eq_flat), stringResource(R.string.eq_no_correction), activeId == "flat", canDelete = false,
            onSelect = { scope.launch { store.setActiveCorrectionId("flat") } }, onDelete = {})
        corrections.forEach { p ->
            Ios5CellDivider()
            CorrectionRow(
                name = p.name,
                subtitle = listOf(
                    p.deviceName.ifBlank { null },
                    com.aurora.music.data.CorrectionSource.label(p.source),
                    "max %+.1f dB".format(p.maxGain),
                ).filterNotNull().joinToString(" · "),
                selected = p.id == activeId,
                canDelete = true,
                onSelect = { scope.launch { store.setActiveCorrectionId(p.id); store.setDspMode(DspMode.CUSTOM) } },
                onDelete = { scope.launch { store.removeCorrectionProfile(p.id) } },
            )
        }
        if (active != null && active.id != "flat") {
            Ios5CellDivider()
            DbSliderRow(stringResource(R.string.eq_correction_trim), active.preampDb, -6f..6f) { v ->
                scope.launch { store.upsertCorrectionProfile(active.copy(preampDb = v)) }
            }
            FloatSliderRow(
                stringResource(R.string.eq_strength), active.strengthPct, 0f..120f,
                valueText = "%.0f%%".format(active.strengthPct),
            ) { v ->
                scope.launch { store.upsertCorrectionProfile(active.copy(strengthPct = v)) }
            }
            Text(stringResource(R.string.eq_strength_hint),
                color = Ios5Colors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Ios5CellDivider()
        Ios5ActionRow(
            title = stringResource(R.string.eq_import),
            onClick = { runCatching { picker.launch(arrayOf("text/plain", "*/*")) } },
        )
        if (importMsg != null) {
            Text(importMsg!!, color = Color(0xFFD63A3A), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun CorrectionRow(
    name: String, subtitle: String, selected: Boolean, canDelete: Boolean,
    onSelect: () -> Unit, onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Ios5CheckRow(title = name, subtitle = subtitle, checked = selected, onClick = onSelect)
        }
        if (canDelete) {
            Icon(Icons.Filled.Close, stringResource(R.string.common_delete), tint = Ios5Colors.TextSecondary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDelete).padding(8.dp))
        }
    }
}

// ---- v0.5: audio profiles (plan §30) ----
@Composable
private fun AudioProfilesPanel(
    container: AppContainer,
    prefs: AudioPrefs,
    activeCorrectionId: String,
    profiles: List<com.aurora.music.data.AudioProfile>,
    deviceProfiles: Map<String, String>,
    store: SettingsStore,
    scope: CoroutineScope,
) {
    var name by remember { mutableStateOf("") }
    val outKey = container.autoEqController.currentOutputKey()
    val outLabel = container.autoEqController.currentOutputLabel()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.eq_new_profile_hint)) }, singleLine = true, shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Ios5Colors.IosBlue,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Ios5GlossButton(
                text = stringResource(R.string.eq_save),
                onClick = {
                    scope.launch {
                        container.autoEqController.snapshotCurrent(name.trim(), prefs, activeCorrectionId)
                        name = ""
                    }
                },
            )
        }
        Ios5CellDivider()
        profiles.forEachIndexed { index, p ->
            if (index > 0) Ios5CellDivider()
            val bound = deviceProfiles[outKey] == p.id
            Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch { container.autoEqController.applyAudioProfile(p) }
                }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(p.describe() + if (bound) " · bound to $outLabel" else "",
                        color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
                Text(stringResource(R.string.eq_bind), color = Ios5Colors.IosBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        scope.launch {
                            store.bindDeviceProfile(outKey, if (bound) "" else p.id)
                            store.setAutoEqAutoSwitch(true)
                        }
                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                Icon(Icons.Filled.Close, stringResource(R.string.common_delete), tint = Ios5Colors.TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { scope.launch { store.removeAudioProfile(p.id) } }.padding(4.dp))
            }
        }
        if (profiles.isEmpty()) {
            Text(stringResource(R.string.eq_profile_save_hint),
                color = Ios5Colors.TextSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun eqKindLabel(kind: EqDeviceKind): String = stringResource(
    when (kind) {
        EqDeviceKind.HEADPHONES -> R.string.eq_kind_headphones
        EqDeviceKind.IN_EAR -> R.string.eq_kind_inear
        EqDeviceKind.EARBUDS -> R.string.eq_kind_earbuds
        EqDeviceKind.SPEAKERS -> R.string.eq_kind_speakers
        else -> R.string.eq_kind_all
    }
)

@Composable
private fun eqKindDescription(kind: EqDeviceKind): String = stringResource(
    when (kind) {
        EqDeviceKind.HEADPHONES -> R.string.eq_kind_headphones_sub
        EqDeviceKind.IN_EAR -> R.string.eq_kind_inear_sub
        EqDeviceKind.EARBUDS -> R.string.eq_kind_earbuds_sub
        EqDeviceKind.SPEAKERS -> R.string.eq_kind_speakers_sub
        else -> R.string.eq_kind_all_sub
    }
)
