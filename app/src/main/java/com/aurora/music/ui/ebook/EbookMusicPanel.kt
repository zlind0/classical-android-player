package com.aurora.music.ui.ebook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.UnifiedTtsPrefs
import com.aurora.music.model.Song
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Slider
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.RepeatMode
import com.aurora.titlemerge.MergeInput
import com.aurora.titlemerge.MergedRow
import com.aurora.titlemerge.mergeTracks
import kotlinx.coroutines.launch

// 音乐面板：阅读页气泡与投屏遥控页共用同一套“当前曲目 + 总列表”的样式。
// 与整页播放器同款材质：进度区银色渐变，走带键区深色渐变。

internal val readerMusicGrayBrush = Brush.verticalGradient(
    0f to Color(0xFFE8EAEE),
    1f to Color(0xFFC6CBD3),
)
internal val readerMusicBlackBrush = Brush.verticalGradient(
    0f to Color(0xFF3D434C),
    1f to Color(0xFF14161B),
)

// 列表行：GroupHeader = 合并后的大曲目（可收起），Track = 单曲或大曲目下的小曲目
internal sealed interface ReaderMusicRow {
    val key: String

    data class GroupHeader(
        override val key: String,
        val major: String,
        val firstIndex: Int,
        val memberIndices: List<Int>,
    ) : ReaderMusicRow

    data class Track(
        override val key: String,
        val index: Int,
        val title: String,
        val durationSec: Int,
        val inGroup: Boolean,
    ) : ReaderMusicRow
}

// 队列按连续同专辑切段，段内用与专辑视图相同的合并引擎收大曲目；
// 当前曲所在的大曲目默认展开，其余默认收起（用户手动点过的以 expanded 为准）。
internal fun buildReaderMusicRows(
    queue: List<Song>,
    currentIndex: Int,
    expanded: Map<String, Boolean>,
): List<ReaderMusicRow> {
    if (queue.isEmpty()) return emptyList()
    val out = ArrayList<ReaderMusicRow>(queue.size)
    var i = 0
    while (i < queue.size) {
        val albumId = queue[i].albumId
        if (albumId.isBlank()) {
            val s = queue[i]
            out += ReaderMusicRow.Track("s:$i", i, s.title, s.durationSec, false)
            i++
            continue
        }
        var j = i + 1
        while (j < queue.size && queue[j].albumId == albumId) j++
        val base = i
        val run = queue.subList(i, j)
        if (run.size < 2) {
            val s = run[0]
            out += ReaderMusicRow.Track("s:$base", base, s.title, s.durationSec, false)
        } else {
            mergeTracks(run.map { MergeInput(it.id, it.title) }).forEach { row ->
                when (row) {
                    is MergedRow.Single -> {
                        val s = run[row.index]
                        out += ReaderMusicRow.Track("s:${base + row.index}", base + row.index, s.title, s.durationSec, false)
                    }
                    is MergedRow.Group -> {
                        // key 里必须带组内第一首的全局序号：同一专辑里可能出现
                        // 两个同名大曲目（中间夹了单曲），只用 base+major 会撞 key
                        val gkey = "g:$base:${row.items.first().index}:${row.major}"
                        val members = row.items.map { base + it.index }
                        out += ReaderMusicRow.GroupHeader(
                            key = gkey,
                            major = row.major,
                            firstIndex = members.first(),
                            memberIndices = members,
                        )
                        val open = expanded[gkey] ?: (currentIndex in members)
                        if (open) {
                            row.items.forEach { item ->
                                val s = run[item.index]
                                out += ReaderMusicRow.Track(
                                    key = "t:${base + item.index}",
                                    index = base + item.index,
                                    title = item.minor.ifBlank { s.title },
                                    durationSec = s.durationSec,
                                    inGroup = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        i = j
    }
    return out
}

/**
 * 音乐面板 = 银色进度头 + 总列表，不含走带键。
 *
 * @param fillList true：列表吃掉剩余高度（阅读页气泡的固定高度卡片）；
 *                 false：列表随内容自适应高度，最多占满可用空间（投屏遥控页内嵌）。
 * @param focusThird 首次定位时把当前曲放在面板约 1/3 处（气泡）；否则直接滚到当前曲。
 * @param showList false：收起曲目列表，只留标题 / 艺术家 / 进度（遥控页的“列表”开关）。
 * @param onHeaderHeight 头部（标题 + 进度）实测高度，遥控页用来算翻页键能膨胀到多大。
 */
@Composable
internal fun EbookMusicPanel(
    state: PlayerUiState,
    onJump: (Int) -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    fillList: Boolean = false,
    focusThird: Boolean = false,
    showList: Boolean = true,
    onHeaderHeight: ((Dp) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    // 用户手动展开/收起：缺省 = 当前曲所在大曲目展开，其余收起
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val rows = remember(state.queue, state.currentIndex, expanded.toMap()) {
        buildReaderMusicRows(state.queue, state.currentIndex, expanded)
    }
    var panelHeightPx by remember { mutableStateOf(0) }
    var headerHeightPx by remember { mutableStateOf(0) }
    // 只在面板刚出现时定位一次；之后点歌/切歌/展开都不再动列表
    var positioned by remember { mutableStateOf(false) }

    LaunchedEffect(panelHeightPx, headerHeightPx, rows.isNotEmpty()) {
        if (positioned) return@LaunchedEffect
        if (panelHeightPx <= 0 || rows.isEmpty()) return@LaunchedEffect
        val target = rows.indexOfFirst { it is ReaderMusicRow.Track && it.index == state.currentIndex }
            .takeIf { it >= 0 }
            ?: rows.indexOfFirst { it is ReaderMusicRow.GroupHeader && state.currentIndex in it.memberIndices }
        if (target >= 0) {
            if (focusThird) {
                val rowPx = with(density) { 34.dp.toPx() }
                // 面板内 1/3 高度 - 头部高度 - 半个行高 = 列表内需要让目标行顶部停留的位置
                val targetTop = (panelHeightPx / 3f - headerHeightPx - rowPx / 2f).toInt()
                listState.scrollToItem(target, -targetTop)
            } else {
                listState.scrollToItem(target)
            }
        }
        positioned = true
    }

    val song = state.current
    val hasQueue = state.queue.isNotEmpty()
    val idxLabel = if (hasQueue) "${state.currentIndex + 1} / ${state.queue.size}" else ""
    val titleInk = Color(0xFF1A1D22)
    val subInk = Ios5Colors.TextSecondary

    Column(modifier.onSizeChanged { panelHeightPx = it.height }) {
        // ---- 顶部：银色渐变区（当前曲目 + 进度；循环在左、随机在右） ----
        Column(
            Modifier.fillMaxWidth().background(readerMusicGrayBrush)
                .onSizeChanged {
                    headerHeightPx = it.height
                    onHeaderHeight?.invoke(with(density) { it.height.toDp() })
                }
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title.ifBlank { if (hasQueue) "未在播放" else "播放列表为空" },
                        fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                        color = titleInk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" — ")
                    if (sub.isNotBlank()) {
                        Text(
                            sub, fontSize = 11.sp, lineHeight = 13.sp, color = subInk,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (idxLabel.isNotBlank()) {
                    Text(idxLabel, fontSize = 11.sp, color = subInk, maxLines = 1)
                }
            }
            Spacer(Modifier.height(2.dp))
            // 循环/随机始终在两侧：列表收起时布局也不变
            MusicProgressRow(
                state = state,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
            )
        }

        // ---- 中部：正在播放的总列表（紧凑行，按专辑视图合并大曲目） ----
        // 展开/收起用高度动画，遥控页的翻页键同时反向膨胀
        AnimatedVisibility(
            visible = showList,
            modifier = if (fillList) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
            enter = expandVertically(animationSpec = tween(260, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
            exit = shrinkVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)) + fadeOut(tween(140)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF9AA0A8)))
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text("还没有正在播放的曲目", fontSize = 13.sp, color = subInk)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = (
                            if (fillList) Modifier.fillMaxSize()
                            else Modifier.fillMaxWidth()
                            ),
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.key }) { rowIdx, row ->
                            when (row) {
                                is ReaderMusicRow.GroupHeader -> {
                                    val open = expanded[row.key] ?: (state.currentIndex in row.memberIndices)
                                    val containsCurrent = state.currentIndex in row.memberIndices
                                    Row(
                                        Modifier.fillMaxWidth().height(34.dp)
                                            .clickable { onJump(row.firstIndex) }
                                            .padding(start = 12.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            row.major,
                                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                            color = if (containsCurrent) Ios5Colors.IosBlueBright else titleInk,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            if (open) "收起" else "展开",
                                            tint = subInk,
                                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(16.dp))
                                                .clickable {
                                                    expanded[row.key] = !open
                                                }.padding(7.dp),
                                        )
                                    }
                                }
                                is ReaderMusicRow.Track -> {
                                    val current = row.index == state.currentIndex
                                    Row(
                                        Modifier.fillMaxWidth().height(32.dp)
                                            .clickable { onJump(row.index) }
                                            .padding(start = if (row.inGroup) 24.dp else 12.dp, end = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            row.title,
                                            fontSize = 13.sp,
                                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                            color = if (current) Ios5Colors.IosBlueBright else titleInk,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (current) {
                                            Text("♪", fontSize = 12.sp, color = Ios5Colors.IosBlueBright)
                                            Spacer(Modifier.width(5.dp))
                                        }
                                        Text(formatTime(row.durationSec), fontSize = 11.sp, color = subInk, maxLines = 1)
                                    }
                                }
                            }
                            if (rowIdx != rows.lastIndex) {
                                val inset = when (row) {
                                    is ReaderMusicRow.Track -> if (row.inGroup) 24.dp else 12.dp
                                    else -> 12.dp
                                }
                                Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(Color(0xFFE6E8EB)))
                            }
                        }
                    }
                }
            }
        }
    }
}

// 进度行：循环 / 随机分居两侧，展开和收起列表时都保持这一套布局。
@Composable
private fun MusicProgressRow(
    state: PlayerUiState,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (state.repeat) {
                RepeatMode.ONE -> Icons.Filled.RepeatOne
                else -> Icons.Filled.Repeat
            },
            "循环", tint = if (state.repeat == RepeatMode.OFF) Color(0xFF8E8E93) else Ios5Colors.IosBlueBright,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                .clickable(onClick = onCycleRepeat).padding(7.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Ios5Slider(
                value = state.progress.coerceIn(0f, 1f),
                onValueChange = onSeek,
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
            Row(Modifier.fillMaxWidth()) {
                Text(
                    formatTime(state.positionSec.toInt()), fontSize = 10.sp,
                    color = Color(0xFF3E444D), maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(state.durationSec), fontSize = 10.sp,
                    color = Color(0xFF3E444D), maxLines = 1,
                )
            }
        }
        Icon(
            Icons.Filled.Shuffle, "随机",
            tint = if (state.shuffle) Ios5Colors.IosBlueBright else Color(0xFF8E8E93),
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(17.dp))
                .clickable(onClick = onToggleShuffle).padding(7.dp),
        )
    }
}

// ---- 阅读页音乐气泡：面板 + 深色渐变走带键 + 站内音乐音量 ----

@Composable
internal fun ReaderMusicBubble(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val ttsPrefs by container.settingsStore.unifiedTts.collectAsStateWithLifecycle(initialValue = UnifiedTtsPrefs())
    val scope = rememberCoroutineScope()

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.7f)
                .shadow(18.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            EbookMusicPanel(
                state = state,
                onJump = onJump,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                fillList = true,
                focusThird = true,
            )

            // ---- 底部：深色渐变走带键区（上一首 / 播放暂停 / 下一首 + 站内音乐音量） ----
            Column(
                Modifier.fillMaxWidth().background(readerMusicBlackBrush)
                    .padding(top = 6.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious, "上一首", tint = Color.White,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(23.dp))
                            .clickable(onClick = onPrevious).padding(9.dp),
                    )
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "暂停" else "播放", tint = Color.White,
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(27.dp))
                            .clickable(onClick = onTogglePlay).padding(9.dp),
                    )
                    Icon(
                        Icons.Filled.SkipNext, "下一首", tint = Color.White,
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(23.dp))
                            .clickable(onClick = onNext).padding(9.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 与整页播放器同款音量条；数值就是选项里的“站内音乐音量”
                Ios5Slider(
                    value = ttsPrefs.ownMusicLevel.coerceIn(0.05f, 1f),
                    onValueChange = { v -> scope.launch { container.settingsStore.setOwnMusicLevel(v) } },
                    range = 0.05f..1f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
        }
    }
}
