package com.aurora.music.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.aurora.music.navigation.Routes
import com.aurora.music.navigation.topLevelDestinations
import com.aurora.music.model.LibraryFilter
import com.aurora.music.ui.components.AmbientBackground
import com.aurora.music.ui.components.IosTabBar
import com.aurora.music.ui.components.MiniPlayer
import com.aurora.music.ui.screens.detail.DetailScreen
import com.aurora.music.ui.screens.home.HomeScreen
import com.aurora.music.ui.screens.library.LibraryScreen
import com.aurora.music.ui.screens.library.MoreScreen
import com.aurora.music.ui.screens.player.PlayerScreen
import com.aurora.music.ui.screens.player.SpeedPitchSheet
import com.aurora.music.ui.screens.search.SearchScreen
import com.aurora.music.ui.theme.auroraPanel
import com.aurora.music.ui.screens.settings.PlaybackSettingsScreen
import com.aurora.music.ui.screens.settings.SettingsScreen
import com.aurora.music.viewmodel.DetailViewModel
import com.aurora.music.viewmodel.HomeViewModel
import com.aurora.music.viewmodel.LibraryViewModel
import com.aurora.music.viewmodel.PlayerViewModel
import com.aurora.music.viewmodel.SearchViewModel
import kotlinx.coroutines.launch

@Composable
fun AuroraApp() {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container

    val navController = rememberNavController()
    val playerVM: PlayerViewModel = viewModel()

    val playerState by playerVM.state.collectAsStateWithLifecycle()
    val sessionReady by container.sessionReady.collectAsStateWithLifecycle()
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    val downloadsMap by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val downloadedIds = downloadsMap.keys
    // local-only fork: always the on-device library
    val localMode = true
    val serverTagEditing = false
    // pins are no longer scoped per connection
    val allPins by container.settingsStore.pins.collectAsStateWithLifecycle(initialValue = emptyList())
    val pins = allPins
    val gesturePrefs by container.settingsStore.gesturePrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.GesturePrefs())

    val downloadStates by container.downloadManager.states.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun confirm(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = androidx.compose.material3.SnackbarDuration.Short)
        }
    }

    val onDownload: (com.aurora.music.model.Song) -> Unit = {
        val already = container.downloadManager.isDownloaded(it.id)
        container.downloadManager.downloadSong(it)
        confirm(if (already) context.getString(R.string.msg_already_downloaded) else context.getString(R.string.msg_downloading, it.title))
    }
    val onRemoveDownload: (String) -> Unit = { container.downloadManager.removeDownload(it); confirm(context.getString(R.string.msg_removed_download)) }

    // re-pull likes on foreground so stars from other devices show up
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) playerVM.refreshLikes()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // active means still queued or in flight
    val activeDownloads = downloadStates.values.count {
        it is com.aurora.music.data.DownloadState.Queued || it is com.aurora.music.data.DownloadState.Downloading
    }
    val downloadProgress = downloadStates.values
        .mapNotNull { (it as? com.aurora.music.data.DownloadState.Downloading)?.progress }
        .let { if (it.isEmpty()) 0f else it.sum() / activeDownloads.coerceAtLeast(1) }
    // announce when a batch finishes active falls back to zero
    var hadActiveDownloads by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(activeDownloads) {
        if (activeDownloads > 0) {
            hadActiveDownloads = true
        } else if (hadActiveDownloads) {
            hadActiveDownloads = false
            val failed = downloadStates.values.count { it is com.aurora.music.data.DownloadState.Failed }
            snackbarHostState.showSnackbar(
                if (failed > 0) context.getString(R.string.msg_download_finished_failed, failed) else context.getString(R.string.msg_download_complete),
                duration = androidx.compose.material3.SnackbarDuration.Short,
            )
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // browse pages belong to the More tab; player highlights nothing
    val tabRoute = when {
        currentRoute == Routes.PLAYLISTS || currentRoute == Routes.ARTISTS ||
            currentRoute == Routes.MORE || currentRoute?.startsWith("browse/") == true -> currentRoute?.let {
            when {
                it.startsWith("browse/") -> Routes.MORE
                else -> it
            }
        }
        currentRoute in topLevelDestinations.map { it.route } -> currentRoute
        else -> null
    }
    val showChrome = currentRoute != null

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showVisualizer by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    fun navigateTopLevel(route: String) {
        if (currentRoute == route) return
        container.haptic()
        // tab press lands on the tab root pop pushed detail/settings dont restore the sub-stack
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    fun openDetail(kind: String, id: String) = navController.navigate(Routes.detail(kind, id))
    fun playAlbum(id: String) = scope.launch {
        container.repository.detail("album", id)?.let { if (it.tracks.isNotEmpty()) playerVM.playCollection("album", id, it.tracks, 0, it.info.songCount) }
    }
    fun playById(id: String) = scope.launch {
        container.repository.songFor(id)?.let { playerVM.play(it) }
    }

    // wait for the local-session bootstrap before choosing a start destination
    if (sessionReady == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(90.dp))
        }
        return
    }
    val startDestination = Routes.HOME

    // iOS-style shell: no drawer. MiniPlayer + metal tab bar, full-bleed to the
    // system gesture area (no navigation inset gap).
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showChrome) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (activeDownloads > 0) {
                            DownloadProgressBanner(count = activeDownloads, progress = downloadProgress)
                        }
                        if (playerState.hasTrack && currentRoute != Routes.PLAYER) {
                            MiniPlayer(
                                state = playerState,
                                onExpand = { navController.navigate(Routes.PLAYER) },
                                onTogglePlay = { playerVM.togglePlay() },
                                onToggleLike = { playerVM.toggleLikeCurrent() },
                                onNext = { playerVM.next() },
                            )
                        }
                        IosTabBar(selectedRoute = tabRoute) { navigateTopLevel(it) }
                    }
                }
            },
        ) { inner ->
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                    // A drawer action followed quickly by Back interrupts Navigation
                    // 2.8's default 700 ms fade and can leave the returned page invisible.
                    // The drawer and player animate independently; route content stays visible.
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None },
                ) {
                    composable(Routes.HOME) {
                        val homeVM: HomeViewModel = viewModel()
                        val homeState by homeVM.state.collectAsStateWithLifecycle()
                        HomeScreen(
                            contentPadding = inner,
                            state = homeState,
                            username = session?.username ?: "",
                            avatarUrl = session?.imageUrl ?: "",
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onOpenDetail = { kind, id -> openDetail(kind, id) },
                            onPlayAlbum = { playAlbum(it) },
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                        )
                    }
                    composable(Routes.SEARCH) {
                        val searchVM: SearchViewModel = viewModel()
                        val searchState by searchVM.state.collectAsStateWithLifecycle()
                        val recentSearches by searchVM.recentSearches.collectAsStateWithLifecycle()
                        androidx.compose.runtime.LaunchedEffect(searchState.results.songs.size) {
                            playerVM.checkLiked(searchState.results.songs.map { it.id })
                        }
                        SearchScreen(
                            contentPadding = inner,
                            state = searchState,
                            likedIds = playerState.likedIds,
                            currentSongId = playerState.current.id,
                            isPlaying = playerState.isPlaying,
                            onQuery = searchVM::onQuery,
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                            onAddToQueue = { playerVM.addToQueue(it); confirm(context.getString(R.string.msg_added_to_queue)) },
                            onPlayNext = { playerVM.playNext(it); confirm(context.getString(R.string.msg_playing_next)) },
                            onToggleLike = { playerVM.toggleLike(it) },
                            onOpenDetail = { kind, id -> openDetail(kind, id) },
                            downloadedIds = downloadedIds,
                            onDownload = onDownload,
                            onRemoveDownload = onRemoveDownload,
                            canDownload = !localMode,
                            recentSearches = recentSearches,
                            onRecentClick = { searchVM.onQuery(it) },
                            onRemoveRecent = { searchVM.removeRecent(it) },
                            onClearRecents = { searchVM.clearRecents() },
                            onCommitSearch = { searchVM.commit() },
                        )
                    }
                    // Shared library page: full mode for LIBRARY, embedded for tab/browse pages.
                    @Composable
                    fun LibraryPage(
                        inner: PaddingValues,
                        forceFilter: LibraryFilter? = null,
                        showTabs: Boolean = true,
                        titleOverride: String? = null,
                    ) {
                        val libraryVM: LibraryViewModel = viewModel()
                        val libraryState by libraryVM.state.collectAsStateWithLifecycle()
                        androidx.compose.runtime.LaunchedEffect(forceFilter) {
                            forceFilter?.let { libraryVM.setFilter(it) }
                        }
                        // m3u export staged here written once the user picks a file
                        var pendingM3u by remember { mutableStateOf<String?>(null) }
                        val exportM3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
                            val text = pendingM3u
                            pendingM3u = null
                            if (uri != null && text != null) {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val ok = runCatching {
                                        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
                                    }.getOrDefault(false)
                                    confirm(if (ok) context.getString(R.string.msg_playlist_exported) else context.getString(R.string.msg_export_failed))
                                }
                            }
                        }
                        val importM3uLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                            if (uri != null) scope.launch {
                                val (text, displayName) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val t = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                                    // saf uris carry opaque doc ids the human file name needs a query
                                    val n = runCatching {
                                        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                                            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                                        }
                                    }.getOrNull()
                                    t to n
                                }
                                val entries = text?.let { com.aurora.music.data.M3u.parse(it) }.orEmpty()
                                if (entries.isEmpty()) { confirm(context.getString(R.string.msg_no_tracks_in_file)) } else {
                                    val name = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_import_default)
                                    confirm(context.getString(R.string.msg_importing, entries.size))
                                    val result = container.repository.importPlaylist(name, entries)
                                    if (result == null) confirm(context.getString(R.string.msg_import_failed)) else {
                                        confirm(context.getString(R.string.msg_matched, result.first, result.second))
                                        libraryVM.load()
                                    }
                                }
                            }
                        }
                        LibraryScreen(
                            contentPadding = inner,
                            state = libraryState,
                            username = session?.username ?: "",
                            likedIds = playerState.likedIds,
                            currentSongId = playerState.current.id,
                            isPlaying = playerState.isPlaying,
                            onFilter = libraryVM::setFilter,
                            onSort = libraryVM::setSort,
                            onToggleLayout = libraryVM::toggleLayout,
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                            onAddToQueue = { playerVM.addToQueue(it); confirm(context.getString(R.string.msg_added_to_queue)) },
                            onPlayNext = { playerVM.playNext(it); confirm(context.getString(R.string.msg_playing_next)) },
                            onToggleLike = { playerVM.toggleLike(it) },
                            onOpenDetail = { kind, id -> openDetail(kind, id) },
                            downloadedIds = downloadedIds,
                            onDownload = onDownload,
                            onRemoveDownload = onRemoveDownload,
                            onOpenSearch = { navController.navigate(Routes.SEARCH) },
                            onCreatePlaylist = { name -> scope.launch { container.repository.createPlaylist(name); libraryVM.load() } },
                            onCreateSmart = { navController.navigate(Routes.smartEdit()) },
                            onEditSmart = { id -> navController.navigate(Routes.smartEdit(id)) },
                            onDeleteSmart = { id -> scope.launch { container.settingsStore.deleteSmartPlaylist(id) } },
                            onImportM3u = { importM3uLauncher.launch(arrayOf("*/*")) },
                            onExportPlaylist = { id, kind, title -> scope.launch {
                                val text = container.repository.exportPlaylist(kind, id)
                                if (text == null) confirm(context.getString(R.string.msg_nothing_to_export)) else {
                                    pendingM3u = text
                                    exportM3uLauncher.launch("$title.m3u8")
                                }
                            } },
                            onOpenFolders = { navController.navigate(Routes.folders()) },
                            onPlayCollection = { id, kind -> scope.launch { container.repository.detail(kind, id)?.let { d -> if (d.tracks.isNotEmpty()) playerVM.playCollection(kind, id, d.tracks, 0, d.info.songCount) } } },
                            onShuffleCollection = { id, kind -> scope.launch { container.repository.detail(kind, id)?.let { d -> if (d.tracks.isNotEmpty()) playerVM.shuffleCollection(kind, id, d.tracks, d.info.songCount) } } },
                            onQueueCollection = { id, kind -> scope.launch {
                                val tracks = container.repository.detail(kind, id)?.tracks.orEmpty()
                                tracks.forEach { playerVM.addToQueue(it) }
                                if (tracks.isNotEmpty()) confirm(context.getString(R.string.msg_added_n_to_queue, tracks.size))
                            } },
                            onToggleLikeKind = { id, kind -> playerVM.toggleLike(id, kind) },
                            onDeletePlaylist = { id -> scope.launch { container.repository.deletePlaylist(id); libraryVM.load() } },
                            canDownload = !localMode,
                            pins = pins,
                            onEditTags = { song -> navController.navigate(Routes.tagEdit(song.id)) },
                            serverTagEditing = serverTagEditing,
                            onLoadMoreSongs = libraryVM::loadMoreSongs,
                            onPlayAllSongs = { shuffle ->
                                scope.launch {
                                    val all = libraryVM.fullSortedSongs()
                                    if (all.isEmpty()) return@launch
                                    if (shuffle) playerVM.shufflePlay(all) else playerVM.playAll(all, 0)
                                }
                            },
                            onPlaySong = { song ->
                                scope.launch {
                                    val all = libraryVM.fullSortedSongs()
                                    val idx = all.indexOfFirst { it.id == song.id }
                                    // fall back to the single track if the full list somehow lacks it (never play the wrong index)
                                    if (idx >= 0) playerVM.playAll(all, idx) else playerVM.playAll(listOf(song), 0)
                                }
                            },
                            showTabs = showTabs,
                            forceFilter = forceFilter,
                            titleOverride = titleOverride,
                        )
                    }

                    composable(Routes.PLAYLISTS) {
                        LibraryPage(
                            inner,
                            forceFilter = LibraryFilter.PLAYLISTS,
                            showTabs = false,
                            titleOverride = stringResource(R.string.tab_playlists),
                        )
                    }
                    composable(Routes.ARTISTS) {
                        LibraryPage(
                            inner,
                            forceFilter = LibraryFilter.ARTISTS,
                            showTabs = false,
                            titleOverride = stringResource(R.string.tab_artists),
                        )
                    }
                    composable(Routes.MORE) {
                        MoreScreen(
                            contentPadding = inner,
                            onBrowse = { filter ->
                                val kind = when (filter) {
                                    LibraryFilter.SONGS -> "songs"
                                    LibraryFilter.ALBUMS -> "albums"
                                    LibraryFilter.GENRES -> "genres"
                                    else -> "composers"
                                }
                                navController.navigate(Routes.browse(kind))
                            },
                            onOpenFolders = { navController.navigate(Routes.folders()) },
                        )
                    }
                    composable(
                        Routes.BROWSE,
                        arguments = listOf(androidx.navigation.navArgument("kind") { defaultValue = "songs" }),
                    ) { entry ->
                        val kind = entry.arguments?.getString("kind").orEmpty()
                        val (filter, title) = when (kind) {
                            "albums" -> LibraryFilter.ALBUMS to stringResource(R.string.more_albums)
                            "genres" -> LibraryFilter.GENRES to stringResource(R.string.more_genres)
                            "composers" -> LibraryFilter.COMPOSERS to stringResource(R.string.more_composers)
                            else -> LibraryFilter.SONGS to stringResource(R.string.more_songs)
                        }
                        LibraryPage(inner, forceFilter = filter, showTabs = false, titleOverride = title)
                    }
                    composable(
                        Routes.FOLDERS,
                        arguments = listOf(
                            androidx.navigation.navArgument("fid") { defaultValue = "" },
                            androidx.navigation.navArgument("title") { defaultValue = "" },
                        ),
                    ) { entry ->
                        val fid = entry.arguments?.getString("fid").orEmpty()
                        val folderTitle = entry.arguments?.getString("title").orEmpty()
                        val folderVM: com.aurora.music.viewmodel.FolderViewModel = viewModel()
                        androidx.compose.runtime.LaunchedEffect(fid) { folderVM.load(fid) }
                        val folderState by folderVM.state.collectAsStateWithLifecycle()
                        androidx.compose.runtime.LaunchedEffect(folderState.content?.songs?.size) {
                            folderState.content?.songs?.let { ss -> playerVM.checkLiked(ss.map { it.id }) }
                        }
                        com.aurora.music.ui.screens.library.FolderScreen(
                            contentPadding = inner,
                            title = folderTitle,
                            loading = folderState.loading,
                            content = folderState.content,
                            likedIds = playerState.likedIds,
                            currentSongId = playerState.current.id,
                            isPlaying = playerState.isPlaying,
                            onBack = { navController.popBackStack() },
                            onOpenFolder = { id, name -> navController.navigate(Routes.folders(id, name)) },
                            onPlayAll = { songs, index -> playerVM.playAll(songs, index) },
                            onShufflePlay = { songs -> playerVM.shufflePlay(songs) },
                            onAddToQueue = { playerVM.addToQueue(it); confirm(context.getString(R.string.msg_added_to_queue)) },
                            onPlayNext = { playerVM.playNext(it); confirm(context.getString(R.string.msg_playing_next)) },
                            onToggleLike = { playerVM.toggleLike(it) },
                            onOpenDetail = { k, i -> openDetail(k, i) },
                            downloadedIds = downloadedIds,
                            onDownload = onDownload,
                            onRemoveDownload = onRemoveDownload,
                            canDownload = !localMode,
                            onEditTags = { song -> navController.navigate(Routes.tagEdit(song.id)) },
                            serverTagEditing = serverTagEditing,
                        )
                    }
                    composable(
                        Routes.SMART_EDIT,
                        arguments = listOf(androidx.navigation.navArgument("id") { defaultValue = "" }),
                    ) { entry ->
                        val smartId = entry.arguments?.getString("id").orEmpty()
                        val smartVM: com.aurora.music.viewmodel.SmartPlaylistViewModel = viewModel()
                        androidx.compose.runtime.LaunchedEffect(smartId) { smartVM.load(smartId) }
                        val smartState by smartVM.state.collectAsStateWithLifecycle()
                        com.aurora.music.ui.screens.library.SmartPlaylistEditScreen(
                            contentPadding = inner,
                            playlist = smartState,
                            isNew = smartId.isBlank(),
                            onUpdate = smartVM::update,
                            onSave = { smartVM.save { navController.popBackStack() } },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.DETAIL) { entry ->
                        val kind = entry.arguments?.getString("kind").orEmpty()
                        val id = entry.arguments?.getString("id").orEmpty()
                        val detailVM: DetailViewModel = viewModel()
                        androidx.compose.runtime.LaunchedEffect(kind, id) { detailVM.load(kind, id) }
                        val detailState by detailVM.state.collectAsStateWithLifecycle()
                        // resolve liked state for visible tracks so hearts show beyond page 1
                        androidx.compose.runtime.LaunchedEffect(detailState.data?.tracks?.size) {
                            detailState.data?.tracks?.let { ts -> playerVM.checkLiked(ts.map { it.id }) }
                        }
                        DetailScreen(
                            contentPadding = inner,
                            state = detailState,
                            likedIds = playerState.likedIds,
                            currentSongId = playerState.current.id,
                            isPlaying = playerState.isPlaying,
                            onBack = { navController.popBackStack() },
                            onPlayAll = { songs, index -> playerVM.playCollection(kind, id, songs, index, detailState.data?.info?.songCount ?: songs.size) },
                            onShufflePlay = { songs -> playerVM.shuffleCollection(kind, id, songs, detailState.data?.info?.songCount ?: songs.size) },
                            onAddToQueue = { playerVM.addToQueue(it); confirm(context.getString(R.string.msg_added_to_queue)) },
                            onPlayNext = { playerVM.playNext(it); confirm(context.getString(R.string.msg_playing_next)) },
                            onToggleLike = { playerVM.toggleLike(it) },
                            onOpenDetail = { k, i -> openDetail(k, i) },
                            itemKind = kind,
                            isItemLiked = playerState.likedIds.contains(id),
                            onToggleItemLike = { playerVM.toggleLike(id, kind) },
                            downloadedIds = downloadedIds,
                            onDownload = onDownload,
                            onRemoveDownload = onRemoveDownload,
                            onDownloadAll = {
                                val d = detailState.data
                                if (d != null) {
                                    if (kind == "album" || kind == "playlist") {
                                        container.downloadManager.downloadCollection(id, kind, d.info.title, d.info.subtitle, d.info.artUrl, d.tracks)
                                    } else {
                                        container.downloadManager.downloadAll(d.tracks)
                                    }
                                    val n = d.tracks.count { !container.downloadManager.isDownloaded(it.id) }
                                    confirm(if (n > 0) context.getString(R.string.msg_downloading_n, n, if (n == 1) "" else "s") else context.getString(R.string.msg_already_downloaded))
                                }
                            },
                            onRemoveDownloads = {
                                if (kind == "album" || kind == "playlist") container.downloadManager.removeCollection(id)
                                detailState.data?.tracks?.forEach { container.downloadManager.removeDownload(it.id) }
                            },
                            onEditPlaylist = { name, desc ->
                                scope.launch { container.repository.updatePlaylist(id, name, desc); detailVM.reload(kind, id) }
                            },
                            onDeletePlaylist = {
                                scope.launch { container.repository.deletePlaylist(id); navController.popBackStack() }
                            },
                            onLoadMore = { detailVM.loadMore() },
                            canDownload = !localMode,
                            isPinned = pins.any { it.id == id && it.kind == kind },
                            onTogglePin = {
                                val d = detailState.data
                                val pin = com.aurora.music.data.Pin(
                                    id = id, kind = kind,
                                    title = d?.info?.title ?: kind,
                                    subtitle = d?.info?.subtitle ?: "",
                                    coverUrl = d?.info?.artUrl ?: "",
                                    serverId = "",
                                )
                                scope.launch { container.settingsStore.togglePin(pin) }
                            },
                            onEditTags = { song -> navController.navigate(Routes.tagEdit(song.id)) },
                            serverTagEditing = serverTagEditing,
                            artistInfo = detailState.artistInfo,
                        )
                    }
                    composable(
                        Routes.TAG_EDIT,
                        arguments = listOf(androidx.navigation.navArgument("songId") { defaultValue = "" }),
                    ) { entry ->
                        val songId = entry.arguments?.getString("songId").orEmpty()
                        val tagVM: com.aurora.music.viewmodel.TagEditViewModel = viewModel()
                        androidx.compose.runtime.LaunchedEffect(songId) { tagVM.load(songId) }
                        val tagState by tagVM.state.collectAsStateWithLifecycle()
                        com.aurora.music.ui.screens.detail.TagEditScreen(
                            contentPadding = inner,
                            state = tagState,
                            onEdit = tagVM::edit,
                            onMatch = tagVM::matchOnline,
                            onApplyMatch = tagVM::applyMatch,
                            // auto-identify fingerprints the decodable local file not for server items
                            onIdentify = if (container.acoustId.available && tagState.localFile) ({ tagVM.identify() }) else null,
                            identifying = tagState.identifying,
                            onBack = { navController.popBackStack() },
                            confirm = { confirm(it) },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            contentPadding = inner,
                            username = session?.username ?: "",
                            server = session?.server ?: "",
                            onBack = { navController.popBackStack() },
                            onOpenPlayback = { navController.navigate(Routes.SETTINGS_PLAYBACK) },
                            onOpenEq = { navController.navigate(Routes.SETTINGS_EQ) },
                            onOpenVisualizer = { navController.navigate(Routes.SETTINGS_VISUALIZER) },
                            onOpenSonic = { navController.navigate(Routes.SETTINGS_SONIC) },
                            onOpenSources = { navController.navigate(Routes.SETTINGS_SOURCES) },
                            onOpenDownloads = { navController.navigate(Routes.SETTINGS_STORAGE) },
                            onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                            onOpenGestures = { navController.navigate(Routes.SETTINGS_GESTURES) },
                            onOpenIntegrations = { navController.navigate(Routes.SETTINGS_INTEGRATIONS) },
                            onOpenPermissions = { navController.navigate(Routes.SETTINGS_PERMISSIONS) },
                            onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
                            onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
                            onOpenHistory = { navController.navigate(Routes.HISTORY) },
                            onOpenStats = { navController.navigate(Routes.STATS) },
                            onOpenDuplicates = { navController.navigate(Routes.DUPLICATES) },
                        )
                    }
                    composable(Routes.SETTINGS_PLAYBACK) {
                        PlaybackSettingsScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS_BACKUP) {
                        com.aurora.music.ui.screens.settings.BackupScreen(
                            contentPadding = inner,
                            onBack = { navController.popBackStack() },
                            confirm = { confirm(it) },
                        )
                    }
                    composable(Routes.SETTINGS_GESTURES) {
                        com.aurora.music.ui.screens.settings.GesturesSettingsScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS_INTEGRATIONS) {
                        com.aurora.music.ui.screens.settings.IntegrationsSettingsScreen(
                            contentPadding = inner,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.SETTINGS_APPEARANCE) {
                        com.aurora.music.ui.screens.settings.AppearanceScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS_EQ) {
                        com.aurora.music.ui.screens.settings.EqualizerScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS_VISUALIZER) {
                        com.aurora.music.ui.screens.settings.VisualizerSettingsScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS_SONIC) {
                        com.aurora.music.ui.screens.settings.SonicSettingsScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS_SOURCES) {
                        com.aurora.music.ui.screens.settings.MusicSourcesScreen(
                            contentPadding = inner,
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
                    composable(Routes.SETTINGS_PERMISSIONS) {
                        com.aurora.music.ui.screens.settings.PermissionsScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS_STORAGE) {
                        com.aurora.music.ui.screens.settings.StorageSettingsScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.SETTINGS_ABOUT) {
                        com.aurora.music.ui.screens.settings.AboutSettingsScreen(contentPadding = inner, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.HISTORY) {
                        com.aurora.music.ui.screens.stats.ListeningHistoryScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) })
                    }
                    composable(Routes.DUPLICATES) {
                        val dupVM: com.aurora.music.viewmodel.DuplicatesViewModel = viewModel()
                        val dupState by dupVM.state.collectAsStateWithLifecycle()
                        com.aurora.music.ui.screens.library.DuplicatesScreen(
                            contentPadding = inner,
                            loading = dupState.loading,
                            scanned = dupState.scanned,
                            groups = dupState.groups,
                            currentSongId = playerState.current.id,
                            onBack = { navController.popBackStack() },
                            onPlay = { s -> playerVM.playAll(listOf(s), 0) },
                        )
                    }
                    composable(Routes.STATS) {
                        com.aurora.music.ui.screens.stats.ListeningStatsScreen(contentPadding = inner, onBack = { navController.popBackStack() }, onPlay = { playById(it) }, onOpenDetail = { k, i -> openDetail(k, i) })
                    }
                    composable(Routes.PLAYER) {
                        PlayerScreen(
                            state = playerState,
                            onCollapse = { navController.popBackStack() },
                            onTogglePlay = { playerVM.togglePlay() },
                            onNext = { playerVM.next() },
                            onPrevious = { playerVM.previous() },
                            onSeek = { playerVM.seekTo(it) },
                            onToggleLike = { playerVM.toggleLikeCurrent() },
                            onToggleShuffle = { playerVM.toggleShuffle() },
                            onCycleRepeat = { playerVM.cycleRepeat() },
                            onOpenSpeedPitch = { showSpeedSheet = true },
                            onOpenQueue = { showQueue = true },
                            onGoToAlbum = {
                                val id = playerState.current.albumId
                                if (id.isNotBlank()) { openDetail("album", id) }
                            },
                            onGoToArtist = {
                                val id = playerState.current.artistId
                                if (id.isNotBlank()) { openDetail("artist", id) }
                            },
                            onOpenOutput = { showOutput = true },
                            onOpenSleep = { showSleep = true },
                            onOpenVisualizer = { showVisualizer = true },
                            onOpenEqualizer = { navController.navigate(Routes.SETTINGS_EQ) },
                            onSonicRadio = { playerVM.startSonicRadio(onResult = { confirm(it) }) },
                            onAutoDj = { playerVM.startAutoDj(onResult = { confirm(it) }) },
                            gestures = gesturePrefs,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showQueue,
                enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(animationSpec = tween(260)) { it } + fadeOut(tween(160)),
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

            AnimatedVisibility(
                visible = showVisualizer,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180)),
            ) {
                com.aurora.music.ui.screens.visualizer.VisualizerScreen(
                    state = playerState,
                    onClose = { showVisualizer = false },
                )
            }
        }

    if (showSpeedSheet) {
        SpeedPitchSheet(
            speed = playerState.speed,
            pitch = playerState.pitch,
            matchPitch = playerState.matchPitch,
            onSpeed = { playerVM.setSpeed(it) },
            onPitch = { playerVM.setPitch(it) },
            onMatchPitch = { playerVM.setMatchPitch(it) },
            onReset = { playerVM.resetSpeedPitch() },
            onDismiss = { showSpeedSheet = false },
        )
    }

    if (showOutput) {
        com.aurora.music.ui.screens.player.OutputDeviceSheet(
            currentId = playerVM.preferredDeviceId(),
            onSelect = { playerVM.setPreferredDevice(it) },
            onDismiss = { showOutput = false },
        )
    }
    if (showSleep) {
        com.aurora.music.ui.screens.player.SleepTimerSheet(
            currentMinutes = playerState.sleepTimerMinutes,
            endOfTrack = playerState.sleepEndOfTrack,
            onSelect = { playerVM.setSleepTimer(it) },
            onEndOfTrack = { playerVM.setSleepEndOfTrack() },
            onDismiss = { showSleep = false },
        )
    }

    BackHandler(enabled = showQueue) { showQueue = false }
    BackHandler(enabled = showVisualizer) { showVisualizer = false }
}

@Composable
private fun DownloadProgressBanner(count: Int, progress: Float) {
    val classic = com.aurora.music.ui.theme.LocalUiPrefs.current.themeStyle == com.aurora.music.data.ThemeStyle.AURORA
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "dlProgress",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (classic) Modifier.clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                else Modifier.auroraPanel(MaterialTheme.shapes.large))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Downloading, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.msg_downloading_n, count, if (count == 1) "" else "s"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "${(animated * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
