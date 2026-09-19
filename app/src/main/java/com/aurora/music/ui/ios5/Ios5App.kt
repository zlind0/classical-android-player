package com.aurora.music.ui.ios5

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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

/**
 * 可见的播放面吃掉多余手势，防止点透到下面的内容面。
 * 不可见时必须彻底拿掉，否则全屏透明层会吞掉主界面所有触摸。
 */
private fun Modifier.blockTouch(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

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

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun confirm(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var lastTab by remember { mutableStateOf(Ios5Routes.HOME) }
    LaunchedEffect(currentRoute) {
        if (currentRoute in ios5TabRoutes) lastTab = currentRoute ?: Ios5Routes.HOME
    }
    val tabHighlight = if (currentRoute in ios5TabRoutes) currentRoute else lastTab

    var showQueue by remember { mutableStateOf(false) }

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
        // iOS5 翻转：内容面 / 播放面，前半程内容转走，后半程播放页转入
        val flipDensity = LocalDensity.current
        val flip by animateFloatAsState(
            targetValue = if (playerState.expanded) 1f else 0f,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "playerFlip",
        )
        Box(Modifier.fillMaxSize()) {
            // ---- 内容面 ----
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        rotationY = flip * 180f
                        cameraDistance = 8 * flipDensity.density
                        alpha = if (flip < 0.5f) 1f else 0f
                    },
            ) {
            Column(Modifier.fillMaxSize()) {
                // ---- 上栏：当前页面 ----
                // Mini 条是悬在 Tab 栏上方的覆盖层（56dp），有歌时内容区底部预留，
                // 否则各列表最后一个元素会被它盖住
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .then(if (playerState.hasTrack) Modifier.padding(bottom = 56.dp) else Modifier),
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
                Box(
                    Modifier.fillMaxWidth().background(Ios5Colors.tabBrush),
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        if (playerState.hasTrack && flip < 0.5f) {
                            Ios5MiniStrip(
                                state = playerState,
                                onExpand = { playerVM.setExpanded(true) },
                                onTogglePlay = { playerVM.togglePlay() },
                                onNext = { playerVM.next() },
                            )
                        }
                        Ios5TabBar(tabHighlight) { navigateTopLevel(it) }
                    }
                }
            }
            } // 内容面

            // ---- 播放面（整页 iPod 播放器，翻转进入） ----
            // 可见时吞掉多余手势；不可见时不挂任何手势，让触摸穿透回主界面。
            // 动画结束后卸载，避免两棵树常驻耗性能。
            if (playerState.expanded || flip > 0.02f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        rotationY = flip * 180f - 180f
                        cameraDistance = 8 * flipDensity.density
                        alpha = if (flip >= 0.5f) 1f else 0f
                    }
                    .then(if (flip >= 0.5f) Modifier.blockTouch() else Modifier),
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
                    )
                }
            }
            }

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
        }
    }

    BackHandler(enabled = playerState.expanded) { playerVM.setExpanded(false) }
    BackHandler(enabled = showQueue) { showQueue = false }

    // keep liked flags warm for visible tracks
    LaunchedEffect(browseState.songs.size) {
        playerVM.checkLiked(browseState.songs.take(200).map { it.id })
    }
}
