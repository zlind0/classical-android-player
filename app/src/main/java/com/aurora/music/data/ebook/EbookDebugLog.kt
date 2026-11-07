package com.aurora.music.data.ebook

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 临时调试日志：定位“锁屏→暂停→解锁后阅读页回到旧进度”的问题。
 *
 * 同时输出到 Logcat（tag = EbookPos）和 filesDir/ebook_pos_debug.log，
 * 方便没有 adb 时直接在应用私有目录里取文件。定位完成后可整体删除。
 */
object EbookDebugLog {
    private const val TAG = "EbookPos"
    private const val FILE_NAME = "ebook_pos_debug.log"
    private const val MAX_BYTES = 4 * 1024 * 1024L

    @Volatile private var appContext: Context? = null
    @Volatile private var enabled: Boolean = false
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 由设置开关驱动；关闭时既不写文件也不打 Logcat。 */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun log(msg: String) {
        if (!enabled) return
        val line = "${fmt.format(Date())} [${Thread.currentThread().name}] $msg"
        Log.i(TAG, line)
        val ctx = appContext ?: return
        synchronized(lock) {
            runCatching {
                val f = File(ctx.filesDir, FILE_NAME)
                if (f.length() > MAX_BYTES) f.delete()
                f.appendText(line + "\n")
            }
        }
    }

    /** 当前日志文件（供 UI/分享用）。 */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
