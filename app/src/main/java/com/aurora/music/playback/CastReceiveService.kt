package com.aurora.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.R
import com.aurora.music.data.cast.receiver.CastReceiverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service kept alive while the phone is receiving a cast, so the
 * OS does not freeze the RTP/decoder threads when the app goes to background.
 *
 * It deliberately does not own playback: the Hub is the source of truth and
 * this service only mirrors its state into a notification.
 */
class CastReceiveService : Service() {

    private val container by lazy { (application as AuroraApplication).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(null))
        observeJob = scope.launch {
            container.castHub.state.collectLatest { state ->
                if (state is CastReceiverState.Idle) {
                    stopSelf()
                } else {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.castHub.stopSession()
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

    private fun buildNotification(state: CastReceiverState?): Notification {
        val title = when (state) {
            is CastReceiverState.Playing ->
                state.media.title.ifBlank { "${state.protocol.label} 投屏" }
            is CastReceiverState.Connecting -> "${state.protocol.label} 正在连接"
            else -> "正在接收投屏"
        }
        val text = when (state) {
            is CastReceiverState.Playing ->
                listOfNotNull(
                    state.senderName.takeIf { it.isNotBlank() },
                    state.media.artist.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "正在接收投屏音频" }
            is CastReceiverState.Connecting -> state.senderName.ifBlank { "等待发送端" }
            else -> ""
        }
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
