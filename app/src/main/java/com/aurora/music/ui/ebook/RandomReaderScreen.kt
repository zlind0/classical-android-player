package com.aurora.music.ui.ebook

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.RandomFeedBook
import com.aurora.music.data.cast.RandomFeedManager
import com.aurora.music.data.ebook.EbookCastAnchor
import com.aurora.music.data.ebook.EbookCastMode
import com.aurora.music.data.ebook.EbookChapter
import com.aurora.music.data.ebook.EbookFeedResume
import com.aurora.music.data.ebook.EbookReadPrefs
import com.aurora.music.data.ebook.ParsedEbook
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.viewmodel.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 随机阅读：正文仍复用 [ReaderBody]（左右翻页/听书/选项），
 * 外层只负责上下滑切换上一本/下一本，并在两本书之间显示清晰的书名/章节界限。
 */
@Composable
fun RandomReaderScreen(
    deviceId: String,
    rootKey: String,
    poolPaths: List<String>,
    /** 非空 = 本地/设备间集合，整本解析后随机；空 = 远程 NAS 的 feed。 */
    localSpec: com.aurora.music.data.cast.LocalRandomFeedSpec? = null,
    /** 远程文件夹随机：poolPaths 是远端路径，需先整本下载；这里放设备 id。 */
    poolDownloadDeviceId: String = "",
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val manager = container.randomFeed
    val tts = container.ebookTts
    val scope = rememberCoroutineScope()
    val state by manager.state.collectAsStateWithLifecycle()
    val prefs by container.ebookPrefs.prefs.collectAsStateWithLifecycle()
    val feedImages by container.ebookPrefs.feedImages.collectAsStateWithLifecycle()
    val feedCacheSize by container.ebookPrefs.feedCacheSize.collectAsStateWithLifecycle()
    val chapterVersion by manager.chapterVersion.collectAsStateWithLifecycle()
    val ttsPlaying by tts.playing.collectAsStateWithLifecycle()
    val ttsPosition by tts.position.collectAsStateWithLifecycle()

    var optionsOpen by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var musicOpen by remember { mutableStateOf(false) }
    var castOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 与普通阅读共用 Activity 级播放器 VM；投屏也复用同一个控制器。
    val playerVM: PlayerViewModel = viewModel()
    val playerState by playerVM.state.collectAsStateWithLifecycle()
    val cast = container.ebookCast
    val castState by cast.state.collectAsStateWithLifecycle()
    val castPrefs by container.ebookPrefs.castPrefs.collectAsStateWithLifecycle()
    val recentFonts by container.ebookPrefs.recentFonts.collectAsStateWithLifecycle()
    val castActive = castState.mode != EbookCastMode.OFF
    // 切书后“停顿一下再朗读”的任务：再次切书/退出时取消，避免旧目标在新书里突然开口。
    val autoReadJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var starting by remember { mutableStateOf(true) }

    val pager = rememberPagerState(initialPage = 1) { 3 }

    // 切书流水线：手势连滑/耳机连按时串行切书，提示音不被打断（见 [drainSwitches]）。
    val switchPipe = remember { BookSwitchPipeline() }
    // 屏幕最上方 20% 是系统状态栏的下拉区：从这里开始的上下滑不参与切书。
    val topZone = remember { TopZoneDragGuard() }
    // 本地预缓存空了以后，上滑/下滑到不存在的邻居页要直接吃掉。
    val directionGuard = remember { PagerDirectionGuard() }
    val blockDirectionDrag = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y == 0f) return Offset.Zero
                if (source != NestedScrollSource.UserInput && source != NestedScrollSource.SideEffect) {
                    return Offset.Zero
                }
                // VerticalPager 里上滑（下一本）的 available.y 是负的，下滑是正的。
                if (available.y < 0f && !directionGuard.canNext) return Offset(0f, available.y)
                if (available.y > 0f && !directionGuard.canPrevious) return Offset(0f, available.y)
                return Offset.Zero
            }
        }
    }
    val blockTopZoneDrag = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y == 0f) return Offset.Zero
                // 手势从顶部下拉区开始：纵向位移全部吃掉，状态栏照常拉下来，pager 不动。
                if (source == NestedScrollSource.UserInput && topZone.startedInTopZone) {
                    return Offset(0f, available.y)
                }
                // 顶部手势抬手后的惯性也同样吃掉，否则手指没拖动、页面却被甩到下一本。
                if (source == NestedScrollSource.SideEffect && topZone.blockFling) {
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
        }
    }

    // 随机阅读内切章节：复用阅读器已有的按块跳转请求。
    var pendingJump by remember { mutableStateOf<BlockJump?>(null) }
    var jumpGen by remember { mutableLongStateOf(0L) }

    /**
     * 当前真实位置：眼睛（窗口落定回报）与耳朵（TTS 推进）取更靠后的一个。
     * 锁屏听书时眼睛冻在暂停那一刻，只有控制器在前进。
     */
    fun currentPosition(b: RandomFeedBook): Triple<Int, Int, Int> {
        val eye = manager.positionFor(b.path)
        // 遥控页打开时大屏是唯一真相：手机阅读页可能还在追大屏的锚点，
        // 退出投屏/入库/朗读都必须用大屏最后停下的位置，而不是手机上的旧页。
        val castAnchor = cast.state.value.anchor.takeIf { cast.state.value.remote }
        val ear = tts.position.value?.takeIf { tts.bookPath == b.path }
        var chapter = castAnchor?.chapter ?: eye?.chapter ?: b.chapterIndex
        var block = castAnchor?.block ?: eye?.block ?: b.blockIndex
        var char = castAnchor?.char ?: eye?.char ?: b.charOffset
        if (ear != null) {
            val later = when {
                ear.chapter != chapter -> ear.chapter > chapter
                ear.block != block -> ear.block > block
                else -> ear.startChar > char
            }
            if (later) {
                chapter = ear.chapter
                block = ear.block
                char = ear.startChar
            }
        }
        return Triple(chapter, block, char)
    }

    fun persistResume() {
        val b = manager.state.value.current ?: return
        // 耳朵（TTS 位置）可能比眼睛更靠后：锁屏听书时眼睛冻在暂停那一刻，
        // 只有控制器在推进。恢复点取两者里更靠后的一个。
        val (chapter, block, char) = currentPosition(b)
        container.ebookPrefs.saveFeedResume(
            EbookFeedResume(
                deviceId = deviceId,
                rootKey = rootKey,
                path = b.path,
                chapter = chapter,
                block = block,
                char = char,
            ),
        )
    }

    fun showToast(message: String) {
        toast = message
    }

    /** 首屏/重试统一入口：本地、远程文件夹整本模式、远程 NAS feed 三种来源。 */
    suspend fun startFeed() {
        val resume = container.ebookPrefs.initialFeedResume()
        if (localSpec != null) {
            // 本地/设备间：整本解析后再随机。文件夹按路径前缀取整个子树里的书。
            val store = container.ebookStore
            val paths = when {
                localSpec.shelf -> store.shelf.value.map { it.path }
                localSpec.dirPath.isNotBlank() -> {
                    val prefix = localSpec.dirPath.trimEnd('/') + "/"
                    store.booksOfRoot(localSpec.rootId).map { it.path }
                        .filter { it == localSpec.dirPath || it.startsWith(prefix) }
                }
                else -> store.booksOfRoot(localSpec.rootId).map { it.path }
            }
            manager.startLocal(localSpec.key, paths, resume)
        } else if (poolDownloadDeviceId.isNotBlank() && poolPaths.isNotEmpty()) {
            // 远程文件夹/默认书架：先整本下载再解析，保证随机范围就是当前集合。
            manager.startLocal(
                key = "remote:$deviceId:$rootKey",
                poolPaths = poolPaths,
                resume = null,
                downloadDeviceId = poolDownloadDeviceId,
            )
        } else {
            manager.start(
                deviceId = deviceId,
                rootKey = rootKey,
                poolPaths = poolPaths,
                resume = resume,
                includeImages = container.ebookPrefs.feedImages.value,
                cacheSize = container.ebookPrefs.feedCacheSize.value,
            )
        }
    }

    LaunchedEffect(deviceId, rootKey, localSpec, poolDownloadDeviceId, poolPaths) {
        if (manager.state.value.current == null) {
            starting = true
            startFeed()
            starting = false
        } else {
            starting = false
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1600)
            toast = null
        }
    }

    // 耳朵（TTS）推进时同步进内存位置表：锁屏/通知栏停止后仍能保存最后一次读到的位置。
    LaunchedEffect(ttsPosition) {
        val p = ttsPosition ?: return@LaunchedEffect
        if (tts.bookPath == manager.state.value.current?.path) {
            manager.rememberPosition(tts.bookPath, p.chapter, p.block, p.startChar)
        }
    }

    // 停止朗读（读者按停止/通知栏停止/本章读完）时落一次恢复点。
    LaunchedEffect(ttsPlaying) {
        if (!ttsPlaying) persistResume()
    }

    // 全屏阅读：隐藏系统状态栏，避免压住标题 marquee；底部导航键保留。
    // 跟随屏幕方向重建，旋转回竖屏时把系统栏再藏一次。
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    DisposableEffect(orientation) {
        val activity = context as? Activity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 阅读页底栏是深色：导航键用白图标；打开选项页（纸面）时按主题切深色。
    DisposableEffect(optionsOpen, prefs.theme) {
        val activity = context as? Activity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        val previous = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightNavigationBars =
            optionsOpen && themeOf(prefs.theme).bg.luminance() > 0.5f
        onDispose { controller?.isAppearanceLightNavigationBars = previous ?: false }
    }

    /** 停掉仍在播的切书提示音：换章节/退出时不让它稍后抢着开口。 */
    fun cancelSwitchAnnouncement() {
        switchPipe.announceJob?.cancel()
        switchPipe.announceJob = null
        switchPipe.announcing = false
        switchPipe.readAfter = false
    }

    /** 提示音之后真正开口：读当前（最新）这一本。 */
    suspend fun readCurrentBook() {
        val book = manager.state.value.current ?: return
        // 提示音期间新书已经完成排版：用屏幕上实际的第一字（而不是旧随机锚点）起读；
        // playFrom 内部还会吸附到该字所在句子的句首。
        val (c, b, ch) = currentPosition(book)
        val chapter = manager.loadChapter(book.path, c) ?: return
        val parsed = feedParsed(book, c, chapter)
        tts.setBook("feed:${book.path}", parsed, book.path, force = true)
        tts.playFrom(c, b, ch)
    }

    /**
     * 播切书提示音（音效 + “上一本/下一本书名”），播完接着读最新一本。
     *
     * 连滑时的约定：
     * - 提示音还没播完又切了书：不打断、不重播，播完后直接读最后落定的那本；
     * - 提示音已经播完（甚至已经开始读）才切书：重新播一遍提示音，再读最新那本。
     */
    fun startBookAnnouncement(direction: Int, title: String) {
        switchPipe.announcing = true
        switchPipe.readAfter = true
        switchPipe.announceJob = scope.launch {
            try {
                tts.playBookSwitchAnnouncement(direction, title)
                while (true) {
                    // 连续切书：等排队的那几本都换完再开口，避免读完旧书又被下一本打断。
                    withTimeoutOrNull(5_000L) {
                        while (switchPipe.running || manager.state.value.loading) delay(40)
                    }
                    if (!switchPipe.readAfter) break
                    switchPipe.readAfter = false
                    readCurrentBook()
                    // 主循环真正开播前还有一小段空窗：这期间连滑不能被当成静音翻书。
                    withTimeoutOrNull(2_000L) { while (!tts.playing.value) delay(30) }
                    // 空窗里又切了书（提示音已播过）：直接读最新那本，不再重播。
                    if (!switchPipe.readAfter) break
                }
            } finally {
                switchPipe.announcing = false
            }
        }
    }

    /** 串行消费切书请求：一次只翻一本，翻完再处理下一本。 */
    suspend fun drainSwitches() {
        try {
            while (true) {
                val req = switchPipe.queue.removeFirstOrNull() ?: break
                if (switchPipe.closed) break
                val ok = manager.turn(req.direction)
                if (!ok) {
                    showToast(manager.state.value.error ?: "切换失败")
                    // 失败后不要回放积压的滑动：否则链路恢复时会一口气刷过很多本。
                    switchPipe.queue.clear()
                    runCatching { pager.scrollToPage(1) }
                    break
                }
                // 新书数据已经就位：这时才把 pager 收回中间。相邻页一直是最终布局，
                // 收回去的画面和刚才停住的那一页完全一致，不会有跳变。
                runCatching { pager.scrollToPage(1) }
                // 切过去就先把新书的目标位置记下：后台听书时进程被杀也能恢复到最后切到的那本。
                persistResume()
                if (!req.read) continue
                switchPipe.readAfter = true
                // 提示音还在播：让它播完（播完由那个任务读最新一本），这里既不打断也不重播。
                if (switchPipe.announcing) continue
                startBookAnnouncement(req.direction, manager.state.value.current?.title.orEmpty())
            }
        } finally {
            switchPipe.running = false
        }
    }

    /**
     * 切书统一入口：耳机/通知栏、上下滑、投屏页都走它。
     * 请求只入队，由 [drainSwitches] 串行执行，快速连滑不会打断提示音，也不会让 TTS 卡死。
     * @param autoRead 切好后是否接着朗读（耳机切书总是读；手势只在原本正在朗读时才续读）。
     */
    fun switchBook(direction: Int, autoRead: Boolean, announce: Boolean = false) {
        if (switchPipe.closed) return
        if (manager.state.value.current == null) {
            scope.launch { runCatching { pager.scrollToPage(1) } }
            return
        }
        if (direction > 0 && !manager.state.value.canNext) {
            showToast("没有更多随机书了")
            scope.launch { runCatching { pager.scrollToPage(1) } }
            return
        }
        if (direction < 0 && manager.state.value.previous == null) {
            // 还没有上一本：别把请求送进 turn 再弹失败提示，直接回正。
            scope.launch { runCatching { pager.scrollToPage(1) } }
            return
        }
        if (manager.state.value.loading) {
            // 上一本还在路上：先把 pager 收回中间，让这次滑动也能落回页面上；
            // 请求照常入队，等上一本落定后接着切。
            scope.launch { runCatching { pager.scrollToPage(1) } }
        }
        // 听书会话还活着就续读：提示音播放期间 tts.playing 已经是 false，
        // 但用户仍在听书，此时连滑不能被当成静音翻书（旧行为会因此不再开口）。
        val listening = autoRead || announce || tts.playing.value ||
            switchPipe.announcing || switchPipe.readAfter
        // 正在读正文就先停；提示音期间 tts.playing 为 false，不会误停提示音。
        if (tts.playing.value) tts.stop(savePos = false)
        persistResume()
        switchPipe.queue.addLast(BookSwitchRequest(direction, listening))
        if (!switchPipe.running) {
            switchPipe.running = true
            scope.launch { drainSwitches() }
        }
    }

    /**
     * 底栏“随机章节”：在整本书里随机跳到一个位置（章节随机，章内段落也随机）。
     * 正在朗读时先把旧位置静音，页面跳过去后再接着读新位置。
     */
    fun switchChapter() {
        val book = manager.state.value.current ?: return
        val count = book.chapterCount.coerceAtLeast(book.chapterTitles.size).coerceAtLeast(1)
        val target = kotlin.random.Random.nextInt(count)
        // 提示音/等待续读期间 tts.playing 已经是 false，但听书会话还活着。
        val wasPlaying = tts.playing.value || switchPipe.announcing || switchPipe.readAfter
        autoReadJob[0]?.cancel()
        autoReadJob[0] = null
        cancelSwitchAnnouncement()
        if (tts.playing.value) tts.stop(savePos = false)
        autoReadJob[0] = scope.launch {
            // 先取正文，才能在这一章里随机落到某个段落，而不是永远从章首开始。
            val chapter = manager.loadChapter(book.path, target)
            val blockCount = chapter?.blocks?.size ?: 0
            val block = if (blockCount > 0) kotlin.random.Random.nextInt(blockCount) else 0
            manager.rememberPosition(book.path, target, block, 0)
            persistResume()
            // 投屏中：大屏也要跟着跳到随机章节，否则只有手机页面动了。
            if (cast.state.value.mode != EbookCastMode.OFF) {
                cast.setReaderAnchor(EbookCastAnchor(target, block, 0), force = true)
            }
            jumpGen += 1
            pendingJump = BlockJump(target, block, 0, jumpGen)
            if (!wasPlaying) return@launch
            // 等页面跳到新位置再开口，避免音频还念着旧章标题的时候画面已经翻走。
            delay(450)
            val ch = chapter ?: manager.loadChapter(book.path, target) ?: return@launch
            val parsed = feedParsed(book, target, ch)
            tts.setBook("feed:${book.path}", parsed, book.path, force = true)
            tts.playFrom(target, block, 0)
        }
    }

    /** 投屏页底部的「朗读」：从大屏/手机当前停下的位置开始或停止朗读。 */
    fun toggleTts() {
        if (tts.playing.value) {
            tts.stop(userInitiated = true, savePos = false)
        } else {
            scope.launch { readCurrentBook() }
        }
    }

    /** 目录跳转：只换章节/段落，不写本地书架进度。 */
    fun jumpToChapter(chapter: Int, block: Int) {
        val book = manager.state.value.current ?: return
        val wasPlaying = tts.playing.value || switchPipe.announcing || switchPipe.readAfter
        autoReadJob[0]?.cancel()
        autoReadJob[0] = null
        cancelSwitchAnnouncement()
        if (tts.playing.value) tts.stop(savePos = false)
        manager.rememberPosition(book.path, chapter, block, 0)
        persistResume()
        // 投屏中：目录跳转同时把大屏带过去。
        if (cast.state.value.mode != EbookCastMode.OFF) {
            cast.setReaderAnchor(EbookCastAnchor(chapter, block, 0), force = true)
        }
        jumpGen += 1
        pendingJump = BlockJump(chapter, block, 0, jumpGen)
        tocOpen = false
        if (!wasPlaying) return
        autoReadJob[0] = scope.launch {
            delay(450)
            val ch = manager.loadChapter(book.path, chapter) ?: return@launch
            val parsed = feedParsed(book, chapter, ch)
            tts.setBook("feed:${book.path}", parsed, book.path, force = true)
            tts.playFrom(chapter, block, 0)
        }
    }

    // 耳机/通知栏上一首/下一首：随机阅读里切成上一本/下一本，并接着朗读。
    DisposableEffect(Unit) {
        val hook: (Int) -> Boolean = { direction ->
            if (manager.state.value.current == null) {
                false
            } else {
                switchBook(direction, autoRead = true, announce = true)
                true
            }
        }
        tts.onBookTurn = hook
        // 跨章续读：TTS 读完本章时向服务端取下一章，接在同一播放流里。
        tts.chapterTailLoader = { chapter -> manager.loadChapter(tts.bookPath, chapter)?.blocks }
        // 随机阅读不写本地书库进度（远程相对路径在本地没有条目）。
        tts.persistProgress = false
        // 熄屏/通知栏恢复没有排版：从上次那个字所在段落开头读，而不是段落中间。
        tts.resumeFromBlockStart = true
        onDispose {
            if (tts.onBookTurn === hook) tts.onBookTurn = null
            tts.chapterTailLoader = null
            tts.persistProgress = true
            tts.resumeFromBlockStart = false
            autoReadJob[0]?.cancel()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) persistResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun closeReader() {
        switchPipe.closed = true
        switchPipe.queue.clear()
        cancelSwitchAnnouncement()
        autoReadJob[0]?.cancel()
        autoReadJob[0] = null
        persistResume()
        tts.stop(savePos = false)
        cast.stop()
        manager.close()
        onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            // 退出随机阅读时清掉远程书目：通知栏不该停留在没有会话的随机书上。
            switchPipe.closed = true
            switchPipe.queue.clear()
            cancelSwitchAnnouncement()
            tts.resetBook()
            cast.setBook(null)
            cast.stop()
            FeedPageCache.clear()
            manager.close()
        }
    }

    BackHandler {
        when {
            tocOpen -> tocOpen = false
            musicOpen -> musicOpen = false
            // 投屏中是“非临时页面”：只有结束投屏能退出，返回键吞掉
            castOpen && castActive -> Unit
            castOpen -> castOpen = false
            optionsOpen -> optionsOpen = false
            else -> closeReader()
        }
    }

    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
        if (pager.isScrollInProgress) return@LaunchedEffect
        val delta = pager.settledPage - 1
        if (delta != 0) {
            // 手势切书：是否续读由 switchBook 判断（原本在朗读/提示音还没播完才算听书）。
            // 不在这里回收 pager：等新书数据就位后再收，停住的相邻页本身就是最终布局。
            switchBook(if (delta > 0) 1 else -1, autoRead = false)
        }
    }

    // 切书后停顿一下再朗读由 [switchBook] 内部的任务负责：这里只保留当前书供界面使用。
    val currentBook = state.current
    LaunchedEffect(state.canNext, state.previous) {
        directionGuard.canNext = state.canNext
        directionGuard.canPrevious = state.previous != null
    }

    if (currentBook == null) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (starting || state.loading) {
                    LottieLoader(modifier = Modifier.size(72.dp))
                } else {
                    Text(
                        state.error ?: "没有可阅读的随机书",
                        fontSize = 15.sp,
                        color = Color(0xFF6B7280),
                        textAlign = TextAlign.Center,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Ios5GlossButton(
                        text = "重试",
                        onClick = {
                            scope.launch {
                                starting = true
                                startFeed()
                                starting = false
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Ios5GlossButton("返回", onClose)
                }
            }
        }
        return
    }

    val displayBook = feedParsedBook(manager, currentBook, chapterVersion)
    val bookKey = feedBookKey(currentBook)
    val prevBook = state.previous
    val nextBook = state.next

    val currentFeed = FeedReaderUi(
        bookTitle = currentBook.title,
        onSaveToShelf = {
            val (c, b, ch) = currentPosition(currentBook)
            scope.launch {
                val ok = manager.saveToShelf(deviceId, currentBook, c, b, ch)
                showToast(if (ok) "已存入默认书架（含当前进度）" else "入库失败，请检查连接")
            }
        },
        onToc = { tocOpen = true },
        showSaveToShelf = localSpec == null && poolDownloadDeviceId.isBlank() && !manager.isLocalMode(),
        onChapterEdge = {
            scope.launch { manager.cacheWholeBook(deviceId, currentBook.path) }
        },
        onPositionChanged = { chapter, block, char ->
            manager.rememberPosition(currentBook.path, chapter, block, char)
            // 始终记录眼睛位置：开始投屏时 start() 立刻用它定位大屏。
            // 只在投屏开启后才记录会让第一帧回退到空白的第 0 章。
            cast.setReaderAnchor(EbookCastAnchor(chapter, block, char))
        },
        onPlayFrom = { chapter, block, char ->
            scope.launch {
                val ch = manager.loadChapter(currentBook.path, chapter)
                if (ch != null) {
                    val parsed = feedParsed(currentBook, chapter, ch)
                    tts.setBook("feed:${currentBook.path}", parsed, currentBook.path, force = true)
                    tts.playFrom(chapter, block, char)
                }
            }
        },
        onRandomChapter = ::switchChapter,
        marqueeTitle = true,
    )

    // 投屏方向开关：遥控页开着 = 遥控端驱动大屏，关着 = 手机阅读进度驱动大屏。
    LaunchedEffect(castOpen, castActive) {
        cast.setRemote(castOpen && castActive)
    }

    // 投屏要整本正文：打开投屏时静默缓存整本，章节陆续到位后同步给大屏。
    // 进入随机阅读就把当前书注入投屏控制器，已有投屏不会因为打开/关闭控制页而断掉。
    LaunchedEffect(displayBook) {
        cast.setBook(displayBook)
    }
    // 换书（上下滑）时强制把大屏锚点切到新书的位置；同一本书内章节缓存更新不动它。
    LaunchedEffect(currentBook.path) {
        if (cast.state.value.mode != EbookCastMode.OFF) {
            val (c, b, ch) = currentPosition(currentBook)
            cast.setReaderAnchor(EbookCastAnchor(c, b, ch), force = true)
        }
    }
    // 整本还没解析完时，大屏翻到未缓存的章节会同步回调这里，从 feed 按章取正文，
    // 所以投屏不依赖“整本 EPUB 解析完成”就能从当前屏幕内容正常显示、翻页。
    DisposableEffect(currentBook.path) {
        val path = currentBook.path
        cast.setChapterProvider { chapter ->
            manager.cachedChapter(path, chapter)?.takeIf { it.blocks.isNotEmpty() }
                ?: runBlocking {
                    withTimeoutOrNull(20_000L) {
                        manager.loadChapter(path, chapter)
                    }
                }
        }
        onDispose { cast.setChapterProvider(null) }
    }
    LaunchedEffect(castOpen, castActive) {
        if (!castOpen && !castActive) return@LaunchedEffect
        manager.cacheWholeBook(deviceId, currentBook.path)
    }
    // 刚开始投屏时把大屏定位到当前阅读位置。
    LaunchedEffect(castActive) {
        if (!castActive) return@LaunchedEffect
        val (c, b, ch) = currentPosition(currentBook)
        cast.setReaderAnchor(EbookCastAnchor(c, b, ch))
    }

    // 大屏最后停下的锚点：投屏被外部结束（设备断开 / HDMI 拔线）时 state 会先清空，
    // 这里留一份，退出投屏时把手机阅读进度和恢复点补到同一位置。
    var lastCastAnchor by remember { mutableStateOf<EbookCastAnchor?>(null) }
    LaunchedEffect(castState.anchor) {
        castState.anchor?.let { lastCastAnchor = it }
    }
    // 遥控端翻页：大屏把左上角锚点报回来，手机阅读页跟着跳，并立刻记成恢复点。
    // 随机阅读没有本地进度库，必须自己把位置写进 feed resume，否则退出投屏会回到旧位置。
    LaunchedEffect(Unit) {
        cast.remoteAnchors.collect { a ->
            if (cast.state.value.mode == EbookCastMode.OFF) return@collect
            val b = manager.state.value.current ?: return@collect
            manager.rememberPosition(b.path, a.chapter, a.block, a.char)
            persistResume()
            jumpGen += 1
            pendingJump = BlockJump(a.chapter, a.block, a.char, jumpGen)
        }
    }
    // 投屏结束（包括设备断开 / HDMI 拔线）：把最后的大屏位置补进手机进度。
    LaunchedEffect(castActive) {
        if (castActive) return@LaunchedEffect
        val a = lastCastAnchor ?: return@LaunchedEffect
        val b = manager.state.value.current
        if (b != null) {
            manager.rememberPosition(b.path, a.chapter, a.block, a.char)
            persistResume()
        }
        lastCastAnchor = null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 每一页都比屏幕高出一个“上一本书名”的高度：翻页时这一条先露出，
        // 落定后正好藏到屏幕上沿之外，页面看到的始终是阅读器最终布局。
        BoundaryPager(boundary = BOOK_BOUNDARY_HEIGHT, modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                // 记录手势起点：从屏幕上方 20%（状态栏下拉区）开始的上下滑不切书。
                .pointerInput(Unit) {
                    var tracking: PointerId? = null
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                            if (down != null && tracking == null) {
                                tracking = down.id
                                topZone.blockFling = false
                                topZone.startedInTopZone = down.position.y < size.height * TOP_STATUS_BAR_ZONE
                            }
                            val tracked = tracking?.let { id -> event.changes.firstOrNull { it.id == id } }
                            if (tracked != null && !tracked.pressed) {
                                tracking = null
                                // 抬手这一刻 scrollable 会按拖动速度起惯性：顶部手势连惯性一起挡掉。
                                topZone.blockFling = topZone.startedInTopZone
                                topZone.startedInTopZone = false
                            }
                        }
                    }
                }
                .nestedScroll(blockTopZoneDrag)
                .nestedScroll(blockDirectionDrag),
            userScrollEnabled = !optionsOpen,
            beyondViewportPageCount = 1,
        ) { page ->
            val book = when (page) {
                0 -> prevBook
                2 -> nextBook
                else -> currentBook
            }
            val previousOfPage = when (page) {
                0 -> null
                1 -> prevBook
                else -> currentBook
            }
            Column(Modifier.fillMaxSize()) {
                BoundaryStrip(previousOfPage, prefs)
                if (book == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            if (page == 0) "没有上一本了" else "没有下一本了",
                            fontSize = 15.sp,
                            color = themeOf(prefs.theme).ink.copy(alpha = 0.45f),
                        )
                    }
                } else if (page == 1) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ReaderBody(
                            bookPath = currentBook.path,
                            book = displayBook,
                            initial = GlobalPage(
                                currentBook.chapterIndex.coerceIn(0, displayBook.chapters.size - 1),
                                0,
                            ),
                            initBlock = currentBook.blockIndex,
                            initChar = currentBook.charOffset,
                            pendingJump = pendingJump,
                            onConsumeJump = { pendingJump = null },
                            prefs = prefs,
                            toast = toast,
                            onToast = ::showToast,
                            onOpenToc = { _, _ -> },
                            onOpenOptions = { optionsOpen = true },
                            onOpenMusic = { musicOpen = true },
                            onOpenCast = { castOpen = true },
                            castActive = castActive,
                            bookKey = bookKey,
                            chapterLoader = { chapter -> manager.loadChapter(currentBook.path, chapter)?.blocks },
                            persistPages = false,
                            prefetchChapters = 0,
                            feed = currentFeed,
                            active = true,
                        )
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        NeighborFeedBody(
                            manager = manager,
                            book = book,
                            prefs = prefs,
                            chapterVersion = chapterVersion,
                        )
                    }
                }
            }
        }
        }

        if (optionsOpen) {
            EbookOptionsPage(
                onBack = { optionsOpen = false },
                bookPath = currentBook.path,
                book = displayBook,
                onToast = ::showToast,
                onJumpAnchor = { _, _ -> },
                feedMode = true,
                feedImages = feedImages,
                onSetFeedImages = { container.ebookPrefs.setFeedImages(it) },
                feedCacheSize = feedCacheSize,
                onSetFeedCacheSize = { container.ebookPrefs.setFeedCacheSize(it) },
            )
        }

        if (castOpen) {
            EbookCastPage(
                state = castState,
                prefs = castPrefs,
                playerState = playerState,
                readerFontPath = prefs.fontPath,
                recentFonts = recentFonts,
                bookPath = currentBook.path,
                chapterTitles = remember(displayBook) { displayBook.chapters.map { it.title } },
                onRestoreCastAnchor = { c, b -> cast.restoreAnchor(EbookCastAnchor(c, b, 0)) },
                onBack = { castOpen = false },
                onStartWeb = { cast.start(EbookCastMode.WEB) },
                onStartDevice = { cast.startDevice(it) },
                onStartPresentation = {
                    val displays = currentPresentationDisplays(context)
                    if (displays.isEmpty()) {
                        showToast("未检测到 HDMI 显示器")
                    } else {
                        cast.start(EbookCastMode.PRESENTATION, displays.firstOrNull()?.name.orEmpty())
                    }
                },
                onStop = {
                    cast.stop()
                    musicOpen = false
                    castOpen = false
                },
                onPrevPage = { cast.turn(-1) },
                onNextPage = { cast.turn(1) },
                // 翻页键仍翻页；在这一行上下滑则切上一本/下一本，和阅读页手势一致。
                onPrevBook = { switchBook(-1, autoRead = tts.playing.value) },
                onNextBook = { switchBook(1, autoRead = tts.playing.value) },
                onToc = { tocOpen = true },
                onSaveToShelf = if (currentFeed.showSaveToShelf) currentFeed.onSaveToShelf else null,
                onRandomChapter = ::switchChapter,
                ttsPlaying = ttsPlaying,
                onToggleTts = ::toggleTts,
                onTogglePlay = { playerVM.togglePlay() },
                onPrevious = { playerVM.previous() },
                onNext = { playerVM.next() },
                onJumpTrack = { playerVM.jumpTo(it) },
                onSeek = { playerVM.seekTo(it) },
                onToggleShuffle = { playerVM.toggleShuffle() },
                onCycleRepeat = { playerVM.cycleRepeat() },
                onSetShowMusic = {
                    container.ebookPrefs.setCastShowMusic(castState.mode != EbookCastMode.PRESENTATION, it)
                },
                onSetTurnUnit = {
                    container.ebookPrefs.setCastTurnUnit(castState.mode != EbookCastMode.PRESENTATION, it)
                },
                onSetMusicOnCast = {
                    container.ebookPrefs.setCastMusicOnCast(castState.mode != EbookCastMode.PRESENTATION, it)
                },
                onSetTheme = { container.ebookPrefs.setCastTheme(it) },
                onSetFontSize = {
                    container.ebookPrefs.setCastFontSize(castState.mode != EbookCastMode.PRESENTATION, it)
                },
                onSetColumnWidth = {
                    container.ebookPrefs.setCastColumnWidth(castState.mode != EbookCastMode.PRESENTATION, it)
                },
                onSetFontPath = { container.ebookPrefs.setFontPath(it) },
            )
        }

        // 目录叠在投屏页之上：从投屏页点「目录」时，投屏页仍然保持打开（遥控方向不变）。
        if (tocOpen) {
            val (c, b, _) = currentPosition(currentBook)
            EbookTocPage(
                book = displayBook,
                currentSpine = c,
                currentBlock = b,
                onBack = { tocOpen = false },
                onJump = { spine, block -> jumpToChapter(spine, block) },
            )
        }

        if (musicOpen) {
            ReaderMusicBubble(
                state = playerState,
                onDismiss = { musicOpen = false },
                onJump = { playerVM.jumpTo(it) },
                onTogglePlay = { playerVM.togglePlay() },
                onPrevious = { playerVM.previous() },
                onNext = { playerVM.next() },
                onSeek = { playerVM.seekTo(it) },
                onToggleShuffle = { playerVM.toggleShuffle() },
                onCycleRepeat = { playerVM.cycleRepeat() },
            )
        }

        if (toast != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    toast.orEmpty(),
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier
                        .padding(bottom = 116.dp)
                        .background(Color.Black.copy(alpha = 0.78f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    // HDMI 副屏：复用同一个网页渲染，拔线即停止投屏
    EbookCastPresentationHost(
        active = castState.mode == EbookCastMode.PRESENTATION,
        url = castState.url,
        onUnavailable = {
            if (cast.state.value.mode == EbookCastMode.PRESENTATION) {
                cast.stop()
                showToast("外接显示器已断开，投屏已停止")
            }
        },
    )
}

/** 每页比屏幕多出来的那一条：上一本书的书名/章节，翻页时先露出来，落定后藏到屏幕上沿之外。 */
private val BOOK_BOUNDARY_HEIGHT = 30.dp

/**
 * 屏幕最上方这一段是系统状态栏的下拉区：从这里开始的上下滑只交给系统，
 * 不参与随机阅读的切上一本/下一本（否则拉状态栏会顺带翻书）。
 */
private const val TOP_STATUS_BAR_ZONE = 0.2f

/** 切书请求：连滑/连按时排队，由同一个协程串行执行。 */
private data class BookSwitchRequest(
    val direction: Int,
    /** 切完是否接着读（听书会话还活着）。 */
    val read: Boolean,
)

/**
 * 切书流水线的跨重组状态。连续快速切书时只跑一个消费协程：
 * 提示音不被打断，播完后统一读最后落定的那一本。
 */
private class BookSwitchPipeline {
    val queue = ArrayDeque<BookSwitchRequest>()
    var running = false
    var closed = false
    /** 切书提示音（音效 + 报书名）是否正在播；播放期间不打断也不重播。 */
    var announcing = false
    /** 提示音之后还要读最新一本（连续切书时一直保持，直到真正开口）。 */
    var readAfter = false
    var announceJob: Job? = null
}

/** 手势起点守卫：本次上下滑是否从屏幕上方状态栏下拉区开始。 */
private class TopZoneDragGuard {
    var startedInTopZone = false
    /** 顶部手势抬手后的惯性：没有拖动位移，惯性也不能让 pager 翻页。 */
    var blockFling = false
}

/** 没有上一本/下一本时，把对应方向的拖动和惯性一起吃掉。 */
private class PagerDirectionGuard {
    var canNext = true
    var canPrevious = true
}

/**
 * 按「屏幕高 + 分界条高」排版 pager，再整体上移一个分界条高：
 * 当前页正文正好铺满屏幕（含导航栏区域），分界条藏在屏幕上沿外，翻页时才露出来。
 * 用自定义 layout 而不是 requiredHeight，避免父级 maxHeight 把 pager 压回屏幕高。
 */
@Composable
private fun BoundaryPager(
    boundary: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val boundaryPx = with(density) { boundary.roundToPx() }
    Box(
        modifier.layout { measurable, constraints ->
            val pageHeight = constraints.maxHeight + boundaryPx
            val placeable = measurable.measure(
                constraints.copy(minHeight = pageHeight, maxHeight = pageHeight),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(0, -boundaryPx)
            }
        },
    ) { content() }
}

@Composable
private fun BoundaryStrip(book: RandomFeedBook?, prefs: EbookReadPrefs) {
    val th = themeOf(prefs.theme)
    Box(
        Modifier.fillMaxWidth().height(BOOK_BOUNDARY_HEIGHT).background(th.bg),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (book != null) {
            Text(
                "${book.title} · ${book.chapterTitle.ifBlank { "第 ${book.chapterIndex + 1} 章" }}",
                fontSize = 11.sp,
                color = th.ink.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp)
                .background(th.ink.copy(alpha = 0.08f)),
        )
    }
}

/** 相邻书直接按阅读器最终布局排版：同样的顶栏（静态书名）、正文分页和底部控制条。 */
@Composable
private fun NeighborFeedBody(
    manager: RandomFeedManager,
    book: RandomFeedBook,
    prefs: EbookReadPrefs,
    chapterVersion: Int,
) {
    val parsed = feedParsedBook(manager, book, chapterVersion)
    ReaderBody(
        bookPath = book.path,
        book = parsed,
        initial = GlobalPage(
            book.chapterIndex.coerceIn(0, parsed.chapters.size - 1),
            0,
        ),
        initBlock = book.blockIndex,
        initChar = book.charOffset,
        pendingJump = null,
        onConsumeJump = {},
        prefs = prefs,
        toast = null,
        onToast = {},
        onOpenToc = { _, _ -> },
        onOpenOptions = {},
        onOpenMusic = {},
        onOpenCast = {},
        castActive = false,
        bookKey = feedBookKey(book),
        chapterLoader = { chapter -> manager.loadChapter(book.path, chapter)?.blocks },
        persistPages = false,
        prefetchChapters = 0,
        feed = FeedReaderUi(
            bookTitle = book.title,
            onSaveToShelf = {},
            onChapterEdge = {},
            onPositionChanged = { _, _, _ -> },
            onPlayFrom = { _, _, _ -> },
            onRandomChapter = {},
            // 只有落到当前页才开始 marquee：相邻页先静态排好。
            marqueeTitle = false,
        ),
        active = false,
    )
}

private fun feedBookKey(book: RandomFeedBook): String =
    "${book.path}|${book.chapterIndex}|${book.blockIndex}|${book.charOffset}"

@Composable
private fun feedParsedBook(
    manager: RandomFeedManager,
    book: RandomFeedBook,
    chapterVersion: Int,
): ParsedEbook {
    val count = book.chapterCount.coerceAtLeast(book.chapterTitles.size).coerceAtLeast(1)
    return remember(book.path, chapterVersion) {
        val chapters = (0 until count).map { i ->
            val title = book.chapterTitles.getOrElse(i) { "第 ${i + 1} 章" }
            val cached = manager.cachedChapter(book.path, i)
            EbookChapter(cached?.title?.ifBlank { title } ?: title, cached?.blocks.orEmpty())
        }
        val toc = manager.tocFor(book.path)
            ?: chapters.mapIndexed { i, ch ->
                com.aurora.music.data.ebook.EbookTocEntry(i, 0, ch.title, 1)
            }
        ParsedEbook(title = book.title, author = book.author, chapters = chapters, toc = toc)
    }
}

/** 只把已缓存的章节塞进 ParsedEbook：TTS 的朗读单位跟着已取到的正文走。 */
private fun feedParsed(
    book: RandomFeedBook,
    chapterIndex: Int,
    chapter: EbookChapter,
): ParsedEbook {
    val count = book.chapterCount.coerceAtLeast(book.chapterTitles.size).coerceAtLeast(1)
    val chapters = (0 until count).map { i ->
        val title = book.chapterTitles.getOrElse(i) { "第 ${i + 1} 章" }
        if (i == chapterIndex) chapter else EbookChapter(title, emptyList())
    }
    return ParsedEbook(title = book.title, author = book.author, chapters = chapters)
}
