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
import com.aurora.music.data.ebook.EbookBlock
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
internal const val PREFETCH_CHAPTERS = 2

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
    /** 随机阅读按章取到的正文：分页与渲染都以它为准，避免父级 book 还没重组时出现空白页。 */
    val chapterBlocks = mutableStateMapOf<Int, List<EbookBlock>>()

    /**
     * 恢复锚点（块/字）还没换算成页之前，先不渲染任何页内容。
     * 否则会先显示“本章第 0 页”，等分页完成再跳到锚点页，肉眼就是一次无意义的跳页。
     */
    var anchorPending by mutableStateOf(false)
        internal set

    fun blocksFor(chapter: Int): List<EbookBlock>? = chapterBlocks[chapter]

    /** Host 注入：按需排一章（主线程，TextMeasurer 要求），返回该章是否有可用页。 */
    internal var paginateChapter: (suspend (Int) -> Boolean)? = null

    init {
        fullRewrite()
    }

    private fun phys(logical: Int): Int =
        ((logical + ringOffset) % PAGE_WINDOW_SIZE + PAGE_WINDOW_SIZE) % PAGE_WINDOW_SIZE

    /** 逻辑槽内容（Compose 可观察：订阅 offset + 对应物理格）。 */
    fun slotAt(logical: Int): RenderedPage? =
        if (anchorPending) null
        else if (logical in 0 until PAGE_WINDOW_SIZE) slotItems[phys(logical)] else null

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

    /**
     * 按块跳：先 ensure 再定位（调用方已先落盘块锚点，不等分页）。
     * 该章还没排出页时返回 false 且**不动 center**：绝不能退回第 0 页。
     * 冷启动/Activity 重建时布局高度还没量到，paginateChapter 会短暂失败；
     * 若此时按章首落定，调用方还会把它当成“新进度”写回 DB，解锁后就永远停在本章开头。
     */
    suspend fun jumpToBlock(chapter: Int, block: Int): Boolean {
        if (chapter !in 0 until chapterCount) return false
        paginateChapter?.invoke(chapter)
        val pages = breaks[chapter]?.filter { it.isNotEmpty() }.orEmpty()
        if (pages.isEmpty()) return false
        return jumpTo(GlobalPage(chapter, pageForBlock(pages, block)))
    }

    /** TTS 跟读：同一调用跨章，按 (block, char) 精确定位；该章未排好时同样返回 false 不跳。 */
    suspend fun jumpToChar(chapter: Int, block: Int, char: Int): Boolean {
        if (chapter !in 0 until chapterCount) return false
        paginateChapter?.invoke(chapter)
        val pages = breaks[chapter]?.filter { it.isNotEmpty() }.orEmpty()
        if (pages.isEmpty()) return false
        return jumpTo(GlobalPage(chapter, pageForChar(pages, block, char)))
    }
}

// ---- PaginatorHost：分页 + 缓存 + 预排全部收敛在这里 ----

/**
 * 随机阅读的内存分页缓存：相邻页先按最终布局排好，翻页落定换成当前页时直接复用，
 * 不写磁盘、不碰本地书库。键 = bookKey + 排版参数。
 */
internal object FeedPageCache {
    private const val MAX_ENTRIES = 24
    private val map = object : LinkedHashMap<String, Map<Int, List<List<PageSlice>>>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Map<Int, List<List<PageSlice>>>>?,
        ): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): Map<Int, List<List<PageSlice>>>? = map[key]

    @Synchronized
    fun put(key: String, value: Map<Int, List<List<PageSlice>>>) {
        map[key] = value
    }

    @Synchronized
    fun clear() = map.clear()
}

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
    /** Compose 记忆键：随机阅读同一远程路径会反复进入，但正文随书变化，用它隔离窗口。 */
    bookKey: String = bookPath,
    /**
     * 按章取正文。null = 直接用 [book] 里已解析的块（本地书）。
     * 随机阅读传服务端章节加载器：窗口翻到该章时才下载正文，再交给分页器。
     */
    chapterLoader: (suspend (Int) -> List<EbookBlock>?)? = null,
    /** 随机阅读不写本地分页缓存/进度库，避免远程路径污染本地书架。 */
    persistPages: Boolean = true,
    /** 前后盲预排的章数；随机阅读传 0，只排当前章，翻到边界再按需取下一章。 */
    prefetchChapters: Int = PREFETCH_CHAPTERS,
): PageWindow {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val store = container.ebookStore
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val key = remember(prefs.fontSizeSp, prefs.fontPath, widthPx, heightPx, persistPages) {
        if (heightPx <= 100) null
        else if (persistPages) store.pageCacheKey(prefs, widthPx, heightPx)
        else "mem@${prefs.fontSizeSp.toInt()}x${prefs.fontPath.hashCode()}@${widthPx}x$heightPx"
    }

    // 最近一次真正读到的位置（页 + 页首块/字）。
    // 窗口会因屏幕高度变化（锁屏解锁时系统栏进出、旋转、改字号/字体）按 (bookKey, key) 重建；
    // 重建时必须从“当前读到哪”续，不能用打开这本书时的 initial/anchor ——
    // 那是**进入阅读页那一刻**的位置，通常会退回本章开头。
    val lastCenter = remember(bookKey) { mutableStateOf<GlobalPage?>(null) }
    val lastAnchor = remember(bookKey) { mutableStateOf<Pair<Int, Int>?>(null) }

    val windowSeed = remember(bookKey, key) {
        val prev = lastCenter.value
        val seed = (prev ?: initial).let {
            it.copy(
                chapter = it.chapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0)),
                page = it.page.coerceAtLeast(0),
            )
        }
        // 首次建窗用 DB/调用方给的锚点；重建时用上一扇窗最后读到的块/字（按新布局重新换算成页）。
        val anchor = lastAnchor.value ?: (anchorBlock to anchorChar)
        PageWindow(
            chapterCount = book.chapters.size,
            initial = seed,
        ).also { w ->
            // 有块/字锚点时，先别渲染第 0 页，等锚点换算成页后再显示，避免“先跳章首再跳回”。
            w.anchorPending = anchor.first > 0 || anchor.second > 0
            // 随机阅读：相邻页已经按同一布局排过一遍，换成当前页时直接复用，
            // 避免翻页落定的那一帧闪一下 loader。
            if (!persistPages && key != null) {
                FeedPageCache.get("$bookKey|$key")?.let { cached ->
                    w.breaks.putAll(cached)
                    w.fullRewrite()
                    // 相邻页已经排过：直接落到锚点页，避免新当前页先显示章首再跳回来。
                    if (anchor.first > 0 || anchor.second > 0) {
                        val pages = cached[w.center.chapter]?.filter { it.isNotEmpty() }.orEmpty()
                        if (pages.isNotEmpty()) {
                            w.jumpTo(GlobalPage(w.center.chapter, pageForChar(pages, anchor.first, anchor.second)))
                        }
                    }
                    w.anchorPending = false
                }
            }
        } to anchor
    }
    val win = windowSeed.first
    val windowAnchor = windowSeed.second
    var cacheBase by remember(bookKey) { mutableStateOf<Map<Int, List<List<PageSlice>>>>(emptyMap()) }
    var cacheKey by remember(bookKey) { mutableStateOf<String?>(null) }
    var bookMd5 by remember(bookKey) { mutableStateOf("") }
    var anchorApplied by remember(bookKey, key) {
        mutableStateOf(windowAnchor.first <= 0 && windowAnchor.second <= 0)
    }
    // 只记录“已落定”的位置：锚点换算完成前 slotAt 为 null，不能把第 0 页当成最新位置。
    LaunchedEffect(win.center, win.slotAt(PAGE_WINDOW_CENTER)) {
        lastCenter.value = win.center
        win.slotAt(PAGE_WINDOW_CENTER)?.slices?.firstOrNull()?.let {
            lastAnchor.value = it.block.coerceAtLeast(0) to it.start.coerceAtLeast(0)
        }
    }
    // 落盘串行化：预排循环与跳转并发写全书 JSON 时不互相覆盖丢章
    val saveMutex = remember(bookKey) { Mutex() }

    // 磁盘缓存命中则整本直接可用（秒开）；字号/字体/屏宽变化时旧断点作废重排。
    LaunchedEffect(bookKey, key) {
        if (key == null) return@LaunchedEffect
        cacheKey = key
        if (!persistPages) return@LaunchedEffect
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
        val blocks = chapterLoader?.invoke(ci) ?: book.chapters[ci].blocks
        if (blocks.isEmpty()) return false
        if (chapterLoader != null) win.chapterBlocks[ci] = blocks
        val paginator = ChapterPaginator(measurer, density, widthPx, heightPx, prefs.fontSizeSp, fontFamily)
        val nb = runCatching { paginator.paginate(blocks) }.getOrDefault(emptyList())
        if (nb.isNotEmpty()) {
            win.breaks[ci] = nb
            if (!persistPages) {
                FeedPageCache.put("$bookKey|$k", win.breaks.toMap())
            }
            win.fillGaps() // 边缘扩展 + 空槽填入，loader 立刻消失；落盘随后在 IO 慢慢写
            if (persistPages) {
                // 全书 JSON 序列化 + 文件写挪到 IO：连续预排多章时主线程不卡手势
                saveMutex.withLock {
                    val payload = pagesToCache(cacheBase + win.breaks)
                    val md5 = bookMd5.ifBlank { store.md5Of(bookPath).also { bookMd5 = it } }
                    withContext(Dispatchers.IO) { store.savePageBreaks(md5, k, payload) }
                }
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
    LaunchedEffect(center, breaksSig, key, bookKey) {
        if (key == null || heightPx <= 100) return@LaunchedEffect
        val known = win.knownChapters()
        // 从 FeedPageCache 恢复的断点只有分页结果、没有正文块。若此时 book 里
        // 该章还没缓存，PageView 会拿到空 blocks + 非空 slices，整页空白
        // （深色主题下就是黑屏）。先把窗口内各章的正文补上。
        if (chapterLoader != null) {
            for (c in known) {
                if (win.chapterBlocks[c] == null) {
                    val blocks = chapterLoader.invoke(c)
                    if (blocks != null && blocks.isNotEmpty()) win.chapterBlocks[c] = blocks
                }
            }
        }
        val firstCh = known.minOrNull() ?: center.chapter
        val lastCh = known.maxOrNull() ?: center.chapter
        val ahead = (1..prefetchChapters).map { lastCh + it }.filter { it < book.chapters.size }
        val behind = (1..prefetchChapters).map { firstCh - it }.filter { it >= 0 }
        val near = ((center.chapter - prefetchChapters)..(center.chapter + prefetchChapters))
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
        // 块/字锚点（恢复优先级高于存的页码）：首章排好后一次性推导。
        // 首次建窗用 DB 锚点；因高度/字号变化重建时用上一扇窗最后读到的锚点，
        // 避免把“进书时”的旧锚点当成当前位置重新应用。
        if (!anchorApplied && (windowAnchor.first > 0 || windowAnchor.second > 0)) {
            val pages = win.breaks[center.chapter]?.filter { it.isNotEmpty() }.orEmpty()
            if (pages.isNotEmpty()) {
                anchorApplied = true
                val p = pageForChar(pages, windowAnchor.first, windowAnchor.second)
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                if (p != center.page) win.jumpTo(GlobalPage(center.chapter, p))
                // 锚点已换算成页：解除“先不渲染”，一次性把 41 槽按新 center 重排。
                win.anchorPending = false
                win.fullRewrite()
            } else if (!hasPages(win.breaks, center.chapter)) {
                // 本章确实排不出页（空章/解析失败）：别让 loader 永远转，退回第 0 页。
                anchorApplied = true
                win.anchorPending = false
                win.fullRewrite()
            }
        }
    }
    return win
}
