package com.aurora.music.data.subtitle

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** 一条字幕：章节内相对秒。 */
data class SubtitleCue(val start: Double, val end: Double, val text: String)

/**
 * sherpa-onnx 的薄封装：Silero-VAD 切句 + 离线模型识别。
 *
 * 每次 [reset] 都重造 VAD 实例，这样 SpeechSegment.start 一定是「本次解码起点」
 * 的采样偏移，映射到章节时间只需要加 [epochBaseSec]。
 */
class SubtitleEngine(
    spec: AsrModelSpec,
    modelDir: File,
    vadModel: File,
    private val threads: Int,
) : AutoCloseable {

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var epochBaseSec = 0.0
    private var leftover = FloatArray(0)

    private val spec = spec
    private val modelDir = modelDir
    private val vadModel = vadModel

    /** 引擎创建 + 首次 VAD 构造；返回是否成功。 */
    fun prepare(): Boolean {
        if (recognizer != null) return true
        return try {
            val cfg = OfflineRecognizerConfig().apply {
                featConfig = com.k2fsa.sherpa.onnx.FeatureConfig().apply { sampleRate = 16000; featureDim = 80 }
                modelConfig = OfflineModelConfig().apply {
                    tokens = File(modelDir, spec.tokens).absolutePath
                    numThreads = threads
                    provider = "cpu"
                    when (spec.family) {
                        AsrFamily.SENSE_VOICE -> senseVoice = OfflineSenseVoiceModelConfig().apply {
                            model = File(modelDir, spec.files.first { it.name.endsWith(".onnx") }.name).absolutePath
                            language = "auto"
                            useInverseTextNormalization = true
                        }
                        AsrFamily.WHISPER -> whisper = OfflineWhisperModelConfig().apply {
                            encoder = File(modelDir, spec.encoder).absolutePath
                            decoder = File(modelDir, spec.decoder).absolutePath
                            language = spec.whisperLanguage
                            task = "transcribe"
                        }
                    }
                }
                decodingMethod = "greedy_search"
            }
            recognizer = OfflineRecognizer(null, cfg)
            reset(0.0)
            true
        } catch (t: Throwable) {
            android.util.Log.w("SubtitleEngine", "prepare failed", t)
            close()
            false
        }
    }

    /** 丢弃 VAD 状态，从 [baseSec] 重新开始计数。 */
    fun reset(baseSec: Double) {
        epochBaseSec = baseSec
        leftover = FloatArray(0)
        runCatching { vad?.release() }
        vad = null
        if (recognizer == null) return
        val vc = VadModelConfig().apply {
            sileroVadModelConfig = SileroVadModelConfig().apply {
                model = vadModel.absolutePath
                threshold = 0.5f
                minSilenceDuration = 0.35f
                minSpeechDuration = 0.25f
                maxSpeechDuration = 12f
                windowSize = 512
            }
            sampleRate = 16000
            numThreads = 1
            provider = "cpu"
        }
        vad = Vad(null, vc)
    }

    /** 喂 16 kHz 单声道采样，返回本次切出来的整句字幕（绝对章节秒）。 */
    fun feed(pcm: FloatArray, flush: Boolean = false): List<SubtitleCue> {
        val detector = vad ?: return emptyList()
        val rec = recognizer ?: return emptyList()

        // VAD 的窗口固定 512 采样（32 ms）：不足一块的先攒着，保证每次喂满一块，
        // 不依赖 C API 对任意长度的容忍度。
        val data = if (leftover.isEmpty()) pcm else leftover + pcm
        var i = 0
        while (i + WINDOW <= data.size) {
            detector.acceptWaveform(data.copyOfRange(i, i + WINDOW))
            i += WINDOW
        }
        leftover = if (i < data.size) data.copyOfRange(i, data.size) else FloatArray(0)
        if (flush) {
            if (leftover.isNotEmpty()) {
                val padded = FloatArray(WINDOW)
                leftover.copyInto(padded, 0, 0, leftover.size.coerceAtMost(WINDOW))
                detector.acceptWaveform(padded)
                leftover = FloatArray(0)
            }
            detector.flush()
        }
        val out = ArrayList<SubtitleCue>(4)
        while (!detector.empty()) {
            val seg = detector.front()
            detector.pop()
            val text = try {
                val stream = rec.createStream()
                stream.acceptWaveform(seg.samples, 16000)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } catch (t: Throwable) {
                android.util.Log.w("SubtitleEngine", "decode failed", t)
                ""
            }
            if (text.isNotBlank()) {
                val start = epochBaseSec + seg.start / 16000.0
                out += SubtitleCueSplitter.split(text, start, start + seg.samples.size / 16000.0)
            }
        }
        return out
    }

    override fun close() {
        runCatching { vad?.release() }
        runCatching { recognizer?.release() }
        vad = null
        recognizer = null
    }

    private companion object {
        /** Silero VAD 的窗口：512 采样 = 32 ms @16 kHz。 */
        const val WINDOW = 512
    }
}
