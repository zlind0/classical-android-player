package com.aurora.music.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aurora.music.R
import com.aurora.music.model.LibraryFilter
import com.aurora.music.model.LibraryLayout
import com.aurora.music.model.LibrarySort
import com.aurora.music.model.Song
import com.aurora.music.ui.components.Artwork
import com.aurora.music.ui.components.SongRow
import com.aurora.music.util.accentFor
import com.aurora.music.viewmodel.LibraryUiState
import kotlinx.coroutines.launch

private data class LibRow(
    val title: String,
    val subtitle: String,
    val art: String,
    val accent: Color,
    val id: String,
    val kind: String,
    val circle: Boolean = false,
    val menu: Boolean = true,
    val badge: String = "",
    val sortPlayCount: Int = 0,
    val sortRecencySec: Long = 0,
)

private class LibActions(
    val isLiked: (String) -> Boolean,
    val onPlay: (LibRow) -> Unit,
    val onShuffle: (LibRow) -> Unit,
    val onQueue: (LibRow) -> Unit,
    val onToggleLike: (LibRow) -> Unit,
    val onDelete: (LibRow) -> Unit,
    val onEditSmart: (LibRow) -> Unit,
    val onDeleteSmart: (LibRow) -> Unit,
    val onExport: (LibRow) -> Unit,
)

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    state: LibraryUiState,
    username: String,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    onFilter: (LibraryFilter) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onToggleLayout: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    // iOS-style embedding: hide the tab rail + identity header, lock to one filter
    showTabs: Boolean = true,
    forceFilter: LibraryFilter? = null,
    titleOverride: String? = null,
    onPlayAll: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    downloadedIds: Set<String>,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onCreateSmart: () -> Unit,
    onEditSmart: (String) -> Unit,
    onDeleteSmart: (String) -> Unit,
    onImportM3u: () -> Unit,
    onExportPlaylist: (String, String, String) -> Unit,
    onOpenFolders: () -> Unit,
    onPlayCollection: (String, String) -> Unit,
    onShuffleCollection: (String, String) -> Unit,
    onQueueCollection: (String, String) -> Unit,
    onToggleLikeKind: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    canDownload: Boolean = true,
    pins: List<com.aurora.music.data.Pin> = emptyList(),
    onEditTags: ((Song) -> Unit)? = null,
    serverTagEditing: Boolean = false,
    onLoadMoreSongs: () -> Unit = {},
    // songs-tab playback covers the whole library, not just the scrolled-in rows
    onPlayAllSongs: (shuffle: Boolean) -> Unit = {},
    onPlaySong: (Song) -> Unit = {},
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var showCreate by remember { mutableStateOf(false) }
    val filter = forceFilter ?: state.filter
    // drill-down into one genre/composer group; resets when the filter changes
    var drill by remember(filter) { mutableStateOf<String?>(null) }
    val sort = state.sort
    val layout = state.layout
    val libColumns = com.aurora.music.ui.theme.LocalUiPrefs.current.libraryColumns.coerceIn(2, 4)
    val context = LocalContext.current
    val avatarFallback = stringResource(R.string.avatar_fallback)
    val actions = LibActions(
        isLiked = { id -> likedIds.contains(id) },
        onPlay = { r -> onPlayCollection(r.id, r.kind) },
        onShuffle = { r -> onShuffleCollection(r.id, r.kind) },
        onQueue = { r -> onQueueCollection(r.id, r.kind) },
        onToggleLike = { r -> onToggleLikeKind(r.id, r.kind) },
        onDelete = { r -> onDeletePlaylist(r.id) },
        onEditSmart = { r -> onEditSmart(r.id) },
        onDeleteSmart = { r -> onDeleteSmart(r.id) },
        onExport = { r -> onExportPlaylist(r.id, r.kind, r.title) },
    )

    Column(Modifier.fillMaxWidth().padding(top = topInset)) {
        // ---- header: identity + stats ----
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showTabs) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
                        .clickable(onClick = onOpenDrawer),
                    contentAlignment = Alignment.Center,
                ) { Text(username.take(2).uppercase().ifBlank { avatarFallback }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimary) }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(titleOverride ?: stringResource(R.string.library_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                val playlistsCountLabel = stringResource(R.string.lib_counts_playlists, state.playlists.size + state.smartPlaylists.size)
                val albumsCountLabel = stringResource(R.string.lib_counts_albums, state.albums.size)
                val artistsCountLabel = stringResource(R.string.lib_counts_artists, state.artists.size)
                val stats = buildList {
                    if (state.playlists.isNotEmpty() || state.smartPlaylists.isNotEmpty()) add(playlistsCountLabel)
                    if (state.albums.isNotEmpty()) add(albumsCountLabel)
                    if (state.artists.isNotEmpty()) add(artistsCountLabel)
                }.joinToString("  ·  ")
                if (stats.isNotBlank()) {
                    Text(stats, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Filled.Search, stringResource(R.string.common_search), modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onOpenSearch).padding(8.dp))
            Icon(Icons.Filled.Add, stringResource(R.string.library_new_playlist), modifier = Modifier.size(40.dp).clip(CircleShape).clickable { showCreate = true }.padding(8.dp))
        }

        if (showCreate) {
            CreatePlaylistDialog(
                onCreate = { name -> onCreatePlaylist(name); showCreate = false },
                onCreateSmart = { showCreate = false; onCreateSmart() },
                onImportM3u = { showCreate = false; onImportM3u() },
                onDismiss = { showCreate = false },
            )
        }

        Spacer(Modifier.height(10.dp))

        // ---- tab rail: icon + label with accent underline, not pills ----
        if (showTabs) {
            // genre/composer browsing lives under More, not the rail
            val visibleTabs = LibraryFilter.entries.filter {
                it != LibraryFilter.GENRES && it != LibraryFilter.COMPOSERS &&
                    (canDownload || it != LibraryFilter.DOWNLOADED)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visibleTabs.size) { i ->
                    val f = visibleTabs[i]
                    LibTab(label = tabLabel(f), icon = tabIcon(f), selected = f == filter) { onFilter(f) }
                }
            }

            Spacer(Modifier.height(6.dp))
        }

        // ---- contextual tool row ----
        if (filter != LibraryFilter.ALL) {
            var sortMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable { sortMenu = true }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.SwapVert, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(sort.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        LibrarySort.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(sortLabel(s)) },
                                onClick = { onSort(s); sortMenu = false },
                                trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (filter != LibraryFilter.SONGS) {
                    Icon(
                        imageVector = if (layout == LibraryLayout.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.List,
                        contentDescription = stringResource(R.string.lib_toggle_layout),
                        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggleLayout).padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        val bottom = contentPadding.calculateBottomPadding() + 24.dp

        if (state.loading && state.albums.isEmpty() && state.playlists.isEmpty() && state.songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(72.dp))
            }
            return@Column
        }

        when (filter) {
            LibraryFilter.ALL -> AllOverview(
                state = state, pins = pins, canDownload = canDownload, bottom = bottom,
                onFilter = onFilter, onOpenDetail = onOpenDetail,
                onOpenFolders = onOpenFolders,
            )
            LibraryFilter.SONGS -> SongsTab(
                state = state, sort = sort, likedIds = likedIds, currentSongId = currentSongId, isPlaying = isPlaying,
                bottom = bottom, canDownload = canDownload, downloadedIds = downloadedIds,
                onAddToQueue = onAddToQueue, onPlayNext = onPlayNext, onToggleLike = onToggleLike,
                onOpenDetail = onOpenDetail, onDownload = onDownload, onRemoveDownload = onRemoveDownload,
                onEditTags = onEditTags, serverTagEditing = serverTagEditing, onLoadMoreSongs = onLoadMoreSongs,
                onPlayAllSongs = onPlayAllSongs, onPlaySong = onPlaySong,
            )
            LibraryFilter.DOWNLOADED -> {
                val dlRows = state.downloadedRows.map { LibRow(it.title, context.getString(R.string.lib_kind_downloaded_fmt, it.kind.replaceFirstChar { c -> c.uppercase() }), it.coverUrl, it.accent, it.id, it.kind) }
                if (dlRows.isEmpty()) {
                    EmptyHint(stringResource(R.string.lib_no_downloads), stringResource(R.string.lib_no_downloads_sub))
                } else {
                    RowsContent(dlRows, layout, libColumns, sort, bottom, actions) { r -> onOpenDetail(r.kind, r.id) }
                }
            }
            LibraryFilter.GENRES, LibraryFilter.COMPOSERS -> {
                GroupBrowser(
                    filter = filter,
                    songs = state.songs,
                    drill = drill,
                    onDrill = { drill = it },
                    onBack = { drill = null },
                    bottom = bottom,
                    likedIds = likedIds,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    canDownload = canDownload,
                    downloadedIds = downloadedIds,
                    onPlaySong = onPlaySong,
                    onToggleLike = onToggleLike,
                    onAddToQueue = onAddToQueue,
                    onPlayNext = onPlayNext,
                    onDownload = onDownload,
                    onRemoveDownload = onRemoveDownload,
                    onOpenDetail = onOpenDetail,
                    onEditTags = onEditTags,
                    serverTagEditing = serverTagEditing,
                )
            }
            else -> {
                val rows = buildRows(state, filter, sort, pins)
                if (rows.isEmpty()) {
                    EmptyHint(stringResource(R.string.library_empty_hint), stringResource(R.string.library_empty_sub, tabLabel(filter)))
                } else {
                    RowsContent(rows, layout, libColumns, sort, bottom, actions) { r ->
                        when (r.kind) {
                            "folders" -> onOpenFolders()
                            else -> onOpenDetail(r.kind, r.id)
                        }
                    }
                }
            }
        }
    }
}

private fun tabIcon(f: LibraryFilter): ImageVector = when (f) {
    LibraryFilter.ALL -> Icons.Filled.Apps
    LibraryFilter.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
    LibraryFilter.ALBUMS -> Icons.Filled.Album
    LibraryFilter.ARTISTS -> Icons.Filled.Person
    LibraryFilter.SONGS -> Icons.Filled.MusicNote
    LibraryFilter.DOWNLOADED -> Icons.Filled.Download
    LibraryFilter.GENRES -> Icons.Filled.Category
    LibraryFilter.COMPOSERS -> Icons.Filled.Mic
}

@Composable
private fun tabLabel(f: LibraryFilter): String = when (f) {
    LibraryFilter.PLAYLISTS -> stringResource(R.string.lib_tab_playlists)
    LibraryFilter.ALBUMS -> stringResource(R.string.lib_tab_albums)
    LibraryFilter.ARTISTS -> stringResource(R.string.lib_tab_artists)
    LibraryFilter.SONGS -> stringResource(R.string.search_filter_songs)
    LibraryFilter.DOWNLOADED -> stringResource(R.string.lib_filter_downloaded)
    LibraryFilter.GENRES -> stringResource(R.string.lib_tab_genres)
    LibraryFilter.COMPOSERS -> stringResource(R.string.lib_tab_composers)
    else -> stringResource(R.string.lib_filter_all)
}

@Composable
private fun sortLabel(s: LibrarySort): String = stringResource(
    when (s) {
        LibrarySort.ALPHABETICAL -> R.string.lib_sort_alpha
        LibrarySort.CREATOR -> R.string.lib_sort_creator
        LibrarySort.MOST_PLAYED -> R.string.lib_sort_most
        else -> R.string.lib_sort_recent
    }
)

@Composable
private fun LibTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.height(3.dp).width(26.dp).clip(RoundedCornerShape(50)).background(
                if (selected) Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
        )
    }
}

// ---- ALL tab: sectioned overview with quick tiles + shelves per type ----

@Composable
private fun AllOverview(
    state: LibraryUiState,
    pins: List<com.aurora.music.data.Pin>,
    canDownload: Boolean,
    bottom: androidx.compose.ui.unit.Dp,
    onFilter: (LibraryFilter) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onOpenFolders: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        // quick access tiles
        item {
            val likedTileTitle = stringResource(R.string.lib_tile_liked)
            val likedTileSub = stringResource(R.string.lib_tile_liked_sub, state.likedSongCount)
            val downloadsTileTitle = stringResource(R.string.lib_tile_downloads)
            val downloadsTileSub = stringResource(R.string.lib_tile_downloads_sub, state.downloadedRows.size)
            val foldersTileTitle = stringResource(R.string.lib_tile_folders)
            val foldersTileSub = stringResource(R.string.lib_tile_folders_sub)
            val tiles = buildList {
                add(QuickTile(likedTileTitle, likedTileSub, Icons.Filled.Favorite, state.likedCover) { onOpenDetail("liked", "liked") })
                if (canDownload) add(QuickTile(downloadsTileTitle, downloadsTileSub, Icons.Filled.Download, "") { onFilter(LibraryFilter.DOWNLOADED) })
                if (state.supportsFolders) add(QuickTile(foldersTileTitle, foldersTileSub, Icons.Filled.Folder, "") { onOpenFolders() })
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { t -> QuickTileCard(t, Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (pins.isNotEmpty()) {
            item { ShelfHeader(stringResource(R.string.lib_pinned), null) {} }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(pins.size) { i ->
                        val p = pins[i]
                        ShelfCard(
                            title = p.title, subtitle = p.kind.replaceFirstChar { it.uppercase() }, art = p.coverUrl,
                            accent = accentFor(p.id), circle = p.kind == "artist", badge = "",
                            width = 112.dp,
                        ) { onOpenDetail(p.kind, p.id) }
                    }
                }
            }
        }

        val playlistCount = state.smartPlaylists.size + state.playlists.size
        if (playlistCount > 0) {
            item { ShelfHeader(stringResource(R.string.lib_tab_playlists), playlistCount) { onFilter(LibraryFilter.PLAYLISTS) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.smartPlaylists.size) { i ->
                        val sp = state.smartPlaylists[i]
                        ShelfCard(sp.name ?: stringResource(R.string.lib_smart), stringResource(R.string.lib_smart), "", accentFor(sp.id ?: "smart"), badge = stringResource(R.string.lib_auto)) {
                            onOpenDetail("smart", sp.id ?: "")
                        }
                    }
                    items(state.playlists.size) { i ->
                        val p = state.playlists[i]
                        ShelfCard(p.title, stringResource(R.string.lib_songs_fmt, p.songCount), p.coverUrl, p.accent) { onOpenDetail("playlist", p.id) }
                    }
                }
            }
        }

        if (state.albums.isNotEmpty()) {
            item { ShelfHeader(stringResource(R.string.lib_tab_albums), state.albums.size) { onFilter(LibraryFilter.ALBUMS) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.albums.size) { i ->
                        val a = state.albums[i]
                        val label = a.typeLabel
                        ShelfCard(a.title, a.artist, a.artworkUrl, accentFor(a.id), badge = if (label == "Album") "" else label.uppercase()) {
                            onOpenDetail("album", a.id)
                        }
                    }
                }
            }
        }

        if (state.artists.isNotEmpty()) {
            item { ShelfHeader(stringResource(R.string.lib_tab_artists), state.artists.size) { onFilter(LibraryFilter.ARTISTS) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.artists.size) { i ->
                        val ar = state.artists[i]
                        ShelfCard(ar.name, "", ar.imageUrl, accentFor(ar.id), circle = true, width = 96.dp, centered = true) {
                            onOpenDetail("artist", ar.id)
                        }
                    }
                }
            }
        }
    }
}

private data class QuickTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val art: String,
    val onClick: () -> Unit,
)

@Composable
private fun QuickTileCard(tile: QuickTile, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .clickable(onClick = tile.onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile.art.isNotBlank()) {
            Artwork(tile.art, MaterialTheme.colorScheme.primary, Modifier.size(38.dp), corner = 10.dp)
        } else {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(tile.icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(19.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(tile.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShelfHeader(title: String, count: Int?, onSeeAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (count != null) {
            Text(
                stringResource(R.string.lib_see_all),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onSeeAll).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ShelfCard(
    title: String,
    subtitle: String,
    art: String,
    accent: Color,
    circle: Boolean = false,
    badge: String = "",
    width: androidx.compose.ui.unit.Dp = 132.dp,
    centered: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(width).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box {
            Artwork(art, accent, Modifier.fillMaxWidth().aspectRatio(1f), corner = if (circle) 200.dp else 14.dp)
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---- SONGS tab: infinite paging + A-Z rail ----

@Composable
private fun SongsTab(
    state: LibraryUiState,
    sort: LibrarySort,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    bottom: androidx.compose.ui.unit.Dp,
    canDownload: Boolean,
    downloadedIds: Set<String>,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onEditTags: ((Song) -> Unit)?,
    serverTagEditing: Boolean,
    onLoadMoreSongs: () -> Unit,
    onPlayAllSongs: (shuffle: Boolean) -> Unit,
    onPlaySong: (Song) -> Unit,
) {
    val songs = sortedSongs(state.songs, sort, state.localPlayCounts)
    if (songs.isEmpty() && !state.loading) {
        EmptyHint(stringResource(R.string.lib_no_songs), stringResource(R.string.lib_no_songs_sub))
        return
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.lib_songs_fmt, songs.size) + if (state.canLoadMoreSongs) "+" else "",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        val accent = MaterialTheme.colorScheme.primary
        val onAccent = if (accent.luminance() > 0.6f) Color.Black else Color.White
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(accent, MaterialTheme.colorScheme.tertiary)))
                .clickable(enabled = songs.isNotEmpty()) { onPlayAllSongs(false) }
                .padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, stringResource(R.string.lib_play_all), tint = onAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.library_play), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = onAccent)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.Shuffle, stringResource(R.string.lib_shuffle_all),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(38.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = songs.isNotEmpty()) { onPlayAllSongs(true) }
                .padding(9.dp),
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.canLoadMoreSongs) {
        snapshotFlow {
            val li = listState.layoutInfo
            (li.visibleItemsInfo.lastOrNull()?.index ?: 0) to li.totalItemsCount
        }.collect { (last, count) ->
            if (state.canLoadMoreSongs && count > 0 && last >= count - 14) onLoadMoreSongs()
        }
    }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
            items(songs.size) { i ->
                val s = songs[i]
                SongRow(
                    s, isPlaying = s.id == currentSongId && isPlaying, isLiked = likedIds.contains(s.id),
                    onClick = { onPlaySong(s) }, onToggleLike = { onToggleLike(s.id) },
                    onAddToQueue = { onAddToQueue(s) }, onPlayNext = { onPlayNext(s) },
                    onGoToAlbum = if (s.albumId.isNotBlank()) ({ onOpenDetail("album", s.albumId) }) else null,
                    onGoToArtist = if (s.artistId.isNotBlank()) ({ onOpenDetail("artist", s.artistId) }) else null,
                    isDownloaded = canDownload && downloadedIds.contains(s.id),
                    onDownload = if (canDownload) ({ onDownload(s) }) else null,
                    onRemoveDownload = if (canDownload) ({ onRemoveDownload(s.id) }) else null,
                    onEditTags = onEditTags?.let { cb -> { cb(s) } },
                    serverTagEditing = serverTagEditing,
                )
            }
            if (state.songsLoadingMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        com.aurora.music.ui.components.LottieLoader(modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
        if (sort == LibrarySort.ALPHABETICAL && songs.size > 30) {
            AlphabetRail(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom),
                onJump = { c ->
                    jumpIndex(songs.map { it.title }, c)?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
                },
            )
        }
    }
}

// ---- shared rows content for playlist/album/artist/download tabs ----

@Composable
private fun RowsContent(
    rows: List<LibRow>,
    layout: LibraryLayout,
    libColumns: Int,
    sort: LibrarySort,
    bottom: androidx.compose.ui.unit.Dp,
    actions: LibActions,
    onOpen: (LibRow) -> Unit,
) {
    if (layout == LibraryLayout.LIST) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
                items(rows.size) { i -> LibListItem(rows[i], actions) { onOpen(rows[i]) } }
            }
            if (sort == LibrarySort.ALPHABETICAL && rows.size > 30) {
                AlphabetRail(
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = bottom),
                    onJump = { c ->
                        jumpIndex(rows.map { it.title }, c)?.let { idx -> scope.launch { listState.scrollToItem(idx) } }
                    },
                )
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(libColumns),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows.size) { i -> LibGridItem(rows[i], actions) { onOpen(rows[i]) } }
        }
    }
}

// ---- A-Z fast scroller ----

@Composable
private fun AlphabetRail(onJump: (Char) -> Unit, modifier: Modifier = Modifier) {
    val letters = remember { ('A'..'Z').toList() + '#' }
    var railHeight by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf<Char?>(null) }
    Column(
        modifier
            .width(24.dp)
            .onSizeChanged { railHeight = it.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    fun pick(y: Float): Char? {
                        if (railHeight <= 0) return null
                        val idx = ((y / railHeight) * letters.size).toInt().coerceIn(0, letters.size - 1)
                        return letters[idx]
                    }
                    val down = awaitFirstDown()
                    pick(down.position.y)?.let { c -> if (c != active) { active = c; onJump(c) } }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        pick(change.position.y)?.let { c -> if (c != active) { active = c; onJump(c) } }
                    }
                    active = null
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        letters.forEach { c ->
            val isActive = active == c
            Text(
                "$c",
                fontSize = if (isActive) 13.sp else 9.sp,
                fontWeight = if (isActive) FontWeight.Black else FontWeight.SemiBold,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

private fun jumpIndex(titles: List<String>, c: Char): Int? {
    if (titles.isEmpty()) return null
    if (c == '#') return titles.indexOfFirst { it.trimStart().firstOrNull()?.isLetter() != true }.takeIf { it >= 0 }
    val exact = titles.indexOfFirst { it.trimStart().firstOrNull()?.uppercaseChar() == c }
    if (exact >= 0) return exact
    // no entries for that letter land on the next one that exists
    return titles.indexOfFirst { (it.trimStart().firstOrNull()?.uppercaseChar() ?: ' ') > c }.takeIf { it >= 0 }
}

@Composable
private fun EmptyHint(title: String, message: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

private fun sortedSongs(songs: List<Song>, sort: LibrarySort, localPlayCounts: Map<String, Int>): List<Song> =
    com.aurora.music.viewmodel.sortLibrarySongs(songs, sort, localPlayCounts)

@Composable
private fun buildRows(state: LibraryUiState, filter: LibraryFilter, sort: LibrarySort, pins: List<com.aurora.music.data.Pin>): List<LibRow> {
    val context = LocalContext.current
    val smartLabel = stringResource(R.string.lib_smart)
    val autoBadge = stringResource(R.string.lib_auto)
    val likedTitle = stringResource(R.string.lib_liked_songs)
    val smart = state.smartPlaylists.map {
        val n = it.rules.orEmpty().size
        LibRow(it.name ?: smartLabel, context.getString(R.string.lib_smart_rules_fmt, n), "", accentFor(it.id ?: "smart"), it.id ?: "", "smart", badge = autoBadge)
    }
    val playlists = smart + state.playlists.map { LibRow(it.title, context.getString(R.string.lib_playlist_fmt, it.songCount), it.coverUrl, it.accent, it.id, "playlist") }
    val albums = state.albums.map {
        val label = it.typeLabel
        LibRow(
            it.title, "$label • ${it.artist}", it.artworkUrl, accentFor(it.id), it.id, "album",
            badge = if (label == "Album") "" else label.uppercase(),
            sortPlayCount = it.playCount, sortRecencySec = it.year.toLong(),
        )
    }
    // no per-artist play/added data from the server aggregate over whatever song pages are currently loaded
    val artists = state.artists.map { ar ->
        val tracks = state.songs.filter { it.artistId == ar.id }
        val plays = tracks.sumOf { maxOf(it.playCount, state.localPlayCounts[it.id] ?: 0) }
        val recency = tracks.maxOfOrNull { it.dateAddedSec } ?: 0L
        LibRow(ar.name, stringResource(R.string.list_artist_fallback), ar.imageUrl, accentFor(ar.id), ar.id, "artist", circle = true, sortPlayCount = plays, sortRecencySec = recency)
    }
    val base = when (filter) {
        LibraryFilter.PLAYLISTS -> playlists
        LibraryFilter.ALBUMS -> albums
        LibraryFilter.ARTISTS -> artists
        else -> emptyList()
    }
    val sorted = when (sort) {
        LibrarySort.ALPHABETICAL -> base.sortedBy { it.title.lowercase() }
        LibrarySort.CREATOR -> base.sortedBy { it.subtitle.lowercase() }
        // playlists carry neither signal so they keep their loaded (roughly alphabetical) order
        LibrarySort.MOST_PLAYED -> base.sortedByDescending { it.sortPlayCount }
        LibrarySort.RECENT -> base.sortedByDescending { it.sortRecencySec }
    }
    if (filter != LibraryFilter.PLAYLISTS) return sorted

    // playlists tab keeps liked songs on top pinned entries stay deduped
    val pinned = pins.map { it.kind to it.id }.toSet()
    val deduped = sorted.filterNot { (it.kind to it.id) in pinned }
    val liked = LibRow(likedTitle, context.getString(R.string.lib_playlist_fmt, state.likedSongCount), state.likedCover, accentFor("liked"), "liked", "liked")
    return listOf(liked) + deduped
}

@Composable
private fun CreatePlaylistDialog(onCreate: (String) -> Unit, onCreateSmart: () -> Unit, onImportM3u: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_new_playlist), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.library_playlist_name)) },
                    singleLine = true,
                )
                TextButton(onClick = onCreateSmart, modifier = Modifier.padding(top = 6.dp)) {
                    Text(stringResource(R.string.library_create_smart))
                }
                TextButton(onClick = onImportM3u) {
                    Text(stringResource(R.string.library_import_m3u))
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.library_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) } },
    )
}

@Composable
private fun LibListItem(row: LibRow, actions: LibActions, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(row.art, row.accent, Modifier.size(56.dp), corner = if (row.circle) 56.dp else 14.dp)
            if (row.badge.isNotBlank()) {
                Text(
                    row.badge,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                        .clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.menu) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Icon(
                    Icons.Filled.MoreVert, stringResource(R.string.player_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable { menuOpen = true }.padding(6.dp),
                )
                CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
            }
        }
    }
}

@Composable
private fun LibGridItem(row: LibRow, actions: LibActions, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(6.dp)) {
        Box {
            Artwork(row.art, row.accent, Modifier.fillMaxWidth().aspectRatio(1f), corner = if (row.circle) 200.dp else 12.dp)
            if (row.badge.isNotBlank()) {
                Text(
                    row.badge,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row.menu) {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    Icon(
                        Icons.Filled.MoreVert, stringResource(R.string.player_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp).clip(CircleShape).clickable { menuOpen = true }.padding(4.dp),
                    )
                    CollectionMenu(row, actions, expanded = menuOpen, onDismiss = { menuOpen = false })
                }
            }
        }
    }
}

@Composable
private fun CollectionMenu(row: LibRow, actions: LibActions, expanded: Boolean, onDismiss: () -> Unit) {
    // liked row is virtual no like/delete just playback
    val isVirtual = row.kind == "liked"
    val isSmart = row.kind == "smart"
    val isPlaylist = row.kind == "playlist"
    val liked = actions.isLiked(row.id)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_play)) }, onClick = { onDismiss(); actions.onPlay(row) }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
        DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_shuffle)) }, onClick = { onDismiss(); actions.onShuffle(row) }, leadingIcon = { Icon(Icons.Filled.Shuffle, null) })
        DropdownMenuItem(text = { Text(stringResource(R.string.library_menu_queue)) }, onClick = { onDismiss(); actions.onQueue(row) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) })
        if (isPlaylist || isSmart || isVirtual) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_menu_export)) },
                onClick = { onDismiss(); actions.onExport(row) },
                leadingIcon = { Icon(Icons.Filled.IosShare, null) },
            )
        }
        if (isSmart) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_menu_edit_rules)) },
                onClick = { onDismiss(); actions.onEditSmart(row) },
                leadingIcon = { Icon(Icons.Filled.Edit, null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_menu_delete)) },
                onClick = { onDismiss(); actions.onDeleteSmart(row) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            )
        }
        if (!isVirtual && !isSmart) {
            DropdownMenuItem(
                text = { Text(if (liked) stringResource(R.string.lib_unlike) else stringResource(R.string.player_like)) },
                onClick = { onDismiss(); actions.onToggleLike(row) },
                leadingIcon = { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
            )
            if (isPlaylist) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_menu_delete_playlist)) },
                    onClick = { onDismiss(); actions.onDelete(row) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}

// ---- genre/composer grouped browsing (More tab) ----

@Composable
private fun GroupBrowser(
    filter: LibraryFilter,
    songs: List<Song>,
    drill: String?,
    onDrill: (String) -> Unit,
    onBack: () -> Unit,
    bottom: androidx.compose.ui.unit.Dp,
    likedIds: Set<String>,
    currentSongId: String,
    isPlaying: Boolean,
    canDownload: Boolean,
    downloadedIds: Set<String>,
    onPlaySong: (Song) -> Unit,
    onToggleLike: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onRemoveDownload: (String) -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onEditTags: ((Song) -> Unit)?,
    serverTagEditing: Boolean,
) {
    val unknown = stringResource(R.string.group_unknown)
    val groups = remember(songs, filter) {
        songs.groupBy {
            val raw = if (filter == LibraryFilter.GENRES) it.genre else it.composer
            raw.trim().ifBlank { unknown }.let { v -> if (v == unknown) v else v }
        }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    if (drill != null) {
        val list = (groups[drill] ?: emptyList()).sortedBy { it.title.lowercase() }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(drill, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.lib_songs_fmt, list.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                contentPadding = PaddingValues(bottom = bottom),
            ) {
                items(list.size) { i ->
                    val s = list[i]
                    SongRow(
                        s, isPlaying = s.id == currentSongId && isPlaying, isLiked = likedIds.contains(s.id),
                        onClick = { onPlaySong(s) }, onToggleLike = { onToggleLike(s.id) },
                        onAddToQueue = { onAddToQueue(s) }, onPlayNext = { onPlayNext(s) },
                        onGoToAlbum = if (s.albumId.isNotBlank()) ({ onOpenDetail("album", s.albumId) }) else null,
                        onGoToArtist = if (s.artistId.isNotBlank()) ({ onOpenDetail("artist", s.artistId) }) else null,
                        isDownloaded = canDownload && downloadedIds.contains(s.id),
                        onDownload = if (canDownload) ({ onDownload(s) }) else null,
                        onRemoveDownload = if (canDownload) ({ onRemoveDownload(s.id) }) else null,
                        onEditTags = onEditTags?.let { cb -> { cb(s) } },
                        serverTagEditing = serverTagEditing,
                    )
                }
            }
        }
        return
    }
    if (groups.isEmpty()) {
        EmptyHint(stringResource(R.string.library_empty_hint), stringResource(R.string.library_empty_sub, tabLabel(filter)))
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        items(groups.keys.toList().size) { i ->
            val name = groups.keys.toList()[i]
            val count = groups[name]?.size ?: 0
            Row(
                Modifier.fillMaxWidth().clickable { onDrill(name) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.lib_songs_fmt, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
