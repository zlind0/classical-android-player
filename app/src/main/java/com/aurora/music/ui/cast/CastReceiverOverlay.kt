package com.aurora.music.ui.cast

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.CastContentType
import com.aurora.music.data.cast.CastReceiveState
import com.aurora.music.ui.ios5.Ios5ButtonTone
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5SmallButton
import com.aurora.music.ui.player.Ios5PlayerPage
import com.aurora.music.viewmodel.PlayerUiState
import com.aurora.music.viewmodel.RepeatMode

/**
 * 接收投屏的全屏浮层。挂在整个 App 的根 Box 最后，
 * 不论当前在哪个 Tab / 阅读器 / 播放页，都盖在最上面。
 */
@Composable
fun CastReceiverOverlay() {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val state by container.castReceiver.state.collectAsStateWithLifecycle()
    when (val s = state) {
        CastReceiveState.Idle -> Unit
        is CastReceiveState.Incoming -> IncomingScreen(s, onAccept = { container.castReceiver.accept() }, onReject = { container.castReceiver.reject() })
        is CastReceiveState.Loading -> LoadingScreen(s)
        is CastReceiveState.Music -> MusicScreen(s)
        is CastReceiveState.Ebook -> EbookScreen(s)
    }
}

@Composable
private fun IncomingScreen(
    s: CastReceiveState.Incoming,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    BackHandler { onReject() }
    Box(
        Modifier.fillMaxSize().background(Ios5Colors.linenBrush),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Cast, null, tint = Color(0xFF0A60D6), modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "投屏请求",
                color = Color(0xFF1A1D22),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${s.offer.senderName.ifBlank { s.offer.host }} 想投到这设备",
                color = Color(0xFF3E444D),
                fontSize = 14.sp,
                fontFamily = Ios5Sans,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            val kind = if (s.offer.contentType == CastContentType.EBOOK) "电子书" else "音乐"
            val detail = listOf(s.offer.title, s.offer.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
            Text(
                if (detail.isBlank()) kind else "$kind · $detail",
                color = Color(0xFF6B7280),
                fontSize = 12.sp,
                fontFamily = Ios5Sans,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ios5SmallButton(
                    text = "拒绝",
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    tone = Ios5ButtonTone.Grey,
                )
                Ios5SmallButton(
                    text = "接受投屏",
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                    tone = Ios5ButtonTone.Blue,
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen(s: CastReceiveState.Loading) {
    Box(Modifier.fillMaxSize().background(Ios5Colors.linenBrush), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Cast, null, tint = Color(0xFF0A60D6), modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                "正在连接 ${s.senderName}…",
                color = Color(0xFF3E444D),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
            )
        }
    }
}

@Composable
private fun MusicScreen(s: CastReceiveState.Music) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val controller = container.castReceiver
    BackHandler { controller.stop() }
    var seekFrac by remember(s.song.id) { mutableFloatStateOf(-1f) }
    val ui = PlayerUiState(
        current = s.song,
        queue = s.queue,
        isPlaying = s.playing,
        positionSec = s.positionMs / 1000f,
        shuffle = s.shuffle,
        repeat = when (s.repeat) {
            "ONE" -> RepeatMode.ONE
            "ALL" -> RepeatMode.ALL
            else -> RepeatMode.OFF
        },
        likedIds = if (s.liked) setOf(s.song.id) else emptySet(),
        currentIndex = s.index,
    )
    Box(Modifier.fillMaxSize()) {
        Ios5PlayerPage(
            state = ui,
            onCollapse = { controller.stop() },
            onTogglePlay = { controller.togglePlay() },
            onNext = { controller.next() },
            onPrevious = { controller.previous() },
            onSeek = { f ->
                seekFrac = f
                controller.seekFraction(f, false)
            },
            onSeekFinished = {
                if (seekFrac >= 0f) controller.seekFraction(seekFrac, true)
            },
            onToggleLike = { controller.toggleLike() },
            onToggleShuffle = { controller.toggleShuffle() },
            onCycleRepeat = { controller.cycleRepeat() },
            onOpenQueue = {},
            collapseLabel = "结束投屏",
            remoteMode = true,
        )
    }
}

@Composable
private fun EbookScreen(s: CastReceiveState.Ebook) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val controller = container.castReceiver
    BackHandler { controller.stop() }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().background(Ios5Colors.barBrush)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(50.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ios5SmallButton(
                text = "退出投屏",
                onClick = { controller.stop() },
                icon = Icons.Filled.Stop,
                tone = Ios5ButtonTone.Red,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.bookTitle.ifBlank { "阅读投屏" },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Ios5Sans,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val info = listOf(s.chapterTitle, s.anchor).filter { it.isNotBlank() }.joinToString(" · ")
                if (info.isNotBlank()) {
                    Text(
                        info,
                        color = Color(0xFFC7CBD2),
                        fontSize = 11.sp,
                        fontFamily = Ios5Sans,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                s.senderName,
                color = Color(0xFFC7CBD2),
                fontSize = 11.sp,
                fontFamily = Ios5Sans,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(90.dp),
                textAlign = TextAlign.End,
            )
        }
        val web = remember { WebView(context) }
        LaunchedEffect(s.pageUrl) { web.loadUrl(s.pageUrl) }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
            }
        }
        AndroidView(
            factory = {
                web.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = WebViewClient()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
