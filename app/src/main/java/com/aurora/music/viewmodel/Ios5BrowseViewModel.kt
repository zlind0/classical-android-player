package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NamedGroup(val name: String, val songs: List<Song>)

data class Ios5BrowseState(
    val loading: Boolean = true,
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val genres: List<NamedGroup> = emptyList(),
    val composers: List<NamedGroup> = emptyList(),
)

fun unknownGenreLabel(): String = "未知风格"
fun unknownComposerLabel(): String = "未知作曲家"

/** Single source for the MORE browse pages: library lists + genre/composer groups. */
class Ios5BrowseViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(Ios5BrowseState())
    val state: StateFlow<Ios5BrowseState> = _state.asStateFlow()

    init {
        viewModelScope.launch { container.libraryReload.drop(1).collect { load() } }
        load()
    }

    fun load() {
        // 全库分页 walk + 分组排序放后台：万首级别在主线程做会掉帧
        viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(loading = true) }
            val songs = container.repository.allLibrarySongs()
            val albums = container.repository.allAlbums()
            val artists = container.repository.allArtists()
            val playlists = container.repository.allPlaylists()
            val genres = songs.groupBy {
                it.genre.trim().ifBlank { unknownGenreLabel() }
            }.map { (k, v) -> NamedGroup(k, v.sortedBy { it.title.lowercase() }) }
                .sortedBy { it.name.lowercase() }
            val composers = songs.groupBy {
                it.composer.trim().ifBlank { unknownComposerLabel() }
            }.map { (k, v) -> NamedGroup(k, v.sortedBy { it.title.lowercase() }) }
                .sortedBy { it.name.lowercase() }
            _state.update {
                it.copy(
                    loading = false,
                    songs = songs.sortedBy { it.title.lowercase() },
                    albums = albums,
                    artists = artists,
                    playlists = playlists,
                    genres = genres,
                    composers = composers,
                )
            }
        }
    }
}
