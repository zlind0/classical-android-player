package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.accentFor

// FILE 栈的 backend：只读 MusicRootsStore 内存快照（library_files.db 的映射），
// 全程不碰 MediaStore。曲目 id 体系为 "file:<path>"，与 MediaStore 栈不互通。
class FileBackend(
    private val roots: MusicRootsStore,
    private val store: LocalStore,
    override val session: Session,
) : MediaBackend {

    private fun songs(): List<Song> = roots.allSongs()

    private fun albums(): List<Album> = songs().groupBy { it.albumId }
        .map { (aid, ts) ->
            val dirName = ts.first().path.substringBeforeLast('/').substringAfterLast('/')
            Album(
                id = aid,
                title = ts.first().album.ifBlank { dirName },
                artist = ts.map { it.artist }.distinct().let { if (it.size == 1) it.first() else "Various artists" },
                artworkUrl = ts.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
                year = 0,
                songCount = ts.size,
                durationSec = ts.sumOf { it.durationSec },
            )
        }
        .sortedBy { it.title.lowercase() }

    private fun artists(): List<Artist> = songs().groupBy { it.artistId }
        .map { (aid, ts) ->
            Artist(
                id = aid,
                name = ts.first().artist.ifBlank { "Unknown artist" },
                imageUrl = ts.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
                monthlyListeners = 0,
            )
        }
        .sortedBy { it.name.lowercase() }

    private fun LocalPlaylist.toPlaylist(): Playlist {
        val byId = songs().associateBy { it.id }
        val tracks = trackIds.orEmpty().mapNotNull { byId[it] }
        return Playlist(
            id = id,
            title = title ?: "",
            subtitle = (subtitle ?: "").ifBlank { "${tracks.size} song${if (tracks.size == 1) "" else "s"}" },
            coverUrl = tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: "",
            songCount = tracks.size,
            accent = accentFor(id),
        )
    }

    override suspend fun ping(): Boolean = true

    override suspend fun home(): HomeData {
        val albums = albums()
        return HomeData(
            newReleases = albums.take(20),
            mostPlayed = albums.sortedByDescending { it.songCount }.take(20),
            playlists = store.playlists().map { it.toPlaylist() },
            artists = artists().take(40),
            starred = starredSongs().take(40),
        )
    }

    override suspend fun allAlbums(): List<Album> = albums()
    override suspend fun allArtists(): List<Artist> = artists()
    override suspend fun allPlaylists(): List<Playlist> = store.playlists().map { it.toPlaylist() }
    override suspend fun allSongs(): List<Song> = songs()

    override suspend fun songsPage(offset: Int, count: Int): List<Song> =
        songs().drop(offset).take(count)

    override suspend fun starredSongs(): List<Song> {
        val liked = store.likedIds()
        return songs().filter { it.id in liked }
    }

    override suspend fun starredCount(): Int = starredSongs().size

    override suspend fun starredIds(): Set<String> = store.likedIds()

    override suspend fun songFor(id: String): Song? = songs().firstOrNull { it.id == id }

    override suspend fun likedSongIds(ids: List<String>): Set<String> {
        val liked = store.likedIds()
        return ids.filterTo(HashSet()) { it in liked }
    }

    override suspend fun search(query: String): SearchResults {
        val q = query.trim()
        if (q.isBlank()) return SearchResults()
        return SearchResults(
            songs = songs().filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }.take(60),
            albums = albums().filter { it.title.contains(q, true) || it.artist.contains(q, true) }.take(30),
            artists = artists().filter { it.name.contains(q, true) }.take(30),
            playlists = store.playlists().map { it.toPlaylist() }.filter { it.title.contains(q, true) }.take(20),
        )
    }

    override suspend fun radio(seedId: String): List<Song> {
        val all = songs()
        val seed = all.firstOrNull { it.id == seedId }
        val sameArtist = seed?.let { s -> all.filter { it.artistId == s.artistId && it.id != seedId } }.orEmpty()
        val rest = all.filter { it.id != seedId && it !in sameArtist }.shuffled()
        return (sameArtist.shuffled() + rest).take(40)
    }

    override suspend fun detail(kind: String, id: String): DetailData? {
        return when (kind) {
            "album" -> {
                val album = albums().firstOrNull { it.id == id } ?: return null
                val tracks = songs().filter { it.albumId == id }.sortedBy { it.path.lowercase() }
                DetailData(
                    info = DetailInfo(album.title, album.artist, album.artworkUrl, accentFor(id), false, tracks.size, album.typeLabel),
                    tracks = tracks,
                )
            }
            "artist" -> {
                val artist = artists().firstOrNull { it.id == id } ?: return null
                val tracks = songs().filter { it.artistId == id }
                val albs = albums().filter { a -> tracks.any { it.albumId == a.id } }
                DetailData(
                    info = DetailInfo(artist.name, "${tracks.size} song${if (tracks.size == 1) "" else "s"}", artist.imageUrl, accentFor(id), true, tracks.size, "Artist"),
                    tracks = tracks,
                    albums = albs,
                )
            }
            "playlist" -> {
                val pl = store.playlist(id) ?: return null
                val byId = songs().associateBy { it.id }
                val tracks = pl.trackIds.orEmpty().mapNotNull { byId[it] }
                DetailData(
                    info = DetailInfo(pl.title ?: "", pl.subtitle ?: "", tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl ?: "", accentFor(id), false, tracks.size, "Playlist"),
                    tracks = tracks,
                )
            }
            "liked" -> {
                val tracks = starredSongs()
                DetailData(
                    info = DetailInfo("Liked Songs", "${tracks.size} song${if (tracks.size == 1) "" else "s"}", tracks.firstOrNull()?.artworkUrl ?: "", accentFor("liked"), false, tracks.size, "Liked"),
                    tracks = tracks,
                )
            }
            else -> null
        }
    }

    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean = store.setLiked(id, starred)

    override suspend fun createPlaylist(name: String): Boolean { store.createPlaylist(name); return true }

    override suspend fun createPlaylistWithId(name: String): String? = store.createPlaylist(name)

    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean {
        store.updatePlaylist(id, name, comment); return true
    }

    override suspend fun deletePlaylist(id: String): Boolean { store.deletePlaylist(id); return true }

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean { store.addTracks(playlistId, trackIds); return true }
    suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean { store.removeTracks(playlistId, trackIds); return true }

    override val supportsFolders: Boolean get() = true

    override suspend fun browseFolder(folderId: String): FolderContent? {
        val all = songs()
        if (all.isEmpty()) return null
        val dirs = all.map { it.path.substringBeforeLast('/') }
        val base = folderId.ifBlank {
            var prefix = dirs.first().split('/')
            for (d in dirs) {
                val seg = d.split('/')
                var i = 0
                while (i < prefix.size && i < seg.size && prefix[i] == seg[i]) i++
                prefix = prefix.subList(0, i)
            }
            prefix.joinToString("/")
        }
        if (base.isBlank()) return null
        val here = all.filter { it.path.substringBeforeLast('/') == base }.sortedBy { it.title.lowercase() }
        val subdirs = dirs.asSequence()
            .filter { it != base && it.startsWith("$base/") }
            .map { it.removePrefix("$base/").substringBefore('/') }
            .distinct().sortedBy { it.lowercase() }.toList()
        return FolderContent(
            id = base,
            title = if (folderId.isBlank()) "Folders" else base.substringAfterLast('/'),
            folders = subdirs.map { FolderNode("$base/$it", it) },
            songs = here,
        )
    }

    override suspend fun serverLyrics(song: Song): Lyrics? = null

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String =
        songs().firstOrNull { it.id == songId }?.streamUrl ?: ""

    override fun coverArtUrl(id: String, size: Int): String =
        albums().firstOrNull { it.id == id }?.artworkUrl
            ?: songs().firstOrNull { it.id == id }?.artworkUrl ?: ""
}
