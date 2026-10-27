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
    /** 朗读/阅读锚点：块下标 + 块内字偏移（句子级恢复用） */
    val blockIndex: Int = 0,
    val charOffset: Int = 0,
    /** 进度唯一编码：章:段（显示/跳转用，见 EbookAnchorCode）。 */
    val anchorCode: String = "",
) {
    val displayTitle: String get() = title.ifBlank { path.substringAfterLast('/').substringBeforeLast('.') }
}

// ---- 解析产物（进磁盘缓存，二次打开直接读） ----

/** 正文块：level 0 = 正文，1..6 = h1..h6；links 为块内超链接（字符区间，块坐标） */
data class EbookBlock(
    val text: String,
    val level: Int = 0,
    val links: List<EbookLink> = emptyList(),
)

/**
 * 已解析的超链接：chapter >= 0 = 站内跳转（章/块下标，与目录跳转同一套）；
 * chapter = -1 = 外部链接，看 url（仅 http/https 会被打开）。
 */
data class EbookLink(
    val start: Int,
    val end: Int,
    val chapter: Int,
    val block: Int,
    val url: String = "",
)

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

/** 翻页一次滚动的距离：一屏 = 整个可视宽度（含同屏所有栏），一栏 = 只前进一栏。 */
enum class EbookCastTurnUnit { SCREEN, COLUMN }

/** 投屏画面（网页 / HDMI）专用设置：与手机阅读页分开，遥控时即时生效并持久保存。 */
data class EbookCastDisplayPrefs(
    val theme: EbookTheme = EbookTheme.WHITE,
    /** 网页投屏字号（px），16..64 */
    val webFontSizePx: Int = 32,
    /** 网页投屏一栏的最大宽度（px）；按可用宽度自动决定分几栏 */
    val webMaxColumnWidthPx: Int = 640,
    /** 网页投屏是否在画面上显示音乐播放条 */
    val webShowMusic: Boolean = true,
    /** 网页投屏翻页一次滚多少：一屏 / 一栏 */
    val webTurnUnit: EbookCastTurnUnit = EbookCastTurnUnit.SCREEN,
    /** 网页投屏时音乐是否由网页出声（false = 手机本地播放） */
    val webMusicOnCast: Boolean = true,
    /** HDMI 投屏字号（px），16..64 */
    val hdmiFontSizePx: Int = 32,
    /** HDMI 投屏一栏的最大宽度（px）；按可用宽度自动决定分几栏 */
    val hdmiMaxColumnWidthPx: Int = 640,
    /** HDMI 投屏是否在画面上显示音乐播放条 */
    val hdmiShowMusic: Boolean = true,
    /** HDMI 投屏翻页一次滚多少：一屏 / 一栏 */
    val hdmiTurnUnit: EbookCastTurnUnit = EbookCastTurnUnit.SCREEN,
    /** HDMI 投屏时音乐是否由 HDMI 设备出声（false = 手机本地播放） */
    val hdmiMusicOnCast: Boolean = false,
) {
    /** 按投屏方式取字号：网页和 HDMI 各自独立保存（屏幕尺寸差很多）。 */
    fun fontSizePx(web: Boolean): Int = if (web) webFontSizePx else hdmiFontSizePx

    /** 按投屏方式取一栏最大宽度。 */
    fun maxColumnWidthPx(web: Boolean): Int = if (web) webMaxColumnWidthPx else hdmiMaxColumnWidthPx

    /** 按投屏方式取“是否显示音乐播放条”。 */
    fun showMusic(web: Boolean): Boolean = if (web) webShowMusic else hdmiShowMusic

    /** 按投屏方式取翻页单位。 */
    fun turnUnit(web: Boolean): EbookCastTurnUnit = if (web) webTurnUnit else hdmiTurnUnit

    /** 按投屏方式取“音乐在投屏设备上播”还是“在手机上播”。 */
    fun musicOnCast(web: Boolean): Boolean = if (web) webMusicOnCast else hdmiMusicOnCast
}

const val MIN_CAST_FONT_PX = 16
const val MAX_CAST_FONT_PX = 64
const val MIN_CAST_COLUMN_WIDTH_PX = 240
const val MAX_CAST_COLUMN_WIDTH_PX = 1200

/** 朗读切片单位：PARA = 按段落（<p>块），SENTENCE = 按句子（中英文标点切分）。 */
enum class EbookTtsUnit { PARA, SENTENCE }

data class EbookScanProgress(
    val running: Boolean = false,
    val rootId: Long = 0L,
    val found: Int = 0,
    val total: Int = 0,
    val current: String = "",
)
