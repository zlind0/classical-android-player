package com.aurora.music.data.ebook

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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

/** 电子书 Tab「最近阅读」显示数量范围（首页固定 3 本，不受此设置影响）。 */
const val MIN_RECENT_BOOKS = 5
const val MAX_RECENT_BOOKS = 20

/** 随机阅读本地预缓存本数范围。 */
const val MIN_FEED_CACHE = 5
const val MAX_FEED_CACHE = 50
const val DEFAULT_FEED_CACHE = 20

/** 随机阅读上次停下的位置（用于下次“开始阅读”恢复；书已不在随机池时由服务端重新随机）。 */
data class EbookFeedResume(
    val deviceId: String = "",
    val rootKey: String = "",
    val path: String = "",
    val chapter: Int = 0,
    val block: Int = 0,
    val char: Int = 0,
)

// 阅读设置：页面风格 / 字体 / 字号。复用 DataStore 模式，与音乐设置互相独立。
class EbookPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val FONT_PATH = stringPreferencesKey("font_path")
        val FONT_RECENT = stringPreferencesKey("font_recent")
        /** 电子书 Tab「最近阅读」显示的条数（5..20）。 */
        val RECENT_LIMIT = intPreferencesKey("recent_limit")
        // 引擎/音色/语速/音调已统一存到主 DataStore（SettingsStore.unifiedTts），此处只留切片单位；
        // 旧四键保留仅供一次性迁移读取（migrateEbookTts），不再写入。
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_UNIT = stringPreferencesKey("tts_unit")
        /** 投屏底片（网页端与 HDMI 共用）。 */
        val CAST_THEME = stringPreferencesKey("cast_theme")
        /** 旧版共用的字号/栏宽，仅作为迁移默认值读取，不再写入。 */
        val CAST_FONT_PX = intPreferencesKey("cast_font_px")
        val CAST_COLUMN_WIDTH = intPreferencesKey("cast_column_width_px")
        // 网页投屏 / HDMI 投屏各自的画面设置（字号、栏宽、音乐条、翻页单位，分开存储）。
        val WEB_CAST_FONT_PX = intPreferencesKey("web_cast_font_px")
        val WEB_CAST_COLUMN_WIDTH = intPreferencesKey("web_cast_column_width_px")
        val WEB_CAST_SHOW_MUSIC = booleanPreferencesKey("web_cast_show_music")
        val WEB_CAST_TURN_UNIT = stringPreferencesKey("web_cast_turn_unit")
        val WEB_CAST_MUSIC_ON_CAST = booleanPreferencesKey("web_cast_music_on_cast")
        /** 网页出声时的前置放大（0..1）。 */
        val WEB_CAST_PREAMP = floatPreferencesKey("web_cast_preamp")
        val HDMI_CAST_FONT_PX = intPreferencesKey("hdmi_cast_font_px")
        val HDMI_CAST_COLUMN_WIDTH = intPreferencesKey("hdmi_cast_column_width_px")
        val HDMI_CAST_SHOW_MUSIC = booleanPreferencesKey("hdmi_cast_show_music")
        val HDMI_CAST_TURN_UNIT = stringPreferencesKey("hdmi_cast_turn_unit")
        val HDMI_CAST_MUSIC_ON_CAST = booleanPreferencesKey("hdmi_cast_music_on_cast")
        /** 随机阅读是否请求插图（服务端 v1 暂不返回，开关先留口子）。 */
        val FEED_IMAGES = booleanPreferencesKey("feed_images")
        /** 随机阅读本地预缓存本数（5..50）。 */
        val FEED_CACHE_SIZE = intPreferencesKey("feed_cache_size")
        /** 随机阅读上次位置（JSON）。 */
        val FEED_RESUME = stringPreferencesKey("feed_resume")
    }

    private val gson = Gson()

    private val _prefs = MutableStateFlow(EbookReadPrefs())
    val prefs: StateFlow<EbookReadPrefs> = _prefs.asStateFlow()

    private val _ttsUnit = MutableStateFlow(EbookTtsUnit.PARA)
    val ttsUnit: StateFlow<EbookTtsUnit> = _ttsUnit.asStateFlow()

    /** 最近选择过的自定义字体（新→旧，最多 [MAX_RECENT_FONTS] 个，不存在的文件已滤掉）。 */
    private val _recentFonts = MutableStateFlow<List<String>>(emptyList())
    val recentFonts: StateFlow<List<String>> = _recentFonts.asStateFlow()

    /** 电子书 Tab「最近阅读」显示条数（5..20，默认 5，与旧行为一致）。 */
    private val _recentLimit = MutableStateFlow(MIN_RECENT_BOOKS)
    val recentLimit: StateFlow<Int> = _recentLimit.asStateFlow()

    /** 投屏画面设置：底色共用，字号/栏宽网页端与 HDMI 各存一份，遥控端改完即落盘。 */
    private val _castPrefs = MutableStateFlow(EbookCastDisplayPrefs())
    val castPrefs: StateFlow<EbookCastDisplayPrefs> = _castPrefs.asStateFlow()

    /** 随机阅读：是否请求插图（实验开关）。 */
    private val _feedImages = MutableStateFlow(false)
    val feedImages: StateFlow<Boolean> = _feedImages.asStateFlow()

    /** 随机阅读：本地预缓存本数（5..50，默认 20）。 */
    private val _feedCacheSize = MutableStateFlow(DEFAULT_FEED_CACHE)
    val feedCacheSize: StateFlow<Int> = _feedCacheSize.asStateFlow()

    /** 随机阅读上次位置；空/书已不在池时由调用方重新随机。 */
    private val _feedResume = MutableStateFlow(EbookFeedResume())
    val feedResume: StateFlow<EbookFeedResume> = _feedResume.asStateFlow()

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
        scope.launch {
            appContext.ebookReadDataStore.data.map { p ->
                (p[Keys.RECENT_LIMIT] ?: MIN_RECENT_BOOKS).coerceIn(MIN_RECENT_BOOKS, MAX_RECENT_BOOKS)
            }.collect { _recentLimit.value = it }
        }
        scope.launch {
            appContext.ebookReadDataStore.data.map { p ->
                // 旧版只有一个共用值：迁移时把它作为网页/HDMI 两侧的初始值
                val legacyFont = (p[Keys.CAST_FONT_PX] ?: 32).coerceIn(MIN_CAST_FONT_PX, MAX_CAST_FONT_PX)
                val legacyWidth = (p[Keys.CAST_COLUMN_WIDTH] ?: 640)
                    .coerceIn(MIN_CAST_COLUMN_WIDTH_PX, MAX_CAST_COLUMN_WIDTH_PX)
                EbookCastDisplayPrefs(
                    theme = runCatching { EbookTheme.valueOf(p[Keys.CAST_THEME] ?: "WHITE") }
                        .getOrDefault(EbookTheme.WHITE),
                    webFontSizePx = (p[Keys.WEB_CAST_FONT_PX] ?: legacyFont)
                        .coerceIn(MIN_CAST_FONT_PX, MAX_CAST_FONT_PX),
                    webMaxColumnWidthPx = (p[Keys.WEB_CAST_COLUMN_WIDTH] ?: legacyWidth)
                        .coerceIn(MIN_CAST_COLUMN_WIDTH_PX, MAX_CAST_COLUMN_WIDTH_PX),
                    webShowMusic = p[Keys.WEB_CAST_SHOW_MUSIC] ?: true,
                    webTurnUnit = turnUnitOf(p[Keys.WEB_CAST_TURN_UNIT]),
                    // 网页常驻后默认由手机出声：要网页出声由播放页音量条右侧的 airplay 键打开，
                    // 否则一开网页手机就静音了。HDMI 默认手机出声。
                    webMusicOnCast = p[Keys.WEB_CAST_MUSIC_ON_CAST] ?: false,
                    // 网页出声的前置放大默认满幅（100%）
                    webPreamp = (p[Keys.WEB_CAST_PREAMP] ?: 1f).coerceIn(0f, 1f),
                    hdmiFontSizePx = (p[Keys.HDMI_CAST_FONT_PX] ?: legacyFont)
                        .coerceIn(MIN_CAST_FONT_PX, MAX_CAST_FONT_PX),
                    hdmiMaxColumnWidthPx = (p[Keys.HDMI_CAST_COLUMN_WIDTH] ?: legacyWidth)
                        .coerceIn(MIN_CAST_COLUMN_WIDTH_PX, MAX_CAST_COLUMN_WIDTH_PX),
                    hdmiShowMusic = p[Keys.HDMI_CAST_SHOW_MUSIC] ?: true,
                    hdmiTurnUnit = turnUnitOf(p[Keys.HDMI_CAST_TURN_UNIT]),
                    hdmiMusicOnCast = p[Keys.HDMI_CAST_MUSIC_ON_CAST] ?: false,
                )
            }.collect { _castPrefs.value = it }
        }
        scope.launch {
            appContext.ebookReadDataStore.data.map { p ->
                p[Keys.FEED_IMAGES] ?: false
            }.collect { _feedImages.value = it }
        }
        scope.launch {
            appContext.ebookReadDataStore.data.map { p ->
                (p[Keys.FEED_CACHE_SIZE] ?: DEFAULT_FEED_CACHE)
                    .coerceIn(MIN_FEED_CACHE, MAX_FEED_CACHE)
            }.collect { _feedCacheSize.value = it }
        }
        scope.launch {
            appContext.ebookReadDataStore.data.map { p ->
                p[Keys.FEED_RESUME]?.takeIf { it.isNotBlank() }?.let { json ->
                    runCatching { gson.fromJson(json, EbookFeedResume::class.java) }.getOrNull()
                } ?: EbookFeedResume()
            }.collect { _feedResume.value = it }
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

    /** 设置电子书 Tab「最近阅读」条数；范围外的值直接夹到 5..20。 */
    fun setRecentLimit(n: Int) {
        scope.launch {
            appContext.ebookReadDataStore.edit {
                it[Keys.RECENT_LIMIT] = n.coerceIn(MIN_RECENT_BOOKS, MAX_RECENT_BOOKS)
            }
        }
    }

    fun setCastTheme(t: EbookTheme) {
        scope.launch { appContext.ebookReadDataStore.edit { it[Keys.CAST_THEME] = t.name } }
    }

    /** 随机阅读“加载插图（实验）”开关；协议字段已传，服务端 v1 暂不返回图片。 */
    fun setFeedImages(enabled: Boolean) {
        scope.launch { appContext.ebookReadDataStore.edit { it[Keys.FEED_IMAGES] = enabled } }
    }

    /** 随机阅读本地预缓存本数（5..50）。 */
    fun setFeedCacheSize(value: Int) {
        scope.launch {
            appContext.ebookReadDataStore.edit {
                it[Keys.FEED_CACHE_SIZE] = value.coerceIn(MIN_FEED_CACHE, MAX_FEED_CACHE)
            }
        }
    }

    /** 记住随机阅读最后停下的位置，下次进入优先恢复。 */
    fun saveFeedResume(resume: EbookFeedResume) {
        scope.launch {
            appContext.ebookReadDataStore.edit { it[Keys.FEED_RESUME] = gson.toJson(resume) }
        }
    }

    suspend fun initialFeedResume(): EbookFeedResume = feedResume.first()

    /** 改投屏字号；web = true 存网页投屏的一份，false 存 HDMI 的一份。 */
    fun setCastFontSize(web: Boolean, px: Int) {
        scope.launch {
            appContext.ebookReadDataStore.edit {
                it[if (web) Keys.WEB_CAST_FONT_PX else Keys.HDMI_CAST_FONT_PX] =
                    px.coerceIn(MIN_CAST_FONT_PX, MAX_CAST_FONT_PX)
            }
        }
    }

    private fun turnUnitOf(v: String?): EbookCastTurnUnit =
        runCatching { EbookCastTurnUnit.valueOf(v ?: "SCREEN") }.getOrDefault(EbookCastTurnUnit.SCREEN)

    /** 改“投屏画面上是否显示音乐播放条”；web = true 存网页投屏的一份，false 存 HDMI 的一份。 */
    fun setCastShowMusic(web: Boolean, show: Boolean) {
        scope.launch {
            appContext.ebookReadDataStore.edit {
                it[if (web) Keys.WEB_CAST_SHOW_MUSIC else Keys.HDMI_CAST_SHOW_MUSIC] = show
            }
        }
    }

    /** 改“音乐在投屏设备上播 / 在手机上播”；web = true 存网页投屏的一份，false 存 HDMI 的一份。 */
    fun setCastMusicOnCast(web: Boolean, onCast: Boolean) {
        scope.launch {
            appContext.ebookReadDataStore.edit {
                it[if (web) Keys.WEB_CAST_MUSIC_ON_CAST else Keys.HDMI_CAST_MUSIC_ON_CAST] = onCast
            }
        }
    }

    /** 网页出声的前置放大（0..1）：播放页音量条在投屏出声时控制它。 */
    fun setCastPreamp(v: Float) {
        scope.launch {
            appContext.ebookReadDataStore.edit { it[Keys.WEB_CAST_PREAMP] = v.coerceIn(0f, 1f) }
        }
    }

    /** 改翻页单位（一屏 / 一栏）；web = true 存网页投屏的一份，false 存 HDMI 的一份。 */
    fun setCastTurnUnit(web: Boolean, unit: EbookCastTurnUnit) {
        scope.launch {
            appContext.ebookReadDataStore.edit {
                it[if (web) Keys.WEB_CAST_TURN_UNIT else Keys.HDMI_CAST_TURN_UNIT] = unit.name
            }
        }
    }

    /** 改投屏一栏最大宽度；web = true 存网页投屏的一份，false 存 HDMI 的一份。 */
    fun setCastColumnWidth(web: Boolean, px: Int) {
        scope.launch {
            appContext.ebookReadDataStore.edit {
                it[if (web) Keys.WEB_CAST_COLUMN_WIDTH else Keys.HDMI_CAST_COLUMN_WIDTH] =
                    px.coerceIn(MIN_CAST_COLUMN_WIDTH_PX, MAX_CAST_COLUMN_WIDTH_PX)
            }
        }
    }

}
