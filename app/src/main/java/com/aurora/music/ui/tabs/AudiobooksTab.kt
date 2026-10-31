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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.RemoteDownloadKind
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.RemoteDownloadSwipeRow
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
    remoteLibraries: List<com.aurora.music.data.cast.RemoteDeviceLibrary> = emptyList(),
    remoteRecents: List<com.aurora.music.data.cast.RemoteAudiobookEntry> = emptyList(),
    onPlayRemoteBook: (com.aurora.music.data.cast.RemoteAudiobookEntry) -> Unit = {},
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
        LazyColumn(
            state = com.aurora.music.ui.rememberPersistentLazyListState("tab:audiobooks"),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (recents.isNotEmpty() || remoteRecents.isNotEmpty()) {
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
                ios5Rows(remoteRecents, key = { it.key }) { _, b ->
                    val lib = remoteLibraries.firstOrNull { it.deviceId == b.deviceId }
                    val wire = lib?.audiobooks?.firstOrNull { it.id == b.bookId }
                    RemoteDownloadSwipeRow(
                        kind = RemoteDownloadKind.AUDIOBOOK,
                        deviceId = b.deviceId,
                        remoteKey = b.bookId,
                        remoteOnline = lib?.online == true,
                        onDownload = {
                            if (lib != null && wire != null) {
                                container.remoteDownloads.downloadAudiobook(
                                    b.deviceId, b.deviceName, lib.baseUrl, wire,
                                )
                            }
                        },
                    ) {
                        AudiobookRow(
                            title = b.displayTitle,
                            subtitle = bookSubtitle(b.chapters.size, b.currentIndex, 0f, 0, played = true),
                            coverPath = b.coverUrl,
                            accentKey = b.key,
                            remote = true,
                            onClick = { onPlayRemoteBook(b) },
                        )
                    }
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
            remoteLibraries.filter { it.audiobooks.isNotEmpty() }.forEach { lib ->
                item(key = "remote-abooks-${lib.deviceId}") {
                    Ios5SectionTitle("${lib.deviceName}${if (lib.online) "" else "（离线）"} - 远程（${lib.audiobooks.size}）")
                }
                ios5Rows(lib.audiobooks, key = { "r:${lib.deviceId}:${it.id}" }) { _, b ->
                    val entry = com.aurora.music.data.cast.RemoteAudiobookEntry(
                        deviceId = lib.deviceId,
                        deviceName = lib.deviceName,
                        bookId = b.id,
                        title = b.title,
                        coverUrl = container.remoteLibrary.audiobookCoverUrl(lib, b.id, b.hasCover),
                        chapters = b.chapters,
                        currentIndex = remoteRecents.firstOrNull {
                            it.deviceId == lib.deviceId && it.bookId == b.id
                        }?.currentIndex ?: 0,
                        lastPlayedAt = remoteRecents.firstOrNull {
                            it.deviceId == lib.deviceId && it.bookId == b.id
                        }?.lastPlayedAt ?: 0L,
                    )
                    RemoteDownloadSwipeRow(
                        kind = RemoteDownloadKind.AUDIOBOOK,
                        deviceId = lib.deviceId,
                        remoteKey = b.id,
                        remoteOnline = lib.online,
                        onDownload = {
                            container.remoteDownloads.downloadAudiobook(
                                lib.deviceId, lib.deviceName, lib.baseUrl, b,
                            )
                        },
                    ) {
                        AudiobookRow(
                            title = entry.displayTitle,
                            subtitle = bookSubtitle(entry.chapters.size, entry.currentIndex, 0f, 0, played = entry.lastPlayedAt > 0),
                            coverPath = entry.coverUrl,
                            accentKey = entry.key,
                            remote = true,
                            onClick = { onPlayRemoteBook(entry) },
                        )
                    }
                }
            }
            if (recents.isEmpty() && books.isEmpty() && enabledRoots.isEmpty() && remoteLibraries.all { it.audiobooks.isEmpty() }) {
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
    remote: Boolean = false,
) {
    val available = com.aurora.music.ui.components.LocalRemoteAvailable.current
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = available, onClick = onClick)
            .alpha(if (available) 1f else 0.45f)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (remote) {
            com.aurora.music.ui.components.Ios5RemoteStatusIcon()
            Spacer(Modifier.width(8.dp))
        }
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
