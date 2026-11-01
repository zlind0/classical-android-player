package com.aurora.music.data.cast

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 远程资料库封面缓存：艺人/专辑/图书/有声书封面统一按逻辑 key 落盘。
 *
 * 缓存键是 `deviceId + 资源类型 + key`，不是完整 URL：局域网与公网中继只是同一台设备的
 * 两个端点，切换传输方式后同一个封面依然命中同一份文件，不必重新下载。
 * 下载地址仍由调用方按当前端点拼出来。
 *
 * 存到 filesDir/remote_library/art/<device>/<sha1(deviceId|key)>.img，设备 30 天不连接时
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

    private fun fileFor(context: Context, deviceId: String, key: String): File =
        File(deviceDir(context, deviceId), sha1("$deviceId|$key") + ".img")

    /** 已缓存返回 file://，否则原 URL（在线时由 Coil 先显示，随后被后台下载替换）。 */
    fun cachedOrRemote(context: Context, deviceId: String, key: String, url: String): String {
        if (url.isBlank()) return ""
        val f = fileFor(context, deviceId, key)
        return if (f.isFile && f.length() > 0) Uri.fromFile(f).toString() else url
    }

    fun isCached(context: Context, deviceId: String, key: String): Boolean {
        val f = fileFor(context, deviceId, key)
        return f.isFile && f.length() > 0
    }

    /**
     * [entries] 是 逻辑 key → 当前端点 URL；已缓存的 key 直接跳过。
     * 返回失败条数；调用方可以据此在下一轮补拉。
     */
    suspend fun prefetch(context: Context, deviceId: String, entries: Collection<Pair<String, String>>): Int {
        val ctx = context.applicationContext
        var failed = 0
        entries.asSequence()
            .filter { (_, url) -> url.startsWith("http://") || url.startsWith("https://") }
            .distinctBy { it.first }
            .forEach { (key, url) ->
                // 端点切换时旧任务会被取消：别继续给旧端点发请求。
                kotlin.coroutines.coroutineContext.ensureActive()
                val dest = fileFor(ctx, deviceId, key)
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
                    failed += 1
                    runCatching { tmp.delete() }
                }
            }
        return failed
    }

    fun clearDevice(context: Context, deviceId: String) {
        runCatching { deviceDir(context, deviceId).deleteRecursively() }
    }

    private fun sha1(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(s.hashCode().toString())
}
