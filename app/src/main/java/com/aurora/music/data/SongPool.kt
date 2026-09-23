package com.aurora.music.data

import com.aurora.music.model.Song

// 分析引擎（Sonic/ReplayGain）只依赖“当前源的歌单”，不直连 LocalLibrary，
// 双栈切换时引擎自动跟随当前 source。
interface SongPool {
    val songs: List<Song>
    suspend fun ensureLoaded()
    suspend fun refresh()
}
