//! Maps opaque `file_id`s back to the host's own HTTP routes. Built from the
//! same `/library/all` snapshot the clients see, so ids always agree.

use anyhow::Result;
use pod_relay_protocol::file_id::{
    encode_query, file_id, KIND_AUDIOBOOK, KIND_COVER, KIND_EBOOK, KIND_MUSIC, KIND_SONGART,
};
use pod_relay_protocol::FileId;
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct IndexEntry {
    pub route: String,
    pub root_id: i64,
}

#[derive(Default, Clone)]
pub struct Index {
    entries: HashMap<FileId, IndexEntry>,
}

impl Index {
    pub fn route(&self, id: &FileId) -> Option<&str> {
        self.entries.get(id).map(|entry| entry.route.as_str())
    }

    pub fn root_id(&self, id: &FileId) -> Option<i64> {
        self.entries.get(id).map(|entry| entry.root_id)
    }

    /// Compact `(file_id, root_id)` list pushed to the relay so it can enforce
    /// folder access without ever seeing paths.
    pub fn root_entries(&self) -> Vec<(FileId, i64)> {
        self.entries
            .iter()
            .map(|(id, entry)| (*id, entry.root_id))
            .collect()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    fn insert(&mut self, id: FileId, route: String, root_id: i64) {
        if root_id <= 0 {
            return;
        }
        self.entries.insert(
            id,
            IndexEntry {
                route,
                root_id,
            },
        );
    }

    pub fn build(json: &[u8]) -> Result<Index> {
        let value: Value = serde_json::from_slice(json)?;
        let mut index = Index::default();

        if let Some(music) = value.get("music").and_then(|v| v.as_array()) {
            for track in music {
                let Some(id) = track.get("id").and_then(|v| v.as_str()) else {
                    continue;
                };
                if id.is_empty() {
                    continue;
                }
                let Some(root_id) = root_key(track) else {
                    continue;
                };
                let encoded = encode_query(id);
                index.insert(
                    file_id(KIND_MUSIC, id),
                    format!("/media/music?id={encoded}"),
                    root_id,
                );
                index.insert(
                    file_id(KIND_SONGART, id),
                    format!("/media/songart?id={encoded}"),
                    root_id,
                );
            }
        }

        if let Some(books) = value.get("ebooks").and_then(|v| v.as_array()) {
            for book in books {
                let Some(path) = book.get("path").and_then(|v| v.as_str()) else {
                    continue;
                };
                if path.is_empty() {
                    continue;
                }
                let Some(root_id) = root_key(book) else {
                    continue;
                };
                let encoded = encode_query(path);
                index.insert(
                    file_id(KIND_EBOOK, path),
                    format!("/media/ebook?path={encoded}"),
                    root_id,
                );
                index.insert(
                    file_id(KIND_COVER, &format!("ebook\x1f{path}")),
                    format!("/media/cover?kind=ebook&key={encoded}"),
                    root_id,
                );
            }
        }

        if let Some(books) = value.get("audiobooks").and_then(|v| v.as_array()) {
            for book in books {
                let Some(book_id) = book.get("id").and_then(|v| v.as_str()) else {
                    continue;
                };
                if book_id.is_empty() {
                    continue;
                }
                let Some(root_id) = root_key(book) else {
                    continue;
                };
                index.insert(
                    file_id(KIND_COVER, &format!("audiobook\x1f{book_id}")),
                    format!(
                        "/media/cover?kind=audiobook&key={}",
                        encode_query(book_id)
                    ),
                    root_id,
                );
                if let Some(chapters) = book.get("chapters").and_then(|v| v.as_array()) {
                    for chapter in chapters {
                        let Some(path) = chapter.get("path").and_then(|v| v.as_str()) else {
                            continue;
                        };
                        if path.is_empty() {
                            continue;
                        }
                        index.insert(
                            file_id(KIND_AUDIOBOOK, path),
                            format!("/media/audiobook?path={}", encode_query(path)),
                            root_id,
                        );
                    }
                }
            }
        }

        Ok(index)
    }
}

fn root_key(value: &Value) -> Option<i64> {
    let text = value.get("rootKey")?.as_str()?;
    let parsed = text.parse::<i64>().ok()?;
    if parsed > 0 {
        Some(parsed)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_routes_and_roots() {
        let json = br#"{
            "music":[{"id":"7","rootKey":"2"}],
            "ebooks":[{"path":"/books/a b.epub","rootKey":"4"}],
            "audiobooks":[{"id":"/ab/one","rootKey":"6","chapters":[{"path":"/ab/one/01.mp3"}]}]
        }"#;
        let index = Index::build(json).unwrap();
        assert_eq!(
            index.route(&file_id(KIND_MUSIC, "7")),
            Some("/media/music?id=7")
        );
        assert_eq!(index.root_id(&file_id(KIND_MUSIC, "7")), Some(2));
        assert_eq!(
            index.route(&file_id(KIND_EBOOK, "/books/a b.epub")),
            Some("/media/ebook?path=%2Fbooks%2Fa+b.epub")
        );
        assert_eq!(
            index.root_id(&file_id(KIND_EBOOK, "/books/a b.epub")),
            Some(4)
        );
        let cover = file_id(KIND_COVER, "audiobook\x1f/ab/one");
        assert!(index.route(&cover).is_some());
        assert_eq!(index.root_id(&cover), Some(6));
        assert_eq!(
            index.route(&file_id(KIND_AUDIOBOOK, "/ab/one/01.mp3")),
            Some("/media/audiobook?path=%2Fab%2Fone%2F01.mp3")
        );
        assert_eq!(index.root_entries().len(), 6);
    }
}
