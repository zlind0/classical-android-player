use sha1::{Digest, Sha1};
use std::path::Path;

pub fn sha1_hex(data: &[u8]) -> String {
    let mut hasher = Sha1::new();
    hasher.update(data);
    let out = hasher.finalize();
    let mut s = String::with_capacity(out.len() * 2);
    for b in out {
        use std::fmt::Write;
        let _ = write!(s, "{b:02x}");
    }
    s
}

pub fn path_key(path: &Path) -> String {
    sha1_hex(path.to_string_lossy().as_bytes())
}

/// Case-insensitive natural ordering ("Track 2" < "Track 10").
pub fn natural_cmp(a: &str, b: &str) -> std::cmp::Ordering {
    let a = a.to_lowercase();
    let b = b.to_lowercase();
    let mut ai = a.char_indices().peekable();
    let mut bi = b.char_indices().peekable();

    loop {
        match (ai.next(), bi.next()) {
            (None, None) => return std::cmp::Ordering::Equal,
            (None, Some(_)) => return std::cmp::Ordering::Less,
            (Some(_), None) => return std::cmp::Ordering::Greater,
            (Some((_, ca)), Some((_, cb))) => {
                if ca.is_ascii_digit() && cb.is_ascii_digit() {
                    let mut na = String::new();
                    let mut nb = String::new();
                    na.push(ca);
                    nb.push(cb);
                    while let Some((_, c)) = ai.peek().copied() {
                        if c.is_ascii_digit() {
                            na.push(c);
                            ai.next();
                        } else {
                            break;
                        }
                    }
                    while let Some((_, c)) = bi.peek().copied() {
                        if c.is_ascii_digit() {
                            nb.push(c);
                            bi.next();
                        } else {
                            break;
                        }
                    }
                    let va: u128 = na.parse().unwrap_or(0);
                    let vb: u128 = nb.parse().unwrap_or(0);
                    if va != vb {
                        return va.cmp(&vb);
                    }
                } else if ca != cb {
                    return ca.cmp(&cb);
                }
            }
        }
    }
}

pub fn audio_mime(ext: &str) -> &'static str {
    match ext {
        "mp3" => "audio/mpeg",
        "m4a" | "mp4" | "m4b" | "alac" => "audio/mp4",
        "aac" => "audio/aac",
        "flac" => "audio/flac",
        "ogg" | "oga" | "opus" => "audio/ogg",
        "wav" => "audio/wav",
        "aif" | "aiff" => "audio/aiff",
        "wma" => "audio/x-ms-wma",
        "ape" => "audio/x-ape",
        "wv" => "audio/x-wavpack",
        "dsf" => "audio/x-dsf",
        "dff" => "audio/x-dff",
        // Video containers, kept for the ffmpeg-less fallback path.
        "mkv" => "audio/x-matroska",
        "webm" => "audio/webm",
        "avi" => "video/x-msvideo",
        "mov" => "video/quicktime",
        "ts" | "m2ts" => "video/mp2t",
        "flv" => "video/x-flv",
        _ => "application/octet-stream",
    }
}

pub fn ebook_mime(ext: &str) -> &'static str {
    match ext {
        "epub" => "application/epub+zip",
        "mobi" => "application/x-mobipocket-ebook",
        "azw" | "azw3" => "application/vnd.amazon.ebook",
        "pdf" => "application/pdf",
        "txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

pub const AUDIO_EXTS: &[&str] = &[
    "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "wma", "ape",
    "wv", "dsf", "dff", "alac",
];

pub const VIDEO_EXTS: &[&str] = &[
    "mp4", "mkv", "webm", "mov", "m4v", "avi", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg",
];

// TODO(pdf): PDF 解析（元数据/封面/阅读）还没做完，先不把 pdf 纳入服务端索引；
// 做完后把 "pdf" 加回 BOOK_EXTS 即可恢复索引。ebook_mime 里仍保留 pdf 映射。
pub const BOOK_EXTS: &[&str] = &["epub", "mobi", "azw", "azw3", "txt"];

pub fn is_audio_ext(ext: &str) -> bool {
    AUDIO_EXTS.contains(&ext)
}

pub fn is_video_ext(ext: &str) -> bool {
    VIDEO_EXTS.contains(&ext)
}

pub fn is_book_ext(ext: &str) -> bool {
    BOOK_EXTS.contains(&ext)
}

pub fn is_media_ext(ext: &str) -> bool {
    is_audio_ext(ext) || is_video_ext(ext)
}

/// Pulls a leading track number out of a filename, e.g. `01 - intro.flac` -> 1.
pub fn leading_track_number(name: &str) -> i64 {
    let digits: String = name
        .chars()
        .skip_while(|c| !c.is_ascii_digit())
        .take_while(|c| c.is_ascii_digit())
        .collect();
    if digits.is_empty() {
        return 0;
    }
    digits.parse().unwrap_or(0)
}

pub fn is_hidden(path: &Path) -> bool {
    path.file_name()
        .map(|n| n.to_string_lossy().starts_with('.'))
        .unwrap_or(false)
}

/// Directory cover lookup used for albums / audiobooks.
pub fn dir_cover(dir: &Path) -> Option<(String, Vec<u8>)> {
    const NAMES: &[&str] = &[
        "cover.jpg",
        "cover.jpeg",
        "cover.png",
        "cover.webp",
        "folder.jpg",
        "folder.jpeg",
        "folder.png",
        "front.jpg",
        "front.jpeg",
        "front.png",
        "album.jpg",
        "album.jpeg",
        "album.png",
    ];
    for name in NAMES {
        let p = dir.join(name);
        if let Ok(meta) = std::fs::metadata(&p) {
            if meta.is_file() && meta.len() > 0 && meta.len() <= 12 * 1024 * 1024 {
                if let Ok(data) = std::fs::read(&p) {
                    let mime = match p
                        .extension()
                        .and_then(|e| e.to_str())
                        .map(|e| e.to_ascii_lowercase())
                    {
                        Some(e) if e == "png" => "image/png",
                        Some(e) if e == "webp" => "image/webp",
                        _ => "image/jpeg",
                    };
                    return Some((mime.to_string(), data));
                }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn natural_order_handles_numbers() {
        let mut items = vec!["track10.mp3", "track2.mp3", "track1.mp3"];
        items.sort_by(|a, b| natural_cmp(a, b));
        assert_eq!(items, vec!["track1.mp3", "track2.mp3", "track10.mp3"]);
    }

    #[test]
    fn leading_track_number_reads_prefix() {
        assert_eq!(leading_track_number("01 - intro.flac"), 1);
        assert_eq!(leading_track_number("Chapter 12.mp3"), 12);
        assert_eq!(leading_track_number("intro.flac"), 0);
    }

    #[test]
    fn hidden_dot_files_are_skipped() {
        assert!(is_hidden(Path::new("/music/.cover.jpg")));
        assert!(!is_hidden(Path::new("/music/cover.jpg")));
    }

    #[test]
    fn audio_mime_by_extension() {
        assert_eq!(audio_mime("flac"), "audio/flac");
        assert_eq!(audio_mime("m4a"), "audio/mp4");
        assert_eq!(ebook_mime("epub"), "application/epub+zip");
    }
}
