package com.aurora.music.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.aurora.music.AuroraApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 带落盘记忆的 LazyListState。
 *
 * - 同一页面重进（包括阅读器返回、进程被杀后冷启动）从上次位置继续；
 * - [key] 必须稳定且能区分页面，例如 "tab:home"、"ebook_lib:<dir>"。
 * - 100ms 内的连续滚动只写最后一次，避免每帧都改落盘文件。
 */
@Composable
fun rememberPersistentLazyListState(key: String): LazyListState {
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.uiState
    val saved = remember(key) { store.scrollOf(key) }
    val state = rememberSaveable(key, saver = LazyListState.Saver) {
        LazyListState(saved.index, saved.offset)
    }
    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                delay(250)
                store.saveScroll(key, index, offset)
            }
    }
    return state
}
