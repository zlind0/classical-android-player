package com.aurora.music.data.cast

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 远程资料的「逻辑 URL」。
 *
 * 局域网直连和公网中继只是同一台 NAS 的两个端点，同一首歌在不同传输方式下的
 * host:port 完全不同。如果播放地址直接用当前端点的 http URL，那么每次切换传输方式：
 *
 * - Media3 的 SimpleCache 以 URL 作缓存键，缓存全部失效；
 * - 封面缓存以 URL 作文件名，封面全部重下；
 * - `chapterCached()` 之类的「是否已缓存」判断也全部失效。
 *
 * 所以曲目/章节的播放地址一律用 `pod-remote://` 虚拟 URL 标识，
 * 真正打开数据源时再由 [RemoteMediaCache.resolveUrl] 换成当前端点的 http URL。
 * 虚拟 URL 只由 deviceId + 资源类型 + key 决定，与传输方式无关。
 *
 * 格式：`pod-remote://device/<deviceId>/media/<kind>?<param>=<value>`
 * 这里手写解析而不用 android.net.Uri，便于单元测试，也避免 Uri 对 host 的额外校验。
 */
object RemoteUrls {

    const val SCHEME = "pod-remote"
    private const val PREFIX = "$SCHEME://device/"

    fun music(deviceId: String, trackId: String): String =
        build(deviceId, "music", "id", trackId)

    fun audiobook(deviceId: String, chapterPath: String): String =
        build(deviceId, "audiobook", "path", chapterPath)

    /** http(s) 与虚拟 URL 都算远程可播放资源；file:// / content:// 不算。 */
    fun isRemoteStream(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://") || isVirtual(url)

    fun isVirtual(url: String): Boolean = url.startsWith(PREFIX)

    fun deviceIdOf(url: String): String? {
        if (!isVirtual(url)) return null
        val rest = url.removePrefix(PREFIX)
        return rest.substringBefore('/').takeIf { it.isNotBlank() }?.let { decode(it) }
    }

    /**
     * 把虚拟 URL 解析成当前端点可访问的 http URL。
     * 不是虚拟 URL 时原样返回；端点未知（设备离线/尚未同步）时也原样返回，
     * 让上层请求自然失败，而不是拿到一个猜测的地址。
     */
    fun resolve(url: String, baseUrlOf: (String) -> String?): String {
        if (!isVirtual(url)) return url
        val rest = url.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0) return url
        val deviceId = decode(rest.substring(0, slash))
        val tail = rest.substring(slash + 1)
        if (!tail.startsWith("media/")) return url
        val afterMedia = tail.removePrefix("media/")
        val kind = afterMedia.substringBefore('?')
        val param = when (kind) {
            "music" -> "id"
            "audiobook" -> "path"
            else -> return url
        }
        val base = baseUrlOf(deviceId) ?: return url
        val rawQuery = afterMedia.substringAfter('?', "")
        val value = rawQuery.split('&')
            .firstOrNull { it.startsWith("$param=") }
            ?.substringAfter('=')
            ?.let { decode(it) }
            ?: return url
        return "$base/media/$kind?$param=${enc(value)}"
    }

    private fun build(deviceId: String, kind: String, param: String, value: String): String =
        "$PREFIX${enc(deviceId)}/media/$kind?$param=${enc(value)}"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }
        .getOrDefault(value)
}
