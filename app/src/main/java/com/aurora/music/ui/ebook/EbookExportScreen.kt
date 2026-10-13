package com.aurora.music.ui.ebook

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.ebook.export.EbookExportRunner
import com.aurora.music.data.ebook.export.EbookExportService
import com.aurora.music.data.ebook.export.ExportPhase
import com.aurora.music.tts.MsVoices
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5CheckRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Loading
import com.aurora.music.ui.ios5.Ios5MiniButton
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.viewmodel.EbookExportViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 电子书转有声书：章节多选 + 语音/语速语调 + 目的地（有声书来源二选一）+ 线程/覆盖。
// 转换走前台 Service（切界面/切后台不停），完成后重扫该来源，在有声书里直接听。
@Composable
fun EbookExportScreen(
    bookPath: String,
    onBack: () -> Unit,
    onOpenAudiobooks: () -> Unit,
    onOpenAudiobookSources: () -> Unit,
    confirm: (String) -> Unit,
    vm: EbookExportViewModel = viewModel(),
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val scope = rememberCoroutineScope()

    val roots by container.audiobookStore.roots.collectAsStateWithLifecycle()
    val enabledRoots = remember(roots) {
        roots.filter { it.enabled }.sortedBy { it.displayName.lowercase() }
    }
    val exportPrefs by container.settingsStore.ebookExportPrefs.collectAsStateWithLifecycle(
        initialValue = SettingsStore.EbookExportPrefs(),
    )
    var rootId by remember { mutableStateOf(-1L) }
    LaunchedEffect(enabledRoots, exportPrefs.lastRootId) {
        if (rootId < 0) {
            rootId = enabledRoots.firstOrNull { it.id == exportPrefs.lastRootId }?.id
                ?: enabledRoots.firstOrNull()?.id ?: -1L
        } else if (enabledRoots.none { it.id == rootId }) {
            rootId = enabledRoots.firstOrNull()?.id ?: -1L
        }
    }
    val selectedRoot = enabledRoots.firstOrNull { it.id == rootId }

    val running by EbookExportRunner.running.collectAsStateWithLifecycle()
    val progress by EbookExportRunner.progress.collectAsStateWithLifecycle()
    val result by EbookExportRunner.result.collectAsStateWithLifecycle()
    val doneRootId by EbookExportRunner.doneRootId.collectAsStateWithLifecycle()

    var showChapters by remember { mutableStateOf(false) }

    LaunchedEffect(bookPath) { vm.loadBook(bookPath) }
    // 落文件即重扫该来源，有声书里立刻能听。
    LaunchedEffect(doneRootId) {
        val rid = doneRootId ?: return@LaunchedEffect
        EbookExportRunner.consumeDoneRoot()
        scope.launch(Dispatchers.IO) {
            val root = container.audiobookStore.roots.value.firstOrNull { it.id == rid }
            if (root != null) {
                runCatching { container.audiobookScanner.scan(root) {} }
            }
            withContext(Dispatchers.Main) { confirm("转换完成，已加入有声书") }
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        selectedRoot?.let { vm.startExport(File(it.rootPath), it.id) }
    }
    fun tryStart() {
        val r = selectedRoot ?: return
        EbookExportRunner.clearResult()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startExport(File(r.rootPath), r.id)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Ios5NavBar(title = "转有声书", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            // ---- 书 ----
            item { Ios5SectionTitle("电子书") }
            item {
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    when {
                        vm.parsing -> Ios5Loading()
                        vm.parseError != null -> Text(
                            vm.parseError.orEmpty(),
                            color = androidx.compose.ui.graphics.Color(0xFFD63A3A),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                        vm.bookTitle.isNotBlank() -> Column(Modifier.padding(12.dp)) {
                            Text(
                                "《${vm.bookTitle}》" +
                                    (vm.bookAuthor.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                color = Ios5Colors.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "共 ${vm.segments.size} 段，已选 ${vm.selected.size} 段",
                                color = Ios5Colors.TextSecondary,
                                fontSize = 13.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Ios5MiniButton(
                                text = "选择章节（${vm.selected.size}/${vm.segments.size}）",
                                icon = Icons.Filled.Check,
                                onClick = { showChapters = true },
                            )
                        }
                    }
                }
            }
            // ---- 语音 ----
            item { Ios5SectionTitle("语音") }
            item {
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    if (vm.internalAvailable) {
                        MsVoices.ALL.forEachIndexed { i, v ->
                            if (i > 0) Ios5CellDivider()
                            Ios5CheckRow(
                                title = "内置 · ${v.label()}",
                                checked = vm.useInternal && vm.internalCode == v.code,
                                onClick = { if (!running) vm.setVoiceInternal(v.code) },
                            )
                        }
                        Ios5CellDivider()
                    } else {
                        Text(
                            "内置语音不可用（本设备缺 arm64 运行库），请用系统 TTS。",
                            color = Ios5Colors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Ios5CheckRow(
                        title = "系统 TTS（自动选中文音色）",
                        checked = !vm.useInternal,
                        onClick = { if (!running) vm.setVoiceSystem(vm.systemEnginePkg) },
                    )
                    if (!vm.useInternal) {
                        if (vm.engines.isEmpty()) {
                            LaunchedEffect(Unit) { vm.loadEngines() }
                            Text(
                                "正在读取系统引擎…",
                                color = Ios5Colors.TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        } else {
                            vm.engines.forEach { e ->
                                Ios5CellDivider()
                                Ios5CheckRow(
                                    title = e.label + if (e.isDefault) "（默认）" else "",
                                    checked = vm.systemEnginePkg == e.packageName ||
                                        (vm.systemEnginePkg.isBlank() && e.isDefault),
                                    onClick = { if (!running) vm.setVoiceSystem(e.packageName) },
                                )
                            }
                        }
                    }
                }
            }
            // ---- 语速语调 ----
            item { Ios5SectionTitle("语速 · 语调") }
            item {
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    Ios5SliderRow(
                        title = "语速",
                        valueLabel = "%.2f".format(vm.rate),
                        value = vm.rate,
                        range = 0.5f..2f,
                        steps = 29,
                        onValueChange = { if (!running) vm.setRate(it) },
                    )
                    Ios5CellDivider()
                    Ios5SliderRow(
                        title = "语调",
                        valueLabel = "%.2f".format(vm.pitch),
                        value = vm.pitch,
                        range = 0.5f..2f,
                        steps = 29,
                        onValueChange = { if (!running) vm.setPitch(it) },
                    )
                }
            }
            // ---- 目的地 ----
            item { Ios5SectionTitle("存到哪个有声书来源") }
            item {
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    if (enabledRoots.isEmpty()) {
                        Ios5Empty("还没有启用的有声书来源\n请先去 设置 → 有声书来源 添加目录")
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                            Ios5GlossButton(text = "去添加来源", onClick = onOpenAudiobookSources)
                        }
                    } else {
                        enabledRoots.forEachIndexed { i, r ->
                            if (i > 0) Ios5CellDivider()
                            Ios5CheckRow(
                                title = r.displayName,
                                subtitle = r.rootPath,
                                checked = rootId == r.id,
                                onClick = { if (!running) rootId = r.id },
                            )
                        }
                    }
                }
            }
            // ---- 线程/覆盖 ----
            item { Ios5SectionTitle("转换选项") }
            item {
                Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                    Ios5SliderRow(
                        title = "并行线程",
                        valueLabel = "${vm.threadCount} 线程（本机 ${vm.cpuCores} 核）",
                        value = vm.threadCount.toFloat(),
                        range = 1f..vm.cpuCores.toFloat(),
                        steps = (vm.cpuCores - 2).coerceAtLeast(0),
                        onValueChange = { if (!running) vm.setThreads(it.toInt().coerceIn(1, vm.cpuCores)) },
                    )
                    Ios5CellDivider()
                    Ios5SwitchRow(
                        title = "覆盖已存在文件",
                        subtitle = "关闭时已存在的章节自动跳过",
                        checked = vm.overwriteExisting,
                        onCheckedChange = { if (!running) vm.setOverwrite(it) },
                    )
                }
            }
            // ---- 开始 ----
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Ios5GlossButton(
                        text = if (running) "转换中…" else "开始转换",
                        icon = Icons.Filled.Headset,
                        onClick = {
                            if (!running && vm.selected.isNotEmpty() && selectedRoot != null) tryStart()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // ---- 进度 ----
            if (running && progress != null) {
                item { Ios5SectionTitle("进度") }
                item {
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val p = progress!!
                            Ios5ProgressBar(frac = p.done.toFloat() / p.total.coerceAtLeast(1))
                            Text(
                                if (p.phase == ExportPhase.SYNTH && p.batchTotal > 0) {
                                    "${p.done}/${p.total}段 · 合成 ${p.batchDone}/${p.batchTotal} · " +
                                        "编码 ${p.encodeDone}/${p.batchTotal} · 合并 ${p.mergedDone}/${p.batchTotal}：" +
                                        p.currentTitle
                                } else {
                                    "${p.done}/${p.total} · ${exportPhaseName(p.phase)}：${p.currentTitle}"
                                },
                                color = Ios5Colors.TextPrimary,
                                fontSize = 13.sp,
                            )
                            p.installNote?.let {
                                Text(it, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                Ios5MiniButton(
                                    text = "取消",
                                    icon = Icons.Filled.Close,
                                    onClick = { EbookExportService.cancel(context) },
                                )
                            }
                        }
                    }
                }
            }
            // ---- 结果 ----
            result?.let { r ->
                item { Ios5SectionTitle("结果") }
                item {
                    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                buildString {
                                    if (r.cancelled) append("已取消：")
                                    append("完成 ${r.done} 段")
                                    if (r.skipped > 0) append("，跳过 ${r.skipped} 段（已存在）")
                                    if (r.failed.isNotEmpty()) append("，${r.failed.size} 段失败")
                                },
                                color = Ios5Colors.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            r.failed.forEach {
                                Text(it, color = androidx.compose.ui.graphics.Color(0xFFD63A3A), fontSize = 13.sp)
                            }
                            if (r.outputFolder.isNotBlank()) {
                                Text(
                                    "保存位置：${selectedRoot?.displayName ?: ""}/${r.outputFolder}",
                                    color = Ios5Colors.TextSecondary,
                                    fontSize = 13.sp,
                                )
                            }
                            Text(
                                "标签：artist=epub，album=书名，title=章节名，track=序号",
                                color = Ios5Colors.TextSecondary,
                                fontSize = 12.sp,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                Ios5GlossButton(
                                    text = "去有声书听",
                                    icon = Icons.Filled.Headset,
                                    onClick = onOpenAudiobooks,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showChapters) {
        Dialog(onDismissRequest = { showChapters = false }) {
            Ios5Group {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "选择章节",
                            color = Ios5Colors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Ios5MiniButton(
                            text = "全选",
                            icon = Icons.Filled.Check,
                            onClick = { if (!running) vm.selectAll(true) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Ios5MiniButton(
                            text = "全不选",
                            icon = Icons.Filled.Close,
                            onClick = { if (!running) vm.selectAll(false) },
                        )
                    }
                    Text(
                        "已选 ${vm.selected.size}/${vm.segments.size} 段",
                        color = Ios5Colors.TextSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f)) {
                        items(vm.segments, key = { it.index }) { s ->
                            Ios5CheckRow(
                                title = "${s.index + 1}. ${s.title}",
                                checked = vm.selected.contains(s.index),
                                onClick = { if (!running) vm.toggleSelect(s.index) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Ios5GlossButton(text = "确定", onClick = { showChapters = false })
                    }
                }
            }
        }
    }
}

/**
 * iOS5 风格进度条：银灰凹槽 + 蓝色填充（与 Ios5Slider 同一套质感）。
 */
@Composable
private fun Ios5ProgressBar(frac: Float) {
    val f = frac.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.fillMaxWidth()
            .height(10.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFFC9CED6),
                    0.5f to Color(0xFFE4E7EC),
                    1f to Color(0xFFF4F5F7),
                ),
            )
            .border(1.dp, Color(0xFF9AA0A8), shape),
    ) {
        if (f > 0f) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(f)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFF53A7EB),
                            1f to Color(0xFF0B6EDB),
                        ),
                    ),
            )
        }
    }
}

private fun exportPhaseName(p: ExportPhase): String = when (p) {
    ExportPhase.SYNTH -> "合成"
    ExportPhase.REMUX -> "合并"
    ExportPhase.TAG -> "写标签"
    ExportPhase.COPY -> "保存"
}
