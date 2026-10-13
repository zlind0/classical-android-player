package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SettingsStore
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5GlossButton
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 有声书设置：音乐共存 / 压低站外音量 / 快进快退时长 / 跳过章节关键字（全全局）。
@Composable
fun AudiobookSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val p by container.settingsStore.audiobookPrefs.collectAsStateWithLifecycle(
        initialValue = SettingsStore.AudiobookPrefs(),
    )
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }

    val bottomPad = contentPadding.calculateBottomPadding()
    Ios5SettingsPage(title = "有声书设置", onBack = onBack) {
        ios5Section("与其他应用") {
            Ios5SwitchRow(
                title = "音乐共存",
                subtitle = "开：其他应用可同时播放音乐，互不暂停；关：听书独占音频焦点",
                checked = p.allowMix,
                onCheckedChange = { v -> scope.launch { container.settingsStore.setAbookAllowMix(v) } },
            )
            Ios5CellDivider()
            Ios5SwitchRow(
                title = "压低其他媒体音量",
                subtitle = if (p.allowMix) "共存时把站外音量压低" else "需先打开音乐共存",
                checked = p.duckOthers && p.allowMix,
                onCheckedChange = { v -> scope.launch { container.settingsStore.setAbookDuckOthers(v) } },
            )
        }
        ios5Section("快进快退") {
            Ios5SliderRow(
                title = "步长",
                valueLabel = "${p.seekSec}秒",
                value = p.seekSec.toFloat(),
                range = 5f..120f,
                // 5..120 step5 = 24 档 → steps=22（positions = steps+2）
                steps = 22,
                onValueChangeFinished = {},
                onValueChange = { v ->
                    scope.launch {
                        container.settingsStore.setAbookSeekSec(((v / 5).roundToInt() * 5))
                    }
                },
            )
            Ios5StaticText("通知栏、耳机上下首键与播放页 ± 键都按这个步长位移。")
        }
        ios5Section("跳过章节") {
            val keywords = p.skipKeywords.sorted()
            if (keywords.isEmpty()) {
                Ios5StaticText("未设置关键字，不跳过任何章节。")
            } else {
                keywords.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            k, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.Close, "删除",
                            tint = Ios5Colors.TextSecondary,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    container.settingsStore.setAbookSkipKeywords(p.skipKeywords - k)
                                }
                            }.padding(8.dp),
                        )
                    }
                    Ios5CellDivider()
                }
            }
            Ios5TextRow(
                title = "添加关键字",
                value = draft,
                placeholder = "如：前言",
                onValueChange = { draft = it },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Ios5GlossButton(
                    text = "添加",
                    icon = Icons.Filled.Add,
                    onClick = {
                        val k = draft.trim()
                        if (k.isNotEmpty()) {
                            scope.launch {
                                container.settingsStore.setAbookSkipKeywords(p.skipKeywords + k)
                            }
                            draft = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ios5FootNote("章节名包含任一关键字即在建队时剔除；改动后重组书库，已记的进度按文件路径找回，不断章。")
        item { Spacer(Modifier.height(bottomPad)) }
    }
}
