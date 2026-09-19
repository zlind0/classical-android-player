package com.aurora.music.navigation

import android.net.Uri

/**
 * iOS5 rewrite navigation map. Five iOS-style tabs; browsing pages hang off
 * MORE; search is not a tab (opened from the magnifier on browse pages);
 * the player is an overlay, not a route.
 */
object Ios5Routes {
    // ---- five tabs ----
    const val HOME = "ios5_home"
    const val PLAYLISTS = "ios5_playlists"
    const val ARTISTS = "ios5_artists"
    const val MORE = "ios5_more"
    const val SETTINGS = "ios5_settings"

    // ---- MORE browse pages ----
    const val MORE_SONGS = "ios5_more_songs"
    const val MORE_ALBUMS = "ios5_more_albums"
    const val MORE_GENRES = "ios5_more_genres"
    const val MORE_COMPOSERS = "ios5_more_composers"
    const val MORE_FOLDERS = "ios5_more_folders"
    const val FOLDER = "ios5_folder?fid={fid}&title={title}"
    fun folder(fid: String = "", title: String = "") =
        "ios5_folder?fid=${Uri.encode(fid)}&title=${Uri.encode(title)}"

    // ---- unified detail (album / artist / playlist / smart / liked / genre / composer) ----
    const val DETAIL = "ios5_detail/{kind}/{id}/{title}"
    fun detail(kind: String, id: String, title: String = "") =
        "ios5_detail/${Uri.encode(kind)}/${Uri.encode(id)}/${Uri.encode(title)}"

    // ---- search (scope = songs|albums|genres|composers|all) ----
    const val SEARCH = "ios5_search?scope={scope}"
    fun search(scope: String = "all") = "ios5_search?scope=${Uri.encode(scope)}"

    // ---- settings sub-pages (reuse existing screen files, string values kept stable) ----
    const val SETTINGS_APPEARANCE = "settings_appearance"
    const val SETTINGS_PLAYBACK = "settings_playback"
    const val SETTINGS_EQ = "settings_eq"
    const val SETTINGS_STORAGE = "settings_storage"
    const val SETTINGS_GESTURES = "settings_gestures"
    const val SETTINGS_INTEGRATIONS = "settings_integrations"
    const val SETTINGS_ABOUT = "settings_about"
    const val SETTINGS_VISUALIZER = "settings_visualizer"
    const val SETTINGS_SONIC = "settings_sonic"
    const val SETTINGS_PERMISSIONS = "settings_permissions"
    const val SETTINGS_BACKUP = "settings_backup"
    const val SETTINGS_SOURCES = "settings_sources"

    // ---- library tools (folded into Settings, were drawer/top-level entries) ----
    const val HISTORY = "history"
    const val STATS = "stats"
    const val DUPLICATES = "duplicates"
    const val SMART_EDIT = "smart_edit?id={id}"
    fun smartEdit(id: String = "") = "smart_edit?id=${Uri.encode(id)}"
    const val TAG_EDIT = "tag_edit/{songId}"
    fun tagEdit(songId: String) = "tag_edit/${Uri.encode(songId)}"
}

data class Ios5Tab(
    val route: String,
    val label: String,
)

val ios5Tabs = listOf(
    Ios5Tab(Ios5Routes.HOME, "首页"),
    Ios5Tab(Ios5Routes.PLAYLISTS, "歌单"),
    Ios5Tab(Ios5Routes.ARTISTS, "艺人"),
    Ios5Tab(Ios5Routes.MORE, "更多"),
    Ios5Tab(Ios5Routes.SETTINGS, "设置"),
)

val ios5TabRoutes: Set<String> = ios5Tabs.map { it.route }.toSet()
