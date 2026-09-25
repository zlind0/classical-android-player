package com.aurora.music.ui.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.aurora.music.MainActivity
import com.aurora.music.data.FloatingPrefs
import com.aurora.music.data.MusicRepository
import com.aurora.music.data.SettingsStore
import com.aurora.music.playback.PlaybackService
import com.aurora.music.viewmodel.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 后台方形悬浮窗管理：切后台弹、无曲目/无权限/本次已关闭则不弹，回前台藏。
 *
 * 状态直连 PlaybackService（独立 MediaController，Activity 销毁也不受影响）；
 * 大小/位置只在放手回调里写 DataStore，手势过程中只调 WindowManager。
 */
class FloatingWindowManager(
    private val app: Context,
    private val settings: SettingsStore,
    private val repository: MusicRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wm: WindowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density: Float get() = app.resources.displayMetrics.density

    private val _ui = MutableStateFlow(FloatingUiState())
    private val _sizeDp = MutableStateFlow(FloatingPrefs().sizeDp)
    private val _pinch = MutableStateFlow(true)

    @Volatile private var inBackground = false
    @Volatile private var dismissed = false
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var controller: MediaController? = null
    private var lastPrefs = FloatingPrefs()
    private var liveSizePx = 0
    private var lastSongId = ""

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncState()
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            syncState()
        }
    }

    init {
        scope.launch {
            settings.floatingPrefs.collect { prefs ->
                val prev = lastPrefs
                lastPrefs = prefs
                _pinch.value = prefs.pinchZoom
                _sizeDp.value = prefs.sizeDp
                if (view != null) {
                    // 设置页改了大小 → 实时跟随（只调窗口，不回写）
                    if (prefs.sizeDp != prev.sizeDp) applySize(dpToPx(prefs.sizeDp))
                    if (!prefs.enabled) hide()
                } else if (prefs.enabled && inBackground) {
                    maybeShow()
                }
            }
        }
    }

    fun onAppBackgrounded() {
        android.util.Log.d("FloatingWindow", "onAppBackgrounded")
        inBackground = true
        maybeShow()
    }

    fun onAppForegrounded() {
        android.util.Log.d("FloatingWindow", "onAppForegrounded")
        inBackground = false
        dismissed = false
        hide()
    }

    /** 左上叉：仅本次后台隐藏，不动设置开关（回前台后 dismissed 重置）。 */
    private fun close() {
        dismissed = true
        hide()
    }

    private fun maybeShow() {
        android.util.Log.d(
            "FloatingWindow",
            "maybeShow: bg=$inBackground visible=${view != null} dismissed=$dismissed",
        )
        if (!inBackground || view != null || dismissed) return
        scope.launch {
            val prefs = settings.floatingPrefs.first()
            lastPrefs = prefs
            android.util.Log.d(
                "FloatingWindow",
                "maybeShow: prefs enabled=${prefs.enabled} size=${prefs.sizeDp} overlay=${canDrawOverlays(app)}",
            )
            if (!inBackground || view != null || dismissed) return@launch
            if (!prefs.enabled || !canDrawOverlays(app)) return@launch
            val c = ensureController()
            android.util.Log.d(
                "FloatingWindow",
                "maybeShow: controller=${c != null} items=${c?.mediaItemCount} cur=${c?.currentMediaItem?.mediaId}",
            )
            if (!inBackground || view != null || dismissed) return@launch
            if (c == null) return@launch
            if (c.currentMediaItem == null && c.mediaItemCount == 0) return@launch
            show(prefs)
        }
    }

    private suspend fun ensureController(): MediaController? = withContext(Dispatchers.Main.immediate) {
        controller?.let { return@withContext it }
        try {
            withTimeout(4000) {
                suspendCancellableCoroutine { cont ->
                    val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                    val future = MediaController.Builder(app, token).buildAsync()
                    future.addListener({
                        try {
                            val c = future.get()
                            c.addListener(listener)
                            controller = c
                            syncState()
                            if (cont.isActive) cont.resume(c)
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }, ContextCompat.getMainExecutor(app))
                    cont.invokeOnCancellation { runCatching { future.cancel(true) } }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun syncState() {
        val c = controller ?: return
        val item = c.currentMediaItem
        // 后台时队列被清空 → 关窗
        if (item == null && view != null) {
            hide()
            return
        }
        val md = item?.mediaMetadata
        val repeat = when (c.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        val id = item?.mediaId.orEmpty()
        _ui.update {
            it.copy(
                title = md?.title?.toString().orEmpty(),
                artist = md?.artist?.toString().orEmpty(),
                artUri = md?.artworkUri?.toString().orEmpty(),
                isPlaying = c.isPlaying,
                shuffle = c.shuffleModeEnabled,
                repeat = repeat,
                hasTrack = item != null,
            )
        }
        if (id != lastSongId) {
            lastSongId = id
            refreshLike(id)
        }
    }

    private fun refreshLike(id: String) {
        if (id.isEmpty()) {
            _ui.update { it.copy(liked = false) }
            return
        }
        scope.launch(Dispatchers.IO) {
            val liked = runCatching { repository.likedSongIds(listOf(id)) }.getOrNull()?.contains(id) == true
            _ui.update { if (lastSongId == id) it.copy(liked = liked) else it }
        }
    }

    private fun show(prefs: FloatingPrefs) {
        if (view != null) return
        val sizePx = clampPx(dpToPx(prefs.sizeDp))
        liveSizePx = sizePx
        _sizeDp.value = sizePx / density
        _pinch.value = prefs.pinchZoom
        val (sw, sh) = screenSize()
        var x = prefs.posX
        var y = prefs.posY
        if (x == FloatingPrefs.UNPLACED || y == FloatingPrefs.UNPLACED) {
            x = (sw - sizePx - (16 * density).toInt()).coerceAtLeast(0)
            y = (sh / 2 - sizePx / 2).coerceAtLeast(0)
        }
        val lp = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            // 窗外触摸穿透 + 不抢按键焦点（返回键等留给底层应用），窗内按钮触摸不受影响
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceIn(0, (sw - sizePx).coerceAtLeast(0))
            this.y = y.coerceIn(0, (sh - sizePx).coerceAtLeast(0))
        }
        params = lp
        val o = OverlayLifecycleOwner()
        val v = ComposeView(app).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val st by _ui.collectAsState()
                val sz by _sizeDp.collectAsState()
                val pinch by _pinch.collectAsState()
                FloatingWindowContent(
                    state = st,
                    sizeDp = sz.dp,
                    pinchEnabled = pinch,
                    onClose = { close() },
                    onReturnToApp = { returnToApp() },
                    onDrag = { dx, dy -> moveBy(dx, dy) },
                    onDragEnd = { persistPosition() },
                    onZoom = { factor -> zoomBy(factor) },
                    onZoomEnd = { persistSize() },
                    onTogglePlay = { togglePlay() },
                    onNext = { controller?.seekToNextMediaItem() },
                    onPrevious = { prev() },
                    onToggleLike = { toggleLike() },
                    onToggleShuffle = { toggleShuffle() },
                    onCycleRepeat = { cycleRepeat() },
                )
            }
        }
        // 先挂 owner 再 create/add：自检失败直接放弃，避免 attach 后异步崩
        if (!ViewTreeLifecycle.set(v, o)) {
            runCatching { o.destroy() }
            params = null
            return
        }
        o.create()
        val added = runCatching { wm.addView(v, lp) }.isSuccess
        if (!added) {
            runCatching { o.destroy() }
            params = null
            return
        }
        o.resume()
        view = v
        owner = o
    }

    private fun hide() {
        val v = view ?: return
        view = null
        params = null
        runCatching { wm.removeView(v) }
        runCatching { owner?.destroy() }
        owner = null
    }

    // ---- 手势 live 更新（只调 WindowManager，不写设置） ----

    private fun moveBy(dxPx: Float, dyPx: Float) {
        val v = view ?: return
        val lp = params ?: return
        val (sw, sh) = screenSize()
        lp.x = (lp.x + dxPx).toInt().coerceIn(0, (sw - lp.width).coerceAtLeast(0))
        lp.y = (lp.y + dyPx).toInt().coerceIn(0, (sh - lp.height).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun zoomBy(factor: Float) {
        applySize((liveSizePx * factor).toInt())
    }

    /** 以中心为锚点改尺寸，钳制到 [200dp, 0.8×短边]。 */
    private fun applySize(px: Int) {
        val v = view ?: return
        val lp = params ?: return
        val next = clampPx(px)
        if (next == liveSizePx) return
        val cx = lp.x + liveSizePx / 2
        val cy = lp.y + liveSizePx / 2
        liveSizePx = next
        lp.width = next
        lp.height = next
        val (sw, sh) = screenSize()
        lp.x = (cx - next / 2).coerceIn(0, (sw - next).coerceAtLeast(0))
        lp.y = (cy - next / 2).coerceIn(0, (sh - next).coerceAtLeast(0))
        _sizeDp.value = next / density
        runCatching { wm.updateViewLayout(v, lp) }
    }

    // ---- 放手持久化（IO 线程写 DataStore） ----

    private fun persistPosition() {
        val lp = params ?: return
        val x = lp.x
        val y = lp.y
        android.util.Log.d("FloatingWindow", "persist pos=($x,$y)")
        scope.launch(Dispatchers.IO) { runCatching { settings.setFloatingPosition(x, y) } }
    }

    private fun persistSize() {
        val size = _sizeDp.value
        android.util.Log.d("FloatingWindow", "persist size=${size}dp")
        scope.launch(Dispatchers.IO) { runCatching { settings.setFloatingSize(size) } }
    }

    // ---- 播放控制 ----

    private fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    private fun prev() {
        val c = controller ?: return
        if (c.currentPosition > 4000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    private fun toggleShuffle() {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SHUFFLE, Bundle().apply { putInt("target", -1) }),
            Bundle.EMPTY,
        )
    }

    private fun cycleRepeat() {
        controller?.sendCustomCommand(SessionCommand(PlaybackService.CMD_REPEAT, Bundle.EMPTY), Bundle.EMPTY)
    }

    private fun toggleLike() {
        val id = lastSongId
        if (id.isEmpty()) return
        val now = !_ui.value.liked
        _ui.update { it.copy(liked = now) }
        scope.launch(Dispatchers.IO) { runCatching { repository.setStarred(id, now, "song") } }
    }

    private fun returnToApp() {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    // ---- 尺寸换算 ----

    private fun screenSize(): Pair<Int, Int> {
        val dm = app.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun maxSizePx(): Int {
        val (w, h) = screenSize()
        return (minOf(w, h) * FloatingPrefs.MAX_SCREEN_FRACTION).toInt()
    }

    private fun minSizePx(): Int = (FloatingPrefs.MIN_SIZE_DP * density).toInt()

    private fun clampPx(px: Int): Int = px.coerceIn(minSizePx(), maxSizePx().coerceAtLeast(minSizePx()))

    private fun dpToPx(dp: Float): Int = (dp * density).toInt()

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        fun create() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            runCatching { store.clear() }
        }
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
    }

    /**
     * 等价于 ViewTreeLifecycleOwner.set(view, owner)：ComposeView 靠这个驱动 composition。
     * lifecycle-runtime 是 KMP 分包产物，Kotlin 编译期只能看到 common 变体里的通用类，
     * Android 独有的 ViewTreeLifecycleOwner 引用不到（同包 LifecycleRegistry 等通用类正常），
     * 因此首选运行时反射调用官方方法（dex 里一定有，framework 也是这么调的），
     * 资源 tag 兜底（官方 set 内部就是这一套）。
     *
     * 返回 false = 按 framework 的 get 路径自检找不到 owner，调用方必须放弃挂窗，
     * 否则 attach 时在 traversal 线程异步崩（runCatching 包不住）。
     */
    private object ViewTreeLifecycle {
        private val lifecycleSet: java.lang.reflect.Method? = runCatching {
            Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
                .getMethod("set", View::class.java, LifecycleOwner::class.java)
        }.getOrNull()
        private val lifecycleGet: java.lang.reflect.Method? = runCatching {
            Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
                .getMethod("get", View::class.java)
        }.getOrNull()
        private val savedStateSet: java.lang.reflect.Method? = runCatching {
            Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
                .getMethod("set", View::class.java, SavedStateRegistryOwner::class.java)
        }.getOrNull()
        private val savedStateGet: java.lang.reflect.Method? = runCatching {
            Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
                .getMethod("get", View::class.java)
        }.getOrNull()
        private val vmStoreSet: java.lang.reflect.Method? = runCatching {
            Class.forName("androidx.lifecycle.ViewTreeViewModelStoreOwner")
                .getMethod("set", View::class.java, ViewModelStoreOwner::class.java)
        }.getOrNull()

        fun set(view: View, owner: OverlayLifecycleOwner): Boolean {
            runCatching { lifecycleSet?.invoke(null, view, owner) }
            runCatching { savedStateSet?.invoke(null, view, owner) }
            runCatching { vmStoreSet?.invoke(null, view, owner) }
            val id = runCatching {
                view.resources.getIdentifier("view_tree_lifecycle_owner", "id", view.context.packageName)
            }.getOrDefault(0)
            if (id != 0) runCatching { view.setTag(id, owner) }
            val okLifecycle = runCatching { lifecycleGet?.invoke(null, view) != null }.getOrDefault(false)
            val okSavedState = runCatching { savedStateGet?.invoke(null, view) != null }.getOrDefault(false)
            android.util.Log.d(
                "FloatingWindow",
                "attach owner: lifecycle=$okLifecycle(resId=$id) savedState=$okSavedState",
            )
            return okLifecycle && okSavedState
        }
    }
}
