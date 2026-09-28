package com.aurora.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aurora.music.AuroraApplication
import com.aurora.music.MainActivity
import com.aurora.music.R
import com.aurora.music.data.AppContainer
import com.aurora.music.data.ebook.EbookTtsController
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 听书媒体会话服务：把阅读页底栏的「上一节 / 播放-停止 / 下一节」搬到系统通知栏，
 * 并让耳机、蓝牙、系统媒体中心等安卓媒体控制操作听书。
 *
 * 设计约束（勿动 [EbookTtsController] / [EbookTtsPlayer] 的任何合成与播放逻辑）：
 * - 本服务只做「转接」：会话播放器（[EbookTtsBridgePlayer]）本身不发声，
 *   所有命令原样转发给 App 作用域的 [EbookTtsController]（prev/next/stop/playFrom），
 *   再把控制器的 playing/position 状态回報给系统。
 * - 听书没有暂停，只有播放/停止：系统下达暂停（通知栏暂停键、耳机暂停、自动暂停等）
 *   一律走 [EbookTtsController.stop]，并向系统回報已暂停（通知栏变回播放键）；
 *   系统下达播放，则从最近一次停止的段落 [EbookTtsController.playFrom] 继续，
 *   与 App 内点播放的语义一致（App 内从当前页顶起读，通知栏/耳机处无页面概念，
 *   故用最近停止的段落，二者都是「从断点继续」，没有暂停态）。
 * - 上一节/下一节直接调 [EbookTtsController.prev]/[EbookTtsController.next]，
 *   未在朗读时与 App 内一样无动作（句子切片模式下即上一句/下一句）。
 * - 通知栏标题用当前段落预览、作者用书名；本服务不读整书、不做任何 IO，
 *   对合成与播放时序零影响（首声 latency 完全由既有 TTS 逻辑决定）。
 *
 * 前台保活：onCreate 里先用占位通知自己进前台（与 Media3 同 ID，
 * 随后被真正的媒体通知原位替换），避免「播完即停」等情况下 Media3
 * 来不及接管导致的 ForegroundServiceDidNotStartInTimeException 崩溃。
 *
 * 实现方式：Media3 [MediaSessionService] + [SimpleBasePlayer]，通知栏由系统按
 * 听歌同一套默认样式自动生成（上一节 / 播放-暂停 / 下一节三键，按可用命令出现）。
 * 时间线用「三窗占位」（prev/cur/next 共用同一份元数据、当前窗恒为中间）：
 * 单窗时间线下 seekToNext/Previous 到头会被框架吞掉，有三窗才能让通知栏与
 * 耳机的上一节/下一节稳定到达 [handleSeek]；窗切换不对应真实音频，
 * 实际切段只由控制器完成，元数据三窗一致所以通知栏无闪烁。
 */
@UnstableApi
class EbookTtsMediaService : MediaSessionService() {

    private var session: MediaSession? = null
    private var bridge: EbookTtsBridgePlayer? = null
    private val container: AppContainer by lazy { (application as AuroraApplication).container }

    override fun onCreate() {
        super.onCreate()
        // 先自己进前台占住名额：用户「一点播就停」等情况下 Media3 可能不等到
        // 通知管线走完，系统超时会直接崩进程；占位通知与 Media3 同 ID，
        // 真通知一到即原位替换，用户无感。
        startPlaceholderForeground()
        val p = EbookTtsBridgePlayer(mainLooper, container)
        bridge = p
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, p)
            .setId(SESSION_ID)
            .setSessionActivity(activity)
            .build()
        p.attach()
        // 兜底：START_STICKY 重启等极端情况下若始终无内容，别留一条野通知。
        Handler(mainLooper).postDelayed({
            if (bridge?.hasContent() != true) stopSelf()
        }, STALE_GUARD_MS)
    }

    private fun startPlaceholderForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("听书")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(activity)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 与听歌服务一致：划掉任务即停播并撤掉会话。
        runCatching { container.ebookTts.stop() }
        releaseAll()
        stopSelf()
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun releaseAll() {
        runCatching { bridge?.release() }
        bridge = null
        runCatching { session?.release() }
        session = null
    }

    companion object {
        private const val SESSION_ID = "ebook_tts_session"
        // 与 Media3 DefaultMediaNotificationProvider 的默认值一致，
        // 占位通知才能被真媒体通知原位替换而不闪两条。
        private const val CHANNEL_ID = "default_channel_id"
        private const val NOTIF_ID = 1001
        private const val STALE_GUARD_MS = 5000L

        /** 开始朗读时由 AppContainer 调用，把服务拉起以展示通知并接管媒体键。 */
        fun start(context: Context) {
            val intent = Intent(context, EbookTtsMediaService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/** 会话侧的影子播放器：状态镜像 + 命令转接，见 [EbookTtsMediaService] 类注释。 */
@UnstableApi
private class EbookTtsBridgePlayer(
    looper: Looper,
    private val container: AppContainer,
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tts: EbookTtsController get() = container.ebookTts

    // 以下只在主线程读写（SimpleBasePlayer 要求应用线程）。
    // 注意：这里只搬运 playing/position/书名封面三个现成的 StateFlow，
    // 不读整书、不做任何 IO，合成与播放时序完全不受影响。
    private var playing = false
    /** 最近一次非空的朗读位置：stop() 会清掉控制器的 position，这里留着给「继续播放」用。 */
    private var lastPara: EbookTtsController.Para? = null
    private var lastParaBookPath: String? = null
    private var topPath: String? = null
    private var bookTitle = ""
    private var bookCover = ""
    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        scope.launch {
            tts.playing.collect { p ->
                playing = p
                invalidateState()
            }
        }
        scope.launch {
            tts.position.collect { pos ->
                if (pos != null) {
                    lastPara = pos
                    if (lastParaBookPath == null) lastParaBookPath = topPath
                }
                invalidateState()
            }
        }
        scope.launch {
            container.ebookStore.recents.collect { recents ->
                val top = recents.firstOrNull()
                topPath = top?.path
                bookTitle = top?.displayTitle.orEmpty()
                bookCover = top?.coverPath.orEmpty()
                // 切书：丢掉上一本书的断点，避免按播放读错书。
                if (lastPara != null) {
                    if (lastParaBookPath == null) {
                        lastParaBookPath = topPath
                    } else if (topPath != null && topPath != lastParaBookPath) {
                        lastPara = null
                        lastParaBookPath = null
                    }
                }
                invalidateState()
            }
        }
    }

    fun hasContent(): Boolean = hasItem()

    private fun hasItem(): Boolean = lastPara != null || playing

    private fun titleText(): String =
        lastPara?.readText?.trim().orEmpty().take(80)
            .ifBlank { bookTitle }.ifBlank { "听书" }

    private fun artistText(): String = bookTitle.ifBlank { "听书" }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .build()
        val b = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(false)
        if (!hasItem()) {
            // 从未朗读过：空时间线，不弹通知，但会话仍在（耳机播放键按下来也无事可做）。
            b.setPlaybackState(Player.STATE_IDLE)
            return b.build()
        }
        b.setPlaybackState(Player.STATE_READY)
        val item = MediaItem.Builder()
            .setMediaId(MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(titleText())
                    .setArtist(artistText())
                    .setAlbumTitle(bookTitle)
                    .setIsPlayable(true)
                    .apply { if (bookCover.isNotBlank()) setArtworkUri(Uri.parse(bookCover)) }
                    .build(),
            )
            .build()
        // 三窗占位见类注释：三窗元数据完全一致，当前窗恒为中间窗。
        val data = listOf(UID_PREV, UID_CUR, UID_NEXT).map { uid ->
            MediaItemData.Builder(uid)
                .setMediaItem(item)
                .setDefaultPositionUs(0)
                .setDurationUs(C.TIME_UNSET)
                .build()
        }
        b.setPlaylist(data)
            .setCurrentMediaItemIndex(INDEX_CUR)
            .setContentPositionMs(0)
        return b.build()
    }

    // 暂停即停止：调 stop()，状态保持 paused（通知栏变回播放键）。
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (tts.playing.value) {
                playing = true
            } else {
                val p = lastPara
                if (p != null) {
                    playing = true
                    tts.playFrom(p.chapter, p.block, p.startChar)
                }
            }
        } else {
            playing = false
            tts.stop()
        }
        return Futures.immediateFuture(null)
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleStop(): ListenableFuture<*> {
        playing = false
        tts.stop()
        return Futures.immediateFuture(null)
    }

    // 上一节 / 下一节：原样转给控制器（未朗读时控制器内部直接返回，与 App 内一致）。
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> tts.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> tts.prev()
        }
        return Futures.immediateFuture(null)
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateFuture(null)
    }

    companion object {
        private const val MEDIA_ID = "ebook_tts"
        private const val UID_PREV = "ebook_tts_prev"
        private const val UID_CUR = "ebook_tts_cur"
        private const val UID_NEXT = "ebook_tts_next"
        private const val INDEX_CUR = 1
    }
}
