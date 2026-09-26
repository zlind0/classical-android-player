package com.aurora.music.data.ebook

import android.content.Context
import android.net.Uri
import com.aurora.music.data.StorageType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 电子书仓库：书库/书架 CRUD、扫描落盘、解析缓存、外部打开导入、阅读进度。
// 解析产物按内容寻址（md5 目录）缓存：内容不变缓存永不失效，二次打开秒开。
class EbookStore(context: Context, private val dao: EbookDao) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()

    private val _roots = MutableStateFlow<List<EbookRoot>>(emptyList())
    val roots: StateFlow<List<EbookRoot>> = _roots.asStateFlow()

    private val _shelf = MutableStateFlow<List<EbookBook>>(emptyList())
    val shelf: StateFlow<List<EbookBook>> = _shelf.asStateFlow()

    private val _recents = MutableStateFlow<List<EbookBook>>(emptyList())
    val recents: StateFlow<List<EbookBook>> = _recents.asStateFlow()

    private val _counts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val counts: StateFlow<Map<Long, Int>> = _counts.asStateFlow()

    val scanProgress = MutableStateFlow(EbookScanProgress())

    /** 外部打开（content/file intent）处理完后这里产生路径，UI 消费后清空。 */
    private val _openRequest = MutableStateFlow<String?>(null)
    val openRequest: StateFlow<String?> = _openRequest.asStateFlow()
    fun takeOpenRequest(): String? {
        val v = _openRequest.value
        _openRequest.value = null
        return v
    }

    /** 导入失败等提示，UI 消费后清空。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun takeNotice(): String? {
        val v = _notice.value
        _notice.value = null
        return v
    }

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    suspend fun refresh() {
        val rows = dao.allRoots()
        _roots.value = rows.map {
            EbookRoot(it.id, it.rootPath, it.displayName, storageTypeOf(it.storageType), it.enabled, it.lastScanTime)
        }
        _shelf.value = dao.shelfBooks().map { it.toBook() }
        _recents.value = dao.recentBooks(5).map { it.toBook() }
        val map = mutableMapOf<Long, Int>()
        map[EBOOK_SHELF_ROOT_ID] = _shelf.value.size
        rows.forEach { r ->
            map[r.id] = dao.booksOfRoot(r.id).size
        }
        _counts.value = map
    }

    // ---- 书库管理 ----

    suspend fun addRoot(path: String, displayName: String, type: StorageType): EbookRoot? =
        withContext(Dispatchers.IO) {
            val norm = runCatching { File(path).canonicalPath }.getOrDefault(path)
            if (dao.allRoots().any {
                    runCatching { File(it.rootPath).canonicalPath }.getOrDefault(it.rootPath) == norm
                }
            ) {
                return@withContext null
            }
            val root = EbookRootRow(
                id = System.currentTimeMillis(),
                rootPath = norm,
                displayName = displayName,
                storageType = type.name,
            )
            dao.insertRoots(listOf(root))
            refresh()
            EbookRoot(root.id, root.rootPath, root.displayName, type)
        }

    suspend fun removeRoot(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteRoot(id)
        dao.deleteBooksOfRoot(id)
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

    suspend fun booksOfRoot(rootId: Long): List<EbookBook> =
        withContext(Dispatchers.IO) { dao.booksOfRoot(rootId).map { it.toBook() } }

    suspend fun bookByPath(path: String): EbookBook? =
        withContext(Dispatchers.IO) { dao.bookByPath(path)?.toBook() }

    // ---- 文件夹浏览（文件系统为准，DB 只补元信息） ----

    data class DirListing(val dirs: List<File>, val books: List<EbookBook>)

    suspend fun listDir(rootPath: String, dir: String): DirListing = withContext(Dispatchers.IO) {
        val d = File(dir)
        val children = d.listFiles()?.toList().orEmpty()
        val dirs = children.filter { it.isDirectory && !it.isHidden && it.canRead() }
            .sortedBy { it.name.lowercase() }
        val files = children.filter { it.isFile && isEbookFile(it.name) }
        val books = files.map { f ->
            dao.bookByPath(f.absolutePath)?.toBook()
                ?: EbookBook(path = f.absolutePath, title = "", author = "", size = f.length())
        }.sortedBy { it.displayTitle.lowercase() }
        DirListing(dirs, books)
    }

    // ---- 打开 / 解析缓存 ----

    private fun cacheDirFor(md5: String): File = File(appContext.filesDir, "ebook_cache/$md5")

    private fun fingerprintOf(file: File): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun fingerprint(file: File): String = fingerprintOf(file)

    private val parsedType = object : TypeToken<ParsedEbook>() {}.type

    /** 打开一本书：缓存命中直接读，否则解析后写缓存。IO 线程调用。 */
    suspend fun openBook(path: String): ParsedEbook? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        val row = dao.bookByPath(path)
        val md5 = row?.md5?.ifBlank { null } ?: fingerprintOf(file).ifBlank { null }
        if (md5 != null) {
            val cached = readParsedCache(md5)
            if (cached != null) return@withContext cached
        }
        val parsed = when (ebookFormatOf(path)) {
            EbookFormat.EPUB -> runCatching { EpubParser.parseFull(file) }.getOrNull()
            EbookFormat.MOBI, EbookFormat.AZW, EbookFormat.AZW3 ->
                runCatching { MobiParser.parseFull(file) }.getOrNull()
            EbookFormat.UNKNOWN -> null
        } ?: return@withContext null
        if (md5 != null) writeParsedCache(md5, parsed)
        // 顺手补全 DB 元信息（文件夹浏览进来的书）
        if (row == null) {
            val cover = extractCover(file, md5 ?: "")
            dao.upsertBooks(
                listOf(
                    EbookRow(
                        path = path, md5 = md5.orEmpty(), size = file.length(),
                        lastModified = file.lastModified(),
                        title = parsed.title, author = parsed.author, coverPath = cover,
                        rootId = rootIdFor(path),
                    ),
                ),
            )
            refresh()
        }
        parsed
    }

    private fun readParsedCache(md5: String): ParsedEbook? = runCatching {
        // v2 起带超链接；v1 无该字段直接废弃重解析（分页断点按块字符区间，与此无关不受影响）
        val f = File(cacheDirFor(md5), "parsed_v2.json")
        if (!f.exists()) return null
        gson.fromJson<ParsedEbook>(f.readText(), parsedType)
    }.getOrNull()

    private fun writeParsedCache(md5: String, parsed: ParsedEbook) {
        runCatching {
            val dir = cacheDirFor(md5)
            dir.mkdirs()
            runCatching { File(dir, "parsed.json").delete() }
            File(dir, "parsed_v2.json").writeText(gson.toJson(parsed))
        }
    }

    fun extractCover(file: File, md5: String): String {
        if (md5.isBlank()) return ""
        val out = File(appContext.filesDir, "ebook_covers/$md5.jpg")
        if (out.exists()) return android.net.Uri.fromFile(out).toString()
        return runCatching {
            val bytes: ByteArray? = when (ebookFormatOf(file.name)) {
                EbookFormat.EPUB -> EpubParser.parseMeta(file).cover
                else -> null
            }
            if (bytes == null || bytes.isEmpty()) return ""
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            android.net.Uri.fromFile(out).toString()
        }.getOrDefault("")
    }

    // ---- 分页断点缓存 ----

    fun pageCacheKey(prefs: EbookReadPrefs, widthPx: Int, heightPx: Int): String {
        val fontId = if (prefs.fontPath.isBlank()) "sys" else fingerprintOf(File(prefs.fontPath)).take(8)
        return "${prefs.fontSizeSp.toInt()}x$fontId@${widthPx}x$heightPx"
    }

    /** 每章Labor：List<章节> → List<页> → List<块切片[block,start,end]> */
    fun loadPageBreaks(md5: String, key: String): List<List<List<Int>>>? {
        if (md5.isBlank()) return null
        return runCatching {
            val f = File(cacheDirFor(md5), "pages_${key.replace('/', '_')}.json")
            if (!f.exists()) return null
            val t = object : TypeToken<List<List<List<Int>>>>() {}.type
            gson.fromJson<List<List<List<Int>>>>(f.readText(), t)
        }.getOrNull()
    }

    fun savePageBreaks(md5: String, key: String, breaks: List<List<List<Int>>>) {
        if (md5.isBlank()) return
        runCatching {
            val dir = cacheDirFor(md5)
            dir.mkdirs()
            File(dir, "pages_${key.replace('/', '_')}.json").writeText(gson.toJson(breaks))
        }
    }

    suspend fun md5Of(path: String): String = runCatching {
        bookByPath(path)?.md5?.ifBlank { null } ?: fingerprintOf(File(path))
    }.getOrDefault("")

    // ---- 外部打开导入（默认书架，md5+size 去重） ----

    fun handleOpenUri(uri: Uri, displayName: String?) {
        scope.launch(Dispatchers.IO) {
            val row = runCatching { importContentUri(uri, displayName) }.getOrNull()
            if (row == null) {
                _notice.value = "无法打开这个电子书文件"
            } else {
                refresh()
                _openRequest.value = row.path
            }
        }
    }

    private suspend fun importContentUri(uri: Uri, displayName: String?): EbookRow? {
        val name = (displayName?.ifBlank { null }
            ?: uri.lastPathSegment?.substringAfterLast('/'))?.ifBlank { null }
            ?: "book.epub"
        if (!isEbookFile(name)) return null
        val shelfDir = File(appContext.filesDir, "ebooks/$EBOOK_SHELF_DIR_NAME")
        shelfDir.mkdirs()
        // 先拷临时文件再算 md5（content:// 只能读一次流就地算也行，这里简单起见落盘）
        val tmp = File.createTempFile("ebook_import", ".tmp", appContext.cacheDir)
        try {
            appContext.contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { out -> ins.copyTo(out) }
            } ?: return null
            val size = tmp.length()
            if (size <= 0) return null
            val md5 = fingerprintOf(tmp)
            if (md5.isBlank()) return null
            // 同一文件（md5+大小）只存一份：直接打开第一份拷贝
            dao.shelfByFingerprint(md5, size)?.let { return it }
            val safe = safeFileName(name)
            var dest = File(shelfDir, safe)
            var i = 1
            while (dest.exists()) {
                dest = File(shelfDir, safe.substringBeforeLast('.') + " ($i)." + safe.substringAfterLast('.', "epub"))
                i++
            }
            tmp.copyTo(dest, overwrite = true)
            val meta = when (ebookFormatOf(dest.name)) {
                EbookFormat.EPUB -> EpubParser.parseMeta(dest).let { it.title to it.author }
                else -> MobiParser.parseMeta(dest).let { it.title to it.author }
            }
            val cover = extractCover(dest, md5)
            val row = EbookRow(
                path = dest.absolutePath, md5 = md5, size = size,
                lastModified = dest.lastModified(),
                title = meta.first.ifBlank { dest.nameWithoutExtension },
                author = meta.second, coverPath = cover,
                rootId = EBOOK_SHELF_ROOT_ID, inShelf = true,
            )
            dao.upsertBooks(listOf(row))
            return row
        } finally {
            runCatching { tmp.delete() }
        }
    }

    // ---- 进度 ----

    fun saveProgress(path: String, spine: Int, page: Int, pct: Float) {
        scope.launch(Dispatchers.IO) {
            dao.saveProgress(path, spine, page, pct, System.currentTimeMillis())
            _recents.value = dao.recentBooks(5).map { it.toBook() }
        }
    }

    suspend fun deleteShelf(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.forEach { p ->
            runCatching { File(p).delete() }
            val md5 = dao.bookByPath(p)?.md5
            if (!md5.isNullOrBlank()) {
                runCatching { File(appContext.filesDir, "ebook_cache/$md5").deleteRecursively() }
                runCatching { File(appContext.filesDir, "ebook_covers/$md5.jpg").delete() }
            }
        }
        dao.deleteBooks(paths)
        refresh()
    }

    // ---- 内部 ----

    private suspend fun rootIdFor(path: String): Long {
        val roots = dao.allRoots()
        val match = roots.filter { it.enabled }
            .firstOrNull { path == it.rootPath || path.startsWith(it.rootPath.trimEnd('/') + "/") }
        return match?.id ?: EBOOK_SHELF_ROOT_ID
    }

    private fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "book.epub" }
        return if (isEbookFile(clean)) clean else "$clean.epub"
    }

    private fun EbookRow.toBook() = EbookBook(
        path = path, title = title, author = author, coverPath = coverPath,
        md5 = md5, size = size,
        rootId = if (inShelf) null else rootId,
        inShelf = inShelf,
        spineIndex = spineIndex, pageIndex = pageIndex,
        progressPct = progressPct, lastReadAt = lastReadAt,
    )

    private fun storageTypeOf(name: String): StorageType = runCatching {
        StorageType.valueOf(name)
    }.getOrDefault(StorageType.INTERNAL)
}
