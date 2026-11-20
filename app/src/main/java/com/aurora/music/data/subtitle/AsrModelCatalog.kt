package com.aurora.music.data.subtitle

import android.content.Context
import java.io.File

/**
 * 离线字幕（ASR）模型目录。
 *
 * 运行时（sherpa-onnx AAR）随 APK 打包；权重不打包，按需从 HuggingFace / hf-mirror 下载。
 * ModelScope 上的 iic/SenseVoiceSmall 只有 PyTorch 权重（model.pt），Android 需要 sherpa-onnx
 * 导出的 ONNX，所以下载源只有 HF 系（含国内镜像）。
 */
enum class AsrFamily { SENSE_VOICE, WHISPER }

data class AsrFile(val name: String, val bytes: Long)

data class AsrModelSpec(
    val id: String,
    val title: String,
    val family: AsrFamily,
    /** HuggingFace 仓库（相对路径，源地址可切 hf-mirror）。 */
    val repo: String,
    val files: List<AsrFile>,
    val tokens: String,
    /** whisper 专用：编码器 / 解码器文件名。 */
    val encoder: String = "",
    val decoder: String = "",
    val languages: String,
    val note: String,
    /** whisper 语言参数：.en 模型必须 "en"；多语言留空 = 自动。 */
    val whisperLanguage: String = "",
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val sizeMb: Int get() = (totalBytes / 1_000_000L).toInt()
}

object AsrModelCatalog {

    const val DEFAULT_MODEL_ID = "sensevoice"

    val senseVoice = AsrModelSpec(
        id = "sensevoice",
        title = "SenseVoiceSmall（推荐）",
        family = AsrFamily.SENSE_VOICE,
        repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        files = listOf(
            AsrFile("model.int8.onnx", 239_233_841L),
            AsrFile("tokens.txt", 311_000L),
        ),
        tokens = "tokens.txt",
        languages = "中 / 英 / 日 / 韩 / 粤",
        note = "最快（约 40 倍实时），多语言一个模型搞定；英文人名、术语容易错。",
    )

    val whisperTinyEn = AsrModelSpec(
        id = "whisper-tiny-en",
        title = "Whisper tiny.en",
        family = AsrFamily.WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-tiny.en",
        files = listOf(
            AsrFile("tiny.en-encoder.int8.onnx", 12_900_000L),
            AsrFile("tiny.en-decoder.int8.onnx", 89_900_000L),
            AsrFile("tiny.en-tokens.txt", 816_000L),
        ),
        tokens = "tiny.en-tokens.txt",
        encoder = "tiny.en-encoder.int8.onnx",
        decoder = "tiny.en-decoder.int8.onnx",
        languages = "英语",
        note = "体积最小、速度快；准确度一般，适合只要“大概在说什么”。",
        whisperLanguage = "en",
    )

    val whisperBaseEn = AsrModelSpec(
        id = "whisper-base-en",
        title = "Whisper base.en",
        family = AsrFamily.WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-base.en",
        files = listOf(
            AsrFile("base.en-encoder.int8.onnx", 29_100_000L),
            AsrFile("base.en-decoder.int8.onnx", 130_700_000L),
            AsrFile("base.en-tokens.txt", 816_000L),
        ),
        tokens = "base.en-tokens.txt",
        encoder = "base.en-encoder.int8.onnx",
        decoder = "base.en-decoder.int8.onnx",
        languages = "英语",
        note = "英文准确度好、大小适中；专有名词明显好于 SenseVoice。",
        whisperLanguage = "en",
    )

    val whisperSmallEn = AsrModelSpec(
        id = "whisper-small-en",
        title = "Whisper small.en",
        family = AsrFamily.WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-small.en",
        files = listOf(
            AsrFile("small.en-encoder.int8.onnx", 112_400_000L),
            AsrFile("small.en-decoder.int8.onnx", 262_200_000L),
            AsrFile("small.en-tokens.txt", 816_000L),
        ),
        tokens = "small.en-tokens.txt",
        encoder = "small.en-encoder.int8.onnx",
        decoder = "small.en-decoder.int8.onnx",
        languages = "英语",
        note = "英文最准（Anaximander 这种也能对）；体积大、速度约为 SenseVoice 的 1/10。",
        whisperLanguage = "en",
    )

    val whisperBase = AsrModelSpec(
        id = "whisper-base",
        title = "Whisper base（多语言）",
        family = AsrFamily.WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-base",
        files = listOf(
            AsrFile("base-encoder.int8.onnx", 29_100_000L),
            AsrFile("base-decoder.int8.onnx", 130_700_000L),
            AsrFile("base-tokens.txt", 816_000L),
        ),
        tokens = "base-tokens.txt",
        encoder = "base-encoder.int8.onnx",
        decoder = "base-decoder.int8.onnx",
        languages = "99 种语言",
        note = "多语言，准确度接近 base.en；不需要中日韩粤之外的语言时够用。",
    )

    val whisperSmall = AsrModelSpec(
        id = "whisper-small",
        title = "Whisper small（多语言）",
        family = AsrFamily.WHISPER,
        repo = "csukuangfj/sherpa-onnx-whisper-small",
        files = listOf(
            AsrFile("small-encoder.int8.onnx", 112_400_000L),
            AsrFile("small-decoder.int8.onnx", 262_200_000L),
            AsrFile("small-tokens.txt", 816_000L),
        ),
        tokens = "small-tokens.txt",
        encoder = "small-encoder.int8.onnx",
        decoder = "small-decoder.int8.onnx",
        languages = "99 种语言",
        note = "多语言里最准的一档；体积和耗时也最大。",
    )

    val all: List<AsrModelSpec> = listOf(
        senseVoice, whisperBaseEn, whisperSmallEn, whisperTinyEn, whisperBase, whisperSmall,
    )

    fun byId(id: String): AsrModelSpec? = all.firstOrNull { it.id == id }
}

/** 模型落盘位置：filesDir/asr-models/<id>/；VAD 也放在同一棵树下。 */
fun asrModelDir(context: Context, id: String): File =
    File(File(context.filesDir, "asr-models"), id)

fun asrVadModelFile(context: Context): File =
    File(File(context.filesDir, "asr-models"), "vad/silero_vad.onnx")

fun asrModelDir(context: Context, spec: AsrModelSpec): File = asrModelDir(context, spec.id)

/**
 * Silero VAD（语音切句，629 KB）也不进安装包，和权重一样按需下载。
 * sherpa-onnx 只吃文件路径，所以落到 filesDir/asr-models/vad/。
 */
object VadModelSpec {
    const val fileName = "silero_vad.onnx"
    const val bytes = 643_854L

    /**
     * 这个文件没有 HF 仓库（只有 GitHub release 的 asr-models 标签下有一份），
     * 和模型下载源无关，固定走 GitHub（支持 Range 续传）。
     */
    const val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
}
