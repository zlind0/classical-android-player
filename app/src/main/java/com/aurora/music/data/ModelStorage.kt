package com.aurora.music.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 大模型（LLM 权重 + ASR 权重）的落盘位置。
 *
 * 两档：
 * - [ModelStorageMode.APP]：应用内部存储（filesDir），默认，卸载即清。
 * - [ModelStorageMode.ANDROID]：用户选的安卓共享目录（SD 卡 / Download 等）。
 *
 * 共享目录有两个入口，见 [ModelStorageAccess]：
 * - ALL_FILES：申请「所有文件访问」，然后用文件路径直接读写（推荐，原生库能 mmap）。
 * - SAF：系统目录选择器（ACTION_OPEN_DOCUMENT_TREE）。会把 tree Uri 反解成真实路径，
 *   所以能不能写取决于系统版本 / 是否同时有所有文件访问权限。
 *
 * 配置写在 filesDir 里的一个小 properties 文件：:llm 是独立进程，
 * DataStore 不保证跨进程可见，而原生引擎每次加载都要拿最新目录。
 */
enum class ModelStorageMode { APP, ANDROID }

enum class ModelStorageAccess { ALL_FILES, SAF }

data class ModelStorageConfig(
    val mode: ModelStorageMode = ModelStorageMode.APP,
    val access: ModelStorageAccess = ModelStorageAccess.ALL_FILES,
    /** SAF 返回的 tree Uri（仅展示 / 重新授权用，实际读写走真实路径）。 */
    val treeUri: String = "",
    /** 真实绝对路径；APP 模式留空。 */
    val path: String = "",
    /** 用户可见的目录名，例如「Download/models」。 */
    val label: String = "",
) {
    val isApp: Boolean get() = mode == ModelStorageMode.APP

    companion object {
        private const val TAG = "ModelStorage"
        private const val FILE_NAME = "model-storage.properties"
        private const val KEY_MODE = "mode"
        private const val KEY_ACCESS = "access"
        private const val KEY_TREE = "tree"
        private const val KEY_PATH = "path"
        private const val KEY_LABEL = "label"

        private val cache = AtomicReference<Pair<Long, ModelStorageConfig>?>(null)

        fun configFile(context: Context): File = File(context.filesDir, FILE_NAME)

        /** 读配置。带 mtime 缓存：UI 里会高频调用，但跨进程写入也能看见。 */
        fun load(context: Context): ModelStorageConfig {
            val file = configFile(context)
            val stamp = if (file.isFile) file.lastModified() else 0L
            cache.get()?.takeIf { it.first == stamp }?.let { return it.second }
            val cfg = readFile(file)
            cache.set(stamp to cfg)
            return cfg
        }

        fun save(context: Context, config: ModelStorageConfig) {
            val file = configFile(context)
            val props = Properties().apply {
                setProperty(KEY_MODE, config.mode.name)
                setProperty(KEY_ACCESS, config.access.name)
                setProperty(KEY_TREE, config.treeUri)
                setProperty(KEY_PATH, config.path)
                setProperty(KEY_LABEL, config.label)
            }
            runCatching {
                FileOutputStream(file).use { props.store(it, "nostalgiaPod model storage") }
                cache.set(file.lastModified() to config)
            }.onFailure { Log.w(TAG, "save config failed", it) }
        }

        private fun readFile(file: File): ModelStorageConfig {
            if (!file.isFile) return ModelStorageConfig()
            val props = Properties()
            runCatching { FileInputStream(file).use { props.load(it) } }
                .onFailure { Log.w(TAG, "read config failed", it) }
            val mode = runCatching { ModelStorageMode.valueOf(props.getProperty(KEY_MODE).orEmpty()) }
                .getOrDefault(ModelStorageMode.APP)
            val access = runCatching { ModelStorageAccess.valueOf(props.getProperty(KEY_ACCESS).orEmpty()) }
                .getOrDefault(ModelStorageAccess.ALL_FILES)
            val path = props.getProperty(KEY_PATH).orEmpty()
            // 安卓目录必须带真实路径，否则退回应用存储，避免半截配置把模型「藏起来」。
            if (mode == ModelStorageMode.ANDROID && path.isBlank()) return ModelStorageConfig()
            return ModelStorageConfig(
                mode = mode,
                access = access,
                treeUri = props.getProperty(KEY_TREE).orEmpty(),
                path = path,
                label = props.getProperty(KEY_LABEL).orEmpty(),
            )
        }

        /** 应用内部存储根：llm-models / asr-models 都挂在它下面。 */
        fun appRoot(context: Context): File = context.filesDir

        fun rootOf(context: Context, config: ModelStorageConfig): File =
            if (config.mode == ModelStorageMode.APP || config.path.isBlank()) appRoot(context)
            else File(config.path)

        /** 模型目录根（当前配置）。:llm 进程也走这里。 */
        fun rootDir(context: Context): File = rootOf(context, load(context))

        fun llmModelsDir(context: Context): File = File(rootDir(context), "llm-models")

        fun asrModelsDir(context: Context): File = File(rootDir(context), "asr-models")

        /** LiteRT-LM 编译缓存放在共享目录里时用的子目录名。 */
        const val LITERT_CACHE_DIR = "litert-cache"

        /** 应用存储模式下的编译缓存（系统可回收）。 */
        fun appLitertCacheDir(context: Context): File = File(context.cacheDir, "litert-lm")

        /**
         * LiteRT-LM 的编译缓存目录。
         *
         * 缓存是派生数据（按模型文件指纹命名），本身不迁移，但位置要跟着模型走：
         * 模型放在共享目录时，缓存也放共享目录，免得「模型搬到卡上、缓存还占着内部存储」。
         * 共享目录不可写（权限被撤销、卡拔了）就退回应用缓存，别让翻译直接失败。
         */
        fun litertCacheDirOf(context: Context, config: ModelStorageConfig): File {
            if (config.mode == ModelStorageMode.APP || config.path.isBlank()) return appLitertCacheDir(context)
            val dir = File(rootOf(context, config), LITERT_CACHE_DIR)
            val ready = runCatching { dir.isDirectory || dir.mkdirs() }.getOrDefault(false)
            return if (ready) dir else appLitertCacheDir(context)
        }

        fun litertCacheDir(context: Context): File = litertCacheDirOf(context, load(context))

        fun displayPath(context: Context, config: ModelStorageConfig): String =
            if (config.mode == ModelStorageMode.APP) appRoot(context).absolutePath
            else config.path
    }
}

/** 便捷入口：各处（含 :llm 进程）拿模型目录都走这里。 */
object ModelStorage {
    fun config(context: Context): ModelStorageConfig = ModelStorageConfig.load(context)

    fun save(context: Context, config: ModelStorageConfig) = ModelStorageConfig.save(context, config)

    fun rootDir(context: Context): File = ModelStorageConfig.rootDir(context)

    fun llmModelsDir(context: Context): File = ModelStorageConfig.llmModelsDir(context)

    fun asrModelsDir(context: Context): File = ModelStorageConfig.asrModelsDir(context)

    /** LiteRT-LM 编译缓存目录（跟着模型根走；应用存储时仍是 cacheDir/litert-lm）。 */
    fun litertCacheDir(context: Context): File = ModelStorageConfig.litertCacheDir(context)
}

/** SAF tree Uri → 真实路径。只对内置存储 / 可挂载卷的常规目录有效。 */
fun resolveSafTreePath(treeUri: Uri): File? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val volume = parts[0]
    val relative = parts.getOrElse(1) { "" }
    val base = when (volume) {
        "primary", "home" -> Environment.getExternalStorageDirectory()
        else -> File("/storage", volume)
    }
    return if (relative.isBlank()) base else File(base, relative)
}

/** 目录是否真的能写：原生库只认文件路径，所以必须用 File API 试一次。 */
fun canWriteDirectory(dir: File): Boolean = runCatching {
    if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
    val probe = File(dir, ".nostalgia-write-test")
    probe.writeText("ok")
    probe.delete()
    true
}.getOrDefault(false)

/**
 * 大模型目录切换 + 迁移。
 *
 * 迁移策略：先把旧目录的文件复制到新目录（此时旧目录仍然完整，配置也还指向旧目录，
 * 中途失败不会丢模型），全部成功后才写配置，最后删掉旧文件。所以任何时刻打断都安全。
 */
class ModelStorageManager(private val context: Context) {

    enum class ConflictPolicy { OVERWRITE, KEEP_TARGET }

    sealed interface MigrationState {
        data object Idle : MigrationState
        data class Running(
            val doneBytes: Long,
            val totalBytes: Long,
            val current: String,
        ) : MigrationState
        data class Done(val moved: Int, val kept: Int) : MigrationState
        data class Failed(val message: String) : MigrationState
    }

    private companion object {
        const val TAG = "ModelStorageManager"
        val SUB_DIRS = listOf("llm-models", "asr-models")
        const val SKIP_FILE = "gpu-crashed"
        const val COPY_SUFFIX = ".migrating"
    }

    private val _config = MutableStateFlow(ModelStorageConfig.load(context))
    val config: StateFlow<ModelStorageConfig> = _config.asStateFlow()

    private val _migration = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migration: StateFlow<MigrationState> = _migration.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun currentRoot(): File = ModelStorageConfig.rootOf(context, _config.value)

    fun targetRoot(target: ModelStorageConfig): File = ModelStorageConfig.rootOf(context, target)

    /** 新目录里已经存在、且非空的同名文件（相对路径）。 */
    suspend fun findConflicts(target: ModelStorageConfig): List<String> = withContext(Dispatchers.IO) {
        val from = currentRoot()
        val to = targetRoot(target)
        if (from.absolutePath == to.absolutePath) return@withContext emptyList()
        modelFiles(from).filter { rel ->
            val dst = File(to, rel)
            dst.isFile && dst.length() > 0
        }
    }

    fun startMigration(target: ModelStorageConfig, policy: ConflictPolicy) {
        if (_migration.value is MigrationState.Running) return
        scope.launch { migrate(target, policy) }
    }

    fun resetState() {
        if (_migration.value !is MigrationState.Running) _migration.value = MigrationState.Idle
    }

    private suspend fun migrate(target: ModelStorageConfig, policy: ConflictPolicy) =
        withContext(Dispatchers.IO) {
            val from = currentRoot()
            val to = targetRoot(target)
            if (from.absolutePath == to.absolutePath) {
                ModelStorageConfig.save(context, target)
                _config.value = target
                _migration.value = MigrationState.Done(0, 0)
                return@withContext
            }
            // 上次被打断留下的半截临时文件：清掉，避免白占空间。
            runCatching {
                SUB_DIRS.forEach { sub ->
                    File(to, sub).walkTopDown()
                        .filter { it.isFile && it.name.endsWith(COPY_SUFFIX) }
                        .forEach { it.delete() }
                }
            }
            val files = modelFiles(from)
            val total = files.sumOf { File(from, it).length() }
            var done = 0L
            var moved = 0
            var kept = 0
            val finished = ArrayList<String>(files.size)
            _migration.value = MigrationState.Running(0, total, "")
            try {
                for (rel in files) {
                    val src = File(from, rel)
                    if (!src.isFile) continue
                    val size = src.length()
                    val dst = File(to, rel)
                    dst.parentFile?.mkdirs()
                    val hasTarget = dst.isFile && dst.length() > 0
                    if (hasTarget && policy == ConflictPolicy.KEEP_TARGET) {
                        // 保留新地址里已有的文件，旧的那份等配置落盘后删掉。
                        kept++
                    } else {
                        // 同一分区时 rename 是瞬间完成的，跨分区再退回复制。
                        val renamed = !hasTarget && src.renameTo(dst)
                        if (!renamed) {
                            var copied = 0L
                            copyFile(src, dst) { n ->
                                copied += n
                                _migration.value =
                                    MigrationState.Running(done + copied, total, rel)
                            }
                        }
                        moved++
                    }
                    done += size
                    finished += rel
                    _migration.value = MigrationState.Running(done, total, rel)
                }
                ModelStorageConfig.save(context, target)
                _config.value = target
                // 编译缓存按模型文件的 mtime/size 命名，跨目录复制会改 mtime，旧缓存对不上号。
                // 所以不搬缓存，直接把新旧两处的都删掉，让引擎按需重新编译。
                clearLitertCaches(from, to, moved)
                // 配置已经指向新目录，旧文件可以删了；删不掉的（只读卷）留着也不影响。
                finished.forEach { rel -> runCatching { File(from, rel).delete() } }
                pruneEmptyDirs(from)
                _migration.value = MigrationState.Done(moved, kept)
            } catch (e: Exception) {
                Log.w(TAG, "migrate models failed", e)
                _migration.value = MigrationState.Failed(e.message ?: "迁移失败")
            }
        }

    /** 旧根下所有要迁移的模型文件（相对路径）。 */
    private fun modelFiles(root: File): List<String> {
        val out = ArrayList<String>()
        SUB_DIRS.forEach { sub ->
            val dir = File(root, sub)
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown()
                .filter { it.isFile && it.name != SKIP_FILE && !it.name.endsWith(COPY_SUFFIX) }
                .forEach { file ->
                    out += file.relativeTo(root).path
                }
        }
        return out
    }

    /**
     * 先写 `<name>.migrating`，校验长度后再改名成正式文件：
     * 中途被打断时目标目录里不会留下「看起来完整、实际是半截」的模型文件。
     */
    private fun copyFile(src: File, dst: File, onBytes: (Long) -> Unit) {
        val tmp = File(dst.parentFile, dst.name + COPY_SUFFIX)
        try {
            FileInputStream(src).use { input ->
                FileOutputStream(tmp, false).use { output ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                    output.flush()
                }
            }
            if (tmp.length() != src.length()) throw IOException("复制不完整：${dst.name}")
            if (!tmp.renameTo(dst)) {
                // 覆盖时部分文件系统不支持 rename 覆盖已有文件。
                dst.delete()
                if (!tmp.renameTo(dst)) throw IOException("无法写入 ${dst.name}")
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
    }

    private fun pruneEmptyDirs(root: File) {
        SUB_DIRS.forEach { sub ->
            val dir = File(root, sub)
            runCatching { dir.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() } }
        }
    }

    /** 清掉切换前后两套位置的编译缓存（应用缓存 + 两个模型根下的 litert-cache）。 */
    private fun clearLitertCaches(from: File, to: File, moved: Int) {
        // 旧位置的缓存对新目录没有意义，直接删；模型下次加载时按需重编译。
        runCatching { ModelStorageConfig.appLitertCacheDir(context).deleteRecursively() }
        runCatching { File(from, ModelStorageConfig.LITERT_CACHE_DIR).deleteRecursively() }
        // 目标目录里上一轮留下的缓存只对原来的文件有效。复制过去的模型 mtime 变了，
        // 那些条目已经对不上号；只有真搬过文件时才值得清（否则保留原有缓存）。
        if (moved > 0) {
            runCatching { File(to, ModelStorageConfig.LITERT_CACHE_DIR).deleteRecursively() }
        }
    }
}
