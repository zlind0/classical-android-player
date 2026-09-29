package com.aurora.music.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.HomeData
import com.aurora.music.data.PlayEvent
import com.aurora.music.data.recentAlbumsFromHistory
import com.aurora.music.data.recentArtistsFromHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val data: HomeData = HomeData(),
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as AuroraApplication).container
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { container.offline.collect { load() } }
        viewModelScope.launch { container.libraryReload.drop(1).collect { load() } }
        // 播放历史变化只刷新“最近听过/推荐艺人”，不碰 loading（避免切歌时首页闪 loader）
        viewModelScope.launch {
            container.playHistory.history.collect { events ->
                _state.update { it.copy(data = withRecency(it.data, events)) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val data = container.repository.home()
            _state.update {
                it.copy(loading = false, data = withRecency(data, container.playHistory.history.value))
            }
        }
    }

    private fun withRecency(d: HomeData, events: List<PlayEvent>): HomeData = d.copy(
        recentlyPlayed = recentAlbumsFromHistory(events),
        artists = recentArtistsFromHistory(events),
    )
}
