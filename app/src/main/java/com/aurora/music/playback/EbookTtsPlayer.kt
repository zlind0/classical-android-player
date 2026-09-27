package com.aurora.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.CorrectionProfile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 听书专用播放器：与音乐链完全相同的软件 DSP 链
 * [mono → correctionConv(AutoEQ) → auroraDsp(用户EQ/动态) → convolver]，
 * 同一个 audioSession → 系统 Effect（均衡器/低音/虚拟化/响度）同样生效。
 * 输入永远是 48kHz 立体声 16bit WAV（合成侧归一化好），链路行为与音乐一致。
 *
 * 无 MediaSession、无通知、无音频焦点（不打断音乐；混音由系统叠加）。
 * ExoPlayer 必须在主线程创建，调用方（阅读界面）保证主线程调用 [ensurePlayer]。
 */
@UnstableApi
class EbookTtsPlayer(
    private val appContext: Context,
    private val settingsStore: com.aurora.music.data.SettingsStore,
    private val sessionId: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val monoProcessor = MonoAudioProcessor()
    private val correctionConv = ConvolutionProcessor()
    private val auroraDsp = AuroraDspProcessor()
    private val convolver = ConvolutionProcessor()

    @Volatile private var player: ExoPlayer? = null

    @Volatile private var lastAudioPrefs: AudioPrefs? = null
    @Volatile private var correctionMaxGainDb: Float = 0f
    @Volatile private var correctionTrimDb: Float = 0f
    @Volatile private var correctionActive: Boolean = false
    @Volatile private var lastIrPath: String = ""
    @Volatile private var monoAudioPref: Boolean = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 播放队列播完（最后一个 item ENDED）时触发一次。 */
    @Volatile var onQueueEnded: (() -> Unit)? = null

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
            ) { profiles, activeId -> profiles.firstOrNull { it.id == activeId } }
                .collect { applyCorrectionProfile(it) }
        }
    }

    fun ensurePlayer() {
        if (player != null) return
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val sink = DefaultAudioSink.Builder(appContext)
            .setAudioProcessors(arrayOf(monoProcessor, correctionConv, auroraDsp, convolver))
            .setEnableFloatOutput(false)
            .build()
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink = sink
        }
        val p = ExoPlayer.Builder(appContext, renderers)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        if (sessionId != 0) runCatching { p.setAudioSessionId(sessionId) }
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onQueueEnded?.invoke()
            }
        })
        player = p
        applyEngine()
    }

    private fun applyEngine() {
        val ap = lastAudioPrefs ?: return
        auroraDsp.update(buildDspParams(ap, correctionMaxGainDb, correctionTrimDb, 0f))
        auroraDsp.enabled = true
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

    private fun applyCorrectionProfile(profile: CorrectionProfile?) {
        val id = profile?.id ?: "flat"
        val gains = profile?.scaledGains().orEmpty()
        val on = profile?.enabled == true && !CorrectionCompiler.isFlat(gains) && id != "flat"
        correctionMaxGainDb = if (on) gains.maxOrNull() ?: 0f else 0f
        correctionTrimDb = if (on) profile?.preampDb ?: 0f else 0f
        correctionActive = on
        if (!on) {
            correctionConv.enabled = false
            applyEngine()
            return
        }
        // TTS 音频固定 48k，直接按 48k 编译 FIR（音乐链按实际输出率编译，此处恒定）
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

    /** 替换整个队列并从头播放。 */
    fun playFiles(files: List<File>) {
        ensurePlayer()
        val p = player ?: return
        p.stop()
        p.clearMediaItems()
        files.forEach { f ->
            p.addMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        }
        p.prepare()
        p.play()
    }

    fun play() {
        ensurePlayer()
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        _isPlaying.value = false
    }

    fun release() {
        runCatching { player?.release() }
        player = null
    }
}
