package com.aurora.music.data.llm

import android.content.Context
import java.io.File

/**
 * 多模态大模型目录（LiteRT-LM .litertlm）。
 *
 * 运行时随 APK（litertlm-android AAR）；权重按需从 HuggingFace / hf-mirror 下载。
 * 这里只收录原生支持音频输入、能直接做「语音 → 文本翻译」的 Gemma 4 / Gemma 3n 系列。
 */
data class LlmModelSpec(
    val id: String,
    val title: String,
    /** HuggingFace 仓库 id。下载源可以切到 hf-mirror。 */
    val repo: String,
    val fileName: String,
    val bytes: Long,
    /** 固定 commit，避免 main 上的权重更新导致同一模型文件大小变化。 */
    val commit: String,
    /** 官方建议的最低设备内存（GB）。仅用于提示，不做硬性拦截。 */
    val minRamGb: Int,
    val note: String,
) {
    val sizeMb: Int get() = (bytes / 1_000_000L).toInt()
    val sizeGb: String get() = String.format("%.1f GB", bytes / 1_000_000_000.0)
    val shortTitle: String get() = title.substringBefore("（")
}

object LlmModelCatalog {

    /** Gemma 4 的音频输入是 30 秒/段；超过会被截断，所以目标翻译时长上限固定 30。 */
    const val MAX_AUDIO_SECONDS = 30

    val gemma4E2b = LlmModelSpec(
        id = "gemma4-e2b",
        title = "Gemma 4 E2B（推荐）",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        bytes = 2_588_147_712L,
        commit = "6e5c4f1e395deb959c494953478fa5cec4b8008f",
        minRamGb = 8,
        note = "约 2.6 GB，8 GB 内存起步；支持语音输入，速度和内存占用更友好。",
    )

    val gemma4E4b = LlmModelSpec(
        id = "gemma4-e4b",
        title = "Gemma 4 E4B",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it.litertlm",
        bytes = 3_659_530_240L,
        commit = "28299f30ee4d43294517a4ac93abd6163412f07f",
        minRamGb = 12,
        note = "约 3.7 GB，12 GB 内存起步；翻译质量更好，加载和推理更慢。",
    )

    // 旧版 Gemma 3n 的权重仓库是 GatedRepo，需要 HF 登录授权；这里只保留可匿名下载的
    // Gemma 4 两档。以后要加新模型，往 all 里追加一个 LlmModelSpec 即可。
    val all: List<LlmModelSpec> = listOf(gemma4E2b, gemma4E4b)

    fun byId(id: String): LlmModelSpec? = all.firstOrNull { it.id == id }
}

/** 权重落盘位置：filesDir/llm-models/<id>/。 */
fun llmModelDir(context: Context, id: String): File =
    File(File(context.filesDir, "llm-models"), id)

/**
 * GPU 上一次把 :llm 进程带崩时留下的标记。
 *
 * LiteRT-LM 的 GPU 后端跑在 native 里，部分 Adreno 机型的 Vulkan 驱动会在
 * `vkCreateComputePipelines` 里直接 SIGSEGV，Java 层根本 catch 不到，只能靠
 * 落盘标记让下一次加载改用 CPU。只有在一次 GPU 推理成功后才会删除。
 */
fun llmGpuCrashMarker(context: Context): File =
    File(File(context.filesDir, "llm-models"), "gpu-crashed")

fun llmModelDir(context: Context, spec: LlmModelSpec): File = llmModelDir(context, spec.id)

/** 模型下载源：HF 系走 /resolve/<commit>/，ModelScope 走 /models/.../resolve/master/。 */
data class LlmDownloadSource(
    val id: String,
    val label: String,
    val baseUrl: String,
    val kind: Kind,
) {
    enum class Kind { HF, MODELSCOPE }

    fun fileUrl(repo: String, commit: String, fileName: String): String = when (kind) {
        Kind.HF -> "$baseUrl/$repo/resolve/$commit/$fileName?download=true"
        Kind.MODELSCOPE -> "$baseUrl/models/$repo/resolve/master/$fileName"
    }
}

object LlmDownloadSources {
    const val MIRROR_ID = "mirror"
    const val HF_ID = "hf"
    const val MODELSCOPE_ID = "modelscope"

    val all = listOf(
        LlmDownloadSource(
            MIRROR_ID,
            "hf-mirror.com（国内）",
            "https://hf-mirror.com",
            LlmDownloadSource.Kind.HF,
        ),
        LlmDownloadSource(
            HF_ID,
            "huggingface.co（官方）",
            "https://huggingface.co",
            LlmDownloadSource.Kind.HF,
        ),
        LlmDownloadSource(
            MODELSCOPE_ID,
            "ModelScope（国内）",
            "https://modelscope.cn",
            LlmDownloadSource.Kind.MODELSCOPE,
        ),
    )

    fun byId(id: String): LlmDownloadSource =
        all.firstOrNull { it.id == id } ?: all[0]
}
