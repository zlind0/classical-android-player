package com.aurora.music.data.cast

import android.net.Uri
import com.aurora.music.model.Song
import java.io.File

// 局域网资料库协议（v1）：
// - 发现沿用 _aurora-cast._tcp / UDP 47823，拿到 host/port 后拉 /library/all；
// - 数据只读，媒体文件全部走 /media/*，支持 Range（音乐/有声书）与整文件下载（电子书）。
// - 所有 id/path 都用 URL 参数传，避免中文/空格在 HTTP 头里出问题。

/** 音乐曲目（最小元信息；专辑/艺人由客户端从曲目聚合，保证顺序稳定）。 */
data class RemoteTrackWire(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
    val artistId: String = "",
    val durationSec: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val hasArt: Boolean = false,
)

/** 电子书顶层目录：默认书架 + 各扫描根；远程端按它还原本地“书库→目录→书”的层级。 */
data class RemoteEbookRootWire(
    val key: String = "",
    val rootPath: String = "",
    val displayName: String = "",
    val shelf: Boolean = false,
    val bookCount: Int = 0,
)

data class RemoteEbookWire(
    val path: String = "",
    val title: String = "",
    val author: String = "",
    val size: Long = 0L,
    val hasCover: Boolean = false,
    val rootKey: String = "",
)

data class RemoteChapterWire(
    val path: String = "",
    val title: String = "",
    val trackNumber: Int = 0,
    val durationSec: Int = 0,
    val size: Long = 0L,
)

data class RemoteAudiobookWire(
    val id: String = "",
    val title: String = "",
    val hasCover: Boolean = false,
    val chapters: List<RemoteChapterWire> = emptyList(),
)

/** 一台设备的完整资料库快照（磁盘也缓存这一份，离线时照样显示）。 */
data class RemoteLibraryWire(
    val deviceId: String = "",
    val deviceName: String = "",
    val generatedAt: Long = 0L,
    val music: List<RemoteTrackWire> = emptyList(),
    val ebookRoots: List<RemoteEbookRootWire> = emptyList(),
    val ebooks: List<RemoteEbookWire> = emptyList(),
    val audiobooks: List<RemoteAudiobookWire> = emptyList(),
)

// ---- 客户端可用模型 ----

data class RemoteArtistGroup(
    val name: String,
    val songs: List<Song>,
)

data class RemoteAlbumGroup(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String,
    val songs: List<Song>,
)

/** 一台远程设备在 UI 里的一份只读资料库。songs 已转成可直接播放的 [Song]。 */
data class RemoteDeviceLibrary(
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val online: Boolean,
    val fetchedAt: Long,
    val songs: List<Song> = emptyList(),
    val artists: List<RemoteArtistGroup> = emptyList(),
    val albums: List<RemoteAlbumGroup> = emptyList(),
    val ebookRoots: List<RemoteEbookRootWire> = emptyList(),
    val ebooks: List<RemoteEbookWire> = emptyList(),
    val audiobooks: List<RemoteAudiobookWire> = emptyList(),
) {
    val baseUrl: String get() = "http://$host:$port"
    val hasMusic: Boolean get() = songs.isNotEmpty()
}

data class RemoteEbookEntry(
    val deviceId: String,
    val deviceName: String,
    val remotePath: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val size: Long,
    val localPath: String = "",
    val lastReadAt: Long = 0L,
) {
    val key: String get() = "$deviceId|$remotePath"
    val displayTitle: String get() = title.ifBlank { remotePath.substringAfterLast('/').substringBeforeLast('.') }
}

data class RemoteAudiobookEntry(
    val deviceId: String,
    val deviceName: String,
    val bookId: String,
    val title: String,
    val coverUrl: String,
    val chapters: List<RemoteChapterWire>,
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
) {
    val key: String get() = "$deviceId|$bookId"
    val displayTitle: String get() = title.ifBlank { bookId.substringAfterLast('/') }
}

/** 服务端给 /media/… 返回的资源：本地文件（支持 Range）、content:// 或内存字节。 */
sealed interface LibraryResource {
    val mime: String

    data class LocalFile(val file: File, override val mime: String) : LibraryResource

    data class ContentUri(val uri: Uri, override val mime: String) : LibraryResource

    data class Bytes(val bytes: ByteArray, override val mime: String) : LibraryResource
}

/** 本机 HTTP 服务把 /library/all 与 /media/… 委托给这个 provider。 */
interface RemoteLibraryProvider {
    fun libraryJson(): String

    /** path 形如 "music?id=..." / "ebook?path=..."，由 EbookCastHttpServer 直接转交。 */
    fun serve(out: java.io.OutputStream, headers: Map<String, String>, query: Map<String, String>): Boolean
}
