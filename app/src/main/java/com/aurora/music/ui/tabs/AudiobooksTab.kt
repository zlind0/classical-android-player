package com.aurora.music.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.util.accentFor

// 有声书 Tab：顶部是在听（按最后播放倒序），下面是书库（一本书一行）。
// 点一行 = 整本续播（直接替换音乐队列），不进详情选曲。
@Composable
fun AudiobooksTab(
    onPlayBook: (bookId: String) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val books by container.audiobookStore.books.collectAsStateWithLifecycle()
    val recents by container.audiobookStore.recents.collectAsStateWithLifecycle()
    val roots by container.audiobookStore.roots.collectAsStateWithLifecycle()
    val enabledRoots = roots.filter { it.enabled }
    // 在听里的书只在上面出现一次，避免同 LazyColumn key 重复闪退
    val rest = remember(books, recents) {
        val recentIds = recents.map { it.id }.toSet()
        books.filterNot { it.id in recentIds }
    }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "有声书")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (recents.isNotEmpty()) {
                item { Ios5SectionTitle("在听") }
                ios5Rows(recents, key = { it.id }) { _, b ->
                    AudiobookRow(
                        title = b.displayTitle,
                        subtitle = bookSubtitle(b.chapterCount, b.currentIndex, b.progressPct, b.skippedCount, played = b.lastPlayedAt > 0),
                        coverPath = b.coverPath,
                        accentKey = b.id,
                        onClick = { onPlayBook(b.id) },
                    )
                }
            }
            item { Ios5SectionTitle("书库（${books.size}）") }
            if (books.isEmpty()) {
                item { Ios5Empty("还没有有声书\n请到 设置 → 有声书 → 有声书来源 添加目录并扫描") }
            } else {
                ios5Rows(rest, key = { it.id }) { _, b ->
                    AudiobookRow(
                        title = b.displayTitle,
                        subtitle = bookSubtitle(b.chapterCount, b.currentIndex, b.progressPct, b.skippedCount, played = b.lastPlayedAt > 0),
                        coverPath = b.coverPath,
                        accentKey = b.id,
                        onClick = { onPlayBook(b.id) },
                    )
                }
            }
            if (recents.isEmpty() && books.isEmpty() && enabledRoots.isEmpty()) {
                item { Ios5Empty("还没有有声书\n请到 设置 → 有声书 → 有声书来源 添加目录并扫描") }
            }
        }
    }
}

internal fun bookSubtitle(chapters: Int, currentIndex: Int, pct: Float, skipped: Int, played: Boolean = false): String {
    val parts = mutableListOf<String>()
    parts.add("共 $chapters 章")
    // 播过的书始终显示章节（哪怕 0%），否则用户会以为进度丢了。
    if (played || pct > 0f) parts.add("第 ${currentIndex + 1} 章 · ${(pct * 100).toInt()}%")
    if (skipped > 0) parts.add("跳过 $skipped 章")
    return parts.joinToString(" · ")
}

@Composable
internal fun AudiobookRow(
    title: String,
    subtitle: String,
    coverPath: String,
    accentKey: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (coverPath.isNotBlank()) {
            Artwork(coverPath, accentFor(accentKey), Modifier.size(44.dp), corner = 6.dp)
        } else {
            Icon(Icons.Filled.Headset, null, tint = Ios5Colors.TextSecondary, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "未知书名" },
                color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("›", color = Ios5Colors.TextSecondary.copy(alpha = 0.6f), fontSize = 24.sp, fontWeight = FontWeight.Light)
    }
}
