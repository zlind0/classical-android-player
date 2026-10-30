package com.aurora.music.data.cast

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 远程资料库封面缓存：艺人/专辑/图书/有声书封面统一按 URL 内容寻址落盘。
 *
 * 存到 filesDir/remote_library/art/<device>/<sha1(url)>.img，设备 30 天不连接时
 * 由 [RemoteLibraryManager] 调 [clearDevice] 一起删掉。
 */
object RemoteArtCache {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun root(context: Context): File =
        File(context.applicationContext.filesDir, "remote_library/art")

    private fun deviceDir(context: Context, deviceId: String): File {
        val dir = File(root(context), sha1(deviceId))
        if (!dir.isDirectory) runCatching { dir.mkdirs() }
        return dir
    }

    private fun fileFor(context: Context, deviceId: String, url: String): File =
        File(deviceDir(context, deviceId), sha1(url) + ".img")

    /** 已缓存返回 file://，否则原 URL（在线时由 Coil 先显示，随后被后台下载替换）。 */
    fun cachedOrRemote(context: Context, deviceId: String, url: String): String {
        if (url.isBlank()) return ""
        val f = fileFor(context, deviceId, url)
        return if (f.isFile && f.length() > 0) Uri.fromFile(f).toString() else url
    }

    fun isCached(context: Context, deviceId: String, url: String): Boolean {
        val f = fileFor(context, deviceId, url)
        return f.isFile && f.length() > 0
    }

    suspend fun prefetch(context: Context, deviceId: String, urls: Collection<String>) {
        val ctx = context.applicationContext
        urls.asSequence()
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .forEach { url ->
                val dest = fileFor(ctx, deviceId, url)
                if (dest.isFile && dest.length() > 0) return@forEach
                val tmp = File(dest.parentFile, dest.name + ".part")
                val ok = runCatching {
                    val req = Request.Builder().url(url).get().build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@use false
                        val body = r.body ?: return@use false
                        tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                        true
                    }
                }.getOrDefault(false)
                if (ok && tmp.isFile && tmp.length() > 0) {
                    if (!tmp.renameTo(dest)) {
                        runCatching { tmp.copyTo(dest, overwrite = true) }
                        runCatching { tmp.delete() }
                    }
                } else {
                    runCatching { tmp.delete() }
                }
            }
    }

    fun clearDevice(context: Context, deviceId: String) {
        runCatching { deviceDir(context, deviceId).deleteRecursively() }
    }

    private fun sha1(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(s.hashCode().toString())
}
