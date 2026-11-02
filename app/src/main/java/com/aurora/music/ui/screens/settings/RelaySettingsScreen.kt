package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.AuroraApplication
import com.aurora.music.data.relay.RelayService
import com.aurora.music.data.relay.RelaySettings
import com.aurora.music.ui.components.QrScannerDialog
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.Ios5TextRow
import com.aurora.music.ui.ios5.ios5Section

/**
 * 公网中继（PodRelay）。默认流程：NAS 面板点「添加设备」显示二维码 →
 * 这里内置的扫描器（或系统相机）扫码即可；也可以粘贴跨网络的远程配对字符串。
 */
@Composable
fun RelaySettingsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    val service = container.relayService
    val state by service.state.collectAsState()
    val pairState by service.pairState.collectAsState()
    val stored = service.config.settings.collectAsState()

    var pairText by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(stored.value.enabled) }
    var addr by remember { mutableStateOf(stored.value.addr) }
    var fingerprint by remember { mutableStateOf(stored.value.fingerprint) }
    var certPem by remember { mutableStateOf(stored.value.certPem) }
    var keyPem by remember { mutableStateOf(stored.value.keyPem) }
    var mediaKey by remember { mutableStateOf(stored.value.mediaKey) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun saveManual() {
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
        ios5Section("配对") {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    "1. 在电脑端「公网中继」里点「添加设备」显示二维码",
                    color = Ios5Colors.TextPrimary,
                    fontSize = 13.sp,
                )
                Text(
                    "2. 手机连同一个 Wi-Fi，用系统相机扫码并点击链接",
                    color = Ios5Colors.TextPrimary,
                    fontSize = 13.sp,
                )
                Text(
                    "3. 也可以在电脑端生成「远程配对字符串」（可选择有效期），粘贴到下方即可跨网络配对",
                    color = Ios5Colors.TextSecondary,
                    fontSize = 12.sp,
                )
                val pairInfo = when (val current = pairState) {
                    is RelayService.PairState.Joining -> "正在与 NAS 配对…"
                    is RelayService.PairState.Joined -> "已加入「${current.deviceName}」"
                    is RelayService.PairState.Failed -> "配对失败：${current.message}"
                    else -> null
                }
                pairInfo?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Ios5Colors.TextSecondary, fontSize = 12.sp)
                }
                message?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Ios5Colors.TextSecondary, fontSize = 12.sp)
                }
            }
            Ios5ActionRow("扫描配对二维码") { scanning = true }
            Ios5TextRow(
                title = "配对码",
                value = pairText,
                placeholder = "podrelay://pair?… 或 podrelay://remote?…",
                singleLine = false,
            ) { pairText = it }
            Ios5ActionRow("使用配对码加入") {
                val handled = service.acceptPairUri(pairText.trim())
                message = if (handled) null else "配对码格式不正确"
            }
            if (scanning) {
                QrScannerDialog(
                    onDismiss = { scanning = false },
                    onResult = { scanned ->
                        scanning = false
                        pairText = scanned.trim()
                        val handled = service.acceptPairUri(scanned.trim())
                        message = if (handled) null else "二维码不是有效的配对码"
                    },
                )
            }
        }

        ios5Section("状态") {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                val statusText = when {
                    state.authFailed -> "认证失败"
                    !state.enabled -> "未启用"
                    !state.configured -> "配置不完整"
                    state.connected && state.hostOnline -> "已连接 · 源设备在线"
                    state.connected -> "已连接 · 源设备离线"
                    else -> "未连接"
                }
                Text(
                    statusText,
                    color = if (state.authFailed) Color(0xFFD63A3A) else Ios5Colors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                val detail = buildString {
                    if (state.deviceName.isNotBlank()) append(state.deviceName).append(" · ")
                    append("快照版本 ").append(state.libVersion)
                    if (state.proxyPort > 0) append(" · 本地端口 ").append(state.proxyPort)
                }
                Text(detail, color = Ios5Colors.TextSecondary, fontSize = 13.sp)
                state.lastError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        color = if (state.authFailed) Color(0xFFD63A3A) else Ios5Colors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                if (state.authFailed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这台手机的中继配置已失效。删除配置后，在电脑端重新生成配对码并扫码加入。",
                        color = Color(0xFFD63A3A),
                        fontSize = 12.sp,
                    )
                }
            }
            // 无论认证成功与否都保留删除入口，避免旧配置把手机卡死。
            Ios5ActionRow("删除中继配置", danger = true) { confirmDelete = true }
        }

        ios5Section("高级") {
            Ios5ActionRow(if (advanced) "收起手动配置" else "手动配置（跨网段/调试）") {
                advanced = !advanced
            }
            if (advanced) {
                Ios5SwitchRow(
                    title = "启用中继",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                Ios5TextRow("中继地址", addr, "relay.example.com:21443") { addr = it }
                Ios5TextRow("服务器指纹", fingerprint, "sha256/…") { fingerprint = it }
                Ios5TextRow(
                    title = "客户端证书 (PEM)",
                    value = certPem,
                    placeholder = "-----BEGIN CERTIFICATE-----",
                    singleLine = false,
                ) { certPem = it }
                Ios5TextRow(
                    title = "客户端私钥 (PKCS#8 PEM)",
                    value = keyPem,
                    placeholder = "-----BEGIN PRIVATE KEY-----",
                    singleLine = false,
                ) { keyPem = it }
                Ios5TextRow(
                    title = "media_key",
                    value = mediaKey,
                    placeholder = "Base64 编码的 32 字节密钥",
                    singleLine = false,
                ) { mediaKey = it }
                Ios5ActionRow("保存手动配置") { saveManual() }
            }
        }

        item {
            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("删除中继配置？") },
                    text = {
                        Text("将清除本机保存的证书、密钥和快照。删除后需要在电脑端重新生成配对码并扫码加入。")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmDelete = false
                                service.clearConfig()
                                enabled = false
                                addr = ""
                                fingerprint = ""
                                certPem = ""
                                keyPem = ""
                                mediaKey = ""
                                pairText = ""
                                message = "已删除中继配置"
                            },
                        ) {
                            Text("删除", color = Color(0xFFD63A3A))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                    },
                )
            }
        }
    }
}
