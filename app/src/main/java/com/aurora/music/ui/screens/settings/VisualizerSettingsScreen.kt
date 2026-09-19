package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.VisualizerPrefs
import com.aurora.music.data.VisualizerStyle
import com.aurora.music.data.VizColor
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5CheckRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5SegmentRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5Section
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
    val strTitle = stringResource(R.string.settings_visualizer)
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

    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        item { VisualizerPreview(prefs) }

        ios5Section(strStyle) {
            Column(Modifier.fillMaxWidth()) {
                (0 until VisualizerStyle.count).forEachIndexed { index, s ->
                    if (index > 0) Ios5CellDivider()
                    Ios5CheckRow(
                        title = vizStyleName(s),
                        checked = s == prefs.style,
                        onClick = { save(prefs.copy(style = s)) },
                    )
                }
            }
        }

        ios5Section(strColour) {
            Ios5SegmentRow(strColourSource, vizSrcOptions(), prefs.colorSource) { save(prefs.copy(colorSource = it)) }
        }
        if (prefs.colorSource == VizColor.CUSTOM || prefs.colorSource == VizColor.GRADIENT) {
            item { Swatches(strPrimary, prefs.primaryColor) { save(prefs.copy(primaryColor = it)) } }
        }
        if (prefs.colorSource == VizColor.GRADIENT) {
            item { Swatches(strSecondary, prefs.secondaryColor) { save(prefs.copy(secondaryColor = it)) } }
        }

        ios5Section(strBackground) {
            Ios5SegmentRow(strBackdrop, vizBgOptions(), prefs.background) { save(prefs.copy(background = it)) }
        }

        ios5Section(strSpectrum) {
            Ios5SliderRow(strBarCount, "${prefs.barCount}", prefs.barCount.toFloat(), 16f..160f) { save(prefs.copy(barCount = it.toInt())) }
            Ios5CellDivider()
            Ios5SliderRow(strSmoothing, "${(prefs.smoothing * 100).toInt()}%", prefs.smoothing, 0f..0.95f) { save(prefs.copy(smoothing = it)) }
            Ios5CellDivider()
            Ios5SliderRow(strSensitivity, String.format("%.2fx", prefs.sensitivity), prefs.sensitivity, 0.25f..4f) { save(prefs.copy(sensitivity = it)) }
            Ios5CellDivider()
            Ios5SwitchRow(strPeakHold, strPeakHoldSub, prefs.peakHold) { save(prefs.copy(peakHold = it)) }
            Ios5CellDivider()
            Ios5SwitchRow(strMirror, strMirrorSub, prefs.mirror) { save(prefs.copy(mirror = it)) }
        }

        ios5Section(strFreqRange) {
            Ios5SliderRow(strLowCut, "${prefs.minHz} Hz", prefs.minHz.toFloat(), 10f..500f) { save(prefs.copy(minHz = it.toInt())) }
            Ios5CellDivider()
            Ios5SliderRow(strHighCut, "${prefs.maxHz / 1000} kHz", prefs.maxHz.toFloat(), 2000f..22000f) { save(prefs.copy(maxHz = it.toInt())) }
        }

        ios5Section(strMotion) {
            val fftIdx = when (prefs.fftSize) { 1024 -> 0; 4096 -> 2; else -> 1 }
            Ios5SegmentRow(strFft, listOf("1024", "2048", "4096"), fftIdx) {
                save(prefs.copy(fftSize = when (it) { 0 -> 1024; 2 -> 4096; else -> 2048 }))
            }
            Ios5CellDivider()
            val fpsIdx = when (prefs.fpsCap) { 30 -> 0; 90 -> 2; 120 -> 3; else -> 1 }
            Ios5SegmentRow(strFps, listOf("30", "60", "90", "120"), fpsIdx) {
                save(prefs.copy(fpsCap = when (it) { 0 -> 30; 2 -> 90; 3 -> 120; else -> 60 }))
            }
            Ios5CellDivider()
            Ios5SwitchRow(strRotate, strRotateSub, prefs.rotate) { save(prefs.copy(rotate = it)) }
            Ios5CellDivider()
            Ios5SliderRow(strParticles, "${prefs.particleCount}", prefs.particleCount.toFloat(), 20f..400f) { save(prefs.copy(particleCount = it.toInt())) }
        }

        ios5Section(strOverlay) {
            Ios5SwitchRow(strArtCentre, strArtCentreSub, prefs.showAlbumArt) { save(prefs.copy(showAlbumArt = it)) }
            Ios5CellDivider()
            Ios5SwitchRow(strTrackInfo, strTrackInfoSub, prefs.showTrackInfo) { save(prefs.copy(showTrackInfo = it)) }
        }

        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
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
            .clip(RoundedCornerShape(10.dp)).background(Color.Black),
    ) {
        VisualizerCanvas(controller, prefs, colors, Modifier.fillMaxWidth().height(160.dp).padding(8.dp))
        if (controller.frame.level <= 0.001f) {
            Text(
                stringResource(R.string.viz_play_something),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun Swatches(label: String, selected: Int, onPick: (Int) -> Unit) {
    Ios5Group(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            label,
            color = Ios5Colors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 10.dp),
        )
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(SWATCHES) { c ->
                val isSel = c == selected
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .then(if (isSel) Modifier.border(3.dp, Ios5Colors.IosBlue, CircleShape) else Modifier)
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
