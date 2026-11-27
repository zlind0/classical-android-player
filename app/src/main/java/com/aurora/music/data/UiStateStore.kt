package com.aurora.music.data

import android.content.Context
import com.aurora.music.navigation.Ios5Routes
import com.aurora.music.navigation.Ios5Tab
import com.aurora.music.navigation.defaultTitleForRoute
import com.aurora.music.navigation.ios5Tabs
import com.aurora.music.navigation.parseIos5Route
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** 列表滚动位置：与 Compose 的 LazyListState 对齐。 */
data class SavedScroll(val index: Int = 0, val offset: Int = 0)

/**
 * 单个入栈界面的完整持久化快照（随退栈一起销毁，避免已退栈界面的滚动/展开等状态泄漏）。
 */
data class SavedStackEntry(
    val id: String = "",
    val route: String = "",
    val title: String = "",
    val scroll: Map<String, SavedScroll>? = emptyMap(),
    /** 可展开列表（合并专辑/模型分组/面板等）按页面内 key 保存展开项。 */
    val expanded: Map<String, List<String>>? = emptyMap(),
    /** 页面内轻量 UI 状态（搜索词、筛选、当前子 Tab 等）。 */
    val extras: Map<String, String>? = emptyMap(),
)

/**
 * UI 现场（各 Tab 的完整界面栈、当前 Tab、播放页是否展开、阅读器覆盖层等）。
 *
 * 为什么不用 Activity 的 savedInstanceState：
 * 滑掉任务/被系统杀进程时不一定会有 onSaveInstanceState；这里统一落盘，
 * 冷启动时由 UI 主动读取，恢复成退出前的页面。
 */
data class SavedUiState(
    val activeTab: String? = "",
    val tabStacks: Map<String, List<SavedStackEntry>>? = emptyMap(),
    val playerExpanded: Boolean = false,
    /** 滑掉任务时正在播放：下次启动即使队列里 wasPlaying 被暂停事件写成 false 也强制续播。 */
    val resumeOnLaunch: Boolean = false,
    /** 上次退出时正在阅读的书（阅读器是覆盖层，不在 Tab 返回栈里，单独保存）。 */
    val readerPath: String? = "",
    /**
     * 上次退出时仍在浏览远端内容。冷启动恢复远端页面时，
     * 在设备核对完成前把缓存内容画成灰色而不是直接隐藏，直到回到主屏幕。
     */
    val remoteBrowsing: Boolean? = false,
    // ---- 旧版平铺字段，仅用于首次启动迁移读取，归一化后置空不再写盘 ----
    val navRoutes: List<String>? = null,
    val lastTab: String? = null,
    val scroll: Map<String, SavedScroll>? = null,
    val expanded: Map<String, List<String>>? = null,
)

private fun newEntry(route: String, title: String = ""): SavedStackEntry =
    SavedStackEntry(
        id = UUID.randomUUID().toString(),
        route = route,
        title = title.ifBlank { defaultTitleForRoute(route) },
    )

private fun extractLegacyReaderPath(routes: List<String>): String? =
    routes.lastOrNull { it.startsWith("ebook_reader") }
        ?.let { parseIos5Route(it).query["epath"] }
        ?.takeIf { it.isNotBlank() }

/**
 * 把磁盘读到的状态归一化为「每个 Tab 都有完整界面栈」的新格式，并清理旧版全局平铺状态。
 */
internal fun normalizeUiState(
    raw: SavedUiState,
    tabs: List<Ios5Tab> = ios5Tabs,
): Pair<SavedUiState, Boolean> {
    val tabRoutes = tabs.map { it.route }.toSet()
    val defaultTab = tabs.firstOrNull()?.route ?: Ios5Routes.HOME
    var migrated = raw.navRoutes != null || raw.lastTab != null || raw.scroll != null || raw.expanded != null
    var readerPath = raw.readerPath.orEmpty()

    val rawStacks = raw.tabStacks
    if (!rawStacks.isNullOrEmpty()) {
        val nextStacks = LinkedHashMap<String, List<SavedStackEntry>>()
        for (tab in tabs) {
            val existing = rawStacks[tab.route].orEmpty()
            val legacyReader = extractLegacyReaderPath(existing.map { it.route })
            if (readerPath.isBlank() && legacyReader != null) {
                readerPath = legacyReader
                migrated = true
            }
            val cleaned = existing
                .filterNot { it.route.isBlank() || it.route.startsWith("ebook_reader") }
                .map { entry ->
                    val fixedId = entry.id.ifBlank {
                        migrated = true
                        UUID.randomUUID().toString()
                    }
                    val fixedTitle = entry.title.ifBlank {
                        migrated = true
                        defaultTitleForRoute(entry.route)
                    }
                    entry.copy(
                        id = fixedId,
                        title = fixedTitle,
                        scroll = entry.scroll ?: emptyMap(),
                        expanded = entry.expanded ?: emptyMap(),
                        extras = entry.extras ?: emptyMap(),
                    )
                }
            val withRoot = when {
                cleaned.isEmpty() -> {
                    migrated = true
                    listOf(newEntry(tab.route, tab.label))
                }
                cleaned.first().route != tab.route -> {
                    migrated = true
                    listOf(newEntry(tab.route, tab.label)) + cleaned.filterNot { it.route == tab.route }
                }
                else -> cleaned
            }
            nextStacks[tab.route] = withRoot
        }
        val active = raw.activeTab?.takeIf { it in tabRoutes } ?: defaultTab
        if (active != raw.activeTab || nextStacks.size != rawStacks.size) migrated = true
        val normalized = raw.copy(
            activeTab = active,
            tabStacks = nextStacks,
            readerPath = readerPath,
            navRoutes = null,
            lastTab = null,
            scroll = null,
            expanded = null,
        )
        return normalized to migrated
    }

    // 旧版平铺 navRoutes 迁移：把旧栈归并到对应的活跃 Tab，其余 Tab 各自初始化根页
    val legacyRoutes = raw.navRoutes.orEmpty()
    if (readerPath.isBlank()) {
        extractLegacyReaderPath(legacyRoutes)?.let { readerPath = it }
    }
    val filteredLegacy = legacyRoutes.filterNot { it.isBlank() || it.startsWith("ebook_reader") }
    val active = filteredLegacy.lastOrNull { it in tabRoutes }
        ?: raw.lastTab?.takeIf { it in tabRoutes }
        ?: raw.activeTab?.takeIf { it in tabRoutes }
        ?: defaultTab

    val nextStacks = LinkedHashMap<String, List<SavedStackEntry>>()
    for (tab in tabs) {
        if (tab.route == active && filteredLegacy.isNotEmpty()) {
            val tabIdx = filteredLegacy.lastIndexOf(tab.route)
            val tail = if (tabIdx >= 0) filteredLegacy.drop(tabIdx + 1) else filteredLegacy.filterNot { it in tabRoutes }
            val entries = mutableListOf(newEntry(tab.route, tab.label))
            tail.forEach { r ->
                if (r !in tabRoutes) entries += newEntry(r)
            }
            nextStacks[tab.route] = entries
        } else {
            nextStacks[tab.route] = listOf(newEntry(tab.route, tab.label))
        }
    }
    val normalized = raw.copy(
        activeTab = active,
        tabStacks = nextStacks,
        readerPath = readerPath,
        navRoutes = null,
        lastTab = null,
        scroll = null,
        expanded = null,
    )
    return normalized to true
}

class UiStateStore internal constructor(
    private val file: File,
    autoFlush: Boolean = true,
) {
    constructor(context: Context) : this(File(context.filesDir, "ui_state.json"), autoFlush = true)

    private val gson = Gson()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 串行化落盘：避免旧快照的写线程晚于新快照落地，把新状态覆盖回旧值。 */
    private val ioLock = Any()

    private var state: SavedUiState
    @Volatile private var dirty = false

    init {
        val (normalized, changed) = normalizeUiState(loadRaw())
        state = normalized
        dirty = changed
        if (autoFlush) {
            scope.launch {
                while (isActive) {
                    delay(1_000)
                    if (dirty) flushNow()
                }
            }
        }
    }

    private fun loadRaw(): SavedUiState = runCatching {
        if (!file.exists()) SavedUiState()
        else gson.fromJson(file.readText(), SavedUiState::class.java) ?: SavedUiState()
    }.getOrDefault(SavedUiState())

    fun activeTab(): String = synchronized(lock) {
        state.activeTab.orEmpty().ifBlank { Ios5Routes.HOME }
    }

    fun tabStacks(): Map<String, List<SavedStackEntry>> = synchronized(lock) {
        state.tabStacks.orEmpty().mapValues { (_, list) -> list.toList() }
    }

    fun saveNavigation(activeTab: String, tabStacks: Map<String, List<SavedStackEntry>>) {
        synchronized(lock) {
            if (state.activeTab == activeTab && state.tabStacks == tabStacks) return
            state = state.copy(
                activeTab = activeTab,
                tabStacks = tabStacks.mapValues { (_, list) -> list.toList() },
            )
            dirty = true
        }
        requestFlush()
    }

    fun updateEntryTitle(tabRoute: String, entryId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val changed = updateEntry(tabRoute, entryId) { entry ->
            if (entry.title == clean) null else entry.copy(title = clean)
        }
        if (changed) requestFlush()
    }

    fun scrollOf(tabRoute: String, entryId: String, key: String): SavedScroll = synchronized(lock) {
        findEntry(tabRoute, entryId)?.scroll?.get(key) ?: SavedScroll()
    }

    fun saveScroll(tabRoute: String, entryId: String, key: String, index: Int, offset: Int) {
        updateEntry(tabRoute, entryId) { entry ->
            val current = entry.scroll?.get(key)
            if (current?.index == index && current.offset == offset) return@updateEntry null
            val next = (entry.scroll ?: emptyMap()).toMutableMap()
            next[key] = SavedScroll(index, offset)
            entry.copy(scroll = next)
        }
    }

    fun expandedOf(tabRoute: String, entryId: String, key: String): Set<String> = synchronized(lock) {
        findEntry(tabRoute, entryId)?.expanded?.get(key)?.toSet() ?: emptySet()
    }

    fun saveExpanded(tabRoute: String, entryId: String, key: String, values: Set<String>) {
        val changed = updateEntry(tabRoute, entryId) { entry ->
            val current = entry.expanded?.get(key)?.toSet() ?: emptySet()
            if (current == values) return@updateEntry null
            val next = (entry.expanded ?: emptyMap()).toMutableMap()
            if (values.isEmpty()) next.remove(key) else next[key] = values.toList()
            entry.copy(expanded = next)
        }
        if (changed) requestFlush()
    }

    fun extraOf(tabRoute: String, entryId: String, key: String): String? = synchronized(lock) {
        findEntry(tabRoute, entryId)?.extras?.get(key)
    }

    fun saveExtra(tabRoute: String, entryId: String, key: String, value: String?) {
        val changed = updateEntry(tabRoute, entryId) { entry ->
            val current = entry.extras?.get(key)
            if (current == value) return@updateEntry null
            val next = (entry.extras ?: emptyMap()).toMutableMap()
            if (value == null) next.remove(key) else next[key] = value
            entry.copy(extras = next)
        }
        if (changed) requestFlush()
    }

    private fun findEntry(tabRoute: String, entryId: String): SavedStackEntry? =
        state.tabStacks?.get(tabRoute)?.firstOrNull { it.id == entryId }

    /** 只更新仍在当前栈里的条目；已退栈条目的延迟回调直接丢弃，绝不写回。 */
    private inline fun updateEntry(
        tabRoute: String,
        entryId: String,
        transform: (SavedStackEntry) -> SavedStackEntry?,
    ): Boolean = synchronized(lock) {
        val allStacks = state.tabStacks ?: return@synchronized false
        val stack = allStacks[tabRoute] ?: return@synchronized false
        val idx = stack.indexOfFirst { it.id == entryId }
        if (idx < 0) return@synchronized false
        val nextEntry = transform(stack[idx]) ?: return@synchronized false
        val nextStack = stack.toMutableList()
        nextStack[idx] = nextEntry
        val nextStacks = allStacks.toMutableMap()
        nextStacks[tabRoute] = nextStack
        state = state.copy(tabStacks = nextStacks)
        dirty = true
        true
    }

    fun playerExpanded(): Boolean = synchronized(lock) { state.playerExpanded }

    fun readerPath(): String = synchronized(lock) { state.readerPath.orEmpty() }

    fun saveReaderPath(path: String) {
        synchronized(lock) {
            if (state.readerPath.orEmpty() == path) return
            state = state.copy(readerPath = path)
            dirty = true
        }
        requestFlush()
    }

    fun remoteBrowsing(): Boolean = synchronized(lock) { state.remoteBrowsing == true }

    fun saveRemoteBrowsing(active: Boolean) {
        synchronized(lock) {
            if ((state.remoteBrowsing == true) == active) return
            state = state.copy(remoteBrowsing = active)
            dirty = true
        }
        requestFlush()
    }

    fun savePlayerExpanded(expanded: Boolean) {
        synchronized(lock) {
            if (state.playerExpanded == expanded) return
            state = state.copy(playerExpanded = expanded)
            dirty = true
        }
        requestFlush()
    }

    fun requestFlush() {
        scope.launch { flushNow() }
    }

    /** 滑掉任务且正在播放：打一个一次性标记，下次冷启动恢复播放。 */
    fun markResumeOnLaunch() {
        synchronized(lock) {
            if (state.resumeOnLaunch) return
            state = state.copy(resumeOnLaunch = true)
            dirty = true
        }
        requestFlush()
    }

    /** 读取并清掉一次续播标记（只生效一次，之后手动暂停不会被重新拉起）。 */
    fun consumeResumeOnLaunch(): Boolean {
        val value = synchronized(lock) {
            val v = state.resumeOnLaunch
            if (v) {
                state = state.copy(resumeOnLaunch = false)
                dirty = true
            }
            v
        }
        if (value) requestFlush()
        return value
    }

    fun flushNow() {
        synchronized(ioLock) {
            val snapshot = synchronized(lock) {
                if (!dirty && file.exists()) return
                dirty = false
                state
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(gson.toJson(snapshot))
            }
        }
    }
}
