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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.aurora.music.navigation.filledRoute
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
import com.aurora.music.ui.player.Ios5AudiobookPlayerPage
import com.aurora.music.ui.player.Ios5PlayerPage
import com.aurora.music.ui.search.Ios5SearchPage
import com.aurora.music.ui.tabs.ArtistsTab
import com.aurora.music.ui.tabs.AudiobooksTab
import com.aurora.music.ui.tabs.EbooksTab
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
    val abooks by container.audiobookStore.books.collectAsStateWithLifecycle()
    val abookPrefs by container.settingsStore.audiobookPrefs.collectAsStateWithLifecycle(
        initialValue = com.aurora.music.data.SettingsStore.AudiobookPrefs(),
    )
    val sessionReady by container.sessionReady.collectAsStateWithLifecycle()
    val uiPrefs by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.UiPrefs())
    val remoteLibraries by container.remoteLibrary.libraries.collectAsStateWithLifecycle()
    val remoteEbookRecents by container.remoteLibrary.ebookRecents.collectAsStateWithLifecycle()
    val remoteAbookPlays by container.remoteLibrary.abookPlays.collectAsStateWithLifecycle()
    // 离线设备不展示资料库（但已入队的歌继续播，播放器不读这份过滤后的列表）。
    val onlineRemoteLibraries = remember(remoteLibraries) {
        remoteLibraries.filter { it.online }
    }
    val onlineDeviceIds = remember(onlineRemoteLibraries) {
        onlineRemoteLibraries.map { it.deviceId }.toSet()
    }
    val visibleRemoteEbookRecents = remember(remoteEbookRecents, onlineDeviceIds) {
        remoteEbookRecents.filter { it.deviceId in onlineDeviceIds }
    }
    val remoteAbookRecents = remember(remoteLibraries, remoteAbookPlays) {
        container.remoteLibrary.abookRecents()
    }
    val visibleRemoteAbookRecents = remember(remoteAbookRecents, onlineDeviceIds) {
        remoteAbookRecents.filter { it.deviceId in onlineDeviceIds }
    }
    // 远程艺人/专辑的详情页内容（路由只带一个固定 route，内容由这里提供）
    var remoteDetail by remember { mutableStateOf<RemoteDetailTarget?>(null) }

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
    // 网页投屏常驻（启动即开）：播放器状态与当前清单全局推给网页。
    // 放在 Ios5App 而不是阅读页，没开阅读页时网页也能显示“正在播放 + 当前清单”。
    // 必须直接收 VM 的 StateFlow：上面的 playerState 用的是 collectAsStateWithLifecycle，
    // 锁屏（Activity STOPPED）后会停止收集，投屏状态就冻在锁屏那一刻——网页每 2 秒把
    // 音频拽回那个死位置，听起来像“几秒的死循环”。VM 里的 ticker 在后台照常跑。
    LaunchedEffect(playerVM) {
        playerVM.state.collect { st ->
            container.ebookCast.updateMusic(st.current, st.positionSec, st.isPlaying)
            container.ebookCast.updateQueue(st.queue, st.currentIndex)
            container.ebookCast.updateTransport(st.shuffle, st.repeat.name, st.isCurrentLiked)
        }
    }
    // 接收端（被投屏的那台）回传的走带指令 → 本机播放器
    LaunchedEffect(container) {
        container.ebookCast.commands.collect { cmd ->
            when (cmd.type) {
                com.aurora.music.data.cast.CastCommandType.TOGGLE -> playerVM.togglePlay()
                com.aurora.music.data.cast.CastCommandType.PLAY -> playerVM.play()
                com.aurora.music.data.cast.CastCommandType.PAUSE -> playerVM.pause()
                com.aurora.music.data.cast.CastCommandType.NEXT -> playerVM.next()
                com.aurora.music.data.cast.CastCommandType.PREV -> playerVM.previous()
                com.aurora.music.data.cast.CastCommandType.JUMP -> if (cmd.index >= 0) playerVM.jumpTo(cmd.index)
                com.aurora.music.data.cast.CastCommandType.SEEK -> {
                    val dur = playerVM.state.value.durationSec
                    if (dur > 0) playerVM.seekTo((cmd.positionMs / 1000f / dur).coerceIn(0f, 1f))
                }
                com.aurora.music.data.cast.CastCommandType.SHUFFLE -> playerVM.toggleShuffle()
                com.aurora.music.data.cast.CastCommandType.REPEAT -> playerVM.cycleRepeat()
                com.aurora.music.data.cast.CastCommandType.LIKE -> playerVM.toggleLikeCurrent()
                com.aurora.music.data.cast.CastCommandType.REJECT,
                com.aurora.music.data.cast.CastCommandType.STOP,
                -> Unit
            }
        }
    }
    // 投屏提示（对方拒绝 / 连接失败 / 已断开）
    LaunchedEffect(container) {
        container.ebookCast.events.collect { confirm(it) }
    }
    // 本机正在接收别人的投屏：先停掉本地音乐，避免两边同时出声
    val receiveState by container.castReceiver.state.collectAsStateWithLifecycle()
    val receivingCast = receiveState is com.aurora.music.data.cast.CastReceiveState.Music ||
        receiveState is com.aurora.music.data.cast.CastReceiveState.Ebook
    LaunchedEffect(receivingCast) {
        if (receivingCast) playerVM.pause()
    }
    // 外部打开电子书（文件关联）：导入完成后跳阅读页；失败则提示
    val pendingEbook by container.ebookStore.openRequest.collectAsStateWithLifecycle()
    val ebookNotice by container.ebookStore.notice.collectAsStateWithLifecycle()
    LaunchedEffect(pendingEbook, sessionReady) {
        val p = pendingEbook
        if (p != null && sessionReady != null) {
            container.ebookStore.takeOpenRequest()
            navController.navigate(Ios5Routes.ebookReader(p))
        }
    }
    LaunchedEffect(ebookNotice) {
        val n = ebookNotice
        if (n != null) {
            container.ebookStore.takeNotice()
            confirm(n)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 冷启动恢复到退出前的返回栈（滑掉任务/被系统杀进程时 savedInstanceState 不一定存在）
    var navRestored by rememberSaveable { mutableStateOf(false) }
    var lastTab by rememberSaveable {
        mutableStateOf(container.uiState.lastTab().ifBlank { Ios5Routes.HOME })
    }
    val initialNavRoutes = remember { container.uiState.navRoutes() }
    // 当前所在 Tab（由返回栈推出），用于“再点同一个 Tab 回到根页”和详情页的高亮。
    // 初值优先从保存的返回栈推，避免冷启动先亮首页再跳。
    var currentTab by remember {
        mutableStateOf(
            initialNavRoutes.lastOrNull { it in ios5TabRoutes }
                ?: container.uiState.lastTab().ifBlank { Ios5Routes.HOME },
        )
    }
    LaunchedEffect(currentRoute, navRestored) {
        if (navRestored && currentRoute in ios5TabRoutes) lastTab = currentRoute ?: Ios5Routes.HOME
    }
    LaunchedEffect(lastTab) { container.uiState.saveLastTab(lastTab) }
    // 高亮跟着“当前页属于哪个 Tab”走：在艺人 Tab 的专辑详情上也要亮艺人，
    // 所以详情页用 currentTab（从返回栈推出来的），不能用只在 Tab 根页更新的 lastTab
    val tabHighlight = if (currentRoute in ios5TabRoutes) currentRoute else currentTab
    // 阅读器全屏独占：不带 tab/mini 条，不参与翻转动画
    val isEbookReader = currentRoute?.startsWith("ebook_reader") == true
    // 是否真的有要重放的返回栈（只有首页时不用盖加载圈）
    val hasNavToRestore = remember(initialNavRoutes) {
        initialNavRoutes.size > 1 || initialNavRoutes.firstOrNull()?.let { it != Ios5Routes.HOME } == true
    }
    LaunchedEffect(navController) {
        if (!navRestored) {
            // 从外部“打开方式”直接进来的书优先：别先把旧阅读页恢复出来再叠一层
            if (container.ebookStore.openRequest.value != null) {
                navRestored = true
                return@LaunchedEffect
            }
            val saved = initialNavRoutes
            if (saved.isNotEmpty()) {
                val tab = saved.first()
                // 首页是 NavHost 起点；恢复的 Tab 不是首页时先补上它。
                // 故意把首页留在栈底：Tab 切换靠 popUpTo(首页){saveState} 兜底，
                // 栈底没有首页的话连续切 Tab 会把旧 Tab 越攒越多。
                if (tab in ios5TabRoutes && tab != Ios5Routes.HOME) {
                    runCatching { navController.navigate(tab) { launchSingleTop = true } }
                }
                saved.drop(1).forEach { route ->
                    runCatching { navController.navigate(route) { launchSingleTop = true } }
                }
            }
            navRestored = true
        }
    }
    // 每次导航都记下完整返回栈（含参数），下次冷启动原样重建
    LaunchedEffect(navController, navRestored) {
        if (!navRestored) return@LaunchedEffect
        navController.currentBackStack.collect { entries ->
            val routes = entries.mapNotNull { it.filledRoute() }
            if (routes.isNotEmpty()) container.uiState.saveNav(routes)
            // 当前所在 Tab = 返回栈里最靠上的那个 Tab 页（详情页归它管）。
            // 注意必须取最后一个：首页永远是栈底，取第一个会永远得到“首页”。
            routes.lastOrNull { it in ios5TabRoutes }?.let { currentTab = it }
            android.util.Log.i("AuroraNav", "stack=$routes tab=$currentTab last=$lastTab")
        }
    }
    // 播放页是全屏翻转页，属于页面现场的一部分
    var expandedRestored by remember { mutableStateOf(false) }
    LaunchedEffect(playerState.expanded) {
        if (expandedRestored) container.uiState.savePlayerExpanded(playerState.expanded)
    }
    // 冷启动恢复队列后再把播放页翻回来（只做一次）
    LaunchedEffect(playerState.hasTrack, navRestored) {
        if (!expandedRestored && navRestored && playerState.hasTrack) {
            expandedRestored = true
            if (container.uiState.playerExpanded()) playerVM.setExpanded(true)
        }
    }
    // 退到后台/离开时强制刷盘（连续滚动/播放进度最多丢最后 1 秒内的变化）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                container.uiState.flushNow()
                playerVM.persistNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showQueue by remember { mutableStateOf(false) }

    // 横向合并顶栏的上报宿主：NavHost 内页面的 Ios5NavBar 把标题/返回/搜索交到这里统一渲染
    val topBarHost = remember { TopBarHost() }

    fun navigateTopLevel(route: String) {
        android.util.Log.i("AuroraNav", "tap $route cur=$currentRoute tab=$currentTab last=$lastTab")
        if (currentRoute == route) return
        container.haptic()
        // 再点当前 Tab 的图标：回到这个 Tab 的根页（而不是把刚存下的详情页又还原回来）
        if (currentTab == route && runCatching { navController.popBackStack(route, inclusive = false) }.getOrDefault(false)) {
            android.util.Log.i("AuroraNav", "tap $route -> pop to root, stack=${navController.currentBackStack.value.map { it.destination.route }}")
            return
        }
        // 每个 Tab 各留一份返回栈：切走时 saveState，切回来 restoreState
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            // 首页是 popUpTo 的目标：NavController 会把刚弹出的那个 Tab 栈映射到首页，
            // 这时再对首页 restoreState，就会“还原”成原来的 Tab（点主页回不了主页）。
            restoreState = route != Ios5Routes.HOME
        }
        android.util.Log.i("AuroraNav", "tap $route -> cur=${navController.currentBackStackEntry?.destination?.route} stack=${navController.currentBackStack.value.map { it.destination.route }}")
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
    // 有声书：点书即整本续播（替换音乐队列），记忆按文件路径找回
    fun playAudiobook(bookId: String) = scope.launch {
        val book = container.audiobookStore.books.value.firstOrNull { it.id == bookId }
        if (book == null || book.chapters.isEmpty()) {
            confirm(if (book == null) "这本书不在书库里了" else "这本书没有可播章节（可能全被关键字过滤）")
            return@launch
        }
        val prog = container.audiobookStore.progressOf(bookId)
        val songs = book.chapters.mapIndexed { i, c ->
            com.aurora.music.model.Song(
                id = com.aurora.music.data.audiobook.abookSongId(bookId, i),
                title = c.displayTitle,
                artist = book.displayTitle,
                album = book.displayTitle,
                artworkUrl = book.coverPath,
                durationSec = c.durationSec,
                streamUrl = runCatching { Uri.fromFile(java.io.File(c.path)).toString() }.getOrDefault(""),
                suffix = c.path.substringAfterLast('.', ""),
                path = c.path,
                accent = com.aurora.music.util.accentFor(c.path),
                trackNumber = c.trackNumber,
            )
        }
        val savedPath = prog?.currentPath.orEmpty()
        // 先按完整路径找回；书目录改名/大小小差异导致对不上时按文件名兜底，别静默从头开始。
        val idx = book.chapters.indexOfFirst { it.path == savedPath }.takeIf { it >= 0 }
            ?: savedPath.substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { name ->
                book.chapters.indexOfFirst { it.path.substringAfterLast('/') == name }
            }?.takeIf { it >= 0 } ?: 0
        val pos = if (book.chapters.getOrNull(idx)?.path == savedPath) prog?.positionMs ?: 0L else 0L
        container.haptic()
        if (!playerVM.hasController()) confirm("正在连接播放服务，连上就播")
        playerVM.playAudiobook(bookId, songs, idx, pos, prog?.speed ?: 1f, prog?.gain ?: 1f)
    }

    fun openRemoteArtist(
        lib: com.aurora.music.data.cast.RemoteDeviceLibrary,
        group: com.aurora.music.data.cast.RemoteArtistGroup,
    ) {
        if (group.songs.isEmpty()) return
        // 与本地艺人详情一致：先按专辑分组，再列全部歌曲。
        // 直接取库里已分好的专辑，保留“是否允许曲名合成”的标记。
        val songIds = group.songs.map { it.id }.toSet()
        val albums = lib.albums
            .filter { a -> a.songs.any { it.id in songIds } }
            .sortedBy { it.title.lowercase() }
        remoteDetail = RemoteDetailTarget(
            deviceId = lib.deviceId,
            title = group.name,
            subtitle = "${lib.deviceName} · 远程",
            songs = group.songs,
            albums = albums,
        )
        navController.navigate(Ios5Routes.REMOTE_GROUP)
    }

    fun openRemoteAlbum(
        lib: com.aurora.music.data.cast.RemoteDeviceLibrary,
        album: com.aurora.music.data.cast.RemoteAlbumGroup,
    ) {
        if (album.songs.isEmpty()) return
        remoteDetail = RemoteDetailTarget(
            deviceId = lib.deviceId,
            title = album.title,
            subtitle = "${album.artist} · ${lib.deviceName} · 远程",
            songs = album.songs,
            singleAlbum = album,
        )
        navController.navigate(Ios5Routes.REMOTE_GROUP)
    }

    fun openRemoteBook(entry: com.aurora.music.data.cast.RemoteEbookEntry) = scope.launch {
        val f = container.remoteLibrary.openEbook(entry)
        if (f == null) {
            confirm("无法从 ${entry.deviceName} 下载这本书")
        } else {
            container.remoteLibrary.touchEbook(entry.key)
            navController.navigate(Ios5Routes.ebookReader(f.absolutePath))
        }
    }

    fun playRemoteAudiobook(entry: com.aurora.music.data.cast.RemoteAudiobookEntry) = scope.launch {
        val songs = container.remoteLibrary.songsForAudiobook(entry)
        if (songs.isEmpty()) {
            confirm("这台有声书没有可播章节")
            return@launch
        }
        val idx = entry.currentIndex.coerceIn(0, songs.lastIndex)
        container.remoteLibrary.noteAudiobookPlaying(entry, idx, entry.positionMs)
        container.haptic()
        if (!playerVM.hasController()) confirm("正在连接播放服务，连上就播")
        playerVM.playAudiobook(
            container.remoteLibrary.abookPlayId(entry), songs, idx, entry.positionMs, 1f, 1f,
        )
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
        // 横屏播放页与主页面左右并排 → 不拦截，直接作用于主页面。
        // 注意：这里必须用条件组合（if 成立才组合 BackHandler），不能用
        // BackHandler(enabled = ...) 常驻：
        // 常驻只切换开关、不重排 dispatcher 顺序；旋转时 NavHost 会随
        // ContentFace 换位重建、后注册后来居上，常驻 handler 会永久输给它。
        // 条件组合保证每次打开播放页时都是最新注册，优先级最高。
        // expanded 在横屏时保留（仅不用），转回竖屏自动恢复播放页。
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
                                onOpenBook = { p -> navController.navigate(Ios5Routes.ebookReader(p)) },
                                onPlayAudiobook = { playAudiobook(it) },
                                onOpenExport = { p -> navController.navigate(Ios5Routes.ebookExport(p)) },
                                remoteEbookRecents = visibleRemoteEbookRecents,
                                remoteAbookRecents = visibleRemoteAbookRecents,
                                onOpenRemoteBook = { openRemoteBook(it) },
                                onPlayRemoteAudiobook = { playRemoteAudiobook(it) },
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
                                remote = onlineRemoteLibraries,
                                onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                onOpenRemoteArtist = { lib, g -> openRemoteArtist(lib, g) },
                                onOpenSearch = { navController.navigate(Ios5Routes.search("artists")) },
                            )
                        }
                        composable(Ios5Routes.EBOOKS) {
                            EbooksTab(
                                onOpenShelf = { navController.navigate(Ios5Routes.ebookLib(shelf = true, title = "默认书架")) },
                                onOpenRoot = { id, dir, title ->
                                    navController.navigate(
                                        Ios5Routes.ebookLib(rootId = id, rootPath = dir, dir = dir, title = title),
                                    )
                                },
                                onOpenBook = { p -> navController.navigate(Ios5Routes.ebookReader(p)) },
                                onOpenSearch = { navController.navigate(Ios5Routes.EBOOK_SEARCH) },
                                onOpenExport = { p -> navController.navigate(Ios5Routes.ebookExport(p)) },
                                remoteLibraries = onlineRemoteLibraries,
                                remoteRecents = visibleRemoteEbookRecents,
                                onOpenRemoteBook = { openRemoteBook(it) },
                                onOpenRemoteRoot = { deviceId, rootKey, rootTitle ->
                                    navController.navigate(
                                        Ios5Routes.remoteEbookLib(deviceId, rootKey, title = rootTitle),
                                    )
                                },
                            )
                        }
                        composable(Ios5Routes.AUDIOBOOKS) {
                            AudiobooksTab(
                                onPlayBook = { playAudiobook(it) },
                                remoteLibraries = onlineRemoteLibraries,
                                remoteRecents = visibleRemoteAbookRecents,
                                onPlayRemoteBook = { playRemoteAudiobook(it) },
                            )
                        }
                        composable(
                            Ios5Routes.EBOOK_LIB,
                            arguments = listOf(
                                androidx.navigation.navArgument("shelf") { defaultValue = "false" },
                                androidx.navigation.navArgument("rootId") { defaultValue = "0" },
                                androidx.navigation.navArgument("rpath") { defaultValue = "" },
                                androidx.navigation.navArgument("dir") { defaultValue = "" },
                                androidx.navigation.navArgument("title") { defaultValue = "" },
                            ),
                        ) { entry ->
                            val shelf = entry.arguments?.getString("shelf") == "true"
                            val rootId = entry.arguments?.getString("rootId")?.toLongOrNull() ?: 0L
                            val rootPath = Uri.decode(entry.arguments?.getString("rpath").orEmpty())
                            val dirArg = Uri.decode(entry.arguments?.getString("dir").orEmpty())
                            val libTitle = Uri.decode(entry.arguments?.getString("title").orEmpty())
                            com.aurora.music.ui.ebook.EbookLibraryScreen(
                                isShelf = shelf,
                                rootPath = rootPath.ifBlank { dirArg },
                                dirPath = if (shelf) "" else dirArg,
                                title = libTitle.ifBlank { if (shelf) "默认书架" else "" },
                                onBack = { navController.popBackStack() },
                                onOpenDir = { d, t ->
                                    navController.navigate(
                                        Ios5Routes.ebookLib(rootId = rootId, rootPath = rootPath.ifBlank { dirArg }, dir = d, title = t),
                                    )
                                },
                                onOpenBook = { p -> navController.navigate(Ios5Routes.ebookReader(p)) },
                                onOpenExport = { p -> navController.navigate(Ios5Routes.ebookExport(p)) },
                                confirm = { confirm(it) },
                            )
                        }
                        composable(Ios5Routes.EBOOK_SEARCH) {
                            com.aurora.music.ui.ebook.EbookSearchScreen(
                                onBack = { navController.popBackStack() },
                                onOpenBook = { p -> navController.navigate(Ios5Routes.ebookReader(p)) },
                                onOpenExport = { p -> navController.navigate(Ios5Routes.ebookExport(p)) },
                            )
                        }
                        composable(
                            Ios5Routes.REMOTE_EBOOK_LIB,
                            arguments = listOf(
                                androidx.navigation.navArgument("device") { defaultValue = "" },
                                androidx.navigation.navArgument("root") { defaultValue = "" },
                                androidx.navigation.navArgument("dir") { defaultValue = "" },
                                androidx.navigation.navArgument("title") { defaultValue = "" },
                            ),
                        ) { entry ->
                            val deviceId = Uri.decode(entry.arguments?.getString("device").orEmpty())
                            val rootKey = Uri.decode(entry.arguments?.getString("root").orEmpty())
                            val dir = Uri.decode(entry.arguments?.getString("dir").orEmpty())
                            val libTitle = Uri.decode(entry.arguments?.getString("title").orEmpty())
                            com.aurora.music.ui.ebook.RemoteEbookLibraryScreen(
                                deviceId = deviceId,
                                rootKey = rootKey,
                                dirPath = dir,
                                title = libTitle,
                                onBack = { navController.popBackStack() },
                                onOpenDir = { d, t ->
                                    navController.navigate(
                                        Ios5Routes.remoteEbookLib(deviceId, rootKey, dir = d, title = t),
                                    )
                                },
                                onOpenBook = { openRemoteBook(it) },
                            )
                        }
                        composable(
                            Ios5Routes.EBOOK_EXPORT,
                            arguments = listOf(
                                androidx.navigation.navArgument("epath") { defaultValue = "" },
                            ),
                        ) { entry ->
                            val epath = Uri.decode(entry.arguments?.getString("epath").orEmpty())
                            com.aurora.music.ui.ebook.EbookExportScreen(
                                bookPath = epath,
                                onBack = { navController.popBackStack() },
                                onOpenAudiobooks = { navigateTopLevel(Ios5Routes.AUDIOBOOKS) },
                                onOpenAudiobookSources = {
                                    navController.navigate(Ios5Routes.SETTINGS_AUDIOBOOK_SOURCES)
                                },
                                confirm = { confirm(it) },
                            )
                        }
                        composable(
                            Ios5Routes.EBOOK_READER,
                            arguments = listOf(
                                androidx.navigation.navArgument("epath") { defaultValue = "" },
                            ),
                        ) {
                            // 阅读器全屏渲染在 NavHost 之外（见下），这里留空避免黑屏闪烁
                            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black))
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
                                remote = onlineRemoteLibraries,
                            )
                        }
                        composable(Ios5Routes.MORE_ALBUMS) {
                            Ios5AlbumsBrowse(
                                albums = browseState.albums,
                                loading = browseState.loading,
                                onBack = { navController.popBackStack() },
                                onSearch = { navController.navigate(Ios5Routes.search("albums")) },
                                onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                remote = onlineRemoteLibraries,
                                onOpenRemoteAlbum = { lib, a -> openRemoteAlbum(lib, a) },
                            )
                        }
                        composable(Ios5Routes.REMOTE_GROUP) {
                            val target = remoteDetail
                            if (target == null || target.deviceId !in onlineDeviceIds) {
                                com.aurora.music.ui.browse.Ios5RemoteDetailEmpty(
                                    onBack = { navController.popBackStack() },
                                )
                            } else {
                                com.aurora.music.ui.browse.Ios5RemoteDetail(
                                    title = target.title,
                                    subtitle = target.subtitle,
                                    songs = target.songs,
                                    albums = target.albums,
                                    singleAlbum = target.singleAlbum,
                                    player = playerState,
                                    onBack = { navController.popBackStack() },
                                    onSearch = { navController.navigate(Ios5Routes.search("songs")) },
                                    onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                )
                            }
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
                        composable(Ios5Routes.SETTINGS_EBOOK_SOURCES) {
                            com.aurora.music.ui.screens.settings.EbookSourcesScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                                confirm = { confirm(it) },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_EBOOK) {
                            com.aurora.music.ui.screens.settings.EbookSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_AUDIOBOOK_SOURCES) {
                            com.aurora.music.ui.screens.settings.AudiobookSourcesScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
                                confirm = { confirm(it) },
                            )
                        }
                        composable(Ios5Routes.SETTINGS_AUDIOBOOK) {
                            com.aurora.music.ui.screens.settings.AudiobookSettingsScreen(
                                contentPadding = PaddingValues(0.dp),
                                onBack = { navController.popBackStack() },
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
                                // 有声书态 mini 条下一键 = 前进步长（与通知栏一致），音乐态切歌
                                onNext = {
                                    if (playerState.abookId != null) playerVM.seekBySeconds(playerVM.abookStep())
                                    else playerVM.next()
                                },
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
                                val sideW = if (topSpec?.onAction != null) 100.dp else 56.dp
                                Box(Modifier.width(sideW), contentAlignment = Alignment.CenterStart) {
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
                                Box(Modifier.width(sideW), contentAlignment = Alignment.CenterEnd) {
                                    topSpec?.takeIf { it.onAction != null && it.actionLabel != null }?.let { spec ->
                                        Text(
                                            spec.actionLabel.orEmpty(), color = Color.White,
                                            fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                                            maxLines = 1,
                                            modifier = Modifier.clip(CircleShape).clickable(onClick = { spec.onAction?.invoke() }).padding(6.dp),
                                        )
                                    }
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
                                    // 横屏有声书态：走带键切章节（系统键仍走 ±步长，由 service 拦截）
                                    onNext = {
                                        if (playerState.abookId != null) playerVM.abookChapter(+1)
                                        else playerVM.next()
                                    },
                                    onPrevious = {
                                        if (playerState.abookId != null) playerVM.abookChapter(-1)
                                        else playerVM.previous()
                                    },
                                    onToggleLike = { if (playerState.abookId == null) playerVM.toggleLikeCurrent() },
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
                    val remoteAbookEntry = container.remoteLibrary.remoteAudiobookForPlayId(playerState.abookId)
                    val abook = abooks.firstOrNull { it.id == playerState.abookId }
                        ?: remoteAbookEntry?.let { e ->
                            com.aurora.music.data.audiobook.AudiobookBook(
                                id = container.remoteLibrary.abookPlayId(e),
                                title = e.displayTitle,
                                rootId = -1L,
                                chapters = e.chapters.map { c ->
                                    com.aurora.music.data.audiobook.AudiobookChapter(
                                        path = c.path,
                                        title = c.title,
                                        trackNumber = c.trackNumber,
                                        durationSec = c.durationSec,
                                        size = c.size,
                                    )
                                },
                                coverPath = e.coverUrl,
                                currentPath = e.chapters.getOrNull(e.currentIndex)?.path.orEmpty(),
                                positionMs = e.positionMs,
                                lastPlayedAt = e.lastPlayedAt,
                            )
                        }
                    if (playerState.abookId != null && abook != null) {
                        Ios5AudiobookPlayerPage(
                            state = playerState,
                            book = abook,
                            allowMix = abookPrefs.allowMix,
                            onCollapse = { playerVM.setExpanded(false) },
                            onTogglePlay = { playerVM.togglePlay() },
                            onPrevChapter = { playerVM.abookChapter(-1) },
                            onNextChapter = { playerVM.abookChapter(+1) },
                            onSeekChapter = { playerVM.abookJumpToChapter(it) },
                            onSeekBy = { playerVM.seekBySeconds(it) },
                            onSeek = { playerVM.seekTo(it) },
                            onSpeed = { playerVM.setAbookSpeed(it) },
                            onGain = { playerVM.setAbookGain(it) },
                            onToggleMix = {
                                scope.launch {
                                    container.settingsStore.setAbookAllowMix(!abookPrefs.allowMix)
                                }
                            },
                            onOpenQueue = { showQueue = true },
                            introActive = introState.active,
                            onIntroClick = { onIntroClick() },
                            chapterAvailable = { i ->
                                val e = remoteAbookEntry
                                if (e == null) true
                                else {
                                    val lib = remoteLibraries.firstOrNull { it.deviceId == e.deviceId }
                                    lib?.online == true ||
                                        container.remoteLibrary.chapterCached(e.deviceId, e.chapters.getOrNull(i)?.path.orEmpty())
                                }
                            },
                        )
                    } else {
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

            // 注册在 NavHost 之后 → 后注册优先级高于主页面导航：
            // 竖屏播放页开着时返回先关播放页，再按才作用于主页面。
            // 条件组合（而非常驻 enabled 开关），旋转后重开播放页时重新注册，
            // 保证永远排在 NavHost 的回调之后（最新注册最先收到返回键）。
            if (!landscape && playerState.expanded && !isEbookReader) {
                BackHandler { playerVM.setExpanded(false) }
            }

            // ---- 阅读器：全屏覆盖在内容页之上 ----
            // 不替换 ContentFace：NavHost、Tab 返回栈、列表滚动位置全部保活，
            // 从阅读器返回时书库还停在原来的目录和位置。
            if (isEbookReader) {
                val epath = Uri.decode(backStackEntry?.arguments?.getString("epath").orEmpty())
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
                )
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    com.aurora.music.ui.ebook.EbookReaderScreen(
                        bookPath = epath,
                        onClose = { navController.popBackStack() },
                    )
                }
            }

            // 恢复返回栈期间盖住首页，避免闪一下再跳到上次的页面（没有可恢复的栈就别闪加载圈）
            if (!navRestored && hasNavToRestore) {
                Ios5Backdrop {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(90.dp))
                    }
                }
            }

            // 接收投屏的全屏浮层：不管当前在哪个 Tab / 阅读器 / 播放页，都盖在最上面
            com.aurora.music.ui.cast.CastReceiverOverlay()
        }
      } // BoxWithConstraints 横竖屏分支结束
    }

    // 队列浮层最顶，同样条件组合保证旋转后仍优先于 NavHost 和播放页
    if (showQueue && !isEbookReader) {
        BackHandler { showQueue = false }
    }

    // keep liked flags warm for visible tracks
    LaunchedEffect(browseState.songs.size) {
        playerVM.checkLiked(browseState.songs.take(200).map { it.id })
    }
}

private data class RemoteDetailTarget(
    val deviceId: String,
    val title: String,
    val subtitle: String,
    val songs: List<com.aurora.music.model.Song>,
    val albums: List<com.aurora.music.data.cast.RemoteAlbumGroup> = emptyList(),
    /** 从专辑列表直接进入时，用这份专辑元信息判断是否开启曲名合成。 */
    val singleAlbum: com.aurora.music.data.cast.RemoteAlbumGroup? = null,
)
