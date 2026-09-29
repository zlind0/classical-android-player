package com.aurora.music.data.ebook

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.ebookReadDataStore by preferencesDataStore(name = "ebook_read_prefs")

/** 自定义字体最多保留最近选择的个数。 */
const val MAX_RECENT_FONTS = 10

// 阅读设置：页面风格 / 字体 / 字号。复用 DataStore 模式，与音乐设置互相独立。
class EbookPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val FONT_PATH = stringPreferencesKey("font_path")
        val FONT_RECENT = stringPreferencesKey("font_recent")
        // 引擎/音色/语速/音调已统一存到主 DataStore（SettingsStore.unifiedTts），此处只留切片单位；
        // 旧四键保留仅供一次性迁移读取（migrateEbookTts），不再写入。
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_UNIT = stringPreferencesKey("tts_unit")
    }

    private val gson = Gson()

    private val _prefs = MutableStateFlow(EbookReadPrefs())
    val prefs: StateFlow<EbookReadPrefs> = _prefs.asStateFlow()

    private val _ttsUnit = MutableStateFlow(EbookTtsUnit.PARA)
    val ttsUnit: StateFlow<EbookTtsUnit> = _ttsUnit.asStateFlow()

    /** 最近选择过的自定义字体（新→旧，最多 [MAX_RECENT_FONTS] 个，不存在的文件已滤掉）。 */
    private val _recentFonts = MutableStateFlow<List<String>>(emptyList())
    val recentFonts: StateFlow<List<String>> = _recentFonts.asStateFlow()

    init {
        scope.launch {
            appContext.ebookReadDataStore.data.map { p ->
                EbookReadPrefs(
                    theme = runCatching { EbookTheme.valueOf(p[Keys.THEME] ?: "WHITE") }.getOrDefault(EbookTheme.WHITE),
                    fontSizeSp = p[Keys.FONT_SIZE] ?: 18f,
                    fontPath = p[Keys.FONT_PATH].orEmpty(),
                )
            }.collect { _prefs.value = it }
        }
        scope.launch {
            appContext.ebookReadDataStore.data.map { p ->
                parseRecents(p[Keys.FONT_RECENT])
            }.collect { _recentFonts.value = it }
        }
        scope.launch {
            appContext.ebookReadDataStore.data.map { p ->
                runCatching { EbookTtsUnit.valueOf(p[Keys.TTS_UNIT] ?: "PARA") }
                    .getOrDefault(EbookTtsUnit.PARA)
            }.collect { _ttsUnit.value = it }
        }
    }

    /** 旧 TTS 四键快照（仅供统一设置一次性迁移，平时不用）。 */
    suspend fun snapshotTts(): com.aurora.music.data.UnifiedTtsPrefs =
        appContext.ebookReadDataStore.data.map { p ->
            val internal = (p[Keys.TTS_ENGINE] ?: "INTERNAL") != "SYSTEM"
            com.aurora.music.data.UnifiedTtsPrefs(
                engine = if (internal) com.aurora.music.data.TTS_ENGINE_INTERNAL else "",
                voice = p[Keys.TTS_VOICE].orEmpty(),
                rate = (p[Keys.TTS_RATE] ?: 1f).coerceIn(0.5f, 2f),
                pitch = (p[Keys.TTS_PITCH] ?: 1f).coerceIn(0.5f, 2f),
            )
        }.first()

    /** 旧 TTS 四键是否被用户改过（供迁移判断）。 */
    suspend fun isTtsCustomized(): Boolean =
        appContext.ebookReadDataStore.data.map { p ->
            (p[Keys.TTS_ENGINE] ?: "INTERNAL") != "INTERNAL" ||
                !p[Keys.TTS_VOICE].isNullOrEmpty() ||
                (p[Keys.TTS_RATE] ?: 1f) != 1f ||
                (p[Keys.TTS_PITCH] ?: 1f) != 1f
        }.first()

    suspend fun initial(): EbookReadPrefs = prefs.first()

    fun setTheme(t: EbookTheme) {
        scope.launch { appContext.ebookReadDataStore.edit { it[Keys.THEME] = t.name } }
    }

    fun setFontSize(sp: Float) {
        scope.launch { appContext.ebookReadDataStore.edit { it[Keys.FONT_SIZE] = sp.coerceIn(12f, 50f) } }
    }

    fun setFontPath(path: String) {
        scope.launch {
            appContext.ebookReadDataStore.edit { it[Keys.FONT_PATH] = path }
            if (path.isNotBlank()) rememberFont(path)
        }
    }

    /** 把一次选择记到最近名单头部（去重、截断，掉出名单的旧字体文件顺手删掉）。 */
    private suspend fun rememberFont(path: String) {
        val evicted = mutableListOf<String>()
        appContext.ebookReadDataStore.edit { p ->
            val cur = parseRecents(p[Keys.FONT_RECENT])
            val next = (listOf(path) + cur).distinct().take(MAX_RECENT_FONTS)
            p[Keys.FONT_RECENT] = gson.toJson(next)
            evicted += cur - next.toSet()
        }
        if (evicted.isEmpty()) return
        val dir = runCatching { File(appContext.filesDir, "fonts").canonicalPath }.getOrNull()
            ?: return
        evicted.forEach { old ->
            runCatching {
                val f = File(old)
                // 只删自家 fonts 目录下的，避免误删用户别处文件
                if (f.canonicalPath.startsWith(dir + File.separator)) f.delete()
            }
        }
    }

    private fun parseRecents(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson(json, Array<String>::class.java).toList()
        }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() && File(it).exists() }
            .distinct()
            .take(MAX_RECENT_FONTS)
    }

    fun setTtsUnit(u: EbookTtsUnit) {
        scope.launch { appContext.ebookReadDataStore.edit { it[Keys.TTS_UNIT] = u.name } }
    }
}
