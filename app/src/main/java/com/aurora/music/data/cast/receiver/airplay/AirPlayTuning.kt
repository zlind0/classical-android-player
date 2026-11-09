package com.aurora.music.data.cast.receiver.airplay

import kotlin.math.min

/**
 * AirPlay 音频接收的可调参数。
 *
 * 缓冲时间固定后，时间线缓冲不再依赖自适应抖动直方图：用户选多少毫秒，
 * 就按多少毫秒预缓冲、并把这个值作为 Audio-Latency 上报给发送端。
 */
data class AirPlayConfig(
    /** 固定抖动缓冲（ms），同时作为上报给发送端的 Audio-Latency。 */
    val bufferMs: Int = DEFAULT_BUFFER_MS,
    /** true = 重传等待时间跟随缓冲时间自动计算；false = 使用 [resendWaitMs]。 */
    val resendAuto: Boolean = true,
    /** 缺口最多等待多久再跳过（ms）。 */
    val resendWaitMs: Int = DEFAULT_RESEND_WAIT_MS,
    /** 缺口未补齐时，两次重传请求之间间隔（ms）。 */
    val resendRetryMs: Int = DEFAULT_RESEND_RETRY_MS,
    /** 首次请求之外，最多再重试几次。0 = 只请求一次。 */
    val resendMaxRetries: Int = DEFAULT_RESEND_MAX_RETRIES,
    /** 短缺口用上一段音频做 PLC，而不是直接补零。 */
    val plcEnabled: Boolean = true,
    /** 超过这个时长就不再 PLC，直接静音。 */
    val plcMaxMs: Int = DEFAULT_PLC_MAX_MS,
) {
    /** 实际使用的重传等待时间。 */
    val effectiveResendWaitMs: Int
        get() = if (resendAuto) {
            min(AUTO_WAIT_CAP_MS, (bufferMs - 20).coerceAtLeast(MIN_RESEND_WAIT_MS))
        } else {
            resendWaitMs.coerceIn(MIN_RESEND_WAIT_MS, MAX_RESEND_WAIT_MS)
        }

    fun sanitized(): AirPlayConfig = copy(
        bufferMs = bufferMs.coerceIn(MIN_BUFFER_MS, MAX_BUFFER_MS),
        resendWaitMs = resendWaitMs.coerceIn(MIN_RESEND_WAIT_MS, MAX_RESEND_WAIT_MS),
        resendRetryMs = resendRetryMs.coerceIn(MIN_RESEND_RETRY_MS, MAX_RESEND_RETRY_MS),
        resendMaxRetries = resendMaxRetries.coerceIn(0, MAX_RESEND_RETRIES),
        plcMaxMs = plcMaxMs.coerceIn(MIN_PLC_MAX_MS, MAX_PLC_MAX_MS),
    )

    companion object {
        /** 抖动缓冲可调范围：0.5s – 1.5s。 */
        const val MIN_BUFFER_MS = 500
        const val MAX_BUFFER_MS = 1500
        const val DEFAULT_BUFFER_MS = 800

        /** 环形缓冲的物理大小固定 2s。 */
        const val RING_MS = 2000

        const val MIN_RESEND_WAIT_MS = 30
        const val MAX_RESEND_WAIT_MS = 300
        const val DEFAULT_RESEND_WAIT_MS = 100
        const val AUTO_WAIT_CAP_MS = 100

        const val MIN_RESEND_RETRY_MS = 30
        const val MAX_RESEND_RETRY_MS = 200
        const val DEFAULT_RESEND_RETRY_MS = 60

        const val MAX_RESEND_RETRIES = 3
        const val DEFAULT_RESEND_MAX_RETRIES = 2

        const val MIN_PLC_MAX_MS = 10
        const val MAX_PLC_MAX_MS = 80
        const val DEFAULT_PLC_MAX_MS = 30

        val DEFAULT = AirPlayConfig()
    }
}

/** 全局运行时配置：DataStore 变化时由 AppContainer 写入，音频线程直接读取。 */
object AirPlayTuning {
    @Volatile var config: AirPlayConfig = AirPlayConfig.DEFAULT
}
