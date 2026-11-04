package com.aurora.music.data.cast

import android.content.Context
import com.aurora.music.data.ebook.EbookBlock
import com.aurora.music.data.ebook.EbookChapter
import com.aurora.music.data.ebook.EbookFeedResume
import com.aurora.music.data.ebook.EbookStore
import com.aurora.music.data.ebook.ParsedEbook
import com.google.gson.Gson
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 随机阅读书（只带元信息与阅读位置，正文按章向服务端取）。 */
data class RandomFeedBook(
    val path: String = "",
    val title: String = "",
    val author: String = "",
    val chapterCount: Int = 0,
    val chapterTitles: List<String> = emptyList(),
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val blockIndex: Int = 0,
    val charOffset: Int = 0,
)

/** 随机阅读当前现场：上一本/下一本只用于上下滑动时的界限提示。 */
data class RandomFeedState(
    val deviceId: String = "",
    val rootKey: String = "",
    val sessionId: String = "",
    val current: RandomFeedBook? = null,
    val previous: RandomFeedBook? = null,
    val next: RandomFeedBook? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** 本地预缓存里还有没有下一本；false 时 UI 禁止上滑/切下一本。 */
    val canNext: Boolean = true,
)

data class FeedPosition(val chapter: Int, val block: Int, val char: Int)

/** 本地/设备间随机阅读的集合来源：默认书架、扫描根，或根目录下的某个文件夹。 */
data class LocalRandomFeedSpec(
    val key: String = "",
    val shelf: Boolean = false,
    val rootId: Long = 0L,
    val rootPath: String = "",
    val dirPath: String = "",
)

private data class FeedStartBody(
    val rootKey: String,
    val paths: List<String>,
    val includeImages: Boolean,
    val resume: FeedResumeWire?,
)

private data class FeedTurnBody(val sessionId: String, val direction: Int)

private data class FeedBatchBody(val rootKey: String, val count: Int)

/** 老桌面端只认 paths：退回到一个上限样本，避免整个书库再次进 body。 */
private const val LEGACY_POOL_SAMPLE = 512

private data class FeedCloseBody(val sessionId: String)

/**
 * 随机阅读客户端：只做会话、历史与按章取正文。
 *
 * 服务端始终保持 3 本已解析的书并随机定位到章/段/字；客户端不排全书的页，
 * 只在窗口翻到某一章时取该章正文交给 [com.aurora.music.ui.ebook.rememberPageWindow] 分页。
 * 翻到章节边界时静默下载整本（[cacheWholeBook]），后续章节优先读本机已解析副本。
 */
class RandomFeedManager(
    context: Context,
    private val remoteLibrary: RemoteLibraryManager,
    private val ebookStore: EbookStore,
) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        // 首次 start 要等桌面端解析 3 本书（大 EPUB 可能较慢），读超时留足。
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 切书/取预缓存批次：链路卡住时快速失败，别让 UI 等 90 秒。 */
    private val turnClient = client.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(RandomFeedState())
    val state: StateFlow<RandomFeedState> = _state.asStateFlow()

    /** 章节缓存版本：正文加载完成后 UI 用它重组（缓存本身放在普通 Map，避免大对象进快照）。 */
    private val _chapterVersion = MutableStateFlow(0)
    val chapterVersion: StateFlow<Int> = _chapterVersion.asStateFlow()

    private val positions = ConcurrentHashMap<String, FeedPosition>()
    private val chapters = ConcurrentHashMap<String, MutableMap<Int, EbookChapter>>()
    private val parsedBooks = ConcurrentHashMap<String, ParsedEbook>()
    private val localFiles = ConcurrentHashMap<String, File>()
    private val wholeBookJobs = ConcurrentHashMap.newKeySet<String>()
    /** 本地解析与服务端章节数对不上的书：记住后不再反复解析，继续走逐章接口。 */
    private val localMismatch = ConcurrentHashMap.newKeySet<String>()
    /** 服务端口径的章节数：整本静默解析后据此校验，解析口径不一致就不启用本地副本。 */
    private val expectedChapters = ConcurrentHashMap<String, Int>()

    private val history = ArrayDeque<RandomFeedBook>()
    private val forward = ArrayDeque<RandomFeedBook>()

    // ---- 远程 feed 的本地预缓存：一次向 relay 要一批，切书不再等网络 ----
    private val remoteLock = Any()
    private val remoteQueue = ArrayDeque<RandomFeedBook>()
    private val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var remoteQueueJob: Job? = null
    private var remoteRetryJob: Job? = null
    private var remoteCacheSize = 20

    // ---- 本地/设备间模式：整本下载并解析，随机排队，提前解析若干本 ----
    private val localLock = Any()
    private val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var localMode = false
    private var localDownloadDeviceId = ""
    private var localPool: List<String> = emptyList()
    private val localSeen = HashSet<String>()
    private val localQueue = ArrayDeque<RandomFeedBook>()
    private var localPrefetchJob: Job? = null
    /** 每次开始/关闭本地会话都 +1：阻塞中的解析返回后据此丢弃过期结果。 */
    private var localSession = 0

    companion object {
        /** 本地随机阅读提前解析的整本书数量：切书时直接用内存里的解析结果。 */
        private const val LOCAL_PREFETCH = 4
    }

    private var rawCurrent: RandomFeedBook? = null
    private var serverPrevious: RandomFeedBook? = null
    private var serverNext: RandomFeedBook? = null

    fun currentBook(): RandomFeedBook? = rawCurrent?.let(::resolve)

    /** 当前是否走“整本下载 + 本地解析”模式。 */
    fun isLocalMode(): Boolean = localMode

    fun positionFor(path: String): FeedPosition? = positions[path]

    fun cachedChapter(path: String, chapter: Int): EbookChapter? =
        chapters[path]?.get(chapter)
            ?: parsedBooks[path]?.chapters?.getOrNull(chapter)?.also { putChapter(path, chapter, it) }

    /** 已解析整本书的原始目录（本地模式有层级；远程 feed 只有平铺章节名）。 */
    fun tocFor(path: String): List<com.aurora.music.data.ebook.EbookTocEntry>? =
        parsedBooks[path]?.toc?.takeIf { it.isNotEmpty() }

    /** 阅读页每次落定后调用；不触发状态发布，避免翻页时反复重组整个阅读器。 */
    fun rememberPosition(path: String, chapter: Int, block: Int, char: Int) {
        if (path.isBlank()) return
        val p = FeedPosition(
            chapter = chapter.coerceAtLeast(0),
            block = block.coerceAtLeast(0),
            char = char.coerceAtLeast(0),
        )
        positions[path] = p
        val cur = rawCurrent
        if (cur != null && cur.path == path) {
            rawCurrent = cur.copy(
                chapterIndex = p.chapter,
                chapterTitle = cur.chapterTitles.getOrElse(p.chapter) { cur.chapterTitle },
                blockIndex = p.block,
                charOffset = p.char,
            )
        }
    }

    suspend fun start(
        deviceId: String,
        rootKey: String,
        poolPaths: List<String>,
        resume: EbookFeedResume?,
        includeImages: Boolean,
        cacheSize: Int = 20,
    ): Boolean = withContext(Dispatchers.IO) {
        localPrefetchJob?.cancel()
        localPrefetchJob = null
        localSession++
        localMode = false
        localPool = emptyList()
        synchronized(localLock) {
            localSeen.clear()
            localQueue.clear()
        }
        remoteQueueJob?.cancel()
        remoteQueueJob = null
        remoteRetryJob?.cancel()
        remoteRetryJob = null
        synchronized(remoteLock) { remoteQueue.clear() }
        remoteCacheSize = cacheSize.coerceIn(5, 50)
        var base = remoteLibrary.onlineBaseUrl(deviceId)
        var pool = poolPaths.ifEmpty { remoteLibrary.ebookPoolPaths(deviceId, rootKey) }
        // 冷启动恢复阅读器时设备发现可能还没就绪：给它几秒，别让用户先看到失败。
        // 池子本身由桌面端按 rootKey 取，本地列表只用于校验 resume，不再强制等待。
        var waited = 0
        while (base == null && waited < 20) {
            kotlinx.coroutines.delay(300)
            waited++
            base = remoteLibrary.onlineBaseUrl(deviceId)
            if (pool.isEmpty()) pool = poolPaths.ifEmpty { remoteLibrary.ebookPoolPaths(deviceId, rootKey) }
        }
        if (base == null) {
            fail(deviceId, rootKey, "设备离线，无法开始随机阅读")
            return@withContext false
        }
        _state.value = RandomFeedState(deviceId, rootKey, loading = true)
        history.clear()
        forward.clear()
        positions.clear()
        chapters.clear()
        parsedBooks.clear()
        expectedChapters.clear()
        localMismatch.clear()
        val resumeWire = resume
            ?.takeIf { it.deviceId == deviceId && it.rootKey == rootKey }
            ?.let {
                FeedResumeWire(path = it.path, chapter = it.chapter, block = it.block, char = it.char)
            }
        // 服务端本来就有书库索引：只发 rootKey，让桌面端自己取池。
        // 10 万本书全部塞进 body 会直接超过 axum 的 2 MiB 上限（HTTP 413）。
        val startUrl = "$base/library/ebook/feed/start"
        var reply = postJsonReply(
            startUrl,
            gson.toJson(FeedStartBody(rootKey, emptyList(), includeImages, resumeWire)),
        ).getOrElse { err ->
            fail(deviceId, rootKey, "连不上桌面端：${err.message ?: err.javaClass.simpleName}")
            return@withContext false
        }
        // 兼容没更新的桌面端：老服务端仍要求 paths，返回 empty pool 或 413 时
        // 退回到一个有上限的随机样本，避免手机先升级时随机阅读完全不可用。
        if (needsLegacyPool(reply)) {
            val sample = pool.shuffled().take(LEGACY_POOL_SAMPLE)
            reply = postJsonReply(
                startUrl,
                gson.toJson(FeedStartBody(rootKey, sample, includeImages, resumeWire)),
            ).getOrElse { err ->
                fail(deviceId, rootKey, "连不上桌面端：${err.message ?: err.javaClass.simpleName}")
                return@withContext false
            }
        }
        if (reply.status !in 200..299) {
            // 另一台 Android 设备的资料库只有 /library/all，没有 feed 接口（404/405）：
            // 退回到“整本下载 + 本地解析”的随机阅读，行为与本地书一致。
            if (reply.status == 404 || reply.status == 405) {
                val wholePool = pool.ifEmpty { remoteLibrary.ebookPoolPaths(deviceId, rootKey) }
                if (wholePool.isNotEmpty()) {
                    return@withContext startLocal(
                        key = "remote:$deviceId:$rootKey",
                        poolPaths = wholePool,
                        resume = null,
                        downloadDeviceId = deviceId,
                    )
                }
            }
            fail(deviceId, rootKey, "启动失败：${replyError(reply)}")
            return@withContext false
        }
        val snapshot = runCatching {
            gson.fromJson(reply.body, FeedSnapshotWire::class.java)
        }.getOrNull()
        if (snapshot == null || snapshot.books.isEmpty()) {
            fail(deviceId, rootKey, "启动失败：服务端返回的数据不完整")
            return@withContext false
        }
        applySnapshot(deviceId, rootKey, snapshot, resetHistory = true)
        // 首屏三本里除了当前和“下一本”，第三本也放进本地队列，别浪费。
        val extra = snapshot.books.map { it.toBook() }
            .filter { it.path.isNotBlank() && it.path != rawCurrent?.path && it.path != serverNext?.path }
        synchronized(remoteLock) {
            extra.forEach { book ->
                if (remoteQueue.none { it.path == book.path }) {
                    remoteQueue.addLast(book)
                    expectedChapters[book.path] = book.chapterCount
                }
            }
        }
        // 首屏先给界面，其余预缓存后台补；失败不影响已经拿到的三本。
        remoteQueueJob = remoteScope.launch { fillRemoteQueue() }
        true
    }

    /**
     * 本地 / 设备间随机阅读：整本取到本地解析，随机停在章/段/字。
     *
     * [downloadDeviceId] 非空表示路径属于远程设备，需要先整本下载；为空表示路径已是本地文件。
     * [prefetch] 会在后台继续解析若干本，切书时直接用内存里的结果，避免卡顿。
     */
    suspend fun startLocal(
        key: String,
        poolPaths: List<String>,
        resume: EbookFeedResume?,
        downloadDeviceId: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        val pool = poolPaths.filter { it.isNotBlank() }.distinct()
        if (pool.isEmpty()) {
            fail("local", key, "这个集合里没有可阅读的电子书")
            return@withContext false
        }
        localPrefetchJob?.cancel()
        localPrefetchJob = null
        localSession++
        localMode = true
        remoteQueueJob?.cancel()
        remoteQueueJob = null
        remoteRetryJob?.cancel()
        remoteRetryJob = null
        synchronized(remoteLock) { remoteQueue.clear() }
        localDownloadDeviceId = downloadDeviceId
        localPool = pool
        synchronized(localLock) {
            localSeen.clear()
            localQueue.clear()
        }
        history.clear()
        forward.clear()
        positions.clear()
        chapters.clear()
        parsedBooks.clear()
        expectedChapters.clear()
        localMismatch.clear()
        localFiles.clear()
        wholeBookJobs.clear()
        rawCurrent = null
        serverPrevious = null
        serverNext = null
        _state.value = RandomFeedState(deviceId = "local", rootKey = key, loading = true)

        val preferred = resume?.takeIf {
            it.deviceId == "local" && it.rootKey == key && it.path in pool
        }
        val firstPath = preferred?.path ?: pool.random()
        val first = loadLocalBook(firstPath, preferred)
        if (first == null) {
            // 首选书解析失败时从池子里再试几本，别因为一本坏书就整组不可用。
            var fallback: RandomFeedBook? = null
            for (i in 0 until minOf(pool.size, 8)) {
                val p = pool.random()
                fallback = loadLocalBook(p, null)
                if (fallback != null) break
            }
            if (fallback == null) {
                fail("local", key, "这些电子书都打不开")
                return@withContext false
            }
            rawCurrent = fallback
        } else {
            rawCurrent = first
        }
        synchronized(localLock) { localSeen.add(rawCurrent?.path.orEmpty()) }
        publishLocal()
        // 首本先给界面，其余几本在后台解析；切书时优先用已解析结果。
        val session = localSession
        localPrefetchJob = localScope.launch { prefetchLocal(LOCAL_PREFETCH, session) }
        true
    }

    private suspend fun loadLocalBook(
        path: String,
        resume: com.aurora.music.data.ebook.EbookFeedResume?,
    ): RandomFeedBook? {
        val parsed = parsedBooks[path] ?: run {
            val file = if (File(path).isFile) File(path) else downloadToCache(localDownloadDeviceId, path)
                ?: return null
            val p = ebookStore.openBook(file.absolutePath, registerIfMissing = false) ?: return null
            parsedBooks[path] = p
            _chapterVersion.value = _chapterVersion.value + 1
            p
        }
        val chapters = parsed.chapters
        if (chapters.isEmpty()) return null
        val chapter = resume?.chapter?.coerceIn(0, chapters.size - 1)
            ?: kotlin.random.Random.nextInt(chapters.size)
        val blocks = chapters[chapter].blocks
        val block = resume?.block?.coerceIn(0, (blocks.size - 1).coerceAtLeast(0))
            ?: if (blocks.isEmpty()) 0 else kotlin.random.Random.nextInt(blocks.size)
        val char = resume?.char?.coerceAtLeast(0) ?: 0
        return RandomFeedBook(
            path = path,
            title = parsed.title,
            author = parsed.author,
            chapterCount = chapters.size,
            chapterTitles = chapters.map { it.title },
            chapterIndex = chapter,
            chapterTitle = chapters[chapter].title,
            blockIndex = block,
            charOffset = char,
        )
    }

    private suspend fun prefetchLocal(target: Int, session: Int) {
        var attempts = 0
        while (true) {
            if (session != localSession) return
            if (synchronized(localLock) { localQueue.size } >= target) return
            if (attempts >= localPool.size + 2) return
            val path = synchronized(localLock) { nextLocalPathLocked() } ?: return
            val book = loadLocalBook(path, null)
            if (session != localSession) return
            if (book == null) {
                attempts++
                continue
            }
            synchronized(localLock) {
                if (localQueue.none { it.path == book.path }) localQueue.addLast(book)
            }
            publishLocal()
        }
    }

    /** 池子未抽完前不重复；抽完后重置一轮，只在当前书之外继续随机。 */
    private fun nextLocalPathLocked(): String? {
        if (localSeen.size >= localPool.size) {
            localSeen.clear()
            _state.value.current?.path?.let { localSeen.add(it) }
        }
        val candidates = localPool.filter { it !in localSeen }
        if (candidates.isEmpty()) return null
        val path = candidates.random()
        localSeen.add(path)
        return path
    }

    private fun publishLocal() {
        val current = rawCurrent
        _state.value = _state.value.copy(
            current = current?.let(::resolve),
            previous = synchronized(localLock) { history.lastOrNull() }?.let(::resolve),
            next = synchronized(localLock) { localQueue.firstOrNull() }?.let(::resolve),
            loading = false,
            error = null,
            canNext = true,
        )
    }

    private suspend fun localTurn(direction: Int): Boolean = withContext(Dispatchers.IO) {
        val current = rawCurrent ?: return@withContext false
        if (direction < 0) {
            val previous = synchronized(localLock) {
                history.removeLastOrNull()?.also { forward.addLast(current.copy()) }
            } ?: return@withContext false
            rawCurrent = previous
            publishLocal()
            return@withContext true
        }
        var next = synchronized(localLock) {
            forward.removeLastOrNull() ?: localQueue.removeFirstOrNull()
        }
        if (next == null) {
            for (attempt in 0 until minOf(localPool.size, 8)) {
                val path = synchronized(localLock) { nextLocalPathLocked() } ?: break
                next = loadLocalBook(path, null)
                if (next != null) break
            }
            if (next == null) return@withContext false
        }
        synchronized(localLock) {
            history.addLast(current.copy())
            while (history.size > 10) history.removeFirst()
        }
        rawCurrent = next
        publishLocal()
        localPrefetchJob?.cancel()
        val session = localSession
        localPrefetchJob = localScope.launch { prefetchLocal(LOCAL_PREFETCH, session) }
        true
    }

    suspend fun turn(direction: Int): Boolean = withContext(Dispatchers.IO) {
        if (direction == 0) return@withContext true
        if (localMode) return@withContext localTurn(direction)
        val st = _state.value
        if (st.current == null) return@withContext false
        // 先回本地历史/预缓存：来回翻书不再打服务端，也不消耗随机窗口。
        if (direction < 0) {
            val prev = synchronized(remoteLock) {
                history.removeLastOrNull() ?: serverPrevious?.also { serverPrevious = null }
            }
            if (prev != null) {
                synchronized(remoteLock) {
                    rawCurrent?.let { forward.addLast(it) }
                    rawCurrent = prev
                    serverPrevious = history.lastOrNull()
                    serverNext = forward.lastOrNull()
                }
                publish(loading = false)
                return@withContext true
            }
        } else {
            val next = synchronized(remoteLock) { takeNextLocked() }
            if (next != null) {
                synchronized(remoteLock) {
                    rawCurrent?.let { pushHistory(it) }
                    rawCurrent = next
                    serverPrevious = history.lastOrNull()
                    serverNext = forward.lastOrNull()
                }
                publish(loading = false)
                maybeRefill()
                return@withContext true
            }
            // 本地预缓存空了：先向 relay 要一批，再决定是否真的没书了。
            updateState { it.copy(loading = true, error = null) }
            fillRemoteQueue()
            val fetched = synchronized(remoteLock) { takeNextLocked() }
            if (fetched != null) {
                synchronized(remoteLock) {
                    rawCurrent?.let { pushHistory(it) }
                    rawCurrent = fetched
                    serverPrevious = history.lastOrNull()
                    serverNext = forward.lastOrNull()
                }
                publish(loading = false)
                return@withContext true
            }
        }
        // 老桌面端没有 feed/batch：退回原来的服务端 turn。
        serverTurn(direction)
    }

    /** 服务端 turn 兜底：老桌面端或本地没有上一本时使用。 */
    private suspend fun serverTurn(direction: Int): Boolean = withContext(Dispatchers.IO) {
        val st = _state.value
        if (st.current == null) return@withContext false
        val base = remoteLibrary.onlineBaseUrl(st.deviceId)
        if (base == null) {
            updateState { it.copy(loading = false, error = "设备离线，无法继续切换") }
            if (direction > 0) disableNext()
            return@withContext false
        }
        updateState { it.copy(loading = true, error = null) }
        val reply = postJsonReply(
            "$base/library/ebook/feed/turn",
            gson.toJson(FeedTurnBody(st.sessionId, direction)),
            turnClient,
        ).getOrElse { err ->
            updateState {
                it.copy(
                    loading = false,
                    error = "切换失败：连不上桌面端（${err.message ?: err.javaClass.simpleName}）",
                )
            }
            if (direction > 0) disableNext()
            return@withContext false
        }
        if (reply.status !in 200..299) {
            updateState { it.copy(loading = false, error = "切换失败：${replyError(reply)}") }
            if (direction > 0) disableNext()
            return@withContext false
        }
        val snapshot = runCatching { gson.fromJson(reply.body, FeedSnapshotWire::class.java) }.getOrNull()
        if (snapshot == null || snapshot.books.isEmpty()) {
            updateState { it.copy(loading = false, error = "切换失败：服务端返回的数据不完整") }
            if (direction > 0) disableNext()
            return@withContext false
        }
        synchronized(remoteLock) {
            if (direction < 0) {
                st.current?.let { pushForward(it) }
            } else {
                st.current?.let { pushHistory(it) }
            }
        }
        applySnapshot(st.deviceId, st.rootKey, snapshot, resetHistory = false)
        true
    }

    private fun disableNext() {
        updateState { it.copy(canNext = false) }
        scheduleRetry()
    }

    /**
     * 向 relay 的预缓存队列要一批书，补到 [remoteCacheSize]。
     * 返回实际新增的本数；网络失败/队列为空返回 0。
     */
    private suspend fun fillRemoteQueue(): Int = withContext(Dispatchers.IO) {
        if (localMode) return@withContext 0
        val (need, sessionId, deviceId) = synchronized(remoteLock) {
            Triple(
                remoteCacheSize - remoteQueue.size,
                _state.value.sessionId,
                _state.value.deviceId,
            )
        }
        if (need <= 0 || sessionId.isBlank()) return@withContext 0
        val base = remoteLibrary.onlineBaseUrl(deviceId) ?: return@withContext 0
        val rootKey = synchronized(remoteLock) { _state.value.rootKey }
        // 局域网直连桌面端时 batch 要现场解析一批书，给足时间；
        // 走 relay 时 NAS 端有预取队列，用短超时快速失败。
        val http = if (deviceId in remoteLibrary.relayDevices.value) turnClient else client
        val reply = postJsonReply(
            "$base/library/ebook/feed/batch",
            gson.toJson(FeedBatchBody(rootKey = rootKey, count = need)),
            http,
        ).getOrElse { return@withContext 0 }
        if (reply.status !in 200..299) return@withContext 0
        val snapshot = runCatching { gson.fromJson(reply.body, FeedSnapshotWire::class.java) }
            .getOrNull() ?: return@withContext 0
        var added = 0
        synchronized(remoteLock) {
            if (snapshot.sessionId.isNotBlank()) {
                _state.value = _state.value.copy(sessionId = snapshot.sessionId)
            }
            val known = HashSet<String>()
            rawCurrent?.path?.let { known.add(it) }
            history.forEach { known.add(it.path) }
            forward.forEach { known.add(it.path) }
            remoteQueue.forEach { known.add(it.path) }
            serverNext?.path?.let { known.add(it) }
            snapshot.books.forEach { wire ->
                val book = wire.toBook()
                if (book.path.isNotBlank() && known.add(book.path)) {
                    remoteQueue.addLast(book)
                    expectedChapters[book.path] = book.chapterCount
                    seedChapter(book, wire)
                    added++
                }
            }
            if (added > 0) {
                _state.value = _state.value.copy(canNext = true)
            }
        }
        if (added > 0) publish(loading = false)
        added
    }

    /** 预缓存低于一半时后台补满；不阻塞当前切书。 */
    private fun maybeRefill() {
        if (localMode) return
        val size = synchronized(remoteLock) { remoteQueue.size }
        if (size >= remoteCacheSize / 2) return
        if (remoteQueueJob?.isActive == true) return
        remoteQueueJob = remoteScope.launch { fillRemoteQueue() }
    }

    /** 一本都不剩时每 3 秒重试一次，relay 补货后自动恢复上滑。 */
    private fun scheduleRetry() {
        if (remoteRetryJob?.isActive == true) return
        remoteRetryJob = remoteScope.launch {
            while (isActive && !_state.value.canNext && _state.value.sessionId.isNotBlank()) {
                delay(3_000)
                if (fillRemoteQueue() > 0) break
            }
        }
    }

    /**
     * 取一章正文：先查内存/已解析整本，再向服务端要。
     * 服务端只对属于当前会话的书给正文，历史书也在池内，因此来回翻也能补章。
     */
    suspend fun loadChapter(path: String, chapter: Int): EbookChapter? = withContext(Dispatchers.IO) {
        cachedChapter(path, chapter)?.let { return@withContext it }
        val st = _state.value
        if (st.sessionId.isBlank()) return@withContext null
        val base = remoteLibrary.onlineBaseUrl(st.deviceId) ?: return@withContext null
        val url = "$base/library/ebook/feed/chapter?sessionId=${enc(st.sessionId)}" +
            "&path=${enc(path)}&chapter=$chapter"
        val text = getJson(url) ?: return@withContext null
        val resp = runCatching { gson.fromJson(text, FeedChapterResponseWire::class.java) }.getOrNull()
            ?: return@withContext null
        val wire = resp.chapter
        val ch = EbookChapter(
            title = wire.title,
            blocks = wire.blocks.map { EbookBlock(text = it.text, level = it.level) },
        )
        putChapter(path, chapter, ch)
        ch
    }

    /** 章节边界：静默下载整本并解析，之后本章后续章节不再走网络。 */
    suspend fun cacheWholeBook(deviceId: String, path: String) = withContext(Dispatchers.IO) {
        // 本地/设备间模式一开始就整本下载并解析，不需要再逐章补。
        if (localMode) return@withContext
        if (path.isBlank() || parsedBooks.containsKey(path)) return@withContext
        if (localMismatch.contains(path)) return@withContext
        if (!wholeBookJobs.add(path)) return@withContext
        try {
            val file = downloadToCache(deviceId, path) ?: return@withContext
            val parsed = ebookStore.openBook(file.absolutePath, registerIfMissing = false)
                ?: return@withContext
            // 服务端与手机是两套 EPUB 解析器（linear="no"/空页处理可能不同）：
            // 章节数对不上就继续走逐章接口，绝不能把错位的正文当成第 N 章。
            val expected = expectedChapters[path]
            if (expected != null && expected != parsed.chapters.size) {
                localMismatch.add(path)
                return@withContext
            }
            parsedBooks[path] = parsed
            // 一次性铺进章节缓存，避免 UI 在组合里逐章回填（组合期间写状态会多一轮重组）。
            val map = chapters.getOrPut(path) { ConcurrentHashMap() }
            parsed.chapters.forEachIndexed { i, ch -> map[i] = ch }
            _chapterVersion.value = _chapterVersion.value + 1
        } finally {
            wholeBookJobs.remove(path)
        }
    }

    /** 底栏“入库”：把当前随机书保存到默认书架（成为普通本地书），并带上当前阅读位置。 */
    suspend fun saveToShelf(
        deviceId: String,
        book: RandomFeedBook,
        chapter: Int,
        block: Int,
        char: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        // 本地/设备间的书本来就在本机或来源设备里，不需要“入库”。
        if (localMode) return@withContext true
        val file = localFiles[book.path]?.takeIf { it.isFile } ?: downloadToCache(deviceId, book.path)
        if (file == null) return@withContext false
        val name = book.path.substringAfterLast('/').ifBlank { "book.epub" }
        val imported = ebookStore.importShelfFile(file, name) ?: return@withContext false
        // 入库后把随机阅读的当前位置带过去：下次从书架打开就是同一章/段/字。
        runCatching {
            ebookStore.saveTtsPosSync(
                imported.path,
                chapter.coerceAtLeast(0),
                block.coerceAtLeast(0),
                char.coerceAtLeast(0),
            )
        }
        true
    }

    fun close() {
        localPrefetchJob?.cancel()
        localPrefetchJob = null
        localSession++
        localMode = false
        localDownloadDeviceId = ""
        localPool = emptyList()
        synchronized(localLock) {
            localSeen.clear()
            localQueue.clear()
        }
        remoteQueueJob?.cancel()
        remoteQueueJob = null
        remoteRetryJob?.cancel()
        remoteRetryJob = null
        synchronized(remoteLock) { remoteQueue.clear() }
        val st = _state.value
        val base = remoteLibrary.onlineBaseUrl(st.deviceId)
        val sid = st.sessionId
        _state.value = RandomFeedState()
        rawCurrent = null
        serverPrevious = null
        serverNext = null
        history.clear()
        forward.clear()
        positions.clear()
        chapters.clear()
        parsedBooks.clear()
        expectedChapters.clear()
        localMismatch.clear()
        localFiles.clear()
        wholeBookJobs.clear()
        if (base != null && sid.isNotBlank()) {
            // 主动释放服务端窗口；失败无妨，服务端会自动过期。
            Thread {
                runCatching {
                    postJson("$base/library/ebook/feed/close", gson.toJson(FeedCloseBody(sid)))
                }
            }.start()
        }
    }

    // ---- 内部 ----

    private fun applySnapshot(
        deviceId: String,
        rootKey: String,
        snapshot: FeedSnapshotWire,
        resetHistory: Boolean,
    ) {
        synchronized(remoteLock) {
            if (resetHistory) history.clear()
            val index = snapshot.index.coerceIn(0, (snapshot.books.size - 1).coerceAtLeast(0))
            snapshot.books.forEach { expectedChapters[it.path] = it.chapterCount }
            val adjusted = snapshot.books.map { it.toBook() }
            // 窗口里每本书的当前章快照都先收下：邻居页据此显示“下一本”的正文预览，
            // 切过去时也已经有第一屏可渲染。
            adjusted.zip(snapshot.books).forEach { (book, wire) -> seedChapter(book, wire) }
            rawCurrent = adjusted.getOrNull(index) ?: adjusted.first()
            serverPrevious = adjusted.getOrNull(index - 1)
            serverNext = adjusted.getOrNull(index + 1)
            _state.value = RandomFeedState(
                deviceId = deviceId,
                rootKey = rootKey,
                sessionId = snapshot.sessionId,
                current = rawCurrent?.let(::resolve),
                previous = (history.lastOrNull() ?: serverPrevious)?.let(::resolve),
                next = (forward.lastOrNull() ?: serverNext ?: remoteQueue.firstOrNull())?.let(::resolve),
                loading = false,
                error = null,
                canNext = true,
            )
        }
    }

    private fun updateState(transform: (RandomFeedState) -> RandomFeedState) {
        synchronized(remoteLock) { _state.value = transform(_state.value) }
    }

    private fun publish(loading: Boolean) {
        synchronized(remoteLock) {
            val st = _state.value
            val next = forward.lastOrNull() ?: serverNext ?: remoteQueue.firstOrNull()
            _state.value = st.copy(
                current = rawCurrent?.let(::resolve),
                previous = (history.lastOrNull() ?: serverPrevious)?.let(::resolve),
                next = next?.let(::resolve),
                loading = loading,
                error = null,
                canNext = st.canNext || next != null,
            )
        }
    }

    private fun fail(deviceId: String, rootKey: String, message: String) {
        _state.value = RandomFeedState(
            deviceId = deviceId,
            rootKey = rootKey,
            loading = false,
            error = message,
            canNext = false,
        )
    }

    private fun resolve(base: RandomFeedBook): RandomFeedBook {
        val p = positions[base.path] ?: return base
        return base.copy(
            chapterIndex = p.chapter.coerceIn(0, (base.chapterCount - 1).coerceAtLeast(0)),
            chapterTitle = base.chapterTitles.getOrElse(p.chapter) { base.chapterTitle },
            blockIndex = p.block,
            charOffset = p.char,
        )
    }

    /**
     * 取下一本：先回前进栈，再取服务端窗口的下一本，最后才用本地预缓存。
     * 服务端窗口的下一本可能也在预缓存里，取走时一并去重。
     */
    private fun takeNextLocked(): RandomFeedBook? {
        forward.removeLastOrNull()?.let { return it }
        serverNext?.let { next ->
            serverNext = null
            remoteQueue.removeAll { it.path == next.path }
            return next
        }
        return remoteQueue.removeFirstOrNull()
    }

    private fun pushHistory(book: RandomFeedBook) {
        history.removeAll { it.path == book.path }
        history.addLast(book.copy())
        while (history.size > 10) history.removeFirst()
    }

    private fun pushForward(book: RandomFeedBook) {
        forward.removeAll { it.path == book.path }
        forward.addLast(book.copy())
        while (forward.size > 10) forward.removeFirst()
    }

    private fun seedChapter(book: RandomFeedBook, wire: FeedBookWire?) {
        val ch = wire?.chapter ?: return
        if (ch.blocks.isEmpty()) return
        putChapter(
            book.path,
            wire.chapterIndex,
            EbookChapter(ch.title, ch.blocks.map { EbookBlock(it.text, it.level) }),
        )
    }

    private fun putChapter(path: String, chapter: Int, value: EbookChapter) {
        chapters.getOrPut(path) { ConcurrentHashMap() }[chapter] = value
        _chapterVersion.value = _chapterVersion.value + 1
    }

    private suspend fun downloadToCache(deviceId: String, path: String): File? {
        val dest = feedFile(path)
        if (dest.isFile && dest.length() > 0) {
            localFiles[path] = dest
            return dest
        }
        val entry = RemoteEbookEntry(
            deviceId = deviceId,
            deviceName = "",
            remotePath = path,
            title = "",
            author = "",
            coverUrl = "",
            size = 0L,
        )
        val tmp = remoteLibrary.fetchEbookFile(entry) ?: return null
        return runCatching {
            dest.parentFile?.mkdirs()
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            localFiles[path] = dest
            dest
        }.getOrElse {
            runCatching { tmp.delete() }
            null
        }
    }

    private fun feedFile(path: String): File {
        val dir = File(appContext.cacheDir, "feed_books")
        val name = sha1(path) + "." + path.substringAfterLast('.', "epub").lowercase()
        return File(dir, name)
    }

    private fun sha1(value: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(value.hashCode().toString())

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class HttpReply(val status: Int, val body: String)

    /** 保留状态码与响应体：失败时把服务端 `{"error": "..."}` 原样带回给界面。 */
    private fun postJsonReply(
        url: String,
        body: String,
        http: OkHttpClient = client,
    ): Result<HttpReply> = runCatching {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            HttpReply(r.code, r.body?.string().orEmpty())
        }
    }

    /** 老服务端要求 paths：空池 400 或 body 超限 413 时改用有上限的样本重试。 */
    private fun needsLegacyPool(reply: HttpReply): Boolean =
        reply.status == 413 || (reply.status == 400 && reply.body.contains("random pool is empty"))

    private fun replyError(reply: HttpReply): String {
        val message = runCatching {
            com.google.gson.JsonParser.parseString(reply.body)
                .asJsonObject.get("error")?.asString
        }.getOrNull()
        val reason = if (!message.isNullOrBlank()) {
            message
        } else {
            val body = reply.body.trim()
            body.takeIf { it.isNotEmpty() && it.length <= 200 } ?: "HTTP ${reply.status}"
        }
        // 附带状态码，方便区分“服务端拒绝”和“代理/中继没通”。
        return if (reply.status in 200..299) reason else "$reason（HTTP ${reply.status}）"
    }

    private fun postJson(url: String, body: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) text else null
        }
    }.getOrNull()

    private fun getJson(url: String): String? = runCatching {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) text else null
        }
    }.getOrNull()

    private fun FeedBookWire.toBook(): RandomFeedBook = RandomFeedBook(
        path = path,
        title = title,
        author = author,
        chapterCount = chapterCount,
        chapterTitles = chapterTitles,
        chapterIndex = chapterIndex.coerceAtLeast(0),
        chapterTitle = chapterTitle,
        blockIndex = blockIndex.coerceAtLeast(0),
        charOffset = charOffset.coerceAtLeast(0),
    )
}
