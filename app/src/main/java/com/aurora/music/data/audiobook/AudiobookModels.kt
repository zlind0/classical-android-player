package com.aurora.music.data.audiobook

import com.aurora.music.data.StorageType

// 有声书：一本书 = 用户所选根目录下的一个直接子目录；
// 书内文件（含二级目录如 CD1）递归收平，按文件分组后组内按文件内起点排序，形成章节。
// m4b/m4a 这类"一个文件装多章"的有声书按内嵌章节轨拆成多章：每章记录文件内起止毫秒，
// 播放时用 MediaItem.ClippingConfiguration 只播该区间。

data class AudiobookRoot(
    val id: Long,
    val rootPath: String,
    val displayName: String,
    val storageType: StorageType,
    val enabled: Boolean = true,
    val lastScanTime: Long = 0L,
)

/** 单章：文件级元信息（扫描时落盘，建队直接用）。 */
data class AudiobookChapter(
    val path: String,
    val title: String,
    val trackNumber: Int = 0,
    val durationSec: Int = 0,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    /** 文件内起点毫秒（m4b 内嵌章节）；普通音频文件恒为 0。 */
    val startMs: Long = 0L,
    /** 文件内终点毫秒（m4b 内嵌章节）；0 = 一直播到文件末尾。 */
    val endMs: Long = 0L,
) {
    val displayTitle: String get() = title.ifBlank {
        path.substringAfterLast('/').substringBeforeLast('.')
    }

    /** 章节身份：同一个 m4b 里的多章共用 path，必须带上文件内起点才能区分。 */
    val key: String get() = abookChapterKey(path, startMs)
}

data class AudiobookBook(
    /** 书目录 canonicalPath */
    val id: String,
    val title: String,
    val rootId: Long,
    val chapters: List<AudiobookChapter> = emptyList(),
    val coverPath: String = "",
    // 记忆（DB）
    /** 当前章节文件的绝对路径（按 path 存，关键字/重扫变化不续错章） */
    val currentPath: String = "",
    /** 当前章节在文件内的起点毫秒（m4b 多章同文件时用来区分章节） */
    val currentStartMs: Long = 0L,
    val positionMs: Long = 0L,
    /** 本书变速（不变调，0.5..4x） */
    val speed: Float = 1f,
    /** 本书增益（0.5..2.0） */
    val gain: Float = 1f,
    val lastPlayedAt: Long = 0L,
    val skippedCount: Int = 0,
) {
    val displayTitle: String get() = title.ifBlank { id.substringAfterLast('/') }
    val chapterCount: Int get() = chapters.size

    val currentIndex: Int get() {
        if (chapters.isEmpty()) return 0
        // 同文件多章：先按 (path, startMs) 精确找回；重扫后章节边界变化时退回该文件的第一章。
        val exact = chapters.indexOfFirst { it.path == currentPath && it.startMs == currentStartMs }
        if (exact >= 0) return exact
        if (currentPath.isNotBlank()) {
            val sameFile = chapters.indexOfFirst { it.path == currentPath }
            if (sameFile >= 0) return sameFile
        }
        return 0
    }

    val progressPct: Float get() {
        if (chapters.isEmpty()) return 0f
        // 读不出时长（total = 0）时按章节索引折算，保证听过的书能显示出进度。
        val total = chapters.sumOf { it.durationSec }.takeIf { it > 0 }
            ?: return currentIndex.toFloat() / chapters.size
        val done = chapters.take(currentIndex).sumOf { it.durationSec }
        val cur = chapters.getOrNull(currentIndex)?.durationSec?.takeIf { it > 0 } ?: 0
        return ((done + (positionMs / 1000f).coerceIn(0f, cur.toFloat())) / total).coerceIn(0f, 1f)
    }
}

/** 章节在库里的唯一键：普通文件 = path#0，m4b 内嵌章节 = path#文件内起点。 */
fun abookChapterKey(path: String, startMs: Long): String = "$path#${startMs.coerceAtLeast(0L)}"

/** 跳过章节关键字命中：章节显示名包含任一关键字（忽略大小写）即剔除。 */
fun chapterSkipped(displayName: String, keywords: Set<String>): Boolean {
    if (keywords.isEmpty()) return false
    val lower = displayName.lowercase()
    return keywords.any { k -> k.isNotBlank() && lower.contains(k.lowercase()) }
}

data class AudiobookScanProgress(
    val running: Boolean = false,
    val rootId: Long = 0L,
    val found: Int = 0,
    val total: Int = 0,
    val current: String = "",
)

const val ABOOK_ID_PREFIX = "audiobook:"

fun abookSongId(bookId: String, index: Int): String = "$ABOOK_ID_PREFIX$bookId#$index"

fun abookBookIdOf(songId: String): String? =
    songId.removePrefix(ABOOK_ID_PREFIX).substringBeforeLast('#', "").ifBlank { null }

fun abookIndexOf(songId: String): Int? =
    songId.substringAfterLast('#', "").toIntOrNull()
