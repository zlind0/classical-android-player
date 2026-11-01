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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.RandomFeedBook
import com.aurora.music.data.cast.RandomFeedManager
import com.aurora.music.data.ebook.EbookChapter
import com.aurora.music.data.ebook.EbookFeedResume
import com.aurora.music.data.ebook.EbookReadPrefs
import com.aurora.music.data.ebook.ParsedEbook
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.ios5.Ios5GlossButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 随机阅读：正文仍复用 [ReaderBody]（左右翻页/听书/选项），
 * 外层只负责上下滑切换上一本/下一本，并在两本书之间显示清晰的书名/章节界限。
 */
@Composable
fun RandomReaderScreen(
    deviceId: String,
    rootKey: String,
    poolPaths: List<String>,
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
    val chapterVersion by manager.chapterVersion.collectAsStateWithLifecycle()
    val ttsPlaying by tts.playing.collectAsStateWithLifecycle()
    val ttsPosition by tts.position.collectAsStateWithLifecycle()

    var optionsOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    // 切书后“停顿一下再朗读”的任务：再次切书/退出时取消，避免旧目标在新书里突然开口。
    val autoReadJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var starting by remember { mutableStateOf(true) }

    val pager = rememberPagerState(initialPage = 1) { 3 }

    // 随机阅读内切章节：复用阅读器已有的按块跳转请求。
    var pendingJump by remember { mutableStateOf<BlockJump?>(null) }
    var jumpGen by remember { mutableLongStateOf(0L) }

    /**
     * 当前真实位置：眼睛（窗口落定回报）与耳朵（TTS 推进）取更靠后的一个。
     * 锁屏听书时眼睛冻在暂停那一刻，只有控制器在前进。
     */
    fun currentPosition(b: RandomFeedBook): Triple<Int, Int, Int> {
        val eye = manager.positionFor(b.path)
        val ear = tts.position.value?.takeIf { tts.bookPath == b.path }
        var chapter = eye?.chapter ?: b.chapterIndex
        var block = eye?.block ?: b.blockIndex
        var char = eye?.char ?: b.charOffset
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

    LaunchedEffect(deviceId, rootKey) {
        if (manager.state.value.current == null) {
            starting = true
            val resume = container.ebookPrefs.initialFeedResume()
            manager.start(
                deviceId = deviceId,
                rootKey = rootKey,
                poolPaths = poolPaths,
                resume = resume,
                includeImages = container.ebookPrefs.feedImages.value,
            )
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

    /**
     * 切书统一入口：耳机/通知栏、上下滑都走它。
     * @param autoRead 切好后是否停顿一下再朗读（耳机切书总是读；手势只在原本正在朗读时才续读）。
     */
    fun switchBook(direction: Int, autoRead: Boolean) {
        if (manager.state.value.loading) {
            // 还在换书：先把 pager 收回中间，别停在相邻页上。
            scope.launch { runCatching { pager.scrollToPage(1) } }
            return
        }
        autoReadJob[0]?.cancel()
        autoReadJob[0] = null
        val wasPlaying = tts.playing.value
        if (wasPlaying) tts.stop(savePos = false)
        persistResume()
        autoReadJob[0] = scope.launch {
            val ok = manager.turn(direction)
            if (!ok) {
                showToast(manager.state.value.error ?: "切换失败")
                runCatching { pager.scrollToPage(1) }
                return@launch
            }
            // 新书数据已经就位：这时才把 pager 收回中间。相邻页一直是最终布局，
            // 收回去的画面和刚才停住的那一页完全一致，不会有跳变。
            runCatching { pager.scrollToPage(1) }
            // 切过去就先把新书的目标位置记下：后台听书时进程被杀也能恢复到最后切到的那本。
            persistResume()
            if (!autoRead) return@launch
            // 先让旧书的音频彻底停下，再按新书随机位置开口。
            delay(750)
            val book = manager.state.value.current ?: return@launch
            val chapter = manager.loadChapter(book.path, book.chapterIndex) ?: return@launch
            val parsed = feedParsed(book, book.chapterIndex, chapter)
            tts.setBook("feed:${book.path}", parsed, book.path, force = true)
            tts.playFrom(book.chapterIndex, book.blockIndex, book.charOffset)
        }
    }

    /**
     * 底栏“上一章/下一章”：在本书里随机跳到一个**不同的**章节，从章首开始。
     * 正在朗读时先把旧章静音，页面跳过去后再接着读新章。
     */
    fun switchChapter() {
        val book = manager.state.value.current ?: return
        val count = book.chapterCount.coerceAtLeast(book.chapterTitles.size).coerceAtLeast(1)
        if (count <= 1) {
            showToast("这本书只有一章")
            return
        }
        val current = currentPosition(book).first.coerceIn(0, count - 1)
        var target = current
        while (target == current) target = kotlin.random.Random.nextInt(count)
        val wasPlaying = tts.playing.value
        autoReadJob[0]?.cancel()
        autoReadJob[0] = null
        if (wasPlaying) tts.stop(savePos = false)
        manager.rememberPosition(book.path, target, 0, 0)
        persistResume()
        jumpGen += 1
        pendingJump = BlockJump(target, 0, 0, jumpGen)
        if (!wasPlaying) return
        autoReadJob[0] = scope.launch {
            // 等页面跳到新章再开口，避免音频还念着旧章标题的时候画面已经翻走。
            delay(450)
            val chapter = manager.loadChapter(book.path, target) ?: return@launch
            val parsed = feedParsed(book, target, chapter)
            tts.setBook("feed:${book.path}", parsed, book.path, force = true)
            tts.playFrom(target, 0, 0)
        }
    }

    // 耳机/通知栏上一首/下一首：随机阅读里切成上一本/下一本，并接着朗读。
    DisposableEffect(Unit) {
        val hook: (Int) -> Boolean = { direction ->
            if (manager.state.value.current == null) {
                false
            } else {
                switchBook(direction, autoRead = true)
                true
            }
        }
        tts.onBookTurn = hook
        // 跨章续读：TTS 读完本章时向服务端取下一章，接在同一播放流里。
        tts.chapterTailLoader = { chapter -> manager.loadChapter(tts.bookPath, chapter)?.blocks }
        // 随机阅读不写本地书库进度（远程相对路径在本地没有条目）。
        tts.persistProgress = false
        onDispose {
            if (tts.onBookTurn === hook) tts.onBookTurn = null
            tts.chapterTailLoader = null
            tts.persistProgress = true
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
        autoReadJob[0]?.cancel()
        autoReadJob[0] = null
        persistResume()
        tts.stop(savePos = false)
        manager.close()
        onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            // 退出随机阅读时清掉远程书目：通知栏不该停留在没有会话的随机书上。
            tts.resetBook()
            FeedPageCache.clear()
            manager.close()
        }
    }

    BackHandler {
        if (optionsOpen) optionsOpen = false else closeReader()
    }

    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
        if (pager.isScrollInProgress) return@LaunchedEffect
        val delta = pager.settledPage - 1
        if (delta != 0) {
            // 手势切书：原本在朗读才续读，避免静音翻书时突然开口。
            // 不在这里回收 pager：等新书数据就位后再收，停住的相邻页本身就是最终布局。
            switchBook(if (delta > 0) 1 else -1, autoRead = tts.playing.value)
        }
    }

    // 切书后停顿一下再朗读由 [switchBook] 内部的任务负责：这里只保留当前书供界面使用。
    val currentBook = state.current

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
                                manager.start(
                                    deviceId = deviceId,
                                    rootKey = rootKey,
                                    poolPaths = poolPaths,
                                    resume = container.ebookPrefs.initialFeedResume(),
                                    includeImages = container.ebookPrefs.feedImages.value,
                                )
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
        onChapterEdge = {
            scope.launch { manager.cacheWholeBook(deviceId, currentBook.path) }
        },
        onPositionChanged = { chapter, block, char ->
            manager.rememberPosition(currentBook.path, chapter, block, char)
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
        onPrevChapter = ::switchChapter,
        onNextChapter = ::switchChapter,
        marqueeTitle = true,
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 每一页都比屏幕高出一个“上一本书名”的高度：翻页时这一条先露出，
        // 落定后正好藏到屏幕上沿之外，页面看到的始终是阅读器最终布局。
        BoundaryPager(boundary = BOOK_BOUNDARY_HEIGHT, modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
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
                            onOpenMusic = {},
                            onOpenCast = {},
                            castActive = false,
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
}

/** 每页比屏幕多出来的那一条：上一本书的书名/章节，翻页时先露出来，落定后藏到屏幕上沿之外。 */
private val BOOK_BOUNDARY_HEIGHT = 30.dp

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
            onPrevChapter = {},
            onNextChapter = {},
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
        ParsedEbook(title = book.title, author = book.author, chapters = chapters)
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
