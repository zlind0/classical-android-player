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
            // 音频接收已并入统一正在播放页；这里只保留 AirPlay 视频镜像。
            if (s.video != null) {
                BackHandler { /* modal: use the stop button */ }
                CastNowPlayingScreen(
                    state = s,
                    onStop = { hub.stopSession() },
                    onSurface = { hub.setVideoSurface(it) },
                )
            }
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
    onStop: () -> Unit,
    onSurface: (android.view.Surface?) -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(BackgroundBrush),
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, onStop)
            val video = state.video
            if (video != null) {
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
