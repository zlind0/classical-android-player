package com.aurora.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.model.Song
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.titlemerge.MergeInput
import com.aurora.titlemerge.MergedRow
import com.aurora.titlemerge.mergeTracks

/**
 * 正在播放列表的公共行模型：按连续同专辑切段，段内用与专辑视图相同的合并引擎
 * 收成大曲目（可展开/收起）。阅读页音乐气泡、投屏遥控页、播放页队列共用这一份。
 */
internal sealed interface MusicQueueRow {
    val key: String

    data class GroupHeader(
        override val key: String,
        val major: String,
        val firstIndex: Int,
        val memberIndices: List<Int>,
    ) : MusicQueueRow

    data class Track(
        override val key: String,
        val index: Int,
        val title: String,
        val durationSec: Int,
        val inGroup: Boolean,
    ) : MusicQueueRow
}

internal fun buildMusicQueueRows(
    queue: List<Song>,
    currentIndex: Int,
    expanded: Map<String, Boolean>,
): List<MusicQueueRow> {
    if (queue.isEmpty()) return emptyList()
    val out = ArrayList<MusicQueueRow>(queue.size)
    var i = 0
    while (i < queue.size) {
        val albumId = queue[i].albumId
        if (albumId.isBlank()) {
            val s = queue[i]
            out += MusicQueueRow.Track("s:$i", i, s.title, s.durationSec, false)
            i++
            continue
        }
        var j = i + 1
        while (j < queue.size && queue[j].albumId == albumId) j++
        val base = i
        val run = queue.subList(i, j)
        if (run.size < 2) {
            val s = run[0]
            out += MusicQueueRow.Track("s:$base", base, s.title, s.durationSec, false)
        } else {
            mergeTracks(run.map { MergeInput(it.id, it.title) }).forEach { row ->
                when (row) {
                    is MergedRow.Single -> {
                        val s = run[row.index]
                        out += MusicQueueRow.Track(
                            "s:${base + row.index}",
                            base + row.index,
                            s.title,
                            s.durationSec,
                            false,
                        )
                    }
                    is MergedRow.Group -> {
                        // key 里必须带组内第一首的全局序号：同一专辑里可能出现
                        // 两个同名大曲目（中间夹了单曲），只用 base+major 会撞 key
                        val gkey = "g:$base:${row.items.first().index}:${row.major}"
                        val members = row.items.map { base + it.index }
                        out += MusicQueueRow.GroupHeader(
                            key = gkey,
                            major = row.major,
                            firstIndex = members.first(),
                            memberIndices = members,
                        )
                        val open = expanded[gkey] ?: (currentIndex in members)
                        if (open) {
                            row.items.forEach { item ->
                                val s = run[item.index]
                                out += MusicQueueRow.Track(
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
 * 只读的“当前播放清单”列表。
 *
 * @param fillList true：吃掉父级剩余高度（气泡/队列页）；false：随内容自适应。
 * @param focusThird 首次定位时把当前曲放在面板约 1/3 处（阅读页气泡）。
 * @param panelHeightPx / headerHeightPx 气泡定位用；队列页传 0 即可。
 */
@Composable
internal fun MusicQueueList(
    queue: List<Song>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillList: Boolean = false,
    focusThird: Boolean = false,
    panelHeightPx: Int = 0,
    headerHeightPx: Int = 0,
    showTopDivider: Boolean = true,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val rows = remember(queue, currentIndex, expanded.toMap()) {
        buildMusicQueueRows(queue, currentIndex, expanded)
    }
    var positioned by remember { mutableStateOf(false) }

    LaunchedEffect(panelHeightPx, headerHeightPx, rows.isNotEmpty(), fillList) {
        if (positioned) return@LaunchedEffect
        if (rows.isEmpty()) return@LaunchedEffect
        val target = rows.indexOfFirst { it is MusicQueueRow.Track && it.index == currentIndex }
            .takeIf { it >= 0 }
            ?: rows.indexOfFirst { it is MusicQueueRow.GroupHeader && currentIndex in it.memberIndices }
        if (target >= 0) {
            if (focusThird && panelHeightPx > 0) {
                val rowPx = with(density) { 34.dp.toPx() }
                val targetTop = (panelHeightPx / 3f - headerHeightPx - rowPx / 2f).toInt()
                listState.scrollToItem(target, -targetTop)
            } else {
                listState.scrollToItem(target)
            }
        }
        positioned = true
    }

    val titleInk = Color(0xFF1A1D22)
    val subInk = Ios5Colors.TextSecondary
    Column(modifier) {
        if (showTopDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF9AA0A8)))
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text("还没有正在播放的曲目", fontSize = 13.sp, color = subInk)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = if (fillList) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(rows, key = { _, row -> row.key }) { rowIdx, row ->
                    when (row) {
                        is MusicQueueRow.GroupHeader -> {
                            val open = expanded[row.key] ?: (currentIndex in row.memberIndices)
                            val containsCurrent = currentIndex in row.memberIndices
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
                        is MusicQueueRow.Track -> {
                            val current = row.index == currentIndex
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
                            is MusicQueueRow.Track -> if (row.inGroup) 24.dp else 12.dp
                            else -> 12.dp
                        }
                        Box(
                            Modifier.fillMaxWidth().padding(start = inset).height(1.dp)
                                .background(Color(0xFFE6E8EB)),
                        )
                    }
                }
            }
        }
    }
}
