package com.aurora.music.data

import android.content.Context
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.aurora.music.data.cast.RandomFeedManager
import com.aurora.music.data.cast.RemoteLibraryManager
import com.aurora.music.data.cast.RemoteMediaCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全应用缓存分类。每一类都可以独立配置容量上限（LRU 淘汰）、过期周期（TTL）与手动清理。
 */
enum class CacheCategory(
    val id: String,
    val defaultMaxMb: Int,
    val defaultMaxAgeDays: Int,
) {
    /** 远程音乐 / 有声书边播边缓存（Media3 SimpleCache）。 */
    REMOTE_MEDIA("remote_media", defaultMaxMb = 1024, defaultMaxAgeDays = 14),

    /** 远程设备资料库 SQLite 快照、远程封面与中继快照。 */
    REMOTE_LIBRARY("remote_library", defaultMaxMb = 256, defaultMaxAgeDays = 30),

    /** 远程服务器上打开但未显式下载的电子书临时文件。 */
    REMOTE_EBOOKS("remote_ebooks", defaultMaxMb = 256, defaultMaxAgeDays = 30),

    /** 随机阅读（发现流）预取/打开的电子书缓存。 */
    RANDOM_FEED("random_feed", defaultMaxMb = 128, defaultMaxAgeDays = 7),

    /** 本地/远程电子书全文解析与分页断点缓存、书籍封面。 */
    EBOOK_PARSE("ebook_parse", defaultMaxMb = 128, defaultMaxAgeDays = 30),

    /** 图片磁盘缓存（Coil）与音频内嵌封面去重缓存。 */
    IMAGE_AND_ART("image_art", defaultMaxMb = 512, defaultMaxAgeDays = 30),

    /** AirPlay 投屏接收到的曲目封面图片。 */
    AIRPLAY_ART("airplay_art", defaultMaxMb = 64, defaultMaxAgeDays = 7),

    /** 有声书离线字幕 JSON 与 AI 翻译相关缓存。 */
    SUBTITLES_AND_AI("subtitles_ai", defaultMaxMb = 256, defaultMaxAgeDays = 30),

    /**
     * LiteRT-LM 编译缓存（GPU program / weight cache）。
     *
     * 它是可再生的派生数据，但重建一次要几十秒甚至更久，所以给一份比字幕大得多的预算，
     * 且位置跟着「大模型存储」走（见 [ModelStorage.litertCacheDir]）。
     */
    LLM_COMPILE("llm_compile", defaultMaxMb = 1024, defaultMaxAgeDays = 30),

    /** 导入/导出/TTS 合成产生的临时文件。 */
    TEMP_FILES("temp_files", defaultMaxMb = 128, defaultMaxAgeDays = 1),
    ;

    fun defaultPolicy(): CachePolicy = CachePolicy(defaultMaxMb, defaultMaxAgeDays)
}

/**
 * 单个缓存分类的策略。
 * - [maxMb] == 0 表示不限制大小；
 * - [maxAgeDays] == 0 表示永不过期。
 */
data class CachePolicy(
    val maxMb: Int,
    val maxAgeDays: Int,
) {
    val maxBytes: Long get() = if (maxMb <= 0) 0L else maxMb.toLong() * 1024L * 1024L
    val maxAgeMs: Long get() = if (maxAgeDays <= 0) 0L else maxAgeDays.toLong() * 24L * 60L * 60L * 1000L
}

data class CacheStats(
    val category: CacheCategory,
    val bytes: Long,
    val itemCount: Int,
)

/**
 * 纯文件系统 LRU + TTL 淘汰工具，便于单元测试。
 */
internal object CachePruner {

    data class Entry(
        val file: File,
        val bytes: Long,
        val lastModified: Long,
    )

    fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return runCatching { file.length() }.getOrDefault(0L)
        return file.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
    }

    fun fileCount(file: File): Int {
        if (!file.exists()) return 0
        if (file.isFile) return 1
        return file.walkTopDown().count { it.isFile }
    }

    /**
     * 先按 [maxAgeMs] 清理过期条目，再按 [lastModified] 从旧到新（LRU）淘汰直到总大小 <= [maxBytes]。
     */
    fun pruneEntries(
        entries: List<Entry>,
        maxBytes: Long,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        deleteEntry: (File) -> Unit = { f ->
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        },
    ): Long {
        if (entries.isEmpty()) return 0L
        val remaining = mutableListOf<Entry>()
        val cutoff = if (maxAgeMs > 0L) nowMs - maxAgeMs else Long.MIN_VALUE

        for (e in entries) {
            if (maxAgeMs > 0L && e.lastModified < cutoff) {
                runCatching { deleteEntry(e.file) }
            } else {
                remaining += e
            }
        }

        var total = remaining.sumOf { it.bytes }
        if (maxBytes > 0L && total > maxBytes) {
            val byOldest = remaining.sortedBy { it.lastModified }
            for (e in byOldest) {
                if (total <= maxBytes) break
                runCatching { deleteEntry(e.file) }
                total -= e.bytes
            }
        }
        return total.coerceAtLeast(0L)
    }

    fun pruneFilesInDir(
        dir: File,
        maxBytes: Long,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        filter: (File) -> Boolean = { it.isFile },
    ) {
        if (!dir.isDirectory) return
        val entries = dir.walkTopDown()
            .filter { it.isFile && filter(it) }
            .map { Entry(it, runCatching { it.length() }.getOrDefault(0L), it.lastModified()) }
            .toList()
        pruneEntries(entries, maxBytes, maxAgeMs, nowMs)
    }

    fun pruneSubdirs(
        parentDir: File,
        maxBytes: Long,
        maxAgeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!parentDir.isDirectory) return
        val kids = parentDir.listFiles()?.toList().orEmpty()
        val entries = kids.map { kid ->
            val mtime = if (kid.isDirectory) {
                kid.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: kid.lastModified()
            } else {
                kid.lastModified()
            }
            Entry(kid, dirSize(kid), mtime)
        }
        pruneEntries(entries, maxBytes, maxAgeMs, nowMs)
    }
}

/**
 * 统一管理 App 内所有可清理/可限额缓存的统计、手动清理与 LRU/TTL 淘汰。
 */
class CacheManager(
    private val context: Context,
    private val remoteLibrary: RemoteLibraryManager,
    private val randomFeed: RandomFeedManager,
) {
    private val appContext = context.applicationContext

    suspend fun measure(category: CacheCategory): CacheStats = withContext(Dispatchers.IO) {
        measureSync(category)
    }

    suspend fun measureAll(): Map<CacheCategory, CacheStats> = withContext(Dispatchers.IO) {
        CacheCategory.entries.associateWith { measureSync(it) }
    }

    suspend fun clear(category: CacheCategory) = withContext(Dispatchers.IO) {
        clearSync(category)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        CacheCategory.entries.forEach { clearSync(it) }
    }

    suspend fun enforce(category: CacheCategory, policy: CachePolicy) = withContext(Dispatchers.IO) {
        enforceSync(category, policy)
    }

    suspend fun enforceAll(policies: Map<CacheCategory, CachePolicy>) = withContext(Dispatchers.IO) {
        CacheCategory.entries.forEach { cat ->
            val policy = policies[cat] ?: cat.defaultPolicy()
            enforceSync(cat, policy)
        }
    }

    private fun measureSync(category: CacheCategory): CacheStats {
        var bytes = 0L
        var count = 0
        when (category) {
            CacheCategory.REMOTE_MEDIA -> {
                val dir = File(appContext.cacheDir, "remote_media")
                bytes = CachePruner.dirSize(dir)
                count = CachePruner.fileCount(dir)
            }
            CacheCategory.REMOTE_LIBRARY -> {
                val libDir = File(appContext.filesDir, "remote_library")
                val relayDir = File(appContext.filesDir, "relay_cache")
                bytes = CachePruner.dirSize(libDir) + CachePruner.dirSize(relayDir)
                count = CachePruner.fileCount(libDir) + CachePruner.fileCount(relayDir)
            }
            CacheCategory.REMOTE_EBOOKS -> {
                val dir = File(appContext.filesDir, "remote_ebooks")
                val json = File(appContext.filesDir, "remote_random_books.json")
                bytes = CachePruner.dirSize(dir) + if (json.isFile) json.length() else 0L
                count = CachePruner.fileCount(dir)
            }
            CacheCategory.RANDOM_FEED -> {
                val dir = File(appContext.cacheDir, "feed_books")
                bytes = CachePruner.dirSize(dir)
                count = CachePruner.fileCount(dir)
            }
            CacheCategory.EBOOK_PARSE -> {
                val parseDir = File(appContext.filesDir, "ebook_cache")
                val coverDir = File(appContext.filesDir, "ebook_covers")
                bytes = CachePruner.dirSize(parseDir) + CachePruner.dirSize(coverDir)
                count = CachePruner.fileCount(parseDir) + CachePruner.fileCount(coverDir)
            }
            CacheCategory.IMAGE_AND_ART -> {
                val imgDir = File(appContext.cacheDir, "image_cache")
                val artDir = File(appContext.cacheDir, "track_art")
                bytes = CachePruner.dirSize(imgDir) + CachePruner.dirSize(artDir)
                count = CachePruner.fileCount(imgDir) + CachePruner.fileCount(artDir)
            }
            CacheCategory.AIRPLAY_ART -> {
                val dir = File(appContext.filesDir, "cast_art")
                bytes = CachePruner.dirSize(dir)
                count = CachePruner.fileCount(dir)
            }
            CacheCategory.SUBTITLES_AND_AI -> {
                val subDir = File(appContext.filesDir, "subtitles")
                bytes = CachePruner.dirSize(subDir)
                count = CachePruner.fileCount(subDir)
            }
            CacheCategory.LLM_COMPILE -> {
                litertCacheDirs().forEach { dir ->
                    bytes += CachePruner.dirSize(dir)
                    count += CachePruner.fileCount(dir)
                }
            }
            CacheCategory.TEMP_FILES -> {
                val temps = tempFilesInCacheDir()
                bytes = temps.sumOf { CachePruner.dirSize(it) }
                count = temps.sumOf { CachePruner.fileCount(it) }
            }
        }
        return CacheStats(category, bytes, count)
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun clearSync(category: CacheCategory) {
        when (category) {
            CacheCategory.REMOTE_MEDIA -> RemoteMediaCache.clearAll()
            CacheCategory.REMOTE_LIBRARY -> remoteLibrary.clearLibraryCache()
            CacheCategory.REMOTE_EBOOKS -> remoteLibrary.clearRemoteEbooks()
            CacheCategory.RANDOM_FEED -> randomFeed.clearCache()
            CacheCategory.EBOOK_PARSE -> {
                runCatching { File(appContext.filesDir, "ebook_cache").deleteRecursively() }
                runCatching { File(appContext.filesDir, "ebook_covers").deleteRecursively() }
            }
            CacheCategory.IMAGE_AND_ART -> {
                runCatching {
                    val loader = appContext.imageLoader
                    loader.diskCache?.clear()
                    loader.memoryCache?.clear()
                }
                runCatching { File(appContext.cacheDir, "image_cache").deleteRecursively() }
                runCatching { File(appContext.cacheDir, "track_art").deleteRecursively() }
                TrackArtworkCache.invalidateAll()
            }
            CacheCategory.AIRPLAY_ART -> {
                runCatching { File(appContext.filesDir, "cast_art").deleteRecursively() }
            }
            CacheCategory.SUBTITLES_AND_AI -> {
                runCatching { File(appContext.filesDir, "subtitles").deleteRecursively() }
                runCatching { File(appContext.cacheDir, "llm-slices").deleteRecursively() }
            }
            CacheCategory.LLM_COMPILE -> {
                litertCacheDirs().forEach { dir ->
                    runCatching { dir.deleteRecursively() }
                }
            }
            CacheCategory.TEMP_FILES -> {
                tempFilesInCacheDir().forEach { f ->
                    runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
                }
            }
        }
    }

    private fun enforceSync(category: CacheCategory, policy: CachePolicy) {
        val maxBytes = policy.maxBytes
        val maxAgeMs = policy.maxAgeMs
        if (maxBytes <= 0L && maxAgeMs <= 0L) return
        val now = System.currentTimeMillis()
        when (category) {
            CacheCategory.REMOTE_MEDIA -> RemoteMediaCache.trim(maxBytes, maxAgeMs, now)
            CacheCategory.REMOTE_LIBRARY -> remoteLibrary.pruneWithPolicy(maxBytes, maxAgeMs, now)
            CacheCategory.REMOTE_EBOOKS -> remoteLibrary.pruneRemoteEbooks(maxBytes, maxAgeMs, now)
            CacheCategory.RANDOM_FEED -> {
                val dir = File(appContext.cacheDir, "feed_books")
                CachePruner.pruneFilesInDir(dir, maxBytes, maxAgeMs, now)
            }
            CacheCategory.EBOOK_PARSE -> {
                val parseDir = File(appContext.filesDir, "ebook_cache")
                val coverDir = File(appContext.filesDir, "ebook_covers")
                val parseBytes = CachePruner.dirSize(parseDir)
                val coverBytes = CachePruner.dirSize(coverDir)
                val parseBudget = if (maxBytes > 0L) (maxBytes - coverBytes).coerceAtLeast(maxBytes / 2) else 0L
                CachePruner.pruneSubdirs(parseDir, parseBudget, maxAgeMs, now)
                val remainingBudget = if (maxBytes > 0L) (maxBytes - CachePruner.dirSize(parseDir)).coerceAtLeast(0L) else 0L
                CachePruner.pruneFilesInDir(coverDir, remainingBudget, maxAgeMs, now)
            }
            CacheCategory.IMAGE_AND_ART -> {
                val artDir = File(appContext.cacheDir, "track_art/c")
                val imgDir = File(appContext.cacheDir, "image_cache")
                val artBudget = if (maxBytes > 0L) maxBytes / 2 else 0L
                CachePruner.pruneFilesInDir(artDir, artBudget, maxAgeMs, now)
                // Coil DiskCache 自身已按 maxSizeBytes 做 LRU，这里补做 TTL 清理
                if (maxAgeMs > 0L) {
                    CachePruner.pruneFilesInDir(imgDir, 0L, maxAgeMs, now) {
                        it.isFile && !it.name.equals("journal", ignoreCase = true)
                    }
                }
            }
            CacheCategory.AIRPLAY_ART -> {
                val dir = File(appContext.filesDir, "cast_art")
                CachePruner.pruneFilesInDir(dir, maxBytes, maxAgeMs, now)
            }
            CacheCategory.SUBTITLES_AND_AI -> {
                val subDir = File(appContext.filesDir, "subtitles")
                // 切片已改为纯内存传递，顺手清理历史版本遗留的磁盘切片。
                runCatching { File(appContext.cacheDir, "llm-slices").deleteRecursively() }
                CachePruner.pruneFilesInDir(subDir, maxBytes, maxAgeMs, now)
            }
            CacheCategory.LLM_COMPILE -> {
                litertCacheDirs().forEach { dir ->
                    CachePruner.pruneFilesInDir(dir, maxBytes, maxAgeMs, now)
                }
            }
            CacheCategory.TEMP_FILES -> {
                val entries = tempFilesInCacheDir().map { f ->
                    CachePruner.Entry(f, CachePruner.dirSize(f), f.lastModified())
                }
                CachePruner.pruneEntries(entries, maxBytes, maxAgeMs, now)
            }
        }
    }

    /**
     * 编译缓存的候选目录：当前生效位置 + 应用缓存里的历史位置。
     * 切换存储目录时旧缓存会被删掉，但这里一并统计/清理，避免残留占空间。
     */
    private fun litertCacheDirs(): List<File> {
        val active = ModelStorageConfig.litertCacheDir(appContext)
        val legacy = ModelStorageConfig.appLitertCacheDir(appContext)
        return if (active.absolutePath == legacy.absolutePath) listOf(active) else listOf(active, legacy)
    }

    private fun tempFilesInCacheDir(): List<File> {
        val root = appContext.cacheDir
        if (!root.isDirectory) return emptyList()
        return root.listFiles()?.filter { f ->
            val n = f.name
            n.startsWith("shelf_import_") ||
                n.startsWith("ebook_import") ||
                n.startsWith("ebook_export_") ||
                n.startsWith("ebookutt") ||
                n.startsWith("ebookseg") ||
                n.startsWith("tts") ||
                n.startsWith("tagedit_in.")
        }.orEmpty()
    }
}
