package com.aurora.music.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
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
import com.aurora.music.data.toSavedTrack
import com.aurora.music.model.Song
import com.aurora.music.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

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
    val sleepTimerMinutes: Int = 0,
    val sleepEndOfTrack: Boolean = false,
    val bpm: Int = 0,
    val camelot: String = "",
    val keyName: String = "",
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
    private var sleepJob: Job? = null
    private var queueFillJob: Job? = null
    private var songById: Map<String, Song> = emptyMap()

    @Volatile private var autoplayEnabled = false

    // likes merge server stars + local playlist likes
    private var serverLikedIds: Set<String> = emptySet()
    private var likedPlaylistIds: Set<String> = emptySet()
    private var lastRecordedId: String? = null
    private var lastNowPlayingId: String? = null
    // account the live queue belongs to so it persists/restores under the right key
    @Volatile private var playingAccountKey: String = ""
    private var openRestoreAttempted = false
    private var lastPersistMs = 0L
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
                // last track has no transition to honour the end-of-track sleep so do it here
                if (_state.value.sleepEndOfTrack) { controller?.pause(); _state.update { it.copy(sleepEndOfTrack = false) } }
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // reset position so the bar doesn't show the previous track during the gap usb sink lags across a skip
            _state.update { it.copy(positionSec = 0f) }
            if (_state.value.sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _state.update { it.copy(sleepEndOfTrack = false) }
            }
            item?.mediaId?.let { maybeRevive(it) }
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
                if (c.mediaItemCount == 0 && saved != null) restoreQueue(saved)
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
    }

    fun stopPlayback() {
        controller?.let { c ->
            runCatching { c.pause(); c.stop(); c.clearMediaItems() }
        }
        songById = emptyMap()
        lastRecordedId = null
        lastNowPlayingId = null
        _state.update {
            it.copy(current = emptySong, queue = emptyList(), isPlaying = false, positionSec = 0f, currentIndex = 0, expanded = false)
        }
    }

    private fun persistQueue() {
        val c = controller ?: return
        val key = playingAccountKey.ifBlank { container.currentAccountKey() }
        if (key.isBlank()) return
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
        ))
    }

    private fun restoreQueue(sq: SavedQueue) {
        val c = controller ?: return
        val songs = sq.tracks.orEmpty().map { it.toSong() }.filter { it.id.isNotEmpty() && it.streamUrl.isNotEmpty() }
        if (songs.isEmpty()) return
        songById = songs.associateBy { it.id }
        val idx = sq.currentIndex.coerceIn(0, songs.lastIndex)
        val delivery = deliverQueue(songs, idx, sq.positionSec.toLong() * 1000)
        c.playbackParameters = currentParams()
        c.repeatMode = when (sq.repeat) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
        c.prepare()   // buffer at saved position but stay paused
        _state.update {
            it.copy(queue = delivery.songs, current = songs[idx], positionSec = sq.positionSec.toFloat(), isPlaying = false, currentIndex = delivery.currentIndex, shuffle = sq.shuffle,
                repeat = when (sq.repeat) { 1 -> RepeatMode.ALL; 2 -> RepeatMode.ONE; else -> RepeatMode.OFF })
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
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sendSleepFade(start = false)
        _state.update { it.copy(sleepTimerMinutes = minutes, sleepEndOfTrack = false) }
        if (minutes <= 0) return
        sleepJob = viewModelScope.launch {
            val total = minutes * 60_000L
            delay((total - SLEEP_FADE_MS).coerceAtLeast(0L))
            sendSleepFade(start = true)
            _state.update { it.copy(sleepTimerMinutes = 0) }
        }
    }

    fun setSleepEndOfTrack() {
        sleepJob?.cancel()
        sendSleepFade(start = false)
        _state.update { it.copy(sleepTimerMinutes = 0, sleepEndOfTrack = true) }
    }

    private fun sendSleepFade(start: Boolean) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP_FADE, android.os.Bundle().apply {
                putInt("fadeMs", if (start) SLEEP_FADE_MS.toInt() else 0)
            }),
            android.os.Bundle.EMPTY,
        )
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
                if (c.effectivelyPlaying) {
                    val posSec = (c.currentPosition / 1000f).coerceAtLeast(0f)
                    _state.update { it.copy(positionSec = posSec) }
                    maybeNowPlaying()
                    recordIfPlayed(posSec)
                    val now = System.currentTimeMillis()
                    if (now - lastPersistMs > 2000) { lastPersistMs = now; persistQueue() }
                }
            }
        }
    }

    private fun syncFromController() {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId
        val cur = mediaId?.let { songById[it] } ?: _state.value.current
        val q = (0 until c.mediaItemCount).mapNotNull { i -> songById[c.getMediaItemAt(i).mediaId] }
        if (cur.id != lastKeyInfoId) { lastKeyInfoId = cur.id; lastKeyInfo = runCatching { container.sonicEngine.keyInfo(cur.id) }.getOrNull() }
        val ki = lastKeyInfo
        _state.update {
            it.copy(
                current = cur,
                isPlaying = c.effectivelyPlaying,
                shuffle = c.shuffleModeEnabled,
                repeat = when (c.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
                positionSec = (c.currentPosition / 1000f).coerceAtLeast(0f),
                queue = if (q.isNotEmpty()) q else it.queue,
                currentIndex = c.currentMediaItemIndex.coerceAtLeast(0),
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
        playingAccountKey = container.currentAccountKey()
        songById = songs.associateBy { it.id }
        val idx = startIndex.coerceIn(0, songs.lastIndex)
        val delivery = deliverQueue(songs, idx, 0L)
        c.playbackParameters = currentParams()
        c.prepare()
        c.play()
        // fresh context plays in order make sure shuffle is off
        sendShuffle(0)
        _state.update { it.copy(queue = delivery.songs, currentIndex = delivery.currentIndex, current = songs[idx], positionSec = 0f, isPlaying = true) }
    }

    fun play(song: Song) = playAll(listOf(song), 0)

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
        playingAccountKey = container.currentAccountKey()
        songById = songs.associateBy { it.id }
        val shuffled = songs.shuffled()
        val delivery = deliverQueue(shuffled, 0, 0L)
        c.playbackParameters = currentParams()
        c.prepare()
        c.play()
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
        container.haptic()
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0)
            c.play()
            syncFromController()
        }
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
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun play() {
        controller?.play()
    }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        val dur = _state.value.durationSec
        if (dur > 0) c.seekTo((fraction.coerceIn(0f, 1f) * dur * 1000).toLong())
    }

    fun next() {
        container.haptic()
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        container.haptic()
        if (c.currentPosition > 4000) c.seekTo(0) else c.seekToPreviousMediaItem()
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
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
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
        const val SLEEP_FADE_MS = 6_000L
        // each MediaItem's parcelled metadata (artwork uri, title/artist/album, extras) is a few KB;
        // a binder transaction caps near 1mb, so a few hundred full items can silently get truncated
        // by the session. deliverQueue keeps the initial setMediaItems call well under that.
        const val QUEUE_BATCH = 120
    }
}
