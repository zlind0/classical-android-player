package com.aurora.music.data

import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 睡眠定时（读完当前单位再停，一次性）：
 * deadlineElapsed = 到点时刻（elapsedRealtime 系）；
 * totalMinutes = 倒计时分钟（倒计时方式才有）；clockLabel = 定时时钟 "HH:mm"（定时方式才有）。
 */
data class SleepTimer(
    val deadlineElapsed: Long,
    val totalMinutes: Int?,
    val clockLabel: String?,
)

/** 距下一个 HH:mm 的毫秒数（已过则明天，纯函数，可单测）。 */
fun delayUntilNextClock(hour: Int, minute: Int, nowMillis: Long): Long {
    val target = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (target.timeInMillis <= nowMillis) target.add(Calendar.DATE, 1)
    return (target.timeInMillis - nowMillis).coerceAtLeast(0)
}

/**
 * 全局睡眠定时后台（App 作用域，进程活着就一直记着）：
 * 电子书选项页与音乐页月亮气泡共用同一后台，后设的生效。
 * - 手动停播/离页/切书/切音色都不清定时，只有关闭、到点收尾、进程死亡才消失。
 * - 到点看门狗只停旁路媒体（音乐 + 站外，见 onFired），不断听书；
 *   听书若在播，由朗读主循环读完当前再停并调 [consumeFired] 收尾。
 * - 到点时若主路（听书）没在播，看门狗直接收尾，避免标题永远停在 00:00。
 */
class SleepTimerWorker(
    private val nowElapsed: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val nowWall: () -> Long = { System.currentTimeMillis() },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _timer = MutableStateFlow<SleepTimer?>(null)
    val timer: StateFlow<SleepTimer?> = _timer.asStateFlow()

    /** 到点回调（AppContainer 注入）：暂停旁路媒体用，幂等多调无害。 */
    var onFired: (() -> Unit)? = null

    /** 主路是否活跃（AppContainer 注入听书 playing）：到点时主路没在播则直接收尾。 */
    var primaryActive: (() -> Boolean)? = null

    private var watchJob: Job? = null

    /** 倒计时：从现在起 minutes 分钟后到点。 */
    fun setMinutes(minutes: Int) {
        if (minutes <= 0) {
            cancel()
            return
        }
        _timer.value = SleepTimer(
            deadlineElapsed = nowElapsed() + minutes * 60_000L,
            totalMinutes = minutes,
            clockLabel = null,
        )
        armWatchdog()
    }

    /** 定时：下一个 HH:mm 到点（已过则明天）。 */
    fun setAt(hour: Int, minute: Int) {
        val delayMs = delayUntilNextClock(hour, minute, nowWall())
        _timer.value = SleepTimer(
            deadlineElapsed = nowElapsed() + delayMs,
            totalMinutes = null,
            clockLabel = "%02d:%02d".format(hour, minute),
        )
        armWatchdog()
    }

    fun cancel() {
        _timer.value = null
        watchJob?.cancel()
        watchJob = null
    }

    /**
     * 到点收尾（听书主循环读完当前后调）：清定时，顺手再停一次旁路（幂等）。
     * 看门狗已在到点执行过 onFired，这里是同一语义的兜底。
     */
    fun consumeFired() {
        _timer.value = null
        watchJob?.cancel()
        watchJob = null
        runCatching { onFired?.invoke() }
    }

    private fun armWatchdog() {
        watchJob?.cancel()
        val deadline = _timer.value?.deadlineElapsed ?: return
        val waitMs = (deadline - nowElapsed()).coerceAtLeast(0)
        watchJob = scope.launch {
            delay(waitMs)
            runCatching { onFired?.invoke() }
            // 主路没在播（或已播完）：直接收尾，不留 00:00；
            // 主路在播：留着定时，等主循环读完当前后 consumeFired。
            if (primaryActive?.invoke() != true) {
                _timer.value = null
                watchJob?.cancel()
                watchJob = null
            }
        }
    }
}
