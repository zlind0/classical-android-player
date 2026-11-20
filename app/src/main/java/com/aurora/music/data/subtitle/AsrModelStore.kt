package com.aurora.music.data.subtitle

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ASR 权重下载器：单文件 Range 续传，装到 filesDir/asr-models/<id>/。
 * 权重大（100–400 MB），所以每个文件都续传，失败重试不从头再来。
 */
class AsrModelStore(private val context: Context) {

    sealed interface ModelState {
        data object NotInstalled : ModelState
        data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState
        data object Installed : ModelState
        data class Failed(val message: String) : ModelState
    }

    data class DownloadSource(val id: String, val label: String, val baseUrl: String)

    companion object {
        const val SOURCE_MIRROR_ID = "mirror"
        const val SOURCE_HF_ID = "hf"

        val SOURCES = listOf(
            DownloadSource(SOURCE_MIRROR_ID, "hf-mirror.com（国内）", "https://hf-mirror.com"),
            DownloadSource(SOURCE_HF_ID, "huggingface.co（官方）", "https://huggingface.co"),
        )

        fun sourceById(id: String): DownloadSource = SOURCES.firstOrNull { it.id == id } ?: SOURCES[0]

        private const val TAG = "AsrModelStore"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mutex = Mutex()
    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    @Volatile private var cancelRequested = false

    /** 下载跑在 App 级作用域：退出设置页/切后台不中断，重进还能看到进度。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun stateOf(spec: AsrModelSpec): ModelState =
        _states.value[spec.id] ?: if (isInstalled(spec)) ModelState.Installed else ModelState.NotInstalled

    fun isInstalled(spec: AsrModelSpec): Boolean =
        spec.files.all { f -> File(asrModelDir(context, spec), f.name).let { it.isFile && it.length() > 0 } }

    fun modelDir(spec: AsrModelSpec): File = asrModelDir(context, spec)

    fun installedIds(): List<String> = AsrModelCatalog.all.filter { isInstalled(it) }.map { it.id }

    fun cancel() {
        cancelRequested = true
    }

    /** 有模型时返回第一个可用模型（优先用户选的，其次 SenseVoice）。 */
    fun pickUsable(preferredId: String): AsrModelSpec? =
        AsrModelCatalog.byId(preferredId)?.takeIf { isInstalled(it) }
            ?: AsrModelCatalog.all.firstOrNull { isInstalled(it) }

    fun installedBytes(): Long =
        AsrModelCatalog.all.filter { isInstalled(it) }.sumOf { spec ->
            spec.files.sumOf { File(asrModelDir(context, spec), it.name).length() }
        }

    fun startDownload(spec: AsrModelSpec, sourceId: String) {
        scope.launch { download(spec, sourceId) }
    }

    suspend fun download(spec: AsrModelSpec, sourceId: String, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        mutex.withLock {
            cancelRequested = false
            val total = spec.totalBytes
            var done = achievedBytes(spec)
            _states.value += spec.id to ModelState.Downloading(done, total)
            // 进度节流：每 512 KB 更新一次 StateFlow，避免每 256 KB 分配一次 map
            var lastReported = -1L
            val report: (Long, Long) -> Unit = { d, t ->
                if (d == t || d - lastReported >= 512 * 1024L) {
                    lastReported = d
                    _states.value += spec.id to ModelState.Downloading(d, t)
                    onProgress(d, t)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    val dir = asrModelDir(context, spec).apply { mkdirs() }
                    val baseUrl = sourceById(sourceId).baseUrl.trimEnd('/')
                    for (f in spec.files) {
                        val target = File(dir, f.name)
                        // 完整性由「.part 只在下载完成后才 rename」保证：
                        // 存在正式文件就说明上次已下完，不再按硬编码大小比对
                        // （大小只用于进度显示，避免记录值和真实值差几 KB 就判失败）。
                        if (target.isFile && target.length() > 0) {
                            done += target.length()
                            report(done, total)
                            continue
                        }
                        downloadOne(baseUrl, spec.repo, f, target) { fileDone ->
                            report(done + fileDone, total)
                        }
                        done = achievedBytes(spec)
                    }
                }
                // 顺手把 0.6 MB 的 VAD 也准备好，开字幕时不用再等网络
                runCatching { ensureVadModel() }
                if (isInstalled(spec)) {
                    _states.value += spec.id to ModelState.Installed
                } else {
                    _states.value += spec.id to ModelState.Failed("下载不完整，请重试")
                }
            } catch (e: Exception) {
                if (cancelRequested) {
                    // 取消：保留 .part，下次续传
                    _states.value += spec.id to ModelState.NotInstalled
                } else {
                    Log.w(TAG, "download ${spec.id} failed", e)
                    _states.value += spec.id to ModelState.Failed(e.message ?: "下载失败")
                }
            }
        }

    private fun achievedBytes(spec: AsrModelSpec): Long =
        spec.files.sumOf { f -> File(asrModelDir(context, spec), f.name).length() }

    private fun downloadOne(
        baseUrl: String,
        repo: String,
        file: AsrFile,
        target: File,
        onProgress: (Long) -> Unit,
    ) = fetchFile("$baseUrl/$repo/resolve/main/${file.name}?download=true", target, onProgress)

    /** 单文件下载：.part + Range 续传，成功后才 rename 成正式文件。 */
    private fun fetchFile(url: String, target: File, onProgress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.isFile) part.length() else 0L
        if (target.isFile && target.length() > 0) return
        if (target.isFile) {
            // 不完整的正式文件：搬去 .part 继续
            if (!target.renameTo(part)) target.delete()
            existing = if (part.isFile) part.length() else 0L
        }
        if (existing > 0) onProgress(existing)

        val builder = Request.Builder().url(url)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val request = builder.build()

        http.newCall(request).execute().use { resp ->
            if (resp.code == 416) {
                // 已经下完
                if (part.isFile && part.length() > 0) part.renameTo(target)
                onProgress(target.length())
                return
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} @ ${target.name}")
            val body = resp.body ?: throw IOException("empty body")
            val append = existing > 0 && resp.code == 206
            val startAt = if (append) existing else 0L
            if (!append && part.isFile) part.delete()

            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(startAt)
                var written = startAt
                val buf = ByteArray(256 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        if (cancelRequested) throw IOException("已取消")
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        written += n
                        onProgress(written)
                    }
                }
                raf.fd.sync()
            }
        }
        if (!part.renameTo(target)) throw IOException("无法写入 ${target.name}")
    }

    suspend fun delete(spec: AsrModelSpec) = withContext(Dispatchers.IO) {
        asrModelDir(context, spec).deleteRecursively()
        _states.value += spec.id to ModelState.NotInstalled
    }

    /**
     * VAD（语音切句）模型也不进安装包：首次用到时下载（629 KB），
     * 下载失败返回目标路径，由调用方判断文件是否存在并提示。
     */
    suspend fun ensureVadModel(): File = withContext(Dispatchers.IO) {
        val f = asrVadModelFile(context)
        if (f.isFile && f.length() > 0) return@withContext f
        f.parentFile?.mkdirs()
        runCatching {
            fetchFile(VadModelSpec.url, f) { /* 0.6 MB，进度可以忽略 */ }
        }.onFailure { Log.w(TAG, "download vad failed", it) }
        f
    }
}
