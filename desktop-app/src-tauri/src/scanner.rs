use crate::db::{self, Db};
use crate::media;
use crate::meta;
use crate::models::*;
use crate::util::{self, path_key};
use anyhow::{anyhow, Context, Result};
use rusqlite::{params, Connection};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::Instant;
use walkdir::WalkDir;

#[derive(Debug, Clone)]
struct FileEntry {
    path: String,
    rel_path: String,
    file_name: String,
    size: i64,
    mtime: i64,
    ext: String,
}

#[derive(Debug, Clone)]
struct ArtBlob {
    mime: String,
    data: Vec<u8>,
}

fn file_mtime(path: &Path) -> i64 {
    std::fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn collect_files<F: Fn(&str) -> bool>(root: &Path, want: F) -> Vec<FileEntry> {
    let mut out = Vec::new();
    let walker = WalkDir::new(root)
        .follow_links(false)
        .into_iter()
        .filter_entry(|entry| !util::is_hidden(entry.path()));
    for entry in walker.filter_map(|e| e.ok()) {
        if !entry.file_type().is_file() {
            continue;
        }
        let path = entry.path();
        let ext = match path.extension().and_then(|e| e.to_str()) {
            Some(e) => e.to_ascii_lowercase(),
            None => continue,
        };
        if !want(&ext) {
            continue;
        }
        let rel_path = match path.strip_prefix(root) {
            Ok(rel) => rel
                .components()
                .map(|c| c.as_os_str().to_string_lossy())
                .collect::<Vec<_>>()
                .join("/"),
            Err(_) => continue,
        };
        let meta = match entry.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        out.push(FileEntry {
            path: path.to_string_lossy().to_string(),
            file_name: path
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default(),
            rel_path,
            size: meta.len() as i64,
            mtime: file_mtime(path),
            ext,
        });
    }
    out.sort_by(|a, b| util::natural_cmp(&a.rel_path, &b.rel_path));
    out
}

fn rel_dir(rel_path: &str) -> String {
    match rel_path.rfind('/') {
        Some(pos) => rel_path[..pos].to_string(),
        None => String::new(),
    }
}

fn normalise_artist(value: Option<String>) -> String {
    value.map(|v| v.trim().to_string()).unwrap_or_default()
}

// ------------------------------------------------------------------ music

struct PreparedTrack {
    path: String,
    rel_path: String,
    file_name: String,
    title: String,
    artist: String,
    album: String,
    album_artist: String,
    genre: String,
    year: i64,
    duration_sec: i64,
    track_no: i64,
    disc_no: i64,
    size: i64,
    mtime: i64,
    from_video: bool,
    art_dir: Option<PathBuf>,
}

enum MusicItem {
    Unchanged(String),
    Track(Box<PreparedTrack>),
}

fn collect_music<F: Fn(ScanProgress)>(
    root: &RootInfo,
    root_path: &Path,
    existing: &HashMap<String, (i64, i64)>,
    progress: &F,
) -> (
    Vec<MusicItem>,
    HashMap<PathBuf, Option<ArtBlob>>,
    Vec<String>,
) {
    let files = collect_files(root_path, util::is_media_ext);
    let total = files.len() as i64;
    let folder_mode = root.music_mode.as_deref() == Some(MODE_FOLDER);
    let mut items = Vec::with_capacity(files.len());
    let mut dir_arts: HashMap<PathBuf, Option<ArtBlob>> = HashMap::new();
    let errors = Vec::new();

    for (index, file) in files.iter().enumerate() {
        if index % 25 == 0 {
            progress(ScanProgress {
                root_id: root.id,
                root_name: root.display_name.clone(),
                phase: "collect".to_string(),
                scanned: index as i64,
                total,
            });
        }
        if let Some((size, mtime)) = existing.get(&file.path) {
            if *size == file.size && *mtime == file.mtime {
                items.push(MusicItem::Unchanged(file.path.clone()));
                continue;
            }
        }

        let from_video = util::is_video_ext(&file.ext);
        let tag = if from_video {
            None
        } else {
            meta::read_audio(Path::new(&file.path))
        };
        let tag_ref = tag.as_ref();
        let stem = Path::new(&file.file_name)
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| file.file_name.clone());
        let dir = Path::new(&file.path)
            .parent()
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| root_path.to_path_buf());

        let mut title = tag_ref
            .and_then(|t| t.title.clone())
            .unwrap_or_else(|| stem.clone());
        let mut artist = normalise_artist(tag_ref.and_then(|t| t.artist.clone()));
        let mut album = normalise_artist(tag_ref.and_then(|t| t.album.clone()));
        let album_artist = normalise_artist(tag_ref.and_then(|t| t.album_artist.clone()));
        let mut track_no = tag_ref.map(|t| t.track_no).unwrap_or(0);
        let disc_no = tag_ref.map(|t| t.disc_no).unwrap_or(0);

        if folder_mode {
            // Compatibility layout: <artist>/<album>/<files>
            let dir = rel_dir(&file.rel_path);
            let parts: Vec<&str> = if dir.is_empty() {
                Vec::new()
            } else {
                dir.split('/').collect()
            };
            if let Some(first) = parts.first() {
                if !first.is_empty() {
                    artist = first.to_string();
                }
            }
            if let Some(second) = parts.get(1) {
                if !second.is_empty() {
                    album = second.to_string();
                }
            }
            if track_no == 0 {
                track_no = util::leading_track_number(&file.file_name);
            }
        } else {
            if artist.is_empty() {
                artist = album_artist.clone();
            }
            if track_no == 0 {
                track_no = util::leading_track_number(&file.file_name);
            }
        }
        if title.trim().is_empty() {
            title = stem;
        }
        if album.trim().is_empty() {
            album = dir
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
        }
        if artist.trim().is_empty() {
            artist = album_artist.clone();
        }

        let mut duration_sec = tag_ref.map(|t| t.duration_sec).unwrap_or(0);
        if duration_sec == 0 && from_video {
            duration_sec = media::probe_duration_sec(Path::new(&file.path)).unwrap_or(0);
        }
        if duration_sec == 0 && tag.is_none() {
            duration_sec = media::probe_duration_sec(Path::new(&file.path)).unwrap_or(0);
        }

        let has_art = remember_art(&mut dir_arts, &dir, tag_ref.and_then(|t| t.picture.clone()));

        items.push(MusicItem::Track(Box::new(PreparedTrack {
            art_dir: if has_art { Some(dir) } else { None },
            path: file.path.clone(),
            rel_path: file.rel_path.clone(),
            file_name: file.file_name.clone(),
            title,
            artist,
            album,
            album_artist,
            genre: tag_ref.and_then(|t| t.genre.clone()).unwrap_or_default(),
            year: tag_ref.map(|t| t.year).unwrap_or(0),
            duration_sec,
            track_no,
            disc_no,
            size: file.size,
            mtime: file.mtime,
            from_video,
        })));
    }

    progress(ScanProgress {
        root_id: root.id,
        root_name: root.display_name.clone(),
        phase: "collect".to_string(),
        scanned: total,
        total,
    });
    (items, dir_arts, errors)
}

fn remember_art(
    dir_arts: &mut HashMap<PathBuf, Option<ArtBlob>>,
    dir: &Path,
    embedded: Option<(String, Vec<u8>)>,
) -> bool {
    let entry = dir_arts
        .entry(dir.to_path_buf())
        .or_insert_with(|| util::dir_cover(dir).map(|(mime, data)| ArtBlob { mime, data }));
    if entry.is_none() {
        if let Some((mime, data)) = embedded {
            *entry = Some(ArtBlob { mime, data });
        }
    }
    entry.is_some()
}

fn write_music(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    existing: &HashMap<String, (i64, i64)>,
    items: Vec<MusicItem>,
    dir_arts: &HashMap<PathBuf, Option<ArtBlob>>,
    summary: &mut ScanSummary,
) -> Result<i64> {
    // Album covers first so track rows can point at stable keys.
    for (dir, blob) in dir_arts {
        if let Some(blob) = blob {
            let key = format!("album:{}:{}", root.id, path_key(dir));
            // Only replace when the bytes changed, to keep rescans cheap.
            let same: Option<i64> = tx
                .query_row("SELECT size FROM art WHERE key = ?1", params![key], |row| {
                    row.get(0)
                })
                .ok();
            if same == Some(blob.data.len() as i64) {
                continue;
            }
            tx.execute(
                "INSERT OR REPLACE INTO art(key, mime, data, size) VALUES (?1, ?2, ?3, ?4)",
                params![key, blob.mime, blob.data, blob.data.len() as i64],
            )?;
        }
    }

    let mut count = 0i64;
    let mut statement = tx.prepare_cached(
        "INSERT INTO tracks(
            root_id, path, rel_path, file_name, title, artist, album, album_artist, genre, year,
            duration_sec, track_no, disc_no, size, mtime, from_video, has_art, art_key, sort_path, scan_token
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20)
         ON CONFLICT(root_id, path) DO UPDATE SET
            rel_path = excluded.rel_path,
            file_name = excluded.file_name,
            title = excluded.title,
            artist = excluded.artist,
            album = excluded.album,
            album_artist = excluded.album_artist,
            genre = excluded.genre,
            year = excluded.year,
            duration_sec = excluded.duration_sec,
            track_no = excluded.track_no,
            disc_no = excluded.disc_no,
            size = excluded.size,
            mtime = excluded.mtime,
            from_video = excluded.from_video,
            has_art = excluded.has_art,
            art_key = excluded.art_key,
            sort_path = excluded.sort_path,
            scan_token = excluded.scan_token",
    )?;

    for item in items {
        match item {
            MusicItem::Unchanged(path) => {
                tx.execute(
                    "UPDATE tracks SET scan_token = ?1 WHERE root_id = ?2 AND path = ?3",
                    params![token, root.id, path],
                )?;
            }
            MusicItem::Track(track) => {
                let art_key = track
                    .art_dir
                    .as_ref()
                    .map(|dir| format!("album:{}:{}", root.id, path_key(dir)));
                statement.execute(params![
                    root.id,
                    track.path,
                    track.rel_path,
                    track.file_name,
                    track.title,
                    track.artist,
                    track.album,
                    track.album_artist,
                    track.genre,
                    track.year,
                    track.duration_sec,
                    track.track_no,
                    track.disc_no,
                    track.size,
                    track.mtime,
                    track.from_video as i64,
                    art_key.is_some() as i64,
                    art_key,
                    track.path.to_lowercase(),
                    token,
                ])?;
                if existing.contains_key(&track.path) {
                    summary.updated += 1;
                } else {
                    summary.added += 1;
                }
            }
        }
        count += 1;
    }
    drop(statement);

    summary.removed += tx.execute(
        "DELETE FROM tracks WHERE root_id = ?1 AND scan_token <> ?2",
        params![root.id, token],
    )? as i64;

    tx.execute(
        "DELETE FROM art WHERE key LIKE ?1
         AND key NOT IN (SELECT art_key FROM tracks WHERE root_id = ?2 AND art_key IS NOT NULL)",
        params![format!("album:{}:%", root.id), root.id],
    )?;
    Ok(count)
}

// --------------------------------------------------------------- audiobooks

struct PreparedChapter {
    path: String,
    rel_path: String,
    file_name: String,
    title: String,
    track_no: i64,
    duration_sec: i64,
    size: i64,
    mtime: i64,
    from_video: bool,
}

struct PreparedBook {
    id: String,
    rel_path: String,
    dir_path: String,
    title: String,
    art: Option<ArtBlob>,
    chapters: Vec<PreparedChapter>,
}

fn collect_audiobooks<F: Fn(ScanProgress)>(
    root: &RootInfo,
    root_path: &Path,
    progress: &F,
) -> (Vec<PreparedBook>, Vec<String>) {
    let files = collect_files(root_path, util::is_media_ext);
    let total = files.len() as i64;
    let mut order: Vec<String> = Vec::new();
    let mut grouped: HashMap<String, Vec<&FileEntry>> = HashMap::new();

    for (index, file) in files.iter().enumerate() {
        if index % 25 == 0 {
            progress(ScanProgress {
                root_id: root.id,
                root_name: root.display_name.clone(),
                phase: "collect".to_string(),
                scanned: index as i64,
                total,
            });
        }
        let dir = rel_dir(&file.rel_path);
        if !grouped.contains_key(&dir) {
            order.push(dir.clone());
            grouped.insert(dir.clone(), Vec::new());
        }
        grouped.get_mut(&dir).unwrap().push(file);
    }

    let mut books = Vec::new();
    for dir in order {
        let entries = grouped.remove(&dir).unwrap_or_default();
        let book_id = format!("ab:{}:{}", root.id, path_key(Path::new(&dir)));
        let book_dir = if dir.is_empty() {
            root_path.to_path_buf()
        } else {
            root_path.join(dir.split('/').collect::<PathBuf>())
        };
        let title = if dir.is_empty() {
            root.display_name.clone()
        } else {
            dir.rsplit('/').next().unwrap_or(&dir).to_string()
        };
        let mut art = util::dir_cover(&book_dir).map(|(mime, data)| ArtBlob { mime, data });
        let mut chapters = Vec::with_capacity(entries.len());

        for file in entries {
            let from_video = util::is_video_ext(&file.ext);
            let tag = if from_video {
                None
            } else {
                meta::read_audio(Path::new(&file.path))
            };
            if art.is_none() {
                if let Some((mime, data)) = tag.as_ref().and_then(|t| t.picture.clone()) {
                    art = Some(ArtBlob { mime, data });
                }
            }
            let stem = Path::new(&file.file_name)
                .file_stem()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| file.file_name.clone());
            let track_no = tag
                .as_ref()
                .map(|t| t.track_no)
                .filter(|n| *n > 0)
                .unwrap_or_else(|| util::leading_track_number(&file.file_name));
            let mut duration_sec = tag.as_ref().map(|t| t.duration_sec).unwrap_or(0);
            if duration_sec == 0 {
                duration_sec = media::probe_duration_sec(Path::new(&file.path)).unwrap_or(0);
            }
            chapters.push(PreparedChapter {
                path: file.path.clone(),
                rel_path: file.rel_path.clone(),
                file_name: file.file_name.clone(),
                title: tag
                    .as_ref()
                    .and_then(|t| t.title.clone())
                    .filter(|t| !t.trim().is_empty())
                    .unwrap_or(stem),
                track_no,
                duration_sec,
                size: file.size,
                mtime: file.mtime,
                from_video,
            });
        }

        books.push(PreparedBook {
            id: book_id,
            rel_path: dir.clone(),
            dir_path: book_dir.to_string_lossy().to_string(),
            title,
            art,
            chapters,
        });
    }

    progress(ScanProgress {
        root_id: root.id,
        root_name: root.display_name.clone(),
        phase: "collect".to_string(),
        scanned: total,
        total,
    });
    (books, Vec::new())
}

fn write_audiobooks(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    books: Vec<PreparedBook>,
    summary: &mut ScanSummary,
) -> Result<i64> {
    let mut book_stmt = tx.prepare_cached(
        "INSERT INTO audiobooks(id, root_id, rel_path, dir_path, title, chapter_count, duration_sec, has_art, scan_token)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
         ON CONFLICT(id) DO UPDATE SET
            root_id = excluded.root_id,
            rel_path = excluded.rel_path,
            dir_path = excluded.dir_path,
            title = excluded.title,
            chapter_count = excluded.chapter_count,
            duration_sec = excluded.duration_sec,
            has_art = excluded.has_art,
            scan_token = excluded.scan_token",
    )?;
    let mut chapter_stmt = tx.prepare_cached(
        "INSERT INTO audiobook_chapters(book_id, path, rel_path, file_name, title, track_no, duration_sec, size, mtime, from_video, scan_token)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
         ON CONFLICT(path) DO UPDATE SET
            book_id = excluded.book_id,
            rel_path = excluded.rel_path,
            file_name = excluded.file_name,
            title = excluded.title,
            track_no = excluded.track_no,
            duration_sec = excluded.duration_sec,
            size = excluded.size,
            mtime = excluded.mtime,
            from_video = excluded.from_video,
            scan_token = excluded.scan_token",
    )?;

    let mut item_count = 0i64;
    for book in books {
        let exists: Option<i64> = tx
            .query_row(
                "SELECT 1 FROM audiobooks WHERE id = ?1",
                params![book.id],
                |row| row.get(0),
            )
            .ok();
        let duration: i64 = book.chapters.iter().map(|c| c.duration_sec).sum();
        book_stmt.execute(params![
            book.id,
            root.id,
            book.rel_path,
            book.dir_path,
            book.title,
            book.chapters.len() as i64,
            duration,
            book.art.is_some() as i64,
            token,
        ])?;
        if exists.is_some() {
            summary.updated += 1;
        } else {
            summary.added += 1;
        }
        if let Some(art) = book.art {
            tx.execute(
                "INSERT OR REPLACE INTO art(key, mime, data, size) VALUES (?1, ?2, ?3, ?4)",
                params![
                    format!("audiobook:{}", book.id),
                    art.mime,
                    art.data,
                    art.data.len() as i64
                ],
            )?;
        }
        for chapter in book.chapters {
            chapter_stmt.execute(params![
                book.id,
                chapter.path,
                chapter.rel_path,
                chapter.file_name,
                chapter.title,
                chapter.track_no,
                chapter.duration_sec,
                chapter.size,
                chapter.mtime,
                chapter.from_video as i64,
                token,
            ])?;
            item_count += 1;
        }
    }
    drop(book_stmt);
    drop(chapter_stmt);

    summary.removed += tx.execute(
        "DELETE FROM audiobooks WHERE root_id = ?1 AND scan_token <> ?2",
        params![root.id, token],
    )? as i64;
    tx.execute(
        "DELETE FROM audiobook_chapters WHERE scan_token <> ?1
         AND book_id IN (SELECT id FROM audiobooks WHERE root_id = ?2)",
        params![token, root.id],
    )?;
    tx.execute(
        "DELETE FROM art WHERE key LIKE ?1
         AND key NOT IN (SELECT 'audiobook:' || id FROM audiobooks WHERE root_id = ?2)",
        params![format!("audiobook:ab:{}:%", root.id), root.id],
    )?;
    Ok(item_count)
}

// ------------------------------------------------------------------- books

struct PreparedBookFile {
    path: String,
    rel_path: String,
    file_name: String,
    title: String,
    author: String,
    size: i64,
    mtime: i64,
    format: String,
    meta_scanned: bool,
    cover: Option<ArtBlob>,
}

enum BookItem {
    Unchanged(String),
    Book(PreparedBookFile),
}

fn collect_books<F: Fn(ScanProgress)>(
    root: &RootInfo,
    root_path: &Path,
    existing: &HashMap<String, (i64, i64)>,
    progress: &F,
) -> Vec<BookItem> {
    let files = collect_files(root_path, util::is_book_ext);
    let total = files.len() as i64;
    let mut out = Vec::with_capacity(files.len());

    for (index, file) in files.iter().enumerate() {
        if index % 25 == 0 {
            progress(ScanProgress {
                root_id: root.id,
                root_name: root.display_name.clone(),
                phase: "collect".to_string(),
                scanned: index as i64,
                total,
            });
        }
        if let Some((size, mtime)) = existing.get(&file.path) {
            if *size == file.size && *mtime == file.mtime {
                out.push(BookItem::Unchanged(file.path.clone()));
                continue;
            }
        }
        let fast_lazy = root.fast_scan && file.ext == "epub";
        let meta = if !root.fast_scan && file.ext == "epub" {
            meta::read_epub(Path::new(&file.path))
        } else {
            meta::BookMeta::default()
        };
        let stem = Path::new(&file.file_name)
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| file.file_name.clone());
        out.push(BookItem::Book(PreparedBookFile {
            path: file.path.clone(),
            rel_path: file.rel_path.clone(),
            file_name: file.file_name.clone(),
            title: meta.title.unwrap_or(stem),
            author: meta.author.unwrap_or_default(),
            size: file.size,
            mtime: file.mtime,
            format: file.ext.clone(),
            meta_scanned: !fast_lazy,
            cover: meta.cover.map(|(mime, data)| ArtBlob { mime, data }),
        }));
    }
    progress(ScanProgress {
        root_id: root.id,
        root_name: root.display_name.clone(),
        phase: "collect".to_string(),
        scanned: total,
        total,
    });
    out
}

fn write_books(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    existing: &HashMap<String, (i64, i64)>,
    items: Vec<BookItem>,
    summary: &mut ScanSummary,
) -> Result<i64> {
    let mut stmt = tx.prepare_cached(
        "INSERT INTO books(path, root_id, rel_path, file_name, title, author, size, mtime, format, has_cover, meta_scanned, scan_token)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
         ON CONFLICT(path) DO UPDATE SET
            root_id = excluded.root_id,
            rel_path = excluded.rel_path,
            file_name = excluded.file_name,
            title = excluded.title,
            author = excluded.author,
            size = excluded.size,
            mtime = excluded.mtime,
            format = excluded.format,
            has_cover = excluded.has_cover,
            meta_scanned = excluded.meta_scanned,
            scan_token = excluded.scan_token",
    )?;

    let mut count = 0i64;
    for item in items {
        match item {
            BookItem::Unchanged(path) => {
                tx.execute(
                    "UPDATE books SET scan_token = ?1 WHERE path = ?2",
                    params![token, path],
                )?;
            }
            BookItem::Book(book) => {
                let has_cover = book.cover.is_some();
                if let Some(cover) = book.cover {
                    tx.execute(
                        "INSERT OR REPLACE INTO art(key, mime, data, size) VALUES (?1, ?2, ?3, ?4)",
                        params![
                            format!("book:{}", book.path),
                            cover.mime,
                            cover.data,
                            cover.data.len() as i64
                        ],
                    )?;
                }
                stmt.execute(params![
                    book.path,
                    root.id,
                    book.rel_path,
                    book.file_name,
                    book.title,
                    book.author,
                    book.size,
                    book.mtime,
                    book.format,
                    has_cover as i64,
                    if book.meta_scanned { 1 } else { 0 },
                    token,
                ])?;
                if existing.contains_key(&book.path) {
                    summary.updated += 1;
                } else {
                    summary.added += 1;
                }
            }
        }
        count += 1;
    }
    drop(stmt);

    summary.removed += tx.execute(
        "DELETE FROM books WHERE root_id = ?1 AND scan_token <> ?2",
        params![root.id, token],
    )? as i64;
    tx.execute(
        "DELETE FROM art WHERE key LIKE 'book:%'
         AND key NOT IN (SELECT 'book:' || path FROM books)",
        [],
    )?;
    Ok(count)
}

// ------------------------------------------------------------------- driver

enum Existing {
    Music(HashMap<String, (i64, i64)>),
    Books(HashMap<String, (i64, i64)>),
    None,
}

fn load_existing(conn: &Connection, root: &RootInfo) -> Result<Existing> {
    match root.kind.as_str() {
        KIND_MUSIC => {
            let mut stmt =
                conn.prepare("SELECT path, size, mtime FROM tracks WHERE root_id = ?1")?;
            let mut rows = stmt.query(params![root.id])?;
            let mut map = HashMap::new();
            while let Some(row) = rows.next()? {
                map.insert(row.get::<_, String>(0)?, (row.get(1)?, row.get(2)?));
            }
            Ok(Existing::Music(map))
        }
        KIND_BOOK => {
            let mut stmt =
                conn.prepare("SELECT path, size, mtime FROM books WHERE root_id = ?1")?;
            let mut rows = stmt.query(params![root.id])?;
            let mut map = HashMap::new();
            while let Some(row) = rows.next()? {
                map.insert(row.get::<_, String>(0)?, (row.get(1)?, row.get(2)?));
            }
            Ok(Existing::Books(map))
        }
        _ => Ok(Existing::None),
    }
}

pub fn scan_root<F>(db: &Db, root_id: i64, progress: F) -> Result<ScanSummary>
where
    F: Fn(ScanProgress),
{
    let started = Instant::now();
    let root = {
        let conn = db.lock();
        db::set_scan_state(&conn, root_id, "scanning")?;
        db::get_root(&conn, root_id)?
    };
    let root_path = PathBuf::from(&root.path);

    let outcome = (|| -> Result<ScanSummary> {
        let existing = {
            let conn = db.lock();
            load_existing(&conn, &root)?
        };

        let mut summary = ScanSummary {
            root_id: root.id,
            root_name: root.display_name.clone(),
            kind: root.kind.clone(),
            ..Default::default()
        };

        let empty_music: HashMap<String, (i64, i64)> = HashMap::new();
        let music_existing = match &existing {
            Existing::Music(map) => map,
            _ => &empty_music,
        };
        let book_existing = match &existing {
            Existing::Books(map) => map,
            _ => &empty_music,
        };

        // Collection (walking the filesystem + reading tags) happens without
        // holding the database lock so the HTTP server keeps answering.
        enum Collected {
            Music(Vec<MusicItem>, HashMap<PathBuf, Option<ArtBlob>>),
            Audiobooks(Vec<PreparedBook>),
            Books(Vec<BookItem>),
        }

        let collected = match root.kind.as_str() {
            KIND_MUSIC => {
                let (items, dir_arts, errors) =
                    collect_music(&root, &root_path, music_existing, &progress);
                summary.errors = errors;
                Collected::Music(items, dir_arts)
            }
            KIND_AUDIOBOOK => {
                let (books, errors) = collect_audiobooks(&root, &root_path, &progress);
                summary.errors = errors;
                Collected::Audiobooks(books)
            }
            KIND_BOOK => {
                let items = collect_books(&root, &root_path, book_existing, &progress);
                Collected::Books(items)
            }
            other => return Err(anyhow!("未知的目录类型: {other}")),
        };

        let mut conn = db.lock();
        let token = now_ms().max(1);
        let tx = conn.transaction().context("开启扫描事务失败")?;

        let item_count = match collected {
            Collected::Music(items, dir_arts) => write_music(
                &tx,
                &root,
                token,
                music_existing,
                items,
                &dir_arts,
                &mut summary,
            )?,
            Collected::Audiobooks(books) => {
                write_audiobooks(&tx, &root, token, books, &mut summary)?
            }
            Collected::Books(items) => {
                write_books(&tx, &root, token, book_existing, items, &mut summary)?
            }
        };

        tx.commit()?;
        db::finish_scan(&conn, root.id, item_count)?;
        db::refresh_library_version(&conn)?;
        summary.duration_ms = started.elapsed().as_millis() as i64;
        Ok(summary)
    })();

    if outcome.is_err() {
        let conn = db.lock();
        let _ = db::set_scan_state(&conn, root_id, "idle");
    }
    outcome
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::Db;
    use std::path::PathBuf;

    #[test]
    fn rel_dir_handles_root_files() {
        assert_eq!(rel_dir("a/b/c.mp3"), "a/b");
        assert_eq!(rel_dir("c.mp3"), "");
    }

    fn wav_bytes() -> Vec<u8> {
        // Minimal 8-bit mono WAV header with a handful of silent samples.
        let samples: Vec<u8> = vec![0u8; 800];
        let mut out = Vec::new();
        out.extend_from_slice(b"RIFF");
        out.extend_from_slice(&(36u32 + samples.len() as u32).to_le_bytes());
        out.extend_from_slice(b"WAVEfmt ");
        out.extend_from_slice(&16u32.to_le_bytes());
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&8000u32.to_le_bytes());
        out.extend_from_slice(&8000u32.to_le_bytes());
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&8u16.to_le_bytes());
        out.extend_from_slice(b"data");
        out.extend_from_slice(&(samples.len() as u32).to_le_bytes());
        out.extend_from_slice(&samples);
        out
    }

    fn scratch_dir(name: &str) -> PathBuf {
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("tmp")
            .join(name);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write_file(path: &Path, bytes: &[u8]) {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).unwrap();
        }
        std::fs::write(path, bytes).unwrap();
    }

    #[test]
    fn scans_music_in_folder_mode_and_is_incremental() {
        let base = scratch_dir("scan-music");
        let root_dir = base.join("library");
        let wav = wav_bytes();
        write_file(
            &root_dir.join("Artist").join("Album").join("01 - First.wav"),
            &wav,
        );
        write_file(
            &root_dir
                .join("Artist")
                .join("Album")
                .join("02 - Second.wav"),
            &wav,
        );
        write_file(
            &root_dir.join("Artist").join("Album").join("03 - Clip.mp4"),
            b"not a real video",
        );

        let db = Db::open(&base.join("library.db")).unwrap();
        let root = {
            let conn = db.lock();
            db::add_root(
                &conn,
                root_dir.to_str().unwrap(),
                KIND_MUSIC,
                Some(MODE_FOLDER),
                None,
                false,
            )
            .unwrap()
        };

        let summary = scan_root(&db, root.id, |_| {}).unwrap();
        assert_eq!(summary.added, 3);
        assert_eq!(summary.errors.len(), 0);

        let library = {
            let conn = db.lock();
            db::music_library(&conn).unwrap()
        };
        assert!(library.path_order);
        assert_eq!(library.total_tracks, 3);
        assert_eq!(library.artists.len(), 1);
        assert_eq!(library.artists[0].name, "Artist");
        assert_eq!(library.artists[0].albums.len(), 1);
        assert_eq!(library.artists[0].albums[0].title, "Album");
        assert_eq!(library.artists[0].albums[0].tracks[0].track_number, 1);

        // Second scan must not duplicate rows and must report everything as unchanged.
        let again = scan_root(&db, root.id, |_| {}).unwrap();
        assert_eq!(again.added, 0);
        assert_eq!(again.updated, 0);
        assert_eq!(again.removed, 0);

        let stats = {
            let conn = db.lock();
            db::stats(&conn).unwrap()
        };
        assert_eq!(stats.tracks, 3);
        assert_eq!(stats.video_tracks, 1);
        assert!(stats.library_version > 0);
    }

    #[test]
    fn scans_audiobooks_and_books() {
        let base = scratch_dir("scan-other");
        let audio_root = base.join("audiobooks");
        let wav = wav_bytes();
        write_file(&audio_root.join("Some Book").join("01.wav"), &wav);
        write_file(&audio_root.join("Some Book").join("02.wav"), &wav);

        let book_root = base.join("books");
        write_file(&book_root.join("My Book.epub"), b"not a real epub");

        let db = Db::open(&base.join("library.db")).unwrap();
        let (audio, books) = {
            let conn = db.lock();
            let audio = db::add_root(
                &conn,
                audio_root.to_str().unwrap(),
                KIND_AUDIOBOOK,
                None,
                None,
                false,
            )
            .unwrap();
            let books = db::add_root(
                &conn,
                book_root.to_str().unwrap(),
                KIND_BOOK,
                None,
                None,
                false,
            )
            .unwrap();
            (audio, books)
        };

        scan_root(&db, audio.id, |_| {}).unwrap();
        scan_root(&db, books.id, |_| {}).unwrap();

        let (audiobooks, book_list, snapshot) = {
            let conn = db.lock();
            (
                db::audiobooks(&conn).unwrap(),
                db::books(&conn).unwrap(),
                db::wire_snapshot(&conn, "device", "Device").unwrap(),
            )
        };

        assert_eq!(audiobooks.len(), 1);
        assert_eq!(audiobooks[0].title, "Some Book");
        assert_eq!(audiobooks[0].chapters.len(), 2);

        assert_eq!(book_list.len(), 1);
        assert_eq!(book_list[0].title, "My Book");

        assert_eq!(snapshot.audiobooks.len(), 1);
        assert_eq!(snapshot.ebook_roots.len(), 1);
        assert_eq!(snapshot.ebook_roots[0].key, books.id.to_string());
        assert_eq!(snapshot.ebooks.len(), 1);
        assert_eq!(snapshot.ebooks[0].root_key, books.id.to_string());
    }
}
