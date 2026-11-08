package com.aurora.music.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.CorrectionProfile
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.applyCorrectionCutoffs
import com.aurora.music.data.correctionFreqs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shared DSP assembly for every cast source (plan §5.2).
 *
 * One instance == one player: processors keep per-player state and must not be
 * shared. The chain mirrors [EbookTtsPlayer] exactly so AirPlay / legacy cast
 * audio goes through the same EQ, AutoEq FIR, user IR, compressor, limiter,
 * spatial processing, mono and balance as local music, and taps the same
 * [VisualizerController].
 */
@UnstableApi
class DspAudioChain(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val sessionId: Int,
    private val visualizer: VisualizerController,
    private val scope: CoroutineScope,
) {
    private val monoProcessor = MonoAudioProcessor()
    private val correctionConv = ConvolutionProcessor()
    private val auroraDsp = AuroraDspProcessor()
    private val convolver = ConvolutionProcessor()

    @Volatile private var lastAudioPrefs: AudioPrefs? = null
    @Volatile private var correctionMaxGainDb = 0f
    @Volatile private var correctionTrimDb = 0f
    @Volatile private var correctionActive = false
    @Volatile private var lastIrPath = ""
    @Volatile private var monoAudioPref = false

    init {
        scope.launch {
            settingsStore.playbackPrefs.collect {
                monoAudioPref = it.monoAudio
                applyEngine()
            }
        }
        scope.launch {
            settingsStore.audioPrefs.collect {
                lastAudioPrefs = it
                applyEngine()
            }
        }
        scope.launch {
            combine(
                settingsStore.correctionProfiles,
                settingsStore.activeCorrectionId,
                settingsStore.correctionLowcutHz,
                settingsStore.correctionHighcutHz,
            ) { profiles, activeId, lowcut, highcut ->
                Triple(profiles.firstOrNull { it.id == activeId }, lowcut, highcut)
            }.collect { (profile, lowcut, highcut) -> applyCorrectionProfile(profile, lowcut, highcut) }
        }
    }

    /** Raw PCM sink used by [PcmStreamPlayer] (AirPlay / RTP protocols). */
    fun createSink(): AudioSink {
        val sink = DefaultAudioSink.Builder(appContext)
            .setAudioProcessors(arrayOf(monoProcessor, correctionConv, auroraDsp, convolver))
            .setEnableFloatOutput(false)
            .build()
        return TappingAudioSink(sink, visualizer)
    }

    /** ExoPlayer with the same sink, used for URL-based cast media. */
    fun createPlayer(): ExoPlayer {
        val sink = createSink()
        val renderers = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = sink
        }
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val p = ExoPlayer.Builder(appContext, renderers)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        if (sessionId != 0) runCatching { p.setAudioSessionId(sessionId) }
        return p
    }

    private fun applyEngine() {
        val ap = lastAudioPrefs ?: return
        val params = buildDspParams(ap, correctionMaxGainDb, correctionTrimDb, 0f)
        auroraDsp.update(params)
        auroraDsp.enabled = !params.isNeutral()
        correctionConv.enabled = correctionActive
        convolver.enabled = ap.dspConvEnabled
        convolver.setMakeup(ap.dspConvMakeupDb)
        if (ap.dspConvIrPath != lastIrPath) {
            lastIrPath = ap.dspConvIrPath
            scope.launch(Dispatchers.IO) {
                val ir = ap.dspConvIrPath.takeIf { it.isNotBlank() }
                    ?.let { runCatching { ConvolutionProcessor.loadWav(File(it)) }.getOrNull() }
                convolver.setImpulse(ir, ap.dspConvMakeupDb)
            }
        }
        monoProcessor.enabled = monoAudioPref
    }

    private fun applyCorrectionProfile(profile: CorrectionProfile?, lowcutHz: Float, highcutHz: Float) {
        val id = profile?.id ?: "flat"
        val gains = applyCorrectionCutoffs(profile?.scaledGains().orEmpty(), correctionFreqs(), lowcutHz, highcutHz)
        val on = profile?.enabled == true && !CorrectionCompiler.isFlat(gains) && id != "flat"
        correctionMaxGainDb = if (on) gains.maxOrNull() ?: 0f else 0f
        correctionTrimDb = if (on) profile?.preampDb ?: 0f else 0f
        correctionActive = on
        if (!on) {
            correctionConv.enabled = false
            applyEngine()
            return
        }
        scope.launch(Dispatchers.IO) {
            val taps = runCatching { CorrectionCompiler.gainsToFir(gains.toFloatArray(), 48000) }.getOrNull()
            if (taps != null) {
                correctionConv.setImpulse(ImpulseResponse(taps, taps, 48000), 0f)
                correctionConv.enabled = true
            } else {
                correctionConv.enabled = false
            }
            applyEngine()
        }
    }
}
