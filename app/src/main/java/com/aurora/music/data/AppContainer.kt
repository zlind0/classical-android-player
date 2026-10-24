package com.aurora.music.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.room.Room
import com.aurora.music.data.db.FilesDb
import com.aurora.music.data.db.MediastoreDb
import com.aurora.music.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

// v0.6 gain-reduction meters (plan §36), polled from the DSP each tick
data class DspMeters(val compGrDb: Float = 0f, val limGrDb: Float = 0f)

/**
 * 站内音乐 player 的真实播放状态（PlaybackService 推送）。
 * 双播时系统会话 player 会被换成 UnifiedPlayer 承载朗读，它的时间线/序号都是书的占位，
 * 音乐选项卡与阅读页音乐气泡必须看这一份，否则会把书的占位序号当成歌单序号。
 */
data class MusicPlaybackState(
    val mediaId: String = "",
    /** 音乐 player 时间线的 mediaId 全表（顺序即播放顺序；双播时 UI 队列以此为准）。 */
    val queueIds: List<String> = emptyList(),
    val title: String = "",
    val artist: String = "",
    val artUri: String = "",
    val index: Int = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    /** androidx.media3.common.Player.REPEAT_MODE_* */
    val repeatMode: Int = 0,
)

// bitPerfect true only when samples reach output untouched float passthrough no dsp/mixing
data class SignalPath(
    val active: Boolean = false,
    val codec: String = "",
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val channels: Int = 0,
    val output: String = "",
    val bitPerfect: Boolean = false,
    val note: String = "",
)

// Classical fork (local-only): the on-device library is the only backend.
// There are no server sessions, no unified merge, no best-source rewriting.
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(appContext)
    val playHistory = PlayHistoryStore(appContext)

    val queueStore = QueueStore(appContext)

    /** 页面现场：Tab/返回栈/滚动位置/展开项/播放页展开状态（冷启动恢复用）。 */
    val uiState = UiStateStore(appContext)

    val replayGainStore = ReplayGainStore(appContext)

    // 两套独立 SQLite 库，一源一库，物理隔离、永不串台。
    // 不写迁移：schema 变更即清库重做（深扫/重同步会全量重填）。
    private val filesDb: FilesDb = Room.databaseBuilder(appContext, FilesDb::class.java, "library_files.db")
        .fallbackToDestructiveMigration().build()
    private val mediastoreDb: MediastoreDb = Room.databaseBuilder(appContext, MediastoreDb::class.java, "library_mediastore.db")
        .fallbackToDestructiveMigration().build()

    // MEDIASTORE 栈：DB 快照 → 内存；MediaStore 查询只在首次同步/手动重同步时发生
    val localLibrary = LocalLibrary(appContext, mediastoreDb.mediastoreDao(), gainProvider = { path -> replayGainStore.gainsFor(path) })
    val localStore = LocalStore(appContext)

    val tagEditor = TagEditor(appContext)

    // v0.3 local storage roots (plan §3-13): user-picked directories + scanner.
    // FILE 栈专属：roots 定义 + library_files.db 的内存映射，全程不碰 MediaStore。
    val volumeManager = StorageVolumeManager(appContext)
    val musicRoots = MusicRootsStore(appContext, filesDb.filesDao())
    // 后台扫描：断点暂存 + App 作用域编排 + 前台保活，切界面/切后台都不停
    val scanCheckpoints = ScanCheckpoints(appContext)
    val rootScanner = RootScanner(musicRoots, appContext, scanCheckpoints)
    val scanManager = LibraryScanManager(appContext, musicRoots, rootScanner, scanCheckpoints, scope)

    // 电子书栈：独立 Room 库 + 仓库 + 扫描器 + 阅读偏好
    private val ebookDb: com.aurora.music.data.ebook.EbookDb = Room.databaseBuilder(
        appContext, com.aurora.music.data.ebook.EbookDb::class.java, "ebook_library.db",
    ).fallbackToDestructiveMigration().build()
    val ebookStore = com.aurora.music.data.ebook.EbookStore(appContext, ebookDb.ebookDao())
    val ebookScanner = com.aurora.music.data.ebook.EbookScanner(ebookStore, ebookDb.ebookDao())
    val ebookPrefs = com.aurora.music.data.ebook.EbookPrefs(appContext)

    // 有声书栈：独立 Room 库 + 仓库 + 扫描器（书=根下直接子目录，章=书内音频文件）
    private val audiobookDb: com.aurora.music.data.audiobook.AudiobookDb = Room.databaseBuilder(
        appContext, com.aurora.music.data.audiobook.AudiobookDb::class.java, "audiobook_library.db",
    ).fallbackToDestructiveMigration().build()
    val audiobookStore = com.aurora.music.data.audiobook.AudiobookStore(appContext, audiobookDb.audiobookDao())
    val audiobookScanner = com.aurora.music.data.audiobook.AudiobookScanner(audiobookStore, audiobookDb.audiobookDao())

    // 曲库来源开关（默认 MEDIastore）。两个栈各自独立，切换 = 换 backend + 按源加载。
    private val _librarySource = MutableStateFlow(LibrarySource.MEDIastore)
    val librarySource: StateFlow<LibrarySource> = _librarySource.asStateFlow()

    fun setLibrarySource(v: LibrarySource) {
        scope.launch {
            settingsStore.setLibrarySource(v)
            // DataStore collect 回来后统一走 switchToSource，避免双写竞态
        }
    }

    private suspend fun switchToSource(v: LibrarySource) {
        if (_librarySource.value != v) _librarySource.value = v
        backend = if (v == LibrarySource.FILE) fileBackend else mediaBackend
        loadActiveSource()
        _libraryReload.value++
    }

    /**
     * 按源加载：只读各自 DB 进内存（秒开），再后台做存在性检查。
     * 深扫永不在这里发生（FILE 深扫只在加库/手动重扫；MEDIastore 全量只在 DB 为空时）。
     */
    private suspend fun loadActiveSource() {
        if (_librarySource.value == LibrarySource.FILE) {
            runCatching { musicRoots.loadFromDb() }
            scope.launch(Dispatchers.IO) {
                val gone = runCatching {
                    musicRoots.allRows()
                        .filter { it.available && !File(it.path).exists() }
                        .map { it.path }
                }.getOrDefault(emptyList())
                // 整 root 基目录消失（外接拔掉）→ 整 root 标 unavailable，不删行
                val deadRoots = musicRoots.roots.value
                    .filter { root -> runCatching { !File(root.rootPath).exists() }.getOrDefault(false) }
                    .flatMap { root -> musicRoots.readIndex(root.id).filter { it.available }.map { it.path } }
                val all = (gone + deadRoots).distinct()
                var changed = 0
                all.chunked(500).forEach { chunk ->
                    runCatching { musicRoots.markUnavailable(chunk) }
                    changed += chunk.size
                }
                if (changed > 0) _libraryReload.value++
            }
        } else {
            runCatching { localLibrary.loadFromDb() }
            _libraryReload.value++
            scope.launch(Dispatchers.IO) {
                val changed = runCatching { localLibrary.pruneMissing() }.getOrDefault(0)
                if (changed > 0) _libraryReload.value++
            }
        }
    }

    val backupManager = BackupManager(settingsStore, localStore, playHistory)

    val musicBrainz = com.aurora.music.data.remote.MusicBrainzClient()

    @Volatile private var acoustIdKeyValue: String = ""
    val acoustId = com.aurora.music.data.remote.AcoustIdClient(apiKeyProvider = { acoustIdKeyValue })

    val autoEq = AutoEqRepository(appContext)
    val autoEqController = AutoEqController(appContext, settingsStore, scope)

    private val localSession = Session(server = "On this device", username = "Local Library", salt = "", token = "local", type = ServerType.LOCAL)

    @Volatile private var activeSession: Session = localSession
    private val mediaBackend get(): MediaBackend = LocalBackend(localLibrary, localStore, activeSession)
    private val fileBackend get(): MediaBackend = FileBackend(musicRoots, localStore, activeSession)

    @Volatile
    var backend: MediaBackend = mediaBackend
        private set

    // 分析引擎看到的永远是当前 source 的可用歌单（消失文件不参与分析）
    private val activePool = object : SongPool {
        override val songs: List<Song>
            get() = if (_librarySource.value == LibrarySource.FILE) musicRoots.allSongs().filter { it.available } else localLibrary.songs.filter { it.available }
        override suspend fun ensureLoaded() {
            if (_librarySource.value == LibrarySource.FILE) musicRoots.ensureLoaded() else localLibrary.ensureLoaded()
        }
        override suspend fun refresh() {
            if (_librarySource.value == LibrarySource.FILE) musicRoots.refresh() else localLibrary.refresh()
        }
    }

    val replayGainScanner = ReplayGainScanner(activePool, replayGainStore)

    // server downloads are gone; DownloadManager keeps serving the on-device download index
    val downloadManager = DownloadManager(appContext)

    val sonicStore = SonicStore(appContext)
    val sonicEngine = SonicEngine(activePool, downloadManager, sonicStore)

    val artistInfoClient = com.aurora.music.data.remote.ArtistInfoClient()
    val artistInfoStore = ArtistInfoStore(appContext)

    val songIntroLog = SongIntroLogStore(appContext)
    val songIntro = SongIntroController(appContext, settingsStore, songIntroLog)

    // 有声书会话态：VM 写当前书/增益，PlaybackService 读（音量乘子/混音焦点）
    val abookSession = com.aurora.music.data.audiobook.AudiobookSession()

    val isLocal: Boolean get() = true

    @Volatile private var hapticsEnabled = false
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    fun haptic() {
        if (!hapticsEnabled) return
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else @Suppress("DEPRECATION") v.vibrate(12)
        }
    }

    val audioSessionId: Int = runCatching {
        (appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
    }.getOrDefault(0)

    val audioEffects = AudioEffectsController(audioSessionId, settingsStore, scope)

    // TTS：两个独立播放器（同 DSP 链，互不抢占）+ 统一 worker（合成/缓存/代际共用）。
    // 合成产物只留内存，播完即丢；介绍播报也走 DSP 链，不再直驱 AudioTrack。
    val ebookTtsPlayer = com.aurora.music.playback.EbookTtsPlayer(appContext, settingsStore, audioSessionId, duckable = true)
    val introTtsPlayer = com.aurora.music.playback.EbookTtsPlayer(appContext, settingsStore, audioSessionId)
    val ttsWorker = com.aurora.music.tts.TtsWorker(appContext, settingsStore, ebookTtsPlayer, introTtsPlayer)
    // 全局睡眠定时后台：电子书选项页与音乐页月亮气泡共用，进程活着就一直记着
    val sleepTimer: SleepTimerWorker = SleepTimerWorker().also {
        it.onFired = { pauseSideMediaForSleep() }
    }
    val ebookTts = com.aurora.music.data.ebook.EbookTtsController(appContext).also {
        it.prefs = ebookPrefs
        it.settings = settingsStore
        it.player = ebookTtsPlayer
        it.tts = ttsWorker
        it.store = ebookStore
        it.sleep = sleepTimer
        sleepTimer.primaryActive = { runCatching { it.playing.value }.getOrDefault(false) }
        songIntro.tts = ttsWorker
    }

    val visualizer = com.aurora.music.playback.VisualizerController(scope)

    /**
     * 睡眠定时到点：暂停听书之外的媒体（站内音乐 + 站外系统媒体）。
     * 听书由 EbookTtsController 读完当前再停，不在这里动。幂等多调无害。
     */
    fun pauseSideMediaForSleep() {
        scope.launch(Dispatchers.IO) {
            // 站外先发一轮：若此时站外会话正活跃可直接命中
            runCatching { dispatchExternalPause() }
            // 站内音乐：经统一服务只停音乐一路（淡出 6 秒）
            runCatching { com.aurora.music.playback.PlaybackService.pauseMusicForSleep(appContext) }
            // 到点时我们自己的会话可能仍活跃（音乐淡出中），首发暂停键容易被自己吃掉；
            // 等站内停稳、焦点让出后再补两发，此时在播的只剩站外，必中目标会话。
            // 只发 PAUSE（已暂停则无操作，绝不发 TOGGLE，避免误把别家唤醒）。
            kotlinx.coroutines.delay(7_000)
            runCatching { dispatchExternalPause() }
            kotlinx.coroutines.delay(5_000)
            runCatching { dispatchExternalPause() }
        }
    }

    /**
     * 给当前系统媒体会话发暂停键（down + up 各一）。
     * KeyEvent 必须带正常 uptime 时间戳：零时间戳事件会被系统路由丢掉。
     */
    private fun dispatchExternalPause() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val now = android.os.SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0),
        )
        am.dispatchMediaKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0),
        )
    }

    @Volatile
    private var lrclibEnabled: Boolean = true

    val lyricsRepository = LyricsRepository(backendProvider = { backend }, lrclibEnabledProvider = { lrclibEnabled })

    @Volatile
    private var networkUp: Boolean = true

    // 0 = system default
    val preferredAudioDeviceId = MutableStateFlow(0)

    val signalPath = MutableStateFlow(SignalPath())

    val dspMeters = MutableStateFlow(DspMeters())

    /** 站内音乐 player 的时间线位置/播放态（见 [MusicPlaybackState]）。 */
    val musicState = MutableStateFlow(MusicPlaybackState())

    private val _sessionReady = MutableStateFlow<Boolean?>(null)
    val sessionReady: StateFlow<Boolean?> = _sessionReady.asStateFlow()

    // reloads home/library without playback-stopping semantics
    private val _libraryReload = MutableStateFlow(0)
    val libraryReload: StateFlow<Int> = _libraryReload.asStateFlow()

    /** 库内容变化通知（播放报错标 unavailable 等）：各列表重查，播放不受影响。 */
    fun notifyLibraryChanged() {
        _libraryReload.value++
    }

    fun currentAccountKey(): String = "local"

    private val _offline = MutableStateFlow(false)
    // local-files mode never needs the network so it's never offline
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _noNetwork = MutableStateFlow(false)
    val noNetwork: StateFlow<Boolean> = _noNetwork.asStateFlow()

    @Volatile private var smartPlaylistsValue: List<SmartPlaylist> = emptyList()
    val smartEngine = SmartPlaylistEngine(playHistory, downloadManager)

    val repository = MusicRepository(
        appContext,
        backendProvider = { backend },
        downloadManager = downloadManager,
        offlineProvider = { false },
        currentServerIdProvider = { "" },
        smartPlaylistsProvider = { smartPlaylistsValue },
        smartEngine = smartEngine,
    )

    // 后台方形悬浮窗（app 作用域，切后台才挂 WindowManager）
    val floatingWindow by lazy {
        com.aurora.music.ui.overlay.FloatingWindowManager(appContext, settingsStore, repository)
    }

    private fun recomputeOffline() {
        _offline.value = false
        _noNetwork.value = !networkUp
    }

    init {
        scope.launch {
            // 开关是唯一真相源：collect 首个值即当前开关，按源加载对应栈；切换同样走这里
            settingsStore.librarySource.collect { switchToSource(it) }
        }
        scope.launch {
            // FILE 模式下 roots 启用开关变化只刷新列表，不碰 MediaStore 栈
            musicRoots.roots.collect {
                if (_librarySource.value == LibrarySource.FILE) _libraryReload.value++
            }
        }
        // scan() is idempotent only processes tracks not already in the vector store
        scope.launch {
            if (runCatching { settingsStore.sonicAutoAnalyze.first() }.getOrDefault(false)) sonicEngine.scan()
        }
        scope.launch {
            // local-only bootstrap: stamp the on-device session once so sessionReady gates open
            val existing = runCatching { settingsStore.session.first() }.getOrNull()
            if (existing == null) settingsStore.saveSession(localSession)
            activeSession = runCatching { settingsStore.session.first() }.getOrNull() ?: localSession
            backend = if (_librarySource.value == LibrarySource.FILE) fileBackend else mediaBackend
            _sessionReady.value = true
        }
        scope.launch {
            settingsStore.lrclibEnabled.collect { lrclibEnabled = it }
        }
        scope.launch {
            settingsStore.haptics.collect { hapticsEnabled = it }
        }
        scope.launch {
            settingsStore.smartPlaylists.collect { smartPlaylistsValue = it }
        }
        scope.launch {
            settingsStore.acoustIdKey.collect { acoustIdKeyValue = it }
        }
        scope.launch {
            settingsStore.alarmPrefs.collect { com.aurora.music.playback.AlarmScheduler.apply(appContext, it) }
        }
        scope.launch {
            // 听书通知栏/耳机控制：开始朗读即拉起统一播放服务（单服务单会话，纯集成，不碰 TTS 合成与播放逻辑）。
            ebookTts.playing.collect { playing ->
                if (playing) {
                    runCatching { com.aurora.music.playback.PlaybackService.startForBook(appContext) }
                }
            }
        }
        scope.launch {
            // 电子书旧 TTS 设置一次性迁入统一设置（失败不影响启动）。
            runCatching { settingsStore.migrateEbookTts(ebookPrefs.snapshotTts(), ebookPrefs.isTtsCustomized()) }
        }
        scope.launch {
            // 有声书跳过关键字变化只重过滤，不碰 DB
            settingsStore.audiobookPrefs.collect { runCatching { audiobookStore.setSkipKeywords(it.skipKeywords) } }
        }
        scope.launch(Dispatchers.IO) {
            // 断点续扫：上次没扫完（每 200 个已暂存）的接着扫，已暂存的不重扫。
            // loadFromDb 之后调用，保证 roots/内存快照就绪；串行逐个续扫。
            runCatching { musicRoots.ensureLoaded() }
            runCatching { scanManager.resumePending() }
        }
        registerConnectivity()
    }

    private fun registerConnectivity() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // require VALIDATED so wifi-without-real-internet counts as offline for info features
        fun hasInternet(caps: NetworkCapabilities?) = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            networkUp = hasInternet(caps)
        }
        recomputeOffline()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) { networkUp = false; recomputeOffline() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    networkUp = hasInternet(caps)
                    recomputeOffline()
                }
            })
        }
    }
}
