package com.aurora.music.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import com.aurora.music.AuroraApplication
import com.aurora.music.navigation.LocalNavEntryScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 带落盘记忆的 LazyListState（绑定当前栈条目）。
 *
 * - 同一页面只要还在 Tab 栈里，切 Tab、锁屏重建、进程被杀冷启动都会从上次位置继续；
 * - 一旦该页面退栈，其 `entryId` 被销毁，再重新点进同一页面时从头开始；
 * - 250ms 内的连续滚动只写最后一次，避免每帧都改落盘文件。
 */
@Composable
fun rememberPersistentLazyListState(key: String): LazyListState {
    val entry = LocalNavEntryScope.current ?: return rememberLazyListState()
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.uiState
    val saved = remember(entry.tabRoute, entry.entryId, key) {
        store.scrollOf(entry.tabRoute, entry.entryId, key)
    }
    val state = rememberSaveable(entry.entryId, key, saver = LazyListState.Saver) {
        LazyListState(saved.index, saved.offset)
    }
    LaunchedEffect(state, entry.tabRoute, entry.entryId, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                delay(250)
                store.saveScroll(entry.tabRoute, entry.entryId, key, index, offset)
            }
    }
    return state
}

/** 绑定当前栈条目的分组展开集合（退栈自动清理）。 */
@Composable
fun rememberEntryExpandedState(key: String): MutableState<Set<String>> {
    val entry = LocalNavEntryScope.current ?: return remember { mutableStateOf(emptySet()) }
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.uiState
    val state = remember(entry.tabRoute, entry.entryId, key) {
        mutableStateOf(store.expandedOf(entry.tabRoute, entry.entryId, key))
    }
    LaunchedEffect(state.value, entry.tabRoute, entry.entryId, key) {
        store.saveExpanded(entry.tabRoute, entry.entryId, key, state.value)
    }
    return state
}

@Composable
private fun <T> rememberEntryScalarState(
    key: String,
    default: T,
    decode: (String) -> T,
    encode: (T) -> String,
): MutableState<T> {
    val entry = LocalNavEntryScope.current ?: return remember { mutableStateOf(default) }
    val store = (LocalContext.current.applicationContext as AuroraApplication).container.uiState
    val state = remember(entry.tabRoute, entry.entryId, key) {
        val raw = store.extraOf(entry.tabRoute, entry.entryId, key)
        mutableStateOf(if (raw == null) default else decode(raw))
    }
    LaunchedEffect(state.value, entry.tabRoute, entry.entryId, key) {
        val v = state.value
        store.saveExtra(entry.tabRoute, entry.entryId, key, if (v == default) null else encode(v))
    }
    return state
}

/** 绑定当前栈条目的字符串状态（搜索词、筛选分类等，退栈自动清理）。 */
@Composable
fun rememberEntryStringState(key: String, default: String = ""): MutableState<String> =
    rememberEntryScalarState(key, default, decode = { it }, encode = { it })

/** 绑定当前栈条目的布尔状态（搜索栏展开、编辑模式等，退栈自动清理）。 */
@Composable
fun rememberEntryBooleanState(key: String, default: Boolean = false): MutableState<Boolean> =
    rememberEntryScalarState(
        key,
        default,
        decode = { it.toBooleanStrictOrNull() ?: default },
        encode = { it.toString() },
    )

/** 绑定当前栈条目的整型状态（子 Tab、随机种子等，退栈自动清理）。 */
@Composable
fun rememberEntryIntState(key: String, default: Int = 0): MutableState<Int> =
    rememberEntryScalarState(
        key,
        default,
        decode = { it.toIntOrNull() ?: default },
        encode = { it.toString() },
    )

/** 绑定当前栈条目的长整型状态（打开的历史批次时间戳等，退栈自动清理）。 */
@Composable
fun rememberEntryLongState(key: String, default: Long = 0L): MutableState<Long> =
    rememberEntryScalarState(
        key,
        default,
        decode = { it.toLongOrNull() ?: default },
        encode = { it.toString() },
    )
