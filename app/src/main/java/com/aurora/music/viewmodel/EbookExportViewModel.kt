package com.aurora.music.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ebook.EpubParser
import com.aurora.music.data.ebook.export.EbookExportRunner
import com.aurora.music.data.ebook.export.EbookExportService
import com.aurora.music.data.ebook.export.ExportSegment
import com.aurora.music.data.ebook.export.ExportSynthSettings
import com.aurora.music.data.ebook.export.ExportVoice
import com.aurora.music.data.ebook.export.PendingEbookExport
import com.aurora.music.data.ebook.export.defaultExportThreads
import com.aurora.music.tts.TtsEngineInfo
import com.aurora.music.tts.listSystemEngines
import com.aurora.music.tts.msInternalAvailable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电子书转有声书：解析预览（章节多选）+ 语音/语速语调/线程/覆盖选项。
 * 语音等偏好持久化在 SettingsStore.ebookExportPrefs；目的地（有声书来源）
 * 由界面从 audiobookStore.roots 读取，选中有声书 root 后落盘 lastRootId。
 */
class EbookExportViewModel(app: Application) : AndroidViewModel(app) {

    private fun container() = (getApplication<Application>() as AuroraApplication).container

    var parsing by mutableStateOf(false)
        private set
    var parseError by mutableStateOf<String?>(null)
        private set
    var bookTitle by mutableStateOf("")
        private set
    var bookAuthor by mutableStateOf("")
        private set
    var segments by mutableStateOf<List<ExportSegment>>(emptyList())
        private set
    var selected by mutableStateOf<Set<Int>>(emptySet())
        private set

    val internalAvailable by mutableStateOf(msInternalAvailable())
    var useInternal by mutableStateOf(true)
        private set
    var internalCode by mutableStateOf("zh-CN-XiaoxiaoNeural")
        private set
    var engines by mutableStateOf<List<TtsEngineInfo>>(emptyList())
        private set
    var systemEnginePkg by mutableStateOf("")
        private set

    private var _rate by mutableFloatStateOf(1.1f)
    val rate: Float get() = _rate
    private var _pitch by mutableFloatStateOf(1f)
    val pitch: Float get() = _pitch

    val cpuCores = defaultExportThreads()
    var threadCount by mutableIntStateOf(cpuCores)
        private set

    var overwriteExisting by mutableStateOf(false)
        private set

    private var loadedPath = ""

    init {
        viewModelScope.launch {
            val p = container().settingsStore.ebookExportPrefs.first()
            useInternal = p.useInternal
            internalCode = p.internalCode
            systemEnginePkg = p.systemEnginePkg
            _rate = p.rate
            _pitch = p.pitch
            threadCount = (p.threads.takeIf { it > 0 } ?: cpuCores).coerceIn(1, cpuCores)
            overwriteExisting = p.overwrite
            if (!internalAvailable && useInternal) useInternal = false
        }
    }

    fun loadBook(path: String) {
        if (path.isBlank() || path == loadedPath) return
        loadedPath = path
        parsing = true
        parseError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(path)
                require(file.isFile && file.canRead()) { "电子书文件不可读" }
                val meta = EpubParser.parseMeta(file)
                val book = EpubParser.parseFull(file)
                val segs = com.aurora.music.data.ebook.export.segmentEbook(book)
                if (segs.isEmpty()) throw IllegalStateException("本书解析不到可朗读内容")
                val title = book.title.ifBlank { meta.title }.ifBlank {
                    file.name.substringBeforeLast('.')
                }
                withContext(Dispatchers.Main) {
                    bookTitle = title
                    bookAuthor = book.author.ifBlank { meta.author }
                    segments = segs
                    selected = segs.map { it.index }.toSet()
                    EbookExportRunner.clearResult()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { parseError = t.message ?: "解析失败" }
            } finally {
                withContext(Dispatchers.Main) { parsing = false }
            }
        }
    }

    fun toggleSelect(index: Int) {
        selected = if (selected.contains(index)) selected - index else selected + index
    }

    fun selectAll(v: Boolean) {
        selected = if (v) segments.map { it.index }.toSet() else emptySet()
    }

    private fun store() = container().settingsStore

    fun setVoiceInternal(code: String) {
        useInternal = true
        internalCode = code
        viewModelScope.launch { store().setEexportUseInternal(true); store().setEexportInternalCode(code) }
    }

    fun setVoiceSystem(pkg: String) {
        useInternal = false
        systemEnginePkg = pkg
        viewModelScope.launch { store().setEexportUseInternal(false); store().setEexportEngine(pkg) }
        loadEngines()
    }

    fun setRate(v: Float) {
        _rate = v.coerceIn(0.5f, 2f)
        viewModelScope.launch { store().setEexportRate(_rate) }
    }

    fun setPitch(v: Float) {
        _pitch = v.coerceIn(0.5f, 2f)
        viewModelScope.launch { store().setEexportPitch(_pitch) }
    }

    fun setThreads(v: Int) {
        threadCount = v.coerceIn(1, cpuCores)
        viewModelScope.launch { store().setEexportThreads(threadCount) }
    }

    fun setOverwrite(v: Boolean) {
        overwriteExisting = v
        viewModelScope.launch { store().setEexportOverwrite(v) }
    }

    fun loadEngines() {
        if (engines.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { listSystemEngines(getApplication()) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                engines = list
                if (!useInternal && systemEnginePkg.isBlank() && list.isNotEmpty()) {
                    systemEnginePkg = list.firstOrNull { it.isDefault }?.packageName ?: list.first().packageName
                }
            }
        }
    }

    fun settings(): ExportSynthSettings = ExportSynthSettings(
        voice = if (useInternal && internalAvailable) ExportVoice.Internal(internalCode)
        else ExportVoice.System(systemEnginePkg.ifBlank { null }),
        rate = rate,
        pitch = pitch,
    )

    fun startExport(outRoot: File, rootId: Long) {
        val segs = segments.filter { selected.contains(it.index) }
        if (segs.isEmpty()) return
        viewModelScope.launch { store().setEexportRoot(rootId) }
        EbookExportService.start(
            getApplication(),
            PendingEbookExport.Job(
                bookTitle = bookTitle.ifBlank { "未知书名" },
                segments = segs,
                settings = settings(),
                outRoot = outRoot,
                rootId = rootId,
                threads = threadCount,
                overwrite = overwriteExisting,
            ),
        )
    }
}
