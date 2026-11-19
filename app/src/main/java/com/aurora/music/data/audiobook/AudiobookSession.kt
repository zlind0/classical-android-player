package com.aurora.music.data.audiobook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 有声书会话态（App 作用域）：VM 写，PlaybackService 读（增益/混音/暂停态落进度）。
class AudiobookSession {
    private val _activeBookId = MutableStateFlow<String?>(null)
    val activeBookId: StateFlow<String?> = _activeBookId.asStateFlow()

    private val _gain = MutableStateFlow(1f)
    val gain: StateFlow<Float> = _gain.asStateFlow()

    // 当前章节文件路径 + 文件内起点（m4b 多章同文件靠 startMs 区分）
    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _currentStartMs = MutableStateFlow(0L)
    val currentStartMs: StateFlow<Long> = _currentStartMs.asStateFlow()

    fun setActive(bookId: String?, gain: Float = 1f) {
        _activeBookId.value = bookId
        _gain.value = gain
        if (bookId == null) {
            _currentPath.value = ""
            _currentStartMs.value = 0L
        }
    }

    fun setGain(gain: Float) {
        _gain.value = gain
    }

    fun setCurrentChapter(path: String, startMs: Long = 0L) {
        _currentPath.value = path
        _currentStartMs.value = startMs.coerceAtLeast(0L)
    }

    fun setCurrentPath(path: String) = setCurrentChapter(path, 0L)
}
