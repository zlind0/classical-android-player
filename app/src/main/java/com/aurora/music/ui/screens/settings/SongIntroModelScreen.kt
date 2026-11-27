package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SongIntroPrefs
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Rows
import com.aurora.music.ui.ios5.ios5Section
import kotlinx.coroutines.launch

/**
 * 歌曲介绍的模型选择页。
 *
 * 列表按 `vendor/model` 的前缀分组，点击分组展开/收起，避免 OpenRouter
 * 这类端点一次铺开几百个模型。分组内选中后自动返回上一页。
 */
@Composable
fun SongIntroModelScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    confirm: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val store = container.settingsStore
    val intro = container.songIntro
    val prefs by store.songIntroPrefs.collectAsStateWithLifecycle(initialValue = SongIntroPrefs())
    val scope = rememberCoroutineScope()

    var model by remember(prefs) { mutableStateOf(prefs.llmModel) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var expandedGroups by com.aurora.music.ui.rememberEntryExpandedState("model_groups")

    suspend fun load(showToast: Boolean) {
        if (fetching) return
        fetching = true
        intro.fetchModels()
            .onSuccess {
                models = it.sorted()
                loadError = null
                if (showToast) confirm("获取到 ${it.size} 个模型")
            }
            .onFailure {
                loadError = it.message ?: "获取失败"
                if (showToast) confirm("获取失败：${it.message}")
            }
        fetching = false
    }

    LaunchedEffect(Unit) {
        if (models == null) load(showToast = false)
    }

    // 当前选中的模型所在分组默认展开，再次进入页面不用重新找。
    LaunchedEffect(models, prefs.llmModel) {
        val selected = prefs.llmModel
        if (selected.contains('/')) {
            val group = selected.substringBefore('/')
            if (group !in expandedGroups) expandedGroups = expandedGroups + group
        }
    }

    val rows = remember(models, expandedGroups) {
        buildModelRows(models.orEmpty(), expandedGroups)
    }

    Ios5SettingsPage("选择模型", onBack) {
        ios5Section("当前模型") {
            Ios5StaticText(prefs.llmModel.ifBlank { "未选择" })
            Ios5CellDivider()
            Ios5TextRow(
                title = "手动填写模型 ID",
                value = model,
                placeholder = "例如 gpt-4o-mini 或 openai/gpt-4o",
                onValueChange = { model = it; scope.launch { store.setIntroModel(it) } },
            )
            Ios5CellDivider()
            Ios5ActionRow(
                title = when {
                    fetching -> "正在获取模型列表…"
                    models == null -> "获取端点中的模型列表"
                    else -> "重新获取模型列表"
                },
                onClick = { scope.launch { load(showToast = true) } },
            )
        }

        loadError?.let { error ->
            ios5FootNote("获取失败：$error。可手动填写模型 ID 后重试。")
        }

        val list = models
        if (list != null && list.isNotEmpty()) {
            item { Ios5SectionTitle("可用模型（${list.size}）") }
            ios5Rows(rows, key = { it.key }) { _, row ->
                when (row) {
                    is ModelRow.Group -> {
                        val open = row.name in expandedGroups
                        val selectedInGroup = prefs.llmModel in row.models
                        Ios5Cell(
                            title = row.name,
                            count = row.models.size.toString(),
                            showChevron = false,
                            onClick = {
                                expandedGroups = if (open) {
                                    expandedGroups - row.name
                                } else {
                                    expandedGroups + row.name
                                }
                            },
                            leading = {
                                Text(
                                    if (open) "▼" else "▶",
                                    color = Ios5Colors.TextSecondary,
                                    fontSize = 10.sp,
                                )
                            },
                            trailing = {
                                if (selectedInGroup) {
                                    Text(
                                        "✓",
                                        color = Ios5Colors.IosBlue,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            },
                        )
                    }

                    is ModelRow.Leaf -> {
                        val checked = row.model == prefs.llmModel
                        Ios5Cell(
                            title = row.model,
                            showChevron = false,
                            onClick = {
                                scope.launch {
                                    store.setIntroModel(row.model)
                                    onBack()
                                }
                            },
                            leading = if (row.indented) {
                                { Spacer(Modifier.width(22.dp)) }
                            } else {
                                null
                            },
                            trailing = {
                                if (checked) {
                                    Text(
                                        "✓",
                                        color = Ios5Colors.IosBlue,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            ios5FootNote("点击带 ▶ 的分组展开，再点一次收起。")
        }

        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}

private sealed interface ModelRow {
    val key: String

    data class Group(val name: String, val models: List<String>) : ModelRow {
        override val key: String = "g:$name"
    }

    data class Leaf(val model: String, val indented: Boolean) : ModelRow {
        override val key: String = "m:$model"
    }
}

/**
 * OpenRouter 等端点的模型 ID 形如 `vendor/model`。以第一个 `/` 前的前缀
 * 作为资源管理器式的分组；没有 `/` 的模型保持平铺在最前面。
 */
private fun buildModelRows(models: List<String>, expandedGroups: Set<String>): List<ModelRow> {
    val grouped = models.filter { it.contains('/') }
        .groupBy { it.substringBefore('/') }
        .toSortedMap()
    val flat = models.filterNot { it.contains('/') }

    return buildList {
        flat.forEach { add(ModelRow.Leaf(model = it, indented = false)) }
        grouped.forEach { (name, members) ->
            add(ModelRow.Group(name = name, models = members))
            if (name in expandedGroups) {
                members.forEach { add(ModelRow.Leaf(model = it, indented = true)) }
            }
        }
    }
}
