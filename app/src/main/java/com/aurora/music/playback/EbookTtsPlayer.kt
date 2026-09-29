package com.aurora.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
 * 无 MediaSession、无通知。默认无音频焦点（不打断音乐；混音由系统叠加）；
 * [duckable] 播放器在用户打开 duck 开关后，开播时请求
 * MAY_DUCK 焦点（导航口径），压低站外音乐，停播即归还。
 * ExoPlayer 必须在主线程创建，调用方（阅读界面）保证主线程调用 [ensurePlayer]。
 */
@UnstableApi
class EbookTtsPlayer(
    private val appContext: Context,
    private val settingsStore: com.aurora.music.data.SettingsStore,
    private val sessionId: Int,
    private val duckable: Boolean = false,
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

    // ---- MAY_DUCK 焦点（仅 duckable 播放器 + 用户开 duckOthers 时持有） ----
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    @Volatile private var duckOthers = false
    @Volatile private var streamActive = false
    @Volatile private var focusHeld = false
    private var focusRequest: android.media.AudioFocusRequest? = null
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { }

    private fun updateDuckFocus() {
        if (!duckable) return
        if (duckOthers && streamActive) requestDuckFocus() else abandonDuckFocus()
    }

    private fun requestDuckFocus() {
        if (focusHeld) return
        val res = if (android.os.Build.VERSION.SDK_INT >= 26) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
        focusHeld = res == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonDuckFocus() {
        if (!focusHeld && focusRequest == null) return
        focusHeld = false
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
        focusRequest = null
    }

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
        if (duckable) {
            // 开关播中切换也即时生效：开→压站外，关→归还
            scope.launch {
                settingsStore.unifiedTts.collect {
                    duckOthers = it.duckOthers
                    updateDuckFocus()
                }
            }
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

    private var memUriSeq: Long = 0

    private fun memSource(wav: ByteArray): MediaSource {
        val factory = DataSource.Factory { ByteArrayDataSource(wav) }
        return ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.parse("tts-mem://${memUriSeq++}")))
    }

    /**
     * 内存 WAV 流开播（首块就绪即播）：停掉旧队列并从该块起播。
     * 输入为完整 WAV 字节（含 44 字节头，48k 立体声 16bit），必须在主线程调。
     */
    fun startWavStream(first: ByteArray) {
        ensurePlayer()
        val p = player ?: return
        p.stop()
        p.clearMediaItems()
        p.setMediaSource(memSource(first))
        p.prepare()
        p.play()
        streamActive = true
        updateDuckFocus()
    }

    /** 内存 WAV 流追块：首块播着时后续块边合边加；代际由调用方（TtsWorker）保证。 */
    fun appendWav(bytes: ByteArray) {
        val p = player ?: return
        p.addMediaSource(memSource(bytes))
        // 已播到 ENDED 后又追块：回到尾块重备继续（LLM 供句抖动时会出现）
        if (p.playbackState == Player.STATE_ENDED) {
            runCatching {
                p.seekTo(p.mediaItemCount - 1, 0)
                p.prepare()
                p.play()
            }
        }
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
        streamActive = false
        updateDuckFocus()
    }

    fun release() {
        streamActive = false
        abandonDuckFocus()
        runCatching { player?.release() }
        player = null
    }
}
