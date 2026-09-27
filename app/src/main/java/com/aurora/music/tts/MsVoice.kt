package com.aurora.music.tts

/**
 * 内置微软离线语音（4 个，随 APK 完整打包，开箱即用）：
 * displayName = Speaker.param 首行（setSpeechSynthesisVoice 用名），
 * inlineKey 仅云哲有（param 第二行），其余走设备 license。
 */
data class MsVoice(
    /** 语音目录名 = SSML voice 名，如 zh-CN-XiaoxiaoNeural */
    val code: String,
    val showName: String,
    val locale: String,
    /** 0 女 / 1 男 */
    val gender: Int,
    val displayName: String,
    /** null = 设备 license；非 null = 包内 inline key */
    val inlineKey: String? = null,
) {
    fun label(): String = "$showName · $locale"
}

object MsVoices {
    const val DEFAULT = "zh-CN-XiaoxiaoNeural"

    val ALL: List<MsVoice> = listOf(
        MsVoice(
            "zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN", 0,
            "Microsoft Xiaoxiao (Natural) - Chinese (Simplified, China)",
        ),
        MsVoice(
            "zh-TW-YunJheNeural", "云哲", "zh-TW", 0,
            "Microsoft Server Speech Text to Speech Voice (zh-TW, YunJheNeural)",
            "BGJJwTRVfhRYIZq0xtySkIQlJbmBDsX6GsVyDRFHM0AzOjRvZ7ELI5kgzCUWYAKhTk99WDj5aOSWY@KHnffCDqlB008FmEUZHXM2lmKaFnfffnu4r8eiLUyYuH1uf4fSYA39OKQUZ9wY",
        ),
        MsVoice(
            "en-GB-RyanNeural", "Ryan", "en-GB", 1,
            "Microsoft Ryan (Natural) - English (United Kingdom)",
        ),
        MsVoice(
            "en-US-JennyNeural", "Jenny", "en-US", 0,
            "Microsoft Jenny (Natural) - English (United States)",
        ),
    )

    fun byCode(code: String): MsVoice? = ALL.firstOrNull { it.code == code }

    fun licenseFor(v: MsVoice): String {
        val raw = v.inlineKey ?: org.nobody.multitts.tts.jni.SpeexBridge.getLicense(0)
        return if (raw.startsWith("Key:")) raw else "Key:$raw"
    }
}
