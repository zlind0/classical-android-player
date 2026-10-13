package com.aurora.music.data.ebook.export

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 电子书转有声书导出器。
 *
 * 段间串行、段内分批（≤800 字）并行：每批独立合成 mono PCM 并独立编码为
 * ADTS 分片（边合成边写临时文件，内存恒定）→ 完成的连续前缀即时增量合并
 * 为一段 M4A（PTS 连续、无重编码，最后一个分片落地即收尾）→ 写标签 →
 * 拷入输出根目录下自动新建的书名子目录。同名子目录已存在则复用
 * （重复导出覆盖同名文件）。单段失败记入失败列表，不中断整书；可取消。
 * 内存/磁盘占用与书长无关。
 *
 * 与试验项目 epub2audio 的差异：输出目标不再是 SAF/共享目录，而是调用方
 * 传入的有声书来源根目录（File 直接写，权限由来源管理保证）。
 */
class EbookExporter(private val context: Context) {

    suspend fun export(
        bookTitle: String,
        segments: List<ExportSegment>,
        settings: ExportSynthSettings,
        /** 有声书来源根目录，输出进其下自动新建的书名子目录。 */
        outRoot: File,
        threads: Int,
        overwrite: Boolean,
        cancelled: AtomicBoolean,
        onProgress: (EbookExportProgress) -> Unit,
    ): EbookExportResult = withContext(Dispatchers.IO) {
        val total = segments.size
        if (total == 0) return@withContext EbookExportResult(0, listOf("没有可导出的章节"), false)
        if (!outRoot.isDirectory && !outRoot.mkdirs() && !outRoot.isDirectory) {
            return@withContext EbookExportResult(0, listOf("输出目录不可用：${outRoot.absolutePath}"), false)
        }
        // 输出进以书名命名的子目录，不同书不混；重跑原位覆盖。
        val folderName = sanitizeExportFileName(bookTitle.ifBlank { "未知书名" })
        val outDir = File(outRoot, folderName)
        val existingFile = File(outRoot, folderName)
        if (existingFile.isFile) {
            return@withContext EbookExportResult(0, listOf("输出位置已有同名文件：$folderName"), false)
        }
        if (!outDir.isDirectory && !outDir.mkdirs() && !outDir.isDirectory) {
            return@withContext EbookExportResult(0, listOf("无法创建输出子目录：$folderName"), false)
        }

        val appContext = context.applicationContext
        val rate = settings.rate.coerceIn(0.5f, 2f)
        val pitch = settings.pitch.coerceIn(0.5f, 2f)
        // 批次划分是廉价字符串操作；预计算后按最大段定池大小（多余线程只会空转）。
        val plans = segments.map { seg -> seg to batchExportTexts(seg.texts) }
        val maxBatches = plans.maxOfOrNull { it.second.size } ?: 0
        if (maxBatches == 0) return@withContext EbookExportResult(0, listOf("没有可导出的章节"), false)
        val workers = clampExportThreads(threads, defaultExportThreads()).coerceAtMost(maxBatches)

        // 串行准备：内置语音只装一次，再按工作线程数建会话。
        // （并发安装会互相踩文件；会话按设计线程独占。）
        val internalVoice = when (val v = settings.voice) {
            is ExportVoice.Internal -> {
                try {
                    ensureExportVoice(appContext, v.voiceCode) { d, t, n ->
                        onProgress(EbookExportProgress(0, total, v.voiceCode, ExportPhase.SYNTH, "安装语音 $n ($d/$t)"))
                    }
                } catch (e: Exception) {
                    return@withContext EbookExportResult(0, listOf("语音安装失败：${e.message}"), false, folderName)
                }
            }
            is ExportVoice.System -> null
        }
        val sessions = mutableListOf<ExportWorkerSession>()
        try {
            repeat(workers) {
                sessions += when (val v = settings.voice) {
                    is ExportVoice.Internal ->
                        InternalExportSession(appContext, internalVoice!!, rate, pitch)
                    is ExportVoice.System ->
                        SystemExportSession.create(appContext, v.enginePackage, rate, pitch)
                }
            }
        } catch (e: Exception) {
            sessions.forEach { runCatching { it.close() } }
            return@withContext EbookExportResult(0, listOf("TTS 初始化失败：${e.message}"), false, folderName)
        }

        val failed = Collections.synchronizedList(mutableListOf<String>())
        var done = 0
        var skipped = 0
        val pool = Executors.newFixedThreadPool(workers).asCoroutineDispatcher()
        val exportRoot = File(appContext.cacheDir, "ebook_export_${System.currentTimeMillis()}")
        exportRoot.mkdirs()
        try {
            plans.forEachIndexed { i, (seg, batches) ->
                coroutineContext.ensureActive()
                if (cancelled.get()) {
                    return@withContext EbookExportResult(done, failed.toList(), true, folderName, skipped)
                }
                if (batches.isEmpty()) {
                    failed.add("${seg.title}: 无可用音频")
                    return@forEachIndexed
                }
                if (!overwrite && File(outDir, seg.fileName()).isFile) {
                    skipped++
                    onProgress(
                        EbookExportProgress(done, total, seg.title, ExportPhase.SYNTH, "已存在，跳过", 0, batches.size, 0, 0),
                    )
                    return@forEachIndexed
                }
                val segDir = File(exportRoot, "seg_$i").apply { mkdirs() }
                val m4a = File.createTempFile("ebookseg", ".m4a", appContext.cacheDir)
                try {
                    val synthDoneCount = AtomicInteger(0)
                    val encodeDoneCount = AtomicInteger(0)
                    val mergedCount = AtomicInteger(0)
                    fun prog(): EbookExportProgress = EbookExportProgress(
                        done, total, seg.title, ExportPhase.SYNTH, null,
                        synthDoneCount.get(), batches.size,
                        encodeDoneCount.get(), mergedCount.get(),
                    )
                    val merger = ExportSegmentMerger(segDir, batches.size, m4a) { merged ->
                        mergedCount.set(merged)
                        onProgress(prog())
                    }
                    try {
                        onProgress(
                            EbookExportProgress(done, total, seg.title, ExportPhase.SYNTH, null, 0, batches.size, 0, 0),
                        )
                        val segFailed = AtomicBoolean(false)
                        val nextBatch = AtomicInteger(0)
                        coroutineScope {
                            sessions.forEach { session ->
                                launch(pool) {
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        if (cancelled.get() || segFailed.get()) break
                                        val j = nextBatch.getAndIncrement()
                                        if (j >= batches.size) break
                                        try {
                                            val pcmPart = File(segDir, "part_$j.pcm")
                                            FileOutputStream(pcmPart).buffered().use { out ->
                                                session.synthOne(batches[j], out, cancelled)
                                            }
                                            if (cancelled.get()) {
                                                runCatching { pcmPart.delete() }
                                                break
                                            }
                                            if (pcmPart.length() == 0L) {
                                                throw IllegalStateException("无可用音频")
                                            }
                                            onProgress(prog().copy(batchDone = synthDoneCount.incrementAndGet()))
                                            val aacPart = File(segDir, "part_$j.aac")
                                            val csd = EbookAacEncoder.encodeToAdts(pcmPart, aacPart)
                                            runCatching { pcmPart.delete() }
                                            if (cancelled.get()) {
                                                runCatching { aacPart.delete() }
                                                break
                                            }
                                            merger.submit(j, csd)
                                            onProgress(prog().copy(encodeDone = encodeDoneCount.incrementAndGet()))
                                        } catch (e: Exception) {
                                            if (cancelled.get()) break
                                            // 只有首个失败批次上报；其余看到 segFailed 后收工，
                                            // 不再做无用功。
                                            if (segFailed.compareAndSet(false, true)) {
                                                failed.add("${seg.title}: ${e.message ?: "失败"}")
                                            }
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        if (cancelled.get()) {
                            return@withContext EbookExportResult(done, failed.toList(), true, folderName, skipped)
                        }
                        if (segFailed.get()) return@forEachIndexed
                        onProgress(
                            EbookExportProgress(done, total, seg.title, ExportPhase.REMUX, null, batches.size, batches.size, batches.size, batches.size),
                        )
                        merger.finish()
                        onProgress(
                            EbookExportProgress(done, total, seg.title, ExportPhase.TAG, null, batches.size, batches.size, batches.size, batches.size),
                        )
                        EbookExportTagger.tag(m4a, bookTitle, seg.title, seg.index + 1, seg.total)

                        onProgress(
                            EbookExportProgress(done, total, seg.title, ExportPhase.COPY, null, batches.size, batches.size, batches.size, batches.size),
                        )
                        copyInto(m4a, seg.fileName(), outDir)
                        done++
                    } finally {
                        runCatching { merger.close() }
                    }
                } finally {
                    runCatching { segDir.deleteRecursively() }
                    runCatching { m4a.delete() }
                }
            }
        } finally {
            sessions.forEach { runCatching { it.close() } }
            pool.close()
            runCatching { exportRoot.deleteRecursively() }
        }
        EbookExportResult(done, failed.toList(), cancelled.get(), folderName, skipped)
    }

    private fun copyInto(audio: File, fileName: String, outDir: File) {
        val dest = File(outDir, fileName)
        runCatching { if (dest.isFile) dest.delete() }
        audio.copyTo(dest, overwrite = true)
        if (!dest.isFile || dest.length() == 0L) throw IllegalStateException("无法写入输出文件")
    }
}
