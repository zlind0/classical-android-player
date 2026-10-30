package com.aurora.music.ui.ebook

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
import androidx.compose.material.icons.filled.Folder
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
import com.aurora.music.data.cast.RemoteDeviceLibrary
import com.aurora.music.data.cast.RemoteEbookEntry
import com.aurora.music.data.cast.RemoteEbookRootWire
import com.aurora.music.ui.components.Ios5WifiIcon
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.ui.tabs.EbookRow
import com.aurora.music.ui.tabs.authorAndProgress
import java.io.File

/**
 * 远程电子书库：完全照搬本地“书库 → 目录 → 电子书”的浏览方式。
 * 顶层是对方设备的默认书架与各扫描根；文件夹按子路径即时聚合，点书直接下载打开。
 */
@Composable
fun RemoteEbookLibraryScreen(
    deviceId: String,
    rootKey: String,
    dirPath: String,
    title: String,
    onBack: () -> Unit,
    onOpenDir: (dir: String, title: String) -> Unit,
    onOpenBook: (RemoteEbookEntry) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val libraries by container.remoteLibrary.libraries.collectAsStateWithLifecycle()
    val recents by container.remoteLibrary.ebookRecents.collectAsStateWithLifecycle()
    val lib = libraries.firstOrNull { it.deviceId == deviceId }
    val root = lib?.let { container.remoteLibrary.ebookRootsOf(it) }
        ?.firstOrNull { it.key == rootKey }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = title.ifBlank { root?.displayName ?: "电子书" }, onBack = onBack)
        if (lib == null || !lib.online || root == null) {
            Ios5Empty("这台设备的资料库暂时不可用")
            return@Column
        }
        val currentDir = when {
            root.shelf -> ""
            root.key == "all" -> ""
            dirPath.isNotBlank() -> dirPath
            else -> root.rootPath
        }
        val books = remember(lib, root, recents, currentDir) {
            booksInDir(lib, root, currentDir).map { b ->
                val cached = recents.firstOrNull {
                    it.deviceId == lib.deviceId && it.remotePath == b.path
                }
                RemoteEbookEntry(
                    deviceId = lib.deviceId,
                    deviceName = lib.deviceName,
                    remotePath = b.path,
                    title = b.title,
                    author = b.author,
                    coverUrl = container.remoteLibrary.ebookCoverUrl(lib, b.path, b.hasCover),
                    size = b.size,
                    localPath = cached?.localPath.orEmpty(),
                    lastReadAt = cached?.lastReadAt ?: 0L,
                )
            }.sortedBy { it.displayTitle.lowercase() }
        }
        val dirs = remember(lib, root, currentDir) {
            subDirs(lib, root, currentDir)
        }
        val listState = com.aurora.music.ui.rememberPersistentLazyListState(
            "remote_ebook:$deviceId:$rootKey:$currentDir",
        )
        if (dirs.isEmpty() && books.isEmpty()) {
            Ios5Empty("这个文件夹是空的")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (dirs.isNotEmpty()) {
                    item { Ios5SectionTitle("文件夹（${dirs.size}）") }
                    ios5Rows(dirs, key = { it.second }) { _, d ->
                        Ios5Cell(
                            title = d.first,
                            onClick = { onOpenDir(d.second, d.first) },
                            leading = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Ios5WifiIcon()
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Filled.Folder,
                                        null,
                                        tint = Ios5Colors.IosBlue,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                            },
                        )
                    }
                }
                if (books.isNotEmpty()) {
                    item { Ios5SectionTitle("电子书（${books.size}）") }
                    ios5Rows(books, key = { it.key }) { _, b ->
                        EbookRow(
                            title = b.displayTitle,
                            subtitle = authorAndProgress(b.author, 0f),
                            coverPath = b.coverUrl,
                            accentKey = b.key,
                            remote = true,
                            onClick = { onOpenBook(b) },
                        )
                    }
                } else if (dirs.isNotEmpty()) {
                    item { Ios5SectionTitle("电子书") }
                    item { Ios5Empty("这个目录下没有电子书") }
                }
            }
        }
    }
}

private fun booksInDir(
    lib: RemoteDeviceLibrary,
    root: RemoteEbookRootWire,
    dir: String,
): List<com.aurora.music.data.cast.RemoteEbookWire> {
    if (root.key == "all") return lib.ebooks
    val scoped = lib.ebooks.filter { b ->
        when {
            root.shelf -> b.rootKey == "shelf" || (b.rootKey.isBlank() && b.path.contains("/ebooks/shelf/"))
            b.rootKey == root.key -> true
            b.rootKey.isBlank() && root.rootPath.isNotBlank() ->
                b.path == root.rootPath || b.path.startsWith(root.rootPath.trimEnd('/') + "/")
            else -> false
        }
    }
    if (root.shelf || dir.isBlank()) return scoped
    return scoped.filter { File(it.path).parent == dir }
}

private fun subDirs(
    lib: RemoteDeviceLibrary,
    root: RemoteEbookRootWire,
    dir: String,
): List<Pair<String, String>> {
    if (root.key == "all" || root.shelf) return emptyList()
    val base = dir.ifBlank { root.rootPath }
    if (base.isBlank()) return emptyList()
    val prefix = base.trimEnd('/') + "/"
    return lib.ebooks.asSequence()
        .map { it.path }
        .filter { it.startsWith(prefix) }
        .mapNotNull { path ->
            val rest = path.removePrefix(prefix)
            val idx = rest.indexOf('/')
            if (idx <= 0) null else rest.substring(0, idx)
        }
        .distinct()
        .sortedBy { it.lowercase() }
        .map { name -> name to (prefix + name) }
        .toList()
}
