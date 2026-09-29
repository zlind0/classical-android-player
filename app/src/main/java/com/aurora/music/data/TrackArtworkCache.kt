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
 * （cacheDir/track_art/c/<md5>.jpg），每首歌只存一个 32 字节的索引
 * （cacheDir/track_art/s/<md5(songId)>）。同专辑 12 首歌共用一张图
 * 时只占一份空间。旧版按歌存的遗留文件读到即迁移合并后删除。
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

    private fun indexFile(context: Context, songId: String): File {
        val dir = File(rootDir(context), "s")
        if (!dir.isDirectory) runCatching { dir.mkdirs() }
        return File(dir, safeName(songId))
    }

    private fun readIndex(context: Context, songId: String): String? {
        val f = indexFile(context, songId)
        if (!f.isFile) return null
        return runCatching { f.readText().trim().takeIf { it.matches(MD5_RE) } }.getOrNull()
    }

    private fun contentUriIfValid(context: Context, md5: String): String? {
        val f = contentFile(context, md5)
        if (f.isFile && f.length() > 0) return Uri.fromFile(f).toString()
        return null
    }

    private fun md5Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun cachedSync(context: Context, song: Song): String {
        if (song.id.isBlank()) return song.artworkUrl
        mem[song.id]?.let { return it }
        // 索引 → 去重后的内容文件
        readIndex(context, song.id)?.let { md5 ->
            contentUriIfValid(context, md5)?.let { uri ->
                mem[song.id] = uri
                return uri
            }
        }
        // 旧版遗留文件：存在即可直接显示，去重迁移等后台 resolve 时做，不阻塞 UI
        val legacy = cacheFile(context, song.id)
        if (legacy.isFile && legacy.length() > 0) {
            val uri = Uri.fromFile(legacy).toString()
            mem[song.id] = uri
            return uri
        }
        return ""
    }

    suspend fun resolve(context: Context, song: Song): String = withContext(Dispatchers.IO) {
        if (song.id.isBlank()) return@withContext song.artworkUrl
        mem[song.id]?.let { return@withContext it }
        readIndex(context, song.id)?.let { md5 ->
            contentUriIfValid(context, md5)?.let { uri ->
                mem[song.id] = uri
                return@withContext uri
            }
        }
        // 旧版遗留：就地去重（读字节→按图 MD5 入库→删旧件），以后不再有重复
        val legacy = cacheFile(context, song.id)
        if (legacy.isFile && legacy.length() > 0) {
            migrateLegacy(context, song.id, legacy)?.let { uri ->
                mem[song.id] = uri
                return@withContext uri
            }
            // 迁移失败=坏文件：删掉，走 fallback
            runCatching { legacy.delete() }
        }
        // 本文件的内嵌图
        if (extractEmbedded(context, song)) {
            mem[song.id]?.let { return@withContext it }
        }
        //  fallback：专辑级 art / 目录封面（列表用的那张）
        val fallback = song.artworkUrl.ifBlank { folderCover(song) }
        return@withContext fallback
    }

    /** 兼容旧版：songId 命名的图片文件（新版不再写入，读到即迁移去重后删除）。 */
    fun cacheFile(context: Context, songId: String): File {
        val dir = rootDir(context)
        return File(dir, safeName(songId) + ".jpg")
    }

    /** 内嵌图缓存 URI（不预检存在，供专辑封面等展示层直接用；缺失由 Artwork 默认图兜底）。 */
    fun embeddedCacheUri(context: Context, songId: String): String =
        runCatching {
            val md5 = readIndex(context, songId)
            val f = if (md5 != null) contentFile(context, md5) else cacheFile(context, songId)
            android.net.Uri.fromFile(f).toString()
        }.getOrDefault("")

    /**
     * 存内嵌图字节进缓存（扫描时调用，已知 bytes 非空）。
     * 按图片 MD5 去重：相同图片只落盘一次，多首歌共享。
     * 返回 true = 有效图片已入库。IO 线程调用。
     */
    fun saveEmbedded(context: Context, songId: String, bytes: ByteArray): Boolean {
        if (songId.isBlank() || bytes.isEmpty()) return false
        val md5 = runCatching { md5Hex(bytes) }.getOrNull() ?: return false
        val content = contentFile(context, md5)
        if (!(content.isFile && content.length() > 0)) {
            if (!writeValidated(content, bytes) && !(content.isFile && content.length() > 0)) {
                // 并发写入时别人可能刚好先落盘，再确认一次，避免误判失败
                return false
            }
        }
        // 索引 songId → 图片 md5（幂等覆盖）
        runCatching {
            val idx = indexFile(context, songId)
            idx.parentFile?.mkdirs()
            idx.writeText(md5)
        }
        mem[songId] = Uri.fromFile(content).toString()
        return true
    }

    /** 旧版遗留文件迁移：内容入库（去重）+ 索引 + 删旧件，回收重复空间。 */
    private fun migrateLegacy(context: Context, songId: String, legacy: File): String? {
        val bytes = runCatching { legacy.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (!saveEmbedded(context, songId, bytes)) return null
        runCatching { legacy.delete() }
        return mem[songId]
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

    private fun safeName(id: String): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        val d = md.digest(id.toByteArray())
        d.joinToString("") { "%02x".format(it) }
    }.getOrDefault(id.hashCode().toUInt().toString(16))

    private fun extractEmbedded(context: Context, song: Song): Boolean {
        val bytes = readEmbeddedBytes(context, song) ?: return false
        if (bytes.isEmpty()) return false
        return saveEmbedded(context, song.id, bytes)
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

    fun invalidate(songId: String, context: Context? = null) {
        mem.remove(songId)
        // 索引删掉（内容文件多首歌共享，不删，由整目录清理负责）
        if (context != null && songId.isNotBlank()) {
            runCatching { indexFile(context, songId).delete() }
        }
    }
}
