package com.aurora.music.ui.ebook

import android.app.Activity
import android.app.TimePickerDialog
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.aurora.music.data.ebook.MAX_RECENT_FONTS
import com.aurora.music.data.TTS_ENGINE_INTERNAL
import com.aurora.music.data.UnifiedTtsPrefs
import com.aurora.music.data.ebook.EbookTtsUnit
import com.aurora.music.data.ebook.ParsedEbook
import com.aurora.music.tts.MsVoices
import com.aurora.music.tts.TtsEngineInfo
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
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.ui.ios5.ios5Section
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 阅读页（v1）：左右滑动翻页（普通滑动，无上下滚动）、
// 顶部灰字状态（本章剩余页数/全书进度/时间/电量）、底部 6 键（目录/选项可用，其余占位）。
// 系统状态栏隐藏，下方安卓导航键保留。解析与分页断点落盘，大书二次秒开。

private data class ReaderTheme(val bg: Color, val ink: Color, val barBrush: Brush, val barInk: Color)

// 底条与安卓导航键连成一条：白色/深色用播放界面同款黑渐变，sepia 用深棕渐变
private val readerBarBlack = Brush.verticalGradient(
    0f to Color(0xFF3D434C),
    1f to Color(0xFF14161B),
)
private val readerBarBrown = Brush.verticalGradient(
    0f to Color(0xFF8A755C),
    1f to Color(0xFF54432F),
)

private fun themeOf(t: EbookTheme): ReaderTheme = when (t) {
    EbookTheme.WHITE -> ReaderTheme(Color(0xFFFFFFFF), Color(0xFF1A1A1A), readerBarBlack, Color.White)
    EbookTheme.SEPIA -> ReaderTheme(Color(0xFFF4ECD8), Color(0xFF5B4636), readerBarBrown, Color(0xFFF5EBD5))
    EbookTheme.DARK -> ReaderTheme(Color(0xFF1C1C1E), Color(0xFFE8E8E8), readerBarBlack, Color.White)
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
    var initPage by remember(bookPath) { mutableStateOf(GlobalPage(0, 0)) }
    // DB 里的块/字锚点：恢复时按它推导页（比存的页码更准，TTS 句级推进只动它）
    var initBlock by remember(bookPath) { mutableStateOf(0) }
    var initChar by remember(bookPath) { mutableStateOf(0) }
    // 目录/站内跳转的按块请求（窗口内先 ensure 再定位，不等分页也先落盘块锚点）
    var pendingJump by remember(bookPath) { mutableStateOf<BlockJump?>(null) }
    var jumpGen by remember(bookPath) { mutableStateOf(0L) }
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
        pendingJump = null
        val row = store.bookByPath(bookPath)
        initPage = GlobalPage(row?.spineIndex ?: 0, row?.pageIndex ?: 0)
        initBlock = row?.blockIndex ?: 0
        initChar = row?.charOffset ?: 0
        val p = store.openBook(bookPath)
        if (p == null || p.chapters.isEmpty()) failed = true
        else {
            parsed = p
            initPage = GlobalPage(
                (row?.spineIndex ?: 0).coerceIn(0, p.chapters.size - 1),
                (row?.pageIndex ?: 0).coerceAtLeast(0),
            )
            container.ebookTts.setBook(store.md5Of(bookPath), p, bookPath)
        }
    }

    // 离开阅读页停掉朗读（进目录/设置是页内状态，不经过这里）
    DisposableEffect(bookPath) {
        onDispose { container.ebookTts.stop() }
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

    // 使用 Box 叠加：ReaderBody 常驻，TOC/Options 覆盖在上层
    // 这样返回时不重建 pager、不丢分页缓存、不触发多 LaunchedEffect 竞争
    Box(Modifier.fillMaxSize()) {
        ReaderBody(
            bookPath = bookPath,
            book = book,
            initial = initPage,
            initBlock = initBlock,
            initChar = initChar,
            pendingJump = pendingJump,
            onConsumeJump = { pendingJump = null },
            prefs = prefs,
            toast = toast,
            onToast = { toast = it },
            onOpenToc = { c, b ->
                tocAnchor = c to b
                tocOpen = true
            },
            onOpenOptions = { optionsOpen = true },
        )

        if (tocOpen) {
            EbookTocPage(
                book = book,
                currentSpine = tocAnchor.first,
                currentBlock = tocAnchor.second,
                onBack = { tocOpen = false },
                onJump = { s, b ->
                    scope.launch {
                        jumpGen += 1
                        pendingJump = BlockJump(s, b, 0, jumpGen)
                        tocOpen = false
                        // 目录跳转不等分页，直接把块锚点落盘
                        store.saveTtsPos(bookPath, s, b, 0)
                    }
                },
            )
        }
        if (optionsOpen) {
            EbookOptionsPage(onBack = { optionsOpen = false })
        }
    }
}

@Composable
private fun ReaderBody(
    bookPath: String,
    book: ParsedEbook,
    initial: GlobalPage,
    initBlock: Int,
    initChar: Int,
    pendingJump: BlockJump?,
    onConsumeJump: () -> Unit,
    prefs: EbookReadPrefs,
    toast: String?,
    onToast: (String) -> Unit,
    onOpenToc: (chapter: Int, block: Int) -> Unit,
    onOpenOptions: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val store = container.ebookStore
    val tts = container.ebookTts
    val ttsPlaying by tts.playing.collectAsStateWithLifecycle()
    val ttsUnit by container.ebookPrefs.ttsUnit.collectAsStateWithLifecycle()
    val ttsInstalling by tts.installing.collectAsStateWithLifecycle()
    val ttsNotice by tts.notice.collectAsStateWithLifecycle()
    LaunchedEffect(ttsNotice) {
        tts.takeNotice()?.let { onToast(it) }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val th = themeOf(prefs.theme)
    val meta = th.ink.copy(alpha = 0.55f)
    val linkColor = if (prefs.theme == EbookTheme.DARK) Color(0xFF7AB3FF) else Color(0xFF0A60D6)

    val fontFamily = remember(prefs.fontPath) { loadFontFamily(prefs.fontPath) }

    var pagerHeightPx by remember { mutableStateOf(0) }

    val ttsPos by tts.position.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize().background(th.bg)) {
        val widthPx = with(density) { (maxWidth - 44.dp).toPx().toInt().coerceAtLeast(200) }

        // ---- 7 页滑动窗口：唯一真相源（分页/缓存/预排全在 Host 内） ----
        // pager 固定 7 页、center 恒为 3，落定后窗口平移并回正，下标永不漂移。
        val win = rememberPageWindow(
            bookPath = bookPath,
            book = book,
            widthPx = widthPx,
            heightPx = pagerHeightPx,
            prefs = prefs,
            fontFamily = fontFamily,
            initial = initial,
            anchorBlock = initBlock,
            anchorChar = initChar,
        )
        val breaks: Map<Int, List<List<PageSlice>>> = win.breaks

        // 章节字符统计（进度用，不依赖分页）
        val chapterChars = remember(book) {
            book.chapters.map { c -> c.blocks.sumOf { it.text.length } }
        }
        val totalChars = remember(chapterChars) { chapterChars.sum().coerceAtLeast(1) }
        fun charsBeforeChapter(ci: Int): Int = chapterChars.take(ci).sum()

        // 每翻一页立刻存档（无防抖）：进设置、进程被杀都不丢。
        // 块/字取朗读锚点（在读时），否则取页顶首字，下次恢复按锚点推导更准。
        // 窗口落定只存一次（旧双通道 currentPage + settledPage 已合并）。
        fun persist(gp: GlobalPage) {
            val anchor = tts.position.value?.takeIf { it.chapter == gp.chapter }
            val cps = breaks[gp.chapter]?.filter { it.isNotEmpty() }.orEmpty()
            val (blk, ch) = if (anchor != null) {
                anchor.block to anchor.startChar
            } else {
                val sl = cps.getOrNull(gp.page)?.firstOrNull()
                (sl?.block ?: 0) to (sl?.start ?: 0)
            }
            val chars = charsBeforeChapter(gp.chapter) + charsOfPage(book, gp.chapter, cps, gp.page)
            store.saveReadingPos(
                bookPath, gp.chapter, gp.page, blk, ch,
                (chars.toFloat() / totalChars).coerceIn(0f, 1f),
            )
        }

        // 锁屏/切后台兜底：ON_PAUSE 时把最新位置同步刷盘（fire-and-forget 在被杀时可能来不及）。
        // 唯一位置源 = 窗口 center + TTS 块锚点。
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, bookPath) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                if (ev == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                    scope.launch {
                        val gp = win.center
                        val pos = tts.position.value
                        val cps = breaks[gp.chapter]?.filter { it.isNotEmpty() }.orEmpty()
                        val anchor = pos?.takeIf { it.chapter == gp.chapter }
                        val (blk, ch) = if (anchor != null) anchor.block to anchor.startChar
                        else cps.getOrNull(gp.page)?.firstOrNull()
                            ?.let { it.block to it.start } ?: (0 to 0)
                        val chars = charsBeforeChapter(gp.chapter) +
                            charsOfPage(book, gp.chapter, cps, gp.page)
                        runCatching {
                            store.saveProgressSync(
                                bookPath, gp.chapter, gp.page, blk, ch,
                                (chars.toFloat() / totalChars).coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }

        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏：灰字状态，无按钮（数据源唯一：窗口 center） ----
            val curGP = win.center
            val curPages = breaks[curGP.chapter]?.filter { it.isNotEmpty() }.orEmpty()
            val pageChars = remember(book, curGP, curPages) { charsOfPage(book, curGP.chapter, curPages, curGP.page) }
            val pct = ((charsBeforeChapter(curGP.chapter) + pageChars).toFloat() / totalChars).coerceIn(0f, 1f)
            val remain = (curPages.size - 1 - curGP.page).coerceAtLeast(0)
            ReaderStatusBar(
                left = if (win.slotAt(PAGE_WINDOW_CENTER) == null) "" else "本章还剩${remain}页 · ${(pct * 100).toInt()}%",
                right = "${rememberTimeText()} · ${rememberBatteryPct()}%",
                color = meta,
            )

            // ---- 正文 ----
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .onSizeChanged { pagerHeightPx = it.height }
                    .padding(horizontal = 22.dp),
            ) {
                // pager 只在换书时重建（固定 7 页、center 恒为 3，不跟分页回写走）；
                // TOC/设置返回是页内覆盖，不重建
                val pager = key(bookPath) { rememberPagerState(initialPage = PAGE_WINDOW_CENTER) { PAGE_WINDOW_SIZE } }

                /** 超链接点击：e:url = 外部浏览器；i:章:块 = 站内跳转（先落盘块锚点，窗口内 ensure 再定位）。 */
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
                        if (ci in book.chapters.indices) {
                            // 站内跳转不等分页，直接把块锚点落盘
                            store.saveTtsPos(bookPath, ci, bi, 0)
                            scope.launch {
                                win.jumpToBlock(ci, bi)
                                runCatching { pager.scrollToPage(PAGE_WINDOW_CENTER) }
                                persist(win.center)
                            }
                        }
                    }
                }

                /** 程序化导航（TTS 跟读）：直接换窗 + 无动画回正。
                 *  动画跟读会和 250ms 跟读循环/落定回正互相 cancel，时序差时永远到不了 settle；
                 *  直接换窗是纯状态变更 + 同步回正，确定性和目录跳一致。 */
                suspend fun goToPage(g: GlobalPage) {
                    if (g == win.center) return
                    win.jumpTo(g)
                    runCatching { pager.scrollToPage(PAGE_WINDOW_CENTER) }
                    persist(win.center)
                }

                // 目录按块跳：与 TTS/链接同一调用，跨章也是它
                LaunchedEffect(pendingJump) {
                    val pj = pendingJump ?: return@LaunchedEffect
                    win.jumpToBlock(pj.chapter, pj.block)
                    runCatching { pager.scrollToPage(PAGE_WINDOW_CENTER) }
                    persist(win.center)
                    onConsumeJump()
                }
                // 手势落定：转环（支持连甩 ±20）并在空闲时无动画回正，视觉不动；落定只存一次。
                // 手势进行中绝不碰 pager：回正若打断手指下的滑动，就会"翻快了弹回来"。
                // early-return 只是推迟结算，放手空闲后 effect 重跑，delta 按中心槽算始终自洽。
                LaunchedEffect(pager.settledPage, pager.isScrollInProgress) {
                    if (pager.isScrollInProgress) return@LaunchedEffect
                    val d = pager.settledPage - PAGE_WINDOW_CENTER
                    if (d != 0) {
                        when (val r = win.moveBy(d)) {
                            is MoveResult.Moved -> {
                                persist(win.center)
                                runCatching { pager.scrollToPage(PAGE_WINDOW_CENTER) }
                            }
                            // 真到书首/书末：弹回 center
                            is MoveResult.AtEdge -> {
                                runCatching { pager.scrollToPage(PAGE_WINDOW_CENTER) }
                            }
                            // 邻章没排上：urgent 排完重试（槽留白一次），再回正
                            is MoveResult.NeedChapter -> scope.launch {
                                var cur: MoveResult = r
                                var tries = 0
                                while (cur is MoveResult.NeedChapter && tries < 4) {
                                    tries++
                                    if (win.paginateChapter?.invoke(cur.chapter) != true) break
                                    cur = win.moveBy(d)
                                }
                                if (cur is MoveResult.Moved) persist(win.center)
                                runCatching { pager.scrollToPage(PAGE_WINDOW_CENTER) }
                            }
                        }
                    }
                }
                        // 朗读位置直存（只写盘，不碰 pager/内存导航，导航仍走 turnRequest + pager）：
                        // TTS 每推进一段/一句、点上一首/下一首、通知栏/耳机切段都会走到这里，
                        // 同页不动 pager 时靠这行把块/字锚点落盘。
                        LaunchedEffect(ttsPos) {
                            val pos = ttsPos ?: return@LaunchedEffect
                            val cps = breaks[pos.chapter]?.filter { it.isNotEmpty() }.orEmpty()
                            if (cps.isEmpty()) {
                                store.saveTtsPos(bookPath, pos.chapter, pos.block, pos.startChar)
                            } else {
                                val p = pageForChar(cps, pos.block, pos.startChar)
                                val chars = charsBeforeChapter(pos.chapter) +
                                    charsOfPage(book, pos.chapter, cps, p)
                                store.saveReadingPos(
                                    bookPath, pos.chapter, p, pos.block, pos.startChar,
                                    (chars.toFloat() / totalChars).coerceIn(0f, 1f),
                                )
                            }
                        }
                        // 朗读翻页：段落播完/上下段跳转时翻到该字所在页（跨章同一调用），同时直存。
                        // 手势中不抢 pager：等放手后 effect 重跑再追（turnReq 是 StateFlow，值还在）。
                        // turn 是 sticky 的（stop/setBook 不清）：同一值只导航一次，
                        // 否则每次手势结束的重跑都会把翻页拽回过期位置，停了也翻不动。
                        val turnReq by tts.turnRequest.collectAsStateWithLifecycle()
                        var handledTurn by remember(bookPath) { mutableStateOf<Triple<Int, Int, Int>?>(null) }
                        LaunchedEffect(turnReq, pager.isScrollInProgress) {
                            if (pager.isScrollInProgress) return@LaunchedEffect
                            val t = turnReq ?: return@LaunchedEffect
                            if (t == handledTurn) return@LaunchedEffect
                            handledTurn = t
                            val (c, b, ch) = t
                            val cps = breaks[c]?.filter { it.isNotEmpty() }.orEmpty()
                            if (cps.isEmpty()) {
                                // 该章没排好：先把锚点落盘，再 ensure 后精确定位
                                store.saveTtsPos(bookPath, c, b, ch)
                                win.jumpToChar(c, b, ch)
                                runCatching { pager.scrollToPage(PAGE_WINDOW_CENTER) }
                                persist(win.center)
                            } else {
                                // 该字所在页（同一块可能跨多页，不能只取块首页）
                                val p = cps.indexOfFirst { page ->
                                    page.any { s -> s.block == b && s.end > ch }
                                }.takeIf { it >= 0 } ?: pageForBlock(cps, b)
                                val chars = charsBeforeChapter(c) + charsOfPage(book, c, cps, p)
                                store.saveReadingPos(
                                    bookPath, c, p, b, ch,
                                    (chars.toFloat() / totalChars).coerceIn(0f, 1f),
                                )
                                goToPage(GlobalPage(c, p))
                            }
                        }
                        // 段内跟读：段落跨页时按朗读进度自动翻（只往前翻，不把用户拽回来）
                        val paraTiming by tts.paraTiming.collectAsStateWithLifecycle()
                        LaunchedEffect(paraTiming, ttsPlaying) {
                            val t = paraTiming ?: return@LaunchedEffect
                            if (!ttsPlaying || t.chapter != win.center.chapter) return@LaunchedEffect
                            val cps = breaks[t.chapter]?.filter { it.isNotEmpty() }.orEmpty()
                            if (cps.isEmpty()) return@LaunchedEffect
                            val total = t.totalChars.coerceAtLeast(1)
                            val dur = t.durationMs.coerceAtLeast(1)
                            while (true) {
                                delay(250)
                                if (pager.isScrollInProgress) continue // 手势中不跟读，放手再追
                                if (!tts.playing.value) break
                                val elapsed = android.os.SystemClock.elapsedRealtime() - t.startedAt
                                if (elapsed > dur + 2000) break
                                // 全局字坐标 = 起始偏移 + 已读字数
                                val targetChar = t.startChar +
                                    (elapsed.toFloat() / dur * total).toInt().coerceAtLeast(0)
                                var acc = 0
                                var targetPage = 0
                                for ((pi, page) in cps.withIndex()) {
                                    val pc = page.sumOf { (it.end - it.start).coerceAtLeast(0) }
                                    if (targetChar < acc + pc) {
                                        targetPage = pi
                                        break
                                    }
                                    acc += pc
                                    targetPage = pi
                                }
                                val target = GlobalPage(t.chapter, targetPage)
                                if (target != win.center) {
                                    // 只跟进下一页（直接换窗回正，不走动画）；用户已翻走更远则不拽回
                                    val slot = win.adjacentSlotOf(target)
                                    if (slot != null && slot == PAGE_WINDOW_CENTER + 1) {
                                        win.jumpTo(target)
                                        runCatching { pager.scrollToPage(PAGE_WINDOW_CENTER) }
                                    }
                                }
                            }
                        }
                        HorizontalPager(
                            state = pager,
                            modifier = Modifier.fillMaxSize()
                                .pointerInput(bookPath) {
                                    detectTapGestures { offset ->
                                        val w = size.width
                                        // 点击翻页：相对当前槽 ±1，有坐标才动（书首/书末点不动）
                                        val cur = pager.currentPage
                                        when {
                                            offset.x < w * 0.18f -> {
                                                val t = (cur - 1).coerceAtLeast(0)
                                                if (win.coordAt(t) != null && t != cur) {
                                                    scope.launch { runCatching { pager.animateScrollToPage(t) } }
                                                }
                                            }
                                            offset.x > w * 0.82f -> {
                                                val t = (cur + 1).coerceAtMost(PAGE_WINDOW_SIZE - 1)
                                                if (win.coordAt(t) != null && t != cur) {
                                                    scope.launch { runCatching { pager.animateScrollToPage(t) } }
                                                }
                                            }
                                        }
                                    }
                                },
                            beyondViewportPageCount = 3,
                        ) { pi ->
                            val rendered = win.slotAt(pi)
                            if (rendered == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    LottieLoader(modifier = Modifier.size(64.dp))
                                }
                            } else {
                                val (c, p) = rendered.id
                                PageView(
                                    book = book,
                                    spine = c,
                                    slices = rendered.slices,
                                    prefs = prefs,
                                    fontFamily = fontFamily,
                                    ink = th.ink,
                                    linkColor = linkColor,
                                    onLinkClick = { tag -> handleLink(tag) },
                                    highlightBlock = ttsPos?.takeIf { it.chapter == c }?.block,
                                    highlightFrom = ttsPos?.takeIf { it.chapter == c }?.startChar ?: 0,
                                    highlightTo = ttsPos?.takeIf { it.chapter == c }?.endChar ?: Int.MAX_VALUE,
                                )
                            }
                        }
                }

            // ---- 底栏：6 键 space evenly（底条连同导航键一整条渐变） ----
            // 上一首/播放/下一首 = 听书控制（段落级，跨页自动翻；句子模式下即上一句/下一句）
            ReaderBottomBar(
                barBrush = th.barBrush,
                barInk = th.barInk,
                isTtsPlaying = ttsPlaying,
                sentenceMode = ttsUnit == EbookTtsUnit.SENTENCE,
                onTtsPrev = { tts.prev() },
                onTtsToggle = {
                    if (ttsPlaying) {
                        tts.stop()
                    } else {
                        // 每次点播放都从当前页最顶上第一个字开始读
                        // （页顶可能是段落中间，带上块内字偏移）
                        val (c, p) = win.center
                        val sl = breaks[c]?.filter { it.isNotEmpty() }?.getOrNull(p)?.firstOrNull()
                        if (sl != null) tts.playFrom(c, sl.block, sl.start)
                    }
                },
                onTtsNext = { tts.next() },
                onToc = {
                    val (c, p) = win.center
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
        // 内置语音首次释放进度（173MB，几十秒）：居中遮罩
        val inst = ttsInstalling
        if (inst != null) {
            val (done, total, name) = inst
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.fillMaxWidth(0.8f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("正在准备内置语音", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D22))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$name（$done/$total）",
                        fontSize = 13.sp, color = Color(0xFF6B7280),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("首次使用需释放语音数据，之后不再等待", fontSize = 12.sp, color = Color(0xFF6B7280))
                }
            }
        }
    }
}

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
    highlightBlock: Int? = null,
    highlightFrom: Int = 0,
    highlightTo: Int = Int.MAX_VALUE,
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
                    // 在读段落黄底高亮（后加，盖掉链接色，字强制黑色保证可读）；
                    // 起点是段中时，只染起始字之后；句子模式下只染当前句区间
                    if (highlightBlock != null && bi == highlightBlock) {
                        val hs = maxOf(from, highlightFrom.coerceIn(0, b.text.length))
                        val he = minOf(to, highlightTo.coerceIn(0, b.text.length))
                        if (hs < he) {
                            addStyle(
                                SpanStyle(background = Color(0xFFFFEB3B), color = Color.Black),
                                segBase + (hs - from),
                                segBase + (he - from),
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
    barBrush: Brush,
    barInk: Color,
    isTtsPlaying: Boolean,
    sentenceMode: Boolean = false,
    onTtsPrev: () -> Unit,
    onTtsToggle: () -> Unit,
    onTtsNext: () -> Unit,
    onToc: () -> Unit,
    onOptions: () -> Unit,
    onPlaceholder: () -> Unit,
) {
    // 背景盖住按钮区 + 底部导航键区，两者连成一条（播放界面同款做法）
    Column(Modifier.fillMaxWidth().background(barBrush)) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderButton("目录", Icons.AutoMirrored.Filled.List, barInk, 1f, onToc)
            ReaderButton("音乐", Icons.Filled.MusicNote, barInk, 0.35f, onPlaceholder)
            ReaderButton(if (sentenceMode) "上一句" else "上一首", Icons.Filled.SkipPrevious, barInk, 1f, onTtsPrev)
            ReaderButton(
                if (isTtsPlaying) "停止" else "播放",
                if (isTtsPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                barInk, 1f, onTtsToggle,
            )
            ReaderButton(if (sentenceMode) "下一句" else "下一首", Icons.Filled.SkipNext, barInk, 1f, onTtsNext)
            ReaderButton("选项", Icons.Filled.Settings, barInk, 1f, onOptions)
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
    val prefs = (LocalContext.current.applicationContext as AuroraApplication).container.ebookPrefs.prefs.collectAsStateWithLifecycle().value
    val th = themeOf(prefs.theme)
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
    // 当前位置对应的条目：与听书通知栏作者栏同口径，见 TocSection
    val currentEntry = remember(book, currentSpine, currentBlock) {
        com.aurora.music.data.ebook.TocSection.currentEntry(book.toc, currentSpine, currentBlock)
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
    BoxWithConstraints(Modifier.fillMaxSize().background(th.bg)) {
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
    val ttsPrefs by container.settingsStore.unifiedTts.collectAsStateWithLifecycle(initialValue = UnifiedTtsPrefs())
    val ttsUnit by container.ebookPrefs.ttsUnit.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var engines by remember { mutableStateOf<List<TtsEngineInfo>>(emptyList()) }
    // 进设置页即拉引擎列表并刷新当前引擎的音色列表
    LaunchedEffect(Unit) {
        engines = container.ebookTts.listSystemEngines()
        container.ebookTts.refreshSystemVoices()
    }
    fun engineLabel(): String = when {
        ttsPrefs.isInternal -> "内置微软离线"
        ttsPrefs.engine.isBlank() -> "系统默认引擎"
        else -> engines.firstOrNull { it.packageName == ttsPrefs.engine }?.label ?: ttsPrefs.engine
    }
    fun voiceLabel(): String = when {
        ttsPrefs.voice.isBlank() -> if (ttsPrefs.isInternal) "晓晓（默认）" else "自动（中文优先）"
        ttsPrefs.isInternal -> MsVoices.byCode(ttsPrefs.voice)?.showName ?: ttsPrefs.voice
        else -> ttsPrefs.voice
    }
    var engineOpen by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    BackHandler(engineOpen || voiceOpen) {
        if (voiceOpen) voiceOpen = false else engineOpen = false
    }
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
    val th = themeOf(prefs.theme)
    Box(Modifier.fillMaxSize().background(th.bg)) {
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
            // 最近用过的自定义字体（当前选中的即便还没进名单也排第一，方便老用户迁移）
            val recentFonts by container.ebookPrefs.recentFonts.collectAsStateWithLifecycle()
            val fontOptions = remember(prefs.fontPath, recentFonts) {
                (listOf(prefs.fontPath).filter { it.isNotBlank() } + recentFonts)
                    .distinct().take(MAX_RECENT_FONTS)
            }
            fontOptions.forEach { path ->
                Ios5CellDivider()
                com.aurora.music.ui.ios5.Ios5CheckRow(
                    title = File(path).name,
                    subtitle = "自定义字体",
                    checked = prefs.fontPath == path,
                    onClick = { container.ebookPrefs.setFontPath(path) },
                )
            }
            Ios5CellDivider()
            Ios5NavRow(title = "选择字体文件", subtitle = "ttf / otf", onClick = { fontPicker.launch("*/*") })
        }
        ios5Section("文字大小") {
            // 拖动只改本地预览，抬手才落盘，避免每 tick 重排分页
            var sizeDraft by remember(prefs.fontSizeSp) { mutableFloatStateOf(prefs.fontSizeSp) }
            Ios5SliderRow(
                title = "字号",
                valueLabel = "${sizeDraft.toInt()}",
                value = sizeDraft,
                range = 12f..50f,
                steps = 37,
                onValueChange = { sizeDraft = it },
                onValueChangeFinished = { container.ebookPrefs.setFontSize(sizeDraft) },
            )
            Ios5StaticText("左右滑动翻页时的每页字数会随字号变化，断点会自动重排并记住。")
        }
        ios5Section("听书语音") {
            // 朗读切片（电子书独有，仍存阅读设置）
            Ios5SegmentRow(
                title = "朗读切片",
                options = listOf("按段落", "按句子"),
                selected = if (ttsUnit == EbookTtsUnit.SENTENCE) 1 else 0,
                onSelect = {
                    container.ebookTts.stop()
                    val u = if (it == 1) EbookTtsUnit.SENTENCE else EbookTtsUnit.PARA
                    container.ebookPrefs.setTtsUnit(u)
                    container.ebookTts.setUnit(u)
                },
            )
            Ios5CellDivider()
            Ios5StaticText("按句子切分时，上一首/下一首即上一句/下一句，高亮只染当前句。中文按。！？；…断句，英文按.!?;断句，小数点不断句。")
            Ios5CellDivider()
            // 引擎与音色（统一 TTS 设置，与 设置→歌曲介绍→语音 互通，歌曲介绍同款分级页）
            Ios5NavRow(
                title = "引擎",
                subtitle = engineLabel(),
                onClick = { engineOpen = true },
            )
            Ios5CellDivider()
            Ios5NavRow(
                title = "音色",
                subtitle = voiceLabel(),
                onClick = { voiceOpen = true },
            )
            Ios5CellDivider()
            Ios5StaticText("与 设置 → 歌曲介绍 → 语音 为同一设置，两处互通。切换引擎或音色会停掉当前朗读。")
        }
        ios5Section("听书语速") {
            Ios5SliderRow(
                title = "语速",
                valueLabel = String.format(java.util.Locale.US, "%.2fx", ttsPrefs.rate),
                value = ttsPrefs.rate,
                range = 0.5f..2f,
                steps = 29,
                onValueChange = { scope.launch { container.settingsStore.setIntroTtsRate(it) } },
            )
            Ios5CellDivider()
            Ios5SliderRow(
                title = "音调",
                valueLabel = String.format(java.util.Locale.US, "%.2fx", ttsPrefs.pitch),
                value = ttsPrefs.pitch,
                range = 0.5f..2f,
                steps = 29,
                onValueChange = { scope.launch { container.settingsStore.setIntroTtsPitch(it) } },
            )
            Ios5StaticText("内置与系统语音都经过均衡器 DSP 链（校正/用户均衡/动态等）。切换引擎或音色会停掉当前朗读。与歌曲介绍的语音为同一设置，两处互通。")
        }
        ios5Section("混音与音量") {
            Ios5SwitchRow(
                title = "压低其他音乐",
                subtitle = "听书时让站外音乐自动降低（导航模式；降多少由对方 App 决定）",
                checked = ttsPrefs.duckOthers,
                onCheckedChange = { scope.launch { container.settingsStore.setDuckOthers(it) } },
            )
            Ios5CellDivider()
            Ios5SliderRow(
                title = "站内音乐音量",
                valueLabel = "${(ttsPrefs.ownMusicLevel * 100).toInt()}%",
                value = ttsPrefs.ownMusicLevel,
                range = 0.05f..1f,
                steps = 18,
                onValueChange = { scope.launch { container.settingsStore.setOwnMusicLevel(it) } },
            )
            Ios5CellDivider()
            Ios5StaticText("一边听书一边听本站的歌时，音乐压到该比例，下一首即生效。")
            Ios5CellDivider()
            Ios5SliderRow(
                title = "听书音量",
                valueLabel = "${(ttsPrefs.volume * 100).toInt()}%",
                value = ttsPrefs.volume,
                range = 0.2f..2f,
                steps = 35,
                onValueChange = { scope.launch { container.settingsStore.setTtsVolume(it) } },
            )
            if (!ttsPrefs.isInternal) {
                Ios5CellDivider()
                Ios5StaticText("系统语音超过 100% 的部分无效（设置保留，切回内置离线即生效）。")
            }
            Ios5StaticText("与歌曲介绍的语音为同一设置，音量与压低开关两处互通。")
        }
        ios5Section("睡眠定时") {
            val timer by container.ebookTts.sleepTimer.collectAsStateWithLifecycle()
            val countMinutes = listOf(0, 15, 30, 45, 60)
            Ios5SegmentRow(
                title = "倒计时",
                options = listOf("关闭", "15分钟", "30分钟", "45分钟", "60分钟"),
                selected = countMinutes.indexOf(timer?.totalMinutes ?: 0).takeIf { it >= 0 } ?: 0,
                onSelect = {
                    val m = countMinutes[it]
                    if (m == 0) container.ebookTts.cancelSleepTimer()
                    else container.ebookTts.setSleepMinutes(m)
                },
            )
            Ios5CellDivider()
            Ios5NavRow(
                title = "定时停止",
                subtitle = timer?.clockLabel?.let { "$it（读完当前再停）" } ?: "未设置",
                onClick = {
                    val c = Calendar.getInstance()
                    TimePickerDialog(
                        context,
                        { _, h, m -> container.ebookTts.setSleepAt(h, m) },
                        c.get(Calendar.HOUR_OF_DAY),
                        c.get(Calendar.MINUTE),
                        true,
                    ).show()
                },
            )
            val cur = timer
            if (cur != null) {
                Ios5CellDivider()
                var nowMs by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                LaunchedEffect(Unit) {
                    while (container.ebookTts.sleepTimer.value != null) {
                        delay(20_000)
                        nowMs = android.os.SystemClock.elapsedRealtime()
                    }
                }
                val remainMin = ((cur.deadlineElapsed - nowMs + 59_999) / 60_000).coerceAtLeast(1)
                val unitName = if (ttsUnit == EbookTtsUnit.SENTENCE) "句" else "段"
                Ios5StaticText("约 $remainMin 分钟后停止，将读完当前${unitName}再停。手动停止朗读会清除定时。")
            } else {
                Ios5StaticText("到点后读完当前再停。倒计时与定时二选一，后设的生效；选关闭可清除定时。")
            }
        }
    }
    if (engineOpen) EbookTtsEnginePage(engines = engines, onBack = { engineOpen = false })
    if (voiceOpen) EbookTtsVoicePage(engineLabel = engineLabel(), onBack = { voiceOpen = false })
    }
}

// ---- 听书引擎子页（歌曲介绍语音屏同款，统一 TTS 设置的修改入口之一） ----

// ---- 听书引擎子页（歌曲介绍语音屏同款，统一 TTS 设置的修改入口之一） ----

@Composable
private fun EbookTtsEnginePage(engines: List<TtsEngineInfo>, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val prefs by container.ebookPrefs.prefs.collectAsStateWithLifecycle()
    val unified by container.settingsStore.unifiedTts.collectAsStateWithLifecycle(initialValue = UnifiedTtsPrefs())
    val scope = rememberCoroutineScope()
    val th = themeOf(prefs.theme)

    fun select(engine: String) {
        container.ebookTts.stop()
        scope.launch {
            container.settingsStore.setIntroTtsEngine(engine)
            container.ebookTts.refreshSystemVoices()
        }
    }

    Box(Modifier.fillMaxSize().background(th.bg)) {
        Ios5SettingsPage(title = "引擎", onBack = onBack) {
            ios5Section("语音引擎") {
                com.aurora.music.ui.ios5.Ios5CheckRow(
                    title = "内置微软离线",
                    subtitle = "随 App 打包，无需联网",
                    checked = unified.isInternal,
                    onClick = { select(TTS_ENGINE_INTERNAL) },
                )
                Ios5CellDivider()
                com.aurora.music.ui.ios5.Ios5CheckRow(
                    title = "系统默认引擎",
                    subtitle = "跟随系统设置",
                    checked = unified.engine.isBlank(),
                    onClick = { select("") },
                )
                engines.forEach { e ->
                    Ios5CellDivider()
                    com.aurora.music.ui.ios5.Ios5CheckRow(
                        title = e.label + if (e.isDefault) "（默认）" else "",
                        subtitle = e.packageName,
                        checked = e.packageName == unified.engine,
                        onClick = { select(e.packageName) },
                    )
                }
            }
            ios5FootNote("与 设置 → 歌曲介绍 → 语音 为同一设置，两处互通。切换引擎会停掉当前朗读。")
        }
    }
}

// ---- 听书音色子页（随当前引擎变化，统一 TTS 设置的修改入口之一） ----

@Composable
private fun EbookTtsVoicePage(engineLabel: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val prefs by container.ebookPrefs.prefs.collectAsStateWithLifecycle()
    val unified by container.settingsStore.unifiedTts.collectAsStateWithLifecycle(initialValue = UnifiedTtsPrefs())
    val systemVoices by container.ebookTts.systemVoices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val th = themeOf(prefs.theme)

    fun selectVoice(v: String) {
        container.ebookTts.stop()
        scope.launch { container.settingsStore.setIntroTtsVoice(v) }
    }

    Box(Modifier.fillMaxSize().background(th.bg)) {
        Ios5SettingsPage(title = "音色（$engineLabel）", onBack = onBack) {
            ios5Section("音色") {
                if (unified.isInternal) {
                    com.aurora.music.ui.ios5.Ios5StaticText("内置离线语音（随 App 打包，无需联网）")
                    MsVoices.ALL.forEach { v ->
                        Ios5CellDivider()
                        com.aurora.music.ui.ios5.Ios5CheckRow(
                            title = v.showName,
                            subtitle = v.code,
                            checked = (unified.voice.ifBlank { MsVoices.DEFAULT }) == v.code,
                            onClick = { selectVoice(v.code) },
                        )
                    }
                } else {
                    com.aurora.music.ui.ios5.Ios5CheckRow(
                        title = "自动",
                        subtitle = "优先中文语音",
                        checked = unified.voice.isBlank(),
                        onClick = { selectVoice("") },
                    )
                    systemVoices.take(60).forEach { v ->
                        Ios5CellDivider()
                        com.aurora.music.ui.ios5.Ios5CheckRow(
                            title = v.name,
                            subtitle = v.locale.toString(),
                            checked = unified.voice == v.name,
                            onClick = { selectVoice(v.name) },
                        )
                    }
                }
            }
        }
    }
}
