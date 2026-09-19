package com.aurora.music.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SongRow
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.SearchViewModel

@Composable
fun Ios5SearchPage(
    scope: String,
    player: PlayerUiState,
    onBack: () -> Unit,
    onOpenDetail: (kind: String, id: String, title: String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    val vm: SearchViewModel = viewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val recents by vm.recentSearches.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "搜索", onBack = onBack)
        TextField(
            value = st.query,
            onValueChange = vm::onQuery,
            placeholder = { Text("曲名 / 艺人 / 专辑", fontSize = 15.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
                .clip(RoundedCornerShape(10.dp)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        if (st.loading) {
            Ios5Loading()
            return
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            val r = st.results
            val showSongs = scope == "all" || scope == "songs"
            val showAlbums = scope == "all" || scope == "albums"
            val showArtists = scope == "all" || scope == "artists"
            if (st.query.isBlank()) {
                if (recents.isNotEmpty()) {
                    item { Ios5SectionTitle("最近搜索") }
                    item {
                        Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                            recents.forEachIndexed { i, q ->
                                if (i > 0) Ios5CellDivider()
                                Ios5Cell(title = q, showChevron = false, onClick = { vm.onQuery(q) })
                            }
                        }
                    }
                } else {
                    item { Ios5Empty("输入关键词开始搜索") }
                }
                return@LazyColumn
            }
            if (showSongs && r.songs.isNotEmpty()) {
                item { Ios5SectionTitle("歌曲（${r.songs.size}）") }
                ios5Rows(r.songs, key = { it.id }) { i, s ->
                    Ios5SongRow(s, s.id == player.current.id, player.isPlaying) {
                        vm.commit()
                        onPlaySongs(r.songs, i)
                    }
                }
            }
            if (showAlbums && r.albums.isNotEmpty()) {
                item { Ios5SectionTitle("专辑（${r.albums.size}）") }
                ios5Rows(r.albums, key = { it.id }) { _, a ->
                    Ios5Cell(
                        title = a.title,
                        subtitle = a.artist,
                        onClick = { vm.commit(); onOpenDetail("album", a.id, a.title) },
                        leading = { Artwork(a.artworkUrl, accentFor(a.id), Modifier.size(44.dp), corner = 6.dp) },
                    )
                }
            }
            if (showArtists && r.artists.isNotEmpty()) {
                item { Ios5SectionTitle("艺人（${r.artists.size}）") }
                ios5Rows(r.artists, key = { it.id }) { _, a ->
                    Ios5Cell(
                        title = a.name,
                        onClick = { vm.commit(); onOpenDetail("artist", a.id, a.name) },
                        leading = { Artwork(a.imageUrl, accentFor(a.id), Modifier.size(40.dp), corner = 20.dp) },
                    )
                }
            }
            if (scope == "all" && r.playlists.isNotEmpty()) {
                item { Ios5SectionTitle("歌单（${r.playlists.size}）") }
                ios5Rows(r.playlists, key = { it.id }) { _, p ->
                    Ios5Cell(
                        title = p.title,
                        subtitle = p.subtitle,
                        onClick = { vm.commit(); onOpenDetail("playlist", p.id, p.title) },
                        leading = { Artwork(p.coverUrl, p.accent, Modifier.size(40.dp), corner = 6.dp) },
                    )
                }
            }
            if (r.songs.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty() && r.playlists.isEmpty()) {
                item { Ios5Empty("没有找到 “${st.query}”") }
            }
        }
    }
}
