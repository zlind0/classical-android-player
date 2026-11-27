package com.aurora.music.navigation

import com.aurora.music.data.SavedScroll
import com.aurora.music.data.SavedStackEntry
import com.aurora.music.data.SavedUiState
import com.aurora.music.data.UiStateStore
import com.aurora.music.data.normalizeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Ios5TabNavTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parseIos5Route_roundTripsPercentEncodedParamsAndPreservesPlus() {
        val detail = parseIos5Route("ios5_detail/album/id%2F1/A%2BB%20%26%20C%3F")
        assertEquals("ios5_detail", detail.base)
        assertEquals("album", detail.pathSegments[1])
        assertEquals("id/1", detail.pathSegments[2])
        assertEquals("A+B & C?", detail.pathSegments[3])

        val emptyTitle = parseIos5Route("ios5_detail/album/123/")
        assertEquals("ios5_detail", emptyTitle.base)
        assertEquals("123", emptyTitle.pathSegments[2])
        assertEquals("", emptyTitle.pathSegments[3])

        val ebook = parseIos5Route("ios5_ebook_lib?shelf=false&rootId=7&rpath=%2Fbooks%2FC%2B%2B&dir=%2Fbooks%2FC%2B%2B%2Fsub&title=%E4%B8%AD%E6%96%87%20%2B%201")
        assertEquals("ios5_ebook_lib", ebook.base)
        assertEquals("false", ebook.query["shelf"])
        assertEquals("7", ebook.query["rootId"])
        assertEquals("/books/C++", ebook.query["rpath"])
        assertEquals("/books/C++/sub", ebook.query["dir"])
        assertEquals("中文 + 1", ebook.query["title"])

        val tagEdit = parseIos5Route("tag_edit/song%2F42")
        assertEquals("tag_edit", tagEdit.base)
        assertEquals("song/42", tagEdit.pathSegments[1])
    }

    @Test
    fun defaultTitleForRoute_resolvesTabsStaticAndParameterizedRoutes() {
        assertEquals("设置", defaultTitleForRoute(Ios5Routes.SETTINGS))
        assertEquals("歌曲介绍", defaultTitleForRoute(Ios5Routes.SETTINGS_INTRO))
        assertEquals("默认书架", defaultTitleForRoute("ios5_ebook_lib?shelf=true&rootId=0&rpath=&dir=&title="))
        assertEquals("古典乐", defaultTitleForRoute("ios5_detail/album/a1/%E5%8F%A4%E5%85%B8%E4%B9%90"))
        assertEquals("喜欢的歌曲", defaultTitleForRoute("ios5_detail/liked/fav/"))
        assertEquals("新建智能歌单", defaultTitleForRoute("smart_edit?id="))
        assertEquals("编辑智能歌单", defaultTitleForRoute("smart_edit?id=s1"))
    }

    @Test
    fun uiStateStore_initializesAllTabsAndPersistsPerTabStacks() {
        val file = File(tmp.root, "ui_state.json")
        val store = UiStateStore(file, autoFlush = false)

        assertEquals(Ios5Routes.HOME, store.activeTab())
        val initial = store.tabStacks()
        assertEquals(ios5Tabs.size, initial.size)
        for (tab in ios5Tabs) {
            val stack = initial[tab.route].orEmpty()
            assertEquals(1, stack.size)
            assertEquals(tab.route, stack[0].route)
            assertEquals(tab.label, stack[0].title)
        }

        // Push screens onto two separate tabs and verify full per-tab stacks survive reload.
        val artistRoot = initial[Ios5Routes.ARTISTS]!!.first()
        val artistEntry = SavedStackEntry(id = "e-artist", route = "ios5_detail/artist/a1/Bach", title = "Bach")
        val albumEntry = SavedStackEntry(id = "e-album", route = "ios5_detail/album/al1/Goldberg", title = "Goldberg")
        val settingsRoot = initial[Ios5Routes.SETTINGS]!!.first()
        val introEntry = SavedStackEntry(id = "e-intro", route = Ios5Routes.SETTINGS_INTRO, title = "歌曲介绍")

        val nextStacks = initial.toMutableMap()
        nextStacks[Ios5Routes.ARTISTS] = listOf(artistRoot, artistEntry, albumEntry)
        nextStacks[Ios5Routes.SETTINGS] = listOf(settingsRoot, introEntry)
        store.saveNavigation(Ios5Routes.ARTISTS, nextStacks)
        store.saveScroll(Ios5Routes.ARTISTS, "e-artist", "detail:artist|a1", 4, 32)
        store.saveScroll(Ios5Routes.ARTISTS, "e-album", "detail:album|al1", 9, 120)
        store.saveExpanded(Ios5Routes.ARTISTS, "e-album", "merge", setOf("g:0:BWV"))
        store.saveExtra(Ios5Routes.ARTISTS, "e-album", "filter", "flac")
        store.flushNow()

        val reloaded = UiStateStore(file, autoFlush = false)
        assertEquals(Ios5Routes.ARTISTS, reloaded.activeTab())
        assertEquals(3, reloaded.tabStacks()[Ios5Routes.ARTISTS]?.size)
        assertEquals(2, reloaded.tabStacks()[Ios5Routes.SETTINGS]?.size)
        assertEquals(SavedScroll(4, 32), reloaded.scrollOf(Ios5Routes.ARTISTS, "e-artist", "detail:artist|a1"))
        assertEquals(SavedScroll(9, 120), reloaded.scrollOf(Ios5Routes.ARTISTS, "e-album", "detail:album|al1"))
        assertEquals(setOf("g:0:BWV"), reloaded.expandedOf(Ios5Routes.ARTISTS, "e-album", "merge"))
        assertEquals("flac", reloaded.extraOf(Ios5Routes.ARTISTS, "e-album", "filter"))
    }

    @Test
    fun uiStateStore_poppingDiscardsEntryStateFromDiskAndIgnoresLateWrites() {
        val file = File(tmp.root, "ui_state.json")
        val store = UiStateStore(file, autoFlush = false)
        val initial = store.tabStacks()
        val artistRoot = initial[Ios5Routes.ARTISTS]!!.first()
        val artistEntry = SavedStackEntry(id = "e-artist", route = "ios5_detail/artist/a1/Bach", title = "Bach")
        val albumEntry = SavedStackEntry(id = "e-album-popped", route = "ios5_detail/album/al1/Goldberg", title = "Goldberg")

        val pushed = initial.toMutableMap()
        pushed[Ios5Routes.ARTISTS] = listOf(artistRoot, artistEntry, albumEntry)
        store.saveNavigation(Ios5Routes.ARTISTS, pushed)
        store.saveScroll(Ios5Routes.ARTISTS, "e-artist", "detail:artist|a1", 5, 10)
        store.saveScroll(Ios5Routes.ARTISTS, "e-album-popped", "detail:album|al1", 12, 88)
        store.saveExpanded(Ios5Routes.ARTISTS, "e-album-popped", "merge", setOf("g:0:Aria"))
        store.saveExtra(Ios5Routes.ARTISTS, "e-album-popped", "query", "aria")

        // Pop albumEntry
        val popped = pushed.toMutableMap()
        popped[Ios5Routes.ARTISTS] = listOf(artistRoot, store.tabStacks()[Ios5Routes.ARTISTS]!![1])
        store.saveNavigation(Ios5Routes.ARTISTS, popped)

        // Simulate late debounced write for the popped entry
        store.saveScroll(Ios5Routes.ARTISTS, "e-album-popped", "detail:album|al1", 15, 200)
        store.saveExpanded(Ios5Routes.ARTISTS, "e-album-popped", "merge", setOf("g:1:Var"))
        store.saveExtra(Ios5Routes.ARTISTS, "e-album-popped", "query", "var")
        store.flushNow()

        val json = file.readText()
        assertFalse(json.contains("e-album-popped"))
        assertFalse(json.contains("g:0:Aria"))
        assertFalse(json.contains("g:1:Var"))

        val reloaded = UiStateStore(file, autoFlush = false)
        assertEquals(SavedScroll(5, 10), reloaded.scrollOf(Ios5Routes.ARTISTS, "e-artist", "detail:artist|a1"))
        assertEquals(SavedScroll(0, 0), reloaded.scrollOf(Ios5Routes.ARTISTS, "e-album-popped", "detail:album|al1"))
        assertTrue(reloaded.expandedOf(Ios5Routes.ARTISTS, "e-album-popped", "merge").isEmpty())
        assertNull(reloaded.extraOf(Ios5Routes.ARTISTS, "e-album-popped", "query"))
    }

    @Test
    fun normalizeUiState_migratesLegacyNavRoutesAndDropsGlobalScrollExpanded() {
        val legacy = SavedUiState(
            navRoutes = listOf(
                Ios5Routes.HOME,
                Ios5Routes.SETTINGS,
                Ios5Routes.SETTINGS_INTRO,
                "ebook_reader?epath=%2Fbooks%2FDune.epub",
            ),
            lastTab = Ios5Routes.SETTINGS,
            scroll = mapOf("detail:album|old" to SavedScroll(20, 40)),
            expanded = mapOf("detail_expand:album|old" to listOf("g:0:1")),
        )
        val (normalized, changed) = normalizeUiState(legacy)
        assertTrue(changed)
        assertEquals(Ios5Routes.SETTINGS, normalized.activeTab)
        assertEquals("/books/Dune.epub", normalized.readerPath)
        assertNull(normalized.navRoutes)
        assertNull(normalized.lastTab)
        assertNull(normalized.scroll)
        assertNull(normalized.expanded)

        val settingsStack = normalized.tabStacks?.get(Ios5Routes.SETTINGS).orEmpty()
        assertEquals(2, settingsStack.size)
        assertEquals(Ios5Routes.SETTINGS, settingsStack[0].route)
        assertEquals("设置", settingsStack[0].title)
        assertEquals(Ios5Routes.SETTINGS_INTRO, settingsStack[1].route)
        assertEquals("歌曲介绍", settingsStack[1].title)
        assertEquals(ios5Tabs.size, normalized.tabStacks?.size)
    }
}
