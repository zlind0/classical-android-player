package com.aurora.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.R
import com.aurora.music.data.AudioEffectsController
import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.DspMode
import com.aurora.music.data.MusicPlaybackState
import com.aurora.music.data.SignalPath
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private val container by lazy { (application as AuroraApplication).container }
    private val browseCache = java.util.concurrent.ConcurrentHashMap<String, MediaItem>()
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, List<MediaItem>>()
    private val LIBRARY_ROOT = "root"
    private lateinit var player: ExoPlayer
    private var fadePlayer: ExoPlayer? = null
    private val monoProcessor = MonoAudioProcessor()
    private val correctionConv = ConvolutionProcessor()
    private val auroraDsp = AuroraDspProcessor()
    private val convolver = ConvolutionProcessor()
    @Volatile private var lastIrPath: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var audioEffects: AudioEffectsController? = null
    @Volatile private var crossfadeMs: Int = 0
    @Volatile private var replayGainMode: Int = 0
    @Volatile private var monoAudioPref: Boolean = false
    @Volatile private var lastAudioPrefs: AudioPrefs? = null
    @Volatile private var useFloatOut: Boolean = false
    @Volatile private var grantedBitPerfect: Boolean = false
    @Volatile private var deviceSupportsBitPerfect: Boolean = false
    @Volatile private var preferHighResPref: Boolean = false
    @Volatile private var independentOutput: Boolean = false
    // 听书混音：站内音乐在听书时的保留音量（用户可调，精确值由本服务手动压）。
    @Volatile private var ebookPlaying = false
    @Volatile private var ebookMusicLevel = 0.2f
    @Volatile private var ebookDuckOthers = false
    @Volatile private var ebookDuckApplied = 1f
    @Volatile private var ebookFocusDropped = false
    private var musicAudioAttrs: AudioAttributes? = null
    // 有声书：当前播的是否是有声书（mediaId 前缀判定）+ 本书增益 + 混音开关 + 步长
    @Volatile private var abookActive = false
    @Volatile private var abookGain = 1f
    @Volatile private var abookGainApplied = 1f
    @Volatile private var abookAllowMix = false
    @Volatile private var abookDuckOthers = true
    @Volatile private var abookSeekSec = 30
    @Volatile private var abookDuckHeld = false
    private var abookDuckRequest: android.media.AudioFocusRequest? = null
    // VM 装载的书（开播前已置位、切歌/停止时清空；tick 用它在出声前就保持丢焦点）
    @Volatile private var abookBook: String? = null
    private val abookFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        scope.launch { handleAbookFocusChange(change) }
    }
    // 书是否真正在出声（队列是书 + playing；暂停/切走即 false）
    @Volatile private var abookPlayingNow = false
    private var abookPlaybackCb: AudioManager.AudioPlaybackCallback? = null
    @Volatile private var lastReduckMs = 0L
    @Volatile private var lastMusicStatePublishMs = 0L
    @Volatile private var musicQueueIds: List<String> = emptyList()
    // 上一次推送的音乐进度（单次 0 防抖：seek/缓冲瞬间不把进度推成 0）
    @Volatile private var lastMusicPosition: PositionState? = null

    // ---- 统一会话（单服务单会话）：音乐 + 听书共用一个系统会话 ----
    // 双播一旦进入（dualArmed）就保持，直到用户亲手停掉其中一路；
    // 外部暂停（耳机/来电/系统暂停键）只停播放不退出，双恢复靠 externalSnapshot。
    @Volatile private var dualArmed = false
    private var unifiedPlayer: UnifiedPlayer? = null
    private var noisyReceiver: BroadcastReceiver? = null
    @Volatile private var lastBookPara: com.aurora.music.data.ebook.EbookTtsController.Para? = null

    private data class ExternalSnapshot(
        val musicWasPlaying: Boolean,
        val bookPara: com.aurora.music.data.ebook.EbookTtsController.Para?,
        /** true=瞬态（来电），挂断自动双恢复；false=常停，等系统/用户播放键。 */
        val autoResume: Boolean,
    )

    @Volatile private var externalSnapshot: ExternalSnapshot? = null

    private val dualControl = object : DualMusicControl {
        override fun isMusicPlaying(): Boolean = runCatching { player.isPlaying }.getOrDefault(false)
        // 双播时系统上/下曲切音乐：预留口，默认不处理（只动书），后面再实现。
        override fun switchMusicFromSystem(direction: Int): Boolean = false
    }

    @Volatile private var sleepFadeActive = false
    private var sleepFadeStartMs = 0L
    private var sleepFadeMs = 0
    @Volatile private var wakeFadeActive = false
    private var wakeFadeStartMs = 0L
    private var wakeFadeMs = 0
    private val nowPlaying by lazy { NowPlayingStore(this) }

    private var xfadeActive = false
    @Volatile private var xfadeBpPending = false   // bit-perfect crossfade fired, awaiting the transition
    private var xfadeStartMs = 0L
    private var xfadeExpectedId: String? = null
    private var xfadeInGain = 1f
    private var xfadeOutGain = 1f
    @Volatile private var bitPerfect = false     // crossfade disabled in bit-perfect usb mode

    // pre-shuffle queue order for restore on disable null = not shuffled
    private var originalOrder: List<String>? = null
    // items may lag the command so flag and apply neutralize on next timeline change
    private var pendingNeutralize = false
    private var usbSink: com.decent.usbaudio.media3.UsbAudioSink? = null

    // v0.6 correction convolver state (compiled off-thread, applied via setImpulse)
    @Volatile private var correctionMaxGainDb: Float = 0f
    @Volatile private var correctionTrimDb: Float = 0f
    @Volatile private var correctionActive: Boolean = false
    @Volatile private var lastCorrectionId: String = "flat"

    // v0.6 driving loudness state (plan §34): per-track make-up toward target LUFS
    @Volatile private var driveOn: Boolean = false
    @Volatile private var driveTargetDb: Float = -16f
    @Volatile private var driveGainDb: Float = 0f
    // v0.7: whole-chain bypass flag for the signal-path readout (see applyAudioEngine)
    @Volatile private var dspBypassed: Boolean = true

    override fun onCreate() {
        super.onCreate()

        // float pipeline bypasses all app processors (sonic mono silence-skip custom dsp) so custom dsp needs 16-bit and keeps float off when active decided once here so engine switch takes effect next playback start
        val highRes = runBlocking { container.settingsStore.playbackPrefs.first().preferHighRes }
        val startupDspMode = runBlocking { container.settingsStore.audioPrefs.first().dspMode }
        val bitPerfectUsb = runBlocking { container.settingsStore.playbackPrefs.first().bitPerfectUsb }
        bitPerfect = bitPerfectUsb
        val useFloat = bitPerfectUsb || (highRes && startupDspMode != DspMode.CUSTOM)
        useFloatOut = useFloat

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        musicAudioAttrs = audioAttributes

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                if (bitPerfectUsb) {
                    // no dsp processors which would defeat bit-perfect
                    val delegate = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(true)
                        .build()
                    return com.decent.usbaudio.media3.UsbAudioSink(delegate, context).also {
                        usbSink = it
                        it.pcmTap = com.decent.usbaudio.media3.UsbAudioSink.PcmTap { buf, enc, ch, sr ->
                            container.visualizer.pushPcm(buf, enc, ch, sr)
                        }
                        // native libflac decodes in c++ and never reaches handleBuffer
                        val sink = it
                        container.visualizer.monoSource = object : VisualizerController.MonoSource {
                            override fun read(out: FloatArray) = sink.readNativePcm(out)
                            override fun sampleRate() = sink.nativeEngineSampleRate
                            override fun active() = sink.nativeEngineActive
                        }
                    }
                }
                val base = DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(monoProcessor, correctionConv, auroraDsp, convolver))
                    .setEnableFloatOutput(useFloat)
                    // float bypasses sonic so use hardware playback params for speed
                    .setEnableAudioTrackPlaybackParams(useFloat || enableAudioTrackPlaybackParams)
                    .build()
                return TappingAudioSink(base, container.visualizer)
            }
        }
        // prefer ffmpeg decoder so non-flac content decodes to float32
        if (bitPerfectUsb) {
            renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        // 局域网远程音频走 SimpleCache：边播边缓存，离线时已缓存的章节照样能播。
        // 本地 file:// / content:// 不经过缓存，避免无意义地复制整个曲库。
        val dataSourceFactory = com.aurora.music.data.cast.RemoteMediaCache.dataSourceFactory(this)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)

        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // 锁屏后 CPU 进入低功耗：播放期间按住唤醒锁，避免解码/输出线程被延迟（卡顿、投屏 /state 变慢）。
            // 只在真正播放时持锁，暂停即释放（ExoPlayer 自己管理）。
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // 5 分钟提前缓存：扫描/慢介质抢 IO 时，播放不被拖住。
            // 缓的是解码前压缩样点（CD 级 FLAC 5 分钟约 30MB），内存可接受。
            .setLoadControl(audioLoadControl())
        if (bitPerfectUsb) {
            // stop exoplayer reading the file while the native flac engine handles decode + usb
            playerBuilder.setLoadControl(
                com.decent.usbaudio.media3.UsbAudioSink.wrapLoadControl(
                    audioLoadControl()
                ) { usbSink?.isNativeEngineActive == true }
            )
        }
        player = playerBuilder.build()
        if (bitPerfectUsb) usbSink?.attachToPlayer(player)

        // usb host permission isnt persisted across process restarts so re-acquire on startup else sink silently falls back to normal output
        if (bitPerfectUsb) {
            runCatching {
                val dev = com.decent.usbaudio.UsbAudioDevice.getInstance(this)
                dev.findUsbAudioDevice()?.let { d -> if (!dev.hasPermission(d)) dev.requestPermission(d) {} }
            }
        }

        // share session id so the system dsp effects created on it apply to playback
        if (container.audioSessionId != 0) {
            runCatching { player.setAudioSessionId(container.audioSessionId) }
        }

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && externalSnapshot == null) {
                    when (reason) {
                        // 其他音乐 App 抢占：双停，不自动恢复（等系统播放键双恢复）。
                        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                            noteExternalInterruption(autoResume = false, pauseMusic = false)
                        // 耳机拔出/蓝牙断开：ExoPlayer 已停音乐，这里补停书。常停不自动恢复。
                        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
                            noteExternalInterruption(autoResume = false, pauseMusic = false)
                        // 用户在 App 内亲手停音乐：书若在播则退到 BOOK 态，书不受影响。
                        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                            if (dualArmed && ebookPlaying) {
                                dualArmed = false
                                refreshArbitration()
                            }
                    }
                }
                publishMusicState(force = true)
            }

            override fun onPlaybackSuppressionReasonChanged(suppressionReason: Int) {
                when (suppressionReason) {
                    // 来电等瞬态抑制：音乐被系统压住（playWhenReady 不变，挂断自动回来），
                    // 这里只快照 + 停书；挂断后音乐自动恢复，书由下面 NONE 分支恢复。
                    // 注意：不要调 player.pause()，否则会破坏音乐的自动恢复。
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS ->
                        noteExternalInterruption(autoResume = true, pauseMusic = false)
                    Player.PLAYBACK_SUPPRESSION_REASON_NONE ->
                        if (externalSnapshot?.autoResume == true) resumeExternalSnapshot()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // App 内开音乐时书正播着 → 进入双播。
                if (isPlaying && ebookPlaying && !dualArmed) dualArmed = true
                publishMusicState(force = true)
                unifiedPlayer?.setDualMusicPlaying(isPlaying)
                refreshArbitration()
            }

            override fun onEvents(p: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
                ) updateCustomLayout()
                // 序号/随机/循环变化立即推送，音乐 UI 不等节流
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                    events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                    events.contains(Player.EVENT_REPEAT_MODE_CHANGED)
                ) publishMusicState(force = true)
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) maybeNeutralize()
                if (events.contains(Player.EVENT_TRACKS_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
                ) updateSignalPath()
                // bit-perfect: speed/pitch can't flow through exoplayer's bypassed processors so drive the
                // native usb engine's varispeed directly (reverts to true bit-perfect at 1.0x)
                if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
                ) usbSink?.setTimeStretch(p.playbackParameters.speed)
                // the bit-perfect crossfade transition landed — re-arm for the next boundary. also re-arm on
                // any timeline change so the latch can never get stuck if the advance didn't yield a transition.
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_TIMELINE_CHANGED)
                ) xfadeBpPending = false
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) updateDriveGain()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
                    events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                    events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                ) publishNowPlaying()
            }
        })

        mediaSession = MediaLibrarySession.Builder(this, player, MediaCallback())
            .setCustomLayout(buildCustomLayout())
            .build()

        val store = container.settingsStore
        audioEffects = container.audioEffects

        scope.launch {
            store.playbackPrefs.collect { prefs ->
                player.skipSilenceEnabled = prefs.skipSilence
                monoAudioPref = prefs.monoAudio
                crossfadeMs = prefs.crossfadeSec * 1000
                preferHighResPref = prefs.preferHighRes
                // independent output: drop audio focus so other apps keep playing through the speaker while
                // aurora streams to its own device. handleAudioFocus is runtime-switchable via setAudioAttributes.
                if (prefs.independentOutput != independentOutput) {
                    independentOutput = prefs.independentOutput
                    runCatching { player.setAudioAttributes(audioAttributes, !independentOutput) }
                }
                applyAudioEngine()
            }
        }
        scope.launch {
            store.audioPrefs.collect {
                replayGainMode = it.replayGain
                lastAudioPrefs = it
                applyAudioEngine()
            }
        }
        // v0.5: active correction profile → compile FIR → correction convolver
        scope.launch {
            kotlinx.coroutines.flow.combine(
                store.correctionProfiles, store.activeCorrectionId,
                store.correctionLowcutHz, store.correctionHighcutHz,
            ) { profiles, activeId, lowcut, highcut ->
                Triple(profiles.firstOrNull { it.id == activeId }, lowcut, highcut)
            }.collect { (profile, lowcut, highcut) -> applyCorrectionProfile(profile, lowcut, highcut) }
        }
        scope.launch {
            container.preferredAudioDeviceId.collect { id -> applyPreferredDevice(id) }
        }
        scope.launch {
            // 听书混音状态：播放态 + 用户设定的站内保留音量 + duck 开关
            container.ebookTts.playing.collect { playing ->
                ebookPlaying = playing
                if (playing) {
                    // App 内开书（阅读页播放键）：
                    // 外部快照说音乐本在播 → 音乐跟着回来（ebook 既定规则）；
                    // 音乐正播着 → 进入双播。之后转为用户态 dual，快照清掉。
                    val snap = externalSnapshot
                    if (!player.isPlaying && snap?.musicWasPlaying == true) {
                        runCatching { player.play() }
                    }
                    if (player.isPlaying || snap?.musicWasPlaying == true) dualArmed = true
                    externalSnapshot = null
                } else {
                    // 快照还在 = 外部/系统停的书，保持 dualArmed 等双恢复；
                    // 快照不在 = 用户亲手停书，退出双播，音乐不受影响。
                    if (externalSnapshot == null && dualArmed) dualArmed = false
                }
                refreshArbitration()
            }
        }
        scope.launch {
            // 断点缓存：stop() 会清掉控制器的 position，这里留一份给外部暂停快照/双恢复用。
            container.ebookTts.position.collect { pos ->
                if (pos != null) lastBookPara = pos
            }
        }

        // 统一会话 player：BOOK / DUAL 模式的会话 player，MUSIC 模式沿用音乐 ExoPlayer。
        val up = UnifiedPlayer(mainLooper, container)
        up.onDualSystemPause = { noteExternalInterruption(autoResume = false, pauseMusic = true) }
        up.onDualSystemResume = {
            val snap = externalSnapshot
            if (snap == null) {
                // 没有联合暂停快照：系统播放键只恢复音乐（若在播），不偷偷开始朗读。
                false
            } else {
                externalSnapshot = null
                if (snap.musicWasPlaying && !player.isPlaying) runCatching { player.play() }
                // 暂停前书确实在播 → 双恢复；只有音乐一路 → 书保持停止。
                snap.bookPara != null
            }
        }
        up.attach()
        unifiedPlayer = up
        // 阅读页底栏亲手停朗读：快照并暂停音乐，两路一起停；快照保留 dualArmed，
        // 阅读页播放键 / 系统播放键再按时双恢复。
        container.ebookTts.onUserStop = { onBookUserStop() }
        container.ebookTtsPlayer.onAudioFocusChange = { change ->
            scope.launch { handleBookFocusChange(change) }
        }
        // 耳机拔出/蓝牙断开：动态广播（静态注册无效），音乐侧 ExoPlayer 会自停，这里补停书。
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    noteExternalInterruption(autoResume = false, pauseMusic = false)
                }
            }
        }
        noisyReceiver = noisy
        runCatching {
            ContextCompat.registerReceiver(
                this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        // 后启动的站外音乐：播配置回调发现（排除本站会话），强制压低
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val cb = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                    if (!abookPlayingNow || !abookAllowMix || !abookDuckOthers) return
                    // 公开 API 拿不到 session/uid，只能按 usage 判；
                    // 自己的启停也会触发，重拿一次无害（3s 节流）
                    val other = configs.any { cfg ->
                        cfg.audioAttributes?.usage == android.media.AudioAttributes.USAGE_MEDIA
                    }
                    if (other) scope.launch { reduckOthers() }
                }
            }
            abookPlaybackCb = cb
            runCatching { am.registerAudioPlaybackCallback(cb, null) }
        }
        refreshArbitration()
        scope.launch {
            container.settingsStore.unifiedTts.collect {
                ebookMusicLevel = it.ownMusicLevel
                ebookDuckOthers = it.duckOthers
            }
        }
        scope.launch {
            // 有声书混音/步长（全局设置）+ 本书增益（VM 写 abookSession）
            container.settingsStore.audiobookPrefs.collect {
                abookAllowMix = it.allowMix
                abookDuckOthers = it.duckOthers
                abookSeekSec = it.seekSec
            }
        }
        scope.launch {
            container.abookSession.gain.collect { abookGain = it.coerceIn(0.5f, 2f) }
        }
        scope.launch {
            container.abookSession.activeBookId.collect { abookBook = it }
        }

        scope.launch {
            while (isActive) {
                // ramp finely while a fade is in flight idle replaygain tracking needs only a coarse tick
                delay(if (xfadeActive || sleepFadeActive || wakeFadeActive) 25L else 100L)
                tickAudio()
            }
        }
    }

    // v0.5: compile the active CorrectionProfile to FIR on IO, push to the
    // correction convolver. Runs off the audio thread; setImpulse swaps atomically.
    private fun applyCorrectionProfile(profile: com.aurora.music.data.CorrectionProfile?, lowcutHz: Float = 0f, highcutHz: Float = 0f) {
        val id = profile?.id ?: "flat"
        val gains = com.aurora.music.data.applyCorrectionCutoffs(
            profile?.scaledGains().orEmpty(), com.aurora.music.data.correctionFreqs(), lowcutHz, highcutHz)
        val on = profile?.enabled == true && !CorrectionCompiler.isFlat(gains) && id != "flat"
        correctionMaxGainDb = if (on) gains.maxOrNull() ?: 0f else 0f
        correctionTrimDb = if (on) profile?.preampDb ?: 0f else 0f
        correctionActive = on
        if (id == lastCorrectionId && !on) {
            correctionConv.enabled = false
            applyAudioEngine()
            return
        }
        lastCorrectionId = id
        if (!on) {
            correctionConv.enabled = false
            applyAudioEngine()
            return
        }
        // player 只能在主线程碰：采样率先在主线程取好，算完切回主线程再应用
        val rate = runCatching { player.audioFormat?.sampleRate ?: 48000 }.getOrDefault(48000).coerceAtLeast(8000)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val taps = runCatching { CorrectionCompiler.gainsToFir(gains.toFloatArray(), rate) }.getOrNull()
            if (taps != null) {
                val ir = ImpulseResponse(taps, taps, rate)
                correctionConv.setImpulse(ir, 0f)
                correctionConv.enabled = true
            } else {
                correctionConv.enabled = false
            }
            withContext(Dispatchers.Main) { applyAudioEngine() }
        }
    }

    // 5 分钟提前缓存：最小/最大都按 300s，起播 2.5s、重缓冲 5s 沿用默认
    private fun audioLoadControl(): androidx.media3.exoplayer.DefaultLoadControl =
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                300_000,
                300_000,
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()

    // runtime-switchable via volatile flags no rebuild the two eq engines are mutually exclusive so they never stack
    private fun applyAudioEngine() {
        val ap = lastAudioPrefs ?: return
        // v0.5.1: engine selector removed — the software DSP chain is always on
        // (bit-perfect USB keeps its own bypass chain).
        // 参数装配见 playback/DspChain（听书链共用同一份，保证两边效果一致）
        driveOn = isDrivingOn(ap)
        driveTargetDb = ap.dspDriveTargetDb
        val params = buildDspParams(ap, correctionMaxGainDb, correctionTrimDb, driveGainDb)
        auroraDsp.update(params)
        val dspNeutral = params.isNeutral()
        auroraDsp.enabled = !dspNeutral
        // 全链直通（dsp 中性 + 校正/卷积/单声道全关）才算不改采样，供信号链路展示用
        dspBypassed = dspNeutral && !correctionActive && !ap.dspConvEnabled && !monoAudioPref
        audioEffects?.setMasterEnabled(false)
        correctionConv.enabled = correctionActive

        convolver.enabled = ap.dspConvEnabled
        convolver.setMakeup(ap.dspConvMakeupDb)
        if (ap.dspConvIrPath != lastIrPath) {
            lastIrPath = ap.dspConvIrPath
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ir = ap.dspConvIrPath.takeIf { it.isNotBlank() }
                    ?.let { runCatching { ConvolutionProcessor.loadWav(java.io.File(it)) }.getOrNull() }
                convolver.setImpulse(ir, ap.dspConvMakeupDb)
            }
        }
        // mono runs in the dedicated pre-processor now (no engine switch anymore)
        monoProcessor.enabled = monoAudioPref
        updateSignalPath()
        updateDriveGain()
    }

    private fun applyPreferredDevice(id: Int) {
        runCatching {
            if (id == 0) {
                player.setPreferredAudioDevice(null)
            } else {
                val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                val device = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                player.setPreferredAudioDevice(device)
            }
        }
        updateSignalPath()
    }

    private fun currentOutputDevice(): android.media.AudioDeviceInfo? {
        val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return null
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        fun rank(t: Int) = when (t) {
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> 0
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 1
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
            else -> 9
        }
        return outs.minByOrNull { rank(it.type) }
    }

    private fun isUsb(t: Int) = t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
        t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun requestBitPerfect(device: android.media.AudioDeviceInfo?, on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 34 || device == null) { grantedBitPerfect = false; deviceSupportsBitPerfect = false; return }
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val supported = am.getSupportedMixerAttributes(device)
            android.util.Log.d("BitPerfect", "device=${device.productName}(type=${device.type}) supportedMixerAttrs=${supported.size} behaviors=${supported.map { it.mixerBehavior }}")
            val bp = supported.firstOrNull { it.mixerBehavior == android.media.AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            deviceSupportsBitPerfect = bp != null
            grantedBitPerfect = if (on && bp != null) {
                val ok = am.setPreferredMixerAttributes(attrs, device, bp)
                android.util.Log.d("BitPerfect", "setPreferredMixerAttributes granted=$ok")
                ok
            } else {
                if (bp != null) runCatching { am.clearPreferredMixerAttributes(attrs, device) }
                false
            }
        }.onFailure { grantedBitPerfect = false; deviceSupportsBitPerfect = false }
    }

    private fun updateSignalPath() {
        val container = (application as AuroraApplication).container
        val fmt = runCatching { player.audioFormat }.getOrNull()
        val ap = lastAudioPrefs
        val device = currentOutputDevice()
        val outName = device?.productName?.toString()?.trim()?.ifBlank { null }
            ?: when {
                device == null -> getString(R.string.common_speaker)
                isUsb(device.type) -> getString(R.string.sheet_output_usb_generic)
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> getString(R.string.sheet_output_bluetooth)
                else -> getString(R.string.sheet_output_other)
            }

        val wantBitPerfect = useFloatOut && device != null && isUsb(device.type)
        requestBitPerfect(device, wantBitPerfect)

        if (fmt == null) { container.signalPath.value = SignalPath(active = false); return }
        val codec = fmt.sampleMimeType?.substringAfter('/')?.uppercase() ?: ""
        val depth = when (fmt.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32; C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        // anything that alters samples breaks bit-perfect; fully-bypassed chain is passthrough
        val modifying = !dspBypassed
        val isBt = device != null && (
            device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER)
        // truly bit-perfect needs an exclusive mixer grant float passthrough alone may still get sample-rate-converted by the mixer bluetooth is always re-encoded
        val (bitPerfect, note) = when {
            isBt -> false to getString(R.string.signal_bt)
            modifying -> false to getString(R.string.signal_dsp)
            useFloatOut && grantedBitPerfect -> true to getString(R.string.signal_bp_exclusive)
            useFloatOut && deviceSupportsBitPerfect -> false to getString(R.string.signal_hires_denied)
            useFloatOut -> false to getString(R.string.signal_hires_float)
            preferHighResPref -> false to getString(R.string.signal_restart_hires)
            else -> false to getString(R.string.signal_mixer)
        }
        container.signalPath.value = SignalPath(
            active = true, codec = codec, sampleRateHz = fmt.sampleRate.takeIf { it > 0 } ?: 0,
            bitDepth = depth, channels = fmt.channelCount, output = outName,
            bitPerfect = bitPerfect, note = note,
        )
    }

    /**
     * 推送站内音乐 player 的真实状态（时间线序号/进度/播放态/随机/循环）。
     * 双播时系统会话 player 是 UnifiedPlayer（书的三窗占位时间线），
     * 音乐 UI 只能靠这份快照区分“我播到歌单第几首”。
     * 非强制调用按 ~150ms 节流，位置随 tick 更新；序号/播放态变化走 [force] 立即推。
     */
    private fun publishMusicState(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastMusicStatePublishMs < 150) return
        lastMusicStatePublishMs = now
        // 时间线顺序只在换队列/随机重排时变，force 推送时刷新一次缓存，tick 复用。
        if (force || musicQueueIds.size != runCatching { player.mediaItemCount }.getOrDefault(0)) {
            refreshMusicQueueIds()
        }
        val v = runCatching {
            val md = player.currentMediaItem?.mediaMetadata
            val mediaId = player.currentMediaItem?.mediaId.orEmpty()
            val playingNow = player.isPlaying ||
                (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
            // 单次 0 不推（seek/缓冲瞬态）；连续 0 超过防抖窗口才真正落 0。
            val settled = settlePosition(
                mediaId = mediaId,
                positionMs = player.currentPosition,
                playing = playingNow,
                previous = lastMusicPosition,
                nowMs = now,
            )
            lastMusicPosition = settled
            MusicPlaybackState(
                mediaId = mediaId,
                queueIds = musicQueueIds,
                title = md?.title?.toString().orEmpty(),
                artist = md?.artist?.toString().orEmpty(),
                artUri = md?.artworkUri?.toString().orEmpty(),
                index = if (player.mediaItemCount > 0) player.currentMediaItemIndex.coerceAtLeast(0) else 0,
                positionMs = settled?.positionMs ?: 0L,
                playing = playingNow,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            )
        }.getOrDefault(MusicPlaybackState())
        if (container.musicState.value != v) container.musicState.value = v
    }

    /** 缓存音乐 player 时间线的 mediaId 顺序（含随机后的真实顺序）。 */
    private fun refreshMusicQueueIds() {
        musicQueueIds = runCatching {
            List(player.mediaItemCount) { i -> player.getMediaItemAt(i).mediaId }
        }.getOrDefault(emptyList())
    }

    private fun tickAudio() {
        // 站内音乐真实状态（音乐选项卡/气泡的序号、进度、播放暂停都用它；双播时会话 player 是书）
        publishMusicState()
        // v0.6 GR meters for the Dynamics UI (cheap volatile reads)
        container.dspMeters.value = com.aurora.music.data.DspMeters(auroraDsp.compGrDb, auroraDsp.limGrDb)
        abookActive = player.currentMediaItem?.mediaId?.startsWith(
            com.aurora.music.data.audiobook.ABOOK_ID_PREFIX,
        ) == true
        abookPlayingNow = abookActive && player.isPlaying
        syncEbookDuck()
        if (sleepFadeActive) { driveSleepFade(); return }
        if (wakeFadeActive) { driveWakeFade(); return }
        if (xfadeActive) {
            // cancel if user navigated away from the track we crossfaded into
            if (player.currentMediaItem?.mediaId != xfadeExpectedId) { endXfade() } else { driveXfade() }
            return
        }
        val base = volBase()
        if (kotlin.math.abs(player.volume - base) > 0.01f) player.volume = base
        maybeBeginXfade()
    }

    /** 音量基数：replaygain × 听书压低 × 有声书本书增益；网页投屏时本地静音（声音由浏览器出）。 */
    private fun volBase(): Float = replayGainMultiplier() * ebookDuckApplied * abookGainApplied *
        if (container.castMute.value) 0f else 1f

    /**
     * 统一会话仲裁：单服务单会话，按模式换会话 player。
     * - DUAL/BOOK → [UnifiedPlayer]（通知栏显示书的 heading/书名，双播也显示书）；
     * - MUSIC → 音乐 ExoPlayer（与原来完全一致）。
     * - 两路都停且非 DUAL 时粘滞：不切 player（暂停什么留什么），起播时再按播放态
     *   强制切换（App 内另起一路可抢占）。避免书暂停瞬间翻成旧歌。
     * 只在模式或 player 变化时切换，控制器不断连。
     */
    private fun refreshArbitration() {
        val session = mediaSession ?: return
        val up = unifiedPlayer ?: return
        val musicPlaying = player.isPlaying
        val bookPlaying = ebookPlaying
        val current = runCatching { session.player }.getOrNull()
        if (!musicPlaying && !bookPlaying && !dualArmed) {
            up.dualControl = null
            up.setDualMusicPlaying(false)
            if (current === up) {
                runCatching { session.setCustomLayout(emptyList()) }
            } else if (current === player) {
                updateCustomLayout()
            } else {
                runCatching { session.setPlayer(player) }
                updateCustomLayout()
            }
            return
        }
        val mode = computeUnifiedMode(musicPlaying, bookPlaying, dualArmed)
        up.dualControl = if (mode == UnifiedSessionMode.DUAL) dualControl else null
        up.setDualMusicPlaying(player.isPlaying)
        val wantUnified = mode != UnifiedSessionMode.MUSIC
        if (wantUnified && current !== up) {
            runCatching { session.setPlayer(up) }
            runCatching { session.setCustomLayout(emptyList()) }
        } else if (!wantUnified && current !== player) {
            runCatching { session.setPlayer(player) }
            updateCustomLayout()
        }
    }

    /**
     * 外部打断双停：快照先行（调用方必须保证快照在停之前建好，
     * 服务侧才能区分“用户亲手单停”和“外部/系统双停”）。
     *
     * @param autoResume true=瞬态（来电），挂断自动双恢复；false=常停，等系统/用户播放键。
     * @param pauseMusic 是否顺手停音乐（音乐侧已被系统压住/停掉时传 false，避免破坏自动恢复）。
     */
    private fun noteExternalInterruption(autoResume: Boolean, pauseMusic: Boolean) {
        if (!player.isPlaying && !ebookPlaying) return
        if (externalSnapshot == null) {
            externalSnapshot = ExternalSnapshot(
                musicWasPlaying = player.isPlaying,
                // 书没在播时不断点快照：lastBookPara 可能是上次读完的旧书，避免挂断后误续播。
                bookPara = if (ebookPlaying) lastBookPara else null,
                autoResume = autoResume,
            )
        }
        if (pauseMusic) runCatching { player.pause() }
        if (ebookPlaying) runCatching { container.ebookTts.stop() }
    }

    /**
     * 阅读页底栏「停止朗读」（用户亲手停）：音乐一起暂停。
     * 只在音乐真正在播时建快照：快照让 playing collector 保留 dualArmed，
     * 之后阅读页播放键（collector）或系统播放键（UnifiedPlayer）能把两路一起恢复。
     */
    private fun onBookUserStop() {
        if (externalSnapshot != null || !player.isPlaying) return
        externalSnapshot = ExternalSnapshot(
            musicWasPlaying = true,
            bookPara = lastBookPara,
            autoResume = false,
        )
        runCatching { player.pause() }
    }

    /**
     * 音乐选项卡 / 阅读页音乐气泡的播放暂停：永远只动站内音乐，不开始也不停止朗读。
     * - 听书时从音乐选项卡暂停 → 朗读继续；
     * - 两路一起暂停后从音乐选项卡播放 → 只恢复音乐，朗读保持停止；
     * - 书没在播时恢复音乐 → 退出双播态，会话切回音乐 player（列表/封面回到歌）。
     */
    private fun toggleMusicFromApp() {
        // 与 UI 同口径：缓冲中且 playWhenReady 也算“在播”，这一下应是暂停。
        val playing = player.isPlaying ||
            (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        if (playing) {
            runCatching { player.pause() }
        } else {
            runCatching { player.play() }
            if (!ebookPlaying) {
                // 只恢复了音乐：释放联合暂停快照，书等阅读页/系统播放键再续。
                externalSnapshot = null
                dualArmed = false
            }
        }
        musicSoloIfBookIdle()
        refreshArbitration()
    }

    /**
     * App 要从控制器下发音乐队列（点歌/开有声书）前的接管：
     * 停书后会话按“粘滞”策略还停在 [UnifiedPlayer]（书的占位时间线），
     * 此时 setMediaItems 会落到书上、随后的 play 还会被当成“继续朗读”而开口读书。
     * 先把会话切回音乐 player，命令随之落到真正的音乐 ExoPlayer；
     * 若书在播（双播），保留 dualArmed，起播后 onIsPlayingChanged 会仲裁回 UnifiedPlayer。
     */
    private fun takeSessionForMusic() {
        val session = mediaSession ?: return
        if (!container.ebookTts.playing.value) {
            // 书没在播：音乐独占语义，退出双播态并释放联合暂停快照（按播放键不再带起朗读）。
            dualArmed = false
            externalSnapshot = null
        }
        if (runCatching { session.player }.getOrNull() === player) return
        runCatching { session.setPlayer(player) }
        updateCustomLayout()
    }

    /** 书没在播时的音乐操作：会话回音乐 player（书在播就保持双播，交给仲裁）。 */
    private fun musicSoloIfBookIdle() {
        if (container.ebookTts.playing.value) return
        takeSessionForMusic()
    }

    // ---- 音乐 UI（选项卡/阅读页音乐气泡）的走带键：永远操作站内音乐 player ----
    // 双播时会话 player 是 UnifiedPlayer（书），直接下发 seek/next 会被路由到朗读上。

    private fun musicJumpTo(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        runCatching {
            player.seekTo(index, 0)
            player.play()
        }
        musicSoloIfBookIdle()
        publishMusicState(force = true)
    }

    private fun musicNext() {
        runCatching { player.seekToNextMediaItem() }
        musicSoloIfBookIdle()
        publishMusicState(force = true)
    }

    private fun musicPrevious() {
        runCatching {
            if (player.currentPosition > 4000) player.seekTo(0) else player.seekToPreviousMediaItem()
        }
        musicSoloIfBookIdle()
        publishMusicState(force = true)
    }

    private fun musicSeekTo(fraction: Float) {
        val dur = player.duration
        if (fraction < 0f || dur <= 0) return
        runCatching { player.seekTo((fraction.coerceIn(0f, 1f) * dur).toLong()) }
        musicSoloIfBookIdle()
        publishMusicState(force = true)
    }

    /** 外部瞬态结束（来电挂断）：按快照双恢复，一次性。 */
    private fun resumeExternalSnapshot() {
        val snap = externalSnapshot ?: return
        externalSnapshot = null
        if (snap.musicWasPlaying && !player.isPlaying) runCatching { player.play() }
        val para = snap.bookPara
        if (para != null && !ebookPlaying) {
            runCatching { container.ebookTts.playFrom(para.chapter, para.block, para.startChar) }
        }
        // 快照里两路都在播 → 回到双播；否则按实际播的状态自然落位。
        if (snap.musicWasPlaying && para != null) dualArmed = true
        refreshArbitration()
    }

    /**
     * 书的 duck 焦点变化（书单播 + duckOthers 开时书持有 MAY_DUCK 焦点）：
     * 来电抢占会先到这里（音乐侧无焦点，不会触发上面的抑制回调），同样双停/挂断双恢复。
     * 书没在播时直接忽略，避免误停音乐。
     */
    private fun handleBookFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (ebookPlaying) noteExternalInterruption(autoResume = true, pauseMusic = true)
            AudioManager.AUDIOFOCUS_LOSS ->
                if (ebookPlaying) noteExternalInterruption(autoResume = false, pauseMusic = true)
            AudioManager.AUDIOFOCUS_GAIN ->
                if (externalSnapshot?.autoResume == true) resumeExternalSnapshot()
        }
    }

    /**
     * 听书/有声书混音同步（每 tick 跑一次，100ms 常态 / 25ms 淡入淡出中）：
     * - 音量斜坡：站内音乐向 用户设定的保留音量 / 1.0 平滑过渡；
     * - 有声书增益：向本书增益（0.5..2.0）平滑过渡；
     * - 焦点让出：听书播着且 duck 开关开时，或有声书播着且共存开时，音乐主动丢焦点，
     *   避免 Media3 自动 duck（固定 0.2）与手动比例叠加导致过 quiet；
     *   停后恢复焦点持有。independentOutput 下本来就无焦点，跳过切换。
     * - 有声书压低：共存 + 压低都开时持 MAY_DUCK 焦点，把站外压低。
     */
    private fun syncEbookDuck() {
        val target = if (ebookPlaying) ebookMusicLevel.coerceIn(0.05f, 1f) else 1f
        val cur = ebookDuckApplied
        if (cur != target) {
            val step = 0.08f
            ebookDuckApplied =
                if (cur < target) (cur + step).coerceAtMost(target)
                else (cur - step).coerceAtLeast(target)
        }
        val gainTarget = if (abookActive) abookGain else 1f
        if (abookGainApplied != gainTarget) {
            val step = 0.1f
            abookGainApplied =
                if (abookGainApplied < gainTarget) (abookGainApplied + step).coerceAtMost(gainTarget)
                else (abookGainApplied - step).coerceAtLeast(gainTarget)
        }
        if (!independentOutput && !bitPerfect) {
            // 独占丢焦点：TTS 听书播着，或有声书已装载（开播前即丢，否则起播瞬间
            // GAIN 抢占会先暂停站外；暂停时不持有也不打断别人）。
            // 注意 MAY_DUCK 仍只在真正在播时持有（见 updateAbookDuckFocus）。
            val wantDrop = (ebookPlaying && ebookDuckOthers) ||
                ((abookActive || abookBook != null) && abookAllowMix)
            if (wantDrop != ebookFocusDropped) {
                ebookFocusDropped = wantDrop
                val attrs = musicAudioAttrs ?: return
                runCatching { player.setAudioAttributes(attrs, !wantDrop) }
            }
        }
        updateAbookDuckFocus()
    }

    /**
     * 有声书 MAY_DUCK 焦点：书正在播 + 共存 + 压低都开时持有，否则归还。
     * 只看 playing 不看队列：暂停时按开关也不压别人。
     */
    private fun updateAbookDuckFocus() {
        if (abookPlayingNow && abookAllowMix && abookDuckOthers) requestAbookDuckFocus()
        else abandonAbookDuckFocus()
    }

    /**
     * 有声书自己的焦点变化（共存语义，与 TTS 听书的 handleBookFocusChange 分开）：
     * 被后来者抢占不断书；若仍在播且需压低，重拿一次把对方压下去。
     */
    private fun handleAbookFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                abookDuckHeld = false
                // tick 会在 100ms 内按现态重拿；这里只清标记不断书
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AudioManager.AUDIOFOCUS_GAIN,
                -> Unit
        }
    }

    /**
     * 后启动的站外媒体：框架不会自动把它压低（后来者拿 gain，我方收 LOSS），
     * 靠播放配置回调发现它，强制重走一次 duck 把它压下去（3s 节流）。
     */
    private fun reduckOthers() {
        if (!abookPlayingNow || !abookAllowMix || !abookDuckOthers) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReduckMs < 3000) return
        lastReduckMs = now
        abandonAbookDuckFocus()
        requestAbookDuckFocus()
    }

    private fun requestAbookDuckFocus() {
        if (abookDuckHeld) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val res = if (android.os.Build.VERSION.SDK_INT >= 26) {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(abookFocusListener)
                .build()
            abookDuckRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                abookFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
        abookDuckHeld = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAbookDuckFocus() {
        if (!abookDuckHeld && abookDuckRequest == null) return
        abookDuckHeld = false
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            abookDuckRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(abookFocusListener) }
        }
        abookDuckRequest = null
    }

    /** 有声书态系统上一首/下一首 = ±步长位移（钳制本章内，不跨章）。 */
    private fun abookSeekBy(deltaSec: Int) {
        if (!abookActive) return
        val dur = player.duration.takeIf { it > 0 } ?: return
        val pos = (player.currentPosition + deltaSec * 1000L).coerceIn(0L, dur)
        player.seekTo(pos)
        // 暂停态下 VM ticker 不落盘，这里经 abookSession 直写不断位
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val bookId = mediaId.removePrefix(com.aurora.music.data.audiobook.ABOOK_ID_PREFIX)
            .substringBeforeLast('#', "").ifBlank { return }
        val path = container.abookSession.currentPath.value.ifBlank { return }
        scope.launch(Dispatchers.IO) {
            runCatching { container.audiobookStore.savePos(bookId, path, pos) }
        }
    }

    private fun isAbookMedia(mediaId: String?): Boolean =
        mediaId?.startsWith(com.aurora.music.data.audiobook.ABOOK_ID_PREFIX) == true

    private fun driveSleepFade() {
        val ms = sleepFadeMs.coerceAtLeast(1)
        val t = ((android.os.SystemClock.elapsedRealtime() - sleepFadeStartMs).toFloat() / ms).coerceIn(0f, 1f)
        player.volume = ((1f - t) * volBase()).coerceIn(0f, 1f)
        if (t >= 1f) {
            sleepFadeActive = false
            player.pause()
            player.volume = volBase()
        }
    }

    private fun driveWakeFade() {
        val ms = wakeFadeMs.coerceAtLeast(1)
        val t = ((android.os.SystemClock.elapsedRealtime() - wakeFadeStartMs).toFloat() / ms).coerceIn(0f, 1f)
        player.volume = (t * volBase()).coerceIn(0f, 1f)
        if (t >= 1f) { wakeFadeActive = false; player.volume = volBase() }
    }

    private fun maybeBeginXfade() {
        val fade = crossfadeMs
        if (fade <= 0 || !player.isPlaying) return
        val repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE
        if (bitPerfect) {
            // bit-perfect: the 2nd fadePlayer would come out the speaker, so instead the native USB engine
            // mixes the outgoing track's tail into the incoming one. Only real transitions are supported.
            if (xfadeBpPending || repeatOne || !player.hasNextMediaItem()) return
            val duration = player.duration
            if (duration == C.TIME_UNSET) return
            val remaining = duration - player.currentPosition
            if (remaining in 0..fade.toLong()) beginXfadeBitPerfect()
            return
        }
        // repeat-one navigation acts like repeat-off so loop back to position 0 of the same item to crossfade
        if (!repeatOne && !player.hasNextMediaItem()) return
        val duration = player.duration
        if (duration == C.TIME_UNSET) return
        val remaining = duration - player.currentPosition
        if (remaining in 0..fade.toLong()) beginXfade(repeatOne)
    }

    // bit-perfect crossfade: capture the outgoing track, advance to the incoming one normally (so its
    // position tracking stays sane), and hand the outgoing file to the native engine to fade out + mix.
    private fun beginXfadeBitPerfect() {
        val outgoing = player.currentMediaItem ?: return
        val uri = outgoing.localConfiguration?.uri ?: return
        val posUs = player.currentPosition * 1000L
        // capture the incoming uri up front (before advancing) so the sink can verify the tail still
        // belongs to the right track, and so the pending tail is set before the incoming engine is built
        val nextIdx = player.nextMediaItemIndex
        val incomingUri = if (nextIdx != C.INDEX_UNSET) player.getMediaItemAt(nextIdx).localConfiguration?.uri else null
        usbSink?.setPendingTail(uri, incomingUri, posUs, crossfadeMs.toLong())
        player.seekToNextMediaItem()
        xfadeBpPending = true   // suppress re-fire until the transition lands (cleared in the listener)
    }

    private fun beginXfade(repeatOne: Boolean) {
        val outgoing = player.currentMediaItem ?: return
        val uri = outgoing.localConfiguration?.uri ?: return
        val pos = player.currentPosition
        val tail = ensureFadePlayer()
        runCatching {
            tail.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            tail.prepare()
            tail.seekTo(pos)
            tail.volume = player.volume.coerceIn(0f, 1f)
            tail.playWhenReady = true
        }
        xfadeOutGain = player.volume.coerceIn(0f, 1f)
        if (repeatOne) player.seekTo(0) else player.seekToNextMediaItem()
        xfadeInGain = replayGainMultiplier()
        player.volume = 0f
        xfadeExpectedId = player.currentMediaItem?.mediaId
        xfadeStartMs = android.os.SystemClock.elapsedRealtime()
        xfadeActive = true
    }

    private fun driveXfade() {
        val fade = crossfadeMs.coerceAtLeast(1)
        val t = ((android.os.SystemClock.elapsedRealtime() - xfadeStartMs).toFloat() / fade).coerceIn(0f, 1f)
        // equal-power curves avoid the ~3db dip two linear ramps produce
        val half = Math.PI.toFloat() / 2f
        val inG = kotlin.math.sin(t * half)
        val outG = kotlin.math.cos(t * half)
        player.volume = (inG * xfadeInGain).coerceIn(0f, 1f)
        fadePlayer?.volume = (outG * xfadeOutGain).coerceIn(0f, 1f)
        if (t >= 1f) endXfade()
    }

    private fun endXfade() {
        xfadeActive = false
        xfadeExpectedId = null
        runCatching { fadePlayer?.run { pause(); clearMediaItems() } }
        player.volume = volBase()
    }

    private fun ensureFadePlayer(): ExoPlayer {
        fadePlayer?.let { return it }
        val attrs = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
        return ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            // 交叉淡入淡出也会出声：锁屏时同样要保住音频线程
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().also { fadePlayer = it }
    }

    private fun replayGainMultiplier(): Float {
        // v0.6: driving loudness owns gain in DSP (and may boost) — never stack volume RG on top
        if (driveOn || replayGainMode == 0) return 1f
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return 1f
        val gainDb = if (replayGainMode == 2) extras.getFloat("rgAlbum", Float.NaN) else extras.getFloat("rgTrack", Float.NaN)
        if (gainDb.isNaN() || gainDb == 0f) return 1f
        // attenuate-only to avoid inter-sample clipping when boosting quiet tracks
        return Math.pow(10.0, gainDb / 20.0).toFloat().coerceIn(0.1f, 1f)
    }

    // v0.6: per-track driving make-up = target LUFS − track LUFS (plan §34)
    private fun updateDriveGain() {
        if (!driveOn) {
            if (driveGainDb != 0f) { driveGainDb = 0f; applyAudioEngine() }
            return
        }
        val lufs = player.currentMediaItem?.mediaMetadata?.extras?.getFloat("lufs", Float.NaN)
            ?.takeIf { !it.isNaN() && it < 0f && it > -70f }
        val want = if (lufs == null) 0f else (driveTargetDb - lufs).coerceIn(-12f, 12f)
        if (want != driveGainDb) { driveGainDb = want; applyAudioEngine() }
    }

    private inner class MediaCallback : MediaLibrarySession.Callback {
        /**
         * 有声书态：系统上一首/下一首（通知栏、Auto、系统播放键经 controller 下发）
         * 改为 ±步长位移，不再切章节；音乐态原样放行。
         * media3 1.5 的 onPlayerCommandRequest 返回解析后的命令：执行完位移后
         * 回 COMMAND_INVALID 吞掉原命令，避免再切一次章节。
         */
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (isAbookMedia(player.currentMediaItem?.mediaId)) {
                when (playerCommand) {
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                        abookSeekBy(-abookSeekSec)
                        return Player.COMMAND_INVALID
                    }
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                        abookSeekBy(abookSeekSec)
                        return Player.COMMAND_INVALID
                    }
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        /**
         * 有声书态：耳机/线控上一首/下一首键同样改为 ±步长位移（只消费 ACTION_DOWN 防双跳）。
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val event = if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? android.view.KeyEvent
            }
            if (event?.action == android.view.KeyEvent.ACTION_DOWN &&
                isAbookMedia(player.currentMediaItem?.mediaId)
            ) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        abookSeekBy(-abookSeekSec)
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        abookSeekBy(abookSeekSec)
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // must keep library commands or android auto browser connection is refused
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP_FADE, Bundle.EMPTY))
                .add(SessionCommand(CMD_MUSIC_TOGGLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_MUSIC_PAUSE, Bundle.EMPTY))
                .add(SessionCommand(CMD_MUSIC_PLAY, Bundle.EMPTY))
                .add(SessionCommand(CMD_MUSIC_SOLO, Bundle.EMPTY))
                .add(SessionCommand(CMD_MUSIC_JUMP, Bundle.EMPTY))
                .add(SessionCommand(CMD_MUSIC_NEXT, Bundle.EMPTY))
                .add(SessionCommand(CMD_MUSIC_PREV, Bundle.EMPTY))
                .add(SessionCommand(CMD_MUSIC_SEEK, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {            when (customCommand.customAction) {
                // target 1=on 0=off -1=toggle order = pre-shuffle order when caller already shuffled
                CMD_SHUFFLE -> setShuffle(
                    customCommand.customExtras.getInt("target", -1),
                    customCommand.customExtras.getStringArrayList("order"),
                )
                CMD_REPEAT -> cycleRepeat()
                CMD_MUSIC_TOGGLE -> toggleMusicFromApp()
                CMD_MUSIC_PAUSE -> {
                    runCatching { player.pause() }
                    publishMusicState(force = true)
                }
                CMD_MUSIC_PLAY -> {
                    runCatching { player.play() }
                    musicSoloIfBookIdle()
                    publishMusicState(force = true)
                }
                CMD_MUSIC_SOLO -> takeSessionForMusic()
                CMD_MUSIC_JUMP ->
                    musicJumpTo(customCommand.customExtras.getInt("index", -1))
                CMD_MUSIC_NEXT -> musicNext()
                CMD_MUSIC_PREV -> musicPrevious()
                CMD_MUSIC_SEEK ->
                    musicSeekTo(customCommand.customExtras.getFloat("fraction", -1f))
                CMD_SLEEP_FADE -> {
                    val ms = customCommand.customExtras.getInt("fadeMs", 0)
                    if (ms > 0) { sleepFadeMs = ms; sleepFadeStartMs = android.os.SystemClock.elapsedRealtime(); sleepFadeActive = true }
                    else { sleepFadeActive = false; player.volume = volBase() }
                }
                // 有声书开播前：共存则先丢独占焦点 + 按开关持 duck，
                // 否则起播瞬间的 GAIN 会先把站外暂停（tick 里补丢已经晚了）
                CMD_ABOOK_START -> {
                    if (abookAllowMix) {
                        val attrs = musicAudioAttrs
                        if (attrs != null) {
                            ebookFocusDropped = true
                            runCatching { player.setAudioAttributes(attrs, false) }
                        }
                        if (abookDuckOthers) requestAbookDuckFocus()
                        else abandonAbookDuckFocus()
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = browseItem(LIBRARY_ROOT, "Aurora", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, styleExtras(styleList, styleList))
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceFuture {
            LibraryResult.ofItemList(browseChildren(parentId), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceFuture {
            val item = browseCache[mediaId] ?: resolvePlayable(mediaId)
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceFuture {
            mediaItems.map { item ->
                if (item.localConfiguration != null) item else resolvePlayable(item.mediaId) ?: item
            }.toMutableList()
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = serviceFuture {
            val items = runCatching { searchItems(query) }.getOrDefault(emptyList())
            searchCache[query] = items
            session.notifySearchResultChanged(browser, query, items.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceFuture {
            val items = searchCache[query] ?: runCatching { searchItems(query) }.getOrDefault(emptyList()).also { searchCache[query] = it }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }
    }

    private suspend fun searchItems(query: String): List<MediaItem> {
        if (query.isBlank()) return emptyList()
        val r = container.repository.search(query)
        val songs = r.songs.map { songItem(it) }
        val albums = r.albums.map { collectionItem("alb_${it.id}", it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM) }
        val artists = r.artists.map { collectionItem("art_${it.id}", it.name, "Artist", it.imageUrl, MediaMetadata.MEDIA_TYPE_ARTIST) }
        return songs + albums + artists
    }

    private fun <T> serviceFuture(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { runCatching { f.set(block()) }.onFailure { f.setException(it) } }
        return f
    }

    private suspend fun browseChildren(parentId: String): ImmutableList<MediaItem> {
        val repo = container.repository
        val items: List<MediaItem> = runCatching {
            when {
                parentId == LIBRARY_ROOT -> listOf(
                    browseItem("cat_liked", "Liked Songs", MediaMetadata.MEDIA_TYPE_PLAYLIST, styleExtras(styleList, styleList)),
                    browseItem("cat_playlists", "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_albums", "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_artists", "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, styleExtras(styleGrid, styleList)),
                    browseItem("cat_downloads", "Downloads", MediaMetadata.MEDIA_TYPE_PLAYLIST, styleExtras(styleList, styleList)),
                )
                parentId == "cat_liked" -> repo.starredSongs().map { songItem(it) }
                parentId == "cat_downloads" -> repo.downloadedSongs().map { songItem(it) }
                parentId == "cat_playlists" -> repo.allPlaylists().map { collectionItem("pl_${it.id}", it.title, it.subtitle, it.coverUrl, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
                parentId == "cat_albums" -> repo.allAlbums().map { collectionItem("alb_${it.id}", it.title, it.artist, it.artworkUrl, MediaMetadata.MEDIA_TYPE_ALBUM) }
                parentId == "cat_artists" -> repo.allArtists().map { collectionItem("art_${it.id}", it.name, "Artist", it.imageUrl, MediaMetadata.MEDIA_TYPE_ARTIST) }
                parentId.startsWith("alb_") -> repo.detail("album", parentId.removePrefix("alb_"))?.tracks?.map { songItem(it) }.orEmpty()
                parentId.startsWith("pl_") -> repo.detail("playlist", parentId.removePrefix("pl_"))?.tracks?.map { songItem(it) }.orEmpty()
                parentId.startsWith("art_") -> repo.detail("artist", parentId.removePrefix("art_"))?.tracks?.map { songItem(it) }.orEmpty()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        return ImmutableList.copyOf(items)
    }

    private suspend fun resolvePlayable(mediaId: String): MediaItem? {
        browseCache[mediaId]?.let { return it }
        val id = mediaId.removePrefix("song_")
        return runCatching { container.repository.songFor(id)?.let { songItem(it) } }.getOrNull()
    }

    private val styleGrid get() = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    private val styleList get() = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
    private fun styleExtras(browsable: Int, playable: Int) = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, browsable)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, playable)
    }

    private fun browseItem(id: String, title: String, mediaType: Int, childExtras: Bundle? = null): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(mediaType)
                .apply { if (childExtras != null) setExtras(childExtras) }.build()
        ).build()

    private fun collectionItem(id: String, title: String, subtitle: String, art: String, mediaType: Int): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtist(subtitle)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(mediaType)
                .setExtras(styleExtras(styleList, styleList))
                .apply { if (art.isNotBlank()) setArtworkUri(android.net.Uri.parse(art)) }.build()
        ).build().also { browseCache[id] = it }

    private fun songItem(song: com.aurora.music.model.Song): MediaItem {
        val id = "song_${song.id}"
        // 与前台一致：优先已缓存的本文件内嵌图，避免通知栏长期挂着专辑里别的图
        val art = runCatching { com.aurora.music.data.TrackArtworkCache.cachedSync(this, song) }
            .getOrNull().orEmpty().ifBlank { song.artworkUrl }
        val item = MediaItem.Builder().setMediaId(id).setUri(song.streamUrl).setMediaMetadata(
            MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (art.isNotBlank()) setArtworkUri(android.net.Uri.parse(art)) }.build()
        ).build()
        browseCache[id] = item
        return item
    }

    private fun buildCustomLayout(): List<CommandButton> {
        val shuffleBtn = CommandButton.Builder(
            if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF
        )
            .setDisplayName(getString(R.string.player_shuffle))
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .build()
        val repeatIcon = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_OFF
        }
        val repeatBtn = CommandButton.Builder(repeatIcon)
            .setDisplayName(getString(R.string.player_repeat))
            .setSessionCommand(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
            .build()
        return listOf(shuffleBtn, repeatBtn)
    }

    private fun updateCustomLayout() {
        runCatching { mediaSession?.setCustomLayout(buildCustomLayout()) }
    }

    private fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // physical shuffle reorders the actual items shuffleModeEnabled is just a ui flag with an identity ShuffleOrder so playback follows our order not a second random one
    private fun setShuffle(target: Int, providedOrder: List<String>?) {
        val enable = when (target) {
            1 -> true
            0 -> false
            else -> !player.shuffleModeEnabled
        }
        // a provided order is a fresh shuffle-play always reapply otherwise skip no-ops
        if (providedOrder == null && enable == player.shuffleModeEnabled && enable == (originalOrder != null)) return

        if (enable && providedOrder != null) {
            // caller already shuffled just remember the real order for restore
            originalOrder = providedOrder
            player.shuffleModeEnabled = true
            pendingNeutralize = true
            maybeNeutralize()
            return
        }
        if (enable) {
            // keep the current track shuffle everything after it
            if (player.mediaItemCount <= 1) {
                player.shuffleModeEnabled = true
                originalOrder = currentIds()
                return
            }
            val ids = currentIds()
            originalOrder = ids
            val curId = player.currentMediaItem?.mediaId
            val rest = ids.filter { it != curId }.shuffled()
            val desired = (if (curId != null) listOf(curId) else emptyList()) + rest
            applyOrder(desired)
            player.shuffleModeEnabled = true
            pendingNeutralize = true
            maybeNeutralize()
        } else {
            val orig = originalOrder
            if (orig != null) {
                val present = currentIds()
                // restore snapshot order keep newly-added items at the end
                val restored = orig.filter { it in present } + present.filter { it !in orig }
                applyOrder(restored)
            }
            player.shuffleModeEnabled = false
            originalOrder = null
        }
    }

    private fun maybeNeutralize() {
        if (!pendingNeutralize) return
        val count = player.mediaItemCount
        if (count <= 0) return
        runCatching { player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(count)) }
        pendingNeutralize = false
    }

    private fun currentIds(): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    // reorder in place via moves so playback isnt interrupted
    private fun applyOrder(target: List<String>) {
        for (i in target.indices) {
            if (i >= player.mediaItemCount) break
            val want = target[i]
            var cur = -1
            var j = i
            while (j < player.mediaItemCount) {
                if (player.getMediaItemAt(j).mediaId == want) { cur = j; break }
                j++
            }
            if (cur in (i + 1) until player.mediaItemCount) player.moveMediaItem(cur, i)
        }
    }

    private fun publishNowPlaying() {
        val active = mediaSession?.player ?: player
        val item = active.currentMediaItem
        val md = item?.mediaMetadata
        val np = NowPlaying(
            title = md?.title?.toString().orEmpty(),
            artist = md?.artist?.toString().orEmpty(),
            artUri = md?.artworkUri?.toString().orEmpty(),
            isPlaying = active.isPlaying,
            hasTrack = item != null,
        )
        nowPlaying.save(np)
        NowPlayingBus.state.value = np
        WidgetBridge.refresh(this)
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BOOK_START -> startPlaceholderForeground()
            // 小部件/磁贴/通知直发 intent：双播时同样统一路由（暂停双停、上/下曲只动书），
            // App 内按钮直接调控制器，不走这里，独立性不变。
            ACTION_PLAY_PAUSE -> {
                wakeFadeActive = false
                val up = unifiedPlayer
                val sessionPlayer = runCatching { mediaSession?.player }.getOrNull()
                if (dualArmed && up != null) {
                    if (player.isPlaying || ebookPlaying) {
                        noteExternalInterruption(autoResume = false, pauseMusic = true)
                    } else {
                        val snap = externalSnapshot
                        externalSnapshot = null
                        if (snap?.musicWasPlaying == true) runCatching { player.play() }
                        val para = snap?.bookPara ?: up.peekPara() ?: lastBookPara
                        if (para != null) runCatching { container.ebookTts.playFrom(para.chapter, para.block, para.startChar) }
                        if (snap?.musicWasPlaying == true && para != null) dualArmed = true
                        refreshArbitration()
                    }
                } else if (up != null && resolveSinglePlayTarget(
                        musicPlaying = player.isPlaying,
                        bookPlaying = ebookPlaying,
                        sessionIsUnified = sessionPlayer === up,
                        hasBookmark = up.hasBookmark(),
                    ) == SinglePlayTarget.BOOK
                ) {
                    // 书单路（播或暂停）：只动书，音乐不受影响，暂停什么恢复什么。
                    if (ebookPlaying) {
                        runCatching { container.ebookTts.stop() }
                    } else {
                        val snap = externalSnapshot
                        externalSnapshot = null
                        val para = snap?.bookPara ?: up.peekPara() ?: lastBookPara
                        if (para != null) runCatching { container.ebookTts.playFrom(para.chapter, para.block, para.startChar) }
                    }
                } else {
                    if (player.isPlaying) player.pause() else player.play()
                }
            }
            ACTION_NEXT -> {
                val up = unifiedPlayer
                val sessionPlayer = runCatching { mediaSession?.player }.getOrNull()
                val bookActive = ebookPlaying || (up != null && sessionPlayer === up && up.hasBookmark())
                if (routeSystemNav(dualArmed, player.isPlaying, bookActive) == SystemNavTarget.BOOK) {
                    if (dualControl.switchMusicFromSystem(+1) != true) container.ebookTts.next()
                } else player.seekToNextMediaItem()
            }
            ACTION_PREV -> {
                val up = unifiedPlayer
                val sessionPlayer = runCatching { mediaSession?.player }.getOrNull()
                val bookActive = ebookPlaying || (up != null && sessionPlayer === up && up.hasBookmark())
                if (routeSystemNav(dualArmed, player.isPlaying, bookActive) == SystemNavTarget.BOOK) {
                    if (dualControl.switchMusicFromSystem(-1) != true) container.ebookTts.prev()
                } else if (player.currentPosition > 4000) player.seekTo(0) else player.seekToPreviousMediaItem()
            }
            ACTION_ALARM -> startAlarmPlayback()
            ACTION_ALARM_DISMISS -> dismissAlarm()
            // 睡眠定时到点：音乐走淡出通道（渐弱后停），听书由 EbookTtsController 读完当前再停，不在这里动。
            ACTION_SLEEP_PAUSE -> {
                if (runCatching { ::player.isInitialized && player.isPlaying }.getOrDefault(false)) {
                    sleepFadeMs = 6_000
                    sleepFadeStartMs = android.os.SystemClock.elapsedRealtime()
                    sleepFadeActive = true
                }
                val musicActive = runCatching { ::player.isInitialized && player.isPlaying }.getOrDefault(false)
                val bookActive = ebookPlaying ||
                    runCatching { container.ebookTts.playing.value }.getOrDefault(false)
                if (!musicActive && !bookActive) stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 书单播时拉起本服务的占位前台通知（与 Media3 真媒体通知同 ID，原位替换无闪烁）：
     * 用户「一点播就停」等情况下 Media3 可能来不及接管，系统超时会直接崩进程。
     */
    private fun startPlaceholderForeground() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(BOOK_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        BOOK_CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, BOOK_CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("听书")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(activity)
            .build()
        ServiceCompat.startForeground(
            this, BOOK_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun dismissAlarm() {
        wakeFadeActive = false
        runCatching { player.pause(); player.stop(); player.clearMediaItems() }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(ALARM_NOTIF_ID) }
    }

    private fun postAlarmNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("aurora_alarm", getString(R.string.alarm_title), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val piFlags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val fsIntent = android.content.Intent(this, AlarmActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val fsPending = android.app.PendingIntent.getActivity(this, 1, fsIntent, piFlags)
        val dismissPending = android.app.PendingIntent.getService(
            this, 2, android.content.Intent(this, PlaybackService::class.java).setAction(ACTION_ALARM_DISMISS), piFlags,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, "aurora_alarm")
            .setSmallIcon(com.aurora.music.R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.alarm_notif_title))
            .setContentText(getString(R.string.alarm_dismiss_action))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(true)
            .setContentIntent(fsPending)
            .setFullScreenIntent(fsPending, true)
            .addAction(0, getString(R.string.alarm_dismiss), dismissPending)
            .build()
        nm.notify(ALARM_NOTIF_ID, notif)
    }

    private fun startAlarmPlayback() {
        scope.launch {
            val repo = container.repository
            val songs = runCatching { repo.starredSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { repo.downloadedSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: return@launch
            val ordered = songs.shuffled()
            player.setMediaItems(ordered.map { songItem(it) }, 0, 0L)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.prepare()
            player.volume = 0f
            wakeFadeMs = 30_000
            wakeFadeStartMs = android.os.SystemClock.elapsedRealtime()
            wakeFadeActive = true
            player.play()
            publishNowPlaying()
            postAlarmNotification()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // swipe-away stops playback so music doesnt keep going
        // 但先记住“刚才在播”：下次启动要接着播（暂停事件随后可能把队列里的 wasPlaying 写成 false）
        val wasPlaying = runCatching { player.playWhenReady || player.isPlaying }.getOrDefault(false)
        if (wasPlaying) runCatching { container.uiState.markResumeOnLaunch() }
        runCatching { fadePlayer?.run { pause(); clearMediaItems() } }
        player.pause()
        player.stop()
        player.clearMediaItems()
        // 听书同样停掉，退出双播。
        runCatching { container.ebookTts.stop() }
        dualArmed = false
        externalSnapshot = null
        // 队列/位置已经在播放过程中持续落盘；这里再强制刷一次，保证下次冷启动能接上。
        runCatching { container.queueStore.requestFlush() }
        runCatching { container.uiState.requestFlush() }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { noisyReceiver?.let { unregisterReceiver(it) } }
        noisyReceiver = null
        runCatching { container.ebookTtsPlayer.onAudioFocusChange = null }
        runCatching {
            abookPlaybackCb?.let {
                (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioPlaybackCallback(it)
            }
        }
        abookPlaybackCb = null
        abandonAbookDuckFocus()
        mediaSession?.release()
        mediaSession = null
        runCatching { container.ebookTts.onUserStop = null }
        runCatching { unifiedPlayer?.release() }
        unifiedPlayer = null
        runCatching { fadePlayer?.release() }
        fadePlayer = null
        runCatching { container.musicState.value = MusicPlaybackState() }
        runCatching { player.release() }
        super.onDestroy()
    }

    companion object {
        const val CMD_SHUFFLE = "com.aurora.music.SHUFFLE"
        const val CMD_REPEAT = "com.aurora.music.REPEAT"
        const val CMD_SLEEP_FADE = "com.aurora.music.SLEEP_FADE"
        const val CMD_ABOOK_START = "com.aurora.music.ABOOK_START"
        const val CMD_MUSIC_TOGGLE = "com.aurora.music.MUSIC_TOGGLE"
        const val CMD_MUSIC_PAUSE = "com.aurora.music.MUSIC_PAUSE"
        const val CMD_MUSIC_PLAY = "com.aurora.music.MUSIC_PLAY"
        const val CMD_MUSIC_SOLO = "com.aurora.music.MUSIC_SOLO"
        const val CMD_MUSIC_JUMP = "com.aurora.music.MUSIC_JUMP"
        const val CMD_MUSIC_NEXT = "com.aurora.music.MUSIC_NEXT"
        const val CMD_MUSIC_PREV = "com.aurora.music.MUSIC_PREV"
        const val CMD_MUSIC_SEEK = "com.aurora.music.MUSIC_SEEK"
        const val ACTION_PLAY_PAUSE = "com.aurora.music.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.aurora.music.action.NEXT"
        const val ACTION_PREV = "com.aurora.music.action.PREV"
        const val ACTION_ALARM = "com.aurora.music.action.ALARM"
        const val ACTION_ALARM_DISMISS = "com.aurora.music.action.ALARM_DISMISS"
        const val ACTION_SLEEP_PAUSE = "com.aurora.music.action.SLEEP_PAUSE"
        private const val ALARM_NOTIF_ID = 0xA1A

        // 书单播拉起统一服务的入口（占位前台防超时崩溃，见 startPlaceholderForeground）。
        const val ACTION_BOOK_START = "com.aurora.music.action.BOOK_START"

        // 与 Media3 DefaultMediaNotificationProvider 的默认值一致，占位通知才能被真媒体通知原位替换。
        private const val BOOK_CHANNEL_ID = "default_channel_id"
        private const val BOOK_NOTIF_ID = 1001

        /** 开始朗读时由 AppContainer 调用，把统一服务拉起以展示通知并接管媒体键。 */
        fun startForBook(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_BOOK_START)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 睡眠定时到点停音乐：只投递 intent（服务在跑即处理，不在跑则在 handler 里自停，
         * 不拉前台）。后台投递失败（服务没跑且应用在后台）由 runCatching 吞掉，
         * 此时站内本就没在播，无需处理。
         */
        fun pauseMusicForSleep(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_SLEEP_PAUSE)
            runCatching { context.startService(intent) }
        }
    }
}
