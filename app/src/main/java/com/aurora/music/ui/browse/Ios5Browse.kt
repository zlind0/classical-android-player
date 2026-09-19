package com.aurora.music.ui.browse

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
                    Spacer(Modifier.height(8.dp))
                    Ios5PlayButtons(onPlayAll = onPlayAll, onShuffleAll = onShuffleAll)
                }
                item { Ios5SectionTitle("歌曲（${songs.size}）") }
                ios5Rows(songs, key = { it.id }) { i, s ->
                    Ios5SongRow(s, s.id == player.current.id, player.isPlaying) {
                        onPlaySongs(songs, i)
                    }
                }
            }
        }
    }
}

/** 播放全部 + 随机播放：文字配经典图标，详情页标配。 */
@Composable
private fun Ios5PlayButtons(
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        com.aurora.music.ui.ios5.Ios5GlossButton(
            "播放全部", onClick = onPlayAll,
            modifier = Modifier.weight(1f), icon = Icons.Filled.PlayArrow,
        )
        com.aurora.music.ui.ios5.Ios5GlossButton(
            "随机播放", onClick = onShuffleAll,
            modifier = Modifier.weight(1f), icon = Icons.Filled.Shuffle,
        )
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
                        Spacer(Modifier.height(8.dp))
                        Ios5PlayButtons(
                            onPlayAll = { onPlayCollection(kind, id) },
                            onShuffleAll = { onShuffleCollection(kind, id) },
                        )
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
                        item { Ios5SectionTitle("歌曲（${d.tracks.size}）") }
                        // 专辑内：同一张碟，只留曲名
                        val minimal = kind == "album"
                        ios5Rows(d.tracks, key = { it.id }) { i, s ->
                            Ios5SongRow(
                                s, s.id == player.current.id, player.isPlaying,
                                showArtwork = !minimal, showSubtitle = !minimal,
                            ) {
                                onPlaySongs(d.tracks, i)
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
