package com.aurora.music.data.audiobook

import com.aurora.music.data.StorageType

// 有声书：一本书 = 用户所选根目录下的一个直接子目录；
// 书内文件（含二级目录如 CD1）递归收平，按 trackNumber + 完整 path 自然序排成章节。

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
) {
    val displayTitle: String get() = title.ifBlank {
        path.substringAfterLast('/').substringBeforeLast('.')
    }
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
    val currentIndex: Int get() = chapters.indexOfFirst { it.path == currentPath }.takeIf { it >= 0 } ?: 0
    val progressPct: Float get() {
        if (chapters.isEmpty()) return 0f
        val done = chapters.take(currentIndex).sumOf { it.durationSec }
        val cur = chapters.getOrNull(currentIndex)?.durationSec?.takeIf { it > 0 } ?: 0
        val total = chapters.sumOf { it.durationSec }.takeIf { it > 0 } ?: return 0f
        return ((done + (positionMs / 1000f).coerceIn(0f, cur.toFloat())) / total).coerceIn(0f, 1f)
    }
}

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
