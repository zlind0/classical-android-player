package com.aurora.music.data

import android.content.Context
import com.aurora.music.playback.LibraryScanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 后台扫描编排（App 作用域）：
 * - 扫描不再绑在设置界面的 composable scope 上，切出去、切后台都继续跑；
 * - 同一时间只跑一个 root 的深扫，新任务顶掉旧任务（旧任务的断点已落盘，可续）；
 * - 长扫由 [LibraryScanService] 前台保活（dataSync），杀进程则靠断点文件下次启动续扫；
 * - 删库时顺手清掉该 root 的断点和任务。
 */
class LibraryScanManager(
    private val context: Context,
    private val store: MusicRootsStore,
    private val scanner: RootScanner,
    private val checkpoints: ScanCheckpoints,
    private val appScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var job: Job? = null
    private var activeRootId: Long = -1L

    private val _scanningIds = MutableStateFlow<Set<Long>>(emptySet())
    /** 正在后台扫的 rootId 集合（UI 用来展示/禁用重复入口）。 */
    val scanningIds: StateFlow<Set<Long>> = _scanningIds.asStateFlow()

    /**  fire-and-forget：调用方（UI）直接调，不用管协程作用域。 */
    fun startScan(root: MusicRoot) {
        appScope.launch {
            val already = mutex.withLock {
                if (activeRootId == root.id && job?.isActive == true) {
                    true
                } else {
                    job?.cancel()
                    job = null
                    activeRootId = root.id
                    _scanningIds.value = _scanningIds.value + root.id
                    false
                }
            }
            if (already) return@launch
            LibraryScanService.start(context)
            val j = appScope.launch {
                try {
                    scanner.scan(root) { store.progress.value = it }
                } finally {
                    var stopService = false
                    mutex.withLock {
                        if (activeRootId == root.id) {
                            activeRootId = -1L
                            job = null
                        }
                        _scanningIds.value = _scanningIds.value - root.id
                        // 集合空了才撤保活：串行/顶掉场景下另一个任务还在跑
                        stopService = _scanningIds.value.isEmpty()
                    }
                    if (stopService) LibraryScanService.stop(context)
                    store.refreshCounts()
                }
            }
            mutex.withLock { job = j }
        }
    }

    fun cancel(rootId: Long) {
        appScope.launch {
            mutex.withLock {
                if (activeRootId == rootId) {
                    job?.cancel()
                    job = null
                    activeRootId = -1L
                    _scanningIds.value = _scanningIds.value - rootId
                }
            }
        }
    }

    fun cancelAll() {
        appScope.launch {
            mutex.withLock {
                job?.cancel()
                job = null
                activeRootId = -1L
                _scanningIds.value = emptySet()
            }
            LibraryScanService.stop(context)
        }
    }

    /** 删库：停任务 + 清断点，调用方之后再调 removeRoot。 */
    suspend fun dropRoot(rootId: Long) {
        mutex.withLock {
            if (activeRootId == rootId) {
                job?.cancel()
                job = null
                activeRootId = -1L
            }
            _scanningIds.value = _scanningIds.value - rootId
        }
        checkpoints.clear(rootId)
    }

    /**
     * 启动恢复：有残留断点且 root 还在，就接着扫（已暂存的不重扫，由 RootScanner 合并）。
     * 在 MusicRootsStore.loadFromDb 之后调用，串行续扫。
     */
    suspend fun resumePending() {
        val pending = checkpoints.pendingRootIds()
        if (pending.isEmpty()) return
        val roots = store.roots.value.associateBy { it.id }
        for (rootId in pending.sorted()) {
            val root = roots[rootId]
            if (root == null) {
                // 库已被删但断点残留，清掉
                checkpoints.clear(rootId)
            } else {
                mutex.withLock {
                    if (job?.isActive == true) return
                    activeRootId = root.id
                    _scanningIds.value = _scanningIds.value + root.id
                }
                LibraryScanService.start(context)
                try {
                    scanner.scan(root) { store.progress.value = it }
                } catch (e: Exception) {
                    // 取消/异常都保留断点等下次，前台服务照常撤
                } finally {
                    mutex.withLock {
                        if (activeRootId == root.id) {
                            activeRootId = -1L
                            job = null
                        }
                        _scanningIds.value = _scanningIds.value - root.id
                    }
                    store.refreshCounts()
                }
            }
        }
        if (_scanningIds.value.isEmpty()) LibraryScanService.stop(context)
    }
}
