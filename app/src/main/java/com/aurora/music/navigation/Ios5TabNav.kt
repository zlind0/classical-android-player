package com.aurora.music.navigation

import android.app.Application
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import com.aurora.music.data.SavedStackEntry
import com.aurora.music.data.UiStateStore
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 解析后的路由结构（纯 Kotlin，不依赖 android.net.Uri，便于 JVM 单测与存储归一化共用）。
 */
data class ParsedIos5Route(
    val base: String,
    val pathSegments: List<String> = emptyList(),
    val query: Map<String, String> = emptyMap(),
)

/**
 * 百分号解码（保留字面 `+`，不走 URLDecoder 以免把曲名/文件夹里的 `+` 变成空格）。
 */
fun percentDecode(input: String): String {
    if ('%' !in input) return input
    val bytes = ByteArrayOutputStream(input.length)
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        if (ch == '%' && i + 2 < input.length) {
            val hi = input[i + 1].digitToIntOrNull(16)
            val lo = input[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                bytes.write((hi shl 4) or lo)
                i += 3
                continue
            }
        }
        val utf8 = ch.toString().toByteArray(Charsets.UTF_8)
        bytes.write(utf8, 0, utf8.size)
        i++
    }
    return bytes.toString(Charsets.UTF_8.name())
}

fun parseIos5Route(route: String): ParsedIos5Route {
    val qIdx = route.indexOf('?')
    val pathPart = if (qIdx >= 0) route.substring(0, qIdx) else route
    val queryPart = if (qIdx >= 0) route.substring(qIdx + 1) else ""
    val query = if (queryPart.isEmpty()) {
        emptyMap()
    } else {
        val map = LinkedHashMap<String, String>()
        for (pair in queryPart.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = percentDecode(if (eq >= 0) pair.substring(0, eq) else pair)
            val v = percentDecode(if (eq >= 0) pair.substring(eq + 1) else "")
            map[k] = v
        }
        map
    }
    return when {
        pathPart.startsWith("ios5_detail/") -> {
            val segs = pathPart.split('/', limit = 4).map(::percentDecode)
            ParsedIos5Route(base = "ios5_detail", pathSegments = segs, query = query)
        }
        pathPart.startsWith("tag_edit/") -> {
            val segs = pathPart.split('/', limit = 2).map(::percentDecode)
            ParsedIos5Route(base = "tag_edit", pathSegments = segs, query = query)
        }
        else -> ParsedIos5Route(base = pathPart, pathSegments = listOf(pathPart), query = query)
    }
}

/** 根据路由推算初始标题（未组合或首帧渲染前用于上一级返回按钮标题兜底）。 */
fun defaultTitleForRoute(route: String): String {
    ios5Tabs.firstOrNull { it.route == route }?.let { return it.label }
    val parsed = parseIos5Route(route)
    return when (parsed.base) {
        Ios5Routes.PLAYLISTS -> "歌单"
        Ios5Routes.MORE_SONGS -> "歌曲"
        Ios5Routes.MORE_ALBUMS -> "专辑"
        Ios5Routes.MORE_GENRES -> "风格"
        Ios5Routes.MORE_COMPOSERS -> "作曲家"
        Ios5Routes.MORE_FOLDERS -> "文件夹"
        Ios5Routes.EBOOK_SEARCH -> "搜索电子书"
        "ios5_search" -> "搜索"
        "ios5_video_browse" -> parsed.query["title"].orEmpty().ifBlank { "视频" }
        "ios5_remote_group" -> parsed.query["title"].orEmpty().ifBlank { parsed.query["key"].orEmpty() }
        "ios5_folder" -> parsed.query["title"].orEmpty().ifBlank { "文件夹" }
        "ios5_detail" -> {
            val title = parsed.pathSegments.getOrNull(3).orEmpty()
            val id = parsed.pathSegments.getOrNull(2).orEmpty()
            val kind = parsed.pathSegments.getOrNull(1).orEmpty()
            title.ifBlank { if (kind == "liked") "喜欢的歌曲" else id }
        }
        "ios5_ebook_lib" -> {
            val title = parsed.query["title"].orEmpty()
            val shelf = parsed.query["shelf"] == "true"
            title.ifBlank { if (shelf) "默认书架" else "电子书" }
        }
        "ios5_remote_ebook_lib" -> parsed.query["title"].orEmpty().ifBlank { "电子书" }
        "ebook_export" -> "转有声书"
        Ios5Routes.SETTINGS_APPEARANCE -> "外观"
        Ios5Routes.SETTINGS_PLAYBACK -> "播放"
        Ios5Routes.SETTINGS_EQ -> "均衡器"
        Ios5Routes.SETTINGS_STORAGE -> "存储"
        Ios5Routes.SETTINGS_GESTURES -> "手势"
        Ios5Routes.SETTINGS_INTEGRATIONS -> "集成"
        Ios5Routes.SETTINGS_AIRPLAY_DIAGNOSTICS -> "AirPlay 设置与诊断"
        Ios5Routes.SETTINGS_RELAY -> "公网中继"
        Ios5Routes.SETTINGS_ABOUT -> "关于"
        Ios5Routes.SETTINGS_VISUALIZER -> "可视化"
        Ios5Routes.SETTINGS_SONIC -> "音色分析"
        Ios5Routes.SETTINGS_PERMISSIONS -> "权限"
        Ios5Routes.SETTINGS_BACKUP -> "备份"
        Ios5Routes.SETTINGS_SOURCES -> "音乐来源"
        Ios5Routes.SETTINGS_EBOOK_SOURCES -> "电子书来源"
        Ios5Routes.SETTINGS_EBOOK -> "电子书设置"
        Ios5Routes.SETTINGS_AUDIOBOOK_SOURCES -> "有声书来源"
        Ios5Routes.SETTINGS_AUDIOBOOK -> "有声书设置"
        Ios5Routes.SETTINGS_AUDIOBOOK_SUBTITLES -> "离线字幕"
        Ios5Routes.SETTINGS_MULTIMODAL -> "多模态大模型"
        Ios5Routes.SETTINGS_INTRO -> "歌曲介绍"
        Ios5Routes.SETTINGS_INTRO_MODEL -> "选择模型"
        Ios5Routes.SETTINGS_INTRO_PROMPT -> "系统提示词"
        Ios5Routes.SETTINGS_INTRO_VOICE -> "语音设置"
        Ios5Routes.SETTINGS_INTRO_LOG -> "解说记录"
        Ios5Routes.HISTORY -> "收听历史"
        Ios5Routes.STATS -> "收听统计"
        Ios5Routes.DUPLICATES -> "重复歌曲"
        "smart_edit" -> if (parsed.query["id"].isNullOrBlank()) "新建智能歌单" else "编辑智能歌单"
        "tag_edit" -> "编辑标签"
        else -> ""
    }
}

/** 当前入栈页面的导航作用域：提供所属 Tab、条目 ID、上一级标题及标题回写通道。 */
data class NavEntryScope(
    val tabRoute: String,
    val entryId: String,
    val previousTitle: String?,
    val onTitleChanged: (String) -> Unit,
)

val LocalNavEntryScope = compositionLocalOf<NavEntryScope?> { null }

/** 为每个栈条目持有独立的 ViewModelStore，退栈时精确清理，不残留 ViewModel 状态。 */
class Ios5TabNavViewModel : ViewModel() {
    private val entryStores = mutableMapOf<String, ViewModelStore>()

    fun ownerFor(entryId: String, app: Application): ViewModelStoreOwner {
        val store = entryStores.getOrPut(entryId) { ViewModelStore() }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore get() = store
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory
                get() = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
            override val defaultViewModelCreationExtras: CreationExtras
                get() = MutableCreationExtras().apply {
                    set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
                }
        }
    }

    fun clearEntry(entryId: String) {
        entryStores.remove(entryId)?.clear()
    }

    override fun onCleared() {
        entryStores.values.forEach { it.clear() }
        entryStores.clear()
    }
}

/** 页面切换方向：决定进退场动画（push 右进左移、pop 左回右移、切 Tab 淡入淡出）。 */
enum class NavDirection { FORWARD, BACKWARD, SWITCH }

/**
 * iOS5 分 Tab 独立导航器：
 * - 每个 Tab 各自维护完整的入栈历史，切 Tab 不丢栈，冷启动直接从 UiStateStore 同步还原；
 * - 退栈（返回 / 再点当前 Tab 回根页）等退场动画播完后再销毁条目的 ViewModelStore、
 *   SaveableState 与持久化数据，避免动画中途把正在退场的页面清空。
 */
class Ios5TabNavigator(
    private val uiState: UiStateStore,
    private val navVm: Ios5TabNavViewModel,
    private val saveableStateHolder: SaveableStateHolder,
) {
    var activeTab by mutableStateOf(uiState.activeTab())
        private set

    /** 最近一次导航的方向。只在转场开始时读取一次，故意不做成 Compose 状态。 */
    var direction = NavDirection.SWITCH
        private set

    /** 已退栈、但还要播完退场动画的条目；由 UI 在动画结束后调用 [cleanupPending]。 */
    var pendingCleanup by mutableStateOf<List<String>>(emptyList())
        private set

    /** 正在播退场动画的条目快照：退栈后仍要能按 id 取到它来渲染。 */
    private var exitingEntries by mutableStateOf<Map<String, SavedStackEntry>>(emptyMap())

    /** 退场条目的上一级标题快照：退栈后返回按钮上的文字不能再变。 */
    private var exitingPreviousTitles by mutableStateOf<Map<String, String?>>(emptyMap())

    var stacks by mutableStateOf(uiState.tabStacks())
        private set

    /** 按 id 取条目，包含刚退栈、正在播退场动画的条目（切 Tab 淡出时也要能找到旧页）。 */
    fun entryById(entryId: String): SavedStackEntry? {
        for (stack in stacks.values) {
            stack.firstOrNull { it.id == entryId }?.let { return it }
        }
        return exitingEntries[entryId]
    }

    /**
     * 某个条目自己那一级的上一页标题（不是当前栈顶的上一页）：
     * 入栈后旧页面退场时，它的返回按钮仍应显示它原来的上一级。
     */
    fun previousTitleFor(entryId: String): String? {
        for (stack in stacks.values) {
            val idx = stack.indexOfFirst { it.id == entryId }
            if (idx >= 0) {
                return if (idx == 0) null else stack[idx - 1].title.takeIf { it.isNotBlank() }
            }
        }
        return exitingPreviousTitles[entryId]
    }

    val currentStack: List<SavedStackEntry>
        get() = stacks[activeTab].orEmpty()

    val currentEntry: SavedStackEntry
        get() = currentStack.last()

    val currentRoute: String
        get() = currentEntry.route

    val canPop: Boolean
        get() = currentStack.size > 1

    fun selectTab(tabRoute: String) {
        if (tabRoute !in ios5TabRoutes) return
        if (tabRoute == activeTab) {
            popToRoot(tabRoute)
            return
        }
        direction = NavDirection.SWITCH
        activeTab = tabRoute
        uiState.saveNavigation(activeTab, stacks)
    }

    fun push(route: String, initialTitle: String = "") {
        if (route in ios5TabRoutes) {
            selectTab(route)
            return
        }
        val stack = currentStack
        if (stack.lastOrNull()?.route == route) return
        val entry = SavedStackEntry(
            id = UUID.randomUUID().toString(),
            route = route,
            title = initialTitle.ifBlank { defaultTitleForRoute(route) },
        )
        direction = NavDirection.FORWARD
        val next = stacks.toMutableMap()
        next[activeTab] = stack + entry
        stacks = next
        uiState.saveNavigation(activeTab, stacks)
    }

    fun pop(): Boolean {
        val stack = currentStack
        if (stack.size <= 1) return false
        val popped = stack.last()
        direction = NavDirection.BACKWARD
        val next = stacks.toMutableMap()
        next[activeTab] = stack.dropLast(1)
        stacks = next
        uiState.saveNavigation(activeTab, stacks)
        exitingEntries = exitingEntries + (popped.id to popped)
        exitingPreviousTitles = exitingPreviousTitles +
            (popped.id to stack.getOrNull(stack.lastIndex - 1)?.title?.takeIf { it.isNotBlank() })
        pendingCleanup = pendingCleanup + popped.id
        return true
    }

    fun popToRoot(tabRoute: String = activeTab) {
        val stack = stacks[tabRoute].orEmpty()
        if (stack.size <= 1) return
        direction = NavDirection.BACKWARD
        val dropped = stack.drop(1)
        val next = stacks.toMutableMap()
        next[tabRoute] = listOf(stack.first())
        stacks = next
        uiState.saveNavigation(tabRoute, stacks)
        exitingEntries = exitingEntries + (stack.last().id to stack.last())
        exitingPreviousTitles = exitingPreviousTitles +
            (stack.last().id to stack.getOrNull(stack.lastIndex - 1)?.title?.takeIf { it.isNotBlank() })
        pendingCleanup = pendingCleanup + dropped.map { it.id }
    }

    fun updateTitle(tabRoute: String, entryId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val stack = stacks[tabRoute] ?: return
        val idx = stack.indexOfFirst { it.id == entryId }
        if (idx < 0 || stack[idx].title == clean) return
        val updated = stack.toMutableList()
        updated[idx] = updated[idx].copy(title = clean)
        val next = stacks.toMutableMap()
        next[tabRoute] = updated
        stacks = next
        uiState.updateEntryTitle(tabRoute, entryId, clean)
    }

    /** 退场动画结束后释放已退栈条目（ViewModelStore / SaveableState）。 */
    fun cleanupPending() {
        val ids = pendingCleanup
        if (ids.isEmpty()) return
        pendingCleanup = emptyList()
        exitingEntries = exitingEntries - ids.toSet()
        exitingPreviousTitles = exitingPreviousTitles - ids.toSet()
        ids.forEach { cleanupEntry(it) }
    }

    private fun cleanupEntry(entryId: String) {
        navVm.clearEntry(entryId)
        saveableStateHolder.removeState(entryId)
    }
}
