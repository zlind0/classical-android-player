//! Maps opaque `file_id`s back to the host's own HTTP routes. Built from the
//! same `/library/all` snapshot the clients see, so ids always agree.

use anyhow::Result;
use pod_relay_protocol::file_id::{encode_query, file_id, KIND_AUDIOBOOK, KIND_COVER, KIND_EBOOK, KIND_MUSIC, KIND_SONGART};
use pod_relay_protocol::FileId;
use serde_json::Value;
use std::collections::HashMap;

#[derive(Default, Clone)]
pub struct Index {
    routes: HashMap<FileId, String>,
}

impl Index {
    pub fn route(&self, id: &FileId) -> Option<&str> {
        self.routes.get(id).map(|s| s.as_str())
    }

    pub fn len(&self) -> usize {
        self.routes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.routes.is_empty()
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
                let encoded = encode_query(id);
                index.routes.insert(
                    file_id(KIND_MUSIC, id),
                    format!("/media/music?id={encoded}"),
                );
                index.routes.insert(
                    file_id(KIND_SONGART, id),
                    format!("/media/songart?id={encoded}"),
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
                let encoded = encode_query(path);
                index.routes.insert(
                    file_id(KIND_EBOOK, path),
                    format!("/media/ebook?path={encoded}"),
                );
                index.routes.insert(
                    file_id(KIND_COVER, &format!("ebook\x1f{path}")),
                    format!("/media/cover?kind=ebook&key={encoded}"),
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
                index.routes.insert(
                    file_id(KIND_COVER, &format!("audiobook\x1f{book_id}")),
                    format!(
                        "/media/cover?kind=audiobook&key={}",
                        encode_query(book_id)
                    ),
                );
                if let Some(chapters) = book.get("chapters").and_then(|v| v.as_array()) {
                    for chapter in chapters {
                        let Some(path) = chapter.get("path").and_then(|v| v.as_str()) else {
                            continue;
                        };
                        if path.is_empty() {
                            continue;
                        }
                        index.routes.insert(
                            file_id(KIND_AUDIOBOOK, path),
                            format!("/media/audiobook?path={}", encode_query(path)),
                        );
                    }
                }
            }
        }

        Ok(index)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_routes() {
        let json = br#"{
            "music":[{"id":"7"}],
            "ebooks":[{"path":"/books/a b.epub"}],
            "audiobooks":[{"id":"/ab/one","chapters":[{"path":"/ab/one/01.mp3"}]}]
        }"#;
        let index = Index::build(json).unwrap();
        assert_eq!(
            index.route(&file_id(KIND_MUSIC, "7")),
            Some("/media/music?id=7")
        );
        assert_eq!(
            index.route(&file_id(KIND_EBOOK, "/books/a b.epub")),
            Some("/media/ebook?path=%2Fbooks%2Fa+b.epub")
        );
        assert!(index
            .route(&file_id(KIND_COVER, "audiobook\x1f/ab/one"))
            .is_some());
        assert_eq!(
            index.route(&file_id(KIND_AUDIOBOOK, "/ab/one/01.mp3")),
            Some("/media/audiobook?path=%2Fab%2Fone%2F01.mp3")
        );
    }
}
