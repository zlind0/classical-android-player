package com.aurora.music.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.audiobook.AudiobookRoot
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5MiniButton
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 有声书来源：与电子书来源同一套交互（权限先行 → 目录列表 → 添加/扫描/删除）。
// 每个直接子目录 = 一本书；只认音乐扫描白名单的音频后缀（含 m4b）。
@Composable
fun AudiobookSourcesScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    confirm: (String) -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val roots by container.audiobookStore.roots.collectAsStateWithLifecycle()
    val counts by container.audiobookStore.counts.collectAsStateWithLifecycle()
    val progress by container.audiobookStore.scanProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    val owner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val ctx = LocalContext.current
    resumeTick
    val readOk = remember(resumeTick) { com.aurora.music.data.hasStorageRead(ctx) }
    val fullOk = remember(resumeTick) { com.aurora.music.data.canScanStorage(ctx) }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    if (picking) {
        FolderPickerScreen(
            contentPadding = contentPadding,
            onBack = { picking = false },
            onPicked = { path, name, type ->
                scope.launch {
                    val added = container.audiobookStore.addRoot(path, name, type)
                    if (added == null) {
                        confirm("这个目录已经在书库里了")
                    } else {
                        picking = false
                        confirm("已添加有声书目录")
                        scanJob?.cancel()
                        scanJob = scope.launch {
                            container.audiobookScanner.scan(added) { container.audiobookStore.scanProgress.value = it }
                            val n = container.audiobookStore.books.value.count { it.rootId == added.id }
                            confirm(if (n > 0) "找到 $n 本有声书" else "这个文件夹里没有有声书（子目录里要有音频文件）")
                        }
                    }
                }
            },
        )
        return
    }

    val bottomPad = contentPadding.calculateBottomPadding()
    val needPerm = !fullOk
    val showAllFilesRow = com.aurora.music.data.needsAllFilesRow()

    Ios5SettingsPage(title = "有声书来源", onBack = onBack) {
        if (needPerm) {
            ios5Section("需要所有文件访问权") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Ios5StaticText("浏览文件夹、扫描有声书需要所有文件访问权。")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        if (!readOk) {
                            Ios5GlossButton(
                                text = "授予读取权限",
                                onClick = { readLauncher.launch(com.aurora.music.data.storageReadPermission()) },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (showAllFilesRow) {
                            Ios5GlossButton(
                                text = "打开所有文件设置",
                                onClick = { com.aurora.music.data.openAllFilesSettings(ctx) },
                            )
                        }
                    }
                }
            }
        }
        if (roots.isEmpty()) {
            ios5Section("扫描目录") {
                Ios5StaticText("还没有添加目录。添加存放有声书的文件夹——所选目录的每个直接子目录算一本书，只会扫描这些地方。")
            }
        } else {
            roots.forEach { root ->
                val count = counts[root.id] ?: 0
                val active = progress.running && progress.rootId == root.id
                ios5Section(root.displayName) {
                    AudiobookRootCard(
                        root = root,
                        count = count,
                        scanning = if (active) "${progress.found} · ${progress.current}" else "",
                        indeterminate = active && progress.total <= 0,
                        onScan = {
                            scanJob?.cancel()
                            scanJob = scope.launch {
                                container.audiobookScanner.scan(root) { container.audiobookStore.scanProgress.value = it }
                                confirm("扫描完成")
                            }
                        },
                        onToggle = { v -> scope.launch { container.audiobookStore.setRootEnabled(root.id, v) } },
                        onRemove = {
                            scanJob?.cancel()
                            scope.launch {
                                container.audiobookStore.removeRoot(root.id)
                                confirm("已移除有声书目录")
                            }
                        },
                    )
                }
            }
        }
        ios5Section("添加有声书文件夹") {
            Ios5NavRow(title = "添加", subtitle = "内部存储、SD 卡或 USB", onClick = { picking = true })
        }
        ios5FootNote("扫描只会进入上面的文件夹；每个直接子目录算一本书，书内文件按音轨号 + 文件名自然序排成章节。")
        item { Spacer(Modifier.height(bottomPad)) }
    }
}

@Composable
private fun AudiobookRootCard(
    root: AudiobookRoot,
    count: Int,
    scanning: String,
    indeterminate: Boolean,
    onScan: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val timeSuffix = if (root.lastScanTime > 0) " · " + runCatching {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(root.lastScanTime))
    }.getOrDefault("") else ""
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(root.rootPath, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count 本$timeSuffix", color = Ios5Colors.TextSecondary, fontSize = 13.sp)
        }
        Ios5SwitchRow(
            title = root.displayName,
            subtitle = "",
            checked = root.enabled,
            onCheckedChange = onToggle,
        )
        if (scanning.isNotBlank() || indeterminate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            if (scanning.isNotBlank()) Ios5StaticText("扫描中…$scanning")
        }
        Ios5CellDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                Ios5MiniButton("扫描", Icons.Filled.Refresh, onScan)
            }
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                Ios5MiniButton("删除", Icons.Filled.Delete, onRemove, danger = true)
            }
        }
    }
}
