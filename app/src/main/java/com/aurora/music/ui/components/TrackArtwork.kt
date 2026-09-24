package com.aurora.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.aurora.music.R
import com.aurora.music.data.ThemeStyle
import com.aurora.music.data.TrackArtworkCache
import com.aurora.music.model.Song
import com.aurora.music.ui.theme.LocalUiPrefs
import kotlinx.coroutines.isActive

/**
 * 播放态封面：先用 Song.artworkUrl（专辑级）占位，后台解析本文件内嵌图，
 * 命中后切换到该文件的图。不用于列表行（列表用专辑级图即可，省 IO）。
 *
 * 切歌不闪：双层结构，底层只放已确认加载成功的图，切歌时原样挂着，
 * 一帧都不重载；新图在上层透明处加载（loading/error 槽全空，不放任何
 * 占位和兜底图），成功才扶正到底层。全链路没有任何默认图帧。
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
    // 上层新图目标 (songId, url)，url 空 = 无；底层已确认显示的 URL（"" = 默认图）
    var top by remember { mutableStateOf("" to "") }
    var bottomUrl by remember { mutableStateOf("") }
    LaunchedEffect(song.id, song.artworkUrl, song.path, song.streamUrl) {
        val sync = TrackArtworkCache.cachedSync(context, song)
        val url = if (sync.isNotBlank()) sync else TrackArtworkCache.resolve(context, song)
        // 取消后不再写状态，防止慢 IO 把旧歌结果盖到新歌上
        if (!coroutineContext.isActive) return@LaunchedEffect
        if (url.isBlank()) {
            top = "" to ""
            bottomUrl = ""
        } else {
            top = song.id to url
        }
    }
    // 圆角与 Artwork 保持一致，避免叠层边缘错位
    val themeStyle = LocalUiPrefs.current.themeStyle
    val artCorner = if (corner == 0.dp || corner >= 22.dp) corner else when (themeStyle) {
        ThemeStyle.RETRO -> 2.dp
        ThemeStyle.AERO -> 5.dp
        ThemeStyle.GLASS -> 18.dp
        else -> corner
    }
    fun req(url: String) = ImageRequest.Builder(context).data(url)
        .apply { if (fullQuality) size(coil.size.Size.ORIGINAL) }
        .build()
    Box(modifier) {
        if (bottomUrl.isNotBlank()) {
            val b = bottomUrl
            SubcomposeAsyncImage(
                model = req(b),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(artCorner)),
                // 底层换图（内存命中）瞬间完成；上层已同值时收掉上层
                onSuccess = {
                    val (ctid, cturl) = top
                    if (ctid == song.id && cturl == b) top = "" to ""
                },
                onError = { bottomUrl = "" },
            )
        } else {
            DefaultCover(contentScale = contentScale, corner = artCorner)
        }
        // 上层只加载本首的目标；切歌瞬间旧目标即下线，绝不把旧歌盖到新歌上
        val (topId, topTarget) = top
        if (topId == song.id && topTarget.isNotBlank() && topTarget != bottomUrl) {
            val t = topTarget
            SubcomposeAsyncImage(
                model = req(t),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(artCorner)),
                onSuccess = { bottomUrl = t },
                onError = { top = "" to ""; bottomUrl = "" },
            )
        }
    }
}

@Composable
private fun DefaultCover(contentScale: ContentScale, corner: Dp) {
    Image(
        painter = painterResource(R.drawable.default_cover),
        contentDescription = null,
        contentScale = contentScale,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(corner)),
    )
}

@Composable
fun rememberTrackArtworkUrl(song: Song): String {
    if (song.id.isBlank()) return song.artworkUrl
    val context = LocalContext.current
    var displayed by remember { mutableStateOf("" to "") }
    LaunchedEffect(song.id, song.artworkUrl, song.path, song.streamUrl) {
        val sync = TrackArtworkCache.cachedSync(context, song)
        if (sync.isNotBlank()) {
            displayed = song.id to sync
        } else {
            val url = TrackArtworkCache.resolve(context, song)
            displayed = song.id to url
        }
    }
    return displayed.second.ifBlank { song.artworkUrl }
}
