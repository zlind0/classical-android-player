package com.aurora.music.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.RemoteVideoDirWire
import com.aurora.music.data.cast.RemoteVideoListingWire
import com.aurora.music.data.cast.RemoteVideoRootWire
import com.aurora.music.data.cast.RemoteVideoWire
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5Type
import com.aurora.music.ui.rememberPersistentLazyListState
import kotlinx.coroutines.launch

/**
 * “视频”Tab：列出各台在线设备共享的短视频根目录。
 *
 * 快照里只有根目录这一行信息，视频文件列表与随机批次都走实时接口，
 * 所以书库里有十万个视频也不会拖慢 `/library/all`。
 */
@Composable
fun VideosTab(
    onOpenRoot: (deviceId: String, rootKey: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val libraries by container.remoteLibrary.libraries.collectAsStateWithLifecycle()
    val devices = libraries.filter { it.videoRoots.isNotEmpty() }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "视频")
        LazyColumn(
            state = rememberPersistentLazyListState("tab:videos"),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (devices.isEmpty()) {
                item { Ios5Empty("没有共享的视频目录。\n在桌面端把文件夹添加为“短视频”即可。") }
            }
            devices.forEach { lib ->
                item(key = "video-head-${lib.deviceId}") {
                    Ios5SectionTitle(
                        "${lib.deviceName}${if (lib.online) "" else "（离线）"} - 视频（${lib.videoRoots.size}）",
                    )
                }
                item(key = "video-roots-${lib.deviceId}") {
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        lib.videoRoots.forEachIndexed { index, root ->
                            if (index > 0) Ios5CellDivider()
                            VideoRootCell(
                                root = root,
                                enabled = lib.online,
                                onClick = {
                                    onOpenRoot(lib.deviceId, root.key, root.displayName)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoRootCell(
    root: RemoteVideoRootWire,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ios5Colors.CellBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Videocam, null, tint = Ios5Colors.TextSecondary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                root.displayName,
                color = Ios5Colors.TextPrimary,
                fontSize = Ios5Type.CellTitle,
                maxLines = 1,
            )
            if (!enabled) {
                Text("离线", color = Ios5Colors.TextSecondary, fontSize = 12.sp)
            }
        }
        Text(
            "${root.videoCount} 个视频",
            color = Ios5Colors.TextSecondary,
            fontSize = 13.sp,
        )
    }
}

/**
 * 视频文件浏览器：目录可进入，每个目录右侧有随机按钮（在该目录及其子目录里随机播放），
 * 点视频则从该目录的视频列表里接着往下滑。
 */
@Composable
fun RemoteVideoBrowseScreen(
    deviceId: String,
    rootKey: String,
    dir: String,
    title: String,
    onBack: () -> Unit,
    onOpenDir: (dir: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val scope = rememberCoroutineScope()

    var listing by remember { mutableStateOf<RemoteVideoListingWire?>(null) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(deviceId, rootKey, dir, reloadKey) {
        loading = true
        message = ""
        listing = container.remoteLibrary.fetchVideoListing(deviceId, rootKey, dir)
        if (listing == null) message = "无法连接这台设备，请检查是否在线"
        loading = false
    }

    fun shuffle(targetDir: String, targetTitle: String) {
        scope.launch {
            message = ""
            if (!container.videoFeed.openShuffled(deviceId, rootKey, targetDir, targetTitle)) {
                message = "这个目录里没有可播放的视频"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(
            title = title.ifBlank { "视频" },
            onBack = onBack,
            actionLabel = "随机",
            onAction = { shuffle(dir, title.ifBlank { "视频" }) },
        )
        if (loading) {
            Ios5Loading()
            return@Column
        }
        if (message.isNotBlank()) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(message, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
            }
        }
        val current = listing
        if (current == null) {
            Ios5Empty("读取目录失败")
            return@Column
        }
        if (current.dirs.isEmpty() && current.videos.isEmpty()) {
            Ios5Empty("这个目录里没有视频")
            return@Column
        }
        LazyColumn(
            state = rememberPersistentLazyListState("video:$deviceId:$rootKey:$dir"),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (current.dirs.isNotEmpty()) {
                item { Ios5SectionTitle("文件夹（${current.dirs.size}）") }
                item {
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        current.dirs.forEachIndexed { index, folder ->
                            if (index > 0) Ios5CellDivider(startInset = 44.dp)
                            DirRow(
                                folder = folder,
                                onOpen = { onOpenDir(folder.path, folder.name) },
                                onShuffle = { shuffle(folder.path, folder.name) },
                            )
                        }
                    }
                }
            }
            if (current.videos.isNotEmpty()) {
                item { Ios5SectionTitle("视频（${current.videos.size}）") }
                item {
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        current.videos.forEachIndexed { index, video ->
                            if (index > 0) Ios5CellDivider()
                            VideoFileCell(
                                video = video,
                                onClick = {
                                    container.videoFeed.open(
                                        deviceId = deviceId,
                                        rootKey = rootKey,
                                        dir = dir,
                                        title = title.ifBlank { "视频" },
                                        items = current.videos,
                                        index = index,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirRow(
    folder: RemoteVideoDirWire,
    onOpen: () -> Unit,
    onShuffle: () -> Unit,
) {
    Ios5Cell(
        title = folder.name,
        subtitle = buildString {
            append("${folder.deepCount} 个视频")
            if (folder.subdirCount > 0) append(" · ${folder.subdirCount} 个子目录")
        },
        onClick = onOpen,
        leading = {
            Icon(
                Icons.Filled.Folder,
                null,
                tint = Ios5Colors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        },
        trailing = {
            Box(
                Modifier.size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.05f))
                    .clickable(onClick = onShuffle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    "随机播放这个文件夹",
                    tint = Ios5Colors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
private fun VideoFileCell(video: RemoteVideoWire, onClick: () -> Unit) {
    Ios5Cell(
        title = video.displayTitle,
        subtitle = video.name,
        count = formatVideoSize(video.size),
        leading = {
            Icon(
                Icons.Filled.Videocam,
                null,
                tint = Ios5Colors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
    )
}

fun formatVideoSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
