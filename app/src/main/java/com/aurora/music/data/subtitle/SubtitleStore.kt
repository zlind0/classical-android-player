package com.aurora.music.data.subtitle

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * 字幕缓存：按 (书, 章节) 存 JSON，二次收听零延迟。
 * 位置：filesDir/subtitles/<bookHash>/<chapterHash>.json
 */
class SubtitleStore(private val context: Context) {

    data class Cached(
        val modelId: String,
        val generatedUntil: Double,
        val cues: List<SubtitleCue>,
    )

    private data class Payload(
        val version: Int = 1,
        val modelId: String = "",
        val generatedUntil: Double = 0.0,
        val cues: List<CueDto> = emptyList(),
    )

    private data class CueDto(val s: Double, val e: Double, val t: String)

    private val gson = Gson()

    private fun dirOf(bookId: String): File =
        File(File(context.filesDir, "subtitles"), md5(bookId))

    private fun fileOf(bookId: String, chapterKey: String): File =
        File(dirOf(bookId), md5(chapterKey) + ".json")

    fun load(bookId: String, chapterKey: String): Cached? {
        val f = fileOf(bookId, chapterKey)
        if (!f.isFile) return null
        return try {
            val p = gson.fromJson(f.readText(), Payload::class.java) ?: return null
            Cached(p.modelId, p.generatedUntil, p.cues.map { SubtitleCue(it.s, it.e, it.t) })
        } catch (_: Throwable) {
            null
        }
    }

    fun save(bookId: String, chapterKey: String, modelId: String, cues: List<SubtitleCue>, generatedUntil: Double) {
        try {
            val f = fileOf(bookId, chapterKey)
            f.parentFile?.mkdirs()
            val payload = Payload(
                modelId = modelId,
                generatedUntil = generatedUntil,
                cues = cues.map { CueDto(it.start, it.end, it.text) },
            )
            f.writeText(gson.toJson(payload))
        } catch (_: Throwable) {
            // 缓存失败不影响播放
        }
    }

    fun delete(bookId: String, chapterKey: String) {
        runCatching { fileOf(bookId, chapterKey).delete() }
    }

    fun clearAll() {
        runCatching { File(context.filesDir, "subtitles").deleteRecursively() }
    }

    fun bytes(): Long {
        val root = File(context.filesDir, "subtitles")
        if (!root.isDirectory) return 0
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun chapterCount(): Int {
        val root = File(context.filesDir, "subtitles")
        if (!root.isDirectory) return 0
        return root.walkTopDown().count { it.isFile && it.name.endsWith(".json") }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
