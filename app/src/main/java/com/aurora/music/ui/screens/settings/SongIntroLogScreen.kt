package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.SongIntroLogEntry
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Empty
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import kotlinx.coroutines.launch

@Composable
fun SongIntroLogScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    confirm: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as AuroraApplication).container }
    val entries by container.songIntroLog.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Ios5SettingsPage("解说记录（${entries.size}）", onBack) {
        ios5Section("管理") {
            Ios5ActionRow(
                title = "清空全部记录",
                danger = true,
                onClick = {
                    scope.launch {
                        container.songIntroLog.clear()
                        confirm("已清空")
                    }
                },
            )
        }
        if (entries.isEmpty()) {
            item { Ios5Empty("还没有解说记录\n点播放页的问号试一次") }
        } else {
            entries.forEach { e ->
                item { LogCard(e) }
            }
        }
        ios5FootNote("只保留最近 50 次，含发给 LLM 的 ID3 输入与完整介绍文本。")
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}

@Composable
private fun LogCard(e: SongIntroLogEntry) {
    Ios5Group(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                fmtTime(e.timeMs) + " · " + e.model.ifBlank { "未知模型" } +
                    if (e.interrupted) " · 已中断" else "",
                color = Ios5Colors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                e.input.ifBlank { "（无输入）" },
                color = Color(0xFF6B7280),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(6.dp))
            when {
                e.error.isNotBlank() -> Text(
                    "失败：${e.error}",
                    color = Color(0xFFD63A3A),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                )
                e.result.isNotBlank() -> Text(
                    e.result,
                    color = Ios5Colors.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                )
                else -> Text(
                    "（无结果）",
                    color = Color(0xFF8E8E93),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun fmtTime(ms: Long): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(ms))
}.getOrDefault("")
