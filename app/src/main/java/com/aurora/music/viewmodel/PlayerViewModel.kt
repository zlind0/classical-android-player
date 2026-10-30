package com.aurora.music.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.aurora.music.AuroraApplication
import com.aurora.music.R
import com.aurora.music.data.SavedQueue
import com.aurora.music.data.TrackArtworkCache
import com.aurora.music.data.audiobook.ABOOK_ID_PREFIX
import com.aurora.music.data.audiobook.abookBookIdOf
import com.aurora.music.data.toSavedTrack
import com.aurora.music.model.Song
import com.aurora.music.playback.PositionState
import com.aurora.music.playback.PlaybackService
import com.aurora.music.playback.resolvePosition
import com.aurora.music.playback.shouldResetPositionOnTransition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import java.util.concurrent.TimeUnit

enum class RepeatMode { OFF, ALL, ONE }

private val BLANK_SONG = Song("", "", "", "", "", 0)

data class PlayerUiState(
    val current: Song = BLANK_SONG,
    val queue: List<Song> = emptyList(),
    val isPlaying: Boolean = false,
    val positionSec: Float = 0f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val expanded: Boolean = false,
    val speed: Float = 1.0f,
    val pitch: Float = 0.0f,
    val matchPitch: Boolean = true,
    val likedIds: Set<String> = emptySet(),
    val currentIndex: Int = 0,
    val bpm: Int = 0,
    val camelot: String = "",
    val keyName: String = "",
    // 有声书态：当前书 id（音乐态为 null）+ 本书变速/增益
    val abookId: String? = null,
    val abookSpeed: Float = 1f,
    val abookGain: Float = 1f,
) {
    val durationSec: Int get() = current.durationSec
    val progress: Float get() = if (durationSec == 0) 0f else (positionSec / durationSec).coerceIn(0f, 1f)
    val isCurrentLiked: Boolean get() = likedIds.contains(current.id)
    val hasTrack: Boolean get() = current.id.isNotEmpty()
}

class PlayerViewModel(private val app: Application) : AndroidViewModel(app) {

    private val container = (app as AuroraApplication).container

    private val emptySong: Song
        get() = Song("", app.getString(com.aurora.music.R.string.vm_nothing_playing), "", "", "", 0)

    private val _state = MutableStateFlow(PlayerUiState(current = emptySong))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var queueFillJob: Job? = null
    private var songById: Map<String, Song> = emptyMap()
    // 冷启动点书时 controller 可能还没连上：存下参数，连上即播（只保留最后一次）
    private data class PendingAbook(
        val bookId: String,
        val songs: List<Song>,
        val idx: Int,
        val posMs: Long,
        val speed: Float,
        val gain: Float,
    )
    @Volatile private var pendingAbook: PendingAbook? = null

    fun hasController(): Boolean = controller != null

    @Volatile private var autoplayEnabled = false

    // likes merge server stars + local playlist likes
    private var serverLikedIds: Set<String> = emptySet()
    private var likedPlaylistIds: Set<String> = emptySet()
    private var lastRecordedId: String? = null
    private var lastNowPlayingId: String? = null
    // account the live queue belongs to so it persists/restores under the right key
    @Volatile private var playingAccountKey: String = ""
    // 有声书全书 id 序（分窗时 controller 序号是窗内序号，展示用这份反查书级序号）
    @Volatile private var abookOrder: List<String> = emptyList()
    // 有声书快进/快退步长秒（全局设置，service 拦截与 VM 共用）
    @Volatile private var abookSeekSec: Int = 30
    private var openRestoreAttempted = false
    private var lastPersistMs = 0L
    // 最近一次写入 UI 的进度样本（用于单次 0 位置防抖）
    private var lastPosition: PositionState? = null
    private var lastKeyInfoId: String? = null
    private var lastKeyInfo: com.aurora.music.data.SonicEngine.TrackKey? = null
    @Volatile private var loadingRadio = false

    // bit-perfect quirk: the native flac engine plays audio (and currentPosition advances off engine frames)
    // while exoplayer can still report STATE_BUFFERING because the native-engine loadcontrol blocks loading, so
    // c.isPlaying reads false on a cold connect leaving the bar frozen + a paused icon until a manual pause/play.
    // treat "wants to play and is buffering" as playing so the ui tracks the audible reality.
    private val Player.effectivelyPlaying: Boolean
        get() = isPlaying || (playWhenReady && playbackState == Player.STATE_BUFFERING)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_ENDED) {
                maybeAutoplay()
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // 只有新曲确实是歌单里的已知歌曲、且不是当前展示的这首歌时才清 0：
            // 双播会话切到 UnifiedPlayer 的 ebook_tts 占位、以及同一曲的 seek/缓冲，
            // 都不该把音乐气泡的进度闪回 0:00（下一拍 ticker/快照会立刻写回真实位置）。
            val newId = item?.mediaId
            if (shouldResetPositionOnTransition(newId, _state.value.current.id, songById.keys)) {
                lastPosition = null
                _state.update { it.copy(positionSec = 0f) }
            }
            newId?.let { maybeRevive(it) }
            newId?.let { onAbookChapterChanged(it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 本地文件打不开（多半是文件已消失）：标 unavailable 并自动跳下一首。
            // 查询时不做 exists 预检，这里是唯一的“文件消失”纠正点。
            handleItemError()
        }
    }

    private fun handleItemError() {
        val c = controller ?: return
        val song = c.currentMediaItem?.mediaId?.let { songById[it] } ?: return
        if (song.path.isBlank()) return
        viewModelScope.launch {
            // content:// 走系统媒体库，错误多为 transient，不写库只跳过；
            // file 直链/id 才标 unavailable
            val markable = !song.streamUrl.startsWith("content://")
            if (markable) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (song.id.startsWith("file:")) container.musicRoots.markUnavailable(listOf(song.path))
                        else container.localLibrary.markUnavailableByIds(listOf(song.id))
                    }
                }
                songById = songById + (song.id to song.copy(available = false))
                container.notifyLibraryChanged()
            }
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.play()
            } else {
                c.pause()
            }
            syncFromController()
        }
    }

    /**
     * 播成功复活：灰色（unavailable）曲目实际播起来了，说明存储已重连，
     * 标回 available 并刷新列表。如果文件真没了，随后的报错会再标回去。
     */
    private fun maybeRevive(mediaId: String) {
        val song = songById[mediaId] ?: return
        if (song.available || song.path.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (song.id.startsWith("file:")) container.musicRoots.markAvailable(listOf(song.path))
                    else container.localLibrary.markAvailableByIds(listOf(song.id))
                }
            }
            songById = songById + (song.id to song.copy(available = true))
            container.notifyLibraryChanged()
            syncFromController()
        }
    }

    private fun maybeNowPlaying() {
        val cur = _state.value.current
        if (cur.id.isEmpty() || cur.id == lastNowPlayingId) return
        lastNowPlayingId = cur.id
    }

    private fun recordIfPlayed(posSec: Float) {
        val cur = _state.value.current
        if (cur.id.isEmpty() || posSec < 30f || cur.id == lastRecordedId) return
        lastRecordedId = cur.id
        container.playHistory.record(cur, System.currentTimeMillis())
    }

    private fun maybeAutoplay() {
        if (!autoplayEnabled || loadingRadio) return
        val c = controller ?: return
        val seed = _state.value.current.id.ifEmpty { return }
        loadingRadio = true
        viewModelScope.launch {
            val more = runCatching { container.repository.radio(seed) }.getOrDefault(emptyList())
                .filter { it.id !in songById.keys }
            if (more.isNotEmpty()) {
                songById = songById + more.associateBy { it.id }
                c.addMediaItems(more.map { toMediaItem(it) })
                c.play()
                syncFromController()
            }
            loadingRadio = false
        }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = future.get().also { it.addListener(listener) }
            controller = c
            // service survived but vm is fresh rebuild the domain queue so the queue ui isn't empty
            if (c.mediaItemCount > 0 && songById.isEmpty()) rehydrateFromController()
            syncFromController()
            startTicker()
            // 冷启动时排队的有声书点播：连上即播
            pendingAbook?.let { p ->
                pendingAbook = null
                playAudiobook(p.bookId, p.songs, p.idx, p.posMs, p.speed, p.gain)
            }
        }, ContextCompat.getMainExecutor(app))

        // restore a saved queue only if the service came up empty otherwise keep its queue
        viewModelScope.launch {
            container.sessionReady.collect { ready ->
                if (ready != true || openRestoreAttempted) return@collect
                openRestoreAttempted = true
                while (controller == null) delay(50)
                val c = controller ?: return@collect
                playingAccountKey = container.currentAccountKey()
                val saved = playingAccountKey.takeIf { it.isNotBlank() }?.let { container.queueStore.get(it) }
                val forceResume = container.uiState.consumeResumeOnLaunch()
                if (c.mediaItemCount == 0 && saved != null) {
                    restoreQueue(saved, forceResume)
                } else if (forceResume && c.mediaItemCount > 0) {
                    // 服务没被杀（队列还在）：直接把上次的播放接着放
                    runCatching { c.play() }
                    _state.update { it.copy(isPlaying = true) }
                }
            }
        }

        viewModelScope.launch {
            container.sessionReady.collect { ready -> if (ready == true) refreshLikes() }
        }
        // subsonic can't star playlists so locally-liked ones merge into the same set
        viewModelScope.launch {
            container.settingsStore.likedPlaylists.collect { ids ->
                likedPlaylistIds = ids
                recomputeLikes()
            }
        }
        viewModelScope.launch {
            container.settingsStore.playbackPrefs.collect { p ->
                autoplayEnabled = p.autoplayRadio
                if (_state.value.speed == 1.0f && p.defaultSpeed != 1.0f && _state.value.current.id.isEmpty()) {
                    _state.update { it.copy(speed = p.defaultSpeed) }
                }
            }
        }
        viewModelScope.launch {
            // 有声书快进/快退步长（全局，service 拦截与 VM 共用同一份）
            container.settingsStore.audiobookPrefs.collect { abookSeekSec = it.seekSec }
        }
        viewModelScope.launch {
            // 站内音乐真实状态（双播时会话 player 是书，音乐 UI 的当前曲/序号/进度/播放态都看这份）
            container.musicState.collect { ms ->
                if (ms.mediaId.isBlank()) return@collect
                val cur = songById[ms.mediaId]
                if (cur != null && cur.id != lastKeyInfoId) {
                    lastKeyInfoId = cur.id
                    lastKeyInfo = runCatching { container.sonicEngine.keyInfo(cur.id) }.getOrNull()
                }
                val ki = lastKeyInfo
                val abookIdx = _state.value.abookId?.let { abookOrder.indexOf(ms.mediaId).takeIf { i -> i >= 0 } }
                // 队列顺序以音乐 player 时间线为准（随机是物理重排，双播时会话时间线是书的占位，不能拿来用）
                val mq = ms.queueIds.mapNotNull { songById[it] }
                val queue = if (ms.queueIds.isNotEmpty() && mq.size == ms.queueIds.size) mq else null
                // 进度统一走防抖收口：单次 0（seek/缓冲/会话切换）不落 UI
                applyPosition(controller)
                _state.update {
                    it.copy(
                        current = cur ?: it.current,
                        isPlaying = ms.playing,
                        queue = queue ?: it.queue,
                        currentIndex = abookIdx ?: ms.index,
                        shuffle = ms.shuffle,
                        repeat = when (ms.repeatMode) {
                            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                            else -> RepeatMode.OFF
                        },
                        bpm = ki?.bpm ?: 0,
                        camelot = ki?.camelot ?: "",
                        keyName = ki?.name ?: "",
                    )
                }
            }
        }
    }

    /** App 退到后台/被杀前主动落一次：队列、位置、是否在播。 */
    fun persistNow() {
        persistQueue()
        persistAbookNow()
        container.queueStore.requestFlush()
    }

    fun stopPlayback() {
        persistAbookNow()
        controller?.let { c ->
            runCatching { c.pause(); c.stop(); c.clearMediaItems() }
        }
        val key = playingAccountKey.ifBlank { container.currentAccountKey() }
        if (key.isNotBlank()) container.queueStore.clear(key)
        container.abookSession.setActive(null, 1f)
        songById = emptyMap()
        lastRecordedId = null
        lastNowPlayingId = null
        lastPosition = null
        _state.update {
            it.copy(current = emptySong, queue = emptyList(), isPlaying = false, positionSec = 0f, currentIndex = 0, expanded = false,
                abookId = null, abookSpeed = 1f, abookGain = 1f)
        }
    }

    private fun persistQueue() {
        val c = controller ?: return
        val key = playingAccountKey.ifBlank { container.currentAccountKey() }
        if (key.isBlank()) return
        // 双播时会话 controller 是书的占位时间线（mediaId=ebook_tts、位置恒 0），
        // 音乐队列/进度必须以服务快照为准；否则朗读期间这里会查不到任何歌曲直接 return，
        // 听书时的音乐进度永远不落盘，下次启动只能回到旧位置。
        val ms = container.musicState.value
        if (ms.mediaId.isNotBlank() && ms.queueIds.isNotEmpty()) {
            val songs = ms.queueIds.mapNotNull { songById[it] }
            if (songs.size == ms.queueIds.size) {
                container.queueStore.save(key, SavedQueue(
                    tracks = songs.map { it.toSavedTrack() },
                    currentIndex = ms.index.coerceIn(0, songs.lastIndex),
                    positionSec = (ms.positionMs / 1000).toInt().coerceAtLeast(0),
                    shuffle = ms.shuffle,
                    repeat = when (ms.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 },
                    wasPlaying = ms.playing,
                    speed = _state.value.speed,
                    pitch = _state.value.pitch,
                    matchPitch = _state.value.matchPitch,
                ))
                return
            }
        }
        // don't clear on an empty controller it fires at startup before restore and wipes what we're about to restore
        if (c.mediaItemCount == 0) return
        val songs = (0 until c.mediaItemCount).mapNotNull { songById[c.getMediaItemAt(it).mediaId] }
        if (songs.isEmpty()) return
        container.queueStore.save(key, SavedQueue(
            tracks = songs.map { it.toSavedTrack() },
            currentIndex = c.currentMediaItemIndex.coerceAtLeast(0),
            positionSec = (c.currentPosition / 1000).toInt().coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) { Player.REPEAT_MODE_ALL -> 1; Player.REPEAT_MODE_ONE -> 2; else -> 0 },
            wasPlaying = c.playWhenReady,
            speed = _state.value.speed,
            pitch = _state.value.pitch,
            matchPitch = _state.value.matchPitch,
        ))
    }

    private suspend fun restoreQueue(sq: SavedQueue, forcePlay: Boolean = false) {
        val c = controller ?: return
        val resume = forcePlay || sq.wasPlaying
        val songs = sq.tracks.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() && it.streamUrl.isNotEmpty() }
        if (songs.isEmpty()) return
        songById = songs.associateBy { it.id }
        // 冷启动恢复的是书：走有声书分支（变速/增益/会话），否则还是音乐页
        lastPosition = null
        val abookId = songs.takeIf { it.isNotEmpty() && it.all { s -> isAbookSong(s.id) } }
            ?.let { abookBookIdOf(it.first().id) }
        if (abookId != null) abookOrder = songs.map { it.id }
        val idx = sq.currentIndex.coerceIn(0, songs.lastIndex)
        // 变速先于建队（同上，防内部 seek 冲位置）；音乐同样先设后建
        if (abookId != null) {
            val prog = withContext(Dispatchers.IO) {
                runCatching { container.audiobookStore.progressOf(abookId) }.getOrNull()
            }
            val speed = prog?.speed ?: 1f
            val gain = prog?.gain ?: 1f
            c.playbackParameters = PlaybackParameters(speed, 1f)
            container.abookSession.setActive(abookId, gain)
            container.abookSession.setCurrentPath(songs[idx].path)
            val delivery = deliverQueue(songs, idx, sq.positionSec.toLong() * 1000)
            c.repeatMode = when (sq.repeat) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
            c.prepare()   // buffer at saved position
            if (resume) c.play()
            _state.update {
                it.copy(queue = delivery.songs, current = songs[idx], positionSec = sq.positionSec.toFloat(), isPlaying = resume, currentIndex = delivery.currentIndex, shuffle = sq.shuffle,
                    repeat = when (sq.repeat) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF },
                    abookId = abookId, abookSpeed = speed, abookGain = gain)
            }
        } else {
            val speed = sq.speed.takeIf { it > 0f } ?: 1f
            val pitch = sq.pitch
            val matchPitch = sq.matchPitch
            val pitchRatio = if (matchPitch) speed else 2f.pow(pitch / 12f)
            c.playbackParameters = PlaybackParameters(speed, pitchRatio)
            _state.update { it.copy(speed = speed, pitch = pitch, matchPitch = matchPitch) }
            val delivery = deliverQueue(songs, idx, sq.positionSec.toLong() * 1000)
            c.repeatMode = when (sq.repeat) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
            c.prepare()   // buffer at saved position
            if (resume) c.play()
            _state.update {
                it.copy(queue = delivery.songs, current = songs[idx], positionSec = sq.positionSec.toFloat(), isPlaying = resume, currentIndex = delivery.currentIndex, shuffle = sq.shuffle,
                    repeat = when (sq.repeat) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF })
            }
        }
        if (sq.shuffle) {
            // queue is already in saved order tell the service shuffle is on and remember that order
            c.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply {
                    putInt("target", 1)
                putStringArrayList("order", ArrayList(songs.map { it.id }))
                }),
                android.os.Bundle.EMPTY,
            )
        }
    }

    private fun rehydrateFromController() {
        val c = controller ?: return
        val songs = (0 until c.mediaItemCount).map { i ->
            val mi = c.getMediaItemAt(i)
            val md = mi.mediaMetadata
            Song(
                id = mi.mediaId,
                title = md.title?.toString() ?: "",
                artist = md.artist?.toString() ?: "",
                album = md.albumTitle?.toString() ?: "",
                artworkUrl = md.artworkUri?.toString() ?: "",
                durationSec = 0,
                accent = com.aurora.music.util.accentFor(mi.mediaId),
                streamUrl = mi.localConfiguration?.uri?.toString() ?: "",
                replayGainTrack = md.extras?.getFloat("rgTrack", 0f) ?: 0f,
                replayGainAlbum = md.extras?.getFloat("rgAlbum", 0f) ?: 0f,
            )
        }
        songById = songs.associateBy { it.id }
        restoreAbookFromController()
    }

    /**
     * service 活着但 VM 重建（旋转/重建）：controller 里是书的章节队，
     * 按 mediaId 反查章节重建成完整 Song（含 path），恢复书会话。
     * 音乐队直接返回（上面的快照已够用）。
     */
    private fun restoreAbookFromController() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val firstId = c.getMediaItemAt(0).mediaId
        if (!isAbookSong(firstId)) return
        val bookId = com.aurora.music.data.audiobook.abookBookIdOf(firstId) ?: return
        viewModelScope.launch {
            val book = container.audiobookStore.books.value.firstOrNull { it.id == bookId }
            val chapters = runCatching { container.audiobookStore.chaptersOfBookFiltered(bookId) }
                .getOrDefault(emptyList())
            if (chapters.isEmpty()) return@launch
            val songs = (0 until c.mediaItemCount).mapNotNull { i ->
                val mid = c.getMediaItemAt(i).mediaId
                val idx = com.aurora.music.data.audiobook.abookIndexOf(mid) ?: return@mapNotNull null
                val ch = chapters.getOrNull(idx) ?: return@mapNotNull null
                Song(
                    id = mid,
                    title = ch.displayTitle,
                    artist = book?.displayTitle.orEmpty(),
                    album = book?.displayTitle.orEmpty(),
                    artworkUrl = book?.coverPath.orEmpty(),
                    durationSec = ch.durationSec,
                    streamUrl = runCatching {
                        android.net.Uri.fromFile(java.io.File(ch.path)).toString()
                    }.getOrDefault(""),
                    suffix = ch.path.substringAfterLast('.', ""),
                    path = ch.path,
                    accent = com.aurora.music.util.accentFor(ch.path),
                    trackNumber = ch.trackNumber,
                )
            }
            if (songs.isEmpty()) return@launch
            songById = songs.associateBy { it.id }
            abookOrder = songs.map { it.id }
            val prog = withContext(Dispatchers.IO) {
                runCatching { container.audiobookStore.progressOf(bookId) }.getOrNull()
            }
            val speed = prog?.speed ?: 1f
            val gain = prog?.gain ?: 1f
            container.abookSession.setActive(bookId, gain)
            c.playbackParameters = PlaybackParameters(speed, 1f)
            _state.update { it.copy(abookId = bookId, abookSpeed = speed, abookGain = gain) }
            syncFromController()
            container.abookSession.setCurrentPath(_state.value.current.path)
        }
    }

    fun setPreferredDevice(deviceId: Int) {
        container.preferredAudioDeviceId.value = deviceId
    }

    fun preferredDeviceId(): Int = container.preferredAudioDeviceId.value

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(200)
                val c = controller ?: continue
                // 双播时会话 c 是书：进度只能取音乐快照，否则会被书的占位 0 位覆盖。
                val ms = container.musicState.value
                val active = if (ms.mediaId.isNotBlank()) ms.playing else c.effectivelyPlaying
                if (!active) continue
                val sample = applyPosition(c) ?: continue
                val posSec = (sample.positionMs / 1000f).coerceAtLeast(0f)
                maybeNowPlaying()
                recordIfPlayed(posSec)
                val now = System.currentTimeMillis()
                if (now - lastPersistMs > 2000) {
                    lastPersistMs = now
                    persistQueue()
                    persistAbookNow()
                }
            }
        }
    }

    /**
     * 统一进度写入：ticker / 音乐快照 collect / controller 同步共用。
     * 双播时快照优先；快照缺失时只有会话当前曲是已知歌曲才用 controller 位置，
     * 会话是 ebook_tts 占位时保持原值，避免把书的 0 位写进音乐气泡。
     */
    private fun applyPosition(c: MediaController?, nowMs: Long = SystemClock.elapsedRealtime()): PositionState? {
        val ms = container.musicState.value
        val next = resolvePosition(
            snapshotMediaId = ms.mediaId,
            snapshotPositionMs = ms.positionMs,
            snapshotPlaying = ms.playing,
            sessionMediaId = c?.currentMediaItem?.mediaId,
            sessionPositionMs = c?.currentPosition ?: 0L,
            sessionPlaying = c?.effectivelyPlaying == true,
            knownMediaIds = songById.keys,
            previous = lastPosition,
            nowMs = nowMs,
        ) ?: return null
        lastPosition = next
        _state.update { it.copy(positionSec = (next.positionMs / 1000f).coerceAtLeast(0f)) }
        return next
    }

    private fun syncFromController() {
        val c = controller ?: return
        // 双播时会话 player 是 UnifiedPlayer（书的三窗占位），音乐侧的真实序号/进度看服务快照。
        val ms = container.musicState.value
        val hasMusic = ms.mediaId.isNotBlank()
        applyPosition(c)
        val mediaId = c.currentMediaItem?.mediaId
        val cur = (if (hasMusic) songById[ms.mediaId] else null)
            ?: mediaId?.let { songById[it] }
            ?: _state.value.current
        val q = (0 until c.mediaItemCount).mapNotNull { i -> songById[c.getMediaItemAt(i).mediaId] }
        if (cur.id != lastKeyInfoId) { lastKeyInfoId = cur.id; lastKeyInfo = runCatching { container.sonicEngine.keyInfo(cur.id) }.getOrNull() }
        val ki = lastKeyInfo
        val abookIdx = _state.value.abookId?.let { abookOrder.indexOf(cur.id).takeIf { it >= 0 } }
        _state.update {
            it.copy(
                current = cur,
                isPlaying = if (hasMusic) ms.playing else c.effectivelyPlaying,
                shuffle = if (hasMusic) ms.shuffle else c.shuffleModeEnabled,
                repeat = when (if (hasMusic) ms.repeatMode else c.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                queue = if (q.isNotEmpty()) q else it.queue,
                currentIndex = abookIdx ?: if (hasMusic) ms.index else c.currentMediaItemIndex.coerceAtLeast(0),
                bpm = ki?.bpm ?: 0,
                camelot = ki?.camelot ?: "",
                keyName = ki?.name ?: "",
            )
        }
        maybeEnrichLocal(_state.value.current)
        maybeFixTrackArt(_state.value.current)
        persistQueue()
    }

    // 播放态封面纠偏：MediaStore 的 albumart 是专辑级（同专辑共用一张，多为别的文件的图），
    // 这里后台解析本文件内嵌图，命中后把 current 换成本文件 URI，播放大封面/通知下次即用对的图。
    private val fixedArtwork = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun maybeFixTrackArt(song: Song) {
        if (song.id.isBlank() || !fixedArtwork.add(song.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val url = runCatching { TrackArtworkCache.resolve(app, song) }.getOrNull().orEmpty()
            if (url.isBlank() || url == song.artworkUrl) return@launch
            val fixed = song.copy(artworkUrl = url)
            songById = songById + (song.id to fixed)
            _state.update { st -> if (st.current.id == song.id) st.copy(current = fixed) else st }
        }
    }

    // mediastore 缺采样率/位深，播放时对当前曲补一次；文件模式同样（直路径 MMR）
    private val enrichedLocal = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun maybeEnrichLocal(song: Song) {
        if (song.id.isEmpty()) return
        val isLocal = song.streamUrl.startsWith("content://") ||
            song.streamUrl.startsWith("file://") || song.path.isNotBlank()
        if (!isLocal) return
        if (song.sampleRateHz > 0) return
        if (!enrichedLocal.add(song.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            val mmr = android.media.MediaMetadataRetriever()
            val result = runCatching {
                // 文件直路径优先（最准且不经 ContentResolver），缺失会抛异常走兜底，不预检 exists
                val byPath = song.path.isNotBlank() &&
                    runCatching { mmr.setDataSource(song.path); true }.getOrDefault(false)
                if (!byPath) mmr.setDataSource(getApplication(), Uri.parse(song.streamUrl))
                fun key(k: Int) = mmr.extractMetadata(k)?.toIntOrNull() ?: 0
                val sr = if (android.os.Build.VERSION.SDK_INT >= 31) key(android.media.MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) else 0
                val bd = if (android.os.Build.VERSION.SDK_INT >= 31) key(android.media.MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE) else 0
                val br = key(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE) / 1000
                Triple(sr, bd, br)
            }.getOrNull()
            runCatching { mmr.release() }
            val (sr, bd, br) = result ?: return@launch
            if (sr <= 0 && bd <= 0 && br <= 0) return@launch
            fun enrich(s: Song) = s.copy(
                sampleRateHz = if (sr > 0) sr else s.sampleRateHz,
                bitDepth = if (bd > 0) bd else s.bitDepth,
                bitrateKbps = if (s.bitrateKbps > 0) s.bitrateKbps else br,
            )
            songById = songById.mapValues { (id, s) -> if (id == song.id) enrich(s) else s }
            _state.update { st -> if (st.current.id == song.id) st.copy(current = enrich(st.current)) else st }
        }
    }

    private fun toMediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id)
        .setUri(song.streamUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                // 通知栏也优先用已缓存的本文件内嵌图（同步快查，无 IO 阻塞），
                // 未命中时回落专辑级图；前台纠偏后缓存即热，下次播放通知即对。
                .apply {
                    val art = runCatching { TrackArtworkCache.cachedSync(app, song) }.getOrNull()
                        .orEmpty().ifBlank { song.artworkUrl }
                    if (art.isNotBlank()) setArtworkUri(Uri.parse(art))
                }
                .setExtras(android.os.Bundle().apply {
                    putFloat("rgTrack", song.replayGainTrack)
                    putFloat("rgAlbum", song.replayGainAlbum)
                    // v0.6 driving loudness needs per-track LUFS on the loader path
                    container.replayGainStore.lufsFor(song.path)?.let { putFloat("lufs", it) }
                })
                .build()
        )
        .build()

    fun playAll(songs: List<Song>, startIndex: Int = 0) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        container.haptic()
        // 先把会话切回音乐 player：停书后会话可能还粘在 UnifiedPlayer（书的占位时间线）上，
        // 直接 setMediaItems 会落到书上，随后的 play 还会被当成“继续朗读”而开口读书。
        takeSessionForMusic(c)
        clearAbook()
        playingAccountKey = container.currentAccountKey()
        songById = songs.associateBy { it.id }
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        val delivery = deliverQueue(songs, idx, 0L)
        c.playbackParameters = currentParams()
        c.prepare()
        c.play()
        // fresh context plays in order make sure shuffle is off
        sendShuffle(0)
        lastPosition = null
        _state.update { it.copy(queue = delivery.songs, currentIndex = delivery.currentIndex, current = songs[idx], positionSec = 0f, isPlaying = true) }
    }

    fun play(song: Song) = playAll(listOf(song), 0)

    // ---- 有声书（章节 = Song，id 前缀 audiobook:；点书替换音乐队列） ----

    fun isAbookSong(id: String): Boolean = id.startsWith(ABOOK_ID_PREFIX)

    fun playAudiobook(
        bookId: String,
        songs: List<Song>,
        startIndex: Int,
        positionMs: Long,
        speed: Float,
        gain: Float,
    ) {
        viewModelScope.launch {
            persistOutgoingAbook(bookId)
            val c = controller
            if (c == null) {
                // 连接中：排队，连上即播（调用方负责 toast 提示）
                pendingAbook = PendingAbook(
                    bookId, songs,
                    startIndex.coerceIn(0, songs.lastIndex.coerceAtLeast(0)),
                    positionMs, speed, gain,
                )
                return@launch
            }
            pendingAbook = null
            if (songs.isEmpty()) return@launch
            container.haptic()
            // 同上：有声书也走音乐 player，先把会话从书的占位时间线切回来。
            takeSessionForMusic(c)
            playingAccountKey = container.currentAccountKey()
            songById = songs.associateBy { it.id }
            abookOrder = songs.map { it.id }
            val idx = startIndex.coerceIn(0, songs.lastIndex)
            val clampedSpeed = speed.coerceIn(0.5f, 4f)
            val clampedGain = gain.coerceIn(0.5f, 2f)
            container.abookSession.setActive(bookId, clampedGain)
            container.abookSession.setCurrentPath(songs[idx].path)
            // 共存：先等 service 丢掉独占焦点再起播，否则 GAIN 瞬间暂停站外
            val allowMix = runCatching { container.settingsStore.audiobookPrefs.first().allowMix }
                .getOrDefault(false)
            if (allowMix) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        c.sendCustomCommand(
                            SessionCommand(PlaybackService.CMD_ABOOK_START, android.os.Bundle.EMPTY),
                            android.os.Bundle.EMPTY,
                        ).get(2, TimeUnit.SECONDS)
                    }
                }
            }
            // 变速必须在 setMediaItems 之前设：ExoPlayer 设变速内部会 seek 到当前位置，
            // 后设会把起播位置冲回 0（音乐页起播本来就是 0 才没暴露）
            c.playbackParameters = PlaybackParameters(clampedSpeed, 1f)
            val delivery = deliverQueue(songs, idx, positionMs.coerceAtLeast(0L))
            // 不变调变速：pitch 恒 1，与音乐 matchPitch 逻辑解耦
            c.prepare()
            c.play()
            sendShuffle(0)
            lastPosition = null
            _state.update {
                it.copy(
                    queue = delivery.songs, currentIndex = delivery.currentIndex, current = songs[idx],
                    positionSec = positionMs / 1000f, isPlaying = true,
                    abookId = bookId, abookSpeed = clampedSpeed, abookGain = clampedGain,
                )
            }
        }
    }

    /** 有声书上一章/下一章：无条件切文件（不复用音乐 4s 重播逻辑）。 */
    fun abookChapter(step: Int) {
        val c = controller ?: return
        if (_state.value.abookId == null) return
        container.haptic()
        val target = (c.currentMediaItemIndex + step).coerceIn(0, (c.mediaItemCount - 1).coerceAtLeast(0))
        if (target == c.currentMediaItemIndex) return
        c.seekTo(target, 0)
        c.play()
        syncFromController()
    }

    fun abookJumpToChapter(index: Int) {
        val c = controller ?: return
        if (_state.value.abookId == null) return
        container.haptic()
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0)
            c.play()
            syncFromController()
        }
    }

    /** 快进/快退：按全局步长位移，钳制在本章内，不跨章。 */
    fun seekBySeconds(deltaSec: Int) {
        val c = controller ?: return
        val dur = c.duration.takeIf { it > 0 } ?: (_state.value.durationSec * 1000L)
        if (dur <= 0) return
        c.seekTo((c.currentPosition + deltaSec * 1000L).coerceIn(0L, dur))
        persistAbookNow()
    }

    fun abookStep(): Int = abookSeekSec

    fun setAbookSpeed(speed: Float) {
        val c = controller ?: return
        val bookId = _state.value.abookId ?: return
        // 0.5..4x step 0.25
        val snapped = (kotlin.math.round(speed / 0.25f) * 0.25f).coerceIn(0.5f, 4f)
        _state.update { it.copy(abookSpeed = snapped) }
        c.playbackParameters = PlaybackParameters(snapped, 1f)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.audiobookStore.saveSpeed(bookId, snapped) }
        }
    }

    fun setAbookGain(gain: Float) {
        val bookId = _state.value.abookId ?: return
        val clamped = gain.coerceIn(0.5f, 2f)
        _state.update { it.copy(abookGain = clamped) }
        container.abookSession.setGain(clamped)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.audiobookStore.saveGain(bookId, clamped) }
        }
    }

    private fun onAbookChapterChanged(mediaId: String) {
        if (!isAbookSong(mediaId)) return
        // bookId 必须从 mediaId 反解，不能读可变的 _state.value.abookId：
        // 切书窗口期 state 里还是旧书、songById 已经换成新书，错配会把旧书进度洗成新书章节 + 0 位。
        val bookId = abookBookIdOf(mediaId) ?: return
        val song = songById[mediaId] ?: return
        // 迟到的旧书事件不再碰会话，避免污染当前书的增益/快进路径。
        if (_state.value.abookId == bookId) container.abookSession.setCurrentPath(song.path)
        // 切章即记新章 0 位（ticker 随后会跟进精确位置）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.audiobookStore.savePos(bookId, song.path, 0L) }
        }
    }

    private fun persistAbookNow() {
        val st = _state.value
        val bookId = st.abookId ?: return
        val song = st.current
        if (!isAbookSong(song.id) || song.path.isBlank()) return
        container.abookSession.setCurrentPath(song.path)
        // 双播时会话是书的朗读占位，controller.currentPosition 是朗读位置，不能当有声书进度；
        // 快照正好是音乐 player（本书章节）时用它。
        val ms = container.musicState.value
        val posMs = (if (ms.mediaId == song.id) ms.positionMs
            else (controller?.currentPosition ?: (st.positionSec * 1000).toLong())).coerceAtLeast(0L)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.audiobookStore.savePos(bookId, song.path, posMs) }
        }
    }

    // 切书/切音乐前同步落盘旧书：ticker 最多 2s 一次，不补这一下最后一段必丢。
    private suspend fun persistOutgoingAbook(nextBookId: String?) {
        val st = _state.value
        val oldId = st.abookId ?: return
        if (oldId == nextBookId) return
        val c = controller
        val song = c?.currentMediaItem?.mediaId?.let { songById[it] } ?: st.current
        if (!isAbookSong(song.id) || song.path.isBlank()) return
        // mediaId 反解必须和旧书一致，防止窗口期错配写到别的书上。
        if (abookBookIdOf(song.id) != oldId) return
        // 双播时会话 controller 承载朗读，位置不是有声书进度；快照是本书章节时以快照为准。
        val ms = container.musicState.value
        val posMs = (if (ms.mediaId == song.id) ms.positionMs
            else (c?.currentPosition ?: (st.positionSec * 1000).toLong())).coerceAtLeast(0L)
        runCatching { container.audiobookStore.savePosSync(oldId, song.path, posMs) }
    }

    private fun clearAbook() {
        if (_state.value.abookId != null) {
            persistAbookNow()
            abookOrder = emptyList()
            container.abookSession.setActive(null, 1f)
            _state.update { it.copy(abookId = null, abookSpeed = 1f, abookGain = 1f) }
        }
    }

    fun playCollection(kind: String, id: String, loaded: List<Song>, startIndex: Int, total: Int) {
        playAll(loaded, startIndex)
        fillQueue(kind, id, loaded, total, shuffle = false)
    }

    fun shuffleCollection(kind: String, id: String, loaded: List<Song>, total: Int) {
        shufflePlay(loaded)
        fillQueue(kind, id, loaded, total, shuffle = true)
    }

    private data class QueueDelivery(val songs: List<Song>, val currentIndex: Int)

    // sets a safe initial window (containing startIndex) then backfills the rest of a large in-memory
    // list in chunks, forward first then the songs before startIndex prepended so skip-back still works.
    // returns whatever was actually included in the synchronous setMediaItems call, and where the
    // "current" song landed within it — the controller resets to a local index for the windowed case.
    private fun deliverQueue(songs: List<Song>, startIndex: Int, startPositionMs: Long): QueueDelivery {
        val c = controller ?: return QueueDelivery(emptyList(), 0)
        if (songs.size <= QUEUE_BATCH) {
            c.setMediaItems(songs.map { toMediaItem(it) }, startIndex, startPositionMs)
            return QueueDelivery(songs, startIndex)
        }
        val windowEnd = (startIndex + QUEUE_BATCH).coerceAtMost(songs.size)
        val initial = songs.subList(startIndex, windowEnd)
        c.setMediaItems(initial.map { toMediaItem(it) }, 0, startPositionMs)
        queueFillJob?.cancel()
        queueFillJob = viewModelScope.launch {
            var tail = windowEnd
            while (tail < songs.size) {
                val chunk = songs.subList(tail, (tail + QUEUE_BATCH).coerceAtMost(songs.size))
                c.addMediaItems(chunk.map { toMediaItem(it) })
                syncFromController()
                tail += chunk.size
                delay(40)
            }
            var head = startIndex
            while (head > 0) {
                val chunkStart = (head - QUEUE_BATCH).coerceAtLeast(0)
                c.addMediaItems(0, songs.subList(chunkStart, head).map { toMediaItem(it) })
                syncFromController()
                head = chunkStart
                delay(40)
            }
        }
        return QueueDelivery(initial, 0)
    }

    private fun fillQueue(kind: String, id: String, loaded: List<Song>, total: Int, shuffle: Boolean) {
        queueFillJob?.cancel()
        if (loaded.size >= total || total <= 0) return
        queueFillJob = viewModelScope.launch {
            val c = controller ?: return@launch
            val have = loaded.mapTo(HashSet()) { it.id }
            var offset = loaded.size
            while (offset < total) {
                val page = runCatching { container.repository.detailPage(kind, id, offset) }.getOrDefault(emptyList())
                if (page.isEmpty()) break
                val fresh = page.filter { it.id.isNotEmpty() && have.add(it.id) }
                if (fresh.isNotEmpty()) {
                    val toAdd = if (shuffle) fresh.shuffled() else fresh
                    songById = songById + toAdd.associateBy { it.id }
                    c.addMediaItems(toAdd.map { toMediaItem(it) })
                    syncFromController()
                }
                offset += page.size
            }
        }
    }

    fun startSonicRadio(seed: Song = _state.value.current, onResult: (String) -> Unit = {}) {
        if (seed.id.isEmpty() || loadingRadio) return
        loadingRadio = true
        viewModelScope.launch {
            val sonic = runCatching { container.sonicEngine.buildRadio(seed) }.getOrDefault(emptyList())
            if (sonic.size >= 2) {
                playAll(sonic, 0)
                onResult(app.getString(R.string.msg_sonic_radio, sonic.size - 1))
            } else {
                val more = runCatching { container.repository.radio(seed.id) }.getOrDefault(emptyList())
                    .filter { it.id != seed.id }
                if (more.isNotEmpty()) {
                    playAll(listOf(seed) + more, 0)
                    onResult(app.getString(R.string.msg_radio_started))
                } else {
                    onResult(app.getString(R.string.msg_radio_needs_analysis))
                }
            }
            loadingRadio = false
        }
    }

    fun startAutoDj(seed: Song = _state.value.current, onResult: (String) -> Unit = {}) {
        if (seed.id.isEmpty() || loadingRadio) return
        loadingRadio = true
        viewModelScope.launch {
            val set = runCatching { container.sonicEngine.buildAutoDj(seed) }.getOrDefault(emptyList())
            if (set.size >= 2) {
                playAll(set, 0)
                onResult(app.getString(R.string.msg_autodj, set.size))
            } else {
                loadingRadio = false
                startSonicRadio(seed, onResult)
                return@launch
            }
            loadingRadio = false
        }
    }

    fun shufflePlay(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        // 同 playAll：先把会话切回音乐 player，队列命令才不会落到书的占位时间线。
        takeSessionForMusic(c)
        clearAbook()
        playingAccountKey = container.currentAccountKey()
        songById = songs.associateBy { it.id }
        val shuffled = songs.shuffled()
        val delivery = deliverQueue(shuffled, 0, 0L)
        c.playbackParameters = currentParams()
        c.prepare()
        c.play()
        lastPosition = null
        _state.update { it.copy(queue = delivery.songs, currentIndex = delivery.currentIndex, current = shuffled[0], positionSec = 0f, isPlaying = true, shuffle = true) }
        // pass the original order so disabling shuffle restores it
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply {
                putInt("target", 1)
                putStringArrayList("order", ArrayList(songs.map { it.id }))
            }),
            android.os.Bundle.EMPTY,
        )
    }

    fun addToQueue(song: Song) {
        val c = controller ?: run { play(song); return }
        if (c.mediaItemCount == 0) { play(song); return }
        songById = songById + (song.id to song)
        c.addMediaItem(toMediaItem(song))
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        syncFromController()
    }

    fun playNext(song: Song) {
        val c = controller ?: run { play(song); return }
        if (c.mediaItemCount == 0) { play(song); return }
        songById = songById + (song.id to song)
        val idx = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
        c.addMediaItem(idx, toMediaItem(song))
        syncFromController()
    }

    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index < 0) return
        container.haptic()
        // 走服务：双播时会话 player 是书，直接 seek 会跳到朗读占位时间线上。
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_MUSIC_JUMP, android.os.Bundle().apply { putInt("index", index) }),
            android.os.Bundle.EMPTY,
        )
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.removeMediaItem(index)
            syncFromController()
        }
    }

    fun clearQueue() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        val last = c.mediaItemCount - 1
        if (last > current) {
            c.removeMediaItems(current + 1, last + 1)
            syncFromController()
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (from in 0 until count && to in 0 until count && from != to) {
            c.moveMediaItem(from, to)
            syncFromController()
        }
    }

    // queue holds library tracks only now
    fun saveQueueAsPlaylist(name: String, onResult: (String) -> Unit = {}) {
        val title = name.trim()
        if (title.isEmpty()) return
        val ids = _state.value.queue
            .map { it.id }
            .filter { it.isNotEmpty() }
            .distinct()
        if (ids.isEmpty()) { onResult(app.getString(R.string.msg_nothing_to_save)); return }
        viewModelScope.launch {
            val ok = runCatching { container.repository.createPlaylistFromSongs(title, ids) }.getOrDefault(false)
            onResult(if (ok) app.getString(R.string.msg_playlist_saved, title) else app.getString(R.string.msg_playlist_save_failed))
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        container.haptic()
        // 走服务自定义命令：只动站内音乐，绝不动朗读（双播时暂停音乐不会停书，
        // 播放音乐也不会自动开始朗读）。
        runCatching {
            c.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_MUSIC_TOGGLE, android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY,
            )
        }
    }

    /** 只暂停站内音乐（歌曲介绍朗读时压低音乐用；会话可能正承载着书，不能直接下发 pause）。 */
    fun pause() {
        persistAbookNow()
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_MUSIC_PAUSE, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY,
        )
    }

    /** 只恢复站内音乐（绝不带起电子书朗读）。 */
    fun play() {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_MUSIC_PLAY, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY,
        )
    }

    /**
     * 让会话先切回音乐 player 再下发队列命令。
     * 停书后会话可能还粘在承载朗读的 UnifiedPlayer 上，此时 setMediaItems 会落到书的
     * 三窗占位时间线，随后的 play 还会被当成“继续朗读”而开始朗读。
     */
    private fun takeSessionForMusic(c: MediaController) {
        runCatching {
            c.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_MUSIC_SOLO, android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY,
            )
        }
    }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        // 服务侧按音乐 player 的真实时长定位（会话是书时不能用书的占位时长）
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_MUSIC_SEEK, android.os.Bundle().apply {
                putFloat("fraction", fraction.coerceIn(0f, 1f))
            }),
            android.os.Bundle.EMPTY,
        )
    }

    fun next() {
        container.haptic()
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_MUSIC_NEXT, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY,
        )
    }

    fun previous() {
        val c = controller ?: return
        container.haptic()
        // 4 秒内重播本曲的判定在服务侧按音乐 player 的真实进度做
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_MUSIC_PREV, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY,
        )
    }

    fun toggleShuffle() = sendShuffle(-1)

    // target 1 on 0 off -1 toggle service physically reorders and restores original order on disable
    private fun sendShuffle(target: Int) {
        val c = controller ?: return
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SHUFFLE, android.os.Bundle().apply { putInt("target", target) }),
            android.os.Bundle.EMPTY,
        )
    }

    fun cycleRepeat() {
        val c = controller ?: return
        // 双播时会话是书；循环模式必须改站内音乐 player（服务侧 CMD_REPEAT）。
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_REPEAT, android.os.Bundle.EMPTY),
            android.os.Bundle.EMPTY,
        )
    }

    fun toggleLikeCurrent() = toggleLike(_state.value.current.id)

    private fun recomputeLikes() {
        _state.update { it.copy(likedIds = serverLikedIds + likedPlaylistIds) }
    }

    private val likeChecked = java.util.Collections.synchronizedSet(HashSet<String>())

    fun refreshLikes() {
        viewModelScope.launch {
            serverLikedIds = runCatching { container.repository.starredIds() }.getOrDefault(serverLikedIds)
            recomputeLikes()
        }
    }

    fun checkLiked(ids: List<String>) {
        val toCheck = ids.filter { it.isNotEmpty() && it !in serverLikedIds && it !in likeChecked }.distinct()
        if (toCheck.isEmpty()) return
        viewModelScope.launch {
            val liked = runCatching { container.repository.likedSongIds(toCheck) }.getOrNull() ?: return@launch
            likeChecked.addAll(toCheck)
            if (liked.isNotEmpty()) {
                serverLikedIds = serverLikedIds + liked
                recomputeLikes()
            }
        }
    }

    // song/album/artist persist to the server playlist persists locally
    fun toggleLike(id: String, kind: String = "song") {
        if (id.isEmpty()) return
        val nowLiked = !_state.value.likedIds.contains(id)
        if (kind == "playlist") {
            likedPlaylistIds = if (nowLiked) likedPlaylistIds + id else likedPlaylistIds - id
            recomputeLikes()
            viewModelScope.launch { runCatching { container.settingsStore.setPlaylistLiked(id, nowLiked) } }
            // also sync to backend no-op for backends that can't star playlists
            viewModelScope.launch { runCatching { container.repository.setStarred(id, nowLiked, "playlist") } }
        } else {
            serverLikedIds = if (nowLiked) serverLikedIds + id else serverLikedIds - id
            recomputeLikes()
            viewModelScope.launch { runCatching { container.repository.setStarred(id, nowLiked, kind) } }
        }
    }

    fun setExpanded(value: Boolean) = _state.update { it.copy(expanded = value) }

    fun setSpeed(value: Float) {
        val snapped = (Math.round(value / 0.05f) * 0.05f).coerceIn(0.5f, 2.0f)
        _state.update { it.copy(speed = snapped) }
        controller?.playbackParameters = currentParams()
    }

    fun setPitch(value: Float) {
        _state.update { it.copy(pitch = value.coerceIn(-6f, 6f)) }
        controller?.playbackParameters = currentParams()
    }

    fun setMatchPitch(match: Boolean) {
        _state.update { it.copy(matchPitch = match) }
        controller?.playbackParameters = currentParams()
    }

    fun resetSpeedPitch() {
        _state.update { it.copy(speed = 1.0f, pitch = 0.0f) }
        controller?.playbackParameters = currentParams()
    }

    private fun currentParams(): PlaybackParameters {
        val s = _state.value
        // match pitch to speed = no time-stretch else preserve/shift pitch
        val pitchRatio = if (s.matchPitch) s.speed else 2f.pow(s.pitch / 12f)
        return PlaybackParameters(s.speed, pitchRatio)
    }

    override fun onCleared() {
        persistQueue()
        container.queueStore.requestFlush()
        ticker?.cancel()
        queueFillJob?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    private companion object {
        // each MediaItem's parcelled metadata (artwork uri, title/artist/album, extras) is a few KB;
        // a binder transaction caps near 1mb, so a few hundred full items can silently get truncated
        // by the session. deliverQueue keeps the initial setMediaItems call well under that.
        const val QUEUE_BATCH = 120
    }
}
