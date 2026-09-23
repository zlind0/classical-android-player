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
 * 内嵌图命中后写到 cacheDir/track_art 下，key 为 Song.id 的 MD5，
 * 后续直接走文件 URI，不再开 retriever。
 */
object TrackArtworkCache {

    private val mem = ConcurrentHashMap<String, String>()

    fun cachedSync(context: Context, song: Song): String {
        if (song.id.isBlank()) return song.artworkUrl
        mem[song.id]?.let { return it }
        val f = cacheFile(context, song.id)
        if (f.isFile && f.length() > 0) {
            val uri = Uri.fromFile(f).toString()
            mem[song.id] = uri
            return uri
        }
        return ""
    }

    suspend fun resolve(context: Context, song: Song): String = withContext(Dispatchers.IO) {
        if (song.id.isBlank()) return@withContext song.artworkUrl
        mem[song.id]?.let { return@withContext it }
        val f = cacheFile(context, song.id)
        if (f.isFile && f.length() > 0) {
            val uri = Uri.fromFile(f).toString()
            mem[song.id] = uri
            return@withContext uri
        }
        // 本文件的内嵌图
        if (extractEmbedded(context, song, f)) {
            val uri = Uri.fromFile(f).toString()
            mem[song.id] = uri
            return@withContext uri
        }
        //  fallback：专辑级 art / 目录封面（列表用的那张）
        val fallback = song.artworkUrl.ifBlank { folderCover(song) }
        return@withContext fallback
    }

    fun cacheFile(context: Context, songId: String): File {
        val dir = File(context.cacheDir, "track_art")
        if (!dir.isDirectory) runCatching { dir.mkdirs() }
        return File(dir, safeName(songId) + ".jpg")
    }

    private fun safeName(id: String): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        val d = md.digest(id.toByteArray())
        d.joinToString("") { "%02x".format(it) }
    }.getOrDefault(id.hashCode().toUInt().toString(16))

    private fun extractEmbedded(context: Context, song: Song, out: File): Boolean {
        val bytes = readEmbeddedBytes(context, song) ?: return false
        if (bytes.isEmpty()) return false
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
