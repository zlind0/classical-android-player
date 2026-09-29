package com.aurora.music.data.ebook

import com.aurora.music.tts.mergeSystemTtsEngines
import org.junit.Assert.assertEquals
import org.junit.Test

class SysTtsEngineTest {

    @Test
    fun merge_prefersLabelsAndDefaultFirst() {
        val out = mergeSystemTtsEngines(
            apiEngines = listOf("com.google.android.tts" to "Google"),
            pmServices = listOf(
                "com.google.android.tts" to "Google TTS",
                "org.nobody.multitts" to null,
            ),
            defaultPackage = "org.nobody.multitts",
        )
        // 默认引擎排首；PM 的 label 补上 API 缺失的名字
        assertEquals(
            listOf(
                "org.nobody.multitts" to "org.nobody.multitts",
                "com.google.android.tts" to "Google",
            ),
            out,
        )
    }

    @Test
    fun merge_pmLabelFillsBlankApiLabel() {
        val out = mergeSystemTtsEngines(
            apiEngines = listOf("org.nobody.multitts" to "org.nobody.multitts"),
            pmServices = listOf("org.nobody.multitts" to "MultiTTS"),
            defaultPackage = null,
        )
        assertEquals(listOf("org.nobody.multitts" to "MultiTTS"), out)
    }

    @Test
    fun merge_noDefault_sortsByLabel() {
        val out = mergeSystemTtsEngines(
            apiEngines = listOf("b.pkg" to "Beta", "a.pkg" to "Alpha"),
            pmServices = emptyList(),
            defaultPackage = null,
        )
        assertEquals(listOf("a.pkg" to "Alpha", "b.pkg" to "Beta"), out)
    }

    @Test
    fun merge_blanksIgnored() {
        val out = mergeSystemTtsEngines(
            apiEngines = listOf("" to "X"),
            pmServices = listOf("  " to "Y"),
            defaultPackage = null,
        )
        assertEquals(emptyList<Pair<String, String>>(), out)
    }
}
