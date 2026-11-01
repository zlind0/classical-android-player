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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

data class FeedPosition(val chapter: Int, val block: Int, val char: Int)

private data class FeedStartBody(
    val rootKey: String,
    val paths: List<String>,
    val includeImages: Boolean,
    val resume: FeedResumeWire?,
)

private data class FeedTurnBody(val sessionId: String, val direction: Int)

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

    private var rawCurrent: RandomFeedBook? = null
    private var serverPrevious: RandomFeedBook? = null
    private var serverNext: RandomFeedBook? = null

    fun currentBook(): RandomFeedBook? = rawCurrent?.let(::resolve)

    fun positionFor(path: String): FeedPosition? = positions[path]

    fun cachedChapter(path: String, chapter: Int): EbookChapter? =
        chapters[path]?.get(chapter)
            ?: parsedBooks[path]?.chapters?.getOrNull(chapter)?.also { putChapter(path, chapter, it) }

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
    ): Boolean = withContext(Dispatchers.IO) {
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
            ?.takeIf {
                it.deviceId == deviceId && it.rootKey == rootKey && pool.contains(it.path)
            }
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
        true
    }

    suspend fun turn(direction: Int): Boolean = withContext(Dispatchers.IO) {
        if (direction == 0) return@withContext true
        val st = _state.value
        if (st.current == null) return@withContext false
        // 先回本地历史：来回翻书不再打服务端，也不消耗随机窗口。
        if (direction < 0) {
            val prev = history.removeLastOrNull()
            if (prev != null) {
                st.current?.let { forward.addLast(it) }
                rawCurrent = prev
                serverPrevious = history.lastOrNull()
                serverNext = forward.lastOrNull()
                publish(loading = false)
                return@withContext true
            }
        } else {
            val next = forward.removeLastOrNull()
            if (next != null) {
                st.current?.let { pushHistory(it) }
                rawCurrent = next
                serverPrevious = history.lastOrNull()
                serverNext = forward.lastOrNull()
                publish(loading = false)
                return@withContext true
            }
        }
        val base = remoteLibrary.onlineBaseUrl(st.deviceId)
        if (base == null) {
            _state.value = st.copy(loading = false, error = "设备离线，无法继续切换")
            return@withContext false
        }
        _state.value = st.copy(loading = true, error = null)
        val reply = postJsonReply(
            "$base/library/ebook/feed/turn",
            gson.toJson(FeedTurnBody(st.sessionId, direction)),
        ).getOrElse { err ->
            _state.value = st.copy(
                loading = false,
                error = "切换失败：连不上桌面端（${err.message ?: err.javaClass.simpleName}）",
            )
            return@withContext false
        }
        if (reply.status !in 200..299) {
            _state.value = st.copy(loading = false, error = "切换失败：${replyError(reply)}")
            return@withContext false
        }
        val snapshot = runCatching { gson.fromJson(reply.body, FeedSnapshotWire::class.java) }.getOrNull()
        if (snapshot == null || snapshot.books.isEmpty()) {
            _state.value = st.copy(loading = false, error = "切换失败：服务端返回的数据不完整")
            return@withContext false
        }
        if (direction < 0) {
            st.current?.let { pushForward(it) }
        } else {
            st.current?.let { pushHistory(it) }
        }
        applySnapshot(st.deviceId, st.rootKey, snapshot, resetHistory = false)
        true
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
            next = (forward.lastOrNull() ?: serverNext)?.let(::resolve),
            loading = false,
            error = null,
        )
    }

    private fun publish(loading: Boolean) {
        val st = _state.value
        _state.value = st.copy(
            current = rawCurrent?.let(::resolve),
            previous = (history.lastOrNull() ?: serverPrevious)?.let(::resolve),
            next = (forward.lastOrNull() ?: serverNext)?.let(::resolve),
            loading = loading,
            error = null,
        )
    }

    private fun fail(deviceId: String, rootKey: String, message: String) {
        _state.value = RandomFeedState(
            deviceId = deviceId,
            rootKey = rootKey,
            loading = false,
            error = message,
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
    private fun postJsonReply(url: String, body: String): Result<HttpReply> = runCatching {
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
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
