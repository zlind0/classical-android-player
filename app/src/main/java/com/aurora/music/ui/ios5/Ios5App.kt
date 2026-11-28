package com.aurora.music.ui.ios5

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.navigation.Ios5Routes
import com.aurora.music.navigation.Ios5TabNavViewModel
import com.aurora.music.navigation.Ios5TabNavigator
import com.aurora.music.navigation.LocalNavEntryScope
import com.aurora.music.navigation.NavDirection
import com.aurora.music.navigation.NavEntryScope
import com.aurora.music.navigation.ios5TabRoutes
import com.aurora.music.navigation.ios5Tabs
import com.aurora.music.navigation.parseIos5Route
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
import com.aurora.music.ui.player.Ios5LandscapeAudiobookTransport
import com.aurora.music.ui.player.Ios5LandscapeCastTransport
import com.aurora.music.ui.player.NowPlayingMode
import com.aurora.music.ui.player.toPlayerUiState
import com.aurora.music.ui.search.Ios5SearchPage
import com.aurora.music.ui.tabs.ArtistsTab
import com.aurora.music.ui.tabs.AudiobooksTab
import com.aurora.music.ui.tabs.EbooksTab
import com.aurora.music.ui.tabs.HomeTab
import com.aurora.music.ui.tabs.MoreTab
import com.aurora.music.ui.tabs.PlaylistsTab
import com.aurora.music.ui.tabs.SettingsTab
import com.aurora.music.ui.video.RemoteVideoBrowseScreen
import com.aurora.music.ui.video.VideoPlayerScreen
import com.aurora.music.ui.video.VideosTab
import com.aurora.music.viewmodel.HomeViewModel
import com.aurora.music.viewmodel.Ios5BrowseViewModel
import com.aurora.music.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

/** 页面前进/后退转场时长（iOS 推入大约 0.35s）。 */
private const val PAGE_SLIDE_MS = 340
private val pageSlideSpec = tween<Float>(PAGE_SLIDE_MS, easing = FastOutSlowInEasing)

/**
 * iOS5 页栈转场容器。
 *
 * 和 AnimatedContent 不同，这里显式保留“出场页”和“入场页”两棵树，直到动画播完才移除出场页，
 * 所以返回时旧页面不会在动画刚开始就被卸载：
 * - push：新页从右侧推入，旧页左移 1/3 并淡出；
 * - pop：上一页从左侧回到原位，当前页右移整屏并淡出；
 * - 切 Tab：原地淡入淡出。
 *
 * 两个槽位在树里的位置固定，页面只会在自己的槽里出现/消失，转场结束不会把当前页搬来搬去重建。
 * 位移/透明度都在 graphicsLayer 里读取 [Animatable]，逐帧只更新图层，不触发整页重组。
 */
@Composable
private fun PageTransitionHost(
    pageId: String,
    direction: NavDirection,
    modifier: Modifier = Modifier,
    onSettled: () -> Unit = {},
    content: @Composable (String) -> Unit,
) {
    // 两个固定槽位：front 槽永远是当前页，back 槽在转场时放正在退场的旧页。
    var slotAId by remember { mutableStateOf<String?>(pageId) }
    var slotBId by remember { mutableStateOf<String?>(null) }
    var frontIsA by remember { mutableStateOf(true) }
    var motion by remember { mutableStateOf(NavDirection.SWITCH) }
    var animating by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }

    // 组合被提前拆掉（如翻转/重建）时，动画协程会被取消，这里补一次清理，避免条目泄漏。
    DisposableEffect(Unit) {
        onDispose { onSettled() }
    }

    LaunchedEffect(pageId) {
        val front = if (frontIsA) slotAId else slotBId
        if (pageId == front) return@LaunchedEffect
        // 打断上一轮动画时先收掉还在退场的页，避免归零时闪一下。
        // 注意：这里不能 cleanupPending——刚退栈、正要播退场动画的那一页还在 pendingCleanup 里。
        animating = false
        if (frontIsA) slotBId = null else slotAId = null
        progress.snapTo(0f)
        motion = direction
        if (frontIsA) slotBId = pageId else slotAId = pageId
        frontIsA = !frontIsA
        animating = true
        progress.animateTo(1f, pageSlideSpec)
        animating = false
        if (frontIsA) slotBId = null else slotAId = null
        // 动画播完才允许释放已退栈条目的 ViewModel/SaveableState
        onSettled()
    }

    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        // 槽 A / 槽 B 的调用位置固定，清空其中一个不会挪动另一个的组合位置。
        PageSlot(slotAId, frontIsA, animating, motion, progress, widthPx, content)
        PageSlot(slotBId, !frontIsA, animating, motion, progress, widthPx, content)
    }
}

@Composable
private fun PageSlot(
    id: String?,
    isFront: Boolean,
    animating: Boolean,
    motion: NavDirection,
    progress: Animatable<Float, AnimationVector1D>,
    widthPx: Float,
    content: @Composable (String) -> Unit,
) {
    if (id == null) return
    key(id) {
        Box(
            Modifier.fillMaxSize()
                // 当前页永远盖在退场页上面（push 时新页从右边压上来）
                .zIndex(if (isFront) 1f else 0f)
                .graphicsLayer {
                    val p = progress.value
                    if (!animating) {
                        translationX = 0f
                        alpha = 1f
                        return@graphicsLayer
                    }
                    if (isFront) {
                        translationX = when (motion) {
                            NavDirection.FORWARD -> (1f - p) * widthPx
                            NavDirection.BACKWARD -> -(1f - p) * widthPx
                            NavDirection.SWITCH -> 0f
                        }
                        alpha = if (motion == NavDirection.SWITCH) p else 1f
                    } else {
                        translationX = when (motion) {
                            // 旧页左移 1/3 后淡出，新页从右侧盖上来
                            NavDirection.FORWARD -> -p * widthPx / 3f
                            // 旧页右移整屏并淡出，上一页从左侧顶上来
                            NavDirection.BACKWARD -> p * widthPx
                            NavDirection.SWITCH -> 0f
                        }
                        alpha = 1f - p
                    }
                },
        ) { content(id) }
    }
}

@Composable
fun Ios5App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as Application
    val container = (app as AuroraApplication).container

    val navVm: Ios5TabNavViewModel = viewModel()
    val saveableStateHolder = rememberSaveableStateHolder()
    val tabNav = remember(container.uiState, navVm, saveableStateHolder) {
        Ios5TabNavigator(container.uiState, navVm, saveableStateHolder)
    }

    // 兜底：转场正常结束时会由 PageTransitionHost 的 onSettled 立即清理；
    // 万一组合被提前拆掉（动画协程被取消），PageTransitionHost 销毁时也会补一次。

    // 阅读器是覆盖在当前页面之上的全屏层，不占 Tab 返回栈：
    // 这样打开/返回时底层页面（书库、随机列表、滚动位置）始终留在组合里，
    // 淡出阅读器时直接露出原来的 UI，不会先退回空白再重新加载，闪两下。
    // 从外部“打开方式”直接进来的书优先：别先把旧阅读页恢复出来再叠一层。
    val initialSavedReader = remember {
        if (container.ebookStore.openRequest.value == null) {
            container.uiState.readerPath().takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
    var readerPath by rememberSaveable { mutableStateOf<String?>(initialSavedReader) }
    // 关闭时 readerPath 立刻变 null，但淡出动画还要用最后一次的路径，故单独留一份。
    var lastReaderPath by rememberSaveable { mutableStateOf(initialSavedReader.orEmpty()) }
    // 随机阅读也是全屏覆盖层：设备/书库固定，池子由服务端与本地缓存恢复。
    var feedReaderOpen by rememberSaveable { mutableStateOf(false) }
    var feedDeviceId by rememberSaveable { mutableStateOf("") }
    var feedRootKey by rememberSaveable { mutableStateOf("") }
    // 本地/设备间随机阅读：记住集合来源，冷启动也能按同一集合恢复。
    var feedLocalKey by rememberSaveable { mutableStateOf("") }
    var feedLocalShelf by rememberSaveable { mutableStateOf(false) }
    var feedLocalRootId by rememberSaveable { mutableStateOf(0L) }
    var feedLocalRootPath by rememberSaveable { mutableStateOf("") }
    var feedLocalDirPath by rememberSaveable { mutableStateOf("") }
    // 远程文件夹随机：池子是远端路径，需要先从该设备整本下载。
    var feedPoolPaths by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var feedPoolDownloadDeviceId by rememberSaveable { mutableStateOf("") }
    fun openReader(path: String) {
        if (path.isBlank()) return
        feedReaderOpen = false
        lastReaderPath = path
        readerPath = path
    }
    fun openRandomReader(deviceId: String, rootKey: String) {
        if (deviceId.isBlank() || rootKey.isBlank()) return
        container.randomFeed.close()
        readerPath = null
        feedDeviceId = deviceId
        feedRootKey = rootKey
        feedLocalKey = ""
        feedPoolPaths = arrayListOf()
        feedPoolDownloadDeviceId = ""
        feedReaderOpen = true
    }
    fun openRemoteDirRandomReader(
        deviceId: String,
        rootKey: String,
        key: String,
        poolPaths: List<String>,
    ) {
        if (deviceId.isBlank() || poolPaths.isEmpty()) return
        container.randomFeed.close()
        readerPath = null
        feedDeviceId = deviceId
        feedRootKey = key
        feedLocalKey = ""
        feedPoolPaths = ArrayList(poolPaths)
        feedPoolDownloadDeviceId = deviceId
        feedReaderOpen = true
    }
    fun openLocalRandomReader(
        key: String,
        shelf: Boolean,
        rootId: Long,
        rootPath: String,
        dirPath: String,
    ) {
        container.randomFeed.close()
        readerPath = null
        feedDeviceId = "local"
        feedRootKey = key
        feedLocalKey = key
        feedLocalShelf = shelf
        feedLocalRootId = rootId
        feedLocalRootPath = rootPath
        feedLocalDirPath = dirPath
        feedPoolPaths = arrayListOf()
        feedPoolDownloadDeviceId = ""
        feedReaderOpen = true
    }
    fun closeReader() {
        readerPath = null
        feedReaderOpen = false
    }
    val playerVM: PlayerViewModel = viewModel()
    val browseVM: Ios5BrowseViewModel = viewModel()

    val playerState by playerVM.state.collectAsStateWithLifecycle()
    // 投屏接收：音频接收并入统一播放页；电子书接收和 AirPlay 视频镜像仍走各自浮层。
    val receiveState by container.castReceiver.state.collectAsStateWithLifecycle()
    val standardCastState by container.castHub.state.collectAsStateWithLifecycle()
    val receivingCast = receiveState is com.aurora.music.data.cast.CastReceiveState.Music ||
        receiveState is com.aurora.music.data.cast.CastReceiveState.Ebook ||
        standardCastState !is com.aurora.music.data.cast.receiver.CastReceiverState.Idle
    val podMusicReceive = receiveState as? com.aurora.music.data.cast.CastReceiveState.Music
    val airPlayPlaying = standardCastState as? com.aurora.music.data.cast.receiver.CastReceiverState.Playing
    val airPlayAudioReceive = airPlayPlaying?.takeIf { it.video == null }
    val receiveUi = podMusicReceive?.toPlayerUiState() ?: airPlayAudioReceive?.toPlayerUiState()
    val receiveActive = receiveUi != null
    val nowPlayingMode = when {
        podMusicReceive != null -> NowPlayingMode.POD_RECEIVE
        airPlayAudioReceive != null -> NowPlayingMode.AIRPLAY_RECEIVE
        else -> NowPlayingMode.MUSIC
    }
    val nowPlayingState = receiveUi ?: playerState
    // 统一播放页底部的来源说明：自有协议走局域网，标准协议是 AirPlay。
    val receiveCaption = when (nowPlayingMode) {
        NowPlayingMode.POD_RECEIVE -> "Pod局域网串流"
        NowPlayingMode.AIRPLAY_RECEIVE -> "Airplay串流"
        else -> ""
    }
    // 投屏开始不再暂停本机音乐/听书：音乐由统一控制层让位（保留断点），
    // 听书继续朗读。屏幕也不因为接收开始而跳转。
    val browseState by browseVM.state.collectAsStateWithLifecycle()
    val abooks by container.audiobookStore.books.collectAsStateWithLifecycle()
    val abookPrefs by container.settingsStore.audiobookPrefs.collectAsStateWithLifecycle(
        initialValue = com.aurora.music.data.SettingsStore.AudiobookPrefs(),
    )
    val sessionReady by container.sessionReady.collectAsStateWithLifecycle()
    val uiPrefs by container.settingsStore.uiPrefs.collectAsStateWithLifecycle(initialValue = com.aurora.music.data.UiPrefs())
    val remoteLibraries by container.remoteLibrary.libraries.collectAsStateWithLifecycle()
    val cachedRemoteLibraries by container.remoteLibrary.cachedLibraries.collectAsStateWithLifecycle()
    val remoteEbookRecents by container.remoteLibrary.ebookRecents.collectAsStateWithLifecycle()
    val remoteAbookPlays by container.remoteLibrary.abookPlays.collectAsStateWithLifecycle()
    // 离线设备只要有用户下载的条目就继续展示；下载条目永远留在原设备页面下。
    val visibleRemoteLibraries = remember(remoteLibraries) {
        remoteLibraries.filter { it.online || it.hasDownloads }
    }
    val visibleDeviceIds = remember(visibleRemoteLibraries) {
        visibleRemoteLibraries.map { it.deviceId }.toSet()
    }
    val visibleRemoteEbookRecents = remember(remoteEbookRecents, visibleRemoteLibraries, container.remoteDownloads) {
        val byId = visibleRemoteLibraries.associateBy { it.deviceId }
        remoteEbookRecents.filter { e ->
            val lib = byId[e.deviceId] ?: return@filter false
            // 离线设备只在“最近阅读”里保留已下载到本机的书。
            lib.online || container.remoteDownloads.localEbookPath(e.deviceId, e.remotePath) != null
        }
    }
    val remoteAbookRecents = remember(remoteLibraries, remoteAbookPlays) {
        container.remoteLibrary.abookRecents()
    }
    val visibleRemoteAbookRecents = remember(remoteAbookRecents, visibleDeviceIds) {
        remoteAbookRecents.filter { it.deviceId in visibleDeviceIds }
    }
    // 是否仍在浏览远端内容：冷启动时先画灰色缓存，回到主屏幕后恢复“离线只显示下载”的规则。
    var remoteBrowsing by rememberSaveable {
        mutableStateOf(
            container.uiState.remoteBrowsing() ||
                container.uiState.tabStacks().values.flatten().any {
                    it.route.startsWith("ios5_remote_group") ||
                        it.route.startsWith("ios5_remote_ebook_lib") ||
                        it.route.startsWith("ios5_video_browse")
                },
        )
    }
    LaunchedEffect(remoteBrowsing) { container.uiState.saveRemoteBrowsing(remoteBrowsing) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun confirm(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    fun currentNowPlayingSong(): com.aurora.music.model.Song {
        val pod = container.castReceiver.state.value as? com.aurora.music.data.cast.CastReceiveState.Music
        if (pod != null) return pod.song
        val air = container.castHub.state.value as? com.aurora.music.data.cast.receiver.CastReceiverState.Playing
        if (air != null && air.video == null) return air.toPlayerUiState().current
        return playerState.current
    }
    fun onIntroClick() {
        val s = currentNowPlayingSong()
        if (s.id.isNotEmpty()) container.songIntro.toggle(s)
    }
    val introState by container.songIntro.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        container.songIntro.music = object : com.aurora.music.data.IntroMusicControl {
            override fun isPlaying(): Boolean {
                val air = container.castHub.state.value
                if (air is com.aurora.music.data.cast.receiver.CastReceiverState.Playing && air.video == null) {
                    return air.playing
                }
                val pod = container.castReceiver.state.value
                if (pod is com.aurora.music.data.cast.CastReceiveState.Music) return pod.playing
                return playerVM.state.value.isPlaying
            }

            override fun pause() = playerVM.pauseForIntro()
            override fun resume() = playerVM.resumeForIntro()

            override fun duckForIntro(): Boolean {
                val air = container.castHub.state.value
                if (air is com.aurora.music.data.cast.receiver.CastReceiverState.Playing && air.video == null) {
                    container.castHub.setIntroDuck(true)
                    return true
                }
                val pod = container.castReceiver.state.value
                if (pod is com.aurora.music.data.cast.CastReceiveState.Music) {
                    container.castReceiver.setIntroDuck(true)
                    return true
                }
                return false
            }

            override fun unduckAfterIntro() {
                container.castHub.setIntroDuck(false)
                container.castReceiver.setIntroDuck(false)
            }
        }
        container.songIntro.events.collect { confirm(it) }
    }
    LaunchedEffect(nowPlayingState.current.id) {
        container.songIntro.onSongChanged(currentNowPlayingSong().id)
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
    // 外部打开电子书（文件关联）：导入完成后跳阅读页；失败则提示
    val pendingEbook by container.ebookStore.openRequest.collectAsStateWithLifecycle()
    val ebookNotice by container.ebookStore.notice.collectAsStateWithLifecycle()
    LaunchedEffect(pendingEbook, sessionReady) {
        val p = pendingEbook
        if (p != null && sessionReady != null) {
            container.ebookStore.takeOpenRequest()
            openReader(p)
        }
    }
    LaunchedEffect(ebookNotice) {
        val n = ebookNotice
        if (n != null) {
            container.ebookStore.takeNotice()
            confirm(n)
        }
    }

    val currentRoute = tabNav.currentRoute

    // 远端浏览会话内展示完整缓存（离线条目灰色不可点）；回到任意 Tab 顶层立即隐藏离线内容。
    val showUnavailableRemote = remoteBrowsing && currentRoute !in ios5TabRoutes
    val browsingRemoteLibraries = if (showUnavailableRemote) cachedRemoteLibraries else visibleRemoteLibraries
    val browsingDeviceIds = remember(browsingRemoteLibraries) {
        browsingRemoteLibraries.map { it.deviceId }.toSet()
    }
    val browsingRemoteEbookRecents = remember(remoteEbookRecents, browsingDeviceIds, showUnavailableRemote) {
        if (showUnavailableRemote) {
            remoteEbookRecents.filter { it.deviceId in browsingDeviceIds }
        } else {
            visibleRemoteEbookRecents
        }
    }
    val browsingRemoteAbookRecents = remember(remoteAbookRecents, browsingDeviceIds, showUnavailableRemote) {
        if (showUnavailableRemote) {
            remoteAbookRecents.filter { it.deviceId in browsingDeviceIds }
        } else {
            visibleRemoteAbookRecents
        }
    }
    LaunchedEffect(currentRoute) {
        when {
            currentRoute in ios5TabRoutes -> remoteBrowsing = false
            currentRoute.startsWith("ios5_remote_group") ||
                currentRoute.startsWith("ios5_remote_ebook_lib") ||
                currentRoute.startsWith("ios5_video_browse") -> remoteBrowsing = true
        }
    }
    val tabHighlight = tabNav.activeTab
    // 阅读器全屏独占：不带 tab/mini 条，不参与翻转动画
    val isEbookReader = readerPath != null || feedReaderOpen
    // 阅读器不在 Tab 返回栈里，单独把“上次读到哪本书”落盘，冷启动才能恢复。
    LaunchedEffect(readerPath) {
        if (!feedReaderOpen) container.uiState.saveReaderPath(readerPath.orEmpty())
    }
    // 播放页是全屏翻转页，属于页面现场的一部分
    var expandedRestored by remember { mutableStateOf(false) }
    // 投屏接收页与普通播放页共用同一套翻转现场。
    // 投屏开始时只沿用当前播放页的展开状态，不强制翻页/弹出全屏页，
    // 这样在阅读页听书时开始投屏，屏幕不会闪一下跳到接收页。
    var castPageExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedBeforeCast by remember { mutableStateOf(false) }
    LaunchedEffect(receiveActive, isEbookReader) {
        if (receiveActive) {
            expandedBeforeCast = playerState.expanded
            // 阅读页里永远不自动展开接收页：否则投屏一开始阅读页会被淡出，看起来像闪屏。
            castPageExpanded = !isEbookReader && playerState.expanded
        } else {
            castPageExpanded = false
            if (expandedBeforeCast) playerVM.setExpanded(true)
        }
    }
    val nowPlayingExpanded = if (receiveActive) castPageExpanded else playerState.expanded
    val nowPlayingAvailable = receiveActive || playerState.hasTrack
    LaunchedEffect(playerState.expanded) {
        if (expandedRestored) container.uiState.savePlayerExpanded(playerState.expanded)
    }
    // 冷启动恢复队列后再把播放页翻回来（只做一次）
    LaunchedEffect(playerState.hasTrack) {
        if (!expandedRestored && playerState.hasTrack) {
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
    // 短视频播放页：不占 Tab 返回栈，只作为最上层浮层，关掉就露出原来的页面。
    val videoFeed by container.videoFeed.state.collectAsStateWithLifecycle()
    // 横屏有声书/接收页的气泡宿主：气泡必须盖住整屏，不能只盖右栏。
    var abookBubble by remember { mutableStateOf<com.aurora.music.ui.player.AbookBubble?>(null) }
    var landscapeSleepBubble by remember { mutableStateOf(false) }
    var castSeekFraction by remember { mutableStateOf(-1f) }
    LaunchedEffect(receivingCast) {
        if (receivingCast) showQueue = false
    }

    // 横向合并顶栏的上报宿主：主区页面的 Ios5NavBar 把标题/返回/搜索交到这里统一渲染
    val topBarHost = remember { TopBarHost() }

    fun navigateTopLevel(route: String) {
        if (route in ios5TabRoutes) remoteBrowsing = false
        if (tabNav.activeTab != route || tabNav.canPop) container.haptic()
        tabNav.selectTab(route)
    }
    fun openDetail(kind: String, id: String, title: String = "") =
        tabNav.push(Ios5Routes.detail(kind, id, title), title)
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
                clipStartMs = c.startMs,
                clipEndMs = c.endMs,
            )
        }
        val savedPath = prog?.currentPath.orEmpty()
        val savedStart = prog?.currentStartMs ?: 0L
        // 先按 (完整路径, 文件内起点) 找回；重扫后章节边界变化时退回该文件第一章；
        // 书目录改名/大小小差异导致对不上时按文件名兜底，别静默从头开始。
        val exact = book.chapters.indexOfFirst { it.path == savedPath && it.startMs == savedStart }
            .takeIf { it >= 0 }
        val sameFile = book.chapters.indexOfFirst { it.path == savedPath }.takeIf { it >= 0 }
        val byName = savedPath.substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { name ->
            book.chapters.indexOfFirst { it.path.substringAfterLast('/') == name }
        }?.takeIf { it >= 0 }
        val idx = exact ?: sameFile ?: byName ?: 0
        val pos = if (exact != null) prog?.positionMs ?: 0L else 0L
        container.haptic()
        if (!playerVM.hasController()) confirm("正在连接播放服务，连上就播")
        playerVM.playAudiobook(bookId, songs, idx, pos, prog?.speed ?: 1f, prog?.gain ?: 1f)
    }

    fun openRemoteArtist(
        lib: com.aurora.music.data.cast.RemoteDeviceLibrary,
        group: com.aurora.music.data.cast.RemoteArtistGroup,
    ) {
        if (group.songs.isEmpty()) return
        remoteBrowsing = true
        tabNav.push(
            Ios5Routes.remoteGroup(
                device = lib.deviceId,
                kind = "artist",
                key = group.name,
                title = group.name,
                subtitle = "${lib.deviceName} · 远程",
            ),
            group.name,
        )
    }

    fun openRemoteAlbum(
        lib: com.aurora.music.data.cast.RemoteDeviceLibrary,
        album: com.aurora.music.data.cast.RemoteAlbumGroup,
    ) {
        if (album.songs.isEmpty()) return
        remoteBrowsing = true
        tabNav.push(
            Ios5Routes.remoteGroup(
                device = lib.deviceId,
                kind = "album",
                key = album.id,
                title = album.title,
                subtitle = "${album.artist} · ${lib.deviceName} · 远程",
            ),
            album.title,
        )
    }

    fun openRemoteBook(entry: com.aurora.music.data.cast.RemoteEbookEntry) = scope.launch {
        remoteBrowsing = true
        val f = container.remoteLibrary.openEbook(entry)
        if (f == null) {
            confirm("无法从 ${entry.deviceName} 下载这本书")
        } else {
            container.remoteLibrary.touchEbook(entry.key)
            openReader(f.absolutePath)
        }
    }

    fun openRandomBook(
        entry: com.aurora.music.data.cast.RemoteEbookEntry,
        rootKey: String,
    ) = scope.launch {
        remoteBrowsing = true
        val f = container.remoteLibrary.openEbook(entry, recordRecent = false)
        if (f == null) {
            confirm("无法从 ${entry.deviceName} 下载这本书")
        } else {
            container.remoteLibrary.noteRandomEbookOpened(entry.deviceId, rootKey, entry.remotePath)
            openReader(f.absolutePath)
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
            targetValue = if (!landscape && nowPlayingExpanded) 1f else 0f,
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
                    // 导航栏金属底固定在页面之外：切页时只有标题和按钮跟着页面走，底不动也不淡出
                    val mergedBar = LocalTopBarHost.current != null
                    if (!mergedBar) Ios5NavBarBackdrop()
                    if (tabNav.canPop) {
                        BackHandler { tabNav.pop() }
                    }
                    CompositionLocalProvider(LocalStaticNavBar provides !mergedBar) {
                    PageTransitionHost(
                        // 用条目 id 而不是条目对象：标题回写会换新对象，不能因此触发转场
                        pageId = tabNav.currentEntry.id,
                        direction = tabNav.direction,
                        modifier = Modifier.fillMaxSize(),
                        onSettled = { tabNav.cleanupPending() },
                    ) { entryId ->
                    val entry = tabNav.entryById(entryId) ?: return@PageTransitionHost
                    val parsed = remember(entry.route) { parseIos5Route(entry.route) }
                    saveableStateHolder.SaveableStateProvider(entry.id) {
                        CompositionLocalProvider(
                            LocalViewModelStoreOwner provides navVm.ownerFor(entry.id, app),
                            LocalNavEntryScope provides NavEntryScope(
                                tabRoute = tabNav.activeTab,
                                entryId = entry.id,
                                previousTitle = tabNav.previousTitleFor(entry.id),
                                onTitleChanged = { tabNav.updateTitle(tabNav.activeTab, entry.id, it) },
                            ),
                        ) {
                            when (parsed.base) {
                                Ios5Routes.HOME -> {
                                    val homeVM: HomeViewModel = viewModel()
                                    val homeState by homeVM.state.collectAsStateWithLifecycle()
                                    HomeTab(
                                        state = homeState,
                                        onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                        onPlaySongs = { songs, index -> playerVM.playAll(songs, index) },
                                        onOpenBook = { p -> openReader(p) },
                                        onPlayAudiobook = { playAudiobook(it) },
                                        onOpenExport = { p -> tabNav.push(Ios5Routes.ebookExport(p)) },
                                        remoteEbookRecents = visibleRemoteEbookRecents,
                                        remoteAbookRecents = visibleRemoteAbookRecents,
                                        onOpenRemoteBook = { openRemoteBook(it) },
                                        onPlayRemoteAudiobook = { playRemoteAudiobook(it) },
                                    )
                                }
                                Ios5Routes.PLAYLISTS -> {
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
                                        onOpenSearch = { tabNav.push(Ios5Routes.search("songs")) },
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.ARTISTS -> {
                                    ArtistsTab(
                                        artists = browseState.artists,
                                        loading = browseState.loading,
                                        remote = browsingRemoteLibraries,
                                        onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                        onOpenRemoteArtist = { lib, g -> openRemoteArtist(lib, g) },
                                        onOpenSearch = { tabNav.push(Ios5Routes.search("artists")) },
                                    )
                                }
                                Ios5Routes.EBOOKS -> {
                                    EbooksTab(
                                        onOpenShelf = {
                                            tabNav.push(Ios5Routes.ebookLib(shelf = true, title = "默认书架"), "默认书架")
                                        },
                                        onOpenRoot = { id, dir, title ->
                                            tabNav.push(
                                                Ios5Routes.ebookLib(rootId = id, rootPath = dir, dir = dir, title = title),
                                                title,
                                            )
                                        },
                                        onOpenBook = { p -> openReader(p) },
                                        onOpenSearch = { tabNav.push(Ios5Routes.EBOOK_SEARCH) },
                                        onOpenExport = { p -> tabNav.push(Ios5Routes.ebookExport(p)) },
                                        onStartRandomShelf = {
                                            openLocalRandomReader(
                                                key = "shelf",
                                                shelf = true,
                                                rootId = com.aurora.music.data.ebook.EBOOK_SHELF_ROOT_ID,
                                                rootPath = "",
                                                dirPath = "",
                                            )
                                        },
                                        onStartRandomRoot = { id, dir, _ ->
                                            openLocalRandomReader(
                                                key = "root:$id",
                                                shelf = false,
                                                rootId = id,
                                                rootPath = dir,
                                                dirPath = "",
                                            )
                                        },
                                        remoteLibraries = browsingRemoteLibraries,
                                        remoteRecents = browsingRemoteEbookRecents,
                                        onOpenRemoteBook = { openRemoteBook(it) },
                                        onStartRandomRemoteRoot = { deviceId, rootKey ->
                                            openRandomReader(deviceId, rootKey)
                                        },
                                        onStartRandomRemotePool = { deviceId, rootKey, paths ->
                                            openRemoteDirRandomReader(
                                                deviceId,
                                                rootKey,
                                                key = "remote:$deviceId:$rootKey",
                                                poolPaths = paths,
                                            )
                                        },
                                        onOpenRemoteRoot = { deviceId, rootKey, rootTitle ->
                                            remoteBrowsing = true
                                            tabNav.push(
                                                Ios5Routes.remoteEbookLib(deviceId, rootKey, title = rootTitle),
                                                rootTitle,
                                            )
                                        },
                                    )
                                }
                                Ios5Routes.AUDIOBOOKS -> {
                                    AudiobooksTab(
                                        onPlayBook = { playAudiobook(it) },
                                        remoteLibraries = browsingRemoteLibraries,
                                        remoteRecents = browsingRemoteAbookRecents,
                                        onPlayRemoteBook = { playRemoteAudiobook(it) },
                                        player = playerState,
                                    )
                                }
                                "ios5_ebook_lib" -> {
                                    val shelf = parsed.query["shelf"] == "true"
                                    val rootId = parsed.query["rootId"]?.toLongOrNull() ?: 0L
                                    val rootPath = parsed.query["rpath"].orEmpty()
                                    val dirArg = parsed.query["dir"].orEmpty()
                                    val libTitle = parsed.query["title"].orEmpty()
                                    com.aurora.music.ui.ebook.EbookLibraryScreen(
                                        isShelf = shelf,
                                        rootId = rootId,
                                        rootPath = rootPath.ifBlank { dirArg },
                                        dirPath = if (shelf) "" else dirArg,
                                        title = libTitle.ifBlank { if (shelf) "默认书架" else "" },
                                        onBack = { tabNav.pop() },
                                        onOpenDir = { d, t ->
                                            tabNav.push(
                                                Ios5Routes.ebookLib(rootId = rootId, rootPath = rootPath.ifBlank { dirArg }, dir = d, title = t),
                                                t,
                                            )
                                        },
                                        onOpenBook = { p -> openReader(p) },
                                        onOpenExport = { p -> tabNav.push(Ios5Routes.ebookExport(p)) },
                                        onStartRandomDir = { dir, _ ->
                                            openLocalRandomReader(
                                                key = "root:$rootId:$dir",
                                                shelf = false,
                                                rootId = rootId,
                                                rootPath = rootPath.ifBlank { dirArg },
                                                dirPath = dir,
                                            )
                                        },
                                        confirm = { confirm(it) },
                                    )
                                }
                                Ios5Routes.EBOOK_SEARCH -> {
                                    com.aurora.music.ui.ebook.EbookSearchScreen(
                                        onBack = { tabNav.pop() },
                                        onOpenBook = { p -> openReader(p) },
                                        onOpenExport = { p -> tabNav.push(Ios5Routes.ebookExport(p)) },
                                    )
                                }
                                "ios5_remote_ebook_lib" -> {
                                    val deviceId = parsed.query["device"].orEmpty()
                                    val rootKey = parsed.query["root"].orEmpty()
                                    val dir = parsed.query["dir"].orEmpty()
                                    val libTitle = parsed.query["title"].orEmpty()
                                    com.aurora.music.ui.ebook.RemoteEbookLibraryScreen(
                                        deviceId = deviceId,
                                        rootKey = rootKey,
                                        dirPath = dir,
                                        title = libTitle,
                                        allowOffline = remoteBrowsing,
                                        onBack = { tabNav.pop() },
                                        onOpenDir = { d, t ->
                                            tabNav.push(
                                                Ios5Routes.remoteEbookLib(deviceId, rootKey, dir = d, title = t),
                                                t,
                                            )
                                        },
                                        onOpenBook = { openRemoteBook(it) },
                                        onOpenRandomBook = { bookEntry, rk -> openRandomBook(bookEntry, rk) },
                                        onStartRandomReading = { d, r -> openRandomReader(d, r) },
                                        onStartRandomDir = { d, rk, key, paths ->
                                            openRemoteDirRandomReader(d, rk, key, paths)
                                        },
                                        onMessage = { confirm(it) },
                                    )
                                }
                                "ebook_export" -> {
                                    val epath = parsed.query["epath"].orEmpty()
                                    com.aurora.music.ui.ebook.EbookExportScreen(
                                        bookPath = epath,
                                        onBack = { tabNav.pop() },
                                        onOpenAudiobooks = { navigateTopLevel(Ios5Routes.AUDIOBOOKS) },
                                        onOpenAudiobookSources = {
                                            tabNav.push(Ios5Routes.SETTINGS_AUDIOBOOK_SOURCES)
                                        },
                                        confirm = { confirm(it) },
                                    )
                                }
                                Ios5Routes.VIDEOS -> {
                                    VideosTab(
                                        onOpenRoot = { deviceId, rootKey, title ->
                                            remoteBrowsing = true
                                            tabNav.push(
                                                Ios5Routes.videoBrowse(deviceId, rootKey, title = title),
                                                title,
                                            )
                                        },
                                    )
                                }
                                "ios5_video_browse" -> {
                                    val deviceId = parsed.query["device"].orEmpty()
                                    val rootKey = parsed.query["root"].orEmpty()
                                    val dir = parsed.query["dir"].orEmpty()
                                    val title = parsed.query["title"].orEmpty()
                                    RemoteVideoBrowseScreen(
                                        deviceId = deviceId,
                                        rootKey = rootKey,
                                        dir = dir,
                                        title = title,
                                        onBack = { tabNav.pop() },
                                        onOpenDir = { childDir, childTitle ->
                                            remoteBrowsing = true
                                            tabNav.push(
                                                Ios5Routes.videoBrowse(
                                                    deviceId,
                                                    rootKey,
                                                    dir = childDir,
                                                    title = childTitle,
                                                ),
                                                childTitle,
                                            )
                                        },
                                    )
                                }
                                Ios5Routes.MORE -> {
                                    MoreTab(onOpenRoute = { tabNav.push(it) })
                                }
                                Ios5Routes.SETTINGS -> {
                                    SettingsTab(
                                        onOpen = { tabNav.push(it) },
                                        onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                    )
                                }
                                Ios5Routes.MORE_SONGS -> {
                                    Ios5SongsBrowse(
                                        songs = browseState.songs,
                                        loading = browseState.loading,
                                        player = playerState,
                                        onBack = { tabNav.pop() },
                                        onSearch = { tabNav.push(Ios5Routes.search("songs")) },
                                        onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                        remote = browsingRemoteLibraries,
                                    )
                                }
                                Ios5Routes.MORE_ALBUMS -> {
                                    Ios5AlbumsBrowse(
                                        albums = browseState.albums,
                                        loading = browseState.loading,
                                        onBack = { tabNav.pop() },
                                        onSearch = { tabNav.push(Ios5Routes.search("albums")) },
                                        onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                        remote = browsingRemoteLibraries,
                                        onOpenRemoteAlbum = { lib, a -> openRemoteAlbum(lib, a) },
                                    )
                                }
                                "ios5_remote_group" -> {
                                    val deviceId = parsed.query["device"].orEmpty()
                                    val kind = parsed.query["kind"].orEmpty()
                                    val key = parsed.query["key"].orEmpty()
                                    val savedTitle = parsed.query["title"].orEmpty()
                                    val savedSubtitle = parsed.query["subtitle"].orEmpty()
                                    // 从完整缓存解析：冷启动时设备还没核对完也能先画出灰色内容。
                                    val lib = cachedRemoteLibraries.firstOrNull { it.deviceId == deviceId }
                                    val album = if (kind == "album") {
                                        lib?.albums?.firstOrNull { it.id == key }
                                    } else {
                                        null
                                    }
                                    val artist = if (kind == "artist") {
                                        lib?.artists?.firstOrNull { it.name == key }
                                    } else {
                                        null
                                    }
                                    if (lib == null || (album == null && artist == null)) {
                                        com.aurora.music.ui.browse.Ios5RemoteDetailEmpty(
                                            onBack = { tabNav.pop() },
                                        )
                                    } else {
                                        val artistAlbumList = if (artist != null) {
                                            val songIds = artist.songs.map { it.id }.toSet()
                                            lib.albums.filter { a -> a.songs.any { it.id in songIds } }
                                        } else {
                                            emptyList()
                                        }
                                        com.aurora.music.ui.browse.Ios5RemoteDetail(
                                            title = album?.title ?: artist?.name ?: savedTitle,
                                            subtitle = if (album != null) {
                                                "${album.artist} · ${lib.deviceName} · 远程"
                                            } else {
                                                savedSubtitle.ifBlank { "${lib.deviceName} · 远程" }
                                            },
                                            deviceId = deviceId,
                                            deviceName = lib.deviceName,
                                            songs = album?.songs ?: artist?.songs.orEmpty(),
                                            albums = artistAlbumList,
                                            singleAlbum = album,
                                            available = lib.online,
                                            player = playerState,
                                            onBack = { tabNav.pop() },
                                            onSearch = { tabNav.push(Ios5Routes.search("songs")) },
                                            onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                            onOpenAlbum = { a ->
                                                remoteBrowsing = true
                                                tabNav.push(
                                                    Ios5Routes.remoteGroup(
                                                        device = deviceId,
                                                        kind = "album",
                                                        key = a.id,
                                                        title = a.title,
                                                        subtitle = "${a.artist} · ${lib.deviceName} · 远程",
                                                    ),
                                                    a.title,
                                                )
                                            },
                                        )
                                    }
                                }
                                Ios5Routes.MORE_GENRES -> {
                                    Ios5GroupsBrowse(
                                        title = "风格",
                                        groups = browseState.genres,
                                        loading = browseState.loading,
                                        searchScope = "all",
                                        onBack = { tabNav.pop() },
                                        onSearch = { tabNav.push(Ios5Routes.search("all")) },
                                        onOpenGroup = { name -> openDetail("genre", name, name) },
                                    )
                                }
                                Ios5Routes.MORE_COMPOSERS -> {
                                    Ios5GroupsBrowse(
                                        title = "作曲家",
                                        groups = browseState.composers,
                                        loading = browseState.loading,
                                        searchScope = "all",
                                        onBack = { tabNav.pop() },
                                        onSearch = { tabNav.push(Ios5Routes.search("all")) },
                                        onOpenGroup = { name -> openDetail("composer", name, name) },
                                    )
                                }
                                Ios5Routes.MORE_FOLDERS -> {
                                    Ios5FoldersRoot(
                                        onBack = { tabNav.pop() },
                                        onSearch = { tabNav.push(Ios5Routes.search("songs")) },
                                        onOpenFolder = { fid, title -> tabNav.push(Ios5Routes.folder(fid, title), title) },
                                        player = playerState,
                                        onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                    )
                                }
                                "ios5_folder" -> {
                                    val fid = parsed.query["fid"].orEmpty()
                                    val folderTitle = parsed.query["title"].orEmpty()
                                    Ios5FolderLevel(
                                        fid = fid,
                                        title = folderTitle,
                                        onBack = { tabNav.pop() },
                                        onSearch = { tabNav.push(Ios5Routes.search("songs")) },
                                        onOpenFolder = { id, name -> tabNav.push(Ios5Routes.folder(id, name), name) },
                                        player = playerState,
                                        onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                    )
                                }
                                "ios5_detail" -> {
                                    val kind = parsed.pathSegments.getOrNull(1).orEmpty()
                                    val id = parsed.pathSegments.getOrNull(2).orEmpty()
                                    val title = parsed.pathSegments.getOrNull(3).orEmpty()
                                    when (kind) {
                                        "genre" -> {
                                            val songs = browseState.genres.firstOrNull { it.name == id }?.songs.orEmpty()
                                            Ios5GroupDetail(
                                                title = title.ifBlank { id },
                                                songs = songs,
                                                player = playerState,
                                                onBack = { tabNav.pop() },
                                                onSearch = { tabNav.push(Ios5Routes.search("all")) },
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
                                                onBack = { tabNav.pop() },
                                                onSearch = { tabNav.push(Ios5Routes.search("all")) },
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
                                            onBack = { tabNav.pop() },
                                            onSearch = { tabNav.push(Ios5Routes.search(searchScopeFor(parsed.base))) },
                                            onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                            onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                            onPlayCollection = { k, i -> playCollection(k, i) },
                                            onShuffleCollection = { k, i -> shuffleCollection(k, i) },
                                        )
                                    }
                                }
                                "ios5_search" -> {
                                    val scopeArg = parsed.query["scope"]?.ifBlank { "all" } ?: "all"
                                    Ios5SearchPage(
                                        scope = scopeArg,
                                        player = playerState,
                                        onBack = { tabNav.pop() },
                                        onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                        onPlaySongs = { songs, i -> playerVM.playAll(songs, i) },
                                    )
                                }
                                Ios5Routes.SETTINGS_PLAYBACK -> {
                                    com.aurora.music.ui.screens.settings.PlaybackSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_EQ -> {
                                    com.aurora.music.ui.screens.settings.EqualizerScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_VISUALIZER -> {
                                    com.aurora.music.ui.screens.settings.VisualizerSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_SONIC -> {
                                    com.aurora.music.ui.screens.settings.SonicSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_SOURCES -> {
                                    com.aurora.music.ui.screens.settings.MusicSourcesScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
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
                                Ios5Routes.SETTINGS_EBOOK_SOURCES -> {
                                    com.aurora.music.ui.screens.settings.EbookSourcesScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        confirm = { confirm(it) },
                                    )
                                }
                                Ios5Routes.SETTINGS_EBOOK -> {
                                    com.aurora.music.ui.screens.settings.EbookSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_AUDIOBOOK_SOURCES -> {
                                    com.aurora.music.ui.screens.settings.AudiobookSourcesScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        confirm = { confirm(it) },
                                    )
                                }
                                Ios5Routes.SETTINGS_AUDIOBOOK -> {
                                    com.aurora.music.ui.screens.settings.AudiobookSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        onOpenSubtitles = {
                                            tabNav.push(Ios5Routes.SETTINGS_AUDIOBOOK_SUBTITLES)
                                        },
                                    )
                                }
                                Ios5Routes.SETTINGS_AUDIOBOOK_SUBTITLES -> {
                                    com.aurora.music.ui.screens.settings.SubtitleSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_MULTIMODAL -> {
                                    com.aurora.music.ui.screens.settings.MultimodalSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_STORAGE -> {
                                    com.aurora.music.ui.screens.settings.StorageSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_APPEARANCE -> {
                                    com.aurora.music.ui.screens.settings.AppearanceScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_GESTURES -> {
                                    com.aurora.music.ui.screens.settings.GesturesSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_INTEGRATIONS -> {
                                    com.aurora.music.ui.screens.settings.IntegrationsSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_AIRPLAY_DIAGNOSTICS -> {
                                    com.aurora.music.ui.screens.settings.AirPlayDiagnosticsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_RELAY -> {
                                    com.aurora.music.ui.screens.settings.RelaySettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_PERMISSIONS -> {
                                    com.aurora.music.ui.screens.settings.PermissionsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_ABOUT -> {
                                    com.aurora.music.ui.screens.settings.AboutSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_BACKUP -> {
                                    com.aurora.music.ui.screens.settings.BackupScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        confirm = { confirm(it) },
                                    )
                                }
                                Ios5Routes.SETTINGS_INTRO -> {
                                    com.aurora.music.ui.screens.settings.SongIntroSettingsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        confirm = { confirm(it) },
                                        onOpenPrompt = { tabNav.push(Ios5Routes.SETTINGS_INTRO_PROMPT) },
                                        onOpenVoice = { tabNav.push(Ios5Routes.SETTINGS_INTRO_VOICE) },
                                        onOpenLog = { tabNav.push(Ios5Routes.SETTINGS_INTRO_LOG) },
                                        onOpenModel = { tabNav.push(Ios5Routes.SETTINGS_INTRO_MODEL) },
                                    )
                                }
                                Ios5Routes.SETTINGS_INTRO_MODEL -> {
                                    com.aurora.music.ui.screens.settings.SongIntroModelScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        confirm = { confirm(it) },
                                    )
                                }
                                Ios5Routes.SETTINGS_INTRO_PROMPT -> {
                                    com.aurora.music.ui.screens.settings.SongIntroPromptScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_INTRO_VOICE -> {
                                    com.aurora.music.ui.screens.settings.SongIntroVoiceScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                Ios5Routes.SETTINGS_INTRO_LOG -> {
                                    com.aurora.music.ui.screens.settings.SongIntroLogScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        confirm = { confirm(it) },
                                    )
                                }
                                Ios5Routes.HISTORY -> {
                                    com.aurora.music.ui.screens.stats.ListeningHistoryScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        onPlay = { playById(it) },
                                    )
                                }
                                Ios5Routes.STATS -> {
                                    com.aurora.music.ui.screens.stats.ListeningStatsScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        onBack = { tabNav.pop() },
                                        onPlay = { playById(it) },
                                        onOpenDetail = { k, i -> openDetail(k, i) },
                                    )
                                }
                                Ios5Routes.DUPLICATES -> {
                                    val dupVM: com.aurora.music.viewmodel.DuplicatesViewModel = viewModel()
                                    val dupState by dupVM.state.collectAsStateWithLifecycle()
                                    com.aurora.music.ui.screens.library.DuplicatesScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        loading = dupState.loading,
                                        scanned = dupState.scanned,
                                        groups = dupState.groups,
                                        currentSongId = playerState.current.id,
                                        onBack = { tabNav.pop() },
                                        onPlay = { s -> playerVM.playAll(listOf(s), 0) },
                                    )
                                }
                                "smart_edit" -> {
                                    val smartId = parsed.query["id"].orEmpty()
                                    val smartVM: com.aurora.music.viewmodel.SmartPlaylistViewModel = viewModel()
                                    LaunchedEffect(smartId) { smartVM.load(smartId) }
                                    val smartState by smartVM.state.collectAsStateWithLifecycle()
                                    com.aurora.music.ui.screens.library.SmartPlaylistEditScreen(
                                        contentPadding = PaddingValues(0.dp),
                                        playlist = smartState,
                                        isNew = smartId.isBlank(),
                                        onUpdate = smartVM::update,
                                        onSave = { smartVM.save { tabNav.pop() } },
                                        onBack = { tabNav.pop() },
                                    )
                                }
                                "tag_edit" -> {
                                    val songId = parsed.pathSegments.getOrNull(1).orEmpty()
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
                                        onBack = { tabNav.pop() },
                                        confirm = { confirm(it) },
                                    )
                                }
                                else -> {
                                    val homeVM: HomeViewModel = viewModel()
                                    val homeState by homeVM.state.collectAsStateWithLifecycle()
                                    HomeTab(
                                        state = homeState,
                                        onOpenDetail = { k, i, t -> openDetail(k, i, t) },
                                        onPlaySongs = { songs, index -> playerVM.playAll(songs, index) },
                                        onOpenBook = { p -> openReader(p) },
                                        onPlayAudiobook = { playAudiobook(it) },
                                        onOpenExport = { p -> tabNav.push(Ios5Routes.ebookExport(p)) },
                                        remoteEbookRecents = visibleRemoteEbookRecents,
                                        remoteAbookRecents = visibleRemoteAbookRecents,
                                        onOpenRemoteBook = { openRemoteBook(it) },
                                        onPlayRemoteAudiobook = { playRemoteAudiobook(it) },
                                    )
                                }
                            }
                        }
                    }
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
                        if (nowPlayingAvailable && faceFlip < 0.5f && showMiniStrip) {
                            Ios5MiniStrip(
                                state = nowPlayingState,
                                onExpand = {
                                    if (receiveActive) castPageExpanded = true
                                    else playerVM.setExpanded(true)
                                },
                                onTogglePlay = {
                                    when {
                                        podMusicReceive != null -> container.castReceiver.togglePlay()
                                        airPlayAudioReceive != null ->
                                            if (airPlayAudioReceive.playing) container.castHub.pause()
                                            else container.castHub.play()
                                        else -> playerVM.togglePlay()
                                    }
                                },
                                // 有声书态 mini 条下一键 = 前进步长（与通知栏一致），音乐/接收态切歌
                                onNext = {
                                    when {
                                        podMusicReceive != null -> container.castReceiver.next()
                                        airPlayAudioReceive != null -> container.castHub.next()
                                        playerState.abookId != null -> playerVM.seekBySeconds(playerVM.abookStep())
                                        else -> playerVM.next()
                                    }
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
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                val curTitle = topSpec?.title
                                    ?: ios5Tabs.firstOrNull { it.route == tabHighlight }?.label.orEmpty()
                                val titleShadowY = with(LocalDensity.current) { -1.dp.toPx() }
                                Ios5NavBarLayout(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                                    left = topSpec?.onBack?.let { back ->
                                        {
                                            Ios5BackButton(
                                                label = topSpec.backLabel ?: "返回",
                                                onClick = back,
                                                dark = true,
                                            )
                                        }
                                    },
                                    title = {
                                        Text(
                                            curTitle,
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = Ios5Sans,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            style = androidx.compose.ui.text.TextStyle(
                                                shadow = androidx.compose.ui.graphics.Shadow(
                                                    color = Color.Black.copy(alpha = 0.50f),
                                                    offset = androidx.compose.ui.geometry.Offset(0f, titleShadowY),
                                                ),
                                            ),
                                        )
                                    },
                                    right = if ((topSpec?.onAction != null && topSpec.actionLabel != null) || topSpec?.onSearch != null) ({
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            topSpec.takeIf { it.onAction != null && it.actionLabel != null }?.let { spec ->
                                                Ios5BarKey(onClick = { spec.onAction?.invoke() }, dark = true) {
                                                    Ios5BarButtonText(spec.actionLabel.orEmpty())
                                                }
                                            }
                                            if (topSpec?.onSearch != null) {
                                                if (topSpec.onAction != null && topSpec.actionLabel != null) Spacer(Modifier.width(6.dp))
                                                Ios5BarKey(onClick = { topSpec.onSearch.invoke() }, dark = true, horizontalPadding = 8.dp) {
                                                    Icon(Icons.Filled.Search, "搜索", tint = Color.White, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }) else null,
                                )
                            }
                            Row(
                                Modifier.width(panelWidth).fillMaxHeight().padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val song = nowPlayingState.current
                                val isAbook = !receiveActive && playerState.abookId != null
                                val abookTitle = if (isAbook) {
                                    abooks.firstOrNull { it.id == playerState.abookId }?.displayTitle
                                        ?: container.remoteLibrary
                                            .remoteAudiobookForPlayId(playerState.abookId)?.displayTitle
                                        ?: ""
                                } else ""
                                val merged = if (!receiveActive && !isAbook) {
                                    com.aurora.music.ui.player.rememberMergedTitle(song)
                                } else null
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        when {
                                            receiveActive -> song.artist.ifBlank {
                                                airPlayPlaying?.senderName.orEmpty().ifBlank { " " }
                                            }
                                            isAbook -> abookTitle.ifBlank { " " }
                                            else -> song.artist.ifBlank { " " }
                                        },
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
                                if (receiveActive) {
                                    com.aurora.music.ui.player.Ios5ExitCastButton(
                                        onClick = {
                                            when {
                                                podMusicReceive != null -> container.castReceiver.stop()
                                                airPlayAudioReceive != null -> container.castHub.stopSession()
                                            }
                                        },
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.QueueMusic, "队列", tint = Color.White,
                                        modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = { showQueue = true }).padding(5.dp),
                                    )
                                }
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
                            state = nowPlayingState,
                            onSeek = { f ->
                                when {
                                    podMusicReceive != null -> container.castReceiver.seekFraction(f, true)
                                    airPlayAudioReceive != null -> Unit
                                    else -> playerVM.seekTo(f)
                                }
                            },
                            panelWidth = panelWidth,
                            mode = when {
                                receiveActive -> nowPlayingMode
                                playerState.abookId != null -> NowPlayingMode.AUDIOBOOK
                                else -> NowPlayingMode.MUSIC
                            },
                            onSpeedClick = {
                                if (!receiveActive && playerState.abookId != null) {
                                    abookBubble = com.aurora.music.ui.player.AbookBubble.SPEED
                                }
                            },
                            onVolumeClick = {
                                if (!receiveActive && playerState.abookId != null) {
                                    abookBubble = com.aurora.music.ui.player.AbookBubble.GAIN
                                }
                            },
                            caption = receiveCaption,
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
                                when {
                                    receiveActive -> com.aurora.music.ui.player.Ios5LandscapeCastTransport(
                                        state = nowPlayingState,
                                        introActive = introState.active,
                                        onIntroClick = { onIntroClick() },
                                        onTogglePlay = {
                                            when {
                                                podMusicReceive != null -> container.castReceiver.togglePlay()
                                                airPlayAudioReceive != null ->
                                                    if (airPlayAudioReceive.playing) container.castHub.pause()
                                                    else container.castHub.play()
                                            }
                                        },
                                        onNext = {
                                            when {
                                                podMusicReceive != null -> container.castReceiver.next()
                                                airPlayAudioReceive != null -> container.castHub.next()
                                            }
                                        },
                                        onPrevious = {
                                            when {
                                                podMusicReceive != null -> container.castReceiver.previous()
                                                airPlayAudioReceive != null -> container.castHub.previous()
                                            }
                                        },
                                        onSleep = { landscapeSleepBubble = true },
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        canNext = podMusicReceive != null || airPlayPlaying?.capabilities?.canNext == true,
                                        canPrevious = podMusicReceive != null || airPlayPlaying?.capabilities?.canPrevious == true,
                                    )
                                    playerState.abookId != null -> com.aurora.music.ui.player.Ios5LandscapeAudiobookTransport(
                                        state = playerState,
                                        stepSeconds = playerVM.abookStep(),
                                        onSeekBy = { playerVM.seekBySeconds(it) },
                                        onTogglePlay = { playerVM.togglePlay() },
                                        onChapters = {
                                            abookBubble = com.aurora.music.ui.player.AbookBubble.CHAPTERS
                                        },
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                    else -> com.aurora.music.ui.player.Ios5LandscapeTransport(
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
                    }
                    } // BoxWithConstraints（右栏宽自适应）结束
                }
            } else {
                ContentFace(flip, true, true)

            // ---- 播放面（整页 iPod 播放器，翻转进入） ----
            // 播放页根自带空点按拦截触摸，漏不下去，不需要额外拦截层。
            // 动画结束后卸载，避免两棵树常驻耗性能。
            if (nowPlayingExpanded || flip > 0.02f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        rotationY = flip * 180f - 180f
                        cameraDistance = 8 * flipDensity.density
                        alpha = if (flip >= 0.5f) 1f else 0f
                    },
            ) {
                if (receiveActive) {
                    Ios5PlayerPage(
                        state = nowPlayingState,
                        onCollapse = { castPageExpanded = false },
                        onTogglePlay = {
                            when {
                                podMusicReceive != null -> container.castReceiver.togglePlay()
                                airPlayAudioReceive != null ->
                                    if (airPlayAudioReceive.playing) container.castHub.pause()
                                    else container.castHub.play()
                            }
                        },
                        onNext = {
                            when {
                                podMusicReceive != null -> container.castReceiver.next()
                                airPlayAudioReceive != null -> container.castHub.next()
                            }
                        },
                        onPrevious = {
                            when {
                                podMusicReceive != null -> container.castReceiver.previous()
                                airPlayAudioReceive != null -> container.castHub.previous()
                            }
                        },
                        onSeek = { f ->
                            castSeekFraction = f
                            if (podMusicReceive != null) container.castReceiver.seekFraction(f, false)
                        },
                        onSeekFinished = {
                            if (podMusicReceive != null && castSeekFraction >= 0f) {
                                container.castReceiver.seekFraction(castSeekFraction, true)
                            }
                        },
                        onToggleLike = {},
                        onToggleShuffle = {},
                        onCycleRepeat = {},
                        onOpenQueue = {},
                        introActive = introState.active,
                        onIntroClick = { onIntroClick() },
                        mode = nowPlayingMode,
                        sourceCaption = receiveCaption,
                        onExitCast = {
                            when {
                                podMusicReceive != null -> container.castReceiver.stop()
                                airPlayAudioReceive != null -> container.castHub.stopSession()
                            }
                        },
                    )
                } else if (playerState.hasTrack) {
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
                                        startMs = c.startMs,
                                        endMs = c.endMs,
                                    )
                                },
                                coverPath = e.coverUrl,
                                currentPath = e.chapters.getOrNull(e.currentIndex)?.path.orEmpty(),
                                currentStartMs = e.chapters.getOrNull(e.currentIndex)?.startMs ?: 0L,
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
                            onOpenSubtitleSettings = {
                                playerVM.setExpanded(false)
                                tabNav.push(Ios5Routes.SETTINGS_AUDIOBOOK_SUBTITLES)
                            },
                            onOpenMultimodalSettings = {
                                playerVM.setExpanded(false)
                                tabNav.push(Ios5Routes.SETTINGS_MULTIMODAL)
                            },
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
            if (!landscape && nowPlayingExpanded && (!isEbookReader || receiveActive)) {
                BackHandler {
                    if (receiveActive) castPageExpanded = false
                    else playerVM.setExpanded(false)
                }
            }

            // ---- 阅读器：全屏覆盖在内容页之上 ----
            // 阅读器不占 NavHost 返回栈，也不替换 ContentFace：打开期间底层页面
            // （NavHost、Tab、随机列表、滚动位置）一直留在组合里。关闭时只是这层
            // 黑色覆盖层淡出，直接露出原来的 UI，不会先退回再重新加载。
            AnimatedVisibility(
                visible = isEbookReader && !(receiveActive && castPageExpanded && !landscape),
                enter = fadeIn(tween(260)),
                exit = fadeOut(tween(260)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    // 挡住穿透到下层页面的点击
                    Box(
                        Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {},
                    )
                    val epath = readerPath ?: lastReaderPath
                    if (feedReaderOpen) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            com.aurora.music.ui.ebook.RandomReaderScreen(
                                deviceId = feedDeviceId,
                                rootKey = feedRootKey,
                                poolPaths = feedPoolPaths,
                                poolDownloadDeviceId = feedPoolDownloadDeviceId,
                                localSpec = if (feedLocalKey.isNotBlank()) {
                                    com.aurora.music.data.cast.LocalRandomFeedSpec(
                                        key = feedLocalKey,
                                        shelf = feedLocalShelf,
                                        rootId = feedLocalRootId,
                                        rootPath = feedLocalRootPath,
                                        dirPath = feedLocalDirPath,
                                    )
                                } else {
                                    null
                                },
                                onClose = { closeReader() },
                            )
                        }
                    } else if (epath.isNotBlank()) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            com.aurora.music.ui.ebook.EbookReaderScreen(
                                bookPath = epath,
                                onClose = { closeReader() },
                            )
                        }
                    }
                }
            }

            // 短视频播放页：整页浮层，盖住 Tab 栏与 Mini 条；返回键由浮层内部的 BackHandler 先吃掉。
            if (videoFeed != null) {
                Box(Modifier.fillMaxSize()) {
                    VideoPlayerScreen(onClose = { container.videoFeed.close() })
                }
            }

            // 接收投屏的全屏浮层：不管当前在哪个 Tab / 阅读器 / 播放页，都盖在最上面
            com.aurora.music.ui.cast.CastReceiverOverlay()
            com.aurora.music.ui.cast.StandardCastOverlay()

            // 横屏有声书/接收页气泡：盖住整屏。竖屏播放页内部自己管理气泡。
            val abookBubbleNow = abookBubble
            if (abookBubbleNow != null) {
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
                                    startMs = c.startMs,
                                    endMs = c.endMs,
                                )
                            },
                            coverPath = e.coverUrl,
                            currentPath = e.chapters.getOrNull(e.currentIndex)?.path.orEmpty(),
                            currentStartMs = e.chapters.getOrNull(e.currentIndex)?.startMs ?: 0L,
                            positionMs = e.positionMs,
                            lastPlayedAt = e.lastPlayedAt,
                        )
                    }
                when (abookBubbleNow) {
                    com.aurora.music.ui.player.AbookBubble.SPEED -> com.aurora.music.ui.player.SpeedBubble(
                        speed = playerState.abookSpeed,
                        onSpeed = { playerVM.setAbookSpeed(it) },
                        onDismiss = { abookBubble = null },
                    )
                    com.aurora.music.ui.player.AbookBubble.GAIN -> com.aurora.music.ui.player.GainBubble(
                        gain = playerState.abookGain,
                        onGain = { playerVM.setAbookGain(it) },
                        onDismiss = { abookBubble = null },
                    )
                    com.aurora.music.ui.player.AbookBubble.CHAPTERS -> if (abook != null) {
                        com.aurora.music.ui.player.ChapterBubble(
                            book = abook,
                            currentIndex = playerState.currentIndex,
                            chapterAvailable = { i ->
                                if (remoteAbookEntry == null) true
                                else {
                                    val lib = remoteLibraries.firstOrNull { it.deviceId == remoteAbookEntry.deviceId }
                                    lib?.online == true ||
                                        container.remoteLibrary.chapterCached(
                                            remoteAbookEntry.deviceId,
                                            remoteAbookEntry.chapters.getOrNull(i)?.path.orEmpty(),
                                        )
                                }
                            },
                            onSelect = {
                                playerVM.abookJumpToChapter(it)
                                abookBubble = null
                            },
                            onDismiss = { abookBubble = null },
                        )
                    }
                    com.aurora.music.ui.player.AbookBubble.SLEEP -> com.aurora.music.ui.player.SleepBubble(
                        onDismiss = { abookBubble = null },
                    )
                }
            }
            if (landscapeSleepBubble) {
                com.aurora.music.ui.player.SleepBubble(onDismiss = { landscapeSleepBubble = false })
            }
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

