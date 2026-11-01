//! Deterministic, opaque media identifiers shared by the NAS and the clients.

use crate::msg::FileId;
use sha2::{Digest, Sha256};

pub const KIND_MUSIC: &str = "music";
pub const KIND_AUDIOBOOK: &str = "audiobook";
pub const KIND_EBOOK: &str = "ebook";
pub const KIND_SONGART: &str = "songart";
pub const KIND_COVER: &str = "cover";
pub const KIND_DEFAULT_ART: &str = "defaultart";

pub fn file_id(kind: &str, key: &str) -> FileId {
    let mut hasher = Sha256::new();
    hasher.update(kind.as_bytes());
    hasher.update([0u8]);
    hasher.update(key.as_bytes());
    let digest = hasher.finalize();
    let mut out = [0u8; 16];
    out.copy_from_slice(&digest[..16]);
    out
}

pub fn hex(id: &FileId) -> String {
    let mut out = String::with_capacity(32);
    for b in id {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

/// URL-encodes a query value the same way the Android client does
/// (`URLEncoder` semantics: space becomes `+`, everything outside the
/// unreserved set is percent-encoded).
pub fn encode_query(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stable_ids() {
        assert_eq!(file_id("music", "1"), file_id("music", "1"));
        assert_ne!(file_id("music", "1"), file_id("ebook", "1"));
        assert_ne!(file_id("music", "1"), file_id("songart", "1"));
        assert_eq!(hex(&[0u8; 16]).len(), 32);
    }

    #[test]
    fn encoding() {
        assert_eq!(encode_query("/a b/三体.epub"), "%2Fa+b%2F%E4%B8%89%E4%BD%93.epub");
    }
}
