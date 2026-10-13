package com.aurora.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.AuroraApplication
import com.aurora.music.data.audiobook.AudiobookBook
import com.aurora.music.ui.components.MarqueeText
import com.aurora.music.ui.components.TrackArtwork
import com.aurora.music.ui.components.formatTime
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5Slider
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.RepeatMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

private val iPodBlack = Brush.verticalGradient(
    0f to Color(0xFF3D434C),
    1f to Color(0xFF14161B),
)
private val iPodGray = Brush.verticalGradient(
    0f to Color(0xFFE8EAEE),
    1f to Color(0xFFC6CBD3),
)

// ---------------- 有声书整页播放器（iPod 壳 + 有声书走带键） ----------------
// 灰条 5 键：速度 / 介绍 / 音量 / 音乐共存 / 睡眠；
// 黑底走带 2 行 3 列：上一章 | 章节选择 | 下一章 / 后退N秒 | 播放暂停 | 前进N秒。
@Composable
fun Ios5AudiobookPlayerPage(
    state: PlayerUiState,
    book: AudiobookBook,
    allowMix: Boolean,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekChapter: (Int) -> Unit,
    onSeekBy: (Int) -> Unit,
    onSeek: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onGain: (Float) -> Unit,
    onToggleMix: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenQueue: () -> Unit,
    introActive: Boolean = false,
    onIntroClick: () -> Unit = {},
) {
    val song = state.current
    var bubble by remember { mutableStateOf<AbookBubble?>(null) }

    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val abookPrefs by container.settingsStore.audiobookPrefs.collectAsStateWithLifecycle(
        initialValue = com.aurora.music.data.SettingsStore.AudiobookPrefs(),
    )
    val step = abookPrefs.seekSec

    val remaining = (state.durationSec - state.positionSec.toInt()).coerceAtLeast(0)
    val chapterLabel = "第 ${state.currentIndex + 1} 章 / 共 ${book.chapterCount} 章"

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFFF4F4F6))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
    ) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val titleAreaH = 46.dp
        val topBase = statusTop + 87.dp
        val grayBase = 100.dp
        val bottomBase = 200.dp + navBottom
        val coverSide = minOf(maxWidth, maxHeight - topBase - grayBase - bottomBase).coerceAtLeast(0.dp)
        val leftover = (maxHeight - topBase - grayBase - coverSide - bottomBase).coerceAtLeast(0.dp)
        val topExtra = leftover * 0.4f
        val bottomExtra = leftover - topExtra
        val third = topExtra / 3f
        Column(Modifier.fillMaxSize()) {
            // 黑顶栏：返回 | 书名 | 队列 + 章节标题区
            Column(
                Modifier.fillMaxWidth()
                    .background(iPodBlack)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + third),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(36.dp + third).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IpodBarButton(onClick = onCollapse) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "返回", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        book.displayTitle.ifBlank { " " },
                        color = Color(0xFF9AA0AB),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Ios5Sans,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    )
                    IpodBarButton(onClick = onOpenQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, "队列", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(titleAreaH).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MarqueeText(
                        song.title.ifBlank { "未在播放" },
                        color = Color.White, fontSize = 19.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp + third))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
            }

            // 灰条：章节序号 + 进度 + 5 键
            Column(
                Modifier.fillMaxWidth().background(iPodGray).padding(horizontal = 12.dp).padding(top = 10.dp, bottom = 6.dp),
            ) {
                Text(
                    chapterLabel,
                    color = Color(0xFF6B7280), fontSize = 11.sp, lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(state.positionSec.toInt()),
                        color = Color(0xFF3E444D), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                        modifier = Modifier.width(44.dp),
                    )
                    Ios5Slider(
                        value = state.progress.coerceIn(0f, 1f),
                        onValueChange = onSeek,
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                    Text(
                        "-${formatTime(remaining)}",
                        color = Color(0xFF3E444D), fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
                        textAlign = TextAlign.End, modifier = Modifier.width(44.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    // 速度
                    Icon(
                        Icons.Filled.Speed, "速度",
                        tint = if (state.abookSpeed != 1f) Color(0xFF0A60D6) else Color(0xFF6B7280),
                        modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { bubble = AbookBubble.SPEED },
                            ).padding(9.dp),
                    )
                    // 介绍
                    Icon(
                        if (introActive) Icons.Filled.Stop else Icons.Outlined.HelpOutline,
                        if (introActive) "停止介绍" else "章节介绍",
                        tint = if (introActive) Color(0xFFD63A3A) else Color(0xFF6B7280),
                        modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onIntroClick,
                            ).padding(9.dp),
                    )
                    // 音量（本书增益）
                    Icon(
                        Icons.Filled.VolumeUp, "音量",
                        tint = if (state.abookGain != 1f) Color(0xFF0A60D6) else Color(0xFF6B7280),
                        modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { bubble = AbookBubble.GAIN },
                            ).padding(9.dp),
                    )
                    // 音乐共存
                    Icon(
                        Icons.Filled.MusicNote, "音乐共存",
                        tint = if (allowMix) Color(0xFF0A60D6) else Color(0xFF6B7280),
                        modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onToggleMix,
                            ).padding(9.dp),
                    )
                    // 睡眠定时
                    val sleepState by container.sleepTimer.timer.collectAsStateWithLifecycle()
                    Icon(
                        Icons.Filled.Bedtime, "睡眠定时",
                        tint = if (sleepState != null) Color(0xFF0A60D6) else Color(0xFF6B7280),
                        modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { bubble = AbookBubble.SLEEP },
                            ).padding(9.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF9AA0A8)))

            // 封面
            Box(
                Modifier.fillMaxWidth().height(coverSide),
                contentAlignment = Alignment.Center,
            ) {
                TrackArtwork(
                    song,
                    Modifier.size(coverSide),
                    corner = 0.dp,
                    fullQuality = true,
                )
            }

            // 黑底走带：两行三列
            Column(
                Modifier.fillMaxWidth().background(iPodBlack).padding(horizontal = 20.dp)
                    .padding(
                        top = 8.dp + bottomExtra / 2,
                        bottom = 18.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + bottomExtra / 2,
                    ),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AbookCellButton(label = "上一章", onClick = onPrevChapter) {
                        Icon(Icons.Filled.SkipPrevious, "上一章", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    AbookCellButton(label = "第 ${state.currentIndex + 1} 章", onClick = { bubble = AbookBubble.CHAPTERS }) {
                        Icon(Icons.Outlined.FormatListNumbered, "章节选择", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    AbookCellButton(label = "下一章", onClick = onNextChapter) {
                        Icon(Icons.Filled.SkipNext, "下一章", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    AbookCellButton(label = "后退${step}秒", onClick = { onSeekBy(-step) }) {
                        Text("−${step}s", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans)
                    }
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "暂停" else "播放", tint = Color.White,
                        modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onTogglePlay).padding(8.dp),
                    )
                    AbookCellButton(label = "前进${step}秒", onClick = { onSeekBy(step) }) {
                        Text("+${step}s", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Ios5Sans)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        when (state.repeat) {
                            RepeatMode.ONE -> Icons.Filled.RepeatOne
                            else -> Icons.Filled.Repeat
                        },
                        "循环",
                        tint = if (state.repeat == RepeatMode.OFF) Color(0xFF8E8E93) else Color(0xFF0A60D6),
                        modifier = Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onCycleRepeat,
                            ).padding(5.dp),
                    )
                    Spacer(Modifier.width(28.dp))
                    Icon(
                        Icons.Filled.Shuffle, "随机",
                        tint = if (state.shuffle) Color(0xFF0A60D6) else Color(0xFF8E8E93),
                        modifier = Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onToggleShuffle,
                            ).padding(5.dp),
                    )
                }
            }
        }
        // 气泡
        when (bubble) {
            AbookBubble.SPEED -> SpeedBubble(
                speed = state.abookSpeed,
                onSpeed = onSpeed,
                onDismiss = { bubble = null },
            )
            AbookBubble.GAIN -> GainBubble(
                gain = state.abookGain,
                onGain = onGain,
                onDismiss = { bubble = null },
            )
            AbookBubble.CHAPTERS -> ChapterBubble(
                book = book,
                currentIndex = state.currentIndex,
                onSelect = { onSeekChapter(it); bubble = null },
                onDismiss = { bubble = null },
            )
            AbookBubble.SLEEP -> SleepBubble(onDismiss = { bubble = null })
            null -> {}
        }
    }
}

private enum class AbookBubble { SPEED, GAIN, CHAPTERS, SLEEP }

@Composable
private fun AbookCellButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier.width(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        Spacer(Modifier.height(2.dp))
        Text(
            label, color = Color(0xFFB9BEC7), fontSize = 11.sp,
            fontWeight = FontWeight.Bold, fontFamily = Ios5Sans,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

// ---- 速度气泡：0.5..4x step 0.25（不变调） ----
@Composable
private fun SpeedBubble(
    speed: Float,
    onSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(speed) { mutableFloatStateOf(speed) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(14.dp)).background(Color.White)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "速度（不变调）", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("${"%.2f".format(draft)}x", color = Color(0xFF0A60D6), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Ios5SliderRow(
                title = "变速",
                valueLabel = "${"%.2f".format(draft)}x",
                value = draft,
                range = 0.5f..4f,
                // 0.5..4 step0.25 = 15 档 → steps=13
                steps = 13,
                onValueChange = { draft = (it / 0.25f).roundToInt() * 0.25f },
                onValueChangeFinished = { onSpeed(draft) },
            )
            Box(
                Modifier.fillMaxWidth()
                    .clickable(onClick = { onSpeed(1f) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("恢复 1.00x", color = Color(0xFF0A60D6), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---- 音量气泡：本书增益 50%..200% ----
@Composable
private fun GainBubble(
    gain: Float,
    onGain: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(gain) { mutableFloatStateOf(gain) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(14.dp)).background(Color.White)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "本书音量", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text("${(draft * 100).roundToInt()}%", color = Color(0xFF0A60D6), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Ios5SliderRow(
                title = "增益",
                valueLabel = "${(draft * 100).roundToInt()}%",
                value = draft,
                range = 0.5f..2f,
                // 0.5..2 step0.05 = 31 档 → steps=29
                steps = 29,
                onValueChange = { draft = (it / 0.05f).roundToInt() * 0.05f },
                onValueChangeFinished = { onGain(draft) },
            )
            Box(
                Modifier.fillMaxWidth()
                    .clickable(onClick = { onGain(1f) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("恢复 100%", color = Color(0xFF0A60D6), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---- 章节选择气泡 ----
@Composable
private fun ChapterBubble(
    book: AudiobookBook,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(14.dp)).background(Color.White)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "章节（${book.chapterCount}）", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (book.skippedCount > 0) {
                    Text(
                        "跳过 ${book.skippedCount} 章", color = Color(0xFF8E8E93), fontSize = 13.sp,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                itemsIndexed(book.chapters, key = { _, c -> c.path }) { i, c ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(onClick = { onSelect(i) })
                            .background(if (i == currentIndex) Color(0xFF0A60D6).copy(alpha = 0.12f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}",
                            color = if (i == currentIndex) Color(0xFF0A60D6) else Color(0xFF8E8E93),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(32.dp),
                        )
                        Text(
                            c.displayTitle,
                            color = if (i == currentIndex) Color(0xFF0A60D6) else Color.Black,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (c.durationSec > 0) {
                            Text(
                                formatTime(c.durationSec),
                                color = Color(0xFF8E8E93), fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
