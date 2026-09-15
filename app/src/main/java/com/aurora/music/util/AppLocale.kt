package com.aurora.music.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

// Industry-standard Android localization with an in-app override:
// - UI strings live in res/values/strings.xml (English default) plus one
//   res/values-<qualifier>/strings.xml per language (e.g. values-zh).
// - Adding a language = adding a values-XX folder. No code changes.
// - AppLocale lets the user force a language or follow the system. The choice
//   is stored in SharedPreferences (synchronous read, needed in
//   attachBaseContext) and applied by wrapping the base context.
object AppLocale {
    const val SYSTEM = ""
    const val CHINESE = "zh-CN"
    const val ENGLISH = "en"

    val options: List<String> = listOf(SYSTEM, CHINESE, ENGLISH)

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    fun persistedTag(context: Context): String =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
        }.getOrDefault(SYSTEM)

    fun setTag(context: Context, tag: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        }
        // recreate so resources reload in the new locale
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) { ctx.recreate(); return }
            ctx = ctx.baseContext
        }
    }

    fun wrap(context: Context): Context {
        val tag = persistedTag(context)
        if (tag.isBlank()) return context
        val locale = localeFor(tag) ?: return context
        return wrapWith(context, locale)
    }

    fun displayName(tag: String): Int = when (tag) {
        CHINESE -> com.aurora.music.R.string.lang_chinese
        ENGLISH -> com.aurora.music.R.string.lang_english
        else -> com.aurora.music.R.string.lang_system
    }

    private fun localeFor(tag: String): Locale? = runCatching {
        when (tag) {
            CHINESE -> Locale.SIMPLIFIED_CHINESE
            ENGLISH -> Locale.ENGLISH
            else -> Locale.forLanguageTag(tag)
        }
    }.getOrNull()

    private fun wrapWith(context: Context, locale: Locale): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /** System locale's language for informational use. */
    fun systemIsChinese(context: Context): Boolean {
        val locale = if (Build.VERSION.SDK_INT >= 24) {
            context.resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION") context.resources.configuration.locale
        }
        return locale?.language == "zh"
    }
}
