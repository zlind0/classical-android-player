package com.aurora.music.data.audiobook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 有声书会话态（App 作用域）：VM 写，PlaybackService 读（增益/混音）。
class AudiobookSession {
    private val _activeBookId = MutableStateFlow<String?>(null)
    val activeBookId: StateFlow<String?> = _activeBookId.asStateFlow()

    private val _gain = MutableStateFlow(1f)
    val gain: StateFlow<Float> = _gain.asStateFlow()

    // 当前章节文件路径（VM 写；service 在暂停态位移后用它直写进度）
    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    fun setActive(bookId: String?, gain: Float = 1f) {
        _activeBookId.value = bookId
        _gain.value = gain
        if (bookId == null) _currentPath.value = ""
    }

    fun setGain(gain: Float) {
        _gain.value = gain
    }

    fun setCurrentPath(path: String) {
        _currentPath.value = path
    }
}
