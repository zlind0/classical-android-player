package com.aurora.music.ui.ebook

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.RemoteDeviceLibrary
import com.aurora.music.data.cast.RemoteDownloadKind
import com.aurora.music.data.cast.RemoteEbookEntry
import com.aurora.music.data.cast.RemoteEbookRandomHistory
import com.aurora.music.data.cast.RemoteEbookRootWire
import com.aurora.music.data.cast.RemoteEbookWire
import com.aurora.music.ui.components.Ios5RemoteStatusIcon
import com.aurora.music.ui.components.RemoteDownloadSwipeRow
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SearchBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SmallButton
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.ui.tabs.EbookRow
import com.aurora.music.ui.tabs.authorAndProgress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 远程电子书库：完全照搬本地“书库 → 目录 → 电子书”的浏览方式。
 * 顶层是对方设备的默认书架与各扫描根；文件夹按子路径即时聚合，点书直接下载打开。
 *
 * 如果对方在该书库启用了“快速扫描（随机取书）”，这里默认随机展示 20 本，并保留：
 * - 顶部重新随机与数量调整；
 * - 搜索（结果超过随机数量 2 倍时可换一批或全部展开）；
 * - 页面最底部的“历史随机”，避免错过上一批里的好书。
 * 随机结果会按设备 + 书库存盘，只有用户主动点“重新随机”才会变化。
 */
@Composable
fun RemoteEbookLibraryScreen(
    deviceId: String,
    rootKey: String,
    dirPath: String,
    title: String,
    /** 冷启动恢复远端页面时允许显示灰色缓存（设备还没核对完）。 */
    allowOffline: Boolean = false,
    onBack: () -> Unit,
    onOpenDir: (dir: String, title: String) -> Unit,
    onOpenBook: (RemoteEbookEntry) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val libraries by container.remoteLibrary.libraries.collectAsStateWithLifecycle()
    val cachedLibraries by container.remoteLibrary.cachedLibraries.collectAsStateWithLifecycle()
    val recents by container.remoteLibrary.ebookRecents.collectAsStateWithLifecycle()
    val randomStates by container.remoteLibrary.ebookRandoms.collectAsStateWithLifecycle()
    val randomHistory by container.remoteLibrary.ebookRandomHistory.collectAsStateWithLifecycle()
    val lib = (if (allowOffline) cachedLibraries else libraries).firstOrNull { it.deviceId == deviceId }
    val root = lib?.let { container.remoteLibrary.ebookRootsOf(it) }
        ?.firstOrNull { it.key == rootKey }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = title.ifBlank { root?.displayName ?: "电子书" }, onBack = onBack)
        if (lib == null || root == null || (!lib.online && !allowOffline && !lib.hasDownloads)) {
            Ios5Empty("这台设备的资料库暂时不可用")
            return@Column
        }
        val currentDir = when {
            root.shelf -> ""
            root.key == "all" -> ""
            dirPath.isNotBlank() -> dirPath
            else -> root.rootPath
        }
        val bookWires = remember(lib, root, currentDir) {
            booksInDir(lib, root, currentDir)
        }
        val books = remember(lib, root, recents, currentDir, bookWires) {
            bookWires.map { b -> toEntry(lib, b, recents, container.remoteLibrary, container.remoteDownloads) }
                .sortedBy { it.displayTitle.lowercase() }
        }
        val wireByPath = remember(bookWires) { bookWires.associateBy { it.path } }
        val downloads = container.remoteDownloads
        val dirs = remember(lib, root, currentDir) {
            subDirs(lib, root, currentDir)
        }
        val listState = com.aurora.music.ui.rememberPersistentLazyListState(
            "remote_ebook:$deviceId:$rootKey:$currentDir",
        )

        // “快速扫描（随机取书）”只作用于书库根目录；进一步进入子目录时回到普通浏览。
        val randomMode = root.fastScan && !root.shelf && dirPath.isBlank()
        val poolWires = remember(lib, root) { booksInDir(lib, root, "") }
        val poolBooks = remember(lib, root, recents, poolWires) {
            poolWires.map { b -> toEntry(lib, b, recents, container.remoteLibrary, container.remoteDownloads) }
                .sortedBy { it.displayTitle.lowercase() }
        }
        val poolPaths = remember(poolWires) { poolWires.map { it.path } }
        val poolByPath = remember(poolBooks) { poolBooks.associateBy { it.remotePath } }
        val poolWireByPath = remember(poolWires) { poolWires.associateBy { it.path } }
        val randomState = randomStates["$deviceId|$rootKey"]
        val count = randomState?.count ?: 20

        var query by rememberSaveable(deviceId, rootKey) { mutableStateOf("") }
        var searchSeed by rememberSaveable(deviceId, rootKey) { mutableIntStateOf(0) }
        var searchExpanded by rememberSaveable(deviceId, rootKey) { mutableStateOf(false) }
        LaunchedEffect(deviceId, rootKey, randomMode, poolPaths) {
            if (randomMode && poolPaths.isNotEmpty()) {
                container.remoteLibrary.ensureEbookRandom(deviceId, rootKey, poolPaths)
            }
        }

        val randomBooks = remember(randomState?.paths, poolByPath) {
            randomState?.paths.orEmpty().mapNotNull { poolByPath[it] }
        }
        val searchResults = remember(query, poolBooks) {
            val q = query.trim()
            if (q.isBlank()) emptyList()
            else poolBooks.filter {
                it.displayTitle.contains(q, ignoreCase = true) ||
                    it.author.contains(q, ignoreCase = true)
            }
        }
        val searchTruncated = randomMode && query.isNotBlank() &&
            searchResults.size > count * 2 && !searchExpanded
        val searchBooks = remember(searchResults, searchSeed, count, searchExpanded, searchTruncated) {
            if (searchTruncated) searchResults.shuffled(Random(searchSeed)).take(count)
            else searchResults
        }
        val history = remember(randomHistory, deviceId, rootKey) {
            randomHistory["$deviceId|$rootKey"].orEmpty()
        }

        if (randomMode) {
            Ios5SearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索书名 / 作者",
            )
            RandomControls(
                randomMode = true,
                count = count,
                poolSize = poolBooks.size,
                searching = query.isNotBlank(),
                searchCount = searchResults.size,
                searchTruncated = searchTruncated,
                searchExpanded = searchExpanded,
                onCountChange = {
                    container.remoteLibrary.setEbookRandomCount(deviceId, rootKey, it, poolPaths)
                },
                onReroll = {
                    container.remoteLibrary.rerollEbookRandom(deviceId, rootKey, poolPaths)
                },
                onSearchReroll = { searchSeed += 1 },
                onToggleExpand = { searchExpanded = !searchExpanded },
            )
        }

        val showRandom = randomMode && query.isBlank()
        val displayBooks = when {
            !randomMode -> books
            query.isNotBlank() -> searchBooks
            else -> randomBooks
        }

        // 快速扫描书库：只为当前随机展示的这批书按需解析，不碰“全部展开”的海量结果。
        val warmDetails = randomMode && root.fastScan &&
            !(query.isNotBlank() && searchExpanded && searchResults.size > count * 2)
        LaunchedEffect(deviceId, warmDetails, displayBooks.map { it.remotePath }) {
            if (warmDetails) {
                container.remoteLibrary.requestEbookDetails(
                    deviceId,
                    displayBooks.map { it.remotePath },
                )
            }
        }

        if (!randomMode && dirs.isEmpty() && books.isEmpty()) {
            Ios5Empty("这个文件夹是空的")
        } else if (randomMode && poolBooks.isEmpty()) {
            Ios5Empty("这个书库是空的")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (!randomMode && dirs.isNotEmpty()) {
                    item { Ios5SectionTitle("文件夹（${dirs.size}）") }
                    ios5Rows(dirs, key = { it.second }) { _, d ->
                        val dirBooks = remember(lib, root, d.second) {
                            booksUnderDir(lib, root, d.second)
                        }
                        RemoteDownloadSwipeRow(
                            kind = RemoteDownloadKind.EBOOK_FOLDER,
                            deviceId = lib.deviceId,
                            remoteKey = "${root.key}|${d.second}",
                            remoteOnline = lib.online ||
                                dirBooks.any { downloads.isEbookDownloaded(lib.deviceId, it.path) },
                            onDownload = {
                                downloads.downloadEbookFolder(
                                    lib.deviceId, lib.deviceName, lib.baseUrl, root, d.second, d.first, dirBooks,
                                )
                            },
                        ) {
                            Ios5Cell(
                                title = d.first,
                                onClick = { onOpenDir(d.second, d.first) },
                                leading = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Ios5RemoteStatusIcon()
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
                }

                if (displayBooks.isNotEmpty()) {
                    val section = when {
                        !randomMode -> "电子书（${displayBooks.size}）"
                        query.isNotBlank() -> "搜索结果（${searchResults.size}）" +
                            if (searchTruncated) " · 随机显示 ${displayBooks.size} 本" else ""
                        else -> {
                            val total = poolBooks.size
                            if (randomBooks.size < total) {
                                "随机电子书（${displayBooks.size} / $total）"
                            } else {
                                "随机电子书（${displayBooks.size}）"
                            }
                        }
                    }
                    item { Ios5SectionTitle(section) }
                    ios5Rows(displayBooks, key = { it.key }) { _, b ->
                        BookDownloadRow(
                            book = b,
                            lib = lib,
                            root = root,
                            wire = wireByPath[b.remotePath] ?: poolWireByPath[b.remotePath],
                            onOpenBook = onOpenBook,
                        )
                    }
                } else if (!randomMode && dirs.isNotEmpty()) {
                    item { Ios5SectionTitle("电子书") }
                    item { Ios5Empty("这个目录下没有电子书") }
                } else if (randomMode && query.isNotBlank()) {
                    item { Ios5Empty("没有找到匹配的书") }
                }

                if (showRandom && history.isNotEmpty()) {
                    item { Ios5SectionTitle("历史随机") }
                    history.forEachIndexed { index, batch ->
                        val historyBooks = batch.paths.mapNotNull { poolByPath[it] }
                        item(key = "history-head-$index") {
                            HistoryHeader(
                                batch = batch,
                                visibleCount = historyBooks.size,
                                onRestore = {
                                    container.remoteLibrary.restoreEbookRandom(
                                        deviceId, rootKey, batch, poolPaths,
                                    )
                                },
                            )
                        }
                        ios5Rows(
                            historyBooks,
                            key = { "history-$index:${it.key}" },
                            topInset = 0.dp,
                            bottomInset = 8.dp,
                        ) { _, b ->
                            BookDownloadRow(
                                book = b,
                                lib = lib,
                                root = root,
                                wire = wireByPath[b.remotePath] ?: poolWireByPath[b.remotePath],
                                onOpenBook = onOpenBook,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RandomControls(
    randomMode: Boolean,
    count: Int,
    poolSize: Int,
    searching: Boolean,
    searchCount: Int,
    searchTruncated: Boolean,
    searchExpanded: Boolean,
    onCountChange: (Int) -> Unit,
    onReroll: () -> Unit,
    onSearchReroll: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    if (!randomMode) return
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (searching) "找到 $searchCount 本" else "随机选书 · 共 $poolSize 本",
                color = Ios5Colors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Ios5SmallButton(
                text = "−",
                onClick = { onCountChange(count - 10) },
                modifier = Modifier.width(38.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "每次 $count 本",
                color = Ios5Colors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
            Ios5SmallButton(
                text = "+",
                onClick = { onCountChange(count + 10) },
                modifier = Modifier.width(38.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searching) {
                if (searchTruncated) {
                    Ios5GlossButton(text = "换一批", onClick = onSearchReroll)
                    Ios5SmallButton(
                        text = "全部展开 $searchCount 本",
                        onClick = onToggleExpand,
                    )
                } else if (searchExpanded && searchCount > count * 2) {
                    Ios5SmallButton(text = "收起", onClick = onToggleExpand)
                }
            } else {
                Ios5GlossButton(text = "重新随机", onClick = onReroll)
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    batch: RemoteEbookRandomHistory,
    visibleCount: Int,
    onRestore: () -> Unit,
) {
    val label = remember(batch.createdAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(batch.createdAt))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$label · $visibleCount 本",
                color = Ios5Colors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "上一次的随机结果",
                color = Ios5Colors.TextSecondary.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
        Ios5SmallButton(text = "恢复", onClick = onRestore)
    }
}

@Composable
private fun BookDownloadRow(
    book: RemoteEbookEntry,
    lib: RemoteDeviceLibrary,
    root: RemoteEbookRootWire,
    wire: RemoteEbookWire?,
    onOpenBook: (RemoteEbookEntry) -> Unit,
) {
    val downloads = (LocalContext.current.applicationContext as AuroraApplication)
        .container.remoteDownloads
    RemoteDownloadSwipeRow(
        kind = RemoteDownloadKind.EBOOK,
        deviceId = book.deviceId,
        remoteKey = book.remotePath,
        remoteOnline = lib.online || downloads.isEbookDownloaded(lib.deviceId, book.remotePath),
        onDownload = {
            if (wire != null) {
                downloads.downloadEbook(
                    book.deviceId, book.deviceName, lib.baseUrl, wire, book.coverUrl, root,
                )
            }
        },
    ) {
        EbookRow(
            title = book.displayTitle,
            subtitle = authorAndProgress(book.author, 0f),
            coverPath = book.coverUrl,
            accentKey = book.key,
            remote = true,
            onClick = { onOpenBook(book) },
        )
    }
}

private fun toEntry(
    lib: RemoteDeviceLibrary,
    b: RemoteEbookWire,
    recents: List<RemoteEbookEntry>,
    remoteLibrary: com.aurora.music.data.cast.RemoteLibraryManager,
    downloads: com.aurora.music.data.cast.RemoteDownloadManager,
): RemoteEbookEntry {
    val cached = recents.firstOrNull {
        it.deviceId == lib.deviceId && it.remotePath == b.path
    }
    return RemoteEbookEntry(
        deviceId = lib.deviceId,
        deviceName = lib.deviceName,
        remotePath = b.path,
        title = b.title,
        author = b.author,
        coverUrl = remoteLibrary.ebookCoverUrl(lib, b.path, b.hasCover),
        size = b.size,
        localPath = downloads.localEbookPath(lib.deviceId, b.path) ?: cached?.localPath.orEmpty(),
        lastReadAt = cached?.lastReadAt ?: 0L,
    )
}

private fun booksInDir(
    lib: RemoteDeviceLibrary,
    root: RemoteEbookRootWire,
    dir: String,
): List<RemoteEbookWire> {
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

/** 目录下载用：包含所有子目录里的书。 */
private fun booksUnderDir(
    lib: RemoteDeviceLibrary,
    root: RemoteEbookRootWire,
    dir: String,
): List<RemoteEbookWire> {
    if (dir.isBlank()) return emptyList()
    val prefix = dir.trimEnd('/') + "/"
    return booksInDir(lib, root, "").filter { it.path.startsWith(prefix) }
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
