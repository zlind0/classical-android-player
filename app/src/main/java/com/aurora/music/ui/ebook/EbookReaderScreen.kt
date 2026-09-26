package com.aurora.music.ui.ebook

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ebook.EbookReadPrefs
import com.aurora.music.data.ebook.EbookTheme
import com.aurora.music.data.ebook.ParsedEbook
import com.aurora.music.ui.components.LottieLoader
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SegmentRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.ui.ios5.ios5Section
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 阅读页（v1）：左右滑动翻页（普通滑动，无上下滚动）、
// 顶部灰字状态（本章剩余页数/全书进度/时间/电量）、底部 6 键（目录/选项可用，其余占位）。
// 系统状态栏隐藏，下方安卓导航键保留。解析与分页断点落盘，大书二次秒开。

private data class ReaderTheme(val bg: Color, val ink: Color)

private fun themeOf(t: EbookTheme): ReaderTheme = when (t) {
    EbookTheme.WHITE -> ReaderTheme(Color(0xFFFFFFFF), Color(0xFF1A1A1A))
    EbookTheme.SEPIA -> ReaderTheme(Color(0xFFF4ECD8), Color(0xFF5B4636))
    EbookTheme.DARK -> ReaderTheme(Color(0xFF1C1C1E), Color(0xFFE8E8E8))
}

@Composable
fun EbookReaderScreen(bookPath: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val store = container.ebookStore
    val prefs by container.ebookPrefs.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var parsed by remember(bookPath) { mutableStateOf<ParsedEbook?>(null) }
    var failed by remember(bookPath) { mutableStateOf(false) }
    var spine by remember(bookPath) { mutableStateOf(0) }
    var startPage by remember(bookPath) { mutableStateOf(0) }
    var tocOpen by remember { mutableStateOf(false) }
    var tocAnchor by remember(bookPath) { mutableStateOf(0 to 0) }
    var optionsOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1200)
            toast = null
        }
    }

    // 隐藏系统状态栏（导航键保留），阅读时保持亮屏
    DisposableEffect(Unit) {
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

    LaunchedEffect(bookPath) {
        parsed = null
        failed = false
        val row = store.bookByPath(bookPath)
        spine = row?.spineIndex ?: 0
        startPage = row?.pageIndex ?: 0
        val p = store.openBook(bookPath)
        if (p == null || p.chapters.isEmpty()) failed = true
        else {
            parsed = p
            spine = (row?.spineIndex ?: 0).coerceIn(0, p.chapters.size - 1)
        }
    }

    BackHandler {
        when {
            tocOpen -> tocOpen = false
            optionsOpen -> optionsOpen = false
            else -> onClose()
        }
    }

    val book = parsed
    if (book == null) {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (failed) {
                    Text("打不开这本书", fontSize = 16.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(12.dp))
                    Ios5GlossButton("返回", onClose)
                } else {
                    LottieLoader(modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("正在打开…（第一本大书稍慢，之后秒开）", fontSize = 13.sp, color = Color(0xFF6B7280))
                }
            }
        }
        return
    }

    if (tocOpen) {
        EbookTocPage(
            book = book,
            currentSpine = tocAnchor.first,
            currentBlock = tocAnchor.second,
            onBack = { tocOpen = false },
            onJump = { s, b ->
                scope.launch {
                    spine = s
                    startPage = -b - 2 // 标记：按块跳（负数编码块号）
                    tocOpen = false
                }
            },
        )
        return
    }
    if (optionsOpen) {
        EbookOptionsPage(onBack = { optionsOpen = false })
        return
    }

    ReaderBody(
        bookPath = bookPath,
        book = book,
        spine = spine,
        startPage = startPage,
        prefs = prefs,
        toast = toast,
        onToast = { toast = it },
        onSpineChange = { s, p ->
            spine = s
            startPage = p
        },
        onOpenToc = { c, b ->
            tocAnchor = c to b
            tocOpen = true
        },
        onOpenOptions = { optionsOpen = true },
    )
}

@Composable
private fun ReaderBody(
    bookPath: String,
    book: ParsedEbook,
    spine: Int,
    startPage: Int,
    prefs: EbookReadPrefs,
    toast: String?,
    onToast: (String) -> Unit,
    onSpineChange: (spine: Int, page: Int) -> Unit,
    onOpenToc: (chapter: Int, block: Int) -> Unit,
    onOpenOptions: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val store = container.ebookStore
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val th = themeOf(prefs.theme)
    val meta = th.ink.copy(alpha = 0.55f)
    val linkColor = if (prefs.theme == EbookTheme.DARK) Color(0xFF7AB3FF) else Color(0xFF0A60D6)

    /** 超链接点击：i:章:块 = 站内跳转（复用目录跳转编码），e:url = 外部浏览器。 */
    fun handleLink(tag: String) {
        if (tag.startsWith("e:")) {
            val url = tag.removePrefix("e:")
            val opened = runCatching {
                if (!url.startsWith("http://") && !url.startsWith("https://")) return@runCatching false
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
            if (!opened) onToast("无法打开链接")
        } else if (tag.startsWith("i:")) {
            val parts = tag.removePrefix("i:").split(":")
            val ci = parts.getOrNull(0)?.toIntOrNull() ?: return
            val bi = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (ci in book.chapters.indices) onSpineChange(ci, -bi - 2)
        }
    }

    val fontFamily = remember(prefs.fontPath) { loadFontFamily(prefs.fontPath) }

    // 分页断点：内存 + 磁盘两级。磁盘命中则整本直接可用（秒开）。
    var breaks by remember(bookPath) { mutableStateOf<Map<Int, List<List<PageSlice>>>>(emptyMap()) }
    var cacheBase by remember(bookPath) { mutableStateOf<Map<Int, List<List<PageSlice>>>>(emptyMap()) }
    var cacheKey by remember(bookPath) { mutableStateOf<String?>(null) }
    var pagerHeightPx by remember { mutableStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize().background(th.bg)) {
        val widthPx = with(density) { (maxWidth - 44.dp).toPx().toInt().coerceAtLeast(200) }
        val key = remember(prefs.fontSizeSp, prefs.fontPath, widthPx, pagerHeightPx) {
            if (pagerHeightPx > 100) store.pageCacheKey(prefs, widthPx, pagerHeightPx) else null
        }
        LaunchedEffect(bookPath, key) {
            if (key == null) return@LaunchedEffect
            cacheKey = key
            val md5 = store.md5Of(bookPath)
            val cached = store.loadPageBreaks(md5, key)
            if (!cached.isNullOrEmpty()) {
                // 脏缓存自愈：空页/空章视为缺失，会重新排出来覆盖掉
                val m = pagesFromCache(cached).filterValues { ps -> ps.any { it.isNotEmpty() } }
                cacheBase = m
                breaks = m
            } else {
                cacheBase = emptyMap()
                breaks = emptyMap()
            }
        }
        // 窗口分页：当前章优先（阻塞首屏），随后把前后各两章排好，
        // 落到哪一章，相邻章都已就绪，切窗无 loader。
        // effect 内多次回写用本地累加表，不直接读 breaks（它是启动瞬间快照）。
        LaunchedEffect(bookPath, spine, key, book, breaks) {
            if (key == null || pagerHeightPx <= 100) return@LaunchedEffect
            val need = (-2..2).map { spine + it }
                .filter { it in book.chapters.indices && !hasPages(breaks, it) }
                .sortedBy { kotlin.math.abs(it - spine) }
            if (need.isEmpty()) return@LaunchedEffect
            val paginator = ChapterPaginator(measurer, density, widthPx, pagerHeightPx, prefs.fontSizeSp, fontFamily)
            val acc = breaks.toMutableMap()
            val md5 = store.md5Of(bookPath)
            for (n in need) {
                val nb = runCatching { paginator.paginate(book.chapters[n].blocks) }.getOrDefault(emptyList())
                if (nb.isNotEmpty()) {
                    acc[n] = nb
                    breaks = acc.toMap()
                    // 落盘（与旧缓存合并，避免覆盖别的章）
                    store.savePageBreaks(md5, key, pagesToCache(cacheBase + acc))
                }
                // 每章让出主线程：大书首开不卡手势（TextMeasurer 必须主线程，只能协作式）
                kotlinx.coroutines.yield()
            }
        }

        // ---- 三章窗口：[上一章, 当前章, 下一章]拼成一条连续长卷 ----
        // 跨章就是 pager 内的普通翻页，手势逻辑里不再有章节概念，
        // 不会多翻也不会卡死；落到邻章区间才整体换窗（跳变无动画，同一页无闪烁）。
        // 到达边界页时相邻章已预排好（上面的窗口分页），切窗无 loader。
        var livePos by remember(bookPath) { mutableStateOf<Pair<Int, Int>?>(null) } // (章, 页)实时位置
        var lastSettled by remember(bookPath) { mutableStateOf<Pair<Int, Int>?>(null) }
        // startPage 负数 = 按块跳（目录过来）：-b-2 → 块号 b
        val jumpBlock = if (startPage < 0) -startPage - 2 else -1
        val spinePages0 = breaks[spine]?.filter { it.isNotEmpty() }.orEmpty()
        val spineTarget = when {
            lastSettled?.first == spine -> lastSettled!!.second
            jumpBlock >= 0 -> pageForBlock(spinePages0, jumpBlock)
            else -> startPage.coerceIn(0, (spinePages0.size - 1).coerceAtLeast(0))
        }
        val windowPages: List<Pair<Int, Int>> = remember(breaks, spine, book) {
            if (!hasPages(breaks, spine)) emptyList()
            else buildList {
                listOfNotNull(
                    (spine - 1).takeIf { it >= 0 },
                    spine,
                    (spine + 1).takeIf { it < book.chapters.size },
                ).filter { hasPages(breaks, it) }.forEach { ci ->
                    breaks[ci]?.filter { it.isNotEmpty() }?.forEachIndexed { pi, _ -> add(ci to pi) }
                }
            }
        }
        val winSig = remember(windowPages) { windowPages.joinToString(",") { "${it.first}:${it.second}" } }
        val startIndex = remember(windowPages, spine, spineTarget) {
            windowPages.indexOfFirst { it.first == spine && it.second == spineTarget }.takeIf { it >= 0 } ?: 0
        }

        // 章节字符统计（进度用，不依赖分页）
        val chapterChars = remember(book) {
            book.chapters.map { c -> c.blocks.sumOf { it.text.length } }
        }
        val totalChars = remember(chapterChars) { chapterChars.sum().coerceAtLeast(1) }
        fun charsBeforeChapter(ci: Int): Int = chapterChars.take(ci).sum()

        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏：灰字状态，无按钮 ----
            val curGP = livePos?.takeIf { gp -> windowPages.any { it == gp } } ?: (spine to spineTarget)
            val curPages = breaks[curGP.first]?.filter { it.isNotEmpty() }.orEmpty()
            val pageChars = remember(book, curGP, curPages) { charsOfPage(book, curGP.first, curPages, curGP.second) }
            val pct = ((charsBeforeChapter(curGP.first) + pageChars).toFloat() / totalChars).coerceIn(0f, 1f)
            val remain = (curPages.size - 1 - curGP.second).coerceAtLeast(0)
            ReaderStatusBar(
                left = if (windowPages.isEmpty()) "" else "本章还剩${remain}页 · ${(pct * 100).toInt()}%",
                right = "${rememberTimeText()} · ${rememberBatteryPct()}%",
                color = meta,
            )

            // ---- 正文 ----
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .onSizeChanged { pagerHeightPx = it.height }
                    .padding(horizontal = 22.dp),
            ) {
                if (windowPages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LottieLoader(modifier = Modifier.size(64.dp))
                    }
                } else {
                    key("$winSig|$spine") {
                        val pager = rememberPagerState(initialPage = startIndex) { windowPages.size }
                        LaunchedEffect(pager.currentPage) {
                            windowPages.getOrNull(pager.currentPage)?.let { livePos = it }
                        }
                        // 落定即存进度；落到邻章区间则整体换窗（同页跳变，无动画无闪烁）
                        LaunchedEffect(pager.settledPage) {
                            val gp = windowPages.getOrNull(pager.settledPage) ?: return@LaunchedEffect
                            lastSettled = gp
                            livePos = gp
                            val cps = breaks[gp.first]?.filter { it.isNotEmpty() }.orEmpty()
                            val chars = charsBeforeChapter(gp.first) + charsOfPage(book, gp.first, cps, gp.second)
                            store.saveProgress(bookPath, gp.first, gp.second, (chars.toFloat() / totalChars).coerceIn(0f, 1f))
                            if (gp.first != spine) onSpineChange(gp.first, gp.second)
                        }
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize()
                                .pointerInput(spine, winSig) {
                                    detectTapGestures { offset ->
                                        val w = size.width
                                        when {
                                            offset.x < w * 0.18f -> winPrevPage(pager, windowPages, onSpineChange, scope)
                                            offset.x > w * 0.82f -> winNextPage(pager, windowPages, book.chapters.size, onSpineChange, scope)
                                        }
                                    }
                                },
                            beyondViewportPageCount = 1,
                        ) { pi ->
                            val (c, p) = windowPages[pi]
                            val slices = breaks[c]?.filter { it.isNotEmpty() }?.getOrNull(p).orEmpty()
                            PageView(
                                book = book,
                                spine = c,
                                slices = slices,
                                prefs = prefs,
                                fontFamily = fontFamily,
                                ink = th.ink,
                                linkColor = linkColor,
                                onLinkClick = { tag -> handleLink(tag) },
                            )
                        }
                    }
                }
            }

            // ---- 底栏：6 键 space evenly ----
            ReaderBottomBar(
                ink = th.ink,
                divider = meta.copy(alpha = 0.4f),
                onToc = {
                    val (c, p) = livePos ?: (spine to 0)
                    val blk = breaks[c]?.filter { it.isNotEmpty() }?.getOrNull(p)?.firstOrNull()?.block ?: 0
                    onOpenToc(c, blk)
                },
                onOptions = onOpenOptions,
                onPlaceholder = { onToast("即将推出") },
            )
        }

        // toast
        if (toast != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    toast, fontSize = 13.sp, color = Color.White,
                    modifier = Modifier.padding(bottom = 110.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun winPrevPage(
    pager: androidx.compose.foundation.pager.PagerState,
    windowPages: List<Pair<Int, Int>>,
    onSpineChange: (Int, Int) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        if (pager.currentPage > 0) {
            pager.animateScrollToPage(pager.currentPage - 1)
        } else {
            // 窗口起点：往更早的章跳一章（目标多半已预排好）
            val (c, _) = windowPages.firstOrNull() ?: return@launch
            if (c - 1 >= 0) onSpineChange(c - 1, Int.MAX_VALUE)
        }
    }
}

private fun winNextPage(
    pager: androidx.compose.foundation.pager.PagerState,
    windowPages: List<Pair<Int, Int>>,
    chapterCount: Int,
    onSpineChange: (Int, Int) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        if (pager.currentPage < windowPages.size - 1) {
            pager.animateScrollToPage(pager.currentPage + 1)
        } else {
            val (c, _) = windowPages.lastOrNull() ?: return@launch
            if (c + 1 < chapterCount) onSpineChange(c + 1, 0)
        }
    }
}

private fun pageForBlock(pages: List<List<PageSlice>>, block: Int): Int {
    pages.forEachIndexed { i, page ->
        if (page.any { it.block >= block }) return i
    }
    return 0
}

/** 该章是否有可用页（空页列表视为缺失，触发重排并自愈脏缓存）。 */
private fun hasPages(m: Map<Int, List<List<PageSlice>>>, ci: Int): Boolean =
    m[ci]?.any { it.isNotEmpty() } == true

/** 按展开规则数可见条目（目录自适应深度的计数用）。 */
private fun countVisible(toc: List<com.aurora.music.data.ebook.EbookTocEntry>, expanded: (Int) -> Boolean): Int {
    val stack = ArrayDeque<Pair<Int, Int>>()
    var n = 0
    toc.forEachIndexed { i, e ->
        while (stack.isNotEmpty() && stack.last().second >= e.level) stack.removeLast()
        if (stack.all { expanded(it.first) }) n++
        stack.add(i to e.level)
    }
    return n
}

private fun charsOfPage(book: ParsedEbook, spine: Int, pages: List<List<PageSlice>>, page: Int): Int {
    if (page < 0) return 0
    val blocks = book.chapters.getOrNull(spine)?.blocks ?: return 0
    var n = 0
    for (i in 0 until page.coerceAtMost(pages.size)) {
        pages[i].forEach { s ->
            val len = blocks.getOrNull(s.block)?.text?.length ?: 0
            n += (s.end.coerceAtMost(len) - s.start.coerceAtLeast(0)).coerceAtLeast(0)
        }
    }
    // 当前页读了一半也算读过？只算整页之前，简单可预期
    return n
}

@Composable
@OptIn(ExperimentalTextApi::class)
private fun PageView(
    book: ParsedEbook,
    spine: Int,
    slices: List<PageSlice>,
    prefs: EbookReadPrefs,
    fontFamily: FontFamily,
    ink: Color,
    linkColor: Color,
    onLinkClick: (String) -> Unit,
) {
    val blocks = book.chapters[spine].blocks
    val linkListener = remember(onLinkClick) {
        object : LinkInteractionListener {
            override fun onClick(link: LinkAnnotation) {
                (link as? LinkAnnotation.Clickable)?.let { onLinkClick(it.tag) }
            }
        }
    }
    val linkStyles = remember(linkColor) {
        TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
        // 按块聚合成段显示；超链接按块坐标裁到当前页片后挂 annotation
        val byBlock = slices.groupBy { it.block }.toSortedMap()
        byBlock.forEach { (bi, ss) ->
            val b = blocks.getOrNull(bi) ?: return@forEach
            val ordered = ss.sortedBy { it.start }
            val annotated = buildAnnotatedString {
                var off = 0
                ordered.forEach { s ->
                    val from = s.start.coerceIn(0, b.text.length)
                    val to = s.end.coerceIn(0, b.text.length)
                    if (from >= to) return@forEach
                    val segBase = off
                    append(b.text.substring(from, to))
                    off += to - from
                    b.links.forEach { l ->
                        val a = maxOf(l.start, from)
                        val e = minOf(l.end, to)
                        if (a < e) {
                            val tag = if (l.chapter < 0) "e:${l.url}" else "i:${l.chapter}:${l.block}"
                            addLink(
                                LinkAnnotation.Clickable(tag, linkStyles, linkListener),
                                segBase + (a - from),
                                segBase + (e - from),
                            )
                        }
                    }
                }
            }
            val mult = when (b.level) {
                1 -> 1.45f
                2 -> 1.28f
                3 -> 1.16f
                4, 5, 6 -> 1.08f
                else -> 1f
            }
            if (b.level in 1..6) Spacer(Modifier.height((prefs.fontSizeSp * 0.4f).dp))
            Text(
                annotated,
                style = TextStyle(
                    fontSize = (prefs.fontSizeSp * mult).sp,
                    lineHeight = (prefs.fontSizeSp * mult * 1.55f).sp,
                    fontFamily = fontFamily,
                    fontWeight = if (b.level in 1..6) FontWeight.Bold else FontWeight.Normal,
                    color = ink,
                ),
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Visible,
            )
            Spacer(Modifier.height((prefs.fontSizeSp * (if (b.level in 1..6) 0.3f else 0.45f)).dp))
        }
    }
}

@Composable
private fun ReaderStatusBar(left: String, right: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(left, fontSize = 12.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(right, fontSize = 12.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun ReaderBottomBar(
    ink: Color,
    divider: Color,
    onToc: () -> Unit,
    onOptions: () -> Unit,
    onPlaceholder: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderButton("目录", Icons.AutoMirrored.Filled.List, ink, 1f, onToc)
            ReaderButton("音乐", Icons.Filled.MusicNote, ink, 0.35f, onPlaceholder)
            ReaderButton("上一首", Icons.Filled.SkipPrevious, ink, 0.35f, onPlaceholder)
            ReaderButton("播放", Icons.Filled.PlayArrow, ink, 0.35f, onPlaceholder)
            ReaderButton("下一首", Icons.Filled.SkipNext, ink, 0.35f, onPlaceholder)
            ReaderButton("选项", Icons.Filled.Settings, ink, 1f, onOptions)
        }
    }
}

@Composable
private fun ReaderButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ink: Color,
    alpha: Float,
    onClick: () -> Unit,
) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = ink.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        Text(label, fontSize = 10.sp, color = ink.copy(alpha = alpha), maxLines = 1)
    }
}

// ---- 时间 / 电量 ----

@Composable
private fun rememberTimeText(): String {
    var now by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            now = fmt.format(Date())
            delay(20_000)
        }
    }
    return now
}

@Composable
private fun rememberBatteryPct(): Int {
    val context = LocalContext.current
    var pct by remember { mutableStateOf(100) }
    LaunchedEffect(Unit) {
        while (true) {
            pct = runCatching {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                (level * 100 / scale.coerceAtLeast(1))
            }.getOrDefault(100)
            delay(60_000)
        }
    }
    return pct
}

private fun loadFontFamily(path: String): FontFamily {
    if (path.isBlank()) return FontFamily.Default
    return runCatching {
        val f = File(path)
        if (!f.exists()) return FontFamily.Default
        FontFamily(Typeface.createFromFile(f))
    }.getOrDefault(FontFamily.Default)
}

// ---- 目录页：h1/h2 默认展开，更深默认叠起，当前章自动展开 ----

@Composable
private fun EbookTocPage(
    book: ParsedEbook,
    currentSpine: Int,
    currentBlock: Int,
    onBack: () -> Unit,
    onJump: (spine: Int, block: Int) -> Unit,
) {
    // toggled = 与默认相反的手动项
    val toggled = remember { mutableStateMapOf<Int, Boolean>() }
    val hasChildren = remember(book) {
        BooleanArray(book.toc.size) { i ->
            val lv = book.toc[i].level
            var j = i + 1
            while (j < book.toc.size && book.toc[j].level > lv) {
                if (book.toc[j].level == lv + 1) return@BooleanArray true
                j++
            }
            false
        }
    }
    // 当前位置对应的条目：同章且块号不超过当前位置的最后一条
    val currentEntry = remember(book, currentSpine, currentBlock) {
        if (book.toc.isEmpty()) return@remember -1
        var idx = book.toc.indexOfFirst { it.chapterIndex == currentSpine }
        book.toc.forEachIndexed { i, e ->
            if (e.chapterIndex == currentSpine && e.blockIndex <= currentBlock) idx = i
        }
        if (idx < 0) 0 else idx
    }
    // 当前条目的祖先 + 自己 + 子孙：打开即展开到最详细
    val forceExpanded = remember(book, currentEntry) {
        val s = mutableSetOf<Int>()
        if (currentEntry >= 0) {
            var lv = book.toc[currentEntry].level
            var j = currentEntry - 1
            while (j >= 0) {
                if (book.toc[j].level < lv) {
                    s.add(j)
                    lv = book.toc[j].level
                }
                j--
            }
            s.add(currentEntry)
            var k = currentEntry + 1
            while (k < book.toc.size && book.toc[k].level > book.toc[currentEntry].level) {
                s.add(k)
                k++
            }
        }
        s
    }
    // 自适应深度：把“展开 level ≤ D 的节点”后的可见条目数压到 200 以内，取最大的 D。
    // 不按 h1/h2 字面定——有些书的层级本来就不是 h1/h2。
    // 当前阅读路径（上面）无条件全展，优先级高于计数。
    val expandDepth = remember(book) {
        var d = 6
        while (d > 0 && countVisible(book.toc) { book.toc[it].level <= d } > 200) d--
        d
    }
    fun defaultExpanded(i: Int): Boolean {
        if (i in forceExpanded) return true
        return book.toc[i].level <= expandDepth
    }
    fun expanded(i: Int): Boolean {
        val d = defaultExpanded(i)
        return if (toggled.containsKey(i)) !d else d
    }
    // 可见性：所有祖先都展开
    val visible = remember(book, toggled.toMap(), forceExpanded, expandDepth) {
        val stack = ArrayDeque<Pair<Int, Int>>() // (tocIndex, level)
        BooleanArray(book.toc.size) { i ->
            val lv = book.toc[i].level
            while (stack.isNotEmpty() && stack.last().second >= lv) stack.removeLast()
            val ok = stack.all { expanded(it.first) }
            stack.add(i to lv)
            ok
        }
    }
    val rows = remember(book, visible) {
        book.toc.mapIndexedNotNull { i, e -> if (visible[i]) i to e else null }
    }
    val targetPos = remember(rows, currentEntry) {
        rows.indexOfFirst { it.first == currentEntry }.takeIf { it >= 0 } ?: 0
    }
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tocDensity = LocalDensity.current
        // 打开即定位到当前 heading 并大致居中
        LaunchedEffect(Unit) {
            if (targetPos > 0) {
                val viewportH = maxHeight - 120.dp
                val halfViewport = with(tocDensity) { viewportH.toPx().toInt().coerceAtLeast(0) } / 2
                listState.scrollToItem(targetPos, scrollOffset = -halfViewport)
            }
        }
        Column(Modifier.fillMaxSize()) {
            Ios5NavBar(title = "目录", onBack = onBack)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
            item { Ios5SectionTitle(book.title) }
            ios5Rows(rows, key = { it.first }) { _, (i, e) ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onJump(e.chapterIndex, e.blockIndex) }
                        .padding(start = (12 + (e.level - 1) * 16).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val current = i == currentEntry
                    if (hasChildren[i]) {
                        val ex = expanded(i)
                        Text(
                            if (ex) "▾" else "▸",
                            fontSize = 16.sp,
                            color = com.aurora.music.ui.ios5.Ios5Colors.TextSecondary,
                            modifier = Modifier.width(22.dp).clickable {
                                if (toggled.containsKey(i)) toggled.remove(i) else toggled[i] = true
                            }.padding(vertical = 2.dp),
                        )
                    } else {
                        Spacer(Modifier.width(22.dp))
                    }
                    Text(
                        e.title.ifBlank { "（无标题）" },
                        fontSize = if (e.level <= 2) 15.sp else 14.sp,
                        fontWeight = if (e.level == 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) com.aurora.music.ui.ios5.Ios5Colors.IosBlue
                        else com.aurora.music.ui.ios5.Ios5Colors.TextPrimary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            }
        }
    }
}

// ---- 阅读设置页：风格 / 字体 / 字号（复用 Ios5 设置组件） ----

@Composable
private fun EbookOptionsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val prefs by container.ebookPrefs.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                    }
                }.getOrNull() ?: "custom.ttf"
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext != "ttf" && ext != "otf") return@launch
                val dir = File(context.filesDir, "fonts")
                dir.mkdirs()
                val dest = File(dir, name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    dest.outputStream().use { out -> ins.copyTo(out) }
                }
                container.ebookPrefs.setFontPath(dest.absolutePath)
            }
        }
    }
    val themeIdx = when (prefs.theme) {
        EbookTheme.WHITE -> 0
        EbookTheme.SEPIA -> 1
        EbookTheme.DARK -> 2
    }
    Ios5SettingsPage(title = "阅读设置", onBack = onBack) {
        ios5Section("页面风格") {
            Ios5SegmentRow(
                title = "底色",
                options = listOf("白色", "护眼", "夜间"),
                selected = themeIdx,
                onSelect = {
                    container.ebookPrefs.setTheme(
                        when (it) {
                            1 -> EbookTheme.SEPIA
                            2 -> EbookTheme.DARK
                            else -> EbookTheme.WHITE
                        },
                    )
                },
            )
        }
        ios5Section("字体") {
            com.aurora.music.ui.ios5.Ios5CheckRow(
                title = "系统默认",
                checked = prefs.fontPath.isBlank(),
                onClick = { container.ebookPrefs.setFontPath("") },
            )
            Ios5CellDivider()
            if (prefs.fontPath.isNotBlank()) {
                com.aurora.music.ui.ios5.Ios5CheckRow(
                    title = File(prefs.fontPath).name,
                    subtitle = "自定义字体",
                    checked = true,
                    onClick = {},
                )
                Ios5CellDivider()
            }
            Ios5NavRow(title = "选择字体文件", subtitle = "ttf / otf", onClick = { fontPicker.launch("*/*") })
        }
        ios5Section("文字大小") {
            Ios5SliderRow(
                title = "字号",
                valueLabel = "${prefs.fontSizeSp.toInt()}",
                value = prefs.fontSizeSp,
                range = 12f..28f,
                steps = 15,
                onValueChange = { container.ebookPrefs.setFontSize(it) },
            )
            Ios5StaticText("左右滑动翻页时的每页字数会随字号变化，断点会自动重排并记住。")
        }
    }
}
