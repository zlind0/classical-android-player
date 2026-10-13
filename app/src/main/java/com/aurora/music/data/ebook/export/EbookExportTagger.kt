package com.aurora.music.data.ebook.export

import java.io.File
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/**
 * 导出章节的 MP4 标签（jaudiotagger 映射到 ©ART / ©alb / ©nam / trkn）：
 * artist = "epub"（固定），album = 书名，title = 章节名，track = i/total。
 * 有声书扫描按 trackNumber + 文件名自然序排章节，与这里的序号前缀一致。
 */
object EbookExportTagger {
    init {
        runCatching { Logger.getLogger("org.jaudiotagger").level = Level.OFF }
    }

    suspend fun tag(audio: File, bookTitle: String, heading: String, track: Int, total: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val af = AudioFileIO.read(audio)
                val tag = af.tagOrCreateAndSetDefault
                tag.setField(FieldKey.ARTIST, "epub")
                tag.setField(FieldKey.ALBUM, bookTitle)
                tag.setField(FieldKey.TITLE, heading)
                tag.setField(FieldKey.TRACK, track.toString())
                runCatching { tag.setField(FieldKey.TRACK_TOTAL, total.toString()) }
                af.commit()
                true
            } catch (t: Throwable) {
                android.util.Log.e("EbookExportTagger", "tag failed", t)
                false
            }
        }
}
