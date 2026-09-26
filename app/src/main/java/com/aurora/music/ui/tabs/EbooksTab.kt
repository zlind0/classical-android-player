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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ebook.EBOOK_SHELF_ROOT_ID
import com.aurora.music.ui.components.Artwork
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

// 电子书 Tab：顶部 5 本最近阅读（最近的在最上），下面是书库入口：
// 默认书架置顶，其次各文件夹按名称字母序。
@Composable
fun EbooksTab(
    onOpenShelf: () -> Unit,
    onOpenRoot: (rootId: Long, dir: String, title: String) -> Unit,
    onOpenBook: (path: String) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val recents = container.ebookStore.recents.collectAsStateWithLifecycle()
    val roots = container.ebookStore.roots.collectAsStateWithLifecycle()
    val counts = container.ebookStore.counts.collectAsStateWithLifecycle()
    val enabledRoots = roots.value.filter { it.enabled }.sortedBy { it.displayName.lowercase() }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "电子书", onSearch = onOpenSearch)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            val recent = recents.value
            if (recent.isNotEmpty()) {
                item { Ios5SectionTitle("最近阅读") }
                ios5Rows(recent, key = { it.path }) { _, b ->
                    EbookRow(
                        title = b.displayTitle,
                        subtitle = authorAndProgress(b.author, b.progressPct),
                        coverPath = b.coverPath,
                        accentKey = b.path,
                        onClick = { onOpenBook(b.path) },
                    )
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
            if (recent.isEmpty() && enabledRoots.isEmpty()) {
                item { Ios5Empty("还没有电子书\n请到 设置 → 电子书来源 添加目录并扫描\n也可以用其他应用打开 epub/mobi/azw3 存入默认书架") }
            }
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
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
