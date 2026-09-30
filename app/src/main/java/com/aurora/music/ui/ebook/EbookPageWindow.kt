package com.aurora.music.ui.ebook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ebook.EbookReadPrefs
import com.aurora.music.data.ebook.ParsedEbook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ---- 41 页循环窗口：翻页的唯一真相源（UI 之外） ----
// 前后各预载 20 页，内存常驻 41 个物理槽；pager 固定 41 页、center 恒为 20。
// 用户滑动永远只在槽内移动，落定后环旋转并 scrollToPage(20)（无动画、视觉不动）。
// 加载赶不上时槽留白（loader），前端只管渲染，不管补洞。

/** 前后各预载页数（常量）：内存常驻 2*20+1 = 41 页切片。 */
const val PAGE_PRELOAD_RADIUS = 20
const val PAGE_WINDOW_SIZE = PAGE_PRELOAD_RADIUS * 2 + 1
const val PAGE_WINDOW_CENTER = PAGE_PRELOAD_RADIUS

/** 每次落定后前后各预排多少章（按章序号盲取，不依赖页数，专治短章连续跨章）。 */
private const val PREFETCH_CHAPTERS = 2

/** 全局坐标：唯一的页面位置表示。 */
data class GlobalPage(val chapter: Int, val page: Int)

/** 渲染槽：坐标 + 该页切片（slices 为空表示 loader，由调用方按 null 槽处理）。 */
data class RenderedPage(val id: GlobalPage, val slices: List<PageSlice>)

/** 目录/站内链接的按块跳转请求（gen 区分同目标的连续点击）。 */
data class BlockJump(val chapter: Int, val block: Int, val char: Int = 0, val gen: Long = 0)

/** 该章是否有可用页（空页列表视为缺失，触发重排并自愈脏缓存）。 */
fun hasPages(m: Map<Int, List<List<PageSlice>>>, ci: Int): Boolean =
    m[ci]?.any { it.isNotEmpty() } == true

fun pageForBlock(pages: List<List<PageSlice>>, block: Int): Int {
    pages.forEachIndexed { i, page ->
        if (page.any { it.block >= block }) return i
    }
    // 块超出本章所有页：钳到末页（就近），不能回 0（回章首即“另一页”）。
    return (pages.size - 1).coerceAtLeast(0)
}

/** 字所在页：同一块可能跨多页，按 (block, char) 精确定位。 */
fun pageForChar(pages: List<List<PageSlice>>, block: Int, char: Int): Int {
    pages.forEachIndexed { i, page ->
        if (page.any { s -> s.block == block && s.end > char }) return i
    }
    return pageForBlock(pages, block)
}

// ---- 纯函数坐标换算：只依赖各章页数，不依赖 Compose ----

private fun pageCountOf(breaks: Map<Int, List<List<PageSlice>>>, ci: Int): Int =
    breaks[ci]?.filter { it.isNotEmpty() }?.size ?: -1 // -1 = 未知（未分页）

/** 全局序号（counts 未知章按 0 页计，仅用于估算；未知区返回 -1）。 */
fun globalIndexOf(counts: List<Int>, g: GlobalPage): Int {
    if (g.chapter !in counts.indices) return -1
    if (counts[g.chapter] < 0 || g.page >= counts[g.chapter]) return -1
    var n = 0
    for (i in 0 until g.chapter) n += counts[i].coerceAtLeast(0)
    return n + g.page
}

/** 序号反查（落在未知/空章区返回 null）。 */
fun globalPageAt(counts: List<Int>, index: Int): GlobalPage? {
    if (index < 0) return null
    var acc = 0
    counts.forEachIndexed { ci, n ->
        if (n <= 0) return@forEachIndexed // 未知/空章跳过
        if (index < acc + n) return GlobalPage(ci, index - acc)
        acc += n
    }
    return null
}

private fun stepForward(breaks: Map<Int, List<List<PageSlice>>>, g: GlobalPage, chapterCount: Int): GlobalPage? {
    val n = pageCountOf(breaks, g.chapter)
    if (n < 0) return null // 本章未知：停住，等 paginate
    if (g.page + 1 < n) return g.copy(page = g.page + 1)
    var nc = g.chapter + 1
    while (nc < chapterCount) {
        val nn = pageCountOf(breaks, nc)
        if (nn < 0) return null // 邻章未知：停住，等 paginate
        if (nn > 0) return GlobalPage(nc, 0)
        nc++ // 空章（全空块）跳过
    }
    return null // 书末
}

private fun stepBackward(breaks: Map<Int, List<List<PageSlice>>>, g: GlobalPage): GlobalPage? {
    if (pageCountOf(breaks, g.chapter) < 0) return null
    if (g.page > 0) return g.copy(page = g.page - 1)
    var nc = g.chapter - 1
    while (nc >= 0) {
        val nn = pageCountOf(breaks, nc)
        if (nn < 0) return null
        if (nn > 0) return GlobalPage(nc, nn - 1)
        nc--
    }
    return null // 书首
}

/** 以 center 为中心的 41 坐标（越界/未知为 null），center 恒在下标 20。 */
fun windowCoords(
    center: GlobalPage,
    breaks: Map<Int, List<List<PageSlice>>>,
    chapterCount: Int,
    radius: Int = PAGE_PRELOAD_RADIUS,
): List<GlobalPage?> {
    val out = MutableList<GlobalPage?>(radius * 2 + 1) { null }
    out[radius] = center
    var cur = center
    for (i in 1..radius) {
        cur = stepBackward(breaks, cur) ?: break
        out[radius - i] = cur
    }
    cur = center
    for (i in 1..radius) {
        cur = stepForward(breaks, cur, chapterCount) ?: break
        out[radius + i] = cur
    }
    return out
}

// ---- 窗口本体：循环数组状态机（分页 IO 由 Host 注入） ----
// 41 个物理槽 + 环偏移：逻辑槽 s（0..40，center 恒为 20）映射到物理槽 (s + offset) % 41。
// 落定平移只是 O(1) 转环 + 新入槽留白 + 边缘扩展；重叠槽的对象身份不变，不重组不漂移。
// 填充（坐标扩展 + 切片填入）是纯数学 pass；慢的 paginate 永远走在手势前面，
// 实在没排上就留白显示 loader，前端只管渲染，不管补洞。

/** 落定结果：UI 只需按三态处理，不碰分页细节。 */
sealed interface MoveResult {
    data object Moved : MoveResult
    data object AtEdge : MoveResult // 真到书首/书末：弹回 center
    data class NeedChapter(val chapter: Int) : MoveResult // 邻章没排上：urgent 排完重试
}

class PageWindow(val chapterCount: Int, initial: GlobalPage) {
    var center by mutableStateOf(initial)
        private set
    // 物理槽（固定 41 格，只转环不增减；RenderedPage 是小数据，41 页常驻内存无压力）
    private val slotItems: SnapshotStateList<RenderedPage?> =
        mutableStateListOf(*arrayOfNulls<RenderedPage?>(PAGE_WINDOW_SIZE))
    private val coordItems: SnapshotStateList<GlobalPage?> =
        mutableStateListOf(*arrayOfNulls<GlobalPage?>(PAGE_WINDOW_SIZE))
    private var ringOffset by mutableIntStateOf(0)
    val breaks = mutableStateMapOf<Int, List<List<PageSlice>>>()

    /** Host 注入：按需排一章（主线程，TextMeasurer 要求），返回该章是否有可用页。 */
    internal var paginateChapter: (suspend (Int) -> Boolean)? = null

    init {
        fullRewrite()
    }

    private fun phys(logical: Int): Int =
        ((logical + ringOffset) % PAGE_WINDOW_SIZE + PAGE_WINDOW_SIZE) % PAGE_WINDOW_SIZE

    /** 逻辑槽内容（Compose 可观察：订阅 offset + 对应物理格）。 */
    fun slotAt(logical: Int): RenderedPage? =
        if (logical in 0 until PAGE_WINDOW_SIZE) slotItems[phys(logical)] else null

    fun coordAt(logical: Int): GlobalPage? =
        if (logical in 0 until PAGE_WINDOW_SIZE) coordItems[phys(logical)] else null

    private fun setSlot(logical: Int, v: RenderedPage?) { slotItems[phys(logical)] = v }
    private fun setCoord(logical: Int, v: GlobalPage?) { coordItems[phys(logical)] = v }

    private fun contentOf(id: GlobalPage): RenderedPage? =
        breaks[id.chapter]?.getOrNull(id.page)
            ?.takeIf { it.isNotEmpty() }?.let { RenderedPage(id, it) }

    /** 全量重算 41 槽（跳转/冷启动用，便宜的纯数学 pass）。 */
    fun fullRewrite() {
        val cs = windowCoords(center, breaks, chapterCount, PAGE_PRELOAD_RADIUS)
        cs.forEachIndexed { i, g ->
            setCoord(i, g)
            setSlot(i, g?.let { contentOf(it) })
        }
    }

    /** 该全局页当前在哪个逻辑槽（不在窗口返回 null）。 */
    fun adjacentSlotOf(g: GlobalPage): Int? {
        for (i in 0 until PAGE_WINDOW_SIZE) if (coordAt(i) == g) return i
        return null
    }

    /** 窗口内已知坐标涉及的章（Host 按章序号盲预排前后 2 章用）。 */
    fun knownChapters(): List<Int> {
        val out = ArrayList<Int>(PAGE_WINDOW_SIZE)
        for (i in 0 until PAGE_WINDOW_SIZE) coordAt(i)?.let { out += it.chapter }
        return out
    }

    /**
     * 手势落定：按 delta 转环（delta = settledPage - 20，可 ±20，支持连甩）。
     * 目标在环内 → O(1) 转环 + 新入槽留白 + 边缘扩展；
     * 翻出已知区但书没完 → NeedChapter（UI urgent 排完重试，期间留白）；
     * 真到书首/书末 → AtEdge（UI 弹回 center）。
     */
    fun moveBy(delta: Int): MoveResult {
        if (delta == 0) return MoveResult.Moved
        if (delta <= -PAGE_WINDOW_SIZE || delta >= PAGE_WINDOW_SIZE) return MoveResult.AtEdge
        coordAt(PAGE_WINDOW_CENTER + delta)?.let { t ->
            center = t
            rotate(delta)
            return MoveResult.Moved
        }
        // 翻出已知区：是书界还是邻章没排上？
        return if (delta > 0) {
            val last = (PAGE_WINDOW_CENTER + delta - 1 downTo 0)
                .firstNotNullOfOrNull { coordAt(it) } ?: return MoveResult.AtEdge
            val n = pageCountOf(breaks, last.chapter)
            // last 自身未知（n<0）→ 它所在的章就是要排的；已知 → 下一章要排；没下一章 → 真到书末
            val nc = last.chapter + (if (n < 0) 0 else 1)
            if (nc >= chapterCount) MoveResult.AtEdge else MoveResult.NeedChapter(nc)
        } else {
            val first = (PAGE_WINDOW_CENTER + delta + 1 until PAGE_WINDOW_SIZE)
                .firstNotNullOfOrNull { coordAt(it) } ?: return MoveResult.AtEdge
            val n = pageCountOf(breaks, first.chapter)
            // first 自身未知 → 它所在的章就是要排的；已知 → 上一章要排；没上一章 → 真到书首
            val nc = first.chapter - (if (n < 0) 0 else 1)
            if (nc < 0) MoveResult.AtEdge else MoveResult.NeedChapter(nc)
        }
    }

    /** O(1) 转环：重叠槽内容自动就位，新入槽留白后从边缘扩展。 */
    private fun rotate(delta: Int) {
        ringOffset = ((ringOffset + delta) % PAGE_WINDOW_SIZE + PAGE_WINDOW_SIZE) % PAGE_WINDOW_SIZE
        if (delta > 0) {
            for (s in PAGE_WINDOW_SIZE - delta until PAGE_WINDOW_SIZE) {
                setCoord(s, null)
                setSlot(s, null)
            }
            extendForwardFrom(PAGE_WINDOW_SIZE - delta - 1)
        } else {
            for (s in 0 until -delta) {
                setCoord(s, null)
                setSlot(s, null)
            }
            extendBackwardFrom(-delta)
        }
    }

    private fun extendForwardFrom(anchorLogical: Int) {
        var cur = coordAt(anchorLogical)
        for (s in anchorLogical + 1 until PAGE_WINDOW_SIZE) {
            cur = cur?.let { stepForward(breaks, it, chapterCount) }
            setCoord(s, cur)
            setSlot(s, cur?.let { contentOf(it) })
        }
    }

    private fun extendBackwardFrom(anchorLogical: Int) {
        var cur = coordAt(anchorLogical)
        for (s in anchorLogical - 1 downTo 0) {
            cur = cur?.let { stepBackward(breaks, it) }
            setCoord(s, cur)
            setSlot(s, cur?.let { contentOf(it) })
        }
    }

    /**
     * 查漏补缺（Host 每次分页/缓存加载后调）：null 边缘向外扩展 + 空槽填内容 + 校验 center。
     * 中间段不可能有洞（步进是连续的，洞只出现在两端），所以只处理两端。
     */
    fun fillGaps() {
        val first = (0 until PAGE_WINDOW_SIZE).firstOrNull { coordAt(it) != null }
        if (first == null) {
            fullRewrite()
            return
        }
        extendBackwardFrom(first)
        val last = (PAGE_WINDOW_SIZE - 1 downTo 0).firstOrNull { coordAt(it) != null } ?: return
        extendForwardFrom(last)
        for (s in 0 until PAGE_WINDOW_SIZE) {
            val c = coordAt(s)
            if (c != null && slotAt(s) == null) setSlot(s, contentOf(c))
        }
        validateCenter()
    }

    /** center 落在空章/越界页时就近修正（推测性前进的兜底，极少触发）。 */
    private fun validateCenter() {
        val n = pageCountOf(breaks, center.chapter)
        if (n < 0) return
        if (n == 0 || center.page >= n) {
            val alt = stepForward(breaks, center, chapterCount) ?: stepBackward(breaks, center)
            if (alt != null && alt != center) {
                center = alt
                fullRewrite()
            }
        }
    }

    fun jumpTo(g: GlobalPage): Boolean {
        if (chapterCount <= 0) return false
        val c = g.copy(
            chapter = g.chapter.coerceIn(0, chapterCount - 1),
            page = g.page.coerceAtLeast(0),
        )
        if (c == center) return false
        center = c
        fullRewrite()
        return true
    }

    /** 按块跳：先 ensure 再定位（调用方已先落盘块锚点，不等分页）。 */
    suspend fun jumpToBlock(chapter: Int, block: Int): Boolean {
        if (chapter !in 0 until chapterCount) return false
        paginateChapter?.invoke(chapter)
        val pages = breaks[chapter]?.filter { it.isNotEmpty() }.orEmpty()
        val p = if (pages.isEmpty()) 0 else pageForBlock(pages, block)
        return jumpTo(GlobalPage(chapter, p))
    }

    /** TTS 跟读：同一调用跨章，按 (block, char) 精确定位。 */
    suspend fun jumpToChar(chapter: Int, block: Int, char: Int): Boolean {
        if (chapter !in 0 until chapterCount) return false
        paginateChapter?.invoke(chapter)
        val pages = breaks[chapter]?.filter { it.isNotEmpty() }.orEmpty()
        val p = if (pages.isEmpty()) 0 else pageForChar(pages, block, char)
        return jumpTo(GlobalPage(chapter, p))
    }
}

// ---- PaginatorHost：分页 + 缓存 + 预排全部收敛在这里 ----

@Composable
fun rememberPageWindow(
    bookPath: String,
    book: ParsedEbook,
    widthPx: Int,
    heightPx: Int,
    prefs: EbookReadPrefs,
    fontFamily: FontFamily,
    initial: GlobalPage,
    anchorBlock: Int = 0,
    anchorChar: Int = 0,
): PageWindow {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val store = container.ebookStore
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val win = remember(bookPath) {
        PageWindow(
            chapterCount = book.chapters.size,
            initial = initial.copy(
                chapter = initial.chapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0)),
                page = initial.page.coerceAtLeast(0),
            ),
        )
    }
    var cacheBase by remember(bookPath) { mutableStateOf<Map<Int, List<List<PageSlice>>>>(emptyMap()) }
    var cacheKey by remember(bookPath) { mutableStateOf<String?>(null) }
    var bookMd5 by remember(bookPath) { mutableStateOf("") }
    var anchorApplied by remember(bookPath) { mutableStateOf(anchorBlock <= 0 && anchorChar <= 0) }
    // 落盘串行化：预排循环与跳转并发写全书 JSON 时不互相覆盖丢章
    val saveMutex = remember(bookPath) { Mutex() }

    val key = remember(prefs.fontSizeSp, prefs.fontPath, widthPx, heightPx) {
        if (heightPx > 100) store.pageCacheKey(prefs, widthPx, heightPx) else null
    }

    // 磁盘缓存命中则整本直接可用（秒开）；字号/字体/屏宽变化时旧断点作废重排。
    LaunchedEffect(bookPath, key) {
        if (key == null) return@LaunchedEffect
        cacheKey = key
        val md5 = store.md5Of(bookPath)
        bookMd5 = md5
        val cached = store.loadPageBreaks(md5, key)
        if (!cached.isNullOrEmpty()) {
            val m = pagesFromCache(cached).filterValues { ps -> ps.any { it.isNotEmpty() } }
            cacheBase = m
            win.breaks.clear()
            win.breaks.putAll(m)
        } else {
            cacheBase = emptyMap()
            win.breaks.clear()
        }
        win.fullRewrite()
    }

    suspend fun paginateOne(ci: Int): Boolean {
        if (ci !in book.chapters.indices) return false
        if (hasPages(win.breaks, ci)) return true
        val k = cacheKey ?: key ?: return false
        if (heightPx <= 100) return false
        val paginator = ChapterPaginator(measurer, density, widthPx, heightPx, prefs.fontSizeSp, fontFamily)
        val nb = runCatching { paginator.paginate(book.chapters[ci].blocks) }.getOrDefault(emptyList())
        if (nb.isNotEmpty()) {
            win.breaks[ci] = nb
            win.fillGaps() // 边缘扩展 + 空槽填入，loader 立刻消失；落盘随后在 IO 慢慢写
            // 全书 JSON 序列化 + 文件写挪到 IO：连续预排多章时主线程不卡手势
            saveMutex.withLock {
                val payload = pagesToCache(cacheBase + win.breaks)
                val md5 = bookMd5.ifBlank { store.md5Of(bookPath).also { bookMd5 = it } }
                withContext(Dispatchers.IO) { store.savePageBreaks(md5, k, payload) }
            }
            return true
        }
        return false
    }
    win.paginateChapter = ::paginateOne

    // 按需分页：41 槽涉及的章 + 落定后前后各 2 章盲预排。
    // 用户每次翻页落定都会触发（key 含 center），后面的章提前排好，翻过去零等待。
    // 按章序号盲取（不依赖页数）：窗口边缘为 null 时也能预排，短章连续跨章不卡。
    // effect 内多次回写用 breaks 派生的签名做 key，分页完成即重启以级联扩展窗口。
    val breaksSig by remember {
        derivedStateOf {
            win.breaks.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.size}" }
        }
    }
    val center = win.center
    LaunchedEffect(center, breaksSig, key, bookPath) {
        if (key == null || heightPx <= 100) return@LaunchedEffect
        val known = win.knownChapters()
        val firstCh = known.minOrNull() ?: center.chapter
        val lastCh = known.maxOrNull() ?: center.chapter
        val ahead = (1..PREFETCH_CHAPTERS).map { lastCh + it }.filter { it < book.chapters.size }
        val behind = (1..PREFETCH_CHAPTERS).map { firstCh - it }.filter { it >= 0 }
        val near = ((center.chapter - PREFETCH_CHAPTERS)..(center.chapter + PREFETCH_CHAPTERS))
            .filter { it in book.chapters.indices }
        val need = (known + center.chapter + ahead + behind + near)
            .filter { it in book.chapters.indices && !hasPages(win.breaks, it) }
            .sortedBy { kotlin.math.abs(it - center.chapter) }
        for (n in need) {
            paginateOne(n)
            // 每章让出主线程：大书首开不卡手势（TextMeasurer 必须主线程，只能协作式）
            kotlinx.coroutines.yield()
        }
        // 首开/字号变化后存页越界：钳到该章末页（下标不漂移，只动 center）
        val cnt = pageCountOf(win.breaks, center.chapter)
        if (cnt > 0 && center.page >= cnt) {
            win.jumpTo(GlobalPage(center.chapter, cnt - 1))
        }
        // DB 块/字锚点（恢复优先级高于存的页码）：首章排好后一次性推导
        if (!anchorApplied && (anchorBlock > 0 || anchorChar > 0)) {
            val pages = win.breaks[center.chapter]?.filter { it.isNotEmpty() }.orEmpty()
            if (pages.isNotEmpty()) {
                anchorApplied = true
                val p = pageForChar(pages, anchorBlock, anchorChar)
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                if (p != center.page) win.jumpTo(GlobalPage(center.chapter, p))
            }
        }
    }
    return win
}
