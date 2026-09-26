package com.aurora.music.data.ebook

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

// 阅读设置：页面风格 / 字体 / 字号。复用 DataStore 模式，与音乐设置互相独立。
class EbookPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE = floatPreferencesKey("font_size_sp")
        val FONT_PATH = stringPreferencesKey("font_path")
    }

    private val _prefs = MutableStateFlow(EbookReadPrefs())
    val prefs: StateFlow<EbookReadPrefs> = _prefs.asStateFlow()

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
    }

    suspend fun initial(): EbookReadPrefs = prefs.first()

    fun setTheme(t: EbookTheme) {
        scope.launch { appContext.ebookReadDataStore.edit { it[Keys.THEME] = t.name } }
    }

    fun setFontSize(sp: Float) {
        scope.launch { appContext.ebookReadDataStore.edit { it[Keys.FONT_SIZE] = sp.coerceIn(12f, 28f) } }
    }

    fun setFontPath(path: String) {
        scope.launch { appContext.ebookReadDataStore.edit { it[Keys.FONT_PATH] = path } }
    }
}
