package com.aurora.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class SongIntroLogEntry(
    val timeMs: Long = 0L,
    val model: String = "",
    val input: String = "",   // 发给 LLM 的 ID3 文本
    val result: String = "",  // 完整介绍文本（失败时为空）
    val error: String = "",   // 失败原因（成功时为空）
    val interrupted: Boolean = false, // 是否被用户/切歌中断
)

/** 最近 50 次解说记录：filesDir 单 JSON 文件，不进数据库。 */
class SongIntroLogStore(context: Context) {
    private val file = File(context.filesDir, "song_intro_log.json")
    private val gson = Gson()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _entries = MutableStateFlow<List<SongIntroLogEntry>>(emptyList())
    val entries: StateFlow<List<SongIntroLogEntry>> = _entries.asStateFlow()

    init {
        scope.launch { _entries.value = read() }
    }

    suspend fun add(entry: SongIntroLogEntry) {
        mutex.withLock {
            val next = withContext(Dispatchers.IO) {
                val cur = read().toMutableList()
                cur.add(0, entry)
                val capped = cur.take(MAX)
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(gson.toJson(capped))
                }
                capped
            }
            _entries.value = next
        }
    }

    suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) { runCatching { if (file.exists()) file.delete() } }
            _entries.value = emptyList()
        }
    }

    private fun read(): List<SongIntroLogEntry> = runCatching {
        if (!file.exists()) return emptyList()
        val t = object : TypeToken<List<SongIntroLogEntry>>() {}.type
        gson.fromJson<List<SongIntroLogEntry>>(file.readText(), t).orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX = 50
    }
}
