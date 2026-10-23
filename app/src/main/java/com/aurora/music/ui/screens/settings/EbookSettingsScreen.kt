package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ebook.MAX_RECENT_BOOKS
import com.aurora.music.data.ebook.MIN_RECENT_BOOKS
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.ui.ios5.ios5Section
import kotlin.math.roundToInt

// 电子书设置：目前只有「最近阅读数量」。阅读外观（底色/字体/字号）在阅读页的选项里，
// 来源与扫描在「电子书来源」页，这里只放书架层面的偏好。
@Composable
fun EbookSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val container = (LocalContext.current.applicationContext as AuroraApplication).container
    val saved by container.ebookPrefs.recentLimit.collectAsStateWithLifecycle()
    // 拖动只改本地预览，抬手才落盘（与阅读页字号滑杆同一交互）
    var draft by remember(saved) { mutableIntStateOf(saved) }
    val bottomPad = contentPadding.calculateBottomPadding()

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
        item { Spacer(Modifier.height(bottomPad)) }
    }
}
