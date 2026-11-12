package com.aurora.music.data.video

import android.content.Context
import com.aurora.music.data.cast.RemoteLibraryManager
import com.aurora.music.data.cast.RemoteVideoWire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 竖屏双击放大时画面最多裁到的比例（宽:高）。
 *
 * 比例越“宽”（1:1）裁掉的左右画面越少、上下留黑越多；越“窄”（9:21）越接近铺满竖屏。
 * 竖屏视频比所选比例更窄时不会为了填满比例去裁上下，只会按视频自身比例显示。
 */
enum class VideoFillAspect(val label: String, val ratio: Float) {
    Square("1:1", 1f),
    ThreeFour("3:4", 3f / 4f),
    NineSixteen("9:16", 9f / 16f),
    NineTwentyOne("9:21", 9f / 21f),
}

/** 一次短视频播放现场：服务器 + 目录 + 当前条目列表。 */
data class VideoFeedState(
    val deviceId: String = "",
    val rootKey: String = "",
    val dir: String = "",
    val title: String = "",
    val items: List<RemoteVideoWire> = emptyList(),
    val index: Int = 0,
    /** 由“随机播放”按钮打开：条目顺序已由服务端打乱。 */
    val shuffled: Boolean = false,
) {
    /**
     * Pager 的页数。随机模式在目录里无限循环：页数不设上限，条目按批次取模，
     * 所以哪怕目录里只有一个视频，也能一直往下滑，每一页都是同一个视频的不同随机片段。
     */
    val pageCount: Int get() = if (shuffled) Int.MAX_VALUE else items.size

    /** 第 [page] 页对应的视频；随机模式下按批次循环取模。 */
    fun itemAt(page: Int): RemoteVideoWire? {
        if (items.isEmpty()) return null
        if (!shuffled) return items.getOrNull(page)
        val size = items.size
        return items[((page % size) + size) % size]
    }

    val current: RemoteVideoWire? get() = itemAt(index)
}

/**
 * 短视频播放现场与播放偏好。
 *
 * 现场放在 [com.aurora.music.data.AppContainer] 里，不跟着 Compose 页面走，
 * 这样旋转屏幕、临时进书浏览再回来，播放列表和当前条目都不会丢；
 * 循环/随机/画面缩放等开关落盘，下次进来沿用上次的设置。
 */
class VideoFeedController(
    context: Context,
    private val manager: RemoteLibraryManager,
) {
    private val prefs = context.getSharedPreferences("video_feed", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<VideoFeedState?>(null)
    val state: StateFlow<VideoFeedState?> = _state.asStateFlow()

    private val _loop = MutableStateFlow(prefs.getBoolean("loop", true))
    val loop: StateFlow<Boolean> = _loop.asStateFlow()

    private val _shuffle = MutableStateFlow(prefs.getBoolean("shuffle", false))
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    /** 双击切换：true = 居中裁切铺满，false = 完整画面。 */
    private val _fill = MutableStateFlow(prefs.getBoolean("fill", false))
    val fill: StateFlow<Boolean> = _fill.asStateFlow()

    /** 竖屏双击放大时的最大裁切比例，落盘沿用。 */
    private val _fillAspect = MutableStateFlow(
        VideoFillAspect.entries.firstOrNull { it.name == prefs.getString("fill_aspect", null) }
            ?: VideoFillAspect.NineTwentyOne,
    )
    val fillAspect: StateFlow<VideoFillAspect> = _fillAspect.asStateFlow()

    fun setLoop(value: Boolean) {
        _loop.value = value
        prefs.edit().putBoolean("loop", value).apply()
    }

    fun setShuffle(value: Boolean) {
        _shuffle.value = value
        prefs.edit().putBoolean("shuffle", value).apply()
    }

    fun setFill(value: Boolean) {
        _fill.value = value
        prefs.edit().putBoolean("fill", value).apply()
    }

    fun setFillAspect(value: VideoFillAspect) {
        if (_fillAspect.value == value) return
        _fillAspect.value = value
        prefs.edit().putString("fill_aspect", value.name).apply()
    }

    /** 从文件浏览器里点某个视频：播放这个目录的视频，从它开始。 */
    fun open(
        deviceId: String,
        rootKey: String,
        dir: String,
        title: String,
        items: List<RemoteVideoWire>,
        index: Int,
    ): Boolean {
        if (items.isEmpty()) return false
        _state.value = VideoFeedState(
            deviceId = deviceId,
            rootKey = rootKey,
            dir = dir,
            title = title,
            items = items,
            index = index.coerceIn(0, items.size - 1),
        )
        return true
    }

    /**
     * 目录右侧的随机按钮：向服务端要一批随机视频，按 TikTok 的方式下滑切换。
     * 返回 null 表示服务端不可达（调用方提示）。
     */
    suspend fun openShuffled(
        deviceId: String,
        rootKey: String,
        dir: String,
        title: String,
        count: Int = 20,
    ): Boolean {
        val items = manager.fetchRandomVideos(deviceId, rootKey, dir, count).orEmpty()
        if (items.isEmpty()) return false
        _state.value = VideoFeedState(
            deviceId = deviceId,
            rootKey = rootKey,
            dir = dir,
            title = title,
            items = items,
            index = 0,
            shuffled = true,
        )
        return true
    }

    fun setIndex(index: Int) {
        val current = _state.value ?: return
        val safe = if (current.shuffled) {
            index.coerceAtLeast(0)
        } else {
            index.coerceIn(0, (current.items.size - 1).coerceAtLeast(0))
        }
        if (safe == current.index) return
        _state.value = current.copy(index = safe)
    }

    fun next(): Boolean {
        val current = _state.value ?: return false
        if (current.shuffled) {
            // 随机模式无限循环：一直往后走，条目按批次取模。
            _state.value = current.copy(index = current.index + 1)
            return true
        }
        if (current.index < current.items.size - 1) {
            _state.value = current.copy(index = current.index + 1)
            return true
        }
        // 整批播完：随机模式下再向服务端要一批新的
        return false
    }

    fun previous(): Boolean {
        val current = _state.value ?: return false
        if (current.index > 0) {
            _state.value = current.copy(index = current.index - 1)
            return true
        }
        return false
    }

    /** 追加一批新的随机视频（当前批次播完之后）。 */
    fun append(items: List<RemoteVideoWire>) {
        val current = _state.value ?: return
        if (items.isEmpty()) return
        _state.value = current.copy(items = current.items + items)
    }

    fun close() {
        _state.value = null
    }
}
