package com.aurora.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.R
import com.aurora.music.data.cast.CastReceiveState
import com.aurora.music.data.cast.receiver.CastReceiverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service kept alive while the phone is receiving a cast, so the
 * OS does not freeze the RTP/decoder/Wi-Fi threads when the app goes to
 * background.
 *
 * It deliberately does not own playback: the Hub/receiver is the source of
 * truth and this service only mirrors their state into a notification.
 * 两种接收（自有协议 Pod / 标准协议 AirPlay）共用这一个前台服务，通知里
 * 显示当前这一路「正在播放」的曲目信息。
 */
class CastReceiveService : Service() {

    private val container by lazy { (application as AuroraApplication).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    // 播放进度/位置每几百毫秒推一次；标题和艺人没变就不重复 notify。
    private var lastTitle = ""
    private var lastText = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在接收投屏", ""))
        // 保证统一媒体会话存在：耳机/蓝牙/锁屏的播放键经 PlaybackService 的
        // MediaSession 转发到当前投屏接收（见 PlaybackService.onMediaButtonEvent）。
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
        observeJob = scope.launch {
            combine(container.castHub.state, container.castReceiver.state) { hub, pod -> hub to pod }
                .collectLatest { (hub, pod) ->
                    if (hub is CastReceiverState.Idle && pod is CastReceiveState.Idle) {
                        stopSelf()
                    } else {
                        val (title, text) = notificationCopy(hub, pod)
                        if (title != lastTitle || text != lastText) {
                            lastTitle = title
                            lastText = text
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(title, text))
                        }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.castHub.stopSession()
            container.castReceiver.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "投屏接收", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** 标题/正文只随曲目和发送端变化，供去重后再发通知。 */
    private fun notificationCopy(hub: CastReceiverState?, pod: CastReceiveState): Pair<String, String> {
        val podMusic = pod as? CastReceiveState.Music
        val airPlay = hub as? CastReceiverState.Playing
        val loading = pod as? CastReceiveState.Loading
        val title = when {
            podMusic != null -> podMusic.song.title.ifBlank { "正在接收投屏" }
            airPlay != null -> airPlay.media.title.ifBlank { "${airPlay.protocol.label} 投屏" }
            pod is CastReceiveState.Ebook -> pod.bookTitle.ifBlank { "阅读投屏" }
            loading != null -> "正在连接投屏"
            else -> "正在接收投屏"
        }
        val text = when {
            podMusic != null -> listOfNotNull(
                podMusic.senderName.takeIf { it.isNotBlank() },
                podMusic.song.artist.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "正在接收投屏音频" }
            airPlay != null -> listOfNotNull(
                airPlay.senderName.takeIf { it.isNotBlank() },
                airPlay.media.artist.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "正在接收投屏音频" }
            pod is CastReceiveState.Ebook -> listOfNotNull(
                pod.senderName.takeIf { it.isNotBlank() },
                pod.chapterTitle.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "正在接收投屏" }
            loading != null -> loading.senderName.ifBlank { "等待发送端" }
            else -> ""
        }
        return title to text
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CastReceiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止接收", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cast_receive"
        private const val NOTIFICATION_ID = 0x0CA57
        private const val ACTION_STOP = "com.aurora.music.action.CAST_RECEIVE_STOP"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, CastReceiveService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastReceiveService::class.java)) }
        }

        /** Called by the Hub when the session ends. */
        fun stopQuietly(context: Context) = stop(context)
    }
}
