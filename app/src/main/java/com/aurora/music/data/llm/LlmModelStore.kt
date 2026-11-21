package com.aurora.music.data.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 多模态大模型权重下载器：单文件 Range 续传。
 *
 * 权重是 2.6–4.9 GB 的单个 .litertlm 文件，下到 filesDir/llm-models/<id>/。
 * 下载跑在 App 级作用域：离开设置页、切后台都继续；重进页面仍能看到进度。
 */
class LlmModelStore(private val context: Context) {

    sealed interface ModelState {
        data object NotInstalled : ModelState
        data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState
        data object Installed : ModelState
        data class Failed(val message: String) : ModelState
    }

    private companion object {
        const val TAG = "LlmModelStore"
        const val PART_SUFFIX = ".part"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    @Volatile private var cancelRequested = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun stateOf(spec: LlmModelSpec): ModelState =
        _states.value[spec.id] ?: if (isInstalled(spec)) ModelState.Installed else ModelState.NotInstalled

    fun isInstalled(spec: LlmModelSpec): Boolean =
        modelFile(spec).let { it.isFile && it.length() > 0 }

    fun modelFile(spec: LlmModelSpec): File = File(llmModelDir(context, spec), spec.fileName)

    fun installedIds(): List<String> = LlmModelCatalog.all.filter { isInstalled(it) }.map { it.id }

    fun pickUsable(preferredId: String): LlmModelSpec? =
        LlmModelCatalog.byId(preferredId)?.takeIf { isInstalled(it) }
            ?: LlmModelCatalog.all.firstOrNull { isInstalled(it) }

    fun installedBytes(): Long =
        LlmModelCatalog.all.filter { isInstalled(it) }.sumOf { modelFile(it).length() }

    fun cancel() {
        cancelRequested = true
    }

    fun startDownload(spec: LlmModelSpec, sourceId: String) {
        scope.launch { download(spec, sourceId) }
    }

    suspend fun download(spec: LlmModelSpec, sourceId: String) = mutex.withLock {
        cancelRequested = false
        val total = spec.bytes
        val target = modelFile(spec)
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        val done = part.length().coerceAtLeast(0L)
        _states.value += spec.id to ModelState.Downloading(done, total)
        var lastReported = -1L
        val report: (Long) -> Unit = { d ->
            if (d == total || d - lastReported >= 1_000_000L) {
                lastReported = d
                _states.value += spec.id to ModelState.Downloading(d, total)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                if (target.isFile && target.length() > 0) {
                    _states.value += spec.id to ModelState.Installed
                    return@withContext
                }
                target.parentFile?.mkdirs()
                part.parentFile?.mkdirs()
                if (part.length() > spec.bytes) {
                    java.io.RandomAccessFile(part, "rw").use { it.setLength(0) }
                }
                if (part.length() == spec.bytes && part.renameTo(target)) {
                    _states.value += spec.id to ModelState.Installed
                    return@withContext
                }
                val src = LlmDownloadSources.byId(sourceId)
                val url = src.fileUrl(spec.repo, spec.commit, spec.fileName)
                Log.i(TAG, "download ${spec.id} from $url resumed=$done")
                fetchFile(url, part) { fileDone -> report(fileDone) }
                if (!part.isFile || part.length() <= 0) {
                    throw IOException("下载文件为空")
                }
                if (part.length() != spec.bytes) {
                    throw IOException("下载不完整（${part.length()}/${spec.bytes} 字节），请重试续传")
                }
                if (!part.renameTo(target)) {
                    throw IOException("无法写入模型文件")
                }
                _states.value += spec.id to ModelState.Installed
            }
        } catch (e: Exception) {
            if (cancelRequested) {
                // 保留 .part，下次继续
                _states.value += spec.id to ModelState.NotInstalled
            } else {
                Log.w(TAG, "download ${spec.id} failed", e)
                _states.value += spec.id to ModelState.Failed(e.message ?: "下载失败")
            }
        }
    }

    suspend fun delete(spec: LlmModelSpec) = withContext(Dispatchers.IO) {
        runCatching { llmModelDir(context, spec).deleteRecursively() }
        _states.value += spec.id to ModelState.NotInstalled
    }

    /** 单文件下载：.part + Range 续传。服务器忽略 Range 时自动从头覆盖。 */
    private fun fetchFile(url: String, part: File, onProgress: (Long) -> Unit) {
        var existing = part.length().coerceAtLeast(0L)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "nostalgiaPod")
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val resuming = existing > 0 && response.code == 206
            if (!resuming) existing = 0L
            val body = response.body ?: throw IOException("无响应内容")
            body.byteStream().use { input ->
                java.io.RandomAccessFile(part, "rw").use { output ->
                    if (!resuming) output.setLength(0)
                    output.seek(if (resuming) existing else 0L)
                    var written = if (resuming) existing else 0L
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        if (cancelRequested) throw IOException("已取消")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        onProgress(written)
                    }
                }
            }
        }
    }
}
