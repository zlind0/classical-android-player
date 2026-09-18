package com.aurora.music.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val PLAYLISTS = "tab_playlists"
    const val ARTISTS = "tab_artists"
    const val MORE = "tab_more"
    // More → single-category browsing (songs/albums/genres/composers)
    const val BROWSE = "browse/{kind}"
    fun browse(kind: String) = "browse/$kind"
    // full player page (tab bar stays visible underneath)
    const val PLAYER = "player"
    const val SETTINGS = "settings"
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
    const val HISTORY = "history"
    const val STATS = "stats"
    const val DUPLICATES = "duplicates"
    const val DETAIL = "detail/{kind}/{id}"
    fun detail(kind: String, id: String) = "detail/$kind/$id"

    // folder ids contain slashes so ride as encoded query params
    const val FOLDERS = "folders?fid={fid}&title={title}"
    fun folders(fid: String = "", title: String = "") =
        "folders?fid=${android.net.Uri.encode(fid)}&title=${android.net.Uri.encode(title)}"

    const val SMART_EDIT = "smart_edit?id={id}"
    fun smartEdit(id: String = "") = "smart_edit?id=${android.net.Uri.encode(id)}"

    const val TAG_EDIT = "tag_edit/{songId}"
    fun tagEdit(songId: String) = "tag_edit/${android.net.Uri.encode(songId)}"
}

data class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val topLevelDestinations = listOf(
    TopLevelDestination(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    TopLevelDestination(Routes.PLAYLISTS, "Playlists", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    TopLevelDestination(Routes.ARTISTS, "Artists", Icons.Filled.Person, Icons.Outlined.Person),
    TopLevelDestination(Routes.MORE, "More", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz),
    TopLevelDestination(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)
