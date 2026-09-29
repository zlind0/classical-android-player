package com.aurora.music.data

import android.content.Context
import com.aurora.music.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用存储巡检：递归统计 filesDir / cacheDir / databases / 外部私有目录，
 * 精确到每个文件。IO 线程跑，大库（万级文件）也是一两秒的事。
 *
 * 每级目录只保留最大的 [MAX_CHILDREN] 个孩子，其余计入 [StorageNode.omitted]，
 * UI 按此展开，既能定位大头，又不会把 Coil 万级小文件全铺出来。
 */
data class StorageNode(
    val name: String,
    /** app 数据根下的相对路径（如 files/ebook_cache/<md5>），展开状态的唯一键。 */
    val relPath: String,
    val isDir: Boolean,
    /** 递归总字节。 */
    val bytes: Long,
    val children: List<StorageNode> = emptyList(),
    /** 超出上限被省略的孩子数。 */
    val omitted: Int = 0,
    /** 递归文件总数。 */
    val files: Int = 0,
    /** 已知目录/文件的友好文案（null = 直接显示文件名）。 */
    val labelRes: Int? = null,
    /** labelRes 带一个 %1$s 参数时用它（如 md5 前 8 位、语音 code）。 */
    val labelArg: String? = null,
)

data class StorageReport(
    val roots: List<StorageNode>,
    val totalBytes: Long,
    val scannedMs: Long,
) {
    fun root(name: String): StorageNode? = roots.firstOrNull { it.name == name }
}

const val STORAGE_MAX_CHILDREN = 80

suspend fun scanAppStorage(context: Context): StorageReport = withContext(Dispatchers.IO) {
    val t0 = android.os.SystemClock.elapsedRealtime()
    val appContext = context.applicationContext
    val roots = mutableListOf<StorageNode>()
    walkRoot(appContext.filesDir, "files")?.let { roots += it }
    walkRoot(appContext.cacheDir, "cache")?.let { roots += it }
    // databases/ 与 filesDir 同级，不在 filesDir 里，单独走
    runCatching { appContext.getDatabasePath("__probe__").parentFile }
        .getOrNull()?.takeIf { it.exists() }?.let { walkRoot(it, "databases") }?.let { roots += it }
    runCatching { appContext.codeCacheDir }.getOrNull()
        ?.takeIf { it.exists() }?.let { walkRoot(it, "code_cache") }?.let { roots += it }
    // 外部私有目录（/storage/emulated/0/Android/data/<pkg>/…），一般是空的，有就列出来
    runCatching { appContext.getExternalFilesDirs(null).toList() }.getOrNull().orEmpty()
        .filterNotNull().filter { it.exists() }
        .forEachIndexed { i, dir -> walkRoot(dir, if (i == 0) "external/files" else "external/files-$i")?.let { roots += it } }
    runCatching { appContext.getExternalCacheDirs().toList() }.getOrNull().orEmpty()
        .filterNotNull().filter { it.exists() }
        .forEachIndexed { i, dir -> walkRoot(dir, if (i == 0) "external/cache" else "external/cache-$i")?.let { roots += it } }
    StorageReport(roots, roots.sumOf { it.bytes }, android.os.SystemClock.elapsedRealtime() - t0)
}

private fun walkRoot(dir: File, rel: String): StorageNode? {
    if (!dir.exists()) return null
    return walk(dir, rel)
}

private fun walk(file: File, rel: String): StorageNode {
    if (file.isFile) {
        return StorageNode(file.name, rel, false, runCatching { file.length() }.getOrDefault(0L), files = 1)
    }
    val kids = runCatching { file.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        // 跳过符号链接，防循环
        .filter { kid -> runCatching { !java.nio.file.Files.isSymbolicLink(kid.toPath()) }.getOrDefault(true) }
    if (kids.isEmpty()) return StorageNode(file.name, rel, true, 0L)
    val sub = kids.map { walk(it, "$rel/${it.name}") }.sortedByDescending { it.bytes }
    val shown = sub.take(STORAGE_MAX_CHILDREN)
    return StorageNode(
        name = file.name,
        relPath = rel,
        isDir = true,
        bytes = sub.sumOf { it.bytes },
        children = shown,
        omitted = (sub.size - shown.size).coerceAtLeast(0),
        files = sub.sumOf { it.files },
        labelRes = dirLabel(rel),
        labelArg = dirLabelArg(rel),
    )
}

/** 已知目录的中文化（纯展示，扫出来的字节数不受影响）。 */
private fun dirLabel(rel: String): Int? {
    val seg = rel.split("/")
    if (seg.size == 1) return when (seg[0]) {
        "files" -> R.string.storage_root_files
        "cache" -> R.string.storage_root_cache
        "databases" -> R.string.storage_root_databases
        "code_cache" -> R.string.storage_root_code_cache
        else -> null
    }
    if (seg[0] == "external") return R.string.storage_root_external
    if (seg[0] != "files" && seg[0] != "cache") return null
    val sub = seg.drop(1)
    return when {
        seg[0] == "files" && sub == listOf("voice") -> R.string.storage_dir_voice
        seg[0] == "files" && sub.size == 3 && sub[0] == "voice" && sub[1] == "microsoft" ->
            R.string.storage_voice_fmt
        seg[0] == "files" && sub == listOf("ebook_cache") -> R.string.storage_dir_ebook_cache
        seg[0] == "files" && sub.size == 2 && sub[0] == "ebook_cache" -> R.string.storage_ebook_md5_fmt
        seg[0] == "files" && sub == listOf("ebook_covers") -> R.string.storage_dir_covers
        seg[0] == "files" && sub.size >= 1 && sub[0] == "ebooks" -> R.string.storage_dir_shelf
        seg[0] == "files" && sub == listOf("downloads") -> R.string.storage_dir_downloads
        seg[0] == "files" && sub == listOf("fonts") -> R.string.storage_dir_fonts
        seg[0] == "cache" && sub == listOf("track_art") -> R.string.storage_dir_track_art
        seg[0] == "cache" && sub == listOf("track_art", "c") -> R.string.storage_dir_track_art_images
        seg[0] == "cache" && sub == listOf("track_art", "s") -> R.string.storage_dir_track_art_index
        seg[0] == "cache" && sub == listOf("image_cache") -> R.string.storage_dir_image_cache
        else -> fileLabel(sub.lastOrNull().orEmpty())
    }
}

private fun dirLabelArg(rel: String): String? {
    val seg = rel.split("/")
    if (seg.size == 4 && seg[0] == "files" && seg[1] == "voice" && seg[2] == "microsoft") return seg[3]
    if (seg.size == 3 && seg[0] == "files" && seg[1] == "ebook_cache") return seg[2].take(8)
    return null
}

/** 已知单文件的说明（落到文件节点上，UI 直接显示）。 */
fun fileLabelRes(name: String): Int? = fileLabel(name)

private fun fileLabel(name: String): Int? = when {
    name == "parsed_v2.json" -> R.string.storage_file_parsed
    name.startsWith("pages_") && name.endsWith(".json") -> R.string.storage_file_pages
    name == ".installed" -> R.string.storage_file_installed
    else -> null
}
