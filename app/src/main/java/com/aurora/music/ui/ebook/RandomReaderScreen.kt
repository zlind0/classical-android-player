package com.aurora.music.ui.ebook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.RandomFeedBook
import com.aurora.music.data.ebook.EbookChapter
import com.aurora.music.data.ebook.EbookFeedResume
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

    /**
     * 切书统一入口：耳机/通知栏、上下滑都走它。
     * @param autoRead 切好后是否停顿一下再朗读（耳机切书总是读；手势只在原本正在朗读时才续读）。
     */
    fun switchBook(direction: Int, autoRead: Boolean) {
        if (manager.state.value.loading) return
        autoReadJob[0]?.cancel()
        autoReadJob[0] = null
        val wasPlaying = tts.playing.value
        if (wasPlaying) tts.stop(savePos = false)
        persistResume()
        autoReadJob[0] = scope.launch {
            if (!manager.turn(direction)) {
                showToast(manager.state.value.error ?: "切换失败")
                return@launch
            }
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
            switchBook(if (delta > 0) 1 else -1, autoRead = tts.playing.value)
            runCatching { pager.scrollToPage(1) }
        }
    }

    // 切书后停顿一下再朗读由 [switchBook] 内部的任务负责：这里只保留当前书供界面使用。
    val currentBook = state.current

    if (currentBook == null) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (starting || state.loading) {
                    LottieLoader(modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("正在打开随机书…", fontSize = 13.sp, color = Color(0xFF6B7280))
                } else {
                    Text(
                        state.error ?: "没有可阅读的随机书",
                        fontSize = 15.sp,
                        color = Color(0xFF6B7280),
                        textAlign = TextAlign.Center,
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

    val count = currentBook.chapterCount.coerceAtLeast(currentBook.chapterTitles.size).coerceAtLeast(1)
    val displayBook = remember(currentBook.path, chapterVersion) {
        val chapters = (0 until count).map { i ->
            val title = currentBook.chapterTitles.getOrElse(i) { "第 ${i + 1} 章" }
            val cached = manager.cachedChapter(currentBook.path, i)
            EbookChapter(cached?.title?.ifBlank { title } ?: title, cached?.blocks.orEmpty())
        }
        ParsedEbook(
            title = currentBook.title,
            author = currentBook.author,
            chapters = chapters,
        )
    }
    val bookKey = "${currentBook.path}|${currentBook.chapterIndex}|${currentBook.blockIndex}|${currentBook.charOffset}"

    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !optionsOpen,
            beyondViewportPageCount = 1,
        ) { page ->
            when (page) {
                0 -> NeighborCard("上一本", currentBook, state.previous, prefs.theme)
                2 -> NeighborCard("下一本", currentBook, state.next, prefs.theme)
                else -> {
                    val feed = FeedReaderUi(
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
                    )
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
                        feed = feed,
                    )
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

        // 新书解析可能要走一次网络：切换过程中给个轻提示，避免停在旧书上看起来没反应。
        if (state.loading) {
            Text(
                "正在换书…",
                fontSize = 11.sp,
                color = Color(0xFF6B7280),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
            )
        }
    }
}

@Composable
private fun NeighborCard(
    label: String,
    current: RandomFeedBook?,
    book: RandomFeedBook?,
    theme: com.aurora.music.data.ebook.EbookTheme,
) {
    val th = themeOf(theme)
    Box(
        Modifier.fillMaxSize().background(th.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            // 界限卡同时交代“从哪本离开、将进入哪本”，上下滑时不至于不知道自己在哪。
            current?.let {
                Text("现在", fontSize = 11.sp, color = th.ink.copy(alpha = 0.4f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${it.title} · ${it.chapterTitle.ifBlank { "第 ${it.chapterIndex + 1} 章" }}",
                    fontSize = 13.sp,
                    color = th.ink.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Box(Modifier.width(88.dp).height(1.dp).background(th.ink.copy(alpha = 0.16f)))
                Spacer(Modifier.height(16.dp))
            }
            Text(label, fontSize = 12.sp, color = th.ink.copy(alpha = 0.45f), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                book?.title?.ifBlank { "没有更多了" } ?: "没有更多了",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = th.ink,
                textAlign = TextAlign.Center,
            )
            if (book != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    book.chapterTitle.ifBlank { "第 ${book.chapterIndex + 1} 章" },
                    fontSize = 14.sp,
                    color = th.ink.copy(alpha = 0.62f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    "松手后继续读这本书",
                    fontSize = 12.sp,
                    color = th.ink.copy(alpha = 0.4f),
                )
            }
        }
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
