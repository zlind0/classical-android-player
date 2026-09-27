package org.nobody.multitts.tts.jni

/**
 * 最小 JNI 桥：绑定 `libspeexdsp.so` 中的
 * `Java_org_nobody_multitts_tts_jni_SpeexBridge_getLicense`。
 * JNI 按包名+类名+方法名绑定，包类名必须与导出符号一致；
 * 方法签名照原实现（static String getLicense(int)）。
 * so 缺失的设备（x86_64）上首次调用抛 UnsatisfiedLinkError，由上层捕获后
 * 标记内置引擎不可用。
 */
object SpeexBridge {
    init {
        System.loadLibrary("speexdsp")
    }

    @JvmStatic
    external fun getLicense(type: Int): String
}
