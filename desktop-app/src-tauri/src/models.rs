use serde::{Deserialize, Serialize};

pub const KIND_MUSIC: &str = "music";
pub const KIND_AUDIOBOOK: &str = "audiobook";
pub const KIND_BOOK: &str = "book";
pub const KIND_VIDEO: &str = "video";

pub const MODE_TAG: &str = "tag";
pub const MODE_FOLDER: &str = "folder";

pub fn now_ms() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// A shared folder as configured by the user.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RootInfo {
    pub id: i64,
    pub path: String,
    pub kind: String,
    /// `tag` or `folder`; only meaningful for `kind == "music"`.
    pub music_mode: Option<String>,
    /// One-way fast-scan mode: books are indexed by file name only, and
    /// metadata is parsed lazily when a client randomly picks them.
    pub fast_scan: bool,
    pub display_name: String,
    pub created_at: i64,
    pub last_scan_at: Option<i64>,
    pub scan_state: String,
    pub item_count: i64,
}

/// Server coordinates exposed to the UI so it can build media URLs.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerInfo {
    pub device_id: String,
    pub device_name: String,
    pub port: u16,
    pub started_at: i64,
}

impl ServerInfo {
    pub fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanProgress {
    pub root_id: i64,
    pub root_name: String,
    pub phase: String,
    pub scanned: i64,
    pub total: i64,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ScanSummary {
    pub root_id: i64,
    pub root_name: String,
    pub kind: String,
    pub added: i64,
    pub updated: i64,
    pub removed: i64,
    pub skipped: i64,
    pub duration_ms: i64,
    pub errors: Vec<String>,
}

/// A single music track, both for the UI tree and the `/library/all` payload.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TrackDto {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub album_id: String,
    pub artist_id: String,
    pub duration_sec: i64,
    pub track_number: i64,
    pub disc_number: i64,
    pub has_art: bool,
    pub merge_titles: bool,
    pub path: String,
    pub file_name: String,
    pub size: i64,
    pub from_video: bool,
    pub root_id: i64,
    pub art_url: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AlbumGroup {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub cover_track_id: Option<String>,
    pub duration_sec: i64,
    pub tracks: Vec<TrackDto>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistGroup {
    pub id: String,
    pub name: String,
    pub track_count: i64,
    pub albums: Vec<AlbumGroup>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MusicLibrary {
    /// `true` when at least one music root uses the folder (artist/album) mode,
    /// which makes clients sort tracks by path.
    pub path_order: bool,
    pub total_tracks: i64,
    pub artists: Vec<ArtistGroup>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChapterDto {
    pub path: String,
    pub title: String,
    pub track_number: i64,
    pub duration_sec: i64,
    pub size: i64,
    pub from_video: bool,
    pub audio_url: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudiobookDto {
    pub id: String,
    pub root_id: i64,
    pub title: String,
    pub rel_path: String,
    pub chapter_count: i64,
    pub duration_sec: i64,
    pub has_art: bool,
    pub cover_url: Option<String>,
    pub chapters: Vec<ChapterDto>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BookDto {
    pub path: String,
    pub root_id: i64,
    pub rel_path: String,
    pub title: String,
    pub author: String,
    pub size: i64,
    pub format: String,
    pub has_cover: bool,
    pub meta_scanned: bool,
    pub cover_url: Option<String>,
    pub download_url: String,
}

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct LibraryStats {
    pub roots: i64,
    pub tracks: i64,
    pub audiobooks: i64,
    pub chapters: i64,
    pub books: i64,
    pub videos: i64,
    pub video_tracks: i64,
    pub library_version: i64,
}

/// Wire format of `GET /library/all` (see remote_database.md §4.2).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireTrack {
    pub id: String,
    pub title: String,
    pub artist: String,
    pub album: String,
    pub album_id: String,
    pub artist_id: String,
    pub duration_sec: i64,
    pub track_number: i64,
    pub disc_number: i64,
    pub has_art: bool,
    pub merge_titles: bool,
    pub path: String,
    /// Library root that owns this track. Used by relay folder ACLs.
    pub root_key: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireEbookRoot {
    pub key: String,
    pub root_path: String,
    pub display_name: String,
    pub shelf: bool,
    pub book_count: i64,
    /// One-way fast-scan mode hint for clients (random picks drive lazy parsing).
    pub fast_scan: bool,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireEbook {
    pub path: String,
    pub title: String,
    pub author: String,
    pub size: i64,
    pub has_cover: bool,
    pub meta_scanned: bool,
    pub root_key: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireChapter {
    pub path: String,
    pub title: String,
    pub track_number: i64,
    pub duration_sec: i64,
    pub size: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireAudiobook {
    pub id: String,
    pub title: String,
    pub has_cover: bool,
    pub chapters: Vec<WireChapter>,
    /// Library root that owns this audiobook. Used by relay folder ACLs.
    pub root_key: String,
}

/// One shared short-video root as seen by clients.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireVideoRoot {
    pub key: String,
    pub root_path: String,
    pub display_name: String,
    pub video_count: i64,
}

/// One video file. Only the file listing is indexed on the host; duration and
/// resolution are left to the client's player (no ffprobe pass during scans).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireVideo {
    /// Absolute path; also the stable identity clients use in URLs.
    pub path: String,
    /// Path relative to the video root, using `/` separators.
    pub rel_path: String,
    /// Directory of `rel_path` (`""` for files directly inside the root).
    pub dir_path: String,
    /// File name with extension; the client falls back to it for the title.
    pub name: String,
    /// File name without extension, used as the default title.
    pub title: String,
    pub size: i64,
    pub mtime: i64,
}

/// One directory that contains videos (or subdirectories of such a directory),
/// so clients can browse a video root like a file explorer.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireVideoDir {
    pub path: String,
    pub name: String,
    /// Videos directly inside this directory.
    pub video_count: i64,
    /// Direct subdirectories.
    pub subdir_count: i64,
    /// Videos anywhere below this directory, subdirectories included.
    pub deep_count: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireVideoListing {
    pub path: String,
    pub dirs: Vec<WireVideoDir>,
    pub videos: Vec<WireVideo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireVideoBatch {
    pub videos: Vec<WireVideo>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WireSnapshot {
    pub device_id: String,
    pub device_name: String,
    pub generated_at: i64,
    pub library_updated_at: i64,
    pub music_path_order: bool,
    pub music: Vec<WireTrack>,
    pub ebook_roots: Vec<WireEbookRoot>,
    pub ebooks: Vec<WireEbook>,
    pub audiobooks: Vec<WireAudiobook>,
    /// Only the roots travel in the snapshot; per-directory listings and random
    /// batches stay behind live endpoints so a 100k-video library never gets
    /// serialized into `/library/all`.
    pub video_roots: Vec<WireVideoRoot>,
}
