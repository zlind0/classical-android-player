package com.aurora.music.data.cast

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest

/**
 * 一台远端设备的完整资料库快照。原来的实现把整份 [RemoteLibraryWire] 序列化成一个巨大的
 * JSON 再一次性读回；这里改为每台设备一个 SQLite 文件，按表增量读取，启动更快。
 */
internal data class RemoteLibrarySnapshot(
    val deviceId: String = "",
    val deviceName: String = "",
    val host: String = "",
    val port: Int = 0,
    val fetchedAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val online: Boolean = false,
    val payload: RemoteLibraryWire = RemoteLibraryWire(),
)

/**
 * 远端资料库快照的 SQLite 存储。
 *
 * 设计要点：
 * - 每台设备一个 `.db` 文件（`filesDir/remote_library/<sha1(deviceId).take(20)>.db`）；
 * - 表按数据类型拆开（曲目/电子书/有声书/章节），列表顺序用 `ord` 保留；
 * - 版本没变时只更新 meta 表，不重写曲目表；
 * - 过期清理只需要关掉连接后删文件，没有额外索引要维护。
 *
 * 数据库只当缓存用，schema 变化时直接重建，不做迁移。
 */
internal object RemoteLibraryDb {

    private const val SCHEMA_VERSION = 2
    private const val DB_EXT = ".db"

    fun file(dir: File, deviceId: String): File = File(dir, safeId(deviceId) + DB_EXT)

    fun readAll(dir: File): List<RemoteLibrarySnapshot> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(DB_EXT) }
            ?.mapNotNull { runCatching { read(it) }.getOrNull() }
            .orEmpty()

    fun read(file: File): RemoteLibrarySnapshot? {
        if (!file.isFile) return null
        val db = open(file)
        return db.use {
            val meta = HashMap<String, String>()
            it.rawQuery("SELECT k, v FROM meta", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
            val deviceId = meta["deviceId"].orEmpty()
            if (deviceId.isBlank()) return@use null

            val music = ArrayList<RemoteTrackWire>()
            it.rawQuery(
                "SELECT id, title, artist, album, albumId, artistId, durationSec, trackNumber, " +
                    "discNumber, hasArt, mergeTitles, path FROM tracks ORDER BY ord",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    music += RemoteTrackWire(
                        id = c.getString(0),
                        title = c.getString(1),
                        artist = c.getString(2),
                        album = c.getString(3),
                        albumId = c.getString(4),
                        artistId = c.getString(5),
                        durationSec = c.getInt(6),
                        trackNumber = c.getInt(7),
                        discNumber = c.getInt(8),
                        hasArt = c.getInt(9) != 0,
                        mergeTitles = c.getInt(10) != 0,
                        path = c.getString(11),
                    )
                }
            }

            val roots = ArrayList<RemoteEbookRootWire>()
            it.rawQuery(
                "SELECT key, rootPath, displayName, shelf, bookCount, randomPick FROM ebook_roots ORDER BY ord",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    roots += RemoteEbookRootWire(
                        key = c.getString(0),
                        rootPath = c.getString(1),
                        displayName = c.getString(2),
                        shelf = c.getInt(3) != 0,
                        bookCount = c.getInt(4),
                        randomPick = c.getInt(5) != 0,
                    )
                }
            }

            val ebooks = ArrayList<RemoteEbookWire>()
            it.rawQuery(
                "SELECT path, title, author, size, hasCover, rootKey FROM ebooks ORDER BY ord",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    ebooks += RemoteEbookWire(
                        path = c.getString(0),
                        title = c.getString(1),
                        author = c.getString(2),
                        size = c.getLong(3),
                        hasCover = c.getInt(4) != 0,
                        rootKey = c.getString(5),
                    )
                }
            }

            val chaptersByBook = HashMap<Long, MutableList<RemoteChapterWire>>()
            it.rawQuery(
                "SELECT bookOrd, path, title, trackNumber, durationSec, size FROM chapters " +
                    "ORDER BY bookOrd, ord",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    chaptersByBook.getOrPut(c.getLong(0)) { mutableListOf() } += RemoteChapterWire(
                        path = c.getString(1),
                        title = c.getString(2),
                        trackNumber = c.getInt(3),
                        durationSec = c.getInt(4),
                        size = c.getLong(5),
                    )
                }
            }

            val audiobooks = ArrayList<RemoteAudiobookWire>()
            it.rawQuery("SELECT id, title, hasCover FROM audiobooks ORDER BY ord", null).use { c ->
                var ord = 0L
                while (c.moveToNext()) {
                    audiobooks += RemoteAudiobookWire(
                        id = c.getString(0),
                        title = c.getString(1),
                        hasCover = c.getInt(2) != 0,
                        chapters = chaptersByBook[ord].orEmpty(),
                    )
                    ord++
                }
            }

            RemoteLibrarySnapshot(
                deviceId = deviceId,
                deviceName = meta["deviceName"].orEmpty(),
                host = meta["host"].orEmpty(),
                port = meta["port"]?.toIntOrNull() ?: 0,
                fetchedAt = meta["fetchedAt"]?.toLongOrNull() ?: 0L,
                lastSeenAt = meta["lastSeenAt"]?.toLongOrNull() ?: 0L,
                online = false,
                payload = RemoteLibraryWire(
                    deviceId = meta["payloadDeviceId"].orEmpty(),
                    deviceName = meta["payloadDeviceName"].orEmpty(),
                    generatedAt = meta["generatedAt"]?.toLongOrNull() ?: 0L,
                    libraryUpdatedAt = meta["libraryUpdatedAt"]?.toLongOrNull() ?: 0L,
                    musicPathOrder = meta["musicPathOrder"] == "1",
                    music = music,
                    ebookRoots = roots,
                    ebooks = ebooks,
                    audiobooks = audiobooks,
                ),
            )
        }
    }

    /** 全量写一份快照：一个事务里清空并重建所有表。 */
    fun write(dir: File, snapshot: RemoteLibrarySnapshot) {
        val db = open(file(dir, snapshot.deviceId))
        db.use {
            it.beginTransaction()
            try {
                it.delete("meta", null, null)
                it.delete("tracks", null, null)
                it.delete("ebook_roots", null, null)
                it.delete("ebooks", null, null)
                it.delete("audiobooks", null, null)
                it.delete("chapters", null, null)

                val meta = ContentValues()
                fun put(key: String, value: String) {
                    meta.clear()
                    meta.put("k", key)
                    meta.put("v", value)
                    it.insertWithOnConflict("meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
                }
                put("deviceId", snapshot.deviceId)
                put("deviceName", snapshot.deviceName)
                put("host", snapshot.host)
                put("port", snapshot.port.toString())
                put("fetchedAt", snapshot.fetchedAt.toString())
                put("lastSeenAt", snapshot.lastSeenAt.toString())
                put("payloadDeviceId", snapshot.payload.deviceId)
                put("payloadDeviceName", snapshot.payload.deviceName)
                put("generatedAt", snapshot.payload.generatedAt.toString())
                put("libraryUpdatedAt", snapshot.payload.libraryUpdatedAt.toString())
                put("musicPathOrder", if (snapshot.payload.musicPathOrder) "1" else "0")

                val track = ContentValues()
                snapshot.payload.music.forEachIndexed { ord, t ->
                    track.clear()
                    track.put("ord", ord.toLong())
                    track.put("id", t.id)
                    track.put("title", t.title)
                    track.put("artist", t.artist)
                    track.put("album", t.album)
                    track.put("albumId", t.albumId)
                    track.put("artistId", t.artistId)
                    track.put("durationSec", t.durationSec)
                    track.put("trackNumber", t.trackNumber)
                    track.put("discNumber", t.discNumber)
                    track.put("hasArt", if (t.hasArt) 1 else 0)
                    track.put("mergeTitles", if (t.mergeTitles) 1 else 0)
                    track.put("path", t.path)
                    it.insert("tracks", null, track)
                }

                val root = ContentValues()
                snapshot.payload.ebookRoots.forEachIndexed { ord, r ->
                    root.clear()
                    root.put("ord", ord.toLong())
                    root.put("key", r.key)
                    root.put("rootPath", r.rootPath)
                    root.put("displayName", r.displayName)
                    root.put("shelf", if (r.shelf) 1 else 0)
                    root.put("bookCount", r.bookCount)
                    root.put("randomPick", if (r.randomPick) 1 else 0)
                    it.insert("ebook_roots", null, root)
                }

                val ebook = ContentValues()
                snapshot.payload.ebooks.forEachIndexed { ord, b ->
                    ebook.clear()
                    ebook.put("ord", ord.toLong())
                    ebook.put("path", b.path)
                    ebook.put("title", b.title)
                    ebook.put("author", b.author)
                    ebook.put("size", b.size)
                    ebook.put("hasCover", if (b.hasCover) 1 else 0)
                    ebook.put("rootKey", b.rootKey)
                    it.insert("ebooks", null, ebook)
                }

                val book = ContentValues()
                val chapter = ContentValues()
                snapshot.payload.audiobooks.forEachIndexed { bookOrd, b ->
                    book.clear()
                    book.put("ord", bookOrd.toLong())
                    book.put("id", b.id)
                    book.put("title", b.title)
                    book.put("hasCover", if (b.hasCover) 1 else 0)
                    it.insert("audiobooks", null, book)

                    b.chapters.forEachIndexed { chapterOrd, c ->
                        chapter.clear()
                        chapter.put("bookOrd", bookOrd.toLong())
                        chapter.put("ord", chapterOrd.toLong())
                        chapter.put("path", c.path)
                        chapter.put("title", c.title)
                        chapter.put("trackNumber", c.trackNumber)
                        chapter.put("durationSec", c.durationSec)
                        chapter.put("size", c.size)
                        it.insert("chapters", null, chapter)
                    }
                }

                it.setTransactionSuccessful()
            } finally {
                it.endTransaction()
            }
        }
    }

    /** 版本号没变时只刷新 meta 表（主机、端口、最后在线时间），不动曲目表。 */
    fun updateMeta(dir: File, snapshot: RemoteLibrarySnapshot) {
        val file = file(dir, snapshot.deviceId)
        if (!file.isFile) {
            write(dir, snapshot)
            return
        }
        val db = open(file)
        db.use {
            it.beginTransaction()
            try {
                val meta = ContentValues()
                fun put(key: String, value: String) {
                    meta.clear()
                    meta.put("k", key)
                    meta.put("v", value)
                    it.insertWithOnConflict("meta", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
                }
                put("deviceName", snapshot.deviceName)
                put("host", snapshot.host)
                put("port", snapshot.port.toString())
                put("lastSeenAt", snapshot.lastSeenAt.toString())
                it.setTransactionSuccessful()
            } finally {
                it.endTransaction()
            }
        }
    }

    /** 30 天过期：连接都已关闭，直接删掉数据库文件（含可能残留的 journal/wal）。 */
    fun delete(dir: File, deviceId: String) {
        val f = file(dir, deviceId)
        runCatching {
            f.delete()
            File(f.path + "-journal").delete()
            File(f.path + "-wal").delete()
            File(f.path + "-shm").delete()
        }
    }

    private fun open(file: File): SQLiteDatabase {
        file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        if (db.version != SCHEMA_VERSION) {
            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE IF EXISTS meta")
                db.execSQL("DROP TABLE IF EXISTS tracks")
                db.execSQL("DROP TABLE IF EXISTS ebook_roots")
                db.execSQL("DROP TABLE IF EXISTS ebooks")
                db.execSQL("DROP TABLE IF EXISTS audiobooks")
                db.execSQL("DROP TABLE IF EXISTS chapters")
                db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY NOT NULL, v TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE tracks (" +
                        "ord INTEGER PRIMARY KEY NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, " +
                        "artist TEXT NOT NULL, album TEXT NOT NULL, albumId TEXT NOT NULL, " +
                        "artistId TEXT NOT NULL, durationSec INTEGER NOT NULL, trackNumber INTEGER NOT NULL, " +
                        "discNumber INTEGER NOT NULL, hasArt INTEGER NOT NULL, mergeTitles INTEGER NOT NULL, " +
                        "path TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE ebook_roots (" +
                        "ord INTEGER PRIMARY KEY NOT NULL, key TEXT NOT NULL, rootPath TEXT NOT NULL, " +
                        "displayName TEXT NOT NULL, shelf INTEGER NOT NULL, bookCount INTEGER NOT NULL, " +
                        "randomPick INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE ebooks (" +
                        "ord INTEGER PRIMARY KEY NOT NULL, path TEXT NOT NULL, title TEXT NOT NULL, " +
                        "author TEXT NOT NULL, size INTEGER NOT NULL, hasCover INTEGER NOT NULL, " +
                        "rootKey TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE audiobooks (" +
                        "ord INTEGER PRIMARY KEY NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, " +
                        "hasCover INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE chapters (" +
                        "bookOrd INTEGER NOT NULL, ord INTEGER NOT NULL, path TEXT NOT NULL, " +
                        "title TEXT NOT NULL, trackNumber INTEGER NOT NULL, durationSec INTEGER NOT NULL, " +
                        "size INTEGER NOT NULL, PRIMARY KEY(bookOrd, ord))",
                )
                db.version = SCHEMA_VERSION
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return db
    }

    private fun safeId(s: String): String = sha1(s).take(20)

    private fun sha1(s: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(s.hashCode().toString())
}
