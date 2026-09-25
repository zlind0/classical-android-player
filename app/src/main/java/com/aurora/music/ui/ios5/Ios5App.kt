package com.aurora.music.ui.ios5

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.navigation.Ios5Routes
import com.aurora.music.navigation.ios5TabRoutes
import com.aurora.music.navigation.ios5Tabs
import com.aurora.music.ui.browse.Ios5AlbumsBrowse
import com.aurora.music.ui.browse.Ios5Detail
import com.aurora.music.ui.browse.Ios5FolderLevel
import com.aurora.music.ui.browse.Ios5FoldersRoot
import com.aurora.music.ui.browse.Ios5GroupDetail
import com.aurora.music.ui.browse.Ios5GroupsBrowse
import com.aurora.music.ui.browse.Ios5SongsBrowse
import com.aurora.music.ui.browse.searchScopeFor
import com.aurora.music.ui.player.Ios5MiniStrip
import com.aurora.music.ui.player.Ios5PlayerPage
import com.aurora.music.ui.search.Ios5SearchPage
import com.aurora.music.ui.tabs.ArtistsTab
import com.aurora.music.ui.tabs.HomeTab
import com.aurora.music.ui.tabs.MoreTab
import com.aurora.music.ui.tabs.PlaylistsTab
import com.aurora.music.ui.tabs.SettingsTab
import com.aurora.music.viewmodel.HomeViewModel
import com.aurora.music.viewmodel.Ios5BrowseViewModel
import com.aurora.music.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@Composable
fun Ios5App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container

    val navController = rememberNavController()
    val playerVM: PlayerViewModel = viewModel()
    val browseVM: Ios5BrowseViewModel = viewModel()

    val playerState by playerVM.state.collectAsStateWithLifecycle()
    val browseState by browseVM.state.collectAsStateWithLifecycle()
    val sessionReady by container.sessionReady.collectAsStateWithLifecycle()
    val uiPrefs by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.UiPrefs())

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun confirm(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    fun onIntroClick() {
        val s = playerState.current
        if (s.id.isNotEmpty()) container.songIntro.toggle(s)
    }
    val introState by container.songIntro.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        container.songIntro.music = object : com.aurora.music.data.IntroMusicControl {
            override fun isPlaying(): Boolean = playerVM.state.value.isPlaying
            override fun pause() = playerVM.pause()
            override fun resume() = playerVM.play()
        }
        container.songIntro.events.collect { confirm(it) }
    }
    LaunchedEffect(playerState.current.id) {
        container.songIntro.onSongChanged(playerState.current.id)
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var lastTab by remember { mutableStateOf(Ios5Routes.HOME) }
    LaunchedEffect(currentRoute) {
        if (currentRoute in ios5TabRoutes) lastTab = currentRoute ?: Ios5Routes.HOME
    }
    val tabHighlight = if (currentRoute in ios5TabRoutes) currentRoute else lastTab

    var showQueue by remember { mutableStateOf(false) }

    // 横向合并顶栏的上报宿主：NavHost 内页面的 Ios5NavBar 把标题/返回/搜索交到这里统一渲染
    val topBarHost = remember { TopBarHost() }

    fun navigateTopLevel(route: String) {
        if (currentRoute == route) return
        container.haptic()
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }
    fun openDetail(kind: String, id: String, title: String = "") =
        navController.navigate(Ios5Routes.detail(kind, id, title))
    fun playCollection(kind: String, id: String) = scope.launch {
        container.repository.detail(kind, id)
            ?.let { if (it.tracks.isNotEmpty()) playerVM.playCollection(kind, id, it.tracks, 0, it.info.songCount) }
    }
    fun shuffleCollection(kind: String, id: String) = scope.launch {
        container.repository.detail(kind, id)
            ?.let { if (it.tracks.isNotEmpty()) playerVM.shuffleCollection(kind, id, it.tracks, it.info.songCount) }
    }
    fun playById(id: String) = scope.launch {
        container.repository.songFor(id)?.let { playerVM.play(it) }
    }

    if (sessionReady == null) {
        Ios5Backdrop {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(90.dp))
            }
        }
        return
    }

    Ios5Backdrop {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        // 横向：左 5-tab 主界面（宽度自适应）+ 右固定播放侧栏；竖屏保持翻转整页
        // Activity 不重建（configChanges），NavController/ViewModel/remember 状态旋转前后一致
        val landscape = maxWidth > maxHeight
        // 横屏隐藏系统状态栏（外观开关，默认开）：全屏播放布局，手势可临时划出
        DisposableEffect(landscape, uiPrefs.hideStatusBarLandscape) {
            val activity = context as? Activity
            val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
            if (landscape && uiPrefs.hideStatusBarLandscape) {
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller?.show(WindowInsetsCompat.Type.statusBars())
            }
            onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
        }
        // 返回键：竖屏且播放页开着 → 先关播放页（再按才作用于主页面）；
        // 横屏播放页与主页面左右并排 → 返回键直接作用于主页面。
        // 转横屏时清掉残留的 expanded，否则它会吞掉横屏的返回键。
        LaunchedEffect(landscape) {
            if (landscape && playerState.expanded) playerVM.setExpanded(false)
        }
        // iOS5 翻转：内容面 / 播放面，前半程内容转走，后半程播放页转入（仅竖屏）
        val flipDensity = LocalDensity.current
        val flip by animateFloatAsState(
            targetValue = if (!landscape && playerState.expanded) 1f else 0f,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "playerFlip",
        )
        // 翻转过程中衬黑（转到侧面露黑场）；静止时透明，亚麻底才能透出来
        Box(
            Modifier.fillMaxSize()
                .background(if (flip > 0.02f && flip < 0.98f) Color.Black else Color.Transparent),
        ) {
            // ---- 内容面：自带亚麻底，转起来是一张实卡，真空才露黑 ----
            // 抽成局部函数，竖屏翻转与横向双栏共用同一棵树，状态天然一致
            @Composable
            fun ContentFace(faceFlip: Float, showMiniStrip: Boolean, showTabBar: Boolean) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        rotationY = faceFlip * 180f
                        cameraDistance = 8 * flipDensity.density
                        alpha = if (faceFlip < 0.5f) 1f else 0f
                    }
                    .background(Ios5Colors.linenBrush),
            ) {
            Column(Modifier.fillMaxSize()) {
                // ---- 上栏：当前页面 ----
                // Mini 条在下栏文档流里占位，不再是覆盖层，无需预留
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Ios5Routes.HOME,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable(Ios5Routes.HOME) {
                            val homeVM: HomeViewModel = viewModel()
                            val homeState by homeVM.state.collectAsStateWithLifecycle()
                            HomeTab(
                                state = homeState,
                                onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                onPlaySongs = { songs, index -> playerVM.playAll(songs, index) },
                            )
                        }
                        composable(Ios5Routes.PLAYLISTS) {
                            PlaylistsTab(
                                playlists = browseState.playlists,
                                smartCount = 0,
                                onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                onCreatePlaylist = { name ->
                                    scope.launch {
                                        container.repository.createPlaylist(name)
                                        browseVM.load()
                                    }
                                },
                                onOpenSearch = { navController.navigate(Ios5Routes.search("songs")) },
                            )
                        }
                        composable(Ios5Routes.ARTISTS) {
                            ArtistsTab(
                                artists = browseState.artists,
                                loading = browseState.loading,
                                onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                onOpenSearch = { navController.navigate(Ios5Routes.search("artists")) },
                            )
                        }
                        composable(Ios5Routes.MORE) {
                            MoreTab(onOpenRoute = { navController.navigate(it) })
                        }
                        composable(Ios5Routes.SETTINGS) {
                            SettingsTab(
                                onOpen = { navController.navigate(it) },
                                onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                            )
                        }
                        composable(Ios5Routes.MORE_SONGS) {
                            Ios5SongsBrowse(
                                songs = browseState.songs,
                                loading = browseState.loading,
                                player = playerState,
                                onBack = { navController.popBackStack() },
                                onSearch = { navController.navigate(Ios5Routes.search("songs")) },
                                onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                            )
                        }
                        composable(Ios5Routes.MORE_ALBUMS) {
                            Ios5AlbumsBrowse(
                                albums = browseState.albums,
                                loading = browseState.loading,
                                onBack = { navController.popBackStack() },
                                onSearch = { navController.navigate(Ios5Routes.search("albums")) },
                                onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                            )
                        }
                        composable(Ios5Routes.MORE_GENRES) {
                            Ios5GroupsBrowse(
                                title = "风格",
                                groups = browseState.genres,
                                loading = browseState.loading,
                                searchScope = "all",
                                onBack = { navController.popBackStack() },
                                onSearch = { navController.navigate(Ios5Routes.search("all")) },
                                onOpenGroup = { name -> openDetail("genre", name, name) },
                            )
                        }
                        composable(Ios5Routes.MORE_COMPOSERS) {
                            Ios5GroupsBrowse(
                                title = "作曲家",
                                groups = browseState.composers,
                                loading = browseState.loading,
                                searchScope = "all",
                                onBack = { navController.popBackStack() },
                                onSearch = { navController.navigate(Ios5Routes.search("all")) },
                                onOpenGroup = { name -> openDetail("composer", name, name) },
                            )
                        }
                        composable(Ios5Routes.MORE_FOLDERS) {
                            Ios5FoldersRoot(
                                onBack = { navController.popBackStack() },
                                onSearch = { navController.navigate(Ios5Routes.search("songs")) },
                                onOpenFolder = { fid, title -> navController.navigate(Ios5Routes.folder(fid, title)) },
                                player = playerState,
                                onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                            )
                        }
                        composable(
                            Ios5Routes.FOLDER,
                            arguments = listOf(
                                androidx.navigation.navArgument("fid") { defaultValue = "" },
                                androidx.navigation.navArgument("title") { defaultValue = "" },
                            ),
                        ) { entry ->
                            val fid = entry.arguments?.getString("fid").orEmpty()
                            val folderTitle = entry.arguments?.getString("title").orEmpty()
                            Ios5FolderLevel(
                                fid = fid,
                                title = folderTitle,
                                onBack = { navController.popBackStack() },
                                onSearch = { navController.navigate(Ios5Routes.search("songs")) },
                                onOpenFolder = { id, name -> navController.navigate(Ios5Routes.folder(id, name)) },
                                player = playerState,
                                onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                            )
                        }
                        composable(
                            Ios5Routes.DETAIL,
                            arguments = listOf(
                                androidx.navigation.navArgument("kind") { defaultValue = "" },
                                androidx.navigation.navArgument("id") { defaultValue = "" },
                                androidx.navigation.navArgument("title") { defaultValue = "" },
                            ),
                        ) { entry ->
                            val kind = Uri.decode(entry.arguments?.getString("kind").orEmpty())
                            val id = Uri.decode(entry.arguments?.getString("id").orEmpty())
                            val title = Uri.decode(entry.arguments?.getString("title").orEmpty())
                            when (kind) {
                                "genre" -> {
                                    val songs = browseState.genres.firstOrNull { it.name == id }?.songs.orEmpty()
                                    Ios5GroupDetail(
                                        title = title.ifBlank { id },
                                        songs = songs,
                                        player = playerState,
                                        onBack = { navController.popBackStack() },
                                        onSearch = { navController.navigate(Ios5Routes.search("all")) },
                                        onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                        onPlayAll = { if (songs.isNotEmpty()) playerVM.playAll(songs, 0) },
                                        onShuffleAll = { if (songs.isNotEmpty()) playerVM.shufflePlay(songs) },
                                    )
                                }
                                "composer" -> {
                                    val songs = browseState.composers.firstOrNull { it.name == id }?.songs.orEmpty()
                                    Ios5GroupDetail(
                                        title = title.ifBlank { id },
                                        songs = songs,
                                        player = playerState,
                                        onBack = { navController.popBackStack() },
                                        onSearch = { navController.navigate(Ios5Routes.search("all")) },
                                        onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                        onPlayAll = { if (songs.isNotEmpty()) playerVM.playAll(songs, 0) },
                                        onShuffleAll = { if (songs.isNotEmpty()) playerVM.shufflePlay(songs) },
                                    )
                                }
                                else -> Ios5Detail(
                                    kind = kind,
                                    id = id,
                                    title = title,
                                    player = playerState,
                                    onBack = { navController.popBackStack() },
                                    onSearch = { navController.navigate(Ios5Routes.search(searchScopeFor(currentRoute))) },
                                    onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                    onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                    onPlayCollection = { k, i -> playCollection(k, i) },
                                    onShuffleCollection = { k, i -> shuffleCollection(k, i) },
                                )
                            }
                        }
                        composable(
                            Ios5Routes.SEARCH,
                            arguments = listOf(
                                androidx.navigation.navArgument("scope") { defaultValue = "all" },
                            ),
                        ) { entry ->
                            val scopeArg = entry.arguments?.getString("scope") ?: "all"
                            Ios5SearchPage(
                                scope = scopeArg,
                                player = playerState,
                                onBack = { navController.popBackStack() },
                                onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_PLAYBACK) {
                            com.aurora.music.ui.screens.settings.PlaybackSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_EQ) {
                            com.aurora.music.ui.screens.settings.EqualizerScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_VISUALIZER) {
                            com.aurora.music.ui.screens.settings.VisualizerSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_SONIC) {
                            com.aurora.music.ui.screens.settings.SonicSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_SOURCES) {
                            com.aurora.music.ui.screens.settings.MusicSourcesScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                                onPlayRoot = { id ->
                                    scope.launch {
                                        val songs = container.musicRoots.songsOf(id)
                                        if (songs.isEmpty()) confirm(context.getString(R.string.msg_no_scanned_tracks))
                                        else playerVM.playAll(songs, 0)
                                    }
                                },
                                confirm = { confirm(it) },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_STORAGE) {
                            com.aurora.music.ui.screens.settings.StorageSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_APPEARANCE) {
                            com.aurora.music.ui.screens.settings.AppearanceScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_GESTURES) {
                            com.aurora.music.ui.screens.settings.GesturesSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_INTEGRATIONS) {
                            com.aurora.music.ui.screens.settings.IntegrationsSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_PERMISSIONS) {
                            com.aurora.music.ui.screens.settings.PermissionsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_ABOUT) {
                            com.aurora.music.ui.screens.settings.AboutSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_BACKUP) {
                            com.aurora.music.ui.screens.settings.BackupScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                                confirm = { confirm(it) },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_INTRO) {
                            com.aurora.music.ui.screens.settings.SongIntroSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                                confirm = { confirm(it) },
                                onOpenPrompt = { navController.navigate(Ios5Routes.SETTINGS_INTRO_PROMPT) },
                                onOpenVoice = { navController.navigate(Ios5Routes.SETTINGS_INTRO_VOICE) },
                                onOpenLog = { navController.navigate(Ios5Routes.SETTINGS_INTRO_LOG) },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_INTRO_PROMPT) {
                            com.aurora.music.ui.screens.settings.SongIntroPromptScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_INTRO_VOICE) {
                            com.aurora.music.ui.screens.settings.SongIntroVoiceScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_INTRO_LOG) {
                            com.aurora.music.ui.screens.settings.SongIntroLogScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                                confirm = { confirm(it) },
                            )
                        }
                        composable(Ios5Routes.HISTORY) {
                            com.aurora.music.ui.screens.stats.ListeningHistoryScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                                onPlay = { playById(it) },
                            )
                        }
                        composable(Ios5Routes.STATS) {
                            com.aurora.music.ui.screens.stats.ListeningStatsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                                onPlay = { playById(it) },
                                onOpenDetail = { k, i -> openDetail(k, i) },
                            )
                        }
                        composable(Ios5Routes.DUPLICATES) {
                            val dupVM: com.aurora.music.viewmodel.DuplicatesViewModel = viewModel()
                            val dupState by dupVM.state.collectAsStateWithLifecycle()
                            com.aurora.music.ui.screens.library.DuplicatesScreen(
                                contentPadding = PaddingValues(0.dp),
                                loading = dupState.loading,
                                scanned = dupState.scanned,
                                groups = dupState.groups,
                                currentSongId = playerState.current.id,
                                onBack = { navController.popBackStack() },
                                onPlay = { s -> playerVM.playAll(listOf(s), 0) },
                            )
                        }
                        composable(
                            Ios5Routes.SMART_EDIT,
                            arguments = listOf(androidx.navigation.navArgument("id") { defaultValue = "" }),
                        ) { entry ->
                            val smartId = entry.arguments?.getString("id").orEmpty()
                            val smartVM: com.aurora.music.viewmodel.SmartPlaylistViewModel = viewModel()
                            LaunchedEffect(smartId) { smartVM.load(smartId) }
                            val smartState by smartVM.state.collectAsStateWithLifecycle()
                            com.aurora.music.ui.screens.library.SmartPlaylistEditScreen(
                                contentPadding = PaddingValues(0.dp),
                                playlist = smartState,
                                isNew = smartId.isBlank(),
                                onUpdate = smartVM::update,
                                onSave = { smartVM.save { navController.popBackStack() } },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            Ios5Routes.TAG_EDIT,
                            arguments = listOf(androidx.navigation.navArgument("songId") { defaultValue = "" }),
                        ) { entry ->
                            val songId = entry.arguments?.getString("songId").orEmpty()
                            val tagVM: com.aurora.music.viewmodel.TagEditViewModel = viewModel()
                            LaunchedEffect(songId) { tagVM.load(songId) }
                            val tagState by tagVM.state.collectAsStateWithLifecycle()
                            com.aurora.music.ui.screens.detail.TagEditScreen(
                                contentPadding = PaddingValues(0.dp),
                                state = tagState,
                                onEdit = tagVM::edit,
                                onMatch = tagVM::matchOnline,
                                onApplyMatch = tagVM::applyMatch,
                                onIdentify = if (container.acoustId.available && tagState.localFile) ({ tagVM.identify() }) else null,
                                identifying = tagState.identifying,
                                onBack = { navController.popBackStack() },
                                confirm = { confirm(it) },
                            )
                        }
                    }
                }

                // ---- 下栏：Mini 条 + Tab（不透明，背景直贴系统栏，内容避让手势区） ----
                // 横向时下栏挪到底部整条栏里拼走带键，这里只留内容
                if (showTabBar) {
                Box(
                    Modifier.fillMaxWidth().background(Ios5Colors.tabBrush),
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        if (playerState.hasTrack && faceFlip < 0.5f && showMiniStrip) {
                            Ios5MiniStrip(
                                state = playerState,
                                onExpand = { playerVM.setExpanded(true) },
                                onTogglePlay = { playerVM.togglePlay() },
                                onNext = { playerVM.next() },
                            )
                        }
                        Ios5TabBar(tabHighlight, onNavigate = { navigateTopLevel(it) })
                    }
                }
                }
            }
            } // 内容 Box 结束
            } // ContentFace 内容面函数结束

            if (landscape) {
                // ---- 横向：顶整条栏 + 中部（左主界面 + 右播放侧栏）+ 底整条栏 ----
                // 系统三按钮控制条背后一律黑色：顶/底栏黑底延伸进 insets 区，内容避让
                val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val navEnd = WindowInsets.navigationBars.asPaddingValues().calculateEndPadding(LocalLayoutDirection.current)
                val topSpec = topBarHost.spec
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // 右栏宽严格等于中部可用高度：进度条是透明浮层不占高度，
                    // 一分不扣全给封面，封面永远正方形铺满（极端窄屏时以宽度为上限兜底）
                    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val topBarH = statusTop + 46.dp
                    val bottomBarH = 60.dp + navBottom
                    val middleH = (maxHeight - topBarH - bottomBarH).coerceAtLeast(0.dp)
                    val panelWidth = minOf(middleH, maxWidth).coerceAtLeast(0.dp)
                    Column(Modifier.fillMaxSize()) {
                    // 顶整条栏：左页面导航 + 右正在播放（黑底一通到底，字体行距压缩紧凑）
                    Column(
                        Modifier.fillMaxWidth().background(
                            Brush.verticalGradient(
                                0f to Color(0xFF3D434C),
                                1f to Color(0xFF14161B),
                            ),
                        ).padding(top = statusTop, end = navEnd),
                    ) {
                        Row(Modifier.fillMaxWidth().height(46.dp)) {
                            Row(
                                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
                                    topSpec?.onBack?.let { back ->
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBackIos, "返回", tint = Color.White,
                                            modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = back).padding(6.dp),
                                        )
                                    }
                                }
                                Text(
                                    topSpec?.title
                                        ?: ios5Tabs.firstOrNull { it.route == tabHighlight }?.label.orEmpty(),
                                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                    fontFamily = Ios5Sans,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                                    topSpec?.onSearch?.let { search ->
                                        Icon(
                                            Icons.Filled.Search, "搜索", tint = Color.White,
                                            modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = search).padding(5.dp),
                                        )
                                    }
                                }
                            }
                            Row(
                                Modifier.width(panelWidth).fillMaxHeight().padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val song = playerState.current
                                val merged = com.aurora.music.ui.player.rememberMergedTitle(song)
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        song.artist.ifBlank { " " },
                                        color = Color(0xFF9AA0AB), fontSize = 10.sp, lineHeight = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = Ios5Sans,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (merged != null) {
                                        Text(
                                            merged.first, color = Color.White,
                                            fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                                            fontFamily = Ios5Sans,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            merged.second.ifBlank { song.title },
                                            color = Color(0xFFB9BEC7), fontSize = 11.sp, lineHeight = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = Ios5Sans,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        Text(
                                            song.title.ifBlank { "未在播放" },
                                            color = Color.White, fontSize = 14.sp, lineHeight = 17.sp,
                                            fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.QueueMusic, "队列", tint = Color.White,
                                    modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = { showQueue = true }).padding(5.dp),
                                )
                            }
                        }
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            CompositionLocalProvider(LocalTopBarHost provides topBarHost) {
                                ContentFace(0f, false, false)
                            }
                        }
                        com.aurora.music.ui.player.Ios5LandscapeSidePlayer(
                            state = playerState,
                            onSeek = { playerVM.seekTo(it) },
                            panelWidth = panelWidth,
                        )
                    }
                    // 整条底栏：左 Tab（自适应）+ 右走带键（340dp 与侧栏对齐），背后黑色。
                    // 高度固定（子项 fillMaxHeight 在自适应 Row 里会把整栏撑满全屏）
                    Box(
                        Modifier.fillMaxWidth().background(Color.Black)
                            .windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        // 左右同一黑底色彩；高度压缩（子项 fillMaxHeight 在自适应 Row 里会把整栏撑满全屏）
                        Row(Modifier.fillMaxWidth().height(60.dp)) {
                            Box(
                                Modifier.weight(1f).fillMaxHeight().background(
                                    Brush.verticalGradient(
                                        0f to Color(0xFF3D434C),
                                        1f to Color(0xFF14161B),
                                    ),
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Ios5TabBar(tabHighlight, showTopDivider = false, onNavigate = { navigateTopLevel(it) })
                            }
                            Box(
                                Modifier.width(panelWidth).fillMaxHeight().background(
                                    Brush.verticalGradient(
                                        0f to Color(0xFF3D434C),
                                        1f to Color(0xFF14161B),
                                    ),
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                com.aurora.music.ui.player.Ios5LandscapeTransport(
                                    state = playerState,
                                    onTogglePlay = { playerVM.togglePlay() },
                                    onNext = { playerVM.next() },
                                    onPrevious = { playerVM.previous() },
                                    onToggleLike = { playerVM.toggleLikeCurrent() },
                                    onToggleShuffle = { playerVM.toggleShuffle() },
                                    onCycleRepeat = { playerVM.cycleRepeat() },
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    introActive = introState.active,
                                    onIntroClick = { onIntroClick() },
                                )
                            }
                        }
                    }
                    } // BoxWithConstraints（右栏宽自适应）结束
                }
            } else {
                ContentFace(flip, true, true)

            // ---- 播放面（整页 iPod 播放器，翻转进入） ----
            // 播放页根自带空点按拦截触摸，漏不下去，不需要额外拦截层。
            // 动画结束后卸载，避免两棵树常驻耗性能。
            if (playerState.expanded || flip > 0.02f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        rotationY = flip * 180f - 180f
                        cameraDistance = 8 * flipDensity.density
                        alpha = if (flip >= 0.5f) 1f else 0f
                    },
            ) {
                if (playerState.hasTrack) {
                    Ios5PlayerPage(
                        state = playerState,
                        onCollapse = { playerVM.setExpanded(false) },
                        onTogglePlay = { playerVM.togglePlay() },
                        onNext = { playerVM.next() },
                        onPrevious = { playerVM.previous() },
                        onSeek = { playerVM.seekTo(it) },
                        onToggleLike = { playerVM.toggleLikeCurrent() },
                        onToggleShuffle = { playerVM.toggleShuffle() },
                        onCycleRepeat = { playerVM.cycleRepeat() },
                        onOpenQueue = { showQueue = true },
                        introActive = introState.active,
                        onIntroClick = { onIntroClick() },
                    )
                }
            }
            } // if expanded 播放面结束
            } // else 竖屏分支结束

            AnimatedVisibility(
                visible = showQueue,
                enter = slideInVertically(tween(300)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(160)),
            ) {
                com.aurora.music.ui.screens.player.QueueScreen(
                    queue = playerState.queue,
                    currentIndex = playerState.currentIndex,
                    isPlaying = playerState.isPlaying,
                    onJump = { playerVM.jumpTo(it) },
                    onRemove = { playerVM.removeFromQueue(it) },
                    onMove = { from, to -> playerVM.moveQueueItem(from, to) },
                    onClear = { playerVM.clearQueue() },
                    onSaveAsPlaylist = { name -> playerVM.saveQueueAsPlaylist(name) { confirm(it) } },
                    onClose = { showQueue = false },
                )
            }

            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp),
            )

            // 注册在 NavHost 之后 → 优先级高于主页面导航：
            // 竖屏播放页开着时返回先关播放页，再按才作用于主页面
            BackHandler(enabled = !landscape && playerState.expanded) { playerVM.setExpanded(false) }
        }
      } // BoxWithConstraints 横竖屏分支结束
    }

    BackHandler(enabled = showQueue) { showQueue = false }

    // keep liked flags warm for visible tracks
    LaunchedEffect(browseState.songs.size) {
        playerVM.checkLiked(browseState.songs.take(200).map { it.id })
    }
}
