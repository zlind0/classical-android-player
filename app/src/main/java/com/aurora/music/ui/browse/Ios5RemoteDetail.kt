package com.aurora.music.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.AuroraApplication
import com.aurora.music.data.AlbumRow
import com.aurora.music.data.cast.RemoteAlbumGroup
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.Ios5WifiIcon
import com.aurora.music.ui.ios5.Ios5ActionsHeader
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5HeaderAction
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SongRow
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.PlayerUiState

/**
 * 远程艺人/专辑详情：操作逻辑与本地详情一致——
 * 艺人 → 先专辑、再全部歌曲；点专辑 → 专曲目页（返回回到艺人）。
 */
@Composable
fun Ios5RemoteDetail(
    title: String,
    subtitle: String,
    songs: List<Song>,
    albums: List<RemoteAlbumGroup>,
    singleAlbum: RemoteAlbumGroup? = null,
    player: PlayerUiState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
) {
    var openedAlbum by remember(title, songs) { mutableStateOf<RemoteAlbumGroup?>(null) }
    // 从专辑列表直接进来时 singleAlbum 就是当前专辑；从艺人页点专辑时用 openedAlbum。
    val album = openedAlbum ?: singleAlbum
    val shownSongs = album?.songs ?: songs
    val shownTitle = album?.title ?: title
    val shownSubtitle = album?.artist?.ifBlank { subtitle } ?: subtitle
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    // 与本地专辑详情一致：发送端库开启曲名合成时，按 lib-titlemerge 结果分组显示。
    val mergedRows = remember(album) {
        if (album != null && album.mergeTitles) container.remoteLibrary.albumRowsFor(album)
        else emptyList()
    }
    var expanded by remember("remote_album_expand:${album?.id}") {
        mutableStateOf(
            album?.let { container.uiState.expandedOf("remote_album_expand:${it.id}") } ?: emptySet(),
        )
    }
    androidx.compose.runtime.LaunchedEffect(expanded, album?.id) {
        album?.let { container.uiState.saveExpanded("remote_album_expand:${it.id}", expanded) }
    }

    fun closeLevel() {
        if (openedAlbum != null) openedAlbum = null else onBack()
    }
    BackHandler(enabled = openedAlbum != null) { openedAlbum = null }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = shownTitle, onBack = ::closeLevel, onSearch = onSearch)
        if (shownSongs.isEmpty()) {
            Ios5Empty("这里没有歌曲")
        } else {
            LazyColumn(
                state = com.aurora.music.ui.rememberPersistentLazyListState("remote_detail:$shownTitle"),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(
                                (album?.artworkUrl ?: albums.firstOrNull()?.artworkUrl).orEmpty(),
                                accentFor(shownTitle),
                                Modifier.size(84.dp),
                                corner = 8.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Ios5WifiIcon()
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        shownTitle.ifBlank { "远程资料库" },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = Ios5Colors.TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (shownSubtitle.isNotBlank()) {
                                    Text(
                                        shownSubtitle,
                                        fontSize = 13.sp,
                                        color = Ios5Colors.TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    "${shownSongs.size} 首",
                                    fontSize = 13.sp,
                                    color = Ios5Colors.TextSecondary,
                                )
                            }
                        }
                    }
                }
                if (album == null && albums.isNotEmpty()) {
                    item { Ios5SectionTitle("专辑（${albums.size}）") }
                    ios5Rows(albums, key = { it.id }) { _, a ->
                        Ios5Cell(
                            title = a.title,
                            subtitle = a.artist,
                            count = "${a.songs.size}",
                            onClick = { openedAlbum = a },
                            leading = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Ios5WifiIcon()
                                    Spacer(Modifier.width(8.dp))
                                    Artwork(a.artworkUrl, accentFor(a.id), Modifier.size(44.dp), corner = 6.dp)
                                }
                            },
                        )
                    }
                }
                item {
                    Ios5ActionsHeader(
                        title = "歌曲（${shownSongs.size}）",
                        actions = listOf(
                            Ios5HeaderAction(
                                text = "播放全部",
                                icon = Icons.Filled.PlayArrow,
                                onClick = { onPlaySongs(shownSongs, 0) },
                            ),
                            Ios5HeaderAction(
                                text = "随机播放",
                                icon = Icons.Filled.Shuffle,
                                onClick = { onPlaySongs(shownSongs.shuffled(), 0) },
                            ),
                        ),
                    )
                }
                if (album != null && album.mergeTitles && mergedRows.isNotEmpty()) {
                    remoteMergedAlbumRows(
                        rows = mergedRows,
                        player = player,
                        expanded = expanded,
                        onToggle = { key ->
                            expanded = if (key in expanded) expanded - key else expanded + key
                        },
                        onPlayAt = { index -> onPlaySongs(album.songs, index) },
                    )
                } else {
                    ios5Rows(shownSongs, key = { it.id }) { i, s ->
                        Ios5SongRow(
                            s,
                            s.id == player.current.id,
                            player.isPlaying,
                            remote = true,
                        ) {
                            onPlaySongs(shownSongs, i)
                        }
                    }
                }
            }
        }
    }
}

/** 远程专辑的合并行：与本地专辑详情的“大标题可展开 + 小标题连播”一致。 */
private fun LazyListScope.remoteMergedAlbumRows(
    rows: List<AlbumRow>,
    player: PlayerUiState,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onPlayAt: (Int) -> Unit,
) {
    var i = 0
    while (i < rows.size) {
        when (val row = rows[i]) {
            is AlbumRow.Group -> {
                val key = "rg:${row.items.first().index}:${row.major}"
                val open = key in expanded
                val hasCurrent = row.items.any { it.song.id == player.current.id }
                item(key = key) {
                    Ios5Group(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.major,
                                color = if (hasCurrent) Ios5Colors.IosBlue else Ios5Colors.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                                    .clickable { onPlayAt(row.items.first().index) },
                            )
                            Icon(
                                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                if (open) "收起" else "展开",
                                tint = Ios5Colors.TextSecondary,
                                modifier = Modifier.size(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onToggle(key) }
                                    .padding(8.dp),
                            )
                        }
                        if (open) {
                            Ios5CellDivider()
                            row.items.forEachIndexed { vi, sub ->
                                if (vi > 0) Ios5CellDivider()
                                val subSong = sub.song.copy(title = sub.minor.ifBlank { sub.song.title })
                                Ios5SongRow(
                                    subSong,
                                    sub.song.id == player.current.id,
                                    player.isPlaying,
                                    showArtwork = false,
                                    showSubtitle = false,
                                    remote = true,
                                ) { onPlayAt(sub.index) }
                            }
                        }
                    }
                }
                i++
            }

            is AlbumRow.Single -> {
                var end = i
                while (end < rows.size && rows[end] is AlbumRow.Single) end++
                val run = rows.subList(i, end).map { it as AlbumRow.Single }
                ios5Rows(run, key = { "s:${it.song.id}" }) { _, single ->
                    Ios5SongRow(
                        single.song,
                        single.song.id == player.current.id,
                        player.isPlaying,
                        showArtwork = false,
                        showSubtitle = false,
                        remote = true,
                    ) { onPlayAt(single.index) }
                }
                i = end
            }
        }
    }
}

/** 空状态占位，避免远程路由冷启动恢复时闪黑。 */
@Composable
fun Ios5RemoteDetailEmpty(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ios5Colors.CellBg)) {
        Ios5NavBar(title = "远程资料库", onBack = onBack)
        Ios5Empty("这台设备的资料库暂时不可用")
    }
}
