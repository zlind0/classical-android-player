package com.aurora.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.R
import com.aurora.music.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 曲库深扫的前台保活：大库全量扫描动辄十几分钟，用户切出扫描界面、
 * 切后台、灭屏都不能停。用 dataSync 类型前台服务占住进程，
 * 进度条实时显示在通知栏；真被系统杀掉则靠断点文件下次启动续扫。
 *
 * 注意：本服务只保活 + 展示进度，扫描本身跑在 [AppContainer] 作用域的
 * [com.aurora.music.data.LibraryScanManager] 里，由它负责拉起/停止本服务。
 */
class LibraryScanService : Service() {

    private val container: AppContainer by lazy { (application as AuroraApplication).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private var lastPostMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startPlaceholderForeground()
        // 兜底：START_STICKY 重启等极端情况下若始终没有扫描，别留一条野通知。
        Handler(mainLooper).postDelayed({
            val p = runCatching { container.musicRoots.progress.value }.getOrNull()
            val scanning = runCatching { container.scanManager.scanningIds.value }.getOrNull().orEmpty()
            if (p?.running != true && scanning.isEmpty()) stopSelf()
        }, STALE_GUARD_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { container.scanManager.cancelAll() }
            stopSelf()
            return START_NOT_STICKY
        }
        collectProgress()
        return START_STICKY
    }

    // 划掉任务不停止：用户要的就是切出去继续扫；断点机制兜底进程死亡。
    override fun onTaskRemoved(rootIntent: Intent?) = Unit.also { super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        collectJob?.cancel()
        collectJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.scan_notif_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopAction(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, LibraryScanService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun startPlaceholderForeground() {
        channel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.scan_notif_title))
            .setContentText(getString(R.string.scan_notif_listing))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(activityIntent())
            .addAction(0, getString(R.string.scan_notif_cancel), stopAction())
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun collectProgress() {
        if (collectJob?.isActive == true) return
        collectJob = scope.launch {
            container.musicRoots.progress.collect { p ->
                val now = System.currentTimeMillis()
                val finished = !p.running
                // 节流：进行中 1s 刷一次，结束每次都刷
                if (!finished && now - lastPostMs < 1000L) return@collect
                lastPostMs = now
                runCatching { postProgress(p.running, p.found, p.total, p.current) }
                if (finished && p.total > 0) {
                    // 结束态展示一下即撤，管理器随后也会 stop
                    kotlinx.coroutines.delay(3000)
                    stopSelf()
                    return@collect
                }
            }
        }
    }

    private fun postProgress(running: Boolean, found: Int, total: Int, current: String) {
        val nm = if (Build.VERSION.SDK_INT >= 23) {
            getSystemService(NotificationManager::class.java)
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        } ?: return
        val text = if (total > 0) getString(R.string.scan_notif_text, found, total, current)
        else getString(R.string.scan_notif_listing)
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.scan_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(running)
            .setSilent(true)
            .setContentIntent(activityIntent())
        if (running) {
            b.setProgress(total, found, total <= 0)
                .addAction(0, getString(R.string.scan_notif_cancel), stopAction())
        } else {
            b.setProgress(0, 0, false)
        }
        nm.notify(NOTIF_ID, b.build())
    }

    companion object {
        private const val CHANNEL_ID = "aurora_scan"
        private const val NOTIF_ID = 1002
        private const val STALE_GUARD_MS = 60_000L
        private const val ACTION_STOP = "com.aurora.music.scan.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LibraryScanService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LibraryScanService::class.java)) }
        }
    }
}
