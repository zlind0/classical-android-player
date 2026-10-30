package com.aurora.music.data.cast

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 局域网资料库同步器。
 *
 * 发现沿用 [CastDiscovery]（启动后常驻浏览，不再只在投屏选择页开）；
 * 对每台设备定时拉 `/library/all`，把音乐/电子书/有声书元信息转成可直接播放的
 * [Song] / 列表条目。元信息落盘到 filesDir/remote_library，离线时仍显示上次快照。
 *
 * 缓存策略：
 * - 音乐：当前 + 下一首预取，保留上一首，继续往后播时删除更早的缓存；
 * - 电子书：最近阅读（按用户设置条数）保留整本，超出部分删缓存；
 * - 有声书：当前书前/中/后三章 + “在听”里的书各自三章，其余删缓存。
 */
class RemoteLibraryManager(
    context: Context,
    private val discovery: CastDiscovery,
    private val ebookRecentLimit: () -> Int,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val _libraries = MutableStateFlow<List<RemoteDeviceLibrary>>(emptyList())
    val libraries: StateFlow<List<RemoteDeviceLibrary>> = _libraries.asStateFlow()

    private val _ebookRecents = MutableStateFlow<List<RemoteEbookEntry>>(emptyList())
    val ebookRecents: StateFlow<List<RemoteEbookEntry>> = _ebookRecents.asStateFlow()

    private val _abookPlays = MutableStateFlow<Map<String, RemoteAbookPlay>>(emptyMap())
    val abookPlays: StateFlow<Map<String, RemoteAbookPlay>> = _abookPlays.asStateFlow()

    /** 每一台设备的最近一次快照（含 offline），UI 直接读。 */
    private val payloads = ConcurrentHashMap<String, CachedPayload>()

    /** 所有远程歌曲（音乐 + 有声书章节）按播放用 id 索引，缓存维护用。 */
    private val songsById = ConcurrentHashMap<String, Song>()

    private val storeDir: File
        get() = File(appContext.filesDir, "remote_library").apply { if (!isDirectory) runCatching { mkdirs() } }

    private val ebookDir: File
        get() = File(appContext.filesDir, "remote_ebooks").apply { if (!isDirectory) runCatching { mkdirs() } }

    private val prefs by lazy {
        appContext.getSharedPreferences("aurora_remote_library", Context.MODE_PRIVATE)
    }

    data class RemoteAbookPlay(
        val deviceId: String = "",
        val bookId: String = "",
        val index: Int = 0,
        val positionMs: Long = 0L,
        val lastPlayedAt: Long = 0L,
    ) {
        val key: String get() = "$deviceId|$bookId"
    }

    init {
        // 大库快照 JSON 可能很大，全部放 IO 线程，别拖慢 Application 启动。
        scope.launch {
            loadCachedPayloads()
            loadRecents()
            syncLoop()
        }
    }

    // ---- 同步 ----

    private suspend fun syncLoop() {
        var first = true
        while (scope.isActive) {
            try {
                pruneExpired()
                val discovered = runCatching { discovery.devices.value }.getOrDefault(emptyList())
                    .filter { it.kind == CastDeviceKind.APP && it.port > 0 }
                val discoveredIds = discovered.map { it.id }.toSet()
                // 关键：设备从后台被杀后，mDNS 可能不会重新通知正在浏览的一端。
                // 对“已缓存但当前没被 mDNS/UDP 发现”的设备，用上次 host/port 主动探活，
                // 只要对端应用重新起来，就能恢复在线并跳过全量 meta。
                val known = payloads.values
                    .filter {
                        it.deviceId !in discoveredIds &&
                            it.host.isNotBlank() && it.port > 0
                    }
                    .map {
                        CastDevice(
                            id = it.deviceId,
                            name = it.deviceName,
                            host = it.host,
                            port = it.port,
                            source = "cache",
                            lastSeen = it.lastSeenAt,
                        )
                    }
                (discovered + known).distinctBy { it.id }.forEach { syncDevice(it) }
            } catch (t: Throwable) {
                // 单轮同步异常不能把常驻循环打死，否则会永久看不到对端。
                android.util.Log.w("AuroraRemoteLibrary", "sync loop error", t)
            }
            delay(if (first) 1_500L else 15_000L)
            first = false
        }
    }

    private suspend fun syncDevice(device: CastDevice) {
        // 先问轻量版本：时间戳没变就直接复用磁盘上的完整快照，不拉全量 meta。
        val versionResult = fetchVersion(device)
        if (versionResult == null) {
            markOffline(device.id)
            return
        }
        val cached = payloads[device.id]
        if (versionResult.first && cached != null &&
            cached.payload.libraryUpdatedAt > 0L &&
            cached.payload.libraryUpdatedAt == versionResult.second
        ) {
            // 版本一致：只刷新在线状态和最后连接时间，meta 不发。
            val now = System.currentTimeMillis()
            val sameEndpoint = cached.host == device.host && cached.port == device.port
            val wasOnline = cached.online && sameEndpoint
            val updated = cached.copy(
                deviceName = device.name,
                host = device.host,
                port = device.port,
                online = true,
                lastSeenAt = now,
            )
            payloads[device.id] = updated
            // lastSeen 每 15 秒写一次盘没必要；一小时落一次足够判断 30 天过期。
            if (!sameEndpoint || now - cached.lastSeenAt > LAST_SEEN_SAVE_INTERVAL_MS) {
                persistPayload(updated)
            }
            if (!wasOnline) rebuildLibraries()
            return
        }

        val json = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("${device.baseUrl}/library/all?t=${System.currentTimeMillis()}")
                    .header("X-Aurora-Device", "1")
                    .get()
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
        }
        val wire = json?.let {
            runCatching { gson.fromJson(it, RemoteLibraryWire::class.java) }.getOrNull()
        }
        if (wire == null) {
            markOffline(device.id)
            return
        }
        val now = System.currentTimeMillis()
        val oldVersion = cached?.payload?.libraryUpdatedAt ?: 0L
        if (oldVersion != 0L && wire.libraryUpdatedAt != 0L && oldVersion != wire.libraryUpdatedAt) {
            // 封面可能随资料库变化：版本变化时清掉旧封面，让下面的预取重新下载。
            RemoteArtCache.clearDevice(appContext, device.id)
        }
        val fresh = CachedPayload(
            deviceId = device.id,
            deviceName = device.name,
            host = device.host,
            port = device.port,
            fetchedAt = now,
            lastSeenAt = now,
            payload = wire.copy(deviceId = device.id, deviceName = device.name),
            online = true,
        )
        payloads[device.id] = fresh
        persistPayload(fresh)
        rebuildLibraries()
        scope.launch { prefetchArtwork(fresh); rebuildLibraries() }
    }

    /**
     * 返回 (是否支持版本接口, 版本号)；网络失败返回 null。
     * 老版本设备没有 /library/version 时返回 (false, 0)，调用方走全量。
     */
    private suspend fun fetchVersion(device: CastDevice): Pair<Boolean, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("${device.baseUrl}/library/version?t=${System.currentTimeMillis()}")
                .header("X-Aurora-Device", "1")
                .get()
                .build()
            client.newCall(req).execute().use { r ->
                if (r.code == 404) return@use false to 0L
                if (!r.isSuccessful) return@use null
                val body = r.body?.string() ?: return@use false to 0L
                val v = runCatching { gson.fromJson(body, RemoteLibraryVersion::class.java) }
                    .getOrNull()?.version ?: 0L
                if (v > 0L) true to v else false to 0L
            }
        }.getOrNull()
    }

    /** 艺术家/专辑/图书/有声书封面一次性补到本地，之后 UI 直接用 file://。 */
    private suspend fun prefetchArtwork(cached: CachedPayload) {
        val lib = runCatching { cached.toLibrary(appContext) }.getOrNull() ?: return
        val urls = LinkedHashSet<String>()
        lib.albums.forEach { if (it.artworkUrl.isNotBlank()) urls += it.artworkUrl }
        lib.artists.forEach { if (it.imageUrl.isNotBlank()) urls += it.imageUrl }
        lib.ebooks.filter { it.hasCover }.forEach {
            urls += "${lib.baseUrl}/media/cover?kind=ebook&key=${enc(it.path)}"
        }
        lib.audiobooks.filter { it.hasCover }.forEach {
            urls += "${lib.baseUrl}/media/cover?kind=audiobook&key=${enc(it.id)}"
        }
        RemoteArtCache.prefetch(appContext, cached.deviceId, urls)
    }

    private fun markOffline(deviceId: String) {
        val c = payloads[deviceId] ?: return
        if (!c.online) return
        payloads[deviceId] = c.copy(online = false)
        rebuildLibraries()
    }

    /** 30 天没连上过：删掉该设备的 meta 与封面缓存。 */
    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        val expired = payloads.values.filter { it.lastSeenAt == 0L || it.lastSeenAt < cutoff }
        if (expired.isEmpty()) return
        expired.forEach {
            payloads.remove(it.deviceId)
            runCatching { File(storeDir, "${safeId(it.deviceId)}.json").delete() }
            RemoteArtCache.clearDevice(appContext, it.deviceId)
        }
        rebuildLibraries()
    }

    private fun rebuildLibraries() {
        val out = payloads.values.map { it.toLibrary(appContext) }.sortedBy { it.deviceName.lowercase() }
        val allSongs = ConcurrentHashMap<String, Song>()
        out.forEach { lib -> lib.songs.forEach { allSongs[it.id] = it } }
        songsById.putAll(allSongs)
        // 清掉已不存在设备的条目（保留正在播放的章节，避免缓存维护丢失映射）
        val live = out.flatMap { it.songs }.map { it.id }.toSet()
        songsById.keys.filter { it !in live && !it.startsWith("audiobook:remote:") }.forEach { songsById.remove(it) }
        _libraries.value = out
        lastQueueSignature = ""
        // 远程有声书在听列表：以设备快照为元信息，播放记录为进度
        rebuildAbookRecents()
    }

    // ---- 电子书 ----

    fun ebookEntries(): List<RemoteEbookEntry> =
        _libraries.value.flatMap { lib ->
            lib.ebooks.map { b ->
                val cached = _ebookRecents.value.firstOrNull { it.key == "${lib.deviceId}|${b.path}" }
                RemoteEbookEntry(
                    deviceId = lib.deviceId,
                    deviceName = lib.deviceName,
                    remotePath = b.path,
                    title = b.title,
                    author = b.author,
                    coverUrl = ebookCoverUrl(lib, b.path, b.hasCover),
                    size = b.size,
                    localPath = cached?.localPath.orEmpty(),
                    lastReadAt = cached?.lastReadAt ?: 0L,
                )
            }
        }

    fun ebookEntriesOf(deviceId: String): List<RemoteEbookEntry> =
        ebookEntries().filter { it.deviceId == deviceId }

    fun ebookCoverUrl(lib: RemoteDeviceLibrary, path: String, hasCover: Boolean): String =
        if (!hasCover) "" else RemoteArtCache.cachedOrRemote(
            appContext, lib.deviceId, "${lib.baseUrl}/media/cover?kind=ebook&key=${enc(path)}",
        )

    fun audiobookCoverUrl(lib: RemoteDeviceLibrary, bookId: String, hasCover: Boolean): String =
        if (!hasCover) "" else RemoteArtCache.cachedOrRemote(
            appContext, lib.deviceId, "${lib.baseUrl}/media/cover?kind=audiobook&key=${enc(bookId)}",
        )

    /** 老版本设备没有根目录元信息时兜底：按路径给出一个平铺根。 */
    fun ebookRootsOf(lib: RemoteDeviceLibrary): List<RemoteEbookRootWire> =
        lib.ebookRoots.ifEmpty {
            if (lib.ebooks.isEmpty()) emptyList()
            else listOf(
                RemoteEbookRootWire(
                    key = "all",
                    rootPath = "",
                    displayName = "电子书",
                    shelf = false,
                    bookCount = lib.ebooks.size,
                ),
            )
        }

    suspend fun openEbook(entry: RemoteEbookEntry): File? = withContext(Dispatchers.IO) {
        val lib = _libraries.value.firstOrNull { it.deviceId == entry.deviceId }
            ?: return@withContext null
        val dir = File(ebookDir, safeId(entry.deviceId)).apply { mkdirs() }
        val ext = entry.remotePath.substringAfterLast('.', "epub").lowercase().takeIf {
            it in setOf("epub", "mobi", "azw", "azw3")
        } ?: "epub"
        val name = sha1(entry.remotePath) + "." + ext
        val dest = File(dir, name)
        if (!(dest.isFile && dest.length() > 0)) {
            val tmp = File(dir, "$name.part")
            val ok = runCatching {
                val req = Request.Builder()
                    .url("${lib.baseUrl}/media/ebook?path=${enc(entry.remotePath)}")
                    .get()
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    val body = r.body ?: return@use false
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    true
                }
            }.getOrDefault(false)
            if (!ok || !(tmp.isFile && tmp.length() > 0)) {
                runCatching { tmp.delete() }
                return@withContext null
            }
            if (!tmp.renameTo(dest)) {
                runCatching { tmp.copyTo(dest, overwrite = true) }
                runCatching { tmp.delete() }
            }
        }
        val now = System.currentTimeMillis()
        val updated = (listOf(
            entry.copy(
                localPath = dest.absolutePath,
                deviceName = lib.deviceName,
                lastReadAt = now,
            ),
        ) + _ebookRecents.value.filterNot { it.key == entry.key })
            .sortedByDescending { it.lastReadAt }
        setEbookRecents(updated)
        dest
    }

    /** 用户调整“最近阅读数量”后立刻按新上限清缓存。 */
    fun trimEbookCache() {
        if (_ebookRecents.value.isNotEmpty()) setEbookRecents(_ebookRecents.value)
    }

    /** 阅读页打开远程书后把进度时间往前推（最近阅读排序）。 */
    fun touchEbook(entryKey: String) {
        val now = System.currentTimeMillis()
        val updated = _ebookRecents.value.map {
            if (it.key == entryKey) it.copy(lastReadAt = now) else it
        }.sortedByDescending { it.lastReadAt }
        setEbookRecents(updated)
    }

    private fun setEbookRecents(list: List<RemoteEbookEntry>) {
        val limit = runCatching { ebookRecentLimit() }.getOrDefault(20).coerceIn(1, 50)
        val kept = list.sortedByDescending { it.lastReadAt }.take(limit)
        val dropped = list.filterNot { e -> kept.any { it.key == e.key } }
        dropped.forEach { e -> if (e.localPath.isNotBlank()) runCatching { File(e.localPath).delete() } }
        // 目录里不属于保留条目的残余文件也清掉
        val keepPaths = kept.map { it.localPath }.filter { it.isNotBlank() }.toSet()
        runCatching {
            ebookDir.walkTopDown().filter { it.isFile && it.absolutePath !in keepPaths }.forEach {
                if (it.name.endsWith(".part")) runCatching { it.delete() }
            }
        }
        _ebookRecents.value = kept
        persistRecents()
    }

    // ---- 有声书 ----

    fun remoteAudiobooks(): List<RemoteAudiobookEntry> =
        _libraries.value.flatMap { lib ->
            lib.audiobooks.map { b ->
                val play = _abookPlays.value["${lib.deviceId}|${b.id}"]
                RemoteAudiobookEntry(
                    deviceId = lib.deviceId,
                    deviceName = lib.deviceName,
                    bookId = b.id,
                    title = b.title,
                    coverUrl = audiobookCoverUrl(lib, b.id, b.hasCover),
                    chapters = b.chapters,
                    currentIndex = play?.index ?: 0,
                    positionMs = play?.positionMs ?: 0L,
                    lastPlayedAt = play?.lastPlayedAt ?: 0L,
                )
            }
        }

    fun abookRecents(limit: Int = 5): List<RemoteAudiobookEntry> =
        remoteAudiobooks().filter { it.lastPlayedAt > 0 }
            .sortedByDescending { it.lastPlayedAt }
            .take(limit)

    /** 播放页用：按 PlayerViewModel 的 abookId 反查远程书（本地书返回 null）。 */
    fun remoteAudiobookForPlayId(playId: String?): RemoteAudiobookEntry? {
        if (playId.isNullOrBlank() || !playId.startsWith("remote:")) return null
        return remoteAudiobooks().firstOrNull { abookPlayId(it) == playId }
    }

    /** 把远程有声书组装成播放队列；章节 id 带 audiobook: 前缀，复用现有听书管道。 */
    fun songsForAudiobook(entry: RemoteAudiobookEntry): List<Song> {
        val lib = _libraries.value.firstOrNull { it.deviceId == entry.deviceId } ?: return emptyList()
        return entry.chapters.mapIndexed { i, c ->
            val id = remoteAudiobookSongId(entry.deviceId, entry.bookId, i)
            val song = Song(
                id = id,
                title = c.title.ifBlank { c.path.substringAfterLast('/').substringBeforeLast('.') },
                artist = entry.displayTitle,
                album = entry.displayTitle,
                artworkUrl = entry.coverUrl,
                durationSec = c.durationSec,
                streamUrl = "${lib.baseUrl}/media/audiobook?path=${enc(c.path)}",
                suffix = c.path.substringAfterLast('.', ""),
                path = c.path,
                accent = accentFor(c.path),
                trackNumber = c.trackNumber,
                // 离线时只有已缓存的三章可点；其余 UI 会按 available=false 显示灰色。
                available = lib.online || chapterCached(entry.deviceId, c.path),
            )
            songsById[id] = song
            song
        }
    }

    fun noteAudiobookPlaying(entry: RemoteAudiobookEntry, index: Int, positionMs: Long) {
        val key = "${entry.deviceId}|${entry.bookId}"
        val updated = _abookPlays.value.toMutableMap()
        updated[key] = RemoteAbookPlay(
            deviceId = entry.deviceId,
            bookId = entry.bookId,
            index = index.coerceAtLeast(0),
            positionMs = positionMs.coerceAtLeast(0L),
            lastPlayedAt = System.currentTimeMillis(),
        )
        _abookPlays.value = updated.entries.sortedByDescending { it.value.lastPlayedAt }
            .take(5).associate { it.key to it.value }
        persistRecents()
        rebuildAbookRecents()
    }

    fun chapterCached(deviceId: String, path: String): Boolean {
        val lib = _libraries.value.firstOrNull { it.deviceId == deviceId } ?: return false
        return RemoteMediaCache.isCached("${lib.baseUrl}/media/audiobook?path=${enc(path)}")
    }

    fun isAudiobookSong(id: String): Boolean = id.startsWith("audiobook:remote:")

    /** 供 PlayerViewModel 用的书 id（与章节 Song.id 的前缀保持一致）。 */
    fun abookPlayId(entry: RemoteAudiobookEntry): String =
        "remote:${entry.deviceId}:" + Base64.encodeToString(
            entry.bookId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )

    // ---- 播放缓存维护（由 AppContainer 监听 musicState 调用） ----

    @Volatile private var lastQueueSignature: String = ""

    fun onPlaybackQueue(queueIds: List<String>, index: Int, positionSec: Float) {
        if (queueIds.isEmpty()) return
        val sig = "$index|${queueIds.size}|${queueIds.getOrNull(index).orEmpty()}"
        if (sig == lastQueueSignature) return
        lastQueueSignature = sig
        val audiobook = queueIds.any { isAudiobookSong(it) }
        if (audiobook) {
            handleAudiobookCache(queueIds, index.coerceIn(0, queueIds.lastIndex), (positionSec * 1000).toLong())
        } else {
            handleMusicCache(queueIds, index.coerceIn(0, queueIds.lastIndex))
        }
    }

    @Volatile private var lastRemoteAbookSaveAt: Long = 0L

    /** 播放中定时落一次远程听书进度（切章/退出后“在听”能接着上次位置）。 */
    fun onPlaybackPosition(queueIds: List<String>, index: Int, positionSec: Float) {
        val id = queueIds.getOrNull(index) ?: return
        val parsed = parseAudiobookSongId(id) ?: return
        val now = System.currentTimeMillis()
        if (now - lastRemoteAbookSaveAt < 5_000L) return
        val entry = remoteAudiobooks().firstOrNull {
            it.deviceId == parsed.first && it.bookId == parsed.second
        } ?: return
        lastRemoteAbookSaveAt = now
        noteAudiobookPlaying(entry, parsed.third, (positionSec * 1000).toLong())
    }

    private fun handleMusicCache(queueIds: List<String>, index: Int) {
        val window = listOf(index - 1, index, index + 1)
            .filter { it in queueIds.indices }
            .mapNotNull { songsById[queueIds[it]] }
            .filter { it.streamUrl.startsWith("http") }
        val urls = window.map { it.streamUrl }
        scope.launch {
            window.forEachIndexed { i, song ->
                // 当前与下一首抢先；上一首已经缓存则跳过
                if (i >= 1) RemoteMediaCache.prefetch(song.streamUrl)
            }
            RemoteMediaCache.retainMusic(urls)
        }
    }

    private fun handleAudiobookCache(queueIds: List<String>, index: Int, positionMs: Long) {
        // 记下当前书进度，供“在听”与三章缓存使用
        val currentId = queueIds.getOrNull(index) ?: return
        val currentSong = songsById[currentId] ?: return
        val parsed = parseAudiobookSongId(currentId) ?: return
        val entry = remoteAudiobooks().firstOrNull {
            it.deviceId == parsed.first && it.bookId == parsed.second
        }
        if (entry != null) noteAudiobookPlaying(entry, parsed.third, positionMs)

        val triple = listOf(index - 1, index, index + 1).filter { it in queueIds.indices }
            .mapNotNull { songsById[queueIds[it]] }
            .filter { it.streamUrl.startsWith("http") }
        val retained = mutableSetOf<String>()
        triple.forEach { retained += it.streamUrl }
        // “在听”里每本书也保前/中/后三章
        abookRecents().forEach { e ->
            val songs = songsForAudiobook(e)
            val i = e.currentIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
            listOf(i - 1, i, i + 1).filter { it in songs.indices }.forEach { retained += songs[it].streamUrl }
        }
        scope.launch {
            triple.forEach { RemoteMediaCache.prefetch(it.streamUrl, audiobook = true) }
            // 最近书目的三章也后台预取（书小，串行即可）
            abookRecents().forEach { e ->
                val songs = songsForAudiobook(e)
                val i = e.currentIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
                listOf(i - 1, i, i + 1).filter { it in songs.indices }.forEach {
                    RemoteMediaCache.prefetch(songs[it].streamUrl, audiobook = true)
                }
            }
            RemoteMediaCache.retainAudiobooks(retained)
        }
    }

    // ---- 持久化 ----

    private fun loadCachedPayloads() {
        runCatching {
            storeDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                val type = object : TypeToken<CachedPayload>() {}.type
                val c = gson.fromJson<CachedPayload>(f.readText(), type) ?: return@forEach
                if (c.deviceId.isNotBlank()) payloads[c.deviceId] = c.copy(online = false)
            }
        }
        pruneExpired()
        rebuildLibraries()
    }

    private fun persistPayload(c: CachedPayload) {
        runCatching {
            File(storeDir, "${safeId(c.deviceId)}.json").writeText(gson.toJson(c))
        }
    }

    private fun loadRecents() {
        runCatching {
            val ebookType = object : TypeToken<List<RemoteEbookEntry>>() {}.type
            _ebookRecents.value = gson.fromJson<List<RemoteEbookEntry>>(
                prefs.getString("ebook_recents", "[]"), ebookType,
            ).orEmpty().sortedByDescending { it.lastReadAt }
        }
        runCatching {
            val playType = object : TypeToken<List<RemoteAbookPlay>>() {}.type
            val plays = gson.fromJson<List<RemoteAbookPlay>>(prefs.getString("abook_plays", "[]"), playType).orEmpty()
            _abookPlays.value = plays.associateBy { it.key }
        }
    }

    private fun persistRecents() {
        runCatching {
            prefs.edit()
                .putString("ebook_recents", gson.toJson(_ebookRecents.value))
                .putString("abook_plays", gson.toJson(_abookPlays.value.values.toList()))
                .apply()
        }
    }

    private fun rebuildAbookRecents() {
        // 列表由 remoteAudiobooks() 动态拼装；这里只做状态通知（保留接口对称）
        val current = _abookPlays.value
        if (current.isEmpty()) return
        _libraries.value = _libraries.value.toList()
    }

    private fun CachedPayload.toLibraryOrNull(): RemoteDeviceLibrary? =
        runCatching { toLibrary(appContext) }.getOrNull()

    private fun parseAudiobookSongId(id: String): Triple<String, String, Int>? {
        // audiobook:remote:<deviceId>:<base64(bookId)>#<index>
        if (!id.startsWith("audiobook:remote:")) return null
        val rest = id.removePrefix("audiobook:remote:")
        val idx = rest.substringAfterLast('#', "").toIntOrNull() ?: return null
        val body = rest.substringBeforeLast('#')
        val sep = body.indexOf(':')
        if (sep <= 0) return null
        val deviceId = body.substring(0, sep)
        val bookB64 = body.substring(sep + 1)
        val bookId = runCatching {
            String(Base64.decode(bookB64, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return Triple(deviceId, bookId, idx)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun safeId(s: String): String = sha1(s).take(20)

    private fun sha1(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(s.hashCode().toString())

    private fun remoteMusicSongId(deviceId: String, trackId: String): String =
        "remote:$deviceId:" + Base64.encodeToString(
            trackId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )

    private fun remoteAudiobookSongId(deviceId: String, bookId: String, index: Int): String =
        "audiobook:remote:$deviceId:" + Base64.encodeToString(
            bookId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        ) + "#$index"

    private companion object {
        /** 30 天没成功连上（版本探测也算）就清掉 meta 与封面缓存。 */
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** 在线心跳只每小时落一次盘，避免每 15 秒重写整份大 JSON。 */
        const val LAST_SEEN_SAVE_INTERVAL_MS = 60L * 60 * 1000
    }

    private data class CachedPayload(
        val deviceId: String = "",
        val deviceName: String = "",
        val host: String = "",
        val port: Int = 0,
        val fetchedAt: Long = 0L,
        val lastSeenAt: Long = 0L,
        val payload: RemoteLibraryWire = RemoteLibraryWire(),
        val online: Boolean = false,
    ) {
        fun toLibrary(context: Context): RemoteDeviceLibrary {
            val sid = deviceId
            val base = "http://$host:$port"
            val songs = payload.music.filter { it.id.isNotBlank() }.map { t ->
                val id = "remote:$sid:" + Base64.encodeToString(
                    t.id.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP,
                )
                val remoteArt = if (t.hasArt) "$base/media/songart?id=${URLEncoder.encode(t.id, "UTF-8")}" else ""
                Song(
                    id = id,
                    title = t.title.ifBlank { t.id },
                    artist = t.artist,
                    album = t.album,
                    artworkUrl = if (remoteArt.isBlank()) "" else RemoteArtCache.cachedOrRemote(context, sid, remoteArt),
                    durationSec = t.durationSec,
                    streamUrl = "$base/media/music?id=${URLEncoder.encode(t.id, "UTF-8")}",
                    albumId = t.albumId,
                    artistId = t.artistId,
                    trackNumber = t.trackNumber,
                    discNumber = t.discNumber,
                    accent = accentFor(id),
                )
            }
            val artists = songs.groupBy { it.artist.ifBlank { "未知艺人" } }
                .map { (name, list) ->
                    RemoteArtistGroup(
                        name = name,
                        songs = list,
                        imageUrl = list.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
                    )
                }
                .sortedBy { it.name.lowercase() }
            val albums = songs.groupBy { it.albumId.ifBlank { "${it.artist}|${it.album}" } }
                .map { (key, list) ->
                    RemoteAlbumGroup(
                        id = "remote-album:$sid:$key",
                        title = list.first().album.ifBlank { "未知专辑" },
                        artist = list.first().artist,
                        artworkUrl = list.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
                        songs = list,
                    )
                }
                .sortedBy { it.title.lowercase() }
            return RemoteDeviceLibrary(
                deviceId = sid, deviceName = deviceName, host = host, port = port,
                online = online, fetchedAt = fetchedAt,
                songs = songs, artists = artists, albums = albums,
                ebookRoots = payload.ebookRoots,
                ebooks = payload.ebooks, audiobooks = payload.audiobooks,
            )
        }
    }
}
