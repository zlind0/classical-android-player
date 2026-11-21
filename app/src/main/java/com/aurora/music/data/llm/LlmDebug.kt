package com.aurora.music.data.llm

import android.util.Log

/**
 * 多模态翻译的调试开关（设置 → 多模态大模型 → 调试信息）。
 *
 * 打开后才会输出诊断日志（切片的解码来源与耗时、每段的准备/生成耗时、模型生命周期），
 * 播放页翻译层底部也会显示模型状态和当前 / 下一段的切片信息。
 *
 * 主进程和 :llm 进程各有一份实例；:llm 侧的值随每次 LOAD / TRANSLATE 请求里的
 * Bundle 同步过去（见 LlmClient.prefsBundle / LlmService.onCommand）。
 */
object LlmDebug {

    @Volatile
    var enabled: Boolean = false

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }
}
