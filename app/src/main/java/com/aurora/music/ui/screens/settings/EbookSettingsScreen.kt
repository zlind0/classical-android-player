package com.aurora.music.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ebook.EbookDebugLog
import com.aurora.music.data.ebook.MAX_RECENT_BOOKS
import com.aurora.music.data.ebook.MIN_RECENT_BOOKS
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5Section
import kotlin.math.roundToInt

// 电子书设置：目前只有「最近阅读数量」。阅读外观（底色/字体/字号）在阅读页的选项里，
// 来源与扫描在「电子书来源」页，这里只放书架层面的偏好。
@Composable
fun EbookSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val saved by container.ebookPrefs.recentLimit.collectAsStateWithLifecycle()
    val debugLog by container.ebookPrefs.debugLog.collectAsStateWithLifecycle()
    // 拖动只改本地预览，抬手才落盘（与阅读页字号滑杆同一交互）
    var draft by remember(saved) { mutableIntStateOf(saved) }
    val bottomPad = contentPadding.calculateBottomPadding()
    var logOpen by remember { mutableStateOf(false) }

    Ios5SettingsPage(title = "电子书设置", onBack = onBack) {
        ios5Section("最近阅读") {
            Ios5SliderRow(
                title = "最近阅读数量",
                valueLabel = "${draft}本",
                value = draft.toFloat(),
                range = MIN_RECENT_BOOKS.toFloat()..MAX_RECENT_BOOKS.toFloat(),
                // 5..20 共 16 档 → steps = 14（positions = steps + 2）
                steps = MAX_RECENT_BOOKS - MIN_RECENT_BOOKS - 1,
                onValueChange = { draft = it.roundToInt() },
                onValueChangeFinished = { container.ebookPrefs.setRecentLimit(draft) },
            )
            Ios5StaticText("只影响电子书 Tab 顶部的「最近阅读」列表；首页固定显示 3 本。掉出列表的书不会丢进度，在书库里打开仍从上次位置继续。")
        }
        ios5Section("调试") {
            Ios5SwitchRow(
                title = "记录阅读进度日志",
                subtitle = "默认关闭。开启后记录锁屏/解锁、Activity 重建、分页窗口重建、进度落盘与恢复过程，用于排查进度问题。",
                checked = debugLog,
                onCheckedChange = { container.ebookPrefs.setDebugLog(it) },
            )
            Ios5ActionRow(title = "查看阅读进度日志", onClick = { logOpen = true })
            Ios5StaticText("日志只保留最近约 4MB，关闭开关后不再写入。")
        }
        item { Spacer(Modifier.height(bottomPad)) }
    }

    if (logOpen) {
        EbookDebugLogDialog(onDismiss = { logOpen = false })
    }
}

@Composable
private fun EbookDebugLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val text = remember(reload) {
        runCatching { EbookDebugLog.file(context).readText() }.getOrDefault("（暂无日志）")
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFFF2F2F7)).padding(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "阅读进度日志",
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1D22),
                    modifier = Modifier.weight(1f),
                )
                DialogAction("刷新") { reload++ }
                DialogAction("清空") {
                    runCatching { EbookDebugLog.file(context).delete() }
                    reload++
                }
                DialogAction("分享") {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "分享日志",
                            ),
                        )
                    }
                }
                DialogAction("关闭", onDismiss)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxSize().background(Color.White).padding(8.dp),
            ) {
                Text(
                    text,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF1A1D22),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun DialogAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        color = Color(0xFF0A60D6),
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
