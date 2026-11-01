package com.aurora.music.data.relay

import android.content.Context
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 解密后的资料库快照落盘缓存 + 明文分片内存 LRU。
 * 媒体数据最终还会被 Media3 的 SimpleCache 缓存，这里只做播放窗口内的复用。
 */
class RelayStore(context: Context, private val config: RelayConfig) {

    data class Snapshot(
        val version: Long,
        val json: String,
        val deviceId: String,
        val deviceName: String,
    )

    private val dir = File(context.applicationContext.filesDir, "relay_cache")
    private val snapshotFile = File(dir, "library.json")
    private val snapshotMeta = File(dir, "library.version")
    private val snapshotLock = Mutex()

    @Volatile
    private var cached: Snapshot? = loadFromDisk()

    private val fragmentLock = Object()
    private val fragments = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {}
    private var fragmentBytes = 0L
    private val maxFragmentBytes = 32L * 1024 * 1024

    fun deviceId(): String? = cached?.deviceId

    fun deviceName(): String? = cached?.deviceName

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    private fun loadFromDisk(): Snapshot? {
        val json = runCatching { snapshotFile.readText() }.getOrNull() ?: return null
        val version = runCatching { snapshotMeta.readText().trim().toLong() }.getOrNull() ?: return null
        val deviceId = runCatching {
            JsonParser.parseString(json).asJsonObject.get("deviceId")?.asString.orEmpty()
        }.getOrNull().orEmpty()
        val deviceName = runCatching {
            JsonParser.parseString(json).asJsonObject.get("deviceName")?.asString.orEmpty()
        }.getOrNull().orEmpty()
        if (deviceId.isBlank()) return null
        return Snapshot(version, json, deviceId, deviceName)
    }

    fun cachedVersion(): Snapshot? = cached

    /**
     * 返回指定版本的快照；本地已有同一版本时直接复用。
     * 密钥不一致会抛异常，调用方应提示用户重新填写 media key。
     */
    suspend fun ensureSnapshot(client: RelayClient): Snapshot = withContext(Dispatchers.IO) {
        snapshotLock.withLock {
            val master = config.current.masterKey
                ?: throw IllegalStateException("media key is not configured")
            val version = client.libVersion()
            if (version.keyId != 0L && version.keyId != config.current.keyId) {
                throw IllegalStateException("media key mismatch with relay snapshot")
            }
            cached?.let { if (it.version == version.version && version.version > 0) return@withLock it }
            if (version.version <= 0) {
                throw IllegalStateException("relay has no library snapshot yet")
            }
            val chunks = ArrayList<ByteArray>(version.chunks)
            for (index in 0 until version.chunks) {
                chunks += client.snapshotChunk(version.version, index).data
            }
            val header = RelayCrypto.SnapshotHeader(
                version = version.version,
                keyId = version.keyId,
                plainLen = version.plainLen,
                gzLen = version.gzLen,
                chunks = version.chunks,
                salt = version.salt,
            )
            val json = String(RelayCrypto.decryptSnapshot(master, header, chunks), Charsets.UTF_8)
            val deviceId = JsonParser.parseString(json).asJsonObject.get("deviceId")?.asString.orEmpty()
            val deviceName = JsonParser.parseString(json).asJsonObject.get("deviceName")?.asString.orEmpty()
            ensureDir()
            snapshotFile.writeText(json)
            snapshotMeta.writeText(version.version.toString())
            Snapshot(version.version, json, deviceId, deviceName).also { cached = it }
        }
    }

    fun snapshotJson(): String? = cached?.json

    fun clearSnapshot() {
        cached = null
        runCatching { snapshotFile.delete() }
        runCatching { snapshotMeta.delete() }
    }

    fun decryptFragment(
        fileId: ByteArray,
        index: Int,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val key = fileIdKey(fileId, index)
        synchronized(fragmentLock) {
            fragments[key]?.let { return it }
        }
        val master = config.current.masterKey
            ?: throw IllegalStateException("media key is not configured")
        val plain = RelayCrypto.decryptFragment(
            RelayCrypto.mediaKey(master),
            fileId,
            index,
            nonce,
            ciphertext,
        )
        synchronized(fragmentLock) {
            fragments[key] = plain
            fragmentBytes += plain.size
            while (fragmentBytes > maxFragmentBytes && fragments.isNotEmpty()) {
                val eldest = fragments.entries.first()
                fragments.remove(eldest.key)
                fragmentBytes -= eldest.value.size
            }
        }
        return plain
    }

    private fun fileIdKey(fileId: ByteArray, index: Int): Long {
        var hash = index.toLong() and 0xFFFFFFFFL
        for (byte in fileId) {
            hash = hash * 1099511628211L xor (byte.toLong() and 0xFF)
        }
        return hash
    }
}
