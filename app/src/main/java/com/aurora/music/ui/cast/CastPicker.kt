package com.aurora.music.ui.cast

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.CastContentType
import com.aurora.music.data.ebook.EbookTheme
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5SegmentRow

private val pickerBlue = Color(0xFF0A60D6)
private val pickerRed = Color(0xFFD63A3A)
private val pickerSecondary = Color(0xFF8E8E93)

/**
 * 投屏目标选择卡：本机 / 浏览器 / 局域网里发现的其他 Auroura 设备。
 * 音乐页和阅读页共用；[CastContentType] 决定点设备后投音乐还是投整本书。
 */
@Composable
fun CastPickerCard(
    contentType: CastContentType,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val cast by container.ebookCast.state.collectAsStateWithLifecycle()
    val prefs by container.ebookPrefs.castPrefs.collectAsStateWithLifecycle()
    val sender by container.ebookCast.senderState.collectAsStateWithLifecycle()
    val devices by container.castDiscovery.devices.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val browserUrl = cast.lanUrl.ifBlank { cast.url }

    DisposableEffect(Unit) {
        container.castDiscovery.acquireBrowse()
        onDispose { container.castDiscovery.releaseBrowse() }
    }

    val webAudio = contentType == CastContentType.MUSIC &&
        cast.clientsConnected && prefs.musicOnCast(true)
    val deviceActive = sender.device != null
    val localActive = !deviceActive && !webAudio

    Column(
        modifier.fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "投屏到",
                color = Color(0xFF1A1D22),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.Refresh,
                "重新搜索",
                tint = pickerBlue,
                modifier = Modifier.size(28.dp).clip(CircleShape)
                    .clickable {
                        container.castDiscovery.releaseBrowse()
                        container.castDiscovery.acquireBrowse()
                    }.padding(5.dp),
            )
        }
        Spacer(Modifier.height(4.dp))

        PickerRow(
            icon = Icons.Filled.Smartphone,
            title = "此设备",
            subtitle = if (localActive) "声音从本机播放" else "切回本机播放",
            selected = localActive,
            onClick = {
                container.ebookCast.stop()
                container.ebookPrefs.setCastMusicOnCast(true, false)
                onDismiss()
            },
        )

        PickerRow(
            icon = if (webAudio) Icons.Filled.CastConnected else Icons.Filled.Language,
            title = "浏览器（网页）",
            subtitle = when {
                browserUrl.isBlank() -> "未连接 Wi-Fi，无法提供网页地址"
                cast.clientsConnected -> "网页已连接 · 点按让浏览器出声"
                else -> "同一 Wi-Fi 打开 $browserUrl"
            },
            selected = webAudio,
            enabled = browserUrl.isNotBlank(),
            onClick = {
                if (browserUrl.isBlank()) return@PickerRow
                clipboard.setText(AnnotatedString(browserUrl))
                if (contentType == CastContentType.MUSIC) {
                    container.ebookCast.stopDeviceOnly()
                    container.ebookPrefs.setCastMusicOnCast(true, true)
                    val msg = if (cast.clientsConnected) {
                        "已切换到网页出声"
                    } else {
                        "已复制网页地址，在浏览器打开即可"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } else {
                    container.ebookCast.start(com.aurora.music.data.ebook.EbookCastMode.WEB)
                }
                onDismiss()
            },
        )

        if (sender.device != null) {
            PickerRow(
                icon = Icons.Filled.Stop,
                title = "结束投屏",
                subtitle = buildString {
                    append(sender.device?.name.orEmpty())
                    append(" · ")
                    append(
                        when {
                            sender.connected -> "已连接"
                            sender.waiting -> "等待对方接受…"
                            else -> "已断开"
                        },
                    )
                },
                selected = false,
                danger = true,
                onClick = {
                    container.ebookCast.stop()
                    onDismiss()
                },
            )
        }

        Spacer(Modifier.height(6.dp))
        Divider()
        Text(
            "附近设备",
            color = pickerSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Ios5Sans,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        val visible = devices.filter { it.kind == com.aurora.music.data.cast.CastDeviceKind.APP }
        if (visible.isEmpty()) {
            Text(
                "正在搜索同一 Wi-Fi 下的设备…",
                color = pickerSecondary,
                fontSize = 12.sp,
                fontFamily = Ios5Sans,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        } else {
            visible.forEach { d ->
                val isTarget = sender.device?.id == d.id
                PickerRow(
                    icon = if (isTarget && sender.connected) Icons.Filled.CastConnected else Icons.Filled.Cast,
                    title = d.name,
                    subtitle = when {
                        isTarget && sender.connected -> "已连接 · 正在投屏"
                        isTarget && sender.waiting -> "等待对方接受…"
                        isTarget -> "已选择"
                        else -> "${d.host} · 点按投屏"
                    },
                    selected = isTarget,
                    onClick = {
                        if (!isTarget) {
                            if (contentType == CastContentType.MUSIC) {
                                container.ebookCast.castMusicTo(d)
                            } else {
                                container.ebookCast.startDevice(d)
                            }
                        }
                        onDismiss()
                    },
                )
            }
        }

        if (contentType == CastContentType.MUSIC) {
            Spacer(Modifier.height(6.dp))
            Divider()
            val themeLabels = listOf("白色", "棕色", "深色")
            val themeValues = listOf(EbookTheme.WHITE, EbookTheme.SEPIA, EbookTheme.DARK)
            Ios5SegmentRow(
                title = "页面风格",
                options = themeLabels,
                selected = themeValues.indexOf(prefs.theme).coerceAtLeast(0),
                onSelect = { container.ebookPrefs.setCastTheme(themeValues[it]) },
            )
        }
    }
}

@Composable
private fun Divider() {
    Spacer(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            .height(1.dp).background(Color(0xFFE3E4E8)),
    )
}

@Composable
private fun PickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        danger -> pickerRed
        selected -> pickerBlue
        else -> Color(0xFF3E444D)
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) pickerRed else Color(0xFF1A1D22),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Ios5Sans,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = pickerSecondary,
                    fontSize = 11.sp,
                    fontFamily = Ios5Sans,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected && !danger) {
            Icon(Icons.Filled.Check, "当前", tint = pickerBlue, modifier = Modifier.size(18.dp))
        }
    }
}
