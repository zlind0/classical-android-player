package com.aurora.music.data.llm

import android.os.Build

/**
 * 本地大模型（LiteRT-LM）的运行条件。
 *
 * LiteRT-LM 的 AAR 里 minSdk 固定为 24，而且只带 arm64-v8a / x86_64 的 JNI 库。
 * App 本身从 minSdk 23 起可以运行，但本地大模型在 Android 6.x 和 32 位设备上
 * 拉不起来（类校验失败 / UnsatisfiedLinkError），因此：
 * - UI 侧用 [isSupported] 隐藏翻译入口和模型设置；
 * - [com.aurora.music.data.llm.service.LlmClient] 在支持前不 bind :llm 进程。
 *
 * 放在独立的 object 里，避免低版本设备加载任何引用 litertlm 的类。
 */
object LlmCapability {

    /** LiteRT-LM AAR 声明的 minSdk。 */
    const val MIN_SDK = Build.VERSION_CODES.N // 24

    private val NATIVE_ABIS = setOf("arm64-v8a", "x86_64")

    /** 本机是否有 LiteRT-LM 的 JNI 库（arm64 / x86_64）。 */
    val hasNativeRuntime: Boolean by lazy {
        Build.SUPPORTED_ABIS.any { it in NATIVE_ABIS }
    }

    /** 本机能否使用本地大模型。 */
    val isSupported: Boolean by lazy {
        Build.VERSION.SDK_INT >= MIN_SDK && hasNativeRuntime
    }

    /** 不可用原因（给设置页显示）；可用时为 null。 */
    val unsupportedReason: String?
        get() = when {
            isSupported -> null
            Build.VERSION.SDK_INT < MIN_SDK -> "本地大模型需要 Android 7.0 及以上。"
            else -> "本地大模型需要 64 位设备（arm64-v8a）。"
        }
}