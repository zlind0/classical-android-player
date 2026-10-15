package com.aurora.music.data.audiobook

import android.content.Context
import com.aurora.music.data.StorageType
import com.aurora.music.data.naturalStringOrder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 有声书仓库：扫描根 CRUD、书/章节组装（含关键字过滤）、每书记忆。
// 章节排序：trackNumber 优先（缺失沉底），其次完整 path 自然序。
class AudiobookStore(context: Context, private val dao: AudiobookDao) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _roots = MutableStateFlow<List<AudiobookRoot>>(emptyList())
    val roots: StateFlow<List<AudiobookRoot>> = _roots.asStateFlow()

    private val _books = MutableStateFlow<List<AudiobookBook>>(emptyList())
    val books: StateFlow<List<AudiobookBook>> = _books.asStateFlow()

    private val _recents = MutableStateFlow<List<AudiobookBook>>(emptyList())
    val recents: StateFlow<List<AudiobookBook>> = _recents.asStateFlow()

    private val _counts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val counts: StateFlow<Map<Long, Int>> = _counts.asStateFlow()

    val scanProgress = MutableStateFlow(AudiobookScanProgress())

    @Volatile var skipKeywords: Set<String> = emptySet()

    // 进度写串行化：savePos/saveSpeed/saveGain 并发时 check-then-act 会互相覆盖。
    private val progressMutex = Mutex()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    suspend fun refresh() {
        val rows = dao.allRoots()
        _roots.value = rows.map {
            AudiobookRoot(
                it.id, it.rootPath, it.displayName,
                runCatching { StorageType.valueOf(it.storageType) }.getOrDefault(StorageType.INTERNAL),
                it.enabled, it.lastScanTime,
            )
        }
        rebuildBooks()
    }

    /** 关键字变化后重组（不碰 DB，只重过滤）。 */
    suspend fun setSkipKeywords(keywords: Set<String>) {
        skipKeywords = keywords
        withContext(Dispatchers.IO) { rebuildBooks() }
    }

    private suspend fun rebuildBooks() {
        val enabledRoots = _roots.value.filter { it.enabled }
        val out = mutableListOf<AudiobookBook>()
        val countMap = mutableMapOf<Long, Int>()
        for (r in _roots.value) {
            val rows = dao.chaptersOfRoot(r.id)
            countMap[r.id] = rows.map { it.bookId }.toSet().size
        }
        for (r in enabledRoots) {
            val rows = dao.chaptersOfRoot(r.id)
            val byBook = rows.groupBy { it.bookId }
            for ((bookId, rs) in byBook) {
                val sorted = rs.sortedWith { a, b ->
                    val ta = if (a.trackNumber == 0) Int.MAX_VALUE else a.trackNumber
                    val tb = if (b.trackNumber == 0) Int.MAX_VALUE else b.trackNumber
                    if (ta != tb) ta.compareTo(tb) else naturalStringOrder.compare(a.path, b.path)
                }
                val total = sorted.map {
                    AudiobookChapter(it.path, it.title, it.trackNumber, it.durationSec, it.size, it.lastModified)
                }
                val skipped = total.count { chapterSkipped(it.displayTitle, skipKeywords) }
                val kept = total.filterNot { chapterSkipped(it.displayTitle, skipKeywords) }
                if (kept.isEmpty()) continue
                val prog = dao.progressOf(bookId)
                out.add(
                    AudiobookBook(
                        id = bookId,
                        title = File(bookId).name,
                        rootId = r.id,
                        chapters = kept,
                        coverPath = bookCover(bookId),
                        currentPath = prog?.currentPath.orEmpty(),
                        positionMs = prog?.positionMs ?: 0L,
                        speed = prog?.speed ?: 1f,
                        gain = prog?.gain ?: 1f,
                        lastPlayedAt = prog?.lastPlayedAt ?: 0L,
                        skippedCount = skipped,
                    ),
                )
            }
        }
        out.sortBy { it.displayTitle.lowercase() }
        _books.value = out
        _counts.value = countMap
        _recents.value = out.filter { it.lastPlayedAt > 0 }.sortedByDescending { it.lastPlayedAt }.take(5)
    }

    suspend fun addRoot(path: String, displayName: String, type: StorageType): AudiobookRoot? =
        withContext(Dispatchers.IO) {
            val norm = runCatching { File(path).canonicalPath }.getOrDefault(path)
            if (dao.allRoots().any {
                    runCatching { File(it.rootPath).canonicalPath }.getOrDefault(it.rootPath) == norm
                }
            ) {
                return@withContext null
            }
            val row = AudiobookRootRow(
                id = System.currentTimeMillis(),
                rootPath = norm,
                displayName = displayName,
                storageType = type.name,
            )
            dao.insertRoots(listOf(row))
            refresh()
            AudiobookRoot(row.id, row.rootPath, row.displayName, type)
        }

    suspend fun removeRoot(id: Long) = withContext(Dispatchers.IO) {
        val bookIds = dao.chaptersOfRoot(id).map { it.bookId }.toSet().toList()
        dao.deleteRoot(id)
        dao.deleteChaptersOfRoot(id)
        if (bookIds.isNotEmpty()) dao.deleteProgress(bookIds)
        refresh()
    }

    suspend fun setRootEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        dao.setRootEnabled(id, enabled)
        refresh()
    }

    suspend fun stampScan(id: Long) = withContext(Dispatchers.IO) {
        dao.stampRoot(id, System.currentTimeMillis())
        refresh()
    }

    suspend fun chaptersOfBookFiltered(bookId: String): List<AudiobookChapter> =
        withContext(Dispatchers.IO) {
            _books.value.firstOrNull { it.id == bookId }?.chapters
                ?: dao.chaptersOfBook(bookId).sortedWith { a, b ->
                    val ta = if (a.trackNumber == 0) Int.MAX_VALUE else a.trackNumber
                    val tb = if (b.trackNumber == 0) Int.MAX_VALUE else b.trackNumber
                    if (ta != tb) ta.compareTo(tb) else naturalStringOrder.compare(a.path, b.path)
                }.map {
                    AudiobookChapter(it.path, it.title, it.trackNumber, it.durationSec, it.size, it.lastModified)
                }.filterNot { chapterSkipped(it.displayTitle, skipKeywords) }
        }

    // ---- 记忆（按 path，不存 index） ----

    fun savePos(bookId: String, path: String, posMs: Long) {
        scope.launch(Dispatchers.IO) {
            progressMutex.withLock {
                dao.upsertPos(bookId, path, posMs.coerceAtLeast(0L), System.currentTimeMillis())
            }
            refreshRecentsOnly()
        }
    }

    suspend fun savePosSync(bookId: String, path: String, posMs: Long) = withContext(Dispatchers.IO) {
        progressMutex.withLock {
            dao.upsertPos(bookId, path, posMs.coerceAtLeast(0L), System.currentTimeMillis())
        }
        refreshRecentsOnly()
    }

    suspend fun saveSpeed(bookId: String, speed: Float) = withContext(Dispatchers.IO) {
        progressMutex.withLock { dao.upsertSpeed(bookId, speed) }
    }

    suspend fun saveGain(bookId: String, gain: Float) = withContext(Dispatchers.IO) {
        progressMutex.withLock { dao.upsertGain(bookId, gain) }
    }

    suspend fun progressOf(bookId: String): AudiobookProgressRow? =
        withContext(Dispatchers.IO) { dao.progressOf(bookId) }

    private suspend fun refreshRecentsOnly() {
        _recents.value = _books.value
            .map { b -> b to dao.progressOf(b.id) }
            .filter { it.second?.lastPlayedAt ?: 0L > 0L }
            .sortedByDescending { it.second!!.lastPlayedAt }
            .take(5)
            .map { (b, p) ->
                b.copy(
                    currentPath = p?.currentPath.orEmpty(),
                    positionMs = p?.positionMs ?: 0L,
                    speed = p?.speed ?: 1f,
                    gain = p?.gain ?: 1f,
                    lastPlayedAt = p?.lastPlayedAt ?: 0L,
                )
            }
    }

    private fun bookCover(bookDir: String): String {
        val jpgs = runCatching {
            File(bookDir).listFiles { f ->
                val n = f.name.lowercase()
                runCatching { f.isFile && f.canRead() }.getOrDefault(false) &&
                    (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
            }?.toList().orEmpty()
        }.getOrDefault(emptyList())
        if (jpgs.isEmpty()) return ""
        val pick = jpgs.sortedBy { it.name.lowercase() }.first()
        return runCatching { android.net.Uri.fromFile(pick).toString() }.getOrDefault("")
    }
}
