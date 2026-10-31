use crate::models::*;
use crate::util::{self, sha1_hex};
use anyhow::{anyhow, Context, Result};
use rusqlite::{params, Connection, OptionalExtension};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS roots (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    path         TEXT NOT NULL UNIQUE,
    kind         TEXT NOT NULL CHECK (kind IN ('music','audiobook','book')),
    music_mode   TEXT,
    fast_scan    INTEGER NOT NULL DEFAULT 0,
    display_name TEXT NOT NULL,
    created_at   INTEGER NOT NULL,
    last_scan_at INTEGER,
    scan_token   INTEGER NOT NULL DEFAULT 0,
    item_count   INTEGER NOT NULL DEFAULT 0,
    scan_state   TEXT NOT NULL DEFAULT 'idle'
);

CREATE TABLE IF NOT EXISTS tracks (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    root_id      INTEGER NOT NULL REFERENCES roots(id) ON DELETE CASCADE,
    path         TEXT NOT NULL,
    rel_path     TEXT NOT NULL,
    file_name    TEXT NOT NULL,
    title        TEXT NOT NULL,
    artist       TEXT NOT NULL DEFAULT '',
    album        TEXT NOT NULL DEFAULT '',
    album_artist TEXT NOT NULL DEFAULT '',
    genre        TEXT NOT NULL DEFAULT '',
    year         INTEGER NOT NULL DEFAULT 0,
    duration_sec INTEGER NOT NULL DEFAULT 0,
    track_no     INTEGER NOT NULL DEFAULT 0,
    disc_no      INTEGER NOT NULL DEFAULT 0,
    size         INTEGER NOT NULL DEFAULT 0,
    mtime        INTEGER NOT NULL DEFAULT 0,
    from_video   INTEGER NOT NULL DEFAULT 0,
    has_art      INTEGER NOT NULL DEFAULT 0,
    art_key      TEXT,
    sort_path    TEXT NOT NULL DEFAULT '',
    scan_token   INTEGER NOT NULL DEFAULT 0,
    UNIQUE (root_id, path)
);
CREATE INDEX IF NOT EXISTS idx_tracks_root ON tracks(root_id);
CREATE INDEX IF NOT EXISTS idx_tracks_artist ON tracks(artist COLLATE NOCASE, album COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_tracks_album ON tracks(album COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_tracks_scan ON tracks(root_id, scan_token);

CREATE TABLE IF NOT EXISTS audiobooks (
    id            TEXT PRIMARY KEY,
    root_id       INTEGER NOT NULL REFERENCES roots(id) ON DELETE CASCADE,
    rel_path      TEXT NOT NULL,
    dir_path      TEXT NOT NULL,
    title         TEXT NOT NULL,
    chapter_count INTEGER NOT NULL DEFAULT 0,
    duration_sec  INTEGER NOT NULL DEFAULT 0,
    has_art       INTEGER NOT NULL DEFAULT 0,
    scan_token    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_audiobooks_root ON audiobooks(root_id);

CREATE TABLE IF NOT EXISTS audiobook_chapters (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    book_id      TEXT NOT NULL REFERENCES audiobooks(id) ON DELETE CASCADE,
    path         TEXT NOT NULL UNIQUE,
    rel_path     TEXT NOT NULL,
    file_name    TEXT NOT NULL,
    title        TEXT NOT NULL,
    track_no     INTEGER NOT NULL DEFAULT 0,
    duration_sec INTEGER NOT NULL DEFAULT 0,
    size         INTEGER NOT NULL DEFAULT 0,
    mtime        INTEGER NOT NULL DEFAULT 0,
    from_video   INTEGER NOT NULL DEFAULT 0,
    scan_token   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_chapters_book ON audiobook_chapters(book_id);

CREATE TABLE IF NOT EXISTS books (
    path      TEXT PRIMARY KEY,
    root_id   INTEGER NOT NULL REFERENCES roots(id) ON DELETE CASCADE,
    rel_path  TEXT NOT NULL,
    file_name TEXT NOT NULL,
    title     TEXT NOT NULL,
    author    TEXT NOT NULL DEFAULT '',
    size      INTEGER NOT NULL DEFAULT 0,
    mtime     INTEGER NOT NULL DEFAULT 0,
    format    TEXT NOT NULL,
    has_cover INTEGER NOT NULL DEFAULT 0,
    meta_scanned INTEGER NOT NULL DEFAULT 1,
    scan_token INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_books_root ON books(root_id);

CREATE TABLE IF NOT EXISTS art (
    key  TEXT PRIMARY KEY,
    mime TEXT NOT NULL,
    data BLOB NOT NULL,
    size INTEGER NOT NULL DEFAULT 0
);
"#;

pub struct Db {
    conn: Mutex<Connection>,
}

impl Db {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .with_context(|| format!("create database directory {}", parent.display()))?;
        }
        let conn =
            Connection::open(path).with_context(|| format!("open database {}", path.display()))?;
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;
             PRAGMA synchronous=NORMAL;
             PRAGMA foreign_keys=ON;
             PRAGMA busy_timeout=5000;
             PRAGMA temp_store=MEMORY;
             PRAGMA cache_size=-16000;",
        )?;
        migrate(&conn)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub fn lock(&self) -> MutexGuard<'_, Connection> {
        match self.conn.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }
}

fn migrate(conn: &Connection) -> Result<()> {
    let version: i64 = conn.pragma_query_value(None, "user_version", |row| row.get(0))?;
    if version < 1 {
        conn.execute_batch(SCHEMA)?;
        conn.pragma_update(None, "user_version", 1)?;
    }
    let version: i64 = conn.pragma_query_value(None, "user_version", |row| row.get(0))?;
    if version < 3 {
        let has_fast: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM pragma_table_info('roots') WHERE name = 'fast_scan')",
            [],
            |row| row.get(0),
        )?;
        if !has_fast {
            let has_legacy: bool = conn.query_row(
                "SELECT EXISTS(SELECT 1 FROM pragma_table_info('roots') WHERE name = 'random_pick')",
                [],
                |row| row.get(0),
            )?;
            conn.execute_batch(
                "ALTER TABLE roots ADD COLUMN fast_scan INTEGER NOT NULL DEFAULT 0",
            )?;
            if has_legacy {
                conn.execute_batch("UPDATE roots SET fast_scan = random_pick")?;
            }
        }
        conn.pragma_update(None, "user_version", 3)?;
    }
    let version: i64 = conn.pragma_query_value(None, "user_version", |row| row.get(0))?;
    if version < 4 {
        let has_column: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM pragma_table_info('books') WHERE name = 'meta_scanned')",
            [],
            |row| row.get(0),
        )?;
        if !has_column {
            conn.execute_batch(
                "ALTER TABLE books ADD COLUMN meta_scanned INTEGER NOT NULL DEFAULT 1",
            )?;
        }
        conn.pragma_update(None, "user_version", 4)?;
    }
    Ok(())
}

// ---------------------------------------------------------------- meta helpers

pub fn meta_get(conn: &Connection, key: &str) -> Result<Option<String>> {
    Ok(conn
        .query_row(
            "SELECT value FROM meta WHERE key = ?1",
            params![key],
            |row| row.get(0),
        )
        .optional()?)
}

pub fn meta_set(conn: &Connection, key: &str, value: &str) -> Result<()> {
    conn.execute(
        "INSERT INTO meta(key, value) VALUES (?1, ?2)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}

fn default_device_name(device_id: &str) -> String {
    let host = std::env::var("HOSTNAME")
        .or_else(|_| std::env::var("COMPUTERNAME"))
        .unwrap_or_else(|_| "Aurora Desktop".to_string());
    let host = host
        .split('.')
        .next()
        .unwrap_or("Aurora Desktop")
        .to_string();
    let suffix: String = device_id.chars().take(4).collect::<String>().to_uppercase();
    format!("{host} · {suffix}")
}

/// Loads (or creates) the persistent device identity.
pub fn ensure_identity(conn: &Connection) -> Result<(String, String)> {
    let device_id = match meta_get(conn, "device_id")? {
        Some(id) if !id.trim().is_empty() => id,
        _ => {
            let id = uuid::Uuid::new_v4().to_string();
            meta_set(conn, "device_id", &id)?;
            id
        }
    };
    let device_name = match meta_get(conn, "device_name")? {
        Some(name) if !name.trim().is_empty() => name,
        _ => {
            let name = default_device_name(&device_id);
            meta_set(conn, "device_name", &name)?;
            name
        }
    };
    Ok((device_id, device_name))
}

// ------------------------------------------------------------- library version

fn feed_str(hasher: &mut u64, value: &str) {
    const FNV_PRIME: u64 = 1099511628211;
    for byte in value.as_bytes() {
        *hasher ^= *byte as u64;
        *hasher = hasher.wrapping_mul(FNV_PRIME);
    }
    *hasher ^= 0xff;
    *hasher = hasher.wrapping_mul(FNV_PRIME);
}

fn feed_i64(hasher: &mut u64, value: i64) {
    feed_str(hasher, &value.to_string());
}

fn content_signature(conn: &Connection) -> Result<String> {
    let mut hasher: u64 = 1469598103934665603;

    let mut stmt = conn.prepare(
        "SELECT id, path, kind, COALESCE(music_mode,''), display_name, fast_scan FROM roots ORDER BY id",
    )?;
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        feed_i64(&mut hasher, row.get(0)?);
        feed_str(&mut hasher, &row.get::<_, String>(1)?);
        feed_str(&mut hasher, &row.get::<_, String>(2)?);
        feed_str(&mut hasher, &row.get::<_, String>(3)?);
        feed_str(&mut hasher, &row.get::<_, String>(4)?);
        feed_i64(&mut hasher, row.get(5)?);
    }
    drop(rows);
    drop(stmt);

    let mut stmt = conn.prepare(
        "SELECT id, title, artist, album, path, duration_sec, track_no, disc_no,
                from_video, has_art, size, mtime
         FROM tracks ORDER BY id",
    )?;
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        feed_i64(&mut hasher, row.get(0)?);
        feed_str(&mut hasher, &row.get::<_, String>(1)?);
        feed_str(&mut hasher, &row.get::<_, String>(2)?);
        feed_str(&mut hasher, &row.get::<_, String>(3)?);
        feed_str(&mut hasher, &row.get::<_, String>(4)?);
        feed_i64(&mut hasher, row.get(5)?);
        feed_i64(&mut hasher, row.get(6)?);
        feed_i64(&mut hasher, row.get(7)?);
        feed_i64(&mut hasher, row.get(8)?);
        feed_i64(&mut hasher, row.get(9)?);
        feed_i64(&mut hasher, row.get(10)?);
        feed_i64(&mut hasher, row.get(11)?);
    }
    drop(rows);
    drop(stmt);

    let mut stmt = conn.prepare(
        "SELECT path, title, author, size, format, has_cover, meta_scanned
         FROM books ORDER BY path",
    )?;
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        feed_str(&mut hasher, &row.get::<_, String>(0)?);
        feed_str(&mut hasher, &row.get::<_, String>(1)?);
        feed_str(&mut hasher, &row.get::<_, String>(2)?);
        feed_i64(&mut hasher, row.get(3)?);
        feed_str(&mut hasher, &row.get::<_, String>(4)?);
        feed_i64(&mut hasher, row.get(5)?);
        feed_i64(&mut hasher, row.get(6)?);
    }
    drop(rows);
    drop(stmt);

    let mut stmt = conn.prepare(
        "SELECT b.id, b.title, c.path, c.title, c.track_no, c.duration_sec, c.size
         FROM audiobooks b LEFT JOIN audiobook_chapters c ON c.book_id = b.id
         ORDER BY b.id, c.path",
    )?;
    let mut rows = stmt.query([])?;
    while let Some(row) = rows.next()? {
        feed_str(&mut hasher, &row.get::<_, String>(0)?);
        feed_str(&mut hasher, &row.get::<_, String>(1)?);
        feed_str(
            &mut hasher,
            &row.get::<_, Option<String>>(2)?.unwrap_or_default(),
        );
        feed_str(
            &mut hasher,
            &row.get::<_, Option<String>>(3)?.unwrap_or_default(),
        );
        feed_i64(&mut hasher, row.get::<_, Option<i64>>(4)?.unwrap_or(0));
        feed_i64(&mut hasher, row.get::<_, Option<i64>>(5)?.unwrap_or(0));
        feed_i64(&mut hasher, row.get::<_, Option<i64>>(6)?.unwrap_or(0));
    }

    Ok(format!("{hasher:016x}"))
}

/// Updates the library version only when the content actually changed, keeping
/// the value stable across restarts (remote_database.md §4.1).
pub fn refresh_library_version(conn: &Connection) -> Result<i64> {
    let signature = content_signature(conn)?;
    let previous_signature = meta_get(conn, "library_signature")?;
    let previous_version: i64 = meta_get(conn, "library_version")?
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);

    if previous_version > 0 && previous_signature.as_deref() == Some(signature.as_str()) {
        return Ok(previous_version);
    }

    let now = now_ms();
    let version = if now > previous_version {
        now
    } else {
        previous_version + 1
    };
    meta_set(conn, "library_signature", &signature)?;
    meta_set(conn, "library_version", &version.to_string())?;
    Ok(version)
}

pub fn library_version(conn: &Connection) -> Result<i64> {
    if let Some(value) = meta_get(conn, "library_version")?.and_then(|v| v.parse::<i64>().ok()) {
        if value > 0 {
            return Ok(value);
        }
    }
    refresh_library_version(conn)
}

// --------------------------------------------------------------------- roots

fn row_to_root(row: &rusqlite::Row<'_>) -> rusqlite::Result<RootInfo> {
    Ok(RootInfo {
        id: row.get(0)?,
        path: row.get(1)?,
        kind: row.get(2)?,
        music_mode: row.get(3)?,
        fast_scan: row.get::<_, i64>(4)? != 0,
        display_name: row.get(5)?,
        created_at: row.get(6)?,
        last_scan_at: row.get(7)?,
        scan_state: row.get(8)?,
        item_count: row.get(9)?,
    })
}

const ROOT_COLUMNS: &str =
    "id, path, kind, music_mode, fast_scan, display_name, created_at, last_scan_at, scan_state, item_count";

pub fn list_roots(conn: &Connection) -> Result<Vec<RootInfo>> {
    let mut stmt = conn.prepare(&format!(
        "SELECT {ROOT_COLUMNS} FROM roots ORDER BY created_at ASC, id ASC"
    ))?;
    let rows = stmt.query_map([], row_to_root)?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row?);
    }
    Ok(out)
}

pub fn get_root(conn: &Connection, id: i64) -> Result<RootInfo> {
    conn.query_row(
        &format!("SELECT {ROOT_COLUMNS} FROM roots WHERE id = ?1"),
        params![id],
        row_to_root,
    )
    .optional()?
    .ok_or_else(|| anyhow!("共享目录不存在 (id={id})"))
}

pub fn add_root(
    conn: &Connection,
    path: &str,
    kind: &str,
    music_mode: Option<&str>,
    display_name: Option<&str>,
    fast_scan: bool,
) -> Result<RootInfo> {
    if !matches!(kind, KIND_MUSIC | KIND_AUDIOBOOK | KIND_BOOK) {
        return Err(anyhow!("未知的目录类型: {kind}"));
    }
    let mode = if kind == KIND_MUSIC {
        Some(match music_mode.unwrap_or(MODE_TAG) {
            MODE_FOLDER => MODE_FOLDER,
            _ => MODE_TAG,
        })
    } else {
        None
    };

    let raw = PathBuf::from(path);
    if !raw.is_dir() {
        return Err(anyhow!("目录不存在或不可访问: {path}"));
    }
    let canonical = raw
        .canonicalize()
        .with_context(|| format!("无法解析目录路径: {path}"))?;
    let canonical = canonical.to_string_lossy().to_string();

    let existing: Option<i64> = conn
        .query_row(
            "SELECT id FROM roots WHERE path = ?1",
            params![canonical],
            |row| row.get(0),
        )
        .optional()?;
    if let Some(id) = existing {
        return Err(anyhow!("该目录已经添加过了 (id={id})"));
    }

    let name = display_name
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| {
            Path::new(&canonical)
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_else(|| canonical.clone())
        });

    conn.execute(
        "INSERT INTO roots(path, kind, music_mode, fast_scan, display_name, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            canonical,
            kind,
            mode,
            if fast_scan { 1 } else { 0 },
            name,
            now_ms()
        ],
    )?;
    let id = conn.last_insert_rowid();
    refresh_library_version(conn)?;
    get_root(conn, id)
}

pub fn update_root(
    conn: &Connection,
    id: i64,
    display_name: Option<&str>,
    music_mode: Option<&str>,
    fast_scan: Option<bool>,
) -> Result<RootInfo> {
    let root = get_root(conn, id)?;
    if let Some(name) = display_name {
        let name = name.trim();
        if name.is_empty() {
            return Err(anyhow!("显示名称不能为空"));
        }
        conn.execute(
            "UPDATE roots SET display_name = ?1 WHERE id = ?2",
            params![name, id],
        )?;
    }
    if root.kind == KIND_MUSIC {
        if let Some(mode) = music_mode {
            let mode = if mode == MODE_FOLDER {
                MODE_FOLDER
            } else {
                MODE_TAG
            };
            conn.execute(
                "UPDATE roots SET music_mode = ?1 WHERE id = ?2",
                params![mode, id],
            )?;
        }
    }
    if root.kind == KIND_BOOK {
        if let Some(fast_scan) = fast_scan {
            if !fast_scan && root.fast_scan {
                return Err(anyhow!("快速扫描模式启用后不能撤销"));
            }
            if fast_scan {
                conn.execute("UPDATE roots SET fast_scan = 1 WHERE id = ?1", params![id])?;
            }
        }
    }
    refresh_library_version(conn)?;
    get_root(conn, id)
}

pub fn remove_root(conn: &Connection, id: i64) -> Result<()> {
    get_root(conn, id)?;
    let tx = conn.unchecked_transaction()?;
    tx.execute(
        "DELETE FROM art WHERE key IN (SELECT art_key FROM tracks WHERE root_id = ?1 AND art_key IS NOT NULL)",
        params![id],
    )?;
    tx.execute(
        "DELETE FROM art WHERE key LIKE ?1",
        params![format!("album:{}:%", id)],
    )?;
    tx.execute(
        "DELETE FROM art WHERE key LIKE ?1",
        params![format!("audiobook:ab:{}:%", id)],
    )?;
    tx.execute(
        "DELETE FROM art WHERE key IN (SELECT 'book:' || path FROM books WHERE root_id = ?1)",
        params![id],
    )?;
    tx.execute("DELETE FROM roots WHERE id = ?1", params![id])?;
    tx.commit()?;
    refresh_library_version(conn)?;
    Ok(())
}

pub fn set_scan_state(conn: &Connection, id: i64, state: &str) -> Result<()> {
    conn.execute(
        "UPDATE roots SET scan_state = ?1 WHERE id = ?2",
        params![state, id],
    )?;
    Ok(())
}

pub fn finish_scan(conn: &Connection, id: i64, item_count: i64) -> Result<()> {
    conn.execute(
        "UPDATE roots SET last_scan_at = ?1, item_count = ?2, scan_state = 'idle' WHERE id = ?3",
        params![now_ms(), item_count, id],
    )?;
    Ok(())
}

// -------------------------------------------------------------------- reading

pub fn load_tracks(conn: &Connection) -> Result<(bool, Vec<TrackDto>)> {
    let path_order: bool = conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM roots WHERE kind = 'music' AND music_mode = 'folder')",
        [],
        |row| row.get(0),
    )?;

    let order = if path_order {
        "t.artist COLLATE NOCASE, t.album COLLATE NOCASE, t.path COLLATE NOCASE"
    } else {
        "t.artist COLLATE NOCASE, t.album COLLATE NOCASE,
         CASE WHEN t.disc_no = 0 THEN 2147483647 ELSE t.disc_no END,
         CASE WHEN t.track_no = 0 THEN 2147483647 ELSE t.track_no END,
         t.title COLLATE NOCASE, t.file_name COLLATE NOCASE"
    };

    let sql = format!(
        "SELECT t.id, t.title, t.artist, t.album, t.duration_sec, t.track_no, t.disc_no,
                t.has_art, t.path, t.file_name, t.size, t.from_video, t.root_id
         FROM tracks t JOIN roots r ON r.id = t.root_id
         ORDER BY {order}"
    );

    let mut stmt = conn.prepare(&sql)?;
    let mut rows = stmt.query([])?;
    let mut out = Vec::new();
    while let Some(row) = rows.next()? {
        let id: i64 = row.get(0)?;
        let artist: String = row.get(2)?;
        let album: String = row.get(3)?;
        let artist = artist.trim().to_string();
        let album = album.trim().to_string();
        let artist_key = artist.to_lowercase();
        let album_key = album.to_lowercase();
        out.push(TrackDto {
            id: id.to_string(),
            title: row.get(1)?,
            artist,
            album,
            album_id: format!("{artist_key}::{album_key}"),
            artist_id: artist_key,
            duration_sec: row.get(4)?,
            track_number: row.get(5)?,
            disc_number: row.get(6)?,
            has_art: row.get::<_, i64>(7)? != 0,
            merge_titles: true,
            path: row.get(8)?,
            file_name: row.get(9)?,
            size: row.get(10)?,
            from_video: row.get::<_, i64>(11)? != 0,
            root_id: row.get(12)?,
            art_url: Some(format!("/media/songart?id={id}")),
        });
    }
    Ok((path_order, out))
}

pub fn music_library(conn: &Connection) -> Result<MusicLibrary> {
    let (path_order, tracks) = load_tracks(conn)?;
    let total_tracks = tracks.len() as i64;
    let mut artists: Vec<ArtistGroup> = Vec::new();

    for track in tracks {
        let artist_name = if track.artist.is_empty() {
            "未知艺人".to_string()
        } else {
            track.artist.clone()
        };
        let album_title = if track.album.is_empty() {
            "未知专辑".to_string()
        } else {
            track.album.clone()
        };
        let artist_key = artist_name.to_lowercase();

        let artist_index = match artists.last() {
            Some(last) if last.id == artist_key => artists.len() - 1,
            _ => {
                artists.push(ArtistGroup {
                    id: artist_key.clone(),
                    name: artist_name.clone(),
                    track_count: 0,
                    albums: Vec::new(),
                });
                artists.len() - 1
            }
        };
        let artist = &mut artists[artist_index];
        artist.track_count += 1;

        let album_key = track.album_id.clone();
        let album_index = match artist.albums.last() {
            Some(last) if last.id == album_key => artist.albums.len() - 1,
            _ => {
                artist.albums.push(AlbumGroup {
                    id: album_key,
                    title: album_title,
                    artist: artist_name,
                    cover_track_id: None,
                    duration_sec: 0,
                    tracks: Vec::new(),
                });
                artist.albums.len() - 1
            }
        };
        let album = &mut artist.albums[album_index];
        album.duration_sec += track.duration_sec;
        if album.cover_track_id.is_none() && track.has_art {
            album.cover_track_id = Some(track.id.clone());
        }
        album.tracks.push(track);
    }

    Ok(MusicLibrary {
        path_order,
        total_tracks,
        artists,
    })
}

fn encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for byte in s.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

pub fn audiobooks(conn: &Connection) -> Result<Vec<AudiobookDto>> {
    let mut stmt = conn.prepare(
        "SELECT id, root_id, title, rel_path, duration_sec, has_art
         FROM audiobooks ORDER BY title COLLATE NOCASE, rel_path COLLATE NOCASE",
    )?;
    let mut rows = stmt.query([])?;
    let mut out = Vec::new();
    while let Some(row) = rows.next()? {
        let id: String = row.get(0)?;
        let has_art: i64 = row.get(5)?;
        out.push(AudiobookDto {
            rel_path: row.get(3)?,
            id: id.clone(),
            root_id: row.get(1)?,
            title: row.get(2)?,
            chapter_count: 0,
            duration_sec: row.get(4)?,
            has_art: has_art != 0,
            cover_url: if has_art != 0 {
                Some(format!("/media/cover?kind=audiobook&key={}", encode(&id)))
            } else {
                None
            },
            chapters: Vec::new(),
        });
    }
    drop(rows);
    drop(stmt);

    let mut ch_stmt = conn.prepare(
        "SELECT path, title, track_no, duration_sec, size, from_video, rel_path
         FROM audiobook_chapters WHERE book_id = ?1",
    )?;
    for book in &mut out {
        let mut ch_rows = ch_stmt.query(params![book.id])?;
        let mut list: Vec<(String, ChapterDto)> = Vec::new();
        while let Some(row) = ch_rows.next()? {
            let path: String = row.get(0)?;
            list.push((
                row.get::<_, String>(6)?,
                ChapterDto {
                    audio_url: format!("/media/audiobook?path={}", encode(&path)),
                    path,
                    title: row.get(1)?,
                    track_number: row.get(2)?,
                    duration_sec: row.get(3)?,
                    size: row.get(4)?,
                    from_video: row.get::<_, i64>(5)? != 0,
                },
            ));
        }
        list.sort_by(|a, b| {
            let (ra, ca) = a;
            let (rb, cb) = b;
            let ka = (ca.track_number == 0, ca.track_number);
            let kb = (cb.track_number == 0, cb.track_number);
            ka.cmp(&kb).then_with(|| util::natural_cmp(ra, rb))
        });
        book.chapters = list.into_iter().map(|(_, c)| c).collect();
        book.chapter_count = book.chapters.len() as i64;
    }
    Ok(out)
}

pub fn books(conn: &Connection) -> Result<Vec<BookDto>> {
    let mut stmt = conn.prepare(
        "SELECT path, root_id, rel_path, title, author, size, format, has_cover, meta_scanned
         FROM books ORDER BY title COLLATE NOCASE, rel_path COLLATE NOCASE",
    )?;
    let rows = stmt.query_map([], |row| {
        let path: String = row.get(0)?;
        let has_cover: i64 = row.get(7)?;
        Ok(BookDto {
            cover_url: if has_cover != 0 {
                Some(format!("/media/cover?kind=ebook&key={}", encode(&path)))
            } else {
                None
            },
            download_url: format!("/media/ebook?path={}", encode(&path)),
            path,
            root_id: row.get(1)?,
            rel_path: row.get(2)?,
            title: row.get(3)?,
            author: row.get(4)?,
            size: row.get(5)?,
            format: row.get(6)?,
            has_cover: has_cover != 0,
            meta_scanned: row.get::<_, i64>(8)? != 0,
        })
    })?;
    let mut out = Vec::new();
    for row in rows {
        out.push(row?);
    }
    Ok(out)
}

pub fn stats(conn: &Connection) -> Result<LibraryStats> {
    let count = |sql: &str| -> Result<i64> { Ok(conn.query_row(sql, [], |row| row.get(0))?) };
    Ok(LibraryStats {
        roots: count("SELECT COUNT(*) FROM roots")?,
        tracks: count("SELECT COUNT(*) FROM tracks")?,
        audiobooks: count("SELECT COUNT(*) FROM audiobooks")?,
        chapters: count("SELECT COUNT(*) FROM audiobook_chapters")?,
        books: count("SELECT COUNT(*) FROM books")?,
        video_tracks: count("SELECT COUNT(*) FROM tracks WHERE from_video = 1")?,
        library_version: library_version(conn)?,
    })
}

// -------------------------------------------------------------- wire snapshot

pub fn wire_snapshot(
    conn: &Connection,
    device_id: &str,
    device_name: &str,
) -> Result<WireSnapshot> {
    let (path_order, tracks) = load_tracks(conn)?;
    let music = tracks
        .into_iter()
        .map(|t| WireTrack {
            id: t.id,
            title: t.title,
            artist: t.artist,
            album: t.album,
            album_id: t.album_id,
            artist_id: t.artist_id,
            duration_sec: t.duration_sec,
            track_number: t.track_number,
            disc_number: t.disc_number,
            has_art: t.has_art,
            merge_titles: t.merge_titles,
            path: t.path,
        })
        .collect();

    let mut ebook_roots = Vec::new();
    {
        let mut stmt = conn.prepare(
            "SELECT r.id, r.path, r.display_name,
                    (SELECT COUNT(*) FROM books b WHERE b.root_id = r.id), r.fast_scan
             FROM roots r WHERE r.kind = 'book' ORDER BY r.created_at ASC, r.id ASC",
        )?;
        let mut rows = stmt.query([])?;
        while let Some(row) = rows.next()? {
            let id: i64 = row.get(0)?;
            ebook_roots.push(WireEbookRoot {
                key: id.to_string(),
                root_path: row.get(1)?,
                display_name: row.get(2)?,
                shelf: false,
                book_count: row.get(3)?,
                fast_scan: row.get::<_, i64>(4)? != 0,
            });
        }
    }

    let ebooks = books(conn)?
        .into_iter()
        .map(|b| WireEbook {
            path: b.path,
            title: b.title,
            author: b.author,
            size: b.size,
            has_cover: b.has_cover,
            meta_scanned: b.meta_scanned,
            root_key: b.root_id.to_string(),
        })
        .collect();

    let audiobooks = audiobooks(conn)?
        .into_iter()
        .map(|b| WireAudiobook {
            id: b.id,
            title: b.title,
            has_cover: b.has_art,
            chapters: b
                .chapters
                .into_iter()
                .map(|c| WireChapter {
                    path: c.path,
                    title: c.title,
                    track_number: c.track_number,
                    duration_sec: c.duration_sec,
                    size: c.size,
                })
                .collect(),
        })
        .collect();

    Ok(WireSnapshot {
        device_id: device_id.to_string(),
        device_name: device_name.to_string(),
        generated_at: now_ms(),
        library_updated_at: library_version(conn)?,
        music_path_order: path_order,
        music,
        ebook_roots,
        ebooks,
        audiobooks,
    })
}

// ------------------------------------------------------------- media lookups

#[derive(Debug, Clone)]
pub struct MediaSource {
    pub path: PathBuf,
    pub from_video: bool,
}

pub fn track_source(conn: &Connection, id: &str) -> Result<Option<MediaSource>> {
    let Ok(numeric) = id.parse::<i64>() else {
        return Ok(None);
    };
    let row: Option<(String, i64)> = conn
        .query_row(
            "SELECT path, from_video FROM tracks WHERE id = ?1",
            params![numeric],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    Ok(row.map(|(path, from_video)| MediaSource {
        path: PathBuf::from(path),
        from_video: from_video != 0,
    }))
}

pub fn chapter_source(conn: &Connection, path: &str) -> Result<Option<MediaSource>> {
    let row: Option<(String, i64)> = conn
        .query_row(
            "SELECT path, from_video FROM audiobook_chapters WHERE path = ?1",
            params![path],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    Ok(row.map(|(path, from_video)| MediaSource {
        path: PathBuf::from(path),
        from_video: from_video != 0,
    }))
}

pub fn book_source(conn: &Connection, path: &str) -> Result<Option<(PathBuf, String)>> {
    let row: Option<String> = conn
        .query_row(
            "SELECT path FROM books WHERE path = ?1",
            params![path],
            |row| row.get(0),
        )
        .optional()?;
    Ok(row.map(|p| {
        let ext = Path::new(&p)
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_ascii_lowercase();
        (PathBuf::from(p), ext)
    }))
}

/// Metadata and lazy-scan state for one book, used by the on-demand endpoint.
pub struct BookScanInfo {
    pub path: PathBuf,
    pub format: String,
    pub title: String,
    pub author: String,
    pub has_cover: bool,
    pub meta_scanned: bool,
}

pub fn book_scan_info(conn: &Connection, path: &str) -> Result<Option<BookScanInfo>> {
    let row = conn
        .query_row(
            "SELECT path, format, title, author, has_cover, meta_scanned
             FROM books WHERE path = ?1",
            params![path],
            |row| {
                Ok(BookScanInfo {
                    path: PathBuf::from(row.get::<_, String>(0)?),
                    format: row.get(1)?,
                    title: row.get(2)?,
                    author: row.get(3)?,
                    has_cover: row.get::<_, i64>(4)? != 0,
                    meta_scanned: row.get::<_, i64>(5)? != 0,
                })
            },
        )
        .optional()?;
    Ok(row)
}

/// Applies metadata parsed on demand to one fast-scanned book and persists it.
/// Missing fields keep the existing values, so repeated lazy scans are cheap and safe.
pub fn apply_book_meta(
    conn: &Connection,
    path: &str,
    meta: &crate::meta::BookMeta,
) -> Result<(String, String, bool)> {
    let title = meta
        .title
        .clone()
        .map(|t| t.trim().to_string())
        .filter(|t| !t.is_empty());
    let author = meta
        .author
        .clone()
        .map(|a| a.trim().to_string())
        .filter(|a| !a.is_empty());

    if let Some((mime, data)) = &meta.cover {
        conn.execute(
            "INSERT OR REPLACE INTO art(key, mime, data, size) VALUES (?1, ?2, ?3, ?4)",
            params![format!("book:{path}"), mime, data, data.len() as i64],
        )?;
    }

    let updated = conn.execute(
        "UPDATE books SET
            title = COALESCE(?1, title),
            author = COALESCE(?2, author),
            has_cover = CASE WHEN ?3 = 1 THEN 1 ELSE has_cover END,
            meta_scanned = 1
         WHERE path = ?4",
        params![
            title,
            author,
            if meta.cover.is_some() { 1 } else { 0 },
            path
        ],
    )?;
    if updated == 0 {
        return Err(anyhow!("书籍不存在: {path}"));
    }

    let (title, author, has_cover): (String, String, i64) = conn.query_row(
        "SELECT title, author, has_cover FROM books WHERE path = ?1",
        params![path],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
    )?;
    Ok((title, author, has_cover != 0))
}

pub fn art_by_key(conn: &Connection, key: &str) -> Result<Option<(String, Vec<u8>)>> {
    let row: Option<(String, Vec<u8>)> = conn
        .query_row(
            "SELECT mime, data FROM art WHERE key = ?1",
            params![key],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    Ok(row)
}

pub fn track_art(conn: &Connection, id: &str) -> Result<Option<(String, Vec<u8>)>> {
    let Ok(numeric) = id.parse::<i64>() else {
        return Ok(None);
    };
    let key: Option<Option<String>> = conn
        .query_row(
            "SELECT art_key FROM tracks WHERE id = ?1",
            params![numeric],
            |row| row.get(0),
        )
        .optional()?;
    match key.flatten() {
        Some(key) => art_by_key(conn, &key),
        None => Ok(None),
    }
}

pub fn audiobook_art(conn: &Connection, id: &str) -> Result<Option<(String, Vec<u8>)>> {
    art_by_key(conn, &format!("audiobook:{id}"))
}

pub fn book_art(conn: &Connection, path: &str) -> Result<Option<(String, Vec<u8>)>> {
    art_by_key(conn, &format!("book:{path}"))
}

/// Debug helper used by the scan pipeline to keep album cover keys stable.
#[allow(dead_code)]
pub fn album_art_key(root_id: i64, dir: &Path) -> String {
    format!(
        "album:{root_id}:{}",
        sha1_hex(dir.to_string_lossy().as_bytes())
    )
}
