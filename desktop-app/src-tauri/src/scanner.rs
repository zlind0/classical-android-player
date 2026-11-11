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

/// Seconds since the epoch for an already-fetched metadata. Taking the
/// `Metadata` instead of the path keeps the walk to one `stat` per file: the
/// size and the mtime come from the same syscall.
fn meta_mtime(meta: &std::fs::Metadata) -> i64 {
    meta.modified()
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
            mtime: meta_mtime(&meta),
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

// ------------------------------------------------------------ write batching

/// Rows per write transaction.
///
/// All database access goes through one `Mutex<Connection>`, so a scan that
/// writes a whole directory in a single transaction blocks every other command
/// (adding a root, `/library/*`, relay index pushes) for as long as it takes to
/// insert the whole tree. Committing in batches releases the lock in between,
/// which keeps the desktop UI and connected clients responsive while a big
/// directory is being indexed.
///
/// The trade-off is atomicity: an interrupted scan leaves a mix of old and new
/// rows behind, and the `scan_token` cleanup never runs. That is harmless — the
/// next scan of the root fixes both.
const WRITE_CHUNK: usize = 2000;

/// Album/book covers are blobs, so they get smaller batches than plain rows.
const ART_CHUNK: usize = 16;

/// Runs `write_chunk` over `items` in [`WRITE_CHUNK`] batches, taking the
/// database lock only for one batch at a time.
fn write_batched<T, F>(db: &Db, items: Vec<T>, mut write_chunk: F) -> Result<i64>
where
    F: FnMut(&Connection, &[T]) -> Result<i64>,
{
    let mut iter = items.into_iter();
    let mut total = 0i64;
    loop {
        let batch: Vec<T> = iter.by_ref().take(WRITE_CHUNK).collect();
        if batch.is_empty() {
            break;
        }
        total += with_write_tx(db, |tx| write_chunk(tx, &batch))?;
    }
    Ok(total)
}

/// Opens one transaction, runs `f` inside it and commits. Used for the small
/// bookkeeping steps that must stay in a single transaction (stale-row cleanup,
/// directory-tree rebuild, scan state).
fn with_write_tx<T, F>(db: &Db, f: F) -> Result<T>
where
    F: FnOnce(&Connection) -> Result<T>,
{
    let mut conn = db.lock();
    let tx = conn.transaction().context("开启扫描事务失败")?;
    let value = f(&tx)?;
    tx.commit()?;
    Ok(value)
}

fn write_music_art(
    db: &Db,
    root: &RootInfo,
    dir_arts: HashMap<PathBuf, Option<ArtBlob>>,
) -> Result<()> {
    // Album covers first so track rows can point at stable keys.
    let mut pending: Vec<(String, ArtBlob)> = dir_arts
        .into_iter()
        .filter_map(|(dir, blob)| {
            blob.map(|blob| (format!("album:{}:{}", root.id, path_key(&dir)), blob))
        })
        .collect();
    while !pending.is_empty() {
        let batch: Vec<(String, ArtBlob)> = pending.drain(..pending.len().min(ART_CHUNK)).collect();
        with_write_tx(db, |tx| {
            for (key, blob) in batch {
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
            Ok(())
        })?;
    }
    Ok(())
}

fn write_music_batch(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    existing: &HashMap<String, (i64, i64)>,
    items: &[MusicItem],
    summary: &mut ScanSummary,
) -> Result<i64> {
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
    Ok(count)
}

/// Drops tracks this scan did not see and the album art that no longer belongs
/// to any of them.
fn finish_music(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    summary: &mut ScanSummary,
) -> Result<()> {
    summary.removed += tx.execute(
        "DELETE FROM tracks WHERE root_id = ?1 AND scan_token <> ?2",
        params![root.id, token],
    )? as i64;

    tx.execute(
        "DELETE FROM art WHERE key LIKE ?1
         AND key NOT IN (SELECT art_key FROM tracks WHERE root_id = ?2 AND art_key IS NOT NULL)",
        params![format!("album:{}:%", root.id), root.id],
    )?;
    Ok(())
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

fn write_audiobooks_batch(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    books: &[PreparedBook],
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
        if let Some(art) = &book.art {
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
        for chapter in &book.chapters {
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
    Ok(item_count)
}

/// Drops audiobooks this scan did not see, their chapters and their covers.
fn finish_audiobooks(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    summary: &mut ScanSummary,
) -> Result<()> {
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
    Ok(())
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

fn write_books_batch(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    existing: &HashMap<String, (i64, i64)>,
    items: &[BookItem],
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
                if let Some(cover) = &book.cover {
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
    Ok(count)
}

/// Drops books this scan did not see and the covers that no longer belong to
/// any book.
fn finish_books(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    summary: &mut ScanSummary,
) -> Result<()> {
    summary.removed += tx.execute(
        "DELETE FROM books WHERE root_id = ?1 AND scan_token <> ?2",
        params![root.id, token],
    )? as i64;
    tx.execute(
        "DELETE FROM art WHERE key LIKE 'book:%'
         AND key NOT IN (SELECT 'book:' || path FROM books)",
        [],
    )?;
    Ok(())
}

// ------------------------------------------------------------------- videos

struct VideoItem {
    path: String,
    rel_path: String,
    file_name: String,
    title: String,
    size: i64,
    mtime: i64,
    ext: String,
}

enum VideoEntry {
    /// Already indexed with the same size and mtime. The relative path comes
    /// from the walk, so the directory aggregates do not need a second read of
    /// the `videos` table.
    Unchanged {
        path: String,
        rel_path: String,
    },
    Video(VideoItem),
}

/// Extension-only scan: the file name is the whole record. Container metadata
/// (duration, resolution, tracks) is left to the client's player.
fn collect_videos<F: Fn(ScanProgress)>(
    root: &RootInfo,
    root_path: &Path,
    existing: &HashMap<String, (i64, i64)>,
    progress: &F,
) -> Vec<VideoEntry> {
    let files = collect_files(root_path, util::is_video_ext);
    let total = files.len() as i64;
    let mut out = Vec::with_capacity(files.len());

    for (index, file) in files.into_iter().enumerate() {
        if index % 50 == 0 {
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
                out.push(VideoEntry::Unchanged {
                    path: file.path,
                    rel_path: file.rel_path,
                });
                continue;
            }
        }
        let stem = Path::new(&file.file_name)
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| file.file_name.clone());
        out.push(VideoEntry::Video(VideoItem {
            path: file.path,
            rel_path: file.rel_path,
            file_name: file.file_name,
            title: stem,
            size: file.size,
            mtime: file.mtime,
            ext: file.ext,
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

#[derive(Default)]
struct DirAgg {
    name: String,
    video_count: i64,
    deep_count: i64,
}

/// Registers one video path into the per-root directory aggregate: every
/// ancestor gets a `deep_count` bump, the direct parent gets `video_count`.
fn record_video_dirs(rel_path: &str, dirs: &mut HashMap<String, DirAgg>) {
    let dir = rel_dir(rel_path);
    if dir.is_empty() {
        return;
    }
    let mut prefix = String::new();
    for part in dir.split('/') {
        if !prefix.is_empty() {
            prefix.push('/');
        }
        prefix.push_str(part);
        let entry = dirs.entry(prefix.clone()).or_default();
        if entry.name.is_empty() {
            entry.name = part.to_string();
        }
        entry.deep_count += 1;
    }
    if let Some(entry) = dirs.get_mut(&dir) {
        entry.video_count += 1;
    }
}

fn write_videos_batch(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    existing: &HashMap<String, (i64, i64)>,
    items: &[VideoEntry],
    dirs: &mut HashMap<String, DirAgg>,
    summary: &mut ScanSummary,
) -> Result<i64> {
    let mut stmt = tx.prepare_cached(
        "INSERT INTO videos(path, root_id, rel_path, dir_path, file_name, title, size, mtime, ext, scan_token)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
         ON CONFLICT(path) DO UPDATE SET
            root_id = excluded.root_id,
            rel_path = excluded.rel_path,
            dir_path = excluded.dir_path,
            file_name = excluded.file_name,
            title = excluded.title,
            size = excluded.size,
            mtime = excluded.mtime,
            ext = excluded.ext,
            scan_token = excluded.scan_token",
    )?;

    let mut count = 0i64;
    // Cached so a rescan of a big library does not re-prepare the same
    // statement once per unchanged file.
    let mut touch = tx.prepare_cached("UPDATE videos SET scan_token = ?1 WHERE path = ?2")?;
    for item in items {
        match item {
            VideoEntry::Unchanged { path, rel_path } => {
                touch.execute(params![token, path])?;
                record_video_dirs(rel_path, dirs);
                count += 1;
            }
            VideoEntry::Video(video) => {
                stmt.execute(params![
                    video.path,
                    root.id,
                    video.rel_path,
                    rel_dir(&video.rel_path),
                    video.file_name,
                    video.title,
                    video.size,
                    video.mtime,
                    video.ext,
                    token,
                ])?;
                if existing.contains_key(&video.path) {
                    summary.updated += 1;
                } else {
                    summary.added += 1;
                }
                record_video_dirs(&video.rel_path, dirs);
                count += 1;
            }
        }
    }
    Ok(count)
}

/// Drops videos this scan did not see and syncs this root's directory tree.
///
/// The tree is diffed against what is already stored instead of being deleted
/// and re-inserted: a rescan that changes nothing writes no `video_dirs` rows
/// at all, and a scan that adds one clip only touches the directories on that
/// clip's path.
fn finish_videos(
    tx: &Connection,
    root: &RootInfo,
    token: i64,
    dirs: &HashMap<String, DirAgg>,
    summary: &mut ScanSummary,
) -> Result<()> {
    summary.removed += tx.execute(
        "DELETE FROM videos WHERE root_id = ?1 AND scan_token <> ?2",
        params![root.id, token],
    )? as i64;

    let mut subdirs: HashMap<String, i64> = HashMap::new();
    for rel in dirs.keys() {
        let parent = rel_dir(rel);
        if !parent.is_empty() {
            *subdirs.entry(parent).or_insert(0) += 1;
        }
    }

    // Stored rows, so unchanged directories can be skipped.
    let stored: HashMap<String, (String, String, i64, i64, i64)> = {
        let mut stmt = tx.prepare(
            "SELECT rel_path, parent_path, name, video_count, subdir_count, deep_count
             FROM video_dirs WHERE root_id = ?1",
        )?;
        let rows = stmt.query_map(params![root.id], |row| {
            Ok((
                row.get::<_, String>(0)?,
                (
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, i64>(4)?,
                    row.get::<_, i64>(5)?,
                ),
            ))
        })?;
        let mut map = HashMap::new();
        for row in rows {
            let (rel, values) = row?;
            map.insert(rel, values);
        }
        map
    };

    let mut upsert = tx.prepare_cached(
        "INSERT INTO video_dirs(root_id, rel_path, parent_path, name, video_count, subdir_count, deep_count, scan_token)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
         ON CONFLICT(root_id, rel_path) DO UPDATE SET
            parent_path = excluded.parent_path,
            name = excluded.name,
            video_count = excluded.video_count,
            subdir_count = excluded.subdir_count,
            deep_count = excluded.deep_count,
            scan_token = excluded.scan_token",
    )?;
    let mut delete =
        tx.prepare_cached("DELETE FROM video_dirs WHERE root_id = ?1 AND rel_path = ?2")?;

    for (rel, agg) in dirs {
        let parent = rel_dir(rel);
        let subdir_count = subdirs.get(rel).copied().unwrap_or(0);
        let unchanged = stored.get(rel).is_some_and(|(p, n, vc, sc, dc)| {
            *p == parent
                && *n == agg.name
                && *vc == agg.video_count
                && *sc == subdir_count
                && *dc == agg.deep_count
        });
        if unchanged {
            continue;
        }
        upsert.execute(params![
            root.id,
            rel,
            parent,
            agg.name,
            agg.video_count,
            subdir_count,
            agg.deep_count,
            token,
        ])?;
    }
    for rel in stored.keys() {
        if !dirs.contains_key(rel) {
            delete.execute(params![root.id, rel])?;
        }
    }
    Ok(())
}

// ------------------------------------------------------------------- driver

enum Existing {
    Music(HashMap<String, (i64, i64)>),
    Books(HashMap<String, (i64, i64)>),
    Videos(HashMap<String, (i64, i64)>),
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
        KIND_VIDEO => {
            let mut stmt =
                conn.prepare("SELECT path, size, mtime FROM videos WHERE root_id = ?1")?;
            let mut rows = stmt.query(params![root.id])?;
            let mut map = HashMap::new();
            while let Some(row) = rows.next()? {
                map.insert(row.get::<_, String>(0)?, (row.get(1)?, row.get(2)?));
            }
            Ok(Existing::Videos(map))
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
        let video_existing = match &existing {
            Existing::Videos(map) => map,
            _ => &empty_music,
        };

        // Collection (walking the filesystem + reading tags) happens without
        // holding the database lock so the HTTP server keeps answering.
        enum Collected {
            Music(Vec<MusicItem>, HashMap<PathBuf, Option<ArtBlob>>),
            Audiobooks(Vec<PreparedBook>),
            Books(Vec<BookItem>),
            Videos(Vec<VideoEntry>),
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
            KIND_VIDEO => {
                let items = collect_videos(&root, &root_path, video_existing, &progress);
                Collected::Videos(items)
            }
            other => return Err(anyhow!("未知的目录类型: {other}")),
        };

        let token = now_ms().max(1);

        // Each batch below commits and drops the database lock before the next
        // one, so adding a root or a `/library/*` request never has to wait for
        // a whole big directory to be written.
        let item_count = match collected {
            Collected::Music(items, dir_arts) => {
                write_music_art(db, &root, dir_arts)?;
                let count = write_batched(db, items, |tx, batch| {
                    write_music_batch(tx, &root, token, music_existing, batch, &mut summary)
                })?;
                with_write_tx(db, |tx| finish_music(tx, &root, token, &mut summary))?;
                count
            }
            Collected::Audiobooks(books) => {
                let count = write_batched(db, books, |tx, batch| {
                    write_audiobooks_batch(tx, &root, token, batch, &mut summary)
                })?;
                with_write_tx(db, |tx| finish_audiobooks(tx, &root, token, &mut summary))?;
                count
            }
            Collected::Books(items) => {
                let count = write_batched(db, items, |tx, batch| {
                    write_books_batch(tx, &root, token, book_existing, batch, &mut summary)
                })?;
                with_write_tx(db, |tx| finish_books(tx, &root, token, &mut summary))?;
                count
            }
            Collected::Videos(items) => {
                let mut dirs: HashMap<String, DirAgg> = HashMap::new();
                let count = write_batched(db, items, |tx, batch| {
                    write_videos_batch(
                        tx,
                        &root,
                        token,
                        video_existing,
                        batch,
                        &mut dirs,
                        &mut summary,
                    )
                })?;
                with_write_tx(db, |tx| {
                    finish_videos(tx, &root, token, &dirs, &mut summary)
                })?;
                count
            }
        };

        let changed = summary.added + summary.updated + summary.removed > 0;
        with_write_tx(db, |tx| {
            db::finish_scan(tx, root.id, item_count)?;
            // The version only has to move when rows actually changed; a
            // no-op rescan keeps it stable so clients skip a full refetch.
            if changed {
                db::bump_library_version(tx)?;
            }
            Ok(())
        })?;
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

    /// Each run gets its own directory: repeated `cargo test` runs must not
    /// collide with an existing library.db (the tests never delete state).
    fn scratch_dir(name: &str) -> PathBuf {
        let stamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("tmp")
            .join(format!("{name}-{}-{stamp}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write_file(path: &Path, bytes: &[u8]) {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).unwrap();
        }
        std::fs::write(path, bytes).unwrap();
    }

    /// `rel_path -> scan_token` for one root's directory rows. The token only
    /// moves when the scan actually rewrites a row, so it doubles as a cheap
    /// "was this directory touched?" probe.
    fn video_dir_tokens(db: &Db, root_id: i64) -> HashMap<String, i64> {
        let conn = db.lock();
        let mut stmt = conn
            .prepare("SELECT rel_path, scan_token FROM video_dirs WHERE root_id = ?1")
            .unwrap();
        let rows = stmt
            .query_map(rusqlite::params![root_id], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
            })
            .unwrap();
        rows.map(|row| row.unwrap()).collect()
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
    fn scans_videos_by_extension_and_builds_directory_tree() {
        let base = scratch_dir("scan-video");
        let root_dir = base.join("videos");
        write_file(&root_dir.join("clip.mp4"), b"x");
        write_file(&root_dir.join("Season 1").join("ep01.mkv"), b"x");
        write_file(
            &root_dir.join("Season 1").join("Extras").join("behind.webm"),
            b"x",
        );
        write_file(&root_dir.join("notes.txt"), b"not a video");

        let db = Db::open(&base.join("library.db")).unwrap();
        let root = {
            let conn = db.lock();
            db::add_root(
                &conn,
                root_dir.to_str().unwrap(),
                KIND_VIDEO,
                None,
                None,
                false,
            )
            .unwrap()
        };

        let summary = scan_root(&db, root.id, |_| {}).unwrap();
        assert_eq!(summary.added, 3);
        assert!(summary.errors.is_empty());

        let root_key = root.id.to_string();
        let listing = {
            let conn = db.lock();
            db::video_listing(&conn, &root_key, "").unwrap()
        };
        assert_eq!(listing.videos.len(), 1);
        assert_eq!(listing.videos[0].name, "clip.mp4");
        assert_eq!(listing.videos[0].title, "clip");
        assert_eq!(listing.dirs.len(), 1);
        assert_eq!(listing.dirs[0].name, "Season 1");
        assert_eq!(listing.dirs[0].video_count, 1);
        assert_eq!(listing.dirs[0].subdir_count, 1);
        assert_eq!(listing.dirs[0].deep_count, 2);

        let season = {
            let conn = db.lock();
            db::video_listing(&conn, &root_key, "Season 1").unwrap()
        };
        assert_eq!(season.videos.len(), 1);
        assert_eq!(season.dirs.len(), 1);
        assert_eq!(season.dirs[0].deep_count, 1);

        let nested_random = {
            let conn = db.lock();
            db::video_random(&conn, &root_key, "Season 1", 10).unwrap()
        };
        assert_eq!(nested_random.len(), 2);

        // A rescan keeps rows stable and rebuilds the same tree.
        let version_before = {
            let conn = db.lock();
            db::library_version(&conn).unwrap()
        };
        let again = scan_root(&db, root.id, |_| {}).unwrap();
        assert_eq!(again.added, 0);
        assert_eq!(again.updated, 0);
        assert_eq!(again.removed, 0);
        // Nothing changed, so clients must not be told to refetch everything.
        let version_after = {
            let conn = db.lock();
            db::library_version(&conn).unwrap()
        };
        assert_eq!(version_before, version_after);
        let rebuilt = {
            let conn = db.lock();
            db::video_listing(&conn, &root_key, "").unwrap()
        };
        assert_eq!(rebuilt.dirs[0].deep_count, 2);

        // The tree is diffed, not rebuilt: a no-op rescan must not rewrite a
        // single `video_dirs` row. The sleep makes the scan token differ, so
        // the assertion below is meaningful.
        let tokens_before = video_dir_tokens(&db, root.id);
        std::thread::sleep(std::time::Duration::from_millis(5));
        let _ = scan_root(&db, root.id, |_| {}).unwrap();
        assert_eq!(video_dir_tokens(&db, root.id), tokens_before);

        // Adding a file does move the version.
        write_file(&root_dir.join("clip2.mp4"), b"x");
        let third = scan_root(&db, root.id, |_| {}).unwrap();
        assert_eq!(third.added, 1);
        let version_changed = {
            let conn = db.lock();
            db::library_version(&conn).unwrap()
        };
        assert!(version_changed > version_after);

        // A clip in a brand new folder only touches that folder; the untouched
        // branches keep their old rows.
        write_file(&root_dir.join("Season 2").join("ep01.mkv"), b"x");
        let fourth = scan_root(&db, root.id, |_| {}).unwrap();
        assert_eq!(fourth.added, 1);
        let tokens_after = video_dir_tokens(&db, root.id);
        assert!(tokens_after.contains_key("Season 2"));
        assert_ne!(tokens_after.get("Season 2"), tokens_before.get("Season 2"));
        assert_eq!(tokens_after.get("Season 1"), tokens_before.get("Season 1"));
        assert_eq!(
            tokens_after.get("Season 1/Extras"),
            tokens_before.get("Season 1/Extras")
        );

        // The same folder may be shared as a music root at the same time.
        let music = {
            let conn = db.lock();
            db::add_root(
                &conn,
                root_dir.to_str().unwrap(),
                KIND_MUSIC,
                Some(MODE_TAG),
                None,
                false,
            )
            .unwrap()
        };
        assert_ne!(music.id, root.id);
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
