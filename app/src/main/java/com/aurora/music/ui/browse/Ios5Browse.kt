package com.aurora.music.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AlbumRow
import com.aurora.music.data.LibrarySource
import com.aurora.music.data.mapMergeRows
import com.aurora.music.data.mergeEnabledFor
import com.aurora.music.model.Album
import com.aurora.music.model.Song
import com.aurora.music.navigation.Ios5Routes
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5ActionsHeader
import com.aurora.music.ui.ios5.Ios5HeaderAction
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SongRow
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.DetailViewModel
import com.aurora.music.viewmodel.FolderViewModel
import com.aurora.music.viewmodel.NamedGroup
import com.aurora.music.viewmodel.PlayerUiState

// ---------------- 歌曲 ----------------

@Composable
fun Ios5SongsBrowse(
    songs: List<Song>,
    loading: Boolean,
    player: PlayerUiState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "歌曲", onBack = onBack, onSearch = onSearch)
        when {
            loading -> Ios5Loading()
            songs.isEmpty() -> Ios5Empty("没有歌曲")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Ios5SectionTitle("全部歌曲（${songs.size}）") }
                ios5Rows(songs, key = { it.id }) { i, s ->
                    Ios5SongRow(s, s.id == player.current.id, player.isPlaying) {
                        onPlaySongs(songs, i)
                    }
                }
            }
        }
    }
}

// ---------------- 专辑 ----------------

@Composable
fun Ios5AlbumsBrowse(
    albums: List<Album>,
    loading: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenDetail: (kind: String, id: String, title: String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "专辑", onBack = onBack, onSearch = onSearch)
        when {
            loading -> Ios5Loading()
            albums.isEmpty() -> Ios5Empty("没有专辑")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Ios5SectionTitle("全部专辑（${albums.size}）") }
                ios5Rows(albums, key = { it.id }) { _, a ->
                    Ios5Cell(
                        title = a.title,
                        subtitle = listOf(a.artist, a.year.takeIf { it > 0 }?.toString() ?: "").filter { it.isNotBlank() }.joinToString(" · "),
                        count = "${a.songCount}",
                        onClick = { onOpenDetail("album", a.id, a.title) },
                        leading = { Artwork(a.artworkUrl, accentFor(a.id), Modifier.size(44.dp), corner = 6.dp) },
                    )
                }
            }
        }
    }
}

// ---------------- 风格 / 作曲家列表 ----------------

@Composable
fun Ios5GroupsBrowse(
    title: String,
    groups: List<NamedGroup>,
    loading: Boolean,
    searchScope: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenGroup: (name: String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = title, onBack = onBack, onSearch = onSearch)
        when {
            loading -> Ios5Loading()
            groups.isEmpty() -> Ios5Empty("没有$title")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Ios5SectionTitle("$title（${groups.size}）") }
                ios5Rows(groups, key = { it.name }) { _, g ->
                    Ios5Cell(
                        title = g.name,
                        count = "${g.songs.size}",
                        onClick = { onOpenGroup(g.name) },
                    )
                }
            }
        }
    }
}

// ---------------- 风格 / 作曲家详情（内存过滤） ----------------

@Composable
fun Ios5GroupDetail(
    title: String,
    songs: List<Song>,
    player: PlayerUiState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = title, onBack = onBack, onSearch = onSearch)
        if (songs.isEmpty()) {
            Ios5Empty("这里没有歌曲")
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Ios5ActionsHeader(
                        title = "歌曲（${songs.size}）",
                        actions = listOf(
                            Ios5HeaderAction("播放全部", Icons.Filled.PlayArrow, onPlayAll),
                            Ios5HeaderAction("随机播放", Icons.Filled.Shuffle, onShuffleAll),
                        ),
                    )
                }
                ios5Rows(songs, key = { it.id }) { i, s ->
                    Ios5SongRow(s, s.id == player.current.id, player.isPlaying) {
                        onPlaySongs(songs, i)
                    }
                }
            }
        }
    }
}

// ---------------- 文件夹 ----------------

@Composable
fun Ios5FoldersRoot(
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenFolder: (fid: String, title: String) -> Unit,
    player: PlayerUiState,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    Ios5FolderLevel(
        fid = "",
        title = "文件夹",
        onBack = onBack,
        onSearch = onSearch,
        onOpenFolder = onOpenFolder,
        player = player,
        onPlaySongs = onPlaySongs,
    )
}

@Composable
fun Ios5FolderLevel(
    fid: String,
    title: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenFolder: (fid: String, title: String) -> Unit,
    player: PlayerUiState,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    val vm: FolderViewModel = viewModel()
    LaunchedEffect(fid) { vm.load(fid) }
    val st by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = title.ifBlank { "文件夹" }, onBack = onBack, onSearch = onSearch)
        when {
            st.loading -> Ios5Loading()
            st.content == null -> Ios5Empty("打不开这个文件夹")
            else -> {
                val c = st.content!!
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    if (c.folders.isNotEmpty()) {
                        item { Ios5SectionTitle("文件夹（${c.folders.size}）") }
                        ios5Rows(c.folders, key = { it.id }) { _, f ->
                            Ios5Cell(title = f.name, onClick = { onOpenFolder(f.id, f.name) })
                        }
                    }
                    if (c.songs.isNotEmpty()) {
                        item { Ios5SectionTitle("歌曲（${c.songs.size}）") }
                        ios5Rows(c.songs, key = { it.id }) { i, s ->
                            Ios5SongRow(s, s.id == player.current.id, player.isPlaying, showArtwork = false) {
                                onPlaySongs(c.songs, i)
                            }
                        }
                    }
                    if (c.folders.isEmpty() && c.songs.isEmpty()) {
                        item { Ios5Empty("空文件夹") }
                    }
                }
            }
        }
    }
}

// ---------------- 专辑 / 艺人 / 歌单 / 喜欢 详情 ----------------

@Composable
fun Ios5Detail(
    kind: String,
    id: String,
    title: String,
    player: PlayerUiState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenDetail: (kind: String, id: String, title: String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onPlayCollection: (kind: String, id: String) -> Unit,
    onShuffleCollection: (kind: String, id: String) -> Unit,
) {
    val vm: DetailViewModel = viewModel()
    LaunchedEffect(kind, id) { vm.load(kind, id) }
    val st by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = title.ifBlank { st.data?.info?.title ?: "" }, onBack = onBack, onSearch = onSearch)
        when {
            st.loading -> Ios5Loading()
            st.data == null -> Ios5Empty("找不到内容")
            else -> {
                val d = st.data!!
                // 注意：LazyColumn 的 content 不是 @Composable 上下文，remember/状态读取
                // 只能放在这里（普通 @Composable 函数体），下面只做纯发射。
                val mergeContainer = (LocalContext.current.applicationContext as AuroraApplication).container
                val source by mergeContainer.librarySource.collectAsStateWithLifecycle(initialValue = LibrarySource.MEDIastore)
                val mergeRoots by mergeContainer.musicRoots.roots.collectAsStateWithLifecycle(initialValue = emptyList())
                // 扫描预计算好的合并表，直接查，UI 不再分词；两栈各查各的表，永不串台
                val libMerges by mergeContainer.localLibrary.albumMerges.collectAsStateWithLifecycle()
                val fileMerges by mergeContainer.musicRoots.fileMerges.collectAsStateWithLifecycle()
                val mergeRows: List<AlbumRow> = remember(kind, id, d.tracks, libMerges, fileMerges, mergeRoots, source) {
                    if (kind != "album") d.tracks.mapIndexed { i, s -> AlbumRow.Single(s, i) }
                    else if (source == LibrarySource.FILE) {
                        if (mergeEnabledFor(mergeRoots, d.tracks)) mapMergeRows(fileMerges[id], d.tracks)
                        else d.tracks.mapIndexed { i, s -> AlbumRow.Single(s, i) }
                    } else {
                        // MEDIastore 栈：roots 属于文件栈，此处无范围概念，合并表恒生效
                        mapMergeRows(libMerges[id], d.tracks)
                    }
                }
                var mergeExpanded by remember(kind, id, d.tracks) { mutableStateOf(setOf<String>()) }
                val mergeKey: (AlbumRow) -> String = { row ->
                    when (row) {
                        is AlbumRow.Single -> "s:${row.song.id}"
                        is AlbumRow.Group -> "g:${row.items.first().index}:${row.major}"
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxSize().padding(12.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Artwork(d.info.artUrl, d.info.accent, Modifier.size(84.dp), corner = 8.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    androidx.compose.material3.Text(
                                        d.info.title.ifBlank { title },
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = com.aurora.music.ui.ios5.Ios5Colors.TextPrimary,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    if (d.info.subtitle.isNotBlank()) {
                                        androidx.compose.material3.Text(
                                            d.info.subtitle,
                                            fontSize = 13.sp,
                                            color = com.aurora.music.ui.ios5.Ios5Colors.TextSecondary,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    }
                                    androidx.compose.material3.Text(
                                        "${d.info.songCount} 首",
                                        fontSize = 13.sp,
                                        color = com.aurora.music.ui.ios5.Ios5Colors.TextSecondary,
                                    )
                                }
                            }
                        }
                    }
                    if (d.albums.isNotEmpty()) {
                        item { Ios5SectionTitle("专辑（${d.albums.size}）") }
                        ios5Rows(d.albums, key = { it.id }) { _, a ->
                            Ios5Cell(
                                title = a.title,
                                subtitle = a.artist,
                                count = "${a.songCount}",
                                onClick = { onOpenDetail("album", a.id, a.title) },
                                leading = { Artwork(a.artworkUrl, accentFor(a.id), Modifier.size(44.dp), corner = 6.dp) },
                            )
                        }
                    }
                    if (d.tracks.isNotEmpty()) {
                        item {
                            Ios5ActionsHeader(
                                title = "歌曲（${d.tracks.size}）",
                                actions = listOf(
                                    Ios5HeaderAction("播放全部", Icons.Filled.PlayArrow, onClick = { onPlayCollection(kind, id) }),
                                    Ios5HeaderAction("随机播放", Icons.Filled.Shuffle, onClick = { onShuffleCollection(kind, id) }),
                                ),
                            )
                        }
                        if (kind == "album") {
                            // 组卡上下各 4dp；连续单曲连体，只在贴组的一边补 4dp：
                            // 组↔组 = 8，组↔单 = 8，单↔单 = 0，没有双倍
                            var segIdx = 0
                            var prevWasGroup = false
                            while (segIdx < mergeRows.size) {
                                val segRow = mergeRows[segIdx]
                                if (segRow is AlbumRow.Group) {
                                    val gkey = mergeKey(segRow)
                                    val open = gkey in mergeExpanded
                                    val hasCurrent = segRow.items.any { it.song.id == player.current.id }
                                    item(key = gkey) {
                                        Ios5Group(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    segRow.major,
                                                    color = if (hasCurrent) com.aurora.music.ui.ios5.Ios5Colors.IosBlue
                                                    else com.aurora.music.ui.ios5.Ios5Colors.TextPrimary,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                        .clickable { onPlaySongs(d.tracks, segRow.items.first().index) },
                                                )
                                                Icon(
                                                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                    if (open) "收起" else "展开",
                                                    tint = com.aurora.music.ui.ios5.Ios5Colors.TextSecondary,
                                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
                                                        .clickable {
                                                            mergeExpanded = if (open) mergeExpanded - gkey else mergeExpanded + gkey
                                                        }.padding(8.dp),
                                                )
                                            }
                                            if (open) {
                                                Ios5CellDivider()
                                                segRow.items.forEachIndexed { vi, subItem ->
                                                    if (vi > 0) Ios5CellDivider()
                                                    val sub = subItem.song.copy(title = subItem.minor.ifBlank { subItem.song.title })
                                                    Ios5SongRow(
                                                        sub, subItem.song.id == player.current.id, player.isPlaying,
                                                        showArtwork = false, showSubtitle = false,
                                                    ) {
                                                        onPlaySongs(d.tracks, subItem.index)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    segIdx++
                                    prevWasGroup = true
                                } else {
                                    var runEnd = segIdx
                                    while (runEnd < mergeRows.size && mergeRows[runEnd] is AlbumRow.Single) runEnd++
                                    val nextIsGroup = runEnd < mergeRows.size && mergeRows[runEnd] is AlbumRow.Group
                                    val run = mergeRows.subList(segIdx, runEnd).map { it as AlbumRow.Single }
                                    ios5Rows(
                                        run,
                                        key = { "s:${it.song.id}" },
                                        topInset = if (prevWasGroup) 4.dp else 0.dp,
                                        bottomInset = if (nextIsGroup) 4.dp else 0.dp,
                                    ) { _, single ->
                                        Ios5SongRow(
                                            single.song, single.song.id == player.current.id, player.isPlaying,
                                            showArtwork = false, showSubtitle = false,
                                        ) {
                                            onPlaySongs(d.tracks, single.index)
                                        }
                                    }
                                    segIdx = runEnd
                                    prevWasGroup = false
                                }
                            }
                        } else {
                            ios5Rows(d.tracks, key = { it.id }) { i, s ->
                                Ios5SongRow(s, s.id == player.current.id, player.isPlaying) {
                                    onPlaySongs(d.tracks, i)
                                }
                            }
                        }
                        if (st.canLoadMore) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                androidx.compose.foundation.layout.Row(
                                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                ) {
                                    com.aurora.music.ui.ios5.Ios5GlossButton(
                                        if (st.loadingMore) "加载中…" else "加载更多",
                                        { if (!st.loadingMore) vm.loadMore() },
                                        Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Placeholder while the old SEARCH-scope mapping is applied (scope unused beyond nav). */
fun searchScopeFor(route: String?): String = when (route) {
    Ios5Routes.MORE_SONGS, Ios5Routes.PLAYLISTS -> "songs"
    Ios5Routes.MORE_ALBUMS -> "albums"
    Ios5Routes.ARTISTS -> "artists"
    else -> "all"
}
