package com.aurora.music.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** 列表滚动位置：与 Compose 的 LazyListState 对齐。 */
data class SavedScroll(val index: Int = 0, val offset: Int = 0)

/**
 * UI 现场（导航栈、当前 Tab、列表滚动位置、可展开列表的展开项、播放页是否展开）。
 *
 * 为什么不用 Activity 的 savedInstanceState：
 * 滑掉任务/被系统杀进程时不一定会有 onSaveInstanceState；这里统一落盘，
 * 冷启动时由 UI 主动读取，恢复成退出前的页面。
 */
data class SavedUiState(
    // 当前返回栈对应的路由（已把参数填回 URL），第一项通常是首页
    val navRoutes: List<String>? = emptyList(),
    val lastTab: String? = "",
    val playerExpanded: Boolean = false,
    val scroll: Map<String, SavedScroll>? = emptyMap(),
    /** 可展开列表（文件夹树/合并专辑等）按页面 key 保存展开项。 */
    val expanded: Map<String, List<String>>? = emptyMap(),
    /** 滑掉任务时正在播放：下次启动即使队列里 wasPlaying 被暂停事件写成 false 也强制续播。 */
    val resumeOnLaunch: Boolean = false,
    /**
     * 上次退出时仍在浏览远端内容。冷启动恢复远端页面时，
     * 在设备核对完成前把缓存内容画成灰色而不是直接隐藏，直到回到主屏幕。
     */
    val remoteBrowsing: Boolean? = false,
)

class UiStateStore(context: Context) {
    private val file = File(context.filesDir, "ui_state.json")
    private val gson = Gson()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 串行化落盘：避免旧快照的写线程晚于新快照落地，把新状态覆盖回旧值。 */
    private val ioLock = Any()

    private var state: SavedUiState = load()
    @Volatile private var dirty = false

    init {
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (dirty) flushNow()
            }
        }
    }

    private fun load(): SavedUiState = runCatching {
        if (!file.exists()) SavedUiState()
        else gson.fromJson(file.readText(), SavedUiState::class.java) ?: SavedUiState()
    }.getOrDefault(SavedUiState())

    fun navRoutes(): List<String> = synchronized(lock) { state.navRoutes.orEmpty() }

    fun saveNav(routes: List<String>) {
        synchronized(lock) {
            if (state.navRoutes == routes) return
            state = state.copy(navRoutes = routes)
            dirty = true
        }
        requestFlush()
    }

    fun lastTab(): String = synchronized(lock) { state.lastTab.orEmpty() }

    fun saveLastTab(route: String) {
        synchronized(lock) {
            if (state.lastTab == route) return
            state = state.copy(lastTab = route)
            dirty = true
        }
        requestFlush()
    }

    fun playerExpanded(): Boolean = synchronized(lock) { state.playerExpanded }

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

    fun scrollOf(key: String): SavedScroll = synchronized(lock) {
        state.scroll?.get(key) ?: SavedScroll()
    }

    fun saveScroll(key: String, index: Int, offset: Int) {
        synchronized(lock) {
            val current = state.scroll?.get(key)
            if (current?.index == index && current.offset == offset) return
            val next = (state.scroll ?: emptyMap()).toMutableMap()
            next[key] = SavedScroll(index, offset)
            state = state.copy(scroll = next)
            dirty = true
        }
    }

    fun expandedOf(key: String): Set<String> = synchronized(lock) {
        state.expanded?.get(key)?.toSet() ?: emptySet()
    }

    fun saveExpanded(key: String, values: Set<String>) {
        synchronized(lock) {
            val next = (state.expanded ?: emptyMap()).toMutableMap()
            next[key] = values.toList()
            state = state.copy(expanded = next)
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
            runCatching { file.writeText(gson.toJson(snapshot)) }
        }
    }
}
