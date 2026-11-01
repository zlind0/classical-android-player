package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.AuroraApplication
import com.aurora.music.data.relay.RelaySettings
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5Section

/**
 * 公网中继（PodRelay）设置。
 * 中继服务器只保存加密分片；媒体密钥只在本机与 NAS 上。
 */
@Composable
fun RelaySettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val service = container.relayService
    val state by service.state.collectAsState()
    val stored = service.config.settings.collectAsState()

    var enabled by remember { mutableStateOf(stored.value.enabled) }
    var addr by remember { mutableStateOf(stored.value.addr) }
    var fingerprint by remember { mutableStateOf(stored.value.fingerprint) }
    var certPem by remember { mutableStateOf(stored.value.certPem) }
    var keyPem by remember { mutableStateOf(stored.value.keyPem) }
    var mediaKey by remember { mutableStateOf(stored.value.mediaKey) }
    var message by remember { mutableStateOf<String?>(null) }

    fun save() {
        service.saveSettings(
            RelaySettings(
                enabled = enabled,
                addr = addr.trim(),
                fingerprint = fingerprint.trim(),
                certPem = certPem,
                keyPem = keyPem,
                mediaKey = mediaKey.trim(),
                proxyPort = stored.value.proxyPort,
            ),
        )
        message = "已保存"
    }

    Ios5SettingsPage("公网中继", onBack) {
        ios5Section("状态") {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                val statusText = when {
                    !enabled -> "未启用"
                    !state.configured -> "配置不完整"
                    state.connected && state.hostOnline -> "已连接 · 源设备在线"
                    state.connected -> "已连接 · 源设备离线"
                    else -> "未连接"
                }
                Text(statusText, color = Ios5Colors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val detail = buildString {
                    if (state.deviceName.isNotBlank()) append(state.deviceName).append(" · ")
                    append("快照版本 ").append(state.libVersion)
                    if (state.proxyPort > 0) append(" · 本地端口 ").append(state.proxyPort)
                }
                Text(detail, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
                state.lastError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Ios5Colors.TextSecondary, fontSize = 12.sp)
                }
                message?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Ios5Colors.TextSecondary, fontSize = 12.sp)
                }
            }
        }

        ios5Section("连接") {
            Ios5SwitchRow(
                title = "启用中继",
                subtitle = "关闭后不会连接公网服务器，局域网共享不受影响",
                checked = enabled,
                onCheckedChange = { enabled = it },
            )
            Ios5TextRow("中继地址", addr, "relay.example.com:21443") { addr = it }
            Ios5TextRow("服务器指纹", fingerprint, "sha256/…") { fingerprint = it }
        }

        ios5Section("证书") {
            Ios5TextRow(
                title = "客户端证书 (client.crt PEM)",
                value = certPem,
                placeholder = "-----BEGIN CERTIFICATE-----",
                singleLine = false,
            ) { certPem = it }
            Ios5TextRow(
                title = "客户端私钥 (client.key PKCS#8 PEM)",
                value = keyPem,
                placeholder = "-----BEGIN PRIVATE KEY-----",
                singleLine = false,
            ) { keyPem = it }
        }

        ios5Section("媒体密钥") {
            Ios5TextRow(
                title = "media_key（来自 NAS 面板）",
                value = mediaKey,
                placeholder = "Base64 编码的 32 字节密钥",
                singleLine = false,
            ) { mediaKey = it }
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                val keyId = RelaySettings(mediaKey = mediaKey).keyId
                Text(
                    "key_id：${if (keyId == 0L) "无效" else java.lang.Long.toHexString(keyId)}",
                    color = Ios5Colors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        ios5Section("操作") {
            Ios5ActionRow("保存并应用", onClick = { save() })
        }
    }
}
