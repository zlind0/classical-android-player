package com.aurora.music.data.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一章的翻译切块持久化（表结构见 [TranslationChunkRow]）。
 *
 * 只负责「按章读写」；端点一旦写入就不可改，规划逻辑在 [TranslationSession] 里，
 * 加载时会把整章计划读进内存，之后的查找都是 TreeMap 的 O(log n)。
 */
class TranslationStore(
    private val context: Context,
    private val bookId: String,
    private val chapterKey: String,
) {

    data class Entry(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val modelId: String,
        val lang: String,
    )

    suspend fun load(): List<Entry> = withContext(Dispatchers.IO) {
        runCatching {
            TranslationCache.dao(context).chapterChunks(bookId, chapterKey).map {
                Entry(it.startMs, it.endMs, it.text, it.modelId, it.lang)
            }
        }.onFailure {
            Log.w(TAG, "load $bookId/$chapterKey failed", it)
        }.getOrDefault(emptyList())
    }

    /** 一个块翻译完成就立刻写盘；同一 startMs 覆盖旧译文（端点不变）。 */
    suspend fun put(startMs: Long, endMs: Long, text: String, modelId: String, lang: String) {
        if (text.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                TranslationCache.dao(context).put(
                    TranslationChunkRow(
                        bookId = bookId,
                        chapterKey = chapterKey,
                        startMs = startMs,
                        endMs = endMs,
                        text = text,
                        modelId = modelId,
                        lang = lang,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure {
                Log.w(TAG, "persist $startMs-$endMs failed", it)
            }
        }
    }

    private companion object {
        const val TAG = "TranslationStore"
    }
}