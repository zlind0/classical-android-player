package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.VisualizerPrefs
import com.aurora.music.data.VisualizerStyle
import com.aurora.music.data.VizBackground
import com.aurora.music.data.VizColor
import com.aurora.music.ui.screens.visualizer.VisualizerCanvas
import com.aurora.music.ui.screens.visualizer.VizColors
import kotlinx.coroutines.launch

private val SWATCHES = listOf(
    0xFF7C4DFF, 0xFF00E5FF, 0xFF28D572, 0xFFFF5252, 0xFFFFC107,
    0xFFFF4081, 0xFF40C4FF, 0xFFB388FF, 0xFF18FFFF, 0xFFFFFFFF,
).map { it.toInt() }

@Composable
fun VisualizerSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val controller = container.visualizer
    val prefs by store.visualizerPrefs.collectAsStateWithLifecycle(initialValue = VisualizerPrefs())
    val scope = rememberCoroutineScope()
    fun save(p: VisualizerPrefs) { scope.launch { store.setVisualizer(p) } }

    // Hoisted: stringResource is @Composable-only and illegal in LazyColumn DSL / plain lambdas.
    val strStyle = stringResource(R.string.viz_style)
    val strColour = stringResource(R.string.viz_colour)
    val strColourSource = stringResource(R.string.viz_colour_source)
    val strPrimary = stringResource(R.string.viz_primary)
    val strSecondary = stringResource(R.string.viz_secondary)
    val strBackground = stringResource(R.string.viz_background)
    val strBackdrop = stringResource(R.string.viz_backdrop)
    val strSpectrum = stringResource(R.string.viz_spectrum)
    val strBarCount = stringResource(R.string.viz_bar_count)
    val strSmoothing = stringResource(R.string.viz_smoothing)
    val strSensitivity = stringResource(R.string.viz_sensitivity)
    val strPeakHold = stringResource(R.string.viz_peak_hold)
    val strPeakHoldSub = stringResource(R.string.viz_peak_hold_sub)
    val strMirror = stringResource(R.string.viz_mirror)
    val strMirrorSub = stringResource(R.string.viz_mirror_sub)
    val strFreqRange = stringResource(R.string.viz_freq_range)
    val strLowCut = stringResource(R.string.viz_low_cut)
    val strHighCut = stringResource(R.string.viz_high_cut)
    val strMotion = stringResource(R.string.viz_motion)
    val strFft = stringResource(R.string.viz_fft)
    val strFps = stringResource(R.string.viz_fps)
    val strRotate = stringResource(R.string.viz_rotate)
    val strRotateSub = stringResource(R.string.viz_rotate_sub)
    val strParticles = stringResource(R.string.viz_particles)
    val strOverlay = stringResource(R.string.viz_overlay)
    val strArtCentre = stringResource(R.string.viz_art_centre)
    val strArtCentreSub = stringResource(R.string.viz_art_centre_sub)
    val strTrackInfo = stringResource(R.string.viz_track_info)
    val strTrackInfoSub = stringResource(R.string.viz_track_info_sub)

    // Drive the analyser so the preview reacts to whatever is playing.
    DisposableEffect(Unit) { controller.start(); onDispose { controller.stop() } }
    LaunchedEffect(prefs.barCount, prefs.fftSize, prefs.smoothing, prefs.sensitivity, prefs.minHz, prefs.maxHz, prefs.peakHold, prefs.fpsCap) {
        controller.applyPrefs(prefs)
    }

    Column(Modifier.fillMaxWidth()) {
        SettingsTopBar(stringResource(R.string.settings_visualizer), onBack)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            item { VisualizerPreview(prefs) }

            item { SettingsSectionTitle(strStyle) }
            item {
                LazyRow(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    items((0 until VisualizerStyle.count).toList()) { s ->
                        val selected = s == prefs.style
                        Box(
                            Modifier.clip(RoundedCornerShape(50))
                                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { save(prefs.copy(style = s)) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Text(
                                vizStyleName(s),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            item { SettingsSectionTitle(strColour) }
            item {
                SettingsGroup {
                    SegmentedRow(strColourSource, vizSrcOptions(), prefs.colorSource) { save(prefs.copy(colorSource = it)) }
                }
            }
            if (prefs.colorSource == VizColor.CUSTOM || prefs.colorSource == VizColor.GRADIENT) {
                item { Swatches(strPrimary, prefs.primaryColor) { save(prefs.copy(primaryColor = it)) } }
            }
            if (prefs.colorSource == VizColor.GRADIENT) {
                item { Swatches(strSecondary, prefs.secondaryColor) { save(prefs.copy(secondaryColor = it)) } }
            }

            item { SettingsSectionTitle(strBackground) }
            item {
                SettingsGroup {
                    SegmentedRow(strBackdrop, vizBgOptions(), prefs.background) { save(prefs.copy(background = it)) }
                }
            }

            item { SettingsSectionTitle(strSpectrum) }
            item {
                SettingsGroup {
                    SettingsSliderRow(strBarCount, "${prefs.barCount}", prefs.barCount.toFloat(), 16f..160f, steps = 0) { save(prefs.copy(barCount = it.toInt())) }
                    SettingsRowDivider()
                    SettingsSliderRow(strSmoothing, "${(prefs.smoothing * 100).toInt()}%", prefs.smoothing, 0f..0.95f) { save(prefs.copy(smoothing = it)) }
                    SettingsRowDivider()
                    SettingsSliderRow(strSensitivity, String.format("%.2fx", prefs.sensitivity), prefs.sensitivity, 0.25f..4f) { save(prefs.copy(sensitivity = it)) }
                    SettingsRowDivider()
                    SettingsSwitchRow(null, strPeakHold, strPeakHoldSub, prefs.peakHold) { save(prefs.copy(peakHold = it)) }
                    SettingsRowDivider()
                    SettingsSwitchRow(null, strMirror, strMirrorSub, prefs.mirror) { save(prefs.copy(mirror = it)) }
                }
            }

            item { SettingsSectionTitle(strFreqRange) }
            item {
                SettingsGroup {
                    SettingsSliderRow(strLowCut, "${prefs.minHz} Hz", prefs.minHz.toFloat(), 10f..500f) { save(prefs.copy(minHz = it.toInt())) }
                    SettingsRowDivider()
                    SettingsSliderRow(strHighCut, "${prefs.maxHz / 1000} kHz", prefs.maxHz.toFloat(), 2000f..22000f) { save(prefs.copy(maxHz = it.toInt())) }
                }
            }

            item { SettingsSectionTitle(strMotion) }
            item {
                SettingsGroup {
                    val fftIdx = when (prefs.fftSize) { 1024 -> 0; 4096 -> 2; else -> 1 }
                    SegmentedRow(strFft, listOf("1024", "2048", "4096"), fftIdx) {
                        save(prefs.copy(fftSize = when (it) { 0 -> 1024; 2 -> 4096; else -> 2048 }))
                    }
                    SettingsRowDivider()
                    val fpsIdx = when (prefs.fpsCap) { 30 -> 0; 90 -> 2; 120 -> 3; else -> 1 }
                    SegmentedRow(strFps, listOf("30", "60", "90", "120"), fpsIdx) {
                        save(prefs.copy(fpsCap = when (it) { 0 -> 30; 2 -> 90; 3 -> 120; else -> 60 }))
                    }
                    SettingsRowDivider()
                    SettingsSwitchRow(null, strRotate, strRotateSub, prefs.rotate) { save(prefs.copy(rotate = it)) }
                    SettingsRowDivider()
                    SettingsSliderRow(strParticles, "${prefs.particleCount}", prefs.particleCount.toFloat(), 20f..400f) { save(prefs.copy(particleCount = it.toInt())) }
                }
            }

            item { SettingsSectionTitle(strOverlay) }
            item {
                SettingsGroup {
                    SettingsSwitchRow(null, strArtCentre, strArtCentreSub, prefs.showAlbumArt) { save(prefs.copy(showAlbumArt = it)) }
                    SettingsRowDivider()
                    SettingsSwitchRow(null, strTrackInfo, strTrackInfoSub, prefs.showTrackInfo) { save(prefs.copy(showTrackInfo = it)) }
                }
            }
        }
    }
}

@Composable
private fun VisualizerPreview(prefs: VisualizerPrefs) {
    val ctx = LocalContext.current
    val container = remember { (ctx.applicationContext as AuroraApplication).container }
    val controller = container.visualizer
    val colors = when (prefs.colorSource) {
        VizColor.CUSTOM -> VizColors(Color(prefs.primaryColor), Color(prefs.primaryColor))
        VizColor.GRADIENT -> VizColors(Color(prefs.primaryColor), Color(prefs.secondaryColor))
        else -> VizColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    }
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(160.dp)
            .clip(RoundedCornerShape(18.dp)).background(Color.Black),
    ) {
        VisualizerCanvas(controller, prefs, colors, Modifier.fillMaxWidth().height(160.dp).padding(8.dp))
        if (controller.frame.level <= 0.001f) {
            Text(
                stringResource(R.string.viz_play_something),
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun Swatches(label: String, selected: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(SWATCHES) { c ->
                val isSel = c == selected
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { onPick(c) },
                )
            }
        }
    }
}

@Composable
private fun vizSrcOptions(): List<String> = listOf(
    stringResource(R.string.viz_src_accent),
    stringResource(R.string.viz_src_custom),
    stringResource(R.string.viz_src_gradient),
    stringResource(R.string.viz_src_album),
)

@Composable
private fun vizBgOptions(): List<String> = listOf(
    stringResource(R.string.viz_bg_black),
    stringResource(R.string.viz_bg_gradient),
    stringResource(R.string.viz_bg_blur),
)

@Composable
private fun vizStyleName(s: Int): String = stringResource(
    when (s) {
        VisualizerStyle.MIRROR_BARS -> R.string.viz_style_mirror
        VisualizerStyle.WAVEFORM -> R.string.viz_style_waveform
        VisualizerStyle.FILLED_WAVE -> R.string.viz_style_filled
        VisualizerStyle.RADIAL_BARS -> R.string.viz_style_radial_bars
        VisualizerStyle.RADIAL_WAVE -> R.string.viz_style_radial_wave
        VisualizerStyle.PARTICLES -> R.string.viz_style_particles
        VisualizerStyle.FLUID -> R.string.viz_style_fluid
        VisualizerStyle.COMBO -> R.string.viz_style_combo
        VisualizerStyle.SMOOTH_CURVE -> R.string.viz_style_curve
        VisualizerStyle.DOT_GRID -> R.string.viz_style_dots
        VisualizerStyle.RINGS -> R.string.viz_style_rings
        VisualizerStyle.ORB -> R.string.viz_style_orb
        VisualizerStyle.LADDER -> R.string.viz_style_ladder
        VisualizerStyle.HORIZON -> R.string.viz_style_horizon
        VisualizerStyle.CONSTELLATION -> R.string.viz_style_constellation
        VisualizerStyle.PEAK_DOTS -> R.string.viz_style_peak
        VisualizerStyle.SPECTRUM_LINE -> R.string.viz_style_neon
        VisualizerStyle.AURORA -> R.string.viz_style_aurora
        VisualizerStyle.SPECTRAL_RIVER -> R.string.viz_style_river
        VisualizerStyle.SPECTRAL_TERRAIN -> R.string.viz_style_terrain
        VisualizerStyle.CURL_FLOW -> R.string.viz_style_curl
        VisualizerStyle.STRANGE_ATTRACTOR -> R.string.viz_style_attractor
        VisualizerStyle.CYMATIC -> R.string.viz_style_cymatic
        VisualizerStyle.SUPERFORMULA_BLOOM -> R.string.viz_style_bloom
        VisualizerStyle.WORMHOLE -> R.string.viz_style_wormhole
        VisualizerStyle.PLASMA -> R.string.viz_style_plasma
        VisualizerStyle.SILK_VEIL -> R.string.viz_style_silk
        VisualizerStyle.NEBULA -> R.string.viz_style_nebula
        VisualizerStyle.HARMONOGRAPH -> R.string.viz_style_harmonograph
        VisualizerStyle.INK_BLOOM -> R.string.viz_style_ink
        else -> R.string.viz_style_bars
    }
)
