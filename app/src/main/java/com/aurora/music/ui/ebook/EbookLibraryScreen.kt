package com.aurora.music.ui.ebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ebook.EbookBook
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.ui.tabs.EbookRow
import com.aurora.music.ui.tabs.authorAndProgress
import java.io.File
import kotlinx.coroutines.launch

// 书库内页：默认书架（搜索 + 编辑多选删除）或某个文件夹
// （子文件夹 + 电子书混排，文件夹在上按字母序，书在下按书名）。
@Composable
fun EbookLibraryScreen(
    isShelf: Boolean,
    rootPath: String,
    dirPath: String,
    title: String,
    onBack: () -> Unit,
    onOpenDir: (dir: String, title: String) -> Unit,
    onOpenBook: (path: String) -> Unit,
    confirm: (String) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    val shelfBooks by container.ebookStore.shelf.collectAsStateWithLifecycle()

    var listing by remember(dirPath) { mutableStateOf<Pair<List<File>, List<EbookBook>>?>(null) }
    LaunchedEffect(dirPath, isShelf) {
        if (!isShelf) {
            listing = null
            val l = container.ebookStore.listDir(rootPath, dirPath)
            listing = l.dirs to l.books
        }
    }

    val shelfFiltered = remember(shelfBooks, query) {
        if (query.isBlank()) shelfBooks
        else shelfBooks.filter {
            it.displayTitle.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(
            title = title,
            onBack = { if (searching) searching = false else onBack() },
            onSearch = { searching = !searching },
            actionLabel = if (isShelf) (if (editing) "完成" else "编辑") else null,
            onAction = if (isShelf) ({
                editing = !editing
                selected = emptySet()
            }) else null,
        )
        if (searching) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索书名 / 作者", fontSize = 15.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        }
        if (isShelf) {
            if (shelfFiltered.isEmpty()) {
                Ios5Empty(if (query.isBlank()) "默认书架是空的\n用其他应用打开 epub/mobi/azw3 就会存到这里" else "没有匹配的书")
            } else {
                LazyColumn(Modifier.fillMaxSize().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item { Ios5SectionTitle("共 ${shelfFiltered.size} 本") }
                    ios5Rows(shelfFiltered, key = { it.path }) { _, b ->
                        val sel = b.path in selected
                        if (editing) {
                            EbookCheckRow(
                                checked = sel,
                                title = b.displayTitle,
                                subtitle = authorAndProgress(b.author, b.progressPct),
                                coverPath = b.coverPath,
                                accentKey = b.path,
                                onClick = {
                                    selected = if (sel) selected - b.path else selected + b.path
                                },
                            )
                        } else {
                            EbookRow(
                                title = b.displayTitle,
                                subtitle = authorAndProgress(b.author, b.progressPct),
                                coverPath = b.coverPath,
                                accentKey = b.path,
                                onClick = { onOpenBook(b.path) },
                            )
                        }
                    }
                    if (editing) {
                        item {
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                Ios5GlossButton(
                                    text = if (selected.isEmpty()) "删除" else "删除（${selected.size}）",
                                    onClick = {
                                        if (selected.isEmpty()) return@Ios5GlossButton
                                        val doomed = selected.toList()
                                        scope.launch {
                                            container.ebookStore.deleteShelf(doomed)
                                            selected = emptySet()
                                            editing = false
                                            confirm("已删除 ${doomed.size} 本")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            val l = listing
            if (l == null) {
                Ios5Loading()
            } else {
                val (dirs, books) = l
                val q = query.trim()
                val showDirs = dirs.filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
                val showBooks = books.filter {
                    q.isBlank() || it.displayTitle.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true)
                }
                if (showDirs.isEmpty() && showBooks.isEmpty()) {
                    Ios5Empty("这个文件夹是空的")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (showDirs.isNotEmpty()) {
                            item { Ios5SectionTitle("文件夹") }
                            ios5Rows(showDirs, key = { it.absolutePath }) { _, d ->
                                FolderRow(name = d.name, onClick = { onOpenDir(d.absolutePath, d.name) })
                            }
                        }
                        if (showBooks.isNotEmpty()) {
                            item { Ios5SectionTitle("电子书（${showBooks.size}）") }
                            ios5Rows(showBooks, key = { it.path }) { _, b ->
                                EbookRow(
                                    title = b.displayTitle,
                                    subtitle = authorAndProgress(b.author, b.progressPct),
                                    coverPath = b.coverPath,
                                    accentKey = b.path,
                                    onClick = { onOpenBook(b.path) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, null, tint = Ios5Colors.IosBlue, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            name, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("›", color = Ios5Colors.TextSecondary.copy(alpha = 0.6f), fontSize = 24.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun EbookCheckRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    coverPath: String,
    accentKey: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (checked) "☑" else "☐",
            color = if (checked) Ios5Colors.IosBlue else Ios5Colors.TextSecondary,
            fontSize = 22.sp, modifier = Modifier.padding(end = 8.dp),
        )
        EbookRow(title = title, subtitle = subtitle, coverPath = coverPath, accentKey = accentKey, onClick = onClick)
    }
}

// 全库搜索：书架 + 已扫描，书名/作者匹配。
@Composable
fun EbookSearchScreen(
    onBack: () -> Unit,
    onOpenBook: (path: String) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    var query by remember { mutableStateOf("") }
    val shelf by container.ebookStore.shelf.collectAsStateWithLifecycle()
    var scanned by remember { mutableStateOf(emptyList<EbookBook>()) }
    val roots by container.ebookStore.roots.collectAsStateWithLifecycle()
    LaunchedEffect(roots) {
        val all = mutableListOf<EbookBook>()
        roots.filter { it.enabled }.forEach { all.addAll(container.ebookStore.booksOfRoot(it.id)) }
        scanned = all
    }
    val results = remember(query, shelf, scanned) {
        val q = query.trim()
        if (q.isBlank()) emptyList()
        else (shelf + scanned).distinctBy { it.path }.filter {
            it.displayTitle.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true)
        }.take(100)
    }
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "搜索电子书", onBack = onBack)
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("书名 / 作者", fontSize = 15.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        if (query.isBlank()) {
            Ios5Empty("输入书名或作者搜索")
        } else if (results.isEmpty()) {
            Ios5Empty("没有找到匹配的书")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Ios5SectionTitle("找到 ${results.size} 本") }
                item {
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        results.forEachIndexed { i, b ->
                            if (i > 0) Ios5CellDivider()
                            EbookRow(
                                title = b.displayTitle,
                                subtitle = authorAndProgress(b.author, b.progressPct),
                                coverPath = b.coverPath,
                                accentKey = b.path,
                                onClick = { onOpenBook(b.path) },
                            )
                        }
                    }
                }
            }
        }
    }
}
