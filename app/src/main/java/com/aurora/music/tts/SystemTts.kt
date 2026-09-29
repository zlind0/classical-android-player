package com.aurora.music.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 系统 TTS 引擎条目（默认引擎排第一，电子书与歌曲介绍共用）。 */
data class TtsEngineInfo(val packageName: String, val label: String, val isDefault: Boolean)

/** 微软离线 runtime 是否可用（so 缺失直接抛 UnsatisfiedLinkError）。 */
fun msInternalAvailable(): Boolean = runCatching {
    Class.forName("com.microsoft.cognitiveservices.speech.SpeechSynthesizer")
    org.nobody.multitts.tts.jni.SpeexBridge.getLicense(0)
    true
}.getOrDefault(false)

/**
 * 系统全部 TTS 引擎：TextToSpeech.getEngines 与 PackageManager 直查取并集
 * （有些引擎如 MultiTTS 在前者里拿不到 label 甚至整条缺失），默认引擎排第一。
 */
suspend fun listSystemEngines(context: Context): List<TtsEngineInfo> {
    val app = context.applicationContext
    val apiEngines = mutableListOf<Pair<String, String?>>()
    var def: String? = null
    withSystemTts(app, null) { tts ->
        def = runCatching { tts.defaultEngine }.getOrNull()
        runCatching { tts.engines.orEmpty() }.getOrDefault(emptyList())
            .forEach { apiEngines += it.name to it.label }
    }
    val pmServices = mutableListOf<Pair<String, String?>>()
    runCatching {
        val pm = app.packageManager
        pm.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0).forEach { r ->
            val pkg = r.serviceInfo?.packageName ?: return@forEach
            pmServices += pkg to runCatching { r.loadLabel(pm)?.toString() }.getOrNull()
        }
    }
    android.util.Log.d("TtsEngines", "api=$apiEngines pm=$pmServices default=$def")
    return mergeSystemTtsEngines(apiEngines, pmServices, def)
        .map { (pkg, label) -> TtsEngineInfo(pkg, label, pkg == def) }
}

/**
 * 合并两路引擎列表（纯函数，可单测）：空包名丢弃；API 在先，
 * PM 只补 API 缺失的名字（含 API 直接拿包名当 label 的情况）；默认排第一，其余按 label 排。
 */
internal fun mergeSystemTtsEngines(
    apiEngines: List<Pair<String, String?>>,
    pmServices: List<Pair<String, String?>>,
    defaultPackage: String?,
): List<Pair<String, String>> {
    val merged = LinkedHashMap<String, String>()
    fun put(pkg0: String, label0: String?) {
        val pkg = pkg0.trim()
        if (pkg.isEmpty()) return
        val label = label0?.trim().orEmpty()
        val prev = merged[pkg]
        merged[pkg] = when {
            prev == null -> label.ifEmpty { pkg }
            prev == pkg && label.isNotEmpty() -> label
            prev.isEmpty() && label.isNotEmpty() -> label
            else -> prev
        }
    }
    apiEngines.forEach { (p, l) -> put(p, l) }
    pmServices.forEach { (p, l) -> put(p, l) }
    return merged.map { (pkg, label) -> pkg to label.ifEmpty { pkg } }
        .sortedWith(compareBy<Pair<String, String>> { it.first != defaultPackage }.thenBy { it.second })
}

/** 指定系统引擎的全部音色（null/"" = 系统默认引擎实例）。 */
suspend fun systemVoicesOf(context: Context, enginePkg: String?): List<Voice> =
    withSystemTts(context.applicationContext, enginePkg?.takeIf { it.isNotBlank() }) { tts ->
        tts.voices?.toList().orEmpty()
    } ?: emptyList()

/** 建临时系统 TTS 实例执行一段操作后销毁（查引擎/音色列表用）。 */
private suspend fun <T> withSystemTts(
    context: Context,
    enginePkg: String?,
    block: suspend (TextToSpeech) -> T,
): T? {
    val tts = suspendCancellableCoroutine<TextToSpeech?> { cont ->
        var t: TextToSpeech? = null
        t = if (enginePkg != null) {
            TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS && cont.isActive) cont.resume(t)
                else if (cont.isActive) cont.resume(null)
            }, enginePkg)
        } else {
            TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS && cont.isActive) cont.resume(t)
                else if (cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { runCatching { t?.shutdown() } }
    } ?: return null
    try {
        return block(tts)
    } finally {
        runCatching { tts.shutdown() }
    }
}
