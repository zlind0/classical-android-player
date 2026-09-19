package com.aurora.music.ui.screens.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aurora.music.R
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import kotlin.math.roundToInt

@Composable
fun QueueScreen(
    queue: List<Song>,
    currentIndex: Int,
    isPlaying: Boolean,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onSaveAsPlaylist: (String) -> Unit,
    onClose: () -> Unit,
) {
    val current = queue.getOrNull(currentIndex)
    val startIdx = (currentIndex + 1).coerceAtLeast(0)
    val upcoming = (startIdx until queue.size).toList()
    val played = (currentIndex - 1 downTo 0).toList()
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var showHistory by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val strTitle = stringResource(R.string.queue_title)
    val strNowPlaying = stringResource(R.string.queue_now_playing)
    val strPreviously = stringResource(R.string.queue_previously, played.size)
    val strNothingUpNext = stringResource(R.string.queue_nothing_up_next)
    val strUpNext = if (upcoming.isEmpty()) strNothingUpNext else stringResource(R.string.queue_up_next, upcoming.size)
    val strSaveAs = stringResource(R.string.queue_save_as)
    val strClear = stringResource(R.string.queue_clear)

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = strTitle, onBack = onClose)
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            item {
                Ios5Group(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (queue.isNotEmpty()) {
                        Ios5ActionRow(title = strSaveAs, onClick = { showSaveDialog = true })
                    } else {
                        Ios5StaticHint(strSaveAs)
                    }
                    Ios5CellDivider()
                    if (upcoming.isNotEmpty()) {
                        Ios5ActionRow(title = strClear, danger = true, onClick = onClear)
                    } else {
                        Ios5StaticHint(strClear)
                    }
                }
            }
            if (current != null) {
                item { Ios5SectionTitle(strNowPlaying) }
                item {
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(current.artworkUrl, current.accent, Modifier.size(52.dp), corner = 8.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(current.title, color = Ios5Colors.IosBlue, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(current.artist, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (isPlaying) {
                                Text("♪", color = Ios5Colors.IosBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (played.isNotEmpty()) {
                item { Ios5SectionTitle(strPreviously) }
                item {
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showHistory = !showHistory }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                strPreviously,
                                color = Ios5Colors.TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (showHistory) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                                tint = Ios5Colors.TextSecondary, modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (showHistory) {
                    ios5Rows(played, key = { queue[it].id }) { _, i ->
                        QueueTrackRow(
                            song = queue[i], index = null, rowHeight = rowHeight,
                            dimmed = true, onClick = { onJump(i) }, onRemove = null, dragHandle = null,
                        )
                    }
                }
            }
            item { Ios5SectionTitle(strUpNext) }
            if (upcoming.isNotEmpty()) {
                ios5Rows(upcoming, key = { queue[it].id }) { vi, i ->
                    val dragging = i == dragIndex
                    QueueTrackRow(
                        song = queue[i],
                        index = vi + 1,
                        rowHeight = rowHeight,
                        dragging = dragging,
                        dragOffset = if (dragging) dragOffset else 0f,
                        onClick = { onJump(i) },
                        onRemove = { onRemove(i) },
                        // key on i/startIdx so gesture re-captures fresh indices when current advances or rows shift
                        dragHandle = Modifier.pointerInput(queue.size, i, startIdx) {
                            detectDragGestures(
                                onDragStart = { dragIndex = i; dragOffset = 0f },
                                onDragEnd = {
                                    val target = (dragIndex + (dragOffset / rowPx).roundToInt()).coerceIn(startIdx, queue.size - 1)
                                    if (target != dragIndex && dragIndex >= 0) onMove(dragIndex, target)
                                    dragIndex = -1; dragOffset = 0f
                                },
                                onDragCancel = { dragIndex = -1; dragOffset = 0f },
                                onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                            )
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showSaveDialog) {
        SaveQueueDialog(
            onSave = { name -> onSaveAsPlaylist(name); showSaveDialog = false },
            onDismiss = { showSaveDialog = false },
        )
    }
}

@Composable
private fun Ios5StaticHint(text: String) {
    Text(
        text, color = Ios5Colors.TextSecondary.copy(alpha = 0.5f), fontSize = 16.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

// index null = history row no number/drag, dragHandle null = no reorder handle
@Composable
private fun QueueTrackRow(
    song: Song,
    index: Int?,
    rowHeight: androidx.compose.ui.unit.Dp,
    dragging: Boolean = false,
    dragOffset: Float = 0f,
    dimmed: Boolean = false,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
    dragHandle: Modifier?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                if (dragging) { shadowElevation = 16f; scaleX = 1.02f; scaleY = 1.02f }
            }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text("$index", color = Ios5Colors.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(26.dp))
            Spacer(Modifier.width(8.dp))
        }
        Artwork(song.artworkUrl, song.accent, Modifier.size(44.dp), corner = 6.dp)
        Spacer(Modifier.width(10.dp))
        val alpha = if (dimmed) 0.6f else 1f
        Column(Modifier.weight(1f)) {
            Text(
                song.title, color = Ios5Colors.TextPrimary.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(song.artist, color = Ios5Colors.TextSecondary.copy(alpha = alpha), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(song.durationSec), color = Ios5Colors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
        if (onRemove != null) {
            Icon(
                Icons.Filled.Close, stringResource(R.string.queue_remove),
                tint = Ios5Colors.TextSecondary,
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onRemove).padding(7.dp),
            )
        }
        if (dragHandle != null) {
            Icon(
                Icons.Filled.DragHandle, stringResource(R.string.queue_reorder),
                tint = Ios5Colors.TextSecondary,
                modifier = Modifier.size(34.dp).padding(6.dp).then(dragHandle),
            )
        }
    }
}

@Composable
private fun SaveQueueDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.queue_save_title), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.queue_playlist_name)) }, singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.queue_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.queue_cancel)) } },
    )
}
