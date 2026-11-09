package com.aurora.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurora.music.AuroraApplication
import com.aurora.music.data.cast.receiver.airplay.AirPlayConfig
import com.aurora.music.data.cast.receiver.airplay.AirPlayDiagnostics
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5Sans
import com.aurora.music.ui.ios5.Ios5SettingsPage
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5SwitchRow
import com.aurora.music.ui.ios5.ios5FootNote
import com.aurora.music.ui.ios5.ios5Section
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AirPlay 设置与诊断：先设置（固定缓冲 / 重传 / PLC / 日志），再实时诊断。
 *
 * 「有封面没声音」「偶发卡顿」需要同时观察网络、抖动缓冲和输出状态；
 * 计数器始终工作，日志与文件落盘由开关控制。
 */
@Composable
fun AirPlayDiagnosticsScreen(contentPadding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext as AuroraApplication
    val container = appContext.container
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val loggingEnabled by container.settingsStore.airPlayDebugLog
        .collectAsStateWithLifecycle(initialValue = false)
    val savedConfig by container.settingsStore.airPlayConfig
        .collectAsStateWithLifecycle(initialValue = AirPlayConfig.DEFAULT)

    // 滑块拖动时先改本地 draft，松手才写 DataStore，避免每帧都写。
    var config by remember { mutableStateOf(savedConfig) }
    LaunchedEffect(savedConfig) { config = savedConfig }

    fun persist(next: AirPlayConfig) {
        config = next.sanitized()
        scope.launch { container.settingsStore.setAirPlayConfig(config) }
    }

    var snapshot by remember { mutableStateOf(AirPlayDiagnostics.snapshot()) }
    var packetsPerSec by remember { mutableStateOf(0) }
    var lastPollMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        var lastMs = System.currentTimeMillis()
        var lastPackets = snapshot.rtp.packets
        while (true) {
            delay(500)
            val s = AirPlayDiagnostics.snapshot()
            val now = System.currentTimeMillis()
            val dt = (now - lastMs).coerceAtLeast(1)
            packetsPerSec = ((s.rtp.packets - lastPackets) * 1000L / dt).toInt()
            lastMs = now
            lastPackets = s.rtp.packets
            lastPollMs = now
            snapshot = s
        }
    }

    val session = snapshot.session
    val rtp = snapshot.rtp
    val out = snapshot.output
    val tl = snapshot.timeline
    val now = lastPollMs
    val lastPacketAge = if (session.lastPacketAtMs > 0) (now - session.lastPacketAtMs).coerceAtLeast(0) else -1L
    val lossPct = if (rtp.packets + rtp.lostPackets > 0) {
        rtp.lostPackets * 100.0 / (rtp.packets + rtp.lostPackets)
    } else 0.0
    val avgBlockedMs = if (out.sinkBlockedWrites > 0) out.sinkBlockedMs / out.sinkBlockedWrites else 0L

    Ios5SettingsPage("AirPlay 设置与诊断", onBack) {
        // ------------------------------------------------------------ settings
        ios5Section("缓冲") {
            Ios5SliderRow(
                title = "固定缓冲时间",
                valueLabel = "${config.bufferMs} ms",
                value = config.bufferMs.toFloat(),
                range = AirPlayConfig.MIN_BUFFER_MS.toFloat()..AirPlayConfig.MAX_BUFFER_MS.toFloat(),
                steps = 19, // 50ms 一档：500..1500
                onValueChangeFinished = { persist(config) },
                onValueChange = {
                    config = config.copy(bufferMs = (it / 50f).roundToInt() * 50)
                },
            )
            Ios5CellDivider()
            StatRow("环形缓冲物理大小", "${AirPlayConfig.RING_MS} ms（固定）")
            Ios5CellDivider()
            StatRow("上报发送端 Audio-Latency", "${config.bufferMs} ms")
        }

        ios5Section("重传") {
            Ios5SwitchRow(
                title = "自动等待重传",
                subtitle = "跟随缓冲时间，当前 ${config.effectiveResendWaitMs} ms",
                checked = config.resendAuto,
            ) { enabled ->
                persist(config.copy(resendAuto = enabled))
            }
            if (!config.resendAuto) {
                Ios5CellDivider()
                Ios5SliderRow(
                    title = "重传等待时间",
                    valueLabel = "${config.resendWaitMs} ms",
                    value = config.resendWaitMs.toFloat(),
                    range = AirPlayConfig.MIN_RESEND_WAIT_MS.toFloat()..AirPlayConfig.MAX_RESEND_WAIT_MS.toFloat(),
                    steps = 26, // 10ms 一档
                    onValueChangeFinished = { persist(config) },
                    onValueChange = {
                        config = config.copy(resendWaitMs = (it / 10f).roundToInt() * 10)
                    },
                )
            }
            Ios5CellDivider()
            Ios5SliderRow(
                title = "重传重试间隔",
                valueLabel = "${config.resendRetryMs} ms",
                value = config.resendRetryMs.toFloat(),
                range = AirPlayConfig.MIN_RESEND_RETRY_MS.toFloat()..AirPlayConfig.MAX_RESEND_RETRY_MS.toFloat(),
                steps = 16, // 10ms 一档
                onValueChangeFinished = { persist(config) },
                onValueChange = {
                    config = config.copy(resendRetryMs = (it / 10f).roundToInt() * 10)
                },
            )
            Ios5CellDivider()
            Ios5SliderRow(
                title = "重传最大次数",
                valueLabel = "${config.resendMaxRetries} 次",
                value = config.resendMaxRetries.toFloat(),
                range = 0f..AirPlayConfig.MAX_RESEND_RETRIES.toFloat(),
                steps = AirPlayConfig.MAX_RESEND_RETRIES - 1,
                onValueChangeFinished = { persist(config) },
                onValueChange = {
                    config = config.copy(resendMaxRetries = it.roundToInt())
                },
            )
        }

        ios5Section("丢包隐藏") {
            Ios5SwitchRow(
                title = "短缺口使用 PLC",
                subtitle = "用上一段音频衰减填充，代替硬切静音",
                checked = config.plcEnabled,
            ) { enabled ->
                persist(config.copy(plcEnabled = enabled))
            }
            if (config.plcEnabled) {
                Ios5CellDivider()
                Ios5SliderRow(
                    title = "PLC 最大时长",
                    valueLabel = "${config.plcMaxMs} ms",
                    value = config.plcMaxMs.toFloat(),
                    range = AirPlayConfig.MIN_PLC_MAX_MS.toFloat()..AirPlayConfig.MAX_PLC_MAX_MS.toFloat(),
                    steps = 6, // 10ms 一档
                    onValueChangeFinished = { persist(config) },
                    onValueChange = {
                        config = config.copy(plcMaxMs = (it / 10f).roundToInt() * 10)
                    },
                )
            }
        }

        ios5Section("日志") {
            Ios5SwitchRow(
                title = "记录诊断日志",
                subtitle = if (loggingEnabled) {
                    "已开启：写入 Logcat 和文件，复现问题时保持打开"
                } else {
                    "已关闭：不产生日志开销，实时计数器仍然工作"
                },
                checked = loggingEnabled,
            ) { enabled ->
                scope.launch { container.settingsStore.setAirPlayDebugLog(enabled) }
            }
            Ios5CellDivider()
            Ios5ActionRow("复制当前日志（${snapshot.logs.size} 条）") {
                val text = buildString {
                    appendLine("nostalgiaPod AirPlay diagnostics")
                    appendLine("session: ${session.sender} ${session.codec} ${session.sampleRate}Hz")
                    appendLine("buffer=${config.bufferMs}ms wait=${config.effectiveResendWaitMs}ms")
                    snapshot.logs.forEach { e ->
                        appendLine("${formatTime(e.timeMs)} [${e.level.name.first()}/${e.tag}] ${e.message}")
                    }
                }
                clipboard.setText(AnnotatedString(text))
            }
            Ios5CellDivider()
            Ios5ActionRow("清空日志", danger = true) {
                AirPlayDiagnostics.clearLogs()
                snapshot = AirPlayDiagnostics.snapshot()
            }
        }

        // --------------------------------------------------------- diagnostics
        ios5Section("当前参数") {
            StatRow("固定缓冲", "${config.bufferMs} ms")
            Ios5CellDivider()
            StatRow("实际目标 cushion", tl?.let { "${it.tunedCushionMs} ms" } ?: "${config.bufferMs} ms")
            Ios5CellDivider()
            StatRow("重传等待 / 重试", "${config.effectiveResendWaitMs} ms / ${config.resendRetryMs} ms")
            Ios5CellDivider()
            StatRow("PLC", if (config.plcEnabled) "开 · ≤${config.plcMaxMs} ms" else "关")
        }

        ios5Section("接收状态") {
            StatRow("会话", if (session.active) "活跃" else "空闲")
            Ios5CellDivider()
            StatRow("发送端", session.sender.ifBlank { "—" })
            Ios5CellDivider()
            StatRow("编码 / 采样率", if (session.active) {
                "${session.codec} · ${session.sampleRate} Hz · ${session.channels} ch"
            } else "—")
            Ios5CellDivider()
            StatRow("加密", if (session.active) if (session.encrypted) "AES-128-CBC" else "明文" else "—")
            Ios5CellDivider()
            StatRow("输出状态", outputState(out))
            Ios5CellDivider()
            StatRow("音频焦点", when {
                !out.running -> "—"
                out.focusPaused -> "被其它应用占用（已暂停）"
                out.focusHeld -> "已持有"
                else -> "未持有"
            })
            Ios5CellDivider()
            StatRow("发送端音量", if (session.active) "%.0f%%".format(out.volume * 100) else "—")
            Ios5CellDivider()
            StatRow("最后一包", if (lastPacketAge >= 0) "${lastPacketAge} ms 前" else "未收到")
        }

        ios5Section("缓冲区") {
            StatRow("缓冲区堆积", tl?.let { "${it.backlogMs} ms" } ?: "—")
            Ios5CellDivider()
            StatRow("目标缓冲", "${config.bufferMs} ms")
            Ios5CellDivider()
            StatRow("Sink 缓冲", if (out.running) "${out.sinkBufferMs} ms" else "—")
            Ios5CellDivider()
            StatRow("欠载 / Underrun", "${out.sinkUnderruns} 次（sink） / ${tl?.underruns ?: 0} 次（jitter）")
            Ios5CellDivider()
            StatRow("裁剪 / 补静音 / PLC", "${tl?.trims ?: 0} / ${tl?.silences ?: 0} / ${tl?.conceals ?: 0}")
            Ios5CellDivider()
            StatRow("迟到丢弃", "${tl?.drops ?: 0} 包")
            Ios5CellDivider()
            StatRow("Sink 反压写入", if (out.sinkBlockedWrites > 0) {
                "${out.sinkBlockedWrites} 次 · 平均 ${avgBlockedMs} ms"
            } else "0 次")
            Ios5CellDivider()
            StatRow("反压累计等待", "${out.sinkBlockedMs} ms（重试 ${out.sinkBlockedRetries} 次）")
            Ios5CellDivider()
            StatRow("已写入帧", "${out.framesWritten} 帧")
        }

        ios5Section("网络 / RTP") {
            StatRow("收包 / 速率", "${rtp.packets} 包 · $packetsPerSec 包/秒")
            Ios5CellDivider()
            StatRow("丢包", "${rtp.lostPackets} 包 · %.1f%%".format(lossPct))
            Ios5CellDivider()
            StatRow("乱序 / 迟到", "${rtp.reorderedPackets} / ${rtp.latePackets}")
            Ios5CellDivider()
            StatRow("重复 / 解码失败", "${rtp.duplicatePackets} / ${rtp.decodeErrors}")
            Ios5CellDivider()
            StatRow("序列号重同步", "${rtp.sequenceResyncs} 次")
            Ios5CellDivider()
            StatRow("重传请求 / 收到", "${rtp.resendRequests} / ${rtp.resendPackets}")
            Ios5CellDivider()
            StatRow("已解码", "${rtp.decodedFrames} 帧")
            Ios5CellDivider()
            StatRow("累计流量", formatBytes(rtp.bytes))
        }

        if (snapshot.logs.isEmpty()) {
            item {
                Ios5Group(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp)) {
                    StatRow("日志", if (loggingEnabled) "等待事件…" else "日志已关闭")
                }
            }
        } else {
            item { LogSectionTitle("最近日志（${snapshot.logs.size}/${AirPlayDiagnostics.MAX_MEMORY_LINES}）") }
            // 最新一条在最上面，复现问题后不用一直往下翻。
            snapshot.logs.asReversed().take(120).forEach { entry ->
                item { LogRow(entry) }
            }
        }

        ios5FootNote(
            "固定缓冲越大越抗 2.4G 抖动，但起播和音画延迟也越大；缓冲时间同时作为 " +
                "Audio-Latency 上报给 iOS。中位建议 800ms，网络差可到 1200ms。" +
                "\nSink 反压是正常的实时节流（AudioTrack 满了就等它消费），" +
                "只有平均等待明显变大或 underrun 增长时才需要关注。",
        )
        AirPlayDiagnostics.logFilePath()?.let { path ->
            ios5FootNote("日志文件：$path")
        }
        item { Spacer(Modifier.height(contentPadding.calculateBottomPadding())) }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Ios5Colors.TextSecondary,
            fontSize = 14.sp,
            fontFamily = Ios5Sans,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            color = Ios5Colors.TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun LogSectionTitle(text: String) {
    Text(
        text,
        color = Ios5Colors.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = Ios5Sans,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun LogRow(entry: AirPlayDiagnostics.LogEntry) {
    val color = when (entry.level) {
        AirPlayDiagnostics.Level.ERROR -> androidx.compose.ui.graphics.Color(0xFFB00020)
        AirPlayDiagnostics.Level.WARN -> androidx.compose.ui.graphics.Color(0xFFB26A00)
        AirPlayDiagnostics.Level.INFO -> Ios5Colors.TextPrimary
        AirPlayDiagnostics.Level.DEBUG -> Ios5Colors.TextSecondary
    }
    Ios5Group(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(entry.timeMs),
                    color = Ios5Colors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.tag,
                    color = Ios5Colors.IosBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                entry.message,
                color = color,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun outputState(out: AirPlayDiagnostics.OutputInfo): String = when {
    !out.running -> "未运行"
    out.userPaused -> "用户暂停"
    out.focusPaused -> "焦点丢失（已暂停）"
    out.paused -> "已暂停"
    else -> "输出中"
}

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

private fun formatTime(ms: Long): String = timeFormat.get()!!.format(Date(ms))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
