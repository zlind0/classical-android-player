package com.aurora.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

// 后台/断点扫描的暂存：大库深扫动辄十几分钟，进程被杀或切走界面都不能丢进度。
// 每 CHECKPOINT_EVERY 个文件落一次盘（scan_checkpoint_<rootId>.json），
// 下次启动 [LibraryScanManager.resumePending] 读回，已暂存且 size+mtime 未变的
// 文件直接复用，不再走 MediaMetadataRetriever。
data class ScanCheckpoint(
    val rootId: Long,
    val rootPath: String,
    val version: Int = VERSION,
    val updatedAt: Long = 0L,
    val completed: List<ScannedTrack> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

class ScanCheckpoints(private val context: Context) {
    private val gson = Gson()
    private val type = object : TypeToken<ScanCheckpoint>() {}.type

    fun file(rootId: Long): File = File(context.filesDir, "scan_checkpoint_$rootId.json")

    fun load(rootId: Long): ScanCheckpoint? = runCatching {
        val f = file(rootId)
        if (!f.exists()) return null
        (gson.fromJson<ScanCheckpoint>(f.readText(), type))?.takeIf {
            it.version == ScanCheckpoint.VERSION && it.rootId == rootId
        }
    }.getOrNull()

    /** 原子落盘：先写 tmp 再 rename，避免杀进程留下半截 JSON。 */
    fun save(cp: ScanCheckpoint) {
        runCatching {
            val f = file(cp.rootId)
            val tmp = File(context.filesDir, "scan_checkpoint_${cp.rootId}.tmp")
            tmp.writeText(gson.toJson(cp.copy(updatedAt = System.currentTimeMillis())))
            if (!runCatching { tmp.renameTo(f) }.getOrDefault(false)) {
                runCatching { tmp.copyTo(f, overwrite = true) }
                runCatching { tmp.delete() }
            }
        }
    }

    fun clear(rootId: Long) {
        runCatching { file(rootId).delete() }
    }

    /** 启动恢复用：所有残留断点的 rootId。 */
    fun pendingRootIds(): List<Long> = runCatching {
        context.filesDir.listFiles { f ->
            f.isFile && f.name.startsWith("scan_checkpoint_") && f.name.endsWith(".json")
        }?.mapNotNull { f ->
            f.name.removePrefix("scan_checkpoint_").removeSuffix(".json").toLongOrNull()
        }.orEmpty()
    }.getOrDefault(emptyList())
}
