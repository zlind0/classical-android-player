package com.aurora.music.ui.cast

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.receiver.CastReceiverState
import com.aurora.music.ui.ios5.Ios5Sans
import kotlin.math.roundToLong

private val BackgroundBrush = Brush.verticalGradient(
    listOf(Color(0xFF0D0F13), Color(0xFF171B22), Color(0xFF0D0F13)),
)

/**
 * Unified cast now-playing overlay (plan §7). AirPlay audio and mirroring both
 * land here; the session itself lives in the Hub so rotation/recreation keeps
 * playing without renegotiating.
 */
@Composable
fun StandardCastOverlay() {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val hub = container.castHub
    val state by hub.state.collectAsStateWithLifecycle()
    when (val s = state) {
        CastReceiverState.Idle -> Unit
        is CastReceiverState.Connecting -> ConnectingScreen(s)
        is CastReceiverState.Playing -> {
            BackHandler { /* modal: use the stop button */ }
            CastNowPlayingScreen(
                state = s,
                onPlayPause = { if (s.playing) hub.pause() else hub.play() },
                onNext = { hub.next() },
                onPrevious = { hub.previous() },
                onStop = { hub.stopSession() },
                onSurface = { hub.setVideoSurface(it) },
            )
        }
    }
}

@Composable
private fun ConnectingScreen(state: CastReceiverState.Connecting) {
    Box(
        Modifier.fillMaxSize().background(BackgroundBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Cast, null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "${state.protocol.label} 正在连接",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Ios5Sans,
            )
            if (state.senderName.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.senderName,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 14.sp,
                    fontFamily = Ios5Sans,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CastNowPlayingScreen(
    state: CastReceiverState.Playing,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onStop: () -> Unit,
    onSurface: (android.view.Surface?) -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(BackgroundBrush),
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, onStop)
            if (state.video != null) {
                val video = state.video
                val ratio = if (video.width > 0 && video.height > 0) {
                    video.width.toFloat() / video.height
                } else {
                    16f / 9f
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CastVideoSurface(
                        onSurface = onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .padding(horizontal = 12.dp),
                    )
                }
            } else {
                AudioContent(
                    state = state,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun TopBar(state: CastReceiverState.Playing, onStop: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Cast, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.protocol.label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Ios5Sans,
            )
            if (state.senderName.isNotBlank()) {
                Text(
                    state.senderName,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontFamily = Ios5Sans,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onStop) {
            Icon(Icons.Filled.Stop, "结束接收", tint = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun AudioContent(
    state: CastReceiverState.Playing,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (state.media.artworkUri.isNotBlank()) {
                SubcomposeAsyncImage(
                    model = state.media.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = { },
                    error = { ArtworkPlaceholder() },
                )
            } else {
                ArtworkPlaceholder()
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            state.media.title.ifBlank { "投屏音频" },
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = Ios5Sans,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (state.media.artist.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                state.media.artist,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                fontFamily = Ios5Sans,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.media.album.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                state.media.album,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 13.sp,
                fontFamily = Ios5Sans,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(22.dp))
        Progress(state)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ControlButton(Icons.Filled.SkipPrevious, "上一首", enabled = state.capabilities.canPrevious, onClick = onPrevious)
            Spacer(Modifier.size(22.dp))
            PlayPauseButton(playing = state.playing, enabled = state.capabilities.canPlayPause, onClick = onPlayPause)
            Spacer(Modifier.size(22.dp))
            ControlButton(Icons.Filled.SkipNext, "下一首", enabled = state.capabilities.canNext, onClick = onNext)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ArtworkPlaceholder() {
    Icon(
        Icons.Filled.Cast, null,
        tint = Color.White.copy(alpha = 0.35f),
        modifier = Modifier.size(72.dp),
    )
}

@Composable
private fun Progress(state: CastReceiverState.Playing) {
    val duration = state.durationMs.coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (duration > 0) {
        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = if (dragging) dragValue else fraction,
            onValueChange = { if (state.capabilities.canSeek) { dragging = true; dragValue = it } },
            onValueChangeFinished = { dragging = false },
            enabled = state.capabilities.canSeek && duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.9f),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                disabledThumbColor = Color.White.copy(alpha = 0.7f),
                disabledActiveTrackColor = Color.White.copy(alpha = 0.7f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (dragging) (dragValue * duration).roundToLong() else state.positionMs), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontFamily = Ios5Sans)
            Text(formatTime(duration), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontFamily = Ios5Sans)
        }
    }
}

@Composable
private fun PlayPauseButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.06f)),
    ) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            if (playing) "暂停" else "播放",
            tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.3f),
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            icon, description,
            tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.25f),
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun CastVideoSurface(
    onSurface: (android.view.Surface?) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurface(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onSurface(null)
                    }
                })
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose { onSurface(null) }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
