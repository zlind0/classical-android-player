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
import com.aurora.music.data.db.FilesMigration1_2
import com.aurora.music.data.db.FilesMigration2_3
import com.aurora.music.data.db.MediastoreDb
import com.aurora.music.data.db.MsMigration1_2
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

    val replayGainStore = ReplayGainStore(appContext)

    // 两套独立 SQLite 库，一源一库，物理隔离、永不串台
    private val filesDb: FilesDb = Room.databaseBuilder(appContext, FilesDb::class.java, "library_files.db")
        .addMigrations(FilesMigration1_2, FilesMigration2_3).build()
    private val mediastoreDb: MediastoreDb = Room.databaseBuilder(appContext, MediastoreDb::class.java, "library_mediastore.db")
        .addMigrations(MsMigration1_2).build()

    // MEDIASTORE 栈：DB 快照 → 内存；MediaStore 查询只在首次同步/手动重同步时发生
    val localLibrary = LocalLibrary(appContext, mediastoreDb.mediastoreDao(), gainProvider = { path -> replayGainStore.gainsFor(path) })
    val localStore = LocalStore(appContext)

    val tagEditor = TagEditor(appContext)

    // v0.3 local storage roots (plan §3-13): user-picked directories + scanner.
    // FILE 栈专属：roots 定义 + library_files.db 的内存映射，全程不碰 MediaStore。
    val volumeManager = StorageVolumeManager(appContext)
    val musicRoots = MusicRootsStore(appContext, filesDb.filesDao())
    val rootScanner = RootScanner(musicRoots, appContext)

    // 电子书栈：独立 Room 库 + 仓库 + 扫描器 + 阅读偏好
    private val ebookDb: com.aurora.music.data.ebook.EbookDb = Room.databaseBuilder(
        appContext, com.aurora.music.data.ebook.EbookDb::class.java, "ebook_library.db",
    ).build()
    val ebookStore = com.aurora.music.data.ebook.EbookStore(appContext, ebookDb.ebookDao())
    val ebookScanner = com.aurora.music.data.ebook.EbookScanner(ebookStore, ebookDb.ebookDao())
    val ebookPrefs = com.aurora.music.data.ebook.EbookPrefs(appContext)

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

    @Volatile private var squigBaseValue: String = DEFAULT_SQUIG_BASE
    @Volatile private var squigTargetValue: String = DEFAULT_SQUIG_TARGET
    val squigEq = SquigEqRepository(
        com.aurora.music.data.remote.SquigClient(),
        baseProvider = { squigBaseValue },
        targetProvider = { squigTargetValue },
    )

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

    val visualizer = com.aurora.music.playback.VisualizerController(scope)

    @Volatile
    private var lrclibEnabled: Boolean = true

    val lyricsRepository = LyricsRepository(backendProvider = { backend }, lrclibEnabledProvider = { lrclibEnabled })

    @Volatile
    private var networkUp: Boolean = true

    // 0 = system default
    val preferredAudioDeviceId = MutableStateFlow(0)

    val signalPath = MutableStateFlow(SignalPath())

    val dspMeters = MutableStateFlow(DspMeters())

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
            settingsStore.squigBaseUrl.collect { squigBaseValue = it }
        }
        scope.launch {
            settingsStore.squigTarget.collect { squigTargetValue = it }
        }
        scope.launch {
            settingsStore.alarmPrefs.collect { com.aurora.music.playback.AlarmScheduler.apply(appContext, it) }
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
