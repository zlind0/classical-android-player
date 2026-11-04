package com.aurora.music.data

// Classical fork v0.3 (plan §3): user-selected scan roots. One root = one directory
// the user explicitly picked; the scanner never leaves these subtrees (plan §6).
enum class StorageType { INTERNAL, SD_CARD, USB }

data class MusicRoot(
    val id: Long,
    val rootPath: String,
    val displayName: String,
    val storageType: StorageType,
    val enabled: Boolean = true,
    val lastScanTime: Long = 0L,
    val mergeTitles: Boolean = true,
)

// One indexed audio file. size+lastModified drive incremental scans (plan §11);
// available=false marks files missing from disk or on unplugged volumes (plan §12-13).
data class ScannedTrack(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val artworkUrl: String = "",
    val available: Boolean = true,
    // audio codec mime sniffed via MediaExtractor (audio/alac vs audio/mp4a-latm)
    val codec: String = "",
    // 文件自带内嵌图（扫描时已提取进 track_art 缓存）；专辑封面优先从有内嵌图的歌里抽。
    // artMd5 = 去重内容文件 track_art/c/<md5>.jpg 的 key（"" = 无内嵌图），随行入库。
    val hasEmbedded: Boolean? = null,
    val artMd5: String = "",
)

data class ScanProgress(
    val rootId: Long = -1L,
    val running: Boolean = false,
    val found: Int = 0,      // audio files seen on disk
    val total: Int = 0,      // known index entries (for determinate display when available)
    val added: Int = 0,
    val updated: Int = 0,
    val missing: Int = 0,
    val current: String = "",
)

val AUDIO_EXTENSIONS = setOf("flac", "mp3", "m4a", "m4b", "aac", "alac", "ogg", "oga", "opus", "wav", "aiff", "aif")

// 扫描过滤：时长小于此值（秒）的曲目直接忽略（含读不到时长的 0）
const val MIN_TRACK_DURATION_SEC = 10

// 未知艺人/专辑的统一归一键与展示名。所有无标签或占位标签的曲目都归到同一个
// key，避免列表里出现成百上千个“Unknown artist / Unknown album”。
const val UNKNOWN_ALBUM_ID = "unknown-album"
const val UNKNOWN_ARTIST_ID = "unknown-artist"
const val UNKNOWN_ALBUM_LABEL = "Unknown album"
const val UNKNOWN_ARTIST_LABEL = "Unknown artist"

/** 标签是否为空或常见的未知占位符（MediaStore/ID3 都可能给 `<unknown>`）。 */
fun isUnknownAlbumTag(value: String?): Boolean {
    val t = value?.trim().orEmpty()
    return t.isEmpty() || t.equals("<unknown>", ignoreCase = true) ||
        t.equals(UNKNOWN_ALBUM_LABEL, ignoreCase = true)
}

fun isUnknownArtistTag(value: String?): Boolean {
    val t = value?.trim().orEmpty()
    return t.isEmpty() || t.equals("<unknown>", ignoreCase = true) ||
        t.equals(UNKNOWN_ARTIST_LABEL, ignoreCase = true)
}

fun albumDisplayName(value: String?): String =
    if (isUnknownAlbumTag(value)) UNKNOWN_ALBUM_LABEL else value!!.trim()

fun artistDisplayName(value: String?): String =
    if (isUnknownArtistTag(value)) UNKNOWN_ARTIST_LABEL else value!!.trim()

/** 旧版无标签曲目按 `unknown-album:<songId>` 拆成独立专辑；用于启动时迁移合并表。 */
fun isLegacyUnknownAlbumKey(key: String): Boolean = key.startsWith("$UNKNOWN_ALBUM_ID::")

fun isAudioFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in AUDIO_EXTENSIONS
}

// unix 隐藏文件/目录：名前缀 `.`（File.isHidden 在 unix 下等价，但显式判断更稳）
fun isHiddenName(name: String): Boolean = name.startsWith(".")
