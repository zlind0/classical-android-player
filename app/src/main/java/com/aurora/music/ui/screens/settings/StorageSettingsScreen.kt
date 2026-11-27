package com.aurora.music.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.CacheCategory
import com.aurora.music.data.CachePolicy
import com.aurora.music.data.CacheStats
import com.aurora.music.data.ModelStorageAccess
import com.aurora.music.data.ModelStorageConfig
import com.aurora.music.data.ModelStorageManager
import com.aurora.music.data.ModelStorageMode
import com.aurora.music.data.STORAGE_MAX_CHILDREN
import com.aurora.music.data.SettingsStore
import com.aurora.music.data.StorageNode
import com.aurora.music.data.StorageReport
import com.aurora.music.data.canWriteDirectory
import com.aurora.music.data.fileLabelRes
import com.aurora.music.data.hasAllFilesAccess
import com.aurora.music.data.llm.LlmModelCatalog
import com.aurora.music.data.openAllFilesSettings
import com.aurora.music.data.resolveSafTreePath
import com.aurora.music.data.scanAppStorage
import com.aurora.music.data.subtitle.AsrModelCatalog
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5ButtonTone
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5ConfirmDialog
import com.aurora.music.ui.ios5.Ios5Dialog
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SegmentRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SmallButton
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SIZE_PRESETS_MB = listOf(64, 128, 256, 512, 1024, 2048, 0)
private val PERIOD_PRESETS_DAYS = listOf(1, 7, 14, 30, 90, 0)

@Composable
fun StorageSettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val container = LocalContextApp()
    val scope = rememberCoroutineScope()
    val downloads by container.downloadManager.downloads.collectAsStateWithLifecycle()
    val bytes = remember(downloads) { container.downloadManager.totalBytes() }
    val remoteDownloads by container.remoteDownloads.records.collectAsStateWithLifecycle()
    val remoteBytes = remember(remoteDownloads) { container.remoteDownloads.totalBytes() }

    val cachePolicies by container.settingsStore.cachePolicies.collectAsStateWithLifecycle(
        initialValue = CacheCategory.entries.associateWith { it.defaultPolicy() },
    )
    var cacheStats by remember { mutableStateOf<Map<CacheCategory, CacheStats>>(emptyMap()) }
    var cacheScanGen by remember { mutableStateOf(0) }
    var editingCategory by remember { mutableStateOf<CacheCategory?>(null) }
    var confirmClearAllCaches by remember { mutableStateOf(false) }

    val strTitle = stringResource(R.string.storage_title)
    val strDownloaded = stringResource(R.string.storage_downloaded_fmt, downloads.size)
    val strUsed = stringResource(R.string.storage_used_fmt, formatBytes(bytes))
    val strRemoteDownloaded = stringResource(R.string.storage_remote_downloaded_fmt, remoteDownloads.size)
    val strRemoteUsed = stringResource(R.string.storage_used_fmt, formatBytes(remoteBytes))
    val strVolumeLeveling = stringResource(R.string.storage_volume_leveling)
    val strManage = stringResource(R.string.storage_manage)
    val strRemoveAll = stringResource(R.string.storage_remove_all)
    val strRemoveRemoteAll = stringResource(R.string.storage_remove_remote_all)
    val strPrivateNote = stringResource(R.string.storage_private_note)
    val strRgApplies = stringResource(R.string.storage_rg_applies)
    val strCacheSection = stringResource(R.string.storage_cache_section)
    val strCacheClearAll = stringResource(R.string.storage_cache_clear_all)
    val strCacheEnforceNow = stringResource(R.string.storage_cache_enforce_now)
    val bottomPad = contentPadding.calculateBottomPadding()

    // ReplayGain scan measures on-device files (EBU R128) to level playback volume.
    val rg by container.replayGainScanner.progress.collectAsStateWithLifecycle()
    val strScanningRg = stringResource(R.string.storage_scanning_rg)
    val strScanRg = stringResource(R.string.storage_scan_rg)
    val strRgHint = stringResource(R.string.storage_rg_hint)
    val strCancel = stringResource(R.string.common_cancel)
    val rgRunning = rg.running
    val rgHeadline = if (rgRunning) strScanningRg else strScanRg
    val rgProgressLine = if (rgRunning) stringResource(R.string.storage_rg_progress, rg.done, rg.total, rg.current) else ""
    val rgAvg = container.replayGainStore.avgLufs()?.let { stringResource(R.string.storage_rg_avg, it) } ?: ""
    val rgDoneLine = stringResource(R.string.storage_rg_done, container.replayGainStore.size, rgAvg)
    val rgSub = when {
        rgRunning -> rgProgressLine
        container.replayGainStore.size > 0 -> rgDoneLine
        else -> strRgHint
    }
    val downloadsEmpty = downloads.isEmpty()
    val remoteDownloadsEmpty = remoteDownloads.isEmpty()

    // 存储明细与缓存统计：IO 线程递归统计
    val appContext = LocalContext.current.applicationContext
    var storageReport by remember { mutableStateOf<StorageReport?>(null) }
    var storageScanGen by remember { mutableStateOf(0) }
    var storageExpanded by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(storageScanGen) {
        storageReport = scanAppStorage(appContext)
    }
    LaunchedEffect(cacheScanGen) {
        cacheStats = container.cacheManager.measureAll()
    }
    val totalCacheBytes = remember(cacheStats) { cacheStats.values.sumOf { it.bytes } }
    val strBreakdown = stringResource(R.string.storage_breakdown)

    // ---- 大模型存储（LLM 权重 + ASR 权重，共用同一棵目录树）----
    val modelStorageConfig by container.modelStorage.config.collectAsStateWithLifecycle()
    val modelMigration by container.modelStorage.migration.collectAsStateWithLifecycle()
    val llmPrefs by container.settingsStore.multimodalPrefs.collectAsStateWithLifecycle(
        initialValue = SettingsStore.MultimodalPrefs(),
    )
    val subPrefs by container.settingsStore.subtitlePrefs.collectAsStateWithLifecycle(
        initialValue = SettingsStore.SubtitlePrefs(),
    )
    val llmStates by container.llmModels.states.collectAsStateWithLifecycle()
    val asrStates by container.asrModels.states.collectAsStateWithLifecycle()
    var modelGen by remember { mutableIntStateOf(0) }
    var pickingModelFolder by remember { mutableStateOf(false) }
    var showModelAccessChoice by remember { mutableStateOf(false) }
    var pendingAllFilesPick by remember { mutableStateOf(false) }
    var conflictTarget by remember { mutableStateOf<ModelStorageConfig?>(null) }
    var conflictFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMessage by remember { mutableStateOf("") }
    var confirmDeleteAllModels by remember { mutableStateOf(false) }
    var modelStorageUsable by remember { mutableStateOf(true) }
    // 迁移期间配置还没切换，分段控件先跟着「用户选的目标」走。
    var requestedModelTarget by remember { mutableStateOf<ModelStorageConfig?>(null) }

    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var modelResumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) modelResumeTick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // 从「所有文件访问」系统页回来：拿到权限后接着选目录。
    LaunchedEffect(modelResumeTick) {
        if (pendingAllFilesPick && Build.VERSION.SDK_INT >= 30 && hasAllFilesAccess(ctx)) {
            pendingAllFilesPick = false
            pickingModelFolder = true
        }
    }
    LaunchedEffect(modelStorageConfig, modelResumeTick, modelMigration) {
        modelStorageUsable = withContext(Dispatchers.IO) {
            modelStorageConfig.isApp || canWriteDirectory(File(modelStorageConfig.path))
        }
    }
    LaunchedEffect(modelMigration) {
        when (modelMigration) {
            is ModelStorageManager.MigrationState.Done -> {
                requestedModelTarget = null
                modelGen++
                storageScanGen++
                cacheScanGen++
            }
            is ModelStorageManager.MigrationState.Failed -> requestedModelTarget = null
            else -> Unit
        }
    }

    val installedModels = remember(modelGen, modelStorageConfig, modelMigration, llmStates, asrStates) {
        val llm = LlmModelCatalog.all.mapNotNull { spec ->
            val f = container.llmModels.modelFile(spec)
            if (f.isFile && f.length() > 0) InstalledModel(0, spec.id, spec.shortTitle, f.length()) else null
        }
        val asr = AsrModelCatalog.all.mapNotNull { spec ->
            if (!container.asrModels.isInstalled(spec)) return@mapNotNull null
            val dir = container.asrModels.modelDir(spec)
            InstalledModel(
                kind = 1,
                id = spec.id,
                title = spec.title.substringBefore("（"),
                bytes = spec.files.sumOf { File(dir, it.name).length() },
            )
        }
        llm + asr
    }
    val modelTotalBytes = remember(installedModels) { installedModels.sumOf { it.bytes } }

    fun deleteModel(model: InstalledModel) {
        scope.launch {
            when (model.kind) {
                0 -> {
                    LlmModelCatalog.byId(model.id)?.let { container.llmModels.delete(it) }
                    if (llmPrefs.modelId == model.id) container.settingsStore.setMultimodalModelId("")
                }
                else -> {
                    AsrModelCatalog.byId(model.id)?.let { container.asrModels.delete(it) }
                    if (subPrefs.modelId == model.id) container.settingsStore.setSubtitleModelId("")
                }
            }
            modelGen++
            storageScanGen++
            cacheScanGen++
        }
    }

    fun requestModelSwitch(target: ModelStorageConfig) {
        modelMessage = ""
        val downloading = container.llmModels.states.value.values.any {
            it is com.aurora.music.data.llm.LlmModelStore.ModelState.Downloading
        } || container.asrModels.states.value.values.any {
            it is com.aurora.music.data.subtitle.AsrModelStore.ModelState.Downloading
        }
        if (downloading) {
            modelMessage = "有模型正在下载，请等下载完成或取消后再切换目录。"
            return
        }
        val from = container.modelStorage.currentRoot().absolutePath
        val to = container.modelStorage.targetRoot(target).absolutePath
        if (to != from && (to.startsWith("$from/") || from.startsWith("$to/"))) {
            modelMessage = "不能选择当前模型目录自身或它的子目录。"
            return
        }
        scope.launch {
            val conflicts = container.modelStorage.findConflicts(target)
            if (conflicts.isEmpty()) {
                requestedModelTarget = target
                container.modelStorage.startMigration(target, ModelStorageManager.ConflictPolicy.OVERWRITE)
            } else {
                conflictFiles = conflicts
                conflictTarget = target
            }
        }
    }

    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val dir = resolveSafTreePath(uri)
            when {
                dir == null -> modelMessage =
                    "无法把选择的目录转换成文件路径。请改用「所有文件访问」，或换一个目录。"
                !canWriteDirectory(dir) -> modelMessage =
                    "系统不允许直接写入 ${dir.absolutePath}。Android 11 及以上需要先授权「所有文件访问」，" +
                        "或改用应用存储。"
                else -> requestModelSwitch(
                    ModelStorageConfig(
                        mode = ModelStorageMode.ANDROID,
                        access = ModelStorageAccess.SAF,
                        treeUri = uri.toString(),
                        path = dir.absolutePath,
                        label = dir.name.ifBlank { dir.absolutePath },
                    ),
                )
            }
        }
    }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pickingModelFolder = true
    }

    if (pickingModelFolder) {
        FolderPickerScreen(
            contentPadding = contentPadding,
            onBack = { pickingModelFolder = false },
            backLabel = strTitle,
            onPicked = { path, name, _ ->
                pickingModelFolder = false
                val dir = File(path)
                if (!canWriteDirectory(dir)) {
                    modelMessage = "这个目录不能直接写入，请换一个目录。"
                } else {
                    requestModelSwitch(
                        ModelStorageConfig(
                            mode = ModelStorageMode.ANDROID,
                            access = ModelStorageAccess.ALL_FILES,
                            path = dir.absolutePath,
                            label = name.ifBlank { dir.name },
                        ),
                    )
                }
            },
        )
        return
    }

    Ios5SettingsPage(title = strTitle, onBack = onBack) {
        ios5Section(strTitle) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(strDownloaded, color = Ios5Colors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(strUsed, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
            }
            Ios5CellDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(strRemoteDownloaded, color = Ios5Colors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(strRemoteUsed, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
            }
        }
        ios5Section(strManage) {
            if (downloadsEmpty) {
                Ios5StaticText(strRemoveAll)
            } else {
                Ios5ActionRow(
                    title = strRemoveAll,
                    danger = true,
                    onClick = {
                        container.downloadManager.clearAll()
                        storageScanGen++
                    },
                )
            }
            Ios5CellDivider()
            if (remoteDownloadsEmpty) {
                Ios5StaticText(strRemoveRemoteAll)
            } else {
                Ios5ActionRow(
                    title = strRemoveRemoteAll,
                    danger = true,
                    onClick = {
                        container.remoteDownloads.clearAll()
                        storageScanGen++
                    },
                )
            }
        }
        ios5Section(strCacheSection) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.storage_cache_total_fmt, formatBytes(totalCacheBytes)),
                    color = Ios5Colors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                )
            }
            CacheCategory.entries.forEach { cat ->
                Ios5CellDivider()
                val stats = cacheStats[cat]
                val policy = cachePolicies[cat] ?: cat.defaultPolicy()
                val limitLabel = formatLimitMb(policy.maxMb)
                val periodLabel = formatPeriodDays(policy.maxAgeDays)
                val policyLine = stringResource(R.string.storage_cache_policy_fmt, limitLabel, periodLabel)
                Ios5NavRow(
                    title = stringResource(categoryTitleRes(cat)),
                    subtitle = policyLine,
                    value = formatBytes(stats?.bytes ?: 0L),
                    onClick = { editingCategory = cat },
                )
            }
            Ios5CellDivider()
            Ios5ActionRow(
                title = strCacheEnforceNow,
                onClick = {
                    scope.launch {
                        container.cacheManager.enforceAll(cachePolicies)
                        cacheScanGen++
                        storageScanGen++
                    }
                },
            )
            Ios5CellDivider()
            Ios5ActionRow(
                title = strCacheClearAll,
                danger = true,
                onClick = { confirmClearAllCaches = true },
            )
        }
        ios5Section("大模型存储") {
            Ios5SegmentRow(
                title = "存储目录",
                options = listOf("应用存储", "安卓目录"),
                selected = if ((requestedModelTarget ?: modelStorageConfig).isApp) 0 else 1,
                onSelect = { index ->
                    if (index == 0) {
                        if (!modelStorageConfig.isApp) {
                            requestModelSwitch(ModelStorageConfig(mode = ModelStorageMode.APP))
                        }
                    } else {
                        showModelAccessChoice = true
                    }
                },
            )
            Ios5CellDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    if (modelStorageConfig.isApp) "应用内部存储" else "安卓共享目录",
                    color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    ModelStorageConfig.displayPath(appContext, modelStorageConfig),
                    color = Ios5Colors.TextSecondary, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (!modelStorageConfig.isApp) {
                    Text(
                        "访问方式：" + if (modelStorageConfig.access == ModelStorageAccess.SAF) {
                            "系统目录选择器"
                        } else {
                            "所有文件访问"
                        },
                        color = Ios5Colors.TextSecondary, fontSize = 12.sp,
                    )
                }
                if (!modelStorageUsable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "当前目录不可写：可能权限被撤销，或存储卡已拔出。已下载的模型会显示为未安装。",
                        color = Color(0xFFD63A3A), fontSize = 12.sp,
                    )
                }
                if (modelMessage.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(modelMessage, color = Color(0xFFD63A3A), fontSize = 12.sp)
                }
            }
            if (!modelStorageConfig.isApp) {
                Ios5CellDivider()
                Ios5ActionRow(
                    title = "重新选择目录 / 授权",
                    onClick = { showModelAccessChoice = true },
                )
            }
            when (val m = modelMigration) {
                is ModelStorageManager.MigrationState.Running -> {
                    Ios5CellDivider()
                    val pct = if (m.totalBytes > 0) {
                        (m.doneBytes * 100 / m.totalBytes).coerceIn(0, 99)
                    } else 0
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            "正在迁移模型 $pct%",
                            color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${formatBytes(m.doneBytes)} / ${formatBytes(m.totalBytes)}",
                            color = Ios5Colors.TextSecondary, fontSize = 12.sp,
                        )
                        requestedModelTarget?.let { t ->
                            Text(
                                "目标：" + ModelStorageConfig.displayPath(appContext, t),
                                color = Ios5Colors.TextSecondary, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (m.current.isNotBlank()) {
                            Text(
                                m.current, color = Ios5Colors.TextSecondary, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                is ModelStorageManager.MigrationState.Failed -> {
                    Ios5CellDivider()
                    Ios5StaticText("迁移失败：${m.message}（已下载的模型仍保留在原位置）")
                }
                is ModelStorageManager.MigrationState.Done -> {
                    if (m.moved + m.kept > 0) {
                        val keptNote = if (m.kept > 0) "，保留新目录中的 ${m.kept} 个同名文件" else ""
                        Ios5CellDivider()
                        Ios5StaticText("迁移完成：移动 ${m.moved} 个文件$keptNote。")
                    }
                }
                ModelStorageManager.MigrationState.Idle -> Unit
            }
            Ios5CellDivider()
            Ios5NavRow(
                title = "编译缓存",
                subtitle = "LiteRT-LM 编译结果，清除或切换目录后会按需重建",
                value = formatBytes(cacheStats[CacheCategory.LLM_COMPILE]?.bytes ?: 0L),
                onClick = { editingCategory = CacheCategory.LLM_COMPILE },
            )
            Ios5CellDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    "已下载 ${installedModels.size} 个模型 · ${formatBytes(modelTotalBytes)}",
                    color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    "大模型只在需要时下载；删除后要重新下载才能使用。",
                    color = Ios5Colors.TextSecondary, fontSize = 12.sp,
                )
            }
            installedModels.forEach { model ->
                Ios5CellDivider()
                ModelStorageRow(
                    title = model.title,
                    subtitle = (if (model.kind == 0) "综合大模型" else "语音识别") + " · " + formatBytes(model.bytes),
                    onDelete = { deleteModel(model) },
                )
            }
            if (installedModels.isNotEmpty()) {
                Ios5CellDivider()
                Ios5ActionRow(
                    title = "删除全部大模型",
                    danger = true,
                    onClick = { confirmDeleteAllModels = true },
                )
            }
        }
        ios5Section(strVolumeLeveling) {
            Column(
                Modifier.fillMaxWidth().clickable(enabled = !rgRunning) { container.replayGainScanner.scan() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rgHeadline, color = Ios5Colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(rgSub, color = Ios5Colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (rgRunning) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            strCancel, fontSize = 15.sp, color = Ios5Colors.IosBlue,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { container.replayGainScanner.cancel() }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Ios5CellDivider()
            Ios5StaticText(strRgApplies)
        }
        ios5Section(strBreakdown) {
            StorageBreakdownContent(
                report = storageReport,
                expanded = storageExpanded,
                onToggle = { rel ->
                    storageExpanded = if (rel in storageExpanded) storageExpanded - rel else storageExpanded + rel
                },
                onRescan = {
                    storageExpanded = emptySet()
                    storageScanGen++
                    cacheScanGen++
                },
            )
        }
        ios5FootNote(strPrivateNote)
        item { Spacer(Modifier.height(bottomPad)) }
    }

    editingCategory?.let { cat ->
        val policy = cachePolicies[cat] ?: cat.defaultPolicy()
        val stats = cacheStats[cat]
        CachePolicyDialog(
            category = cat,
            stats = stats,
            policy = policy,
            onPolicyChange = { next ->
                scope.launch {
                    container.settingsStore.setCachePolicy(cat, next)
                    container.cacheManager.enforce(cat, next)
                    cacheScanGen++
                    storageScanGen++
                }
            },
            onClearNow = {
                scope.launch {
                    container.cacheManager.clear(cat)
                    cacheScanGen++
                    storageScanGen++
                    editingCategory = null
                }
            },
            onDismiss = { editingCategory = null },
        )
    }

    if (confirmClearAllCaches) {
        Ios5ConfirmDialog(
            title = stringResource(R.string.storage_cache_clear_all_confirm_title),
            message = stringResource(R.string.storage_cache_clear_all_confirm_msg),
            confirmText = strCacheClearAll,
            onConfirm = {
                confirmClearAllCaches = false
                scope.launch {
                    container.cacheManager.clearAll()
                    cacheScanGen++
                    storageScanGen++
                }
            },
            onCancel = { confirmClearAllCaches = false },
        )
    }

    if (showModelAccessChoice) {
        Ios5Dialog(onDismiss = { showModelAccessChoice = false }) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(14.dp),
                color = Ios5Colors.GroupBg,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(
                        "选择大模型目录",
                        color = Ios5Colors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Text(
                        "「所有文件访问」拿到真实路径，原生推理可以直接读取权重；" +
                            "系统目录选择器更省权限，但部分高版本系统仍要求前者才能写入。",
                        color = Ios5Colors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Ios5CellDivider()
                    Ios5ActionRow(title = "所有文件访问（推荐）") {
                        showModelAccessChoice = false
                        if (Build.VERSION.SDK_INT >= 30) {
                            if (hasAllFilesAccess(ctx)) {
                                pickingModelFolder = true
                            } else {
                                pendingAllFilesPick = true
                                openAllFilesSettings(ctx)
                            }
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                ctx,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) pickingModelFolder = true
                            else writeLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                    Ios5CellDivider()
                    Ios5ActionRow(title = "系统目录选择器") {
                        showModelAccessChoice = false
                        safLauncher.launch(null)
                    }
                    Ios5CellDivider()
                    Ios5ActionRow(title = stringResource(R.string.common_cancel)) {
                        showModelAccessChoice = false
                    }
                }
            }
        }
    }

    conflictTarget?.let { target ->
        Ios5Dialog(onDismiss = { conflictTarget = null }) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(14.dp),
                color = Ios5Colors.GroupBg,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(
                        "新目录里已有同名文件",
                        color = Ios5Colors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Text(
                        "有 ${conflictFiles.size} 个模型文件在两个位置都存在：",
                        color = Ios5Colors.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Text(
                        conflictFiles.take(3).joinToString("\n") { it.substringAfterLast('/') } +
                            if (conflictFiles.size > 3) "\n…" else "",
                        color = Ios5Colors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Ios5CellDivider()
                    Ios5ActionRow(title = "覆盖新目录中的文件") {
                        conflictTarget = null
                        requestedModelTarget = target
                        container.modelStorage.startMigration(
                            target,
                            ModelStorageManager.ConflictPolicy.OVERWRITE,
                        )
                    }
                    Ios5CellDivider()
                    Ios5ActionRow(title = "保留新目录中的文件（删除已下载的模型）") {
                        conflictTarget = null
                        requestedModelTarget = target
                        container.modelStorage.startMigration(
                            target,
                            ModelStorageManager.ConflictPolicy.KEEP_TARGET,
                        )
                    }
                    Ios5CellDivider()
                    Ios5ActionRow(title = stringResource(R.string.common_cancel)) {
                        conflictTarget = null
                    }
                }
            }
        }
    }

    if (confirmDeleteAllModels) {
        Ios5ConfirmDialog(
            title = "删除全部大模型？",
            message = "会删除本机已下载的综合大模型和语音识别模型，之后需要重新下载才能使用。",
            confirmText = "删除",
            onConfirm = {
                confirmDeleteAllModels = false
                scope.launch {
                    LlmModelCatalog.all.forEach { container.llmModels.delete(it) }
                    AsrModelCatalog.all.forEach { container.asrModels.delete(it) }
                    // 权重都删了，编译缓存也就没用了，一起清掉。
                    container.cacheManager.clear(CacheCategory.LLM_COMPILE)
                    container.settingsStore.setMultimodalModelId("")
                    container.settingsStore.setSubtitleModelId("")
                    modelGen++
                    storageScanGen++
                    cacheScanGen++
                }
            },
            onCancel = { confirmDeleteAllModels = false },
        )
    }
}

/** 大模型存储列表里的一行：名称 + 类型/体积 + 删除。 */
private data class InstalledModel(val kind: Int, val id: String, val title: String, val bytes: Long)

@Composable
private fun ModelStorageRow(title: String, subtitle: String, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Ios5Colors.TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Ios5SmallButton(text = "删除", onClick = onDelete, tone = Ios5ButtonTone.Grey)
    }
}

@Composable
private fun CachePolicyDialog(
    category: CacheCategory,
    stats: CacheStats?,
    policy: CachePolicy,
    onPolicyChange: (CachePolicy) -> Unit,
    onClearNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sizeOptions = SIZE_PRESETS_MB.map { formatLimitMb(it) }
    val sizeIdx = SIZE_PRESETS_MB.indexOf(policy.maxMb).let { if (it >= 0) it else SIZE_PRESETS_MB.lastIndex }
    val periodOptions = PERIOD_PRESETS_DAYS.map { formatPeriodDays(it) }
    val periodIdx = PERIOD_PRESETS_DAYS.indexOf(policy.maxAgeDays).let { if (it >= 0) it else PERIOD_PRESETS_DAYS.lastIndex }

    Ios5Dialog(onDismiss = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(14.dp),
            color = Ios5Colors.GroupBg,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(
                    stringResource(categoryTitleRes(category)),
                    color = Ios5Colors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Text(
                    stringResource(categoryDescRes(category)),
                    color = Ios5Colors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Text(
                    "${formatBytes(stats?.bytes ?: 0L)} · ${stringResource(R.string.storage_files_fmt, stats?.itemCount ?: 0)}",
                    color = Ios5Colors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Ios5CellDivider()
                Ios5SegmentRow(
                    title = stringResource(R.string.storage_cache_limit_title),
                    options = sizeOptions,
                    selected = sizeIdx,
                    onSelect = { i -> onPolicyChange(policy.copy(maxMb = SIZE_PRESETS_MB[i])) },
                )
                Ios5SegmentRow(
                    title = stringResource(R.string.storage_cache_period_title),
                    options = periodOptions,
                    selected = periodIdx,
                    onSelect = { i -> onPolicyChange(policy.copy(maxAgeDays = PERIOD_PRESETS_DAYS[i])) },
                )
                Ios5CellDivider()
                Ios5ActionRow(
                    title = stringResource(R.string.storage_cache_clear_one),
                    danger = true,
                    onClick = onClearNow,
                )
                Ios5CellDivider()
                Ios5ActionRow(
                    title = stringResource(R.string.common_close),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun formatLimitMb(mb: Int): String = when {
    mb <= 0 -> stringResource(R.string.storage_cache_unlimited)
    mb >= 1024 -> "${mb / 1024}G"
    else -> "${mb}M"
}

@Composable
private fun formatPeriodDays(days: Int): String = when {
    days <= 0 -> stringResource(R.string.storage_cache_never)
    else -> stringResource(R.string.storage_cache_days_fmt, days)
}

private fun categoryTitleRes(cat: CacheCategory): Int = when (cat) {
    CacheCategory.REMOTE_MEDIA -> R.string.cache_cat_remote_media_title
    CacheCategory.REMOTE_LIBRARY -> R.string.cache_cat_remote_library_title
    CacheCategory.REMOTE_EBOOKS -> R.string.cache_cat_remote_ebooks_title
    CacheCategory.RANDOM_FEED -> R.string.cache_cat_random_feed_title
    CacheCategory.EBOOK_PARSE -> R.string.cache_cat_ebook_parse_title
    CacheCategory.IMAGE_AND_ART -> R.string.cache_cat_image_art_title
    CacheCategory.EMBEDDED_ART -> R.string.cache_cat_embedded_art_title
    CacheCategory.AIRPLAY_ART -> R.string.cache_cat_airplay_art_title
    CacheCategory.SUBTITLES_AND_AI -> R.string.cache_cat_subtitles_ai_title
    CacheCategory.LLM_COMPILE -> R.string.cache_cat_llm_compile_title
    CacheCategory.TEMP_FILES -> R.string.cache_cat_temp_files_title
}

private fun categoryDescRes(cat: CacheCategory): Int = when (cat) {
    CacheCategory.REMOTE_MEDIA -> R.string.cache_cat_remote_media_desc
    CacheCategory.REMOTE_LIBRARY -> R.string.cache_cat_remote_library_desc
    CacheCategory.REMOTE_EBOOKS -> R.string.cache_cat_remote_ebooks_desc
    CacheCategory.RANDOM_FEED -> R.string.cache_cat_random_feed_desc
    CacheCategory.EBOOK_PARSE -> R.string.cache_cat_ebook_parse_desc
    CacheCategory.IMAGE_AND_ART -> R.string.cache_cat_image_art_desc
    CacheCategory.EMBEDDED_ART -> R.string.cache_cat_embedded_art_desc
    CacheCategory.AIRPLAY_ART -> R.string.cache_cat_airplay_art_desc
    CacheCategory.SUBTITLES_AND_AI -> R.string.cache_cat_subtitles_ai_desc
    CacheCategory.LLM_COMPILE -> R.string.cache_cat_llm_compile_desc
    CacheCategory.TEMP_FILES -> R.string.cache_cat_temp_files_desc
}

@Composable
private fun LocalContextApp() =
    (LocalContext.current.applicationContext as AuroraApplication).container

/** 存储明细：递归展开到每个文件的大头定位器（section 卡片内的全部内容）。 */
@Composable
private fun StorageBreakdownContent(
    report: StorageReport?,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onRescan: () -> Unit,
) {
    val strRescan = stringResource(R.string.storage_rescan)
    val strScanning = stringResource(R.string.storage_scanning)
    val strRootFiles = stringResource(R.string.storage_root_files)
    val strRootCache = stringResource(R.string.storage_root_cache)

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (report == null) {
            Text(strScanning, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
        } else {
            Text(
                stringResource(R.string.storage_total_fmt, formatBytes(report.totalBytes)),
                color = Ios5Colors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            )
            val filesBytes = report.root("files")?.bytes ?: 0L
            val cacheBytes = report.root("cache")?.bytes ?: 0L
            Text(
                "$strRootFiles ${formatBytes(filesBytes)} · $strRootCache ${formatBytes(cacheBytes)}",
                color = Ios5Colors.TextSecondary, fontSize = 13.sp,
            )
        }
    }
    if (report != null) {
        report.roots.forEachIndexed { i, root ->
            if (i > 0) Ios5CellDivider()
            StorageNodeRows(node = root, depth = 0, expanded = expanded, onToggle = onToggle)
        }
        Ios5CellDivider()
    }
    Ios5ActionRow(title = strRescan, onClick = onRescan)
}

@Composable
private fun StorageNodeRows(
    node: StorageNode,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    val title = nodeTitle(node)
    val size = formatBytes(node.bytes)
    val indent = Modifier.padding(start = (depth * 14).dp)
    if (node.isDir) {
        val isOpen = node.relPath in expanded
        Box(indent) {
            Ios5NavRow(
                title = if (isOpen) "$title ▾" else "$title ▸",
                subtitle = if (node.files > 0) stringResource(R.string.storage_files_fmt, node.files) else "",
                value = size,
                onClick = { onToggle(node.relPath) },
            )
        }
        if (isOpen) {
            node.children.forEach { child ->
                Ios5CellDivider()
                StorageNodeRows(child, depth + 1, expanded, onToggle)
            }
            if (node.omitted > 0) {
                Ios5CellDivider()
                Box(Modifier.padding(start = ((depth + 1) * 14).dp)) {
                    Ios5StaticText(
                        stringResource(R.string.storage_more_fmt, node.omitted, STORAGE_MAX_CHILDREN),
                    )
                }
            }
        }
    } else {
        Box(indent) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, color = Ios5Colors.TextPrimary, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(size, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun nodeTitle(node: StorageNode): String {
    val res = node.labelRes ?: fileLabelRes(node.name)
    return when {
        res != null && node.labelArg != null -> stringResource(res, node.labelArg!!)
        res != null -> stringResource(res)
        else -> node.name
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1) return "$bytes B"
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
