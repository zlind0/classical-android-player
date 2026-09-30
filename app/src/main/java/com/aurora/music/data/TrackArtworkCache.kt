package com.aurora.music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aurora.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 本文件封面解析：播放时必须显示“这一文件”的内嵌图，而不是
 * MediaStore 专辑级 albumart（同专辑所有曲目共用一张，多为专辑里
 * 其他文件的图）或专辑列表里随机挑出来的那张总封面。
 *
 * 优先级：文件内嵌图 > Song.artworkUrl(albumart) > 同目录 cover 文件 > 空。
 *
 * 去重（内容寻址）：内嵌图按图片字节 MD5 只存一份
 * （cacheDir/track_art/c/<md5>.jpg），多首歌共用一张图时只占一份空间。
 * 歌→图的映射存在歌曲数据库行内（artMd5），这里只管内容文件 + 内存。
 */
object TrackArtworkCache {

    private val mem = ConcurrentHashMap<String, String>()
    private val MD5_RE = Regex("[0-9a-f]{32}")

    private fun rootDir(context: Context): File =
        File(context.cacheDir, "track_art").apply { if (!isDirectory) runCatching { mkdirs() } }

    private fun contentFile(context: Context, md5: String): File {
        val dir = File(rootDir(context), "c")
        if (!dir.isDirectory) runCatching { dir.mkdirs() }
        return File(dir, "$md5.jpg")
    }

    private fun contentUriIfValid(context: Context, md5: String): String? {
        if (!md5.matches(MD5_RE)) return null
        val f = contentFile(context, md5)
        if (f.isFile && f.length() > 0) return Uri.fromFile(f).toString()
        return null
    }

    private fun md5Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** 内存命中才返回（同步快查，无 IO 阻塞）；未命中返回 ""，调用方用 song.artworkUrl。 */
    fun cachedSync(context: Context, song: Song): String {
        if (song.id.isBlank()) return song.artworkUrl
        mem[song.id]?.let { return it }
        return ""
    }

    suspend fun resolve(context: Context, song: Song): String = withContext(Dispatchers.IO) {
        if (song.id.isBlank()) return@withContext song.artworkUrl
        mem[song.id]?.let { return@withContext it }
        // 本文件的内嵌图（按需抽一次，进内容文件 + 内存；DB 行的 artMd5 由扫描/改标签时写入）
        if (extractEmbedded(context, song)) {
            mem[song.id]?.let { return@withContext it }
        }
        //  fallback：专辑级 art / 目录封面（列表用的那张）
        val fallback = song.artworkUrl.ifBlank { folderCover(song) }
        return@withContext fallback
    }

    /** 行内 artMd5 → 内容文件 URI（不预检存在，供展示层直接用；缺失由默认图兜底）。 */
    fun contentUri(context: Context, artMd5: String): String {
        if (!artMd5.matches(MD5_RE)) return ""
        return runCatching { Uri.fromFile(contentFile(context, artMd5)).toString() }.getOrDefault("")
    }

    /**
     * 存内嵌图字节进缓存（扫描/改标签时调用，已知 bytes 非空）。
     * 按图片 MD5 去重：相同图片只落盘一次，多首歌共享。
     * 返回图片 md5（"" = 无效），调用方随歌曲行一起入库。IO 线程调用。
     */
    fun saveEmbedded(context: Context, songId: String, bytes: ByteArray): String {
        if (songId.isBlank() || bytes.isEmpty()) return ""
        val md5 = runCatching { md5Hex(bytes) }.getOrNull() ?: return ""
        val content = contentFile(context, md5)
        if (!(content.isFile && content.length() > 0)) {
            if (!writeValidated(content, bytes) && !(content.isFile && content.length() > 0)) {
                // 并发写入时别人可能刚好先落盘，再确认一次，避免误判失败
                return ""
            }
        }
        val uri = Uri.fromFile(content).toString()
        mem[songId] = uri
        return md5
    }

    private fun writeValidated(out: File, bytes: ByteArray): Boolean {
        return runCatching {
            out.parentFile?.mkdirs()
            val tmp = File(out.parent, out.name + ".tmp")
            tmp.writeBytes(bytes)
            // 至少能解出宽高才算有效图片，避免把损坏数据当封面缓存
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(tmp.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                runCatching { tmp.delete() }
                false
            } else {
                if (!tmp.renameTo(out)) {
                    runCatching { tmp.copyTo(out, overwrite = true); tmp.delete() }
                }
                out.isFile && out.length() > 0
            }
        }.getOrDefault(false)
    }

    private fun extractEmbedded(context: Context, song: Song): Boolean {
        val bytes = readEmbeddedBytes(context, song) ?: return false
        if (bytes.isEmpty()) return false
        return saveEmbedded(context, song.id, bytes).isNotBlank()
    }

    private fun readEmbeddedBytes(context: Context, song: Song): ByteArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            var set = false
            // 优先直读文件路径：最准且不需要 ContentResolver 权限（缺失抛异常走兜底，不预检 exists）
            val p = song.path
            if (p.isNotBlank()) {
                set = runCatching { mmr.setDataSource(p); true }.getOrDefault(false)
            }
            if (!set && song.streamUrl.isNotBlank()) {
                val u = runCatching { Uri.parse(song.streamUrl) }.getOrNull()
                if (u != null) {
                    set = when (u.scheme?.lowercase()) {
                        "file" -> runCatching {
                            mmr.setDataSource(u.path ?: song.streamUrl); true
                        }.getOrDefault(false)
                        "content" -> runCatching {
                            mmr.setDataSource(context, u); true
                        }.getOrDefault(false)
                        else -> runCatching {
                            mmr.setDataSource(song.streamUrl); true
                        }.getOrDefault(false)
                    }
                } else {
                    set = runCatching { mmr.setDataSource(song.streamUrl); true }.getOrDefault(false)
                }
            }
            if (!set) return null
            runCatching { mmr.embeddedPicture }.getOrNull()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun folderCover(song: Song): String {
        val p = song.path
        if (p.isBlank()) return ""
        val dir = runCatching { File(p).parentFile }.getOrNull() ?: return ""
        for (name in arrayOf("cover.jpg", "cover.jpeg", "folder.jpg", "cover.png", "folder.png", "front.jpg")) {
            val c = File(dir, name)
            if (runCatching { c.isFile && c.canRead() }.getOrDefault(false)) {
                return runCatching { Uri.fromFile(c).toString() }.getOrDefault("")
            }
        }
        return ""
    }

    fun invalidate(songId: String) {
        mem.remove(songId)
    }
}
