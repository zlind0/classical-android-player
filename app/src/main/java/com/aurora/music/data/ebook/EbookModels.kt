package com.aurora.music.data.ebook

import com.aurora.music.data.StorageType

// 电子书功能数据模型（v1：阅读 + 目录 + 设置，TTS/音乐后续版本）。

enum class EbookFormat { EPUB, MOBI, AZW, AZW3, UNKNOWN }

val EBOOK_EXTENSIONS: Set<String> = setOf("epub", "mobi", "azw", "azw3")

fun ebookFormatOf(path: String): EbookFormat = when (path.substringAfterLast('.', "").lowercase()) {
    "epub" -> EbookFormat.EPUB
    "mobi" -> EbookFormat.MOBI
    "azw" -> EbookFormat.AZW
    "azw3" -> EbookFormat.AZW3
    else -> EbookFormat.UNKNOWN
}

fun isEbookFile(name: String): Boolean =
    EBOOK_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())

data class EbookRoot(
    val id: Long,
    val rootPath: String,
    val displayName: String,
    val storageType: StorageType,
    val enabled: Boolean = true,
    val lastScanTime: Long = 0L,
)

/** 默认书架在库里用这个虚拟 rootId 标记，实际文件在应用私有目录 ebooks/shelf/ 下。 */
const val EBOOK_SHELF_ROOT_ID = -1L

const val EBOOK_SHELF_DIR_NAME = "shelf"

data class EbookBook(
    val path: String,
    val title: String,
    val author: String,
    val coverPath: String = "",
    val md5: String = "",
    val size: Long = 0L,
    /** null/默认书架 = 私有书架；否则为扫描库 rootId */
    val rootId: Long? = null,
    val inShelf: Boolean = false,
    // 阅读进度
    val spineIndex: Int = 0,
    val pageIndex: Int = 0,
    val progressPct: Float = 0f,
    /** 0 = 从未读过；最近阅读按它倒序 */
    val lastReadAt: Long = 0L,
) {
    val displayTitle: String get() = title.ifBlank { path.substringAfterLast('/').substringBeforeLast('.') }
}

// ---- 解析产物（进磁盘缓存，二次打开直接读） ----

/** 正文块：level 0 = 正文，1..6 = h1..h6 */
data class EbookBlock(val text: String, val level: Int = 0)

data class EbookChapter(val title: String, val blocks: List<EbookBlock>)

data class EbookTocEntry(
    val chapterIndex: Int,
    val blockIndex: Int,
    val title: String,
    val level: Int,
)

data class ParsedEbook(
    val title: String,
    val author: String,
    val coverPath: String = "",
    val chapters: List<EbookChapter> = emptyList(),
    val toc: List<EbookTocEntry> = emptyList(),
) {
    val totalChars: Int get() = chapters.sumOf { c -> c.blocks.sumOf { it.text.length } }
}

// ---- 阅读设置 ----

enum class EbookTheme { WHITE, SEPIA, DARK }

data class EbookReadPrefs(
    val theme: EbookTheme = EbookTheme.WHITE,
    val fontSizeSp: Float = 18f,
    /** 自定义 ttf/otf 绝对路径，空 = 系统字体 */
    val fontPath: String = "",
)

data class EbookScanProgress(
    val running: Boolean = false,
    val rootId: Long = 0L,
    val found: Int = 0,
    val total: Int = 0,
    val current: String = "",
)
