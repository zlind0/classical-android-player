package com.aurora.music.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.navigation.Ios5Routes
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SongRow
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.HomeUiState

// ---------------- 首页 ----------------

@Composable
fun HomeTab(
    state: HomeUiState,
    onOpenDetail: (kind: String, id: String, title: String) -> Unit,
    onPlaySongs: (songs: List<Song>, index: Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "首页")
        if (state.loading) {
            Ios5Loading()
            return
        }
        val d = state.data
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (d.starred.isNotEmpty()) {
                item {
                    Ios5SectionTitle("我的收藏")
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        d.starred.take(5).forEachIndexed { i, s ->
                            if (i > 0) Ios5CellDivider()
                            Ios5SongRow(s, false, false) { onPlaySongs(d.starred, d.starred.indexOf(s)) }
                        }
                    }
                }
            }
            if (d.recentlyPlayed.isNotEmpty() || d.newReleases.isNotEmpty()) {
                item {
                    Ios5SectionTitle("最近上架")
                    ShelfRow(
                        covers = (d.newReleases + d.recentlyPlayed).distinctBy { it.id }.take(10),
                        onOpen = { onOpenDetail("album", it.id, it.title) },
                    )
                }
            }
            if (d.mostPlayed.isNotEmpty()) {
                item {
                    Ios5SectionTitle("常听专辑")
                    ShelfRow(
                        covers = d.mostPlayed.take(10),
                        onOpen = { onOpenDetail("album", it.id, it.title) },
                    )
                }
            }
            if (d.playlists.isNotEmpty()) {
                item {
                    Ios5SectionTitle("歌单")
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        d.playlists.take(5).forEachIndexed { i, p ->
                            if (i > 0) Ios5CellDivider()
                            Ios5Cell(
                                title = p.title,
                                subtitle = p.subtitle,
                                onClick = { onOpenDetail("playlist", p.id, p.title) },
                                leading = { Artwork(p.coverUrl, p.accent, Modifier.size(40.dp), corner = 6.dp) },
                            )
                        }
                    }
                }
            }
            if (d.artists.isNotEmpty()) {
                item {
                    Ios5SectionTitle("艺人")
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        d.artists.take(5).forEachIndexed { i, a ->
                            if (i > 0) Ios5CellDivider()
                            Ios5Cell(
                                title = a.name,
                                onClick = { onOpenDetail("artist", a.id, a.name) },
                                leading = { Artwork(a.imageUrl, accentFor(a.id), Modifier.size(40.dp), corner = 20.dp) },
                            )
                        }
                    }
                }
            }
            if (d.newReleases.isEmpty() && d.playlists.isEmpty() && d.artists.isEmpty() && d.starred.isEmpty()) {
                item { Ios5Empty("资料库是空的\n请到 设置 → 音乐来源 添加目录并扫描") }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    covers: List<com.aurora.music.model.Album>,
    onOpen: (com.aurora.music.model.Album) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(covers, key = { it.id }) { a ->
            Column(
                Modifier.width(120.dp)
                    .clickable(onClick = { onOpen(a) }),
            ) {
                Artwork(
                    a.artworkUrl, accentFor(a.id), Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)),
                    corner = 8.dp,
                )
                Text(
                    a.title, fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                    color = com.aurora.music.ui.ios5.Ios5Colors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    a.artist, fontSize = 12.sp, lineHeight = 14.sp, color = com.aurora.music.ui.ios5.Ios5Colors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------- 歌单 ----------------

@Composable
fun PlaylistsTab(
    playlists: List<Playlist>,
    smartCount: Int,
    onOpenDetail: (kind: String, id: String, title: String) -> Unit,
    onCreatePlaylist: (name: String) -> Unit,
    onOpenSearch: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "歌单", onSearch = onOpenSearch)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Ios5GlossButton("＋ 新建歌单", { showCreate = true }, Modifier.fillMaxWidth())
                }
            }
            item {
                Ios5SectionTitle("资料库")
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    Ios5Cell(
                        title = "喜欢的歌曲",
                        subtitle = "本地收藏",
                        onClick = { onOpenDetail("liked", "liked", "喜欢的歌曲") },
                    )
                    if (smartCount > 0) {
                        Ios5CellDivider()
                        Ios5Cell(
                            title = "智能歌单",
                            count = "$smartCount",
                            onClick = { /* opened from detail list below */ },
                        )
                    }
                }
            }
            item {
                Ios5SectionTitle("我的歌单（${playlists.size}）")
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    if (playlists.isEmpty()) {
                        Ios5Empty("还没有歌单")
                    } else {
                        playlists.forEachIndexed { i, p ->
                            if (i > 0) Ios5CellDivider()
                            Ios5Cell(
                                title = p.title,
                                subtitle = p.subtitle,
                                onClick = { onOpenDetail("playlist", p.id, p.title) },
                                leading = { Artwork(p.coverUrl, p.accent, Modifier.size(40.dp), corner = 6.dp) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建歌单") },
            text = { TextField(value = draft, onValueChange = { draft = it }, placeholder = { Text("歌单名称") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (draft.isNotBlank()) onCreatePlaylist(draft.trim())
                    draft = ""
                    showCreate = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }
}

// ---------------- 艺人 ----------------

@Composable
fun ArtistsTab(
    artists: List<Artist>,
    loading: Boolean,
    onOpenDetail: (kind: String, id: String, title: String) -> Unit,
    onOpenSearch: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "艺人", onSearch = onOpenSearch)
        when {
            loading -> Ios5Loading()
            artists.isEmpty() -> Ios5Empty("没有艺人\n请先扫描音乐目录")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Ios5SectionTitle("全部艺人（${artists.size}）")
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        artists.forEachIndexed { i, a ->
                            if (i > 0) Ios5CellDivider()
                            Ios5Cell(
                                title = a.name,
                                onClick = { onOpenDetail("artist", a.id, a.name) },
                                leading = { Artwork(a.imageUrl, accentFor(a.id), Modifier.size(40.dp), corner = 20.dp) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 更多 ----------------

@Composable
fun MoreTab(onOpenRoute: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "更多")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Ios5SectionTitle("浏览音乐")
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    val rows = listOf(
                        "歌曲" to Ios5Routes.MORE_SONGS,
                        "专辑" to Ios5Routes.MORE_ALBUMS,
                        "风格" to Ios5Routes.MORE_GENRES,
                        "作曲家" to Ios5Routes.MORE_COMPOSERS,
                        "文件夹" to Ios5Routes.MORE_FOLDERS,
                    )
                    rows.forEachIndexed { i, (label, route) ->
                        if (i > 0) Ios5CellDivider()
                        Ios5Cell(title = label, onClick = { onOpenRoute(route) })
                    }
                }
            }
        }
    }
}

// ---------------- 设置 ----------------

@Composable
fun SettingsTab(
    onOpen: (String) -> Unit,
    onOpenDetail: (kind: String, id: String, title: String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val session by container.settingsStore.session.collectAsStateWithLifecycle(initialValue = null)
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "设置")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Ios5SectionTitle("我的音乐")
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(
                            session?.imageUrl ?: "", accentFor("me"),
                            Modifier.size(52.dp), corner = 26.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (session?.username ?: "").ifBlank { "本地听众" },
                                fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                color = com.aurora.music.ui.ios5.Ios5Colors.TextPrimary,
                            )
                            Text(
                                "本机音乐库", fontSize = 13.sp,
                                color = com.aurora.music.ui.ios5.Ios5Colors.TextSecondary,
                            )
                        }
                    }
                    Ios5CellDivider()
                    Ios5Cell(title = "喜欢的歌曲", onClick = { onOpenDetail("liked", "liked", "喜欢的歌曲") })
                }
            }
            item {
                Ios5SectionTitle("资料库")
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    Ios5Cell(title = "音乐来源", subtitle = "目录 / 扫描", onClick = { onOpen(Ios5Routes.SETTINGS_SOURCES) })
                    Ios5CellDivider()
                    Ios5Cell(title = "播放历史", onClick = { onOpen(Ios5Routes.HISTORY) })
                    Ios5CellDivider()
                    Ios5Cell(title = "听歌统计", onClick = { onOpen(Ios5Routes.STATS) })
                    Ios5CellDivider()
                    Ios5Cell(title = "重复文件", onClick = { onOpen(Ios5Routes.DUPLICATES) })
                    Ios5CellDivider()
                    Ios5Cell(title = "存储与下载", onClick = { onOpen(Ios5Routes.SETTINGS_STORAGE) })
                    Ios5CellDivider()
                    Ios5Cell(title = "备份", onClick = { onOpen(Ios5Routes.SETTINGS_BACKUP) })
                }
            }
            item {
                Ios5SectionTitle("播放与音效")
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    Ios5Cell(title = "播放", onClick = { onOpen(Ios5Routes.SETTINGS_PLAYBACK) })
                    Ios5CellDivider()
                    Ios5Cell(title = "均衡器", subtitle = "校正 / 用户EQ / 动态", onClick = { onOpen(Ios5Routes.SETTINGS_EQ) })
                    Ios5CellDivider()
                    Ios5Cell(title = "Sonic", onClick = { onOpen(Ios5Routes.SETTINGS_SONIC) })
                    Ios5CellDivider()
                    Ios5Cell(title = "可视化", onClick = { onOpen(Ios5Routes.SETTINGS_VISUALIZER) })
                }
            }
            item {
                Ios5SectionTitle("通用")
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    Ios5Cell(title = "外观", onClick = { onOpen(Ios5Routes.SETTINGS_APPEARANCE) })
                    Ios5CellDivider()
                    Ios5Cell(title = "手势", onClick = { onOpen(Ios5Routes.SETTINGS_GESTURES) })
                    Ios5CellDivider()
                    Ios5Cell(title = "集成", onClick = { onOpen(Ios5Routes.SETTINGS_INTEGRATIONS) })
                    Ios5CellDivider()
                    Ios5Cell(title = "权限", onClick = { onOpen(Ios5Routes.SETTINGS_PERMISSIONS) })
                    Ios5CellDivider()
                    Ios5Cell(title = "关于", onClick = { onOpen(Ios5Routes.SETTINGS_ABOUT) })
                }
            }
        }
    }
}
