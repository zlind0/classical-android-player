package com.aurora.music.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    val localLibrary = LocalLibrary(appContext, gainProvider = { path -> replayGainStore.gainsFor(path) })
    private val localStore = LocalStore(appContext)

    val replayGainScanner = ReplayGainScanner(localLibrary, replayGainStore)

    val tagEditor = TagEditor(appContext)

    // v0.3 local storage roots (plan §3-13): user-picked directories + scanner
    val volumeManager = StorageVolumeManager(appContext)
    val musicRoots = MusicRootsStore(appContext)
    val rootScanner = RootScanner(musicRoots)

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

    @Volatile
    var backend: MediaBackend = LocalBackend(localLibrary, localStore, localSession)
        private set

    // server downloads are gone; DownloadManager keeps serving the on-device download index
    val downloadManager = DownloadManager(appContext)

    val sonicStore = SonicStore(appContext)
    val sonicEngine = SonicEngine(localLibrary, downloadManager, sonicStore)

    val artistInfoClient = com.aurora.music.data.remote.ArtistInfoClient()
    val artistInfoStore = ArtistInfoStore(appContext)

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

    private val _sessionReady = MutableStateFlow<Boolean?>(null)
    val sessionReady: StateFlow<Boolean?> = _sessionReady.asStateFlow()

    // reloads home/library without playback-stopping semantics
    private val _libraryReload = MutableStateFlow(0)
    val libraryReload: StateFlow<Int> = _libraryReload.asStateFlow()

    fun currentAccountKey(): String = "local"

    private val _offline = MutableStateFlow(false)
    // local-files mode never needs the network so it's never offline
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _noNetwork = MutableStateFlow(false)
    val noNetwork: StateFlow<Boolean> = _noNetwork.asStateFlow()

    @Volatile private var smartPlaylistsValue: List<SmartPlaylist> = emptyList()
    val smartEngine = SmartPlaylistEngine(playHistory, downloadManager)

    val repository = MusicRepository(
        backendProvider = { backend },
        downloadManager = downloadManager,
        offlineProvider = { false },
        currentServerIdProvider = { "" },
        smartPlaylistsProvider = { smartPlaylistsValue },
        smartEngine = smartEngine,
    )

    private fun recomputeOffline() {
        _offline.value = false
        _noNetwork.value = !networkUp
    }

    init {
        // scan() is idempotent only processes tracks not already in the vector store
        scope.launch {
            if (runCatching { settingsStore.sonicAutoAnalyze.first() }.getOrDefault(false)) sonicEngine.scan()
        }
        scope.launch {
            // local-only bootstrap: stamp the on-device session once so sessionReady gates open
            val existing = runCatching { settingsStore.session.first() }.getOrNull()
            if (existing == null) settingsStore.saveSession(localSession)
            backend = LocalBackend(localLibrary, localStore, runCatching { settingsStore.session.first() }.getOrNull() ?: localSession)
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
