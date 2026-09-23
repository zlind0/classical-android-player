package com.aurora.music.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.TrackArtworkCache
import com.aurora.music.model.Song

/**
 * 播放态封面：先用 Song.artworkUrl（专辑级）占位，后台解析本文件内嵌图，
 * 命中后切换到该文件的图。不用于列表行（列表用专辑级图即可，省 IO）。
 */
@Composable
fun TrackArtwork(
    song: Song,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Crop,
    fullQuality: Boolean = false,
) {
    val context = LocalContext.current
    var resolved by remember(song.id) { mutableStateOf(TrackArtworkCache.cachedSync(context, song)) }
    LaunchedEffect(song.id, song.artworkUrl, song.path, song.streamUrl) {
        if (resolved.isBlank()) {
            val url = TrackArtworkCache.resolve(context, song)
            if (url.isNotBlank()) resolved = url
        } else {
            // 内存/磁盘已有缓存时仍在后台确认内嵌图（首次可能是旧专辑图占位时不覆盖已有值）
        }
    }
    val url = resolved.ifBlank { song.artworkUrl }
    Artwork(
        url = url,
        accent = song.accent,
        modifier = modifier,
        corner = corner,
        contentScale = contentScale,
        fullQuality = fullQuality,
    )
}

@Composable
fun rememberTrackArtworkUrl(song: Song): String {
    if (song.id.isBlank()) return song.artworkUrl
    val context = LocalContext.current
    var resolved by remember(song.id) { mutableStateOf(TrackArtworkCache.cachedSync(context, song)) }
    LaunchedEffect(song.id, song.artworkUrl, song.path, song.streamUrl) {
        if (resolved.isBlank()) {
            val url = TrackArtworkCache.resolve(context, song)
            if (url.isNotBlank()) resolved = url
        }
    }
    return resolved.ifBlank { song.artworkUrl }
}
