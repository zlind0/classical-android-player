//! Small formatting helpers shared by the relay server and the NAS agent so
//! every log line describes the same way what was pushed, pulled or evicted.

use pod_relay_protocol::file_id::hex;
use pod_relay_protocol::FileId;

/// First 8 hex characters of a file id. Long enough to follow one file across
/// log lines, short enough to keep them readable.
pub fn file_short(id: &FileId) -> String {
    hex(id).get(..8).unwrap_or_default().to_string()
}

/// Human readable byte count such as `12.4 MiB` or `834 B`.
pub fn bytes(value: u64) -> String {
    const KIB: f64 = 1024.0;
    const MIB: f64 = KIB * 1024.0;
    const GIB: f64 = MIB * 1024.0;
    let value_f = value as f64;
    if value_f >= GIB {
        format!("{:.2} GiB", value_f / GIB)
    } else if value_f >= MIB {
        format!("{:.1} MiB", value_f / MIB)
    } else if value_f >= KIB {
        format!("{:.1} KiB", value_f / KIB)
    } else {
        format!("{value} B")
    }
}

/// Human readable duration for elapsed-time fields.
pub fn elapsed(ms: u128) -> String {
    if ms < 1000 {
        format!("{ms}ms")
    } else if ms < 60_000 {
        format!("{:.2}s", ms as f64 / 1000.0)
    } else {
        format!("{:.1}m", ms as f64 / 60_000.0)
    }
}

/// Best-effort media category from the MIME type the host reported. The relay
/// never sees paths, so audio files (music tracks and audiobook chapters) share
/// the same label here; the agent logs the exact route.
pub fn kind_from_mime(mime: &str) -> &'static str {
    let mime = mime.to_ascii_lowercase();
    if mime.starts_with("audio/") {
        "audio"
    } else if mime.starts_with("image/") {
        "image"
    } else if mime.contains("epub")
        || mime.contains("pdf")
        || mime.contains("mobi")
        || mime.starts_with("text/")
    {
        "ebook"
    } else {
        "file"
    }
}

/// Exact media category from a host route produced by the agent index.
pub fn kind_from_route(route: &str) -> &'static str {
    if route.starts_with("/media/music") {
        "music"
    } else if route.starts_with("/media/audiobook") {
        "audiobook chapter"
    } else if route.starts_with("/media/ebook") {
        "ebook"
    } else if route.starts_with("/media/songart") {
        "song art"
    } else if route.starts_with("/media/cover") {
        if route.contains("kind=ebook") {
            "ebook cover"
        } else {
            "audiobook cover"
        }
    } else {
        "file"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_bytes() {
        assert_eq!(bytes(512), "512 B");
        assert_eq!(bytes(1536), "1.5 KiB");
        assert_eq!(bytes(3 * 1024 * 1024), "3.0 MiB");
    }

    #[test]
    fn classifies_routes() {
        assert_eq!(kind_from_route("/media/music?id=1"), "music");
        assert_eq!(kind_from_route("/media/ebook?path=x"), "ebook");
        assert_eq!(
            kind_from_route("/media/audiobook?path=x"),
            "audiobook chapter"
        );
        assert_eq!(
            kind_from_route("/media/cover?kind=ebook&key=x"),
            "ebook cover"
        );
    }

    #[test]
    fn classifies_mime() {
        assert_eq!(kind_from_mime("audio/mpeg"), "audio");
        assert_eq!(kind_from_mime("application/epub+zip"), "ebook");
        assert_eq!(kind_from_mime("image/jpeg"), "image");
        assert_eq!(kind_from_mime("application/octet-stream"), "file");
    }
}
