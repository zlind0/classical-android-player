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
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.aurora.music.data.ebook.EBOOK_SHELF_ROOT_ID
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.RemoteDownloadSwipeRow
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.util.accentFor

// 电子书 Tab：顶部最近阅读（最近的在最上，条数由 设置 → 电子书设置 控制，5..20），
// 下面是书库入口：默认书架置顶，其次各文件夹按名称字母序。
@Composable
fun EbooksTab(
    onOpenShelf: () -> Unit,
    onOpenRoot: (rootId: Long, dir: String, title: String) -> Unit,
    onOpenBook: (path: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenExport: (path: String) -> Unit,
    remoteLibraries: List<com.aurora.music.data.cast.RemoteDeviceLibrary> = emptyList(),
    remoteRecents: List<com.aurora.music.data.cast.RemoteEbookEntry> = emptyList(),
    onOpenRemoteBook: (com.aurora.music.data.cast.RemoteEbookEntry) -> Unit = {},
    onOpenRemoteRoot: (deviceId: String, rootKey: String, title: String) -> Unit = { _, _, _ -> },
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val recents = container.ebookStore.recents.collectAsStateWithLifecycle()
    val recentLimit = container.ebookPrefs.recentLimit.collectAsStateWithLifecycle()
    val roots = container.ebookStore.roots.collectAsStateWithLifecycle()
    val counts = container.ebookStore.counts.collectAsStateWithLifecycle()
    val enabledRoots = roots.value.filter { it.enabled }.sortedBy { it.displayName.lowercase() }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "电子书", onSearch = onOpenSearch)
        LazyColumn(
            state = com.aurora.music.ui.rememberPersistentLazyListState("tab:ebooks"),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // recents 固定存上限 20 条，这里按用户设置截断（首页仍固定 3 本，互不影响）
            val recent = recents.value.take(recentLimit.value)
            val remoteRecent = remoteRecents.take(recentLimit.value)
            if (recent.isNotEmpty() || remoteRecent.isNotEmpty()) {
                item { Ios5SectionTitle("最近阅读") }
                ios5Rows(recent, key = { it.path }) { _, b ->
                    EbookRow(
                        title = b.displayTitle,
                        subtitle = authorAndProgress(b.author, b.progressPct),
                        coverPath = b.coverPath,
                        accentKey = b.path,
                        onClick = { onOpenBook(b.path) },
                        trailing = {
                            com.aurora.music.ui.ebook.EbookExportButton(b.path, onOpenExport)
                        },
                    )
                }
                ios5Rows(remoteRecent, key = { it.key }) { _, b ->
                    val lib = remoteLibraries.firstOrNull { it.deviceId == b.deviceId }
                    val wire = lib?.ebooks?.firstOrNull { it.path == b.remotePath }
                    RemoteDownloadSwipeRow(
                        kind = RemoteDownloadKind.EBOOK,
                        deviceId = b.deviceId,
                        remoteKey = b.remotePath,
                        remoteOnline = lib?.online == true,
                        onDownload = {
                            if (lib != null && wire != null) {
                                container.remoteDownloads.downloadEbook(
                                    b.deviceId, b.deviceName, lib.baseUrl, wire, b.coverUrl,
                                    container.remoteLibrary.ebookRootsOf(lib).firstOrNull { it.key == wire.rootKey },
                                )
                            }
                        },
                    ) {
                        EbookRow(
                            title = b.displayTitle,
                            subtitle = b.deviceName,
                            coverPath = b.coverUrl,
                            accentKey = b.key,
                            remote = true,
                            onClick = { onOpenRemoteBook(b) },
                        )
                    }
                }
            }
            item { Ios5SectionTitle("书库") }
            item {
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    Ios5Cell(
                        title = "默认书架",
                        subtitle = "外部打开的电子书都存在这里",
                        count = "${counts.value[EBOOK_SHELF_ROOT_ID] ?: 0}",
                        onClick = onOpenShelf,
                        leading = {
                            Icon(Icons.Filled.Book, null, tint = Ios5Colors.IosBlue, modifier = Modifier.size(40.dp))
                        },
                    )
                    enabledRoots.forEach { r ->
                        Ios5CellDivider()
                        Ios5Cell(
                            title = r.displayName,
                            subtitle = r.rootPath,
                            count = "${counts.value[r.id] ?: 0}",
                            onClick = { onOpenRoot(r.id, r.rootPath, r.displayName) },
                            leading = {
                                Icon(Icons.Filled.Folder, null, tint = Ios5Colors.IosBlue, modifier = Modifier.size(40.dp))
                            },
                        )
                    }
                }
            }
            remoteLibraries.filter { it.ebooks.isNotEmpty() }.forEach { lib ->
                item(key = "remote-ebooks-${lib.deviceId}") {
                    Ios5SectionTitle("${lib.deviceName}${if (lib.online) "" else "（离线）"} - 远程（${lib.ebooks.size}）")
                }
                val roots = container.remoteLibrary.ebookRootsOf(lib)
                ios5Rows(roots, key = { "r:${lib.deviceId}:${it.key}" }) { _, r ->
                    val rootBooks = remoteBooksInRoot(lib, r)
                    RemoteDownloadSwipeRow(
                        kind = RemoteDownloadKind.EBOOK_ROOT,
                        deviceId = lib.deviceId,
                        remoteKey = r.key,
                        remoteOnline = lib.online ||
                            rootBooks.any {
                                container.remoteDownloads.isEbookDownloaded(lib.deviceId, it.path)
                            },
                        onDownload = {
                            container.remoteDownloads.downloadEbookRoot(
                                lib.deviceId, lib.deviceName, lib.baseUrl, r, rootBooks,
                            )
                        },
                    ) {
                        Ios5Cell(
                            title = r.displayName.ifBlank { "电子书" },
                            subtitle = when {
                                r.shelf -> "外部打开的电子书都存在这里"
                                r.fastScan -> "快速扫描 · 随机取书 · ${r.rootPath}"
                                else -> r.rootPath
                            },
                            count = "${r.bookCount}",
                            onClick = { onOpenRemoteRoot(lib.deviceId, r.key, r.displayName) },
                            leading = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    com.aurora.music.ui.components.Ios5RemoteStatusIcon()
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        if (r.shelf) Icons.Filled.Book else Icons.Filled.Folder,
                                        null,
                                        tint = Ios5Colors.IosBlue,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            if (recent.isEmpty() && remoteRecent.isEmpty() && enabledRoots.isEmpty() && remoteLibraries.all { it.ebooks.isEmpty() }) {
                item { Ios5Empty("还没有电子书\n请到 设置 → 电子书来源 添加目录并扫描\n也可以用其他应用打开 epub/mobi/azw3 存入默认书架") }
            }
        }
    }
}

private fun remoteBooksInRoot(
    lib: com.aurora.music.data.cast.RemoteDeviceLibrary,
    root: com.aurora.music.data.cast.RemoteEbookRootWire,
): List<com.aurora.music.data.cast.RemoteEbookWire> {
    if (root.key == "all") return lib.ebooks
    return lib.ebooks.filter { b ->
        when {
            root.shelf -> b.rootKey == "shelf" || (b.rootKey.isBlank() && b.path.contains("/ebooks/shelf/"))
            b.rootKey == root.key -> true
            b.rootKey.isBlank() && root.rootPath.isNotBlank() ->
                b.path == root.rootPath || b.path.startsWith(root.rootPath.trimEnd('/') + "/")
            else -> false
        }
    }
}

internal fun authorAndProgress(author: String, pct: Float): String {
    val parts = mutableListOf<String>()
    if (author.isNotBlank()) parts.add(author)
    if (pct > 0f) parts.add("已读 ${(pct * 100).toInt()}%")
    return parts.joinToString(" · ")
}

@Composable
internal fun EbookRow(
    title: String,
    subtitle: String,
    coverPath: String,
    accentKey: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
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
            Artwork(coverPath, accentFor(accentKey), Modifier.size(44.dp, 60.dp), corner = 4.dp)
        } else {
            Icon(Icons.Filled.Book, null, tint = Ios5Colors.TextSecondary, modifier = Modifier.size(44.dp))
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
        trailing?.invoke()
        Text("›", color = Ios5Colors.TextSecondary.copy(alpha = 0.6f), fontSize = 24.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
internal fun EbookLoadingRow() {
    Ios5Loading()
}
