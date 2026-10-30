package com.aurora.music.data.cast

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 远程音乐 / 有声书缓存（Media3 SimpleCache）。
 *
 * - 只缓存 http(s)（局域网设备）资源，本地 file:// / content:// 仍是直读；
 * - 播放走 CacheDataSource：没缓存边播边写，已缓存离线也能播；
 * - 预取当前/下一首，保留上一首，其余由 [retainMusic] / [retainAudiobooks] 清理；
 * - 电子书不在这里，走 [RemoteLibraryManager] 的整文件下载目录。
 */
object RemoteMediaCache {

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var upstream: DataSource.Factory? = null
    @Volatile private var cacheFactory: CacheDataSource.Factory? = null

    private val prefetchMutex = Mutex()
    private val prefetching = ConcurrentHashMap<String, Boolean>()
    private val knownMusic = ConcurrentHashMap.newKeySet<String>()
    private val knownAudiobooks = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun init(context: Context) {
        if (cache != null) return
        val dir = File(context.applicationContext.cacheDir, "remote_media")
        if (!dir.isDirectory) runCatching { dir.mkdirs() }
        val c = runCatching { SimpleCache(dir, androidx.media3.datasource.cache.NoOpCacheEvictor()) }.getOrNull() ?: return
        val up = DefaultDataSource.Factory(context.applicationContext)
        val cf = CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(up)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        cache = c
        upstream = up
        cacheFactory = cf
    }

    /** 播放器数据源：http 走缓存，其余原样。 */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        init(context)
        val up = upstream ?: DefaultDataSource.Factory(context.applicationContext)
        val cf = cacheFactory ?: return up
        return DataSource.Factory {
            val local = up.createDataSource()
            val remote = cf.createDataSource()
            object : DataSource {
                private var delegate: DataSource = local

                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    local.addTransferListener(transferListener)
                    remote.addTransferListener(transferListener)
                }

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val scheme = dataSpec.uri.scheme?.lowercase()
                    delegate = if (scheme == "http" || scheme == "https") remote else local
                    return delegate.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    delegate.read(buffer, offset, length)

                override fun getUri(): android.net.Uri? = delegate.uri

                override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

                override fun close() {
                    runCatching { local.close() }
                    runCatching { remote.close() }
                }
            }
        }
    }

    fun isCached(url: String): Boolean {
        val c = cache ?: return false
        return runCatching {
            c.isCached(url, 0L, androidx.media3.common.C.LENGTH_UNSET.toLong())
        }.getOrDefault(false)
    }

    suspend fun prefetch(url: String, audiobook: Boolean = false) {
        if (url.isBlank()) return
        val c = cache ?: return
        if (isCached(url)) {
            if (audiobook) knownAudiobooks += url else knownMusic += url
            return
        }
        if (prefetching.putIfAbsent(url, true) != null) return
        try {
            prefetchMutex.withLock {
                withContext(Dispatchers.IO) {
                    val factory = cacheFactory ?: return@withContext
                    runCatching {
                        val source = factory.createDataSource()
                        val spec = androidx.media3.datasource.DataSpec(UriCompat.of(url))
                        source.open(spec)
                        val buf = ByteArray(128 * 1024)
                        while (source.read(buf, 0, buf.size) != androidx.media3.common.C.RESULT_END_OF_INPUT) {
                            // 读完即写入 SimpleCache
                        }
                        source.close()
                    }
                }
            }
            if (audiobook) knownAudiobooks += url else knownMusic += url
        } finally {
            prefetching.remove(url)
        }
    }

    /** 音乐只保留上一首 / 当前 / 下一首（用户要求：播完保留上一首，再往下播删更早的）。 */
    fun retainMusic(urls: Collection<String>) {
        val keep = urls.filter { it.isNotBlank() }.toSet()
        val c = cache ?: return
        // 进程重启后 knownMusic 是空的：直接扫 cache key，把上一进程遗留的旧歌也清掉。
        val stale = runCatching { c.keys.filter { "/media/music?" in it && it !in keep } }
            .getOrDefault(emptyList()) + knownMusic.filter { it !in keep }
        stale.forEach { url ->
            runCatching { c.removeResource(url) }
            knownMusic.remove(url)
        }
        knownMusic += keep
    }

    /** 有声书按“最近在听书目”的 前/当前/后 三章保留，其余清掉。 */
    fun retainAudiobooks(urls: Collection<String>) {
        val keep = urls.filter { it.isNotBlank() }.toSet()
        val c = cache ?: return
        val stale = runCatching { c.keys.filter { "/media/audiobook?" in it && it !in keep } }
            .getOrDefault(emptyList()) + knownAudiobooks.filter { it !in keep }
        stale.forEach { url ->
            runCatching { c.removeResource(url) }
            knownAudiobooks.remove(url)
        }
        knownAudiobooks += keep
    }

    private object UriCompat {
        fun of(url: String): android.net.Uri = android.net.Uri.parse(url)
    }
}
