package com.aurora.music.data.ebook.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI 到前台服务的进程内任务交接（段文本可能很大，不走 Intent）。 */
object PendingEbookExport {
    data class Job(
        val bookTitle: String,
        val segments: List<ExportSegment>,
        val settings: ExportSynthSettings,
        /** 有声书来源根目录。 */
        val outRoot: File,
        /** 导出完成后触发该来源重扫的有声书 rootId。 */
        val rootId: Long,
        val threads: Int,
        val overwrite: Boolean,
    )

    @Volatile var job: Job? = null
}

/** [EbookExportService] 与 UI 共享的导出状态。 */
object EbookExportRunner {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _progress = MutableStateFlow<EbookExportProgress?>(null)
    val progress: StateFlow<EbookExportProgress?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<EbookExportResult?>(null)
    val result: StateFlow<EbookExportResult?> = _result.asStateFlow()

    /** 导出完成后待重扫的有声书 rootId（UI 消费后清空）。 */
    private val _doneRootId = MutableStateFlow<Long?>(null)
    val doneRootId: StateFlow<Long?> = _doneRootId.asStateFlow()

    val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    internal suspend fun run(context: Context, job: PendingEbookExport.Job) {
        _running.value = true
        _result.value = null
        _progress.value = null
        _doneRootId.value = null
        cancelled.set(false)
        // 导出自己会按线程数各开一份内置语音模型；先把听书那条常驻会话放掉，
        // 免得「103MB 常驻 + N 份导出模型」叠在一起。
        runCatching {
            (context.applicationContext as? com.aurora.music.AuroraApplication)
                ?.container?.ttsWorker?.releaseSynthSession()
        }
        try {
            val res = EbookExporter(context).export(
                job.bookTitle, job.segments, job.settings, job.outRoot,
                job.threads, job.overwrite, cancelled,
            ) { _progress.value = it }
            _result.value = res
            // 只要落了文件（完成/取消/跳过）都重扫，让有声书里立刻能听。
            if (res.done > 0 || res.skipped > 0) _doneRootId.value = job.rootId
        } catch (t: Throwable) {
            _result.value = EbookExportResult(0, listOf(t.message ?: "导出失败"), cancelled.get())
        } finally {
            _progress.value = null
            _running.value = false
        }
    }

    fun clearResult() {
        _result.value = null
    }

    fun consumeDoneRoot(): Long? {
        val v = _doneRootId.value
        _doneRootId.value = null
        return v
    }
}

class EbookExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            EbookExportRunner.cancel()
            return START_NOT_STICKY
        }
        val job = PendingEbookExport.job
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        PendingEbookExport.job = null
        startFg()
        scope.launch {
            try {
                EbookExportRunner.run(this@EbookExportService, job)
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "电子书转有声书", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("正在转换有声读物")
            .setContentText("合成中…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (android.os.Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.aurora.music.action.EBOOK_EXPORT_CANCEL"
        private const val CHANNEL = "ebook_export"
        private const val NOTIF_ID = 21

        fun start(context: Context, job: PendingEbookExport.Job) {
            PendingEbookExport.job = job
            val intent = Intent(context, EbookExportService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            EbookExportRunner.cancel()
            context.startService(Intent(context, EbookExportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
