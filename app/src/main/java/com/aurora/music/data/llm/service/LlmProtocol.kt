package com.aurora.music.data.llm.service

/** 主进程 ↔ :llm 服务进程的 Messenger 协议。 */
object LlmProtocol {
    // client -> service
    const val MSG_LOAD = 1
    const val MSG_TRANSLATE = 2
    const val MSG_CANCEL = 3
    const val MSG_UNLOAD = 4

    /** 心跳：翻译界面在前台时每 5 秒一次，服务端 30 秒收不到就自动卸载模型。 */
    const val MSG_PING = 5

    // service -> client
    const val CB_STATE = 10
    const val CB_READY = 11
    const val CB_TOKEN = 12
    const val CB_DONE = 13
    const val CB_ERROR = 14

    const val STATE_IDLE = 0
    const val STATE_LOADING = 1
    const val STATE_READY = 2
    const val STATE_ERROR = 3

    const val KEY_MODEL_ID = "model_id"
    const val KEY_CONTEXT_TOKENS = "context_tokens"
    const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
    const val KEY_TOP_K = "top_k"
    const val KEY_TOP_P = "top_p"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_USE_GPU = "use_gpu"

    /** 调试信息开关：打开后 :llm 进程才输出诊断日志。 */
    const val KEY_DEBUG = "debug"
    const val KEY_REQUEST_ID = "request_id"
    const val KEY_AUDIO_PATH = "audio_path"

    /** 翻译完是否删除音频文件（临时请求为 true；切片缓存为 false）。 */
    const val KEY_AUDIO_DELETE = "audio_delete"
    const val KEY_PROMPT = "prompt"
    const val KEY_STATE = "state"
    const val KEY_MESSAGE = "message"
    const val KEY_TOKEN = "token"
    const val KEY_TEXT = "text"
}
