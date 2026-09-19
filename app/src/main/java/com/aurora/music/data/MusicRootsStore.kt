package com.aurora.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.musicRootsDataStore by preferencesDataStore(name = "music_roots")

// Classical fork v0.3 (plan §7-13): owns the user's scan roots and the per-root
// file index. Roots live in DataStore; the (potentially large) track index lives
// in one JSON file per root under filesDir and is migrated to Room in v0.4.
class MusicRootsStore(private val context: Context) {

    private val gson = Gson()

    private object Keys {
        val ROOTS = stringPreferencesKey("roots")
        val NEXT_ID = longPreferencesKey("next_id")
    }

    private val indexType = object : TypeToken<List<ScannedTrack>>() {}.type
    private val rootsType = object : TypeToken<List<MusicRoot>>() {}.type

    private val _roots = MutableStateFlow<List<MusicRoot>>(emptyList())
    val roots: StateFlow<List<MusicRoot>> = _roots.asStateFlow()

    private val _counts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val trackCounts: StateFlow<Map<Long, Int>> = _counts.asStateFlow()

    val progress = MutableStateFlow(ScanProgress())

    init {
        _roots.value = readRoots()
        refreshCounts()
    }

    val rootsFlow: Flow<List<MusicRoot>> = context.musicRootsDataStore.data.map { parseRoots(it[Keys.ROOTS]) }

    private fun parseRoots(json: String?): List<MusicRoot> = runCatching {
        if (json.isNullOrBlank()) emptyList()
        else (gson.fromJson<List<MusicRoot>>(json, rootsType) ?: emptyList()).let { list ->
            // 老存档没有 mergeTitles 键（Gson 缺键读成 false），一次性迁成默认开
            if (json.contains("\"mergeTitles\"")) list
            else list.map { it.copy(mergeTitles = true) }
        }
    }.getOrDefault(emptyList())

    private fun readRoots(): List<MusicRoot> = runCatching {
        val file = File(context.filesDir, "music_roots.json")
        if (!file.exists()) emptyList() else parseRoots(file.readText())
    }.getOrDefault(emptyList())

    private suspend fun persistRoots(next: List<MusicRoot>) {
        _roots.value = next
        runCatching { File(context.filesDir, "music_roots.json").writeText(gson.toJson(next)) }
        // mirror into DataStore so future Room migration has a second copy
        runCatching { context.musicRootsDataStore.edit { it[Keys.ROOTS] = gson.toJson(next) } }
    }

    private fun indexFile(rootId: Long) = File(context.filesDir, "scan_$rootId.json")

    fun readIndex(rootId: Long): List<ScannedTrack> = runCatching {
        val f = indexFile(rootId)
        if (!f.exists()) emptyList() else (gson.fromJson<List<ScannedTrack>>(f.readText(), indexType) ?: emptyList())
    }.getOrDefault(emptyList())

    fun writeIndex(rootId: Long, tracks: List<ScannedTrack>) {
        runCatching { indexFile(rootId).writeText(gson.toJson(tracks)) }
        _counts.value = _counts.value + (rootId to tracks.count { it.available })
    }

    fun refreshCounts() {
        _counts.value = _roots.value.associate { it.id to readIndex(it.id).count { t -> t.available } }
    }

    /** All available tracks across enabled roots, as playable Songs. */
    fun allSongs(): List<Song> = _roots.value
        .filter { it.enabled }
        .flatMap { readIndex(it.id) }
        .filter { it.available }
        .map { it.toSong() }

    fun songsOf(rootId: Long): List<Song> =
        readIndex(rootId).filter { it.available }.map { it.toSong() }

    suspend fun addRoot(path: String, displayName: String, type: StorageType): MusicRoot? {
        val norm = File(path).canonicalPath
        if (_roots.value.any { File(it.rootPath).canonicalPath == norm }) return null
        val root = MusicRoot(
            id = System.currentTimeMillis(),
            rootPath = norm,
            displayName = displayName,
            storageType = type,
        )
        persistRoots(_roots.value + root)
        return root
    }

    suspend fun removeRoot(id: Long) {
        persistRoots(_roots.value.filterNot { it.id == id })
        runCatching { indexFile(id).delete() }
        _counts.value = _counts.value - id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        persistRoots(_roots.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun setMergeTitles(id: Long, merge: Boolean) {
        persistRoots(_roots.value.map { if (it.id == id) it.copy(mergeTitles = merge) else it })
    }

    suspend fun stampScan(id: Long) {
        persistRoots(_roots.value.map { if (it.id == id) it.copy(lastScanTime = System.currentTimeMillis()) else it })
    }

    /** Drops index entries whose files are gone (plan §12 "Clean Missing Files"). */
    suspend fun cleanMissing(id: Long): Int {
        val kept = readIndex(id).filter { it.available && File(it.path).exists() }
        val removed = readIndex(id).size - kept.size
        writeIndex(id, kept)
        return removed
    }
}

fun ScannedTrack.toSong(): Song {
    val fileName = path.substringAfterLast('/')
    return Song(
        id = "file:$path",
        title = title.ifBlank { fileName.substringBeforeLast('.') },
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        durationSec = durationSec,
        streamUrl = File(path).let { if (it.exists()) android.net.Uri.fromFile(it).toString() else "" },
        albumId = "dir:${File(path).parent ?: ""}",
        artistId = artist,
        suffix = fileName.substringAfterLast('.', ""),
        path = path,
        accent = accentFor(path),
        codecMime = codec,
    )
}
