//! Random-reading feed: parse caching, three-book sliding window and random
//! chapter/block/char positions.
//!
//! The phone is the only renderer.  This service just keeps three parsed books
//! ready at all times and hands out one chapter per book, so a vertical swipe
//! can switch books without waiting for an EPUB parse.

use crate::ebook_parse::{parse_epub, FeedChapter, ParsedBook};
use anyhow::{anyhow, Result};
use rand::seq::SliceRandom;
use rand::Rng;
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const WINDOW_SIZE: usize = 3;
const MEM_CACHE_BOOKS: usize = 6;
const MIN_CHAPTER_CHARS: usize = 80;
/// 客户端没有发 close 就消失（进程被杀/快速退出）时，会话最多留这么久。
const SESSION_TTL: Duration = Duration::from_secs(30 * 60);

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedResume {
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub chapter: usize,
    #[serde(default)]
    pub block: usize,
    #[serde(default)]
    pub char: usize,
}

#[derive(Debug, Clone)]
struct FeedItem {
    path: String,
    book: Arc<ParsedBook>,
    chapter: usize,
    block: usize,
    char: usize,
}

#[derive(Debug, Clone)]
struct FeedSession {
    pool: Vec<String>,
    queue: VecDeque<FeedItem>,
    last_used: Instant,
}

struct MemCache {
    items: HashMap<String, Arc<ParsedBook>>,
    order: VecDeque<String>,
}

impl MemCache {
    fn new() -> Self {
        MemCache {
            items: HashMap::new(),
            order: VecDeque::new(),
        }
    }

    fn get(&mut self, key: &str) -> Option<Arc<ParsedBook>> {
        let book = self.items.get(key).cloned()?;
        self.order.retain(|k| k != key);
        self.order.push_back(key.to_string());
        Some(book)
    }

    fn put(&mut self, key: String, book: Arc<ParsedBook>) {
        self.items.insert(key.clone(), book);
        self.order.retain(|k| *k != key);
        self.order.push_back(key);
        while self.order.len() > MEM_CACHE_BOOKS {
            if let Some(old) = self.order.pop_front() {
                self.items.remove(&old);
            }
        }
    }
}

pub struct FeedService {
    cache_dir: PathBuf,
    sessions: Mutex<HashMap<String, FeedSession>>,
    mem: Mutex<MemCache>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedBookView {
    pub path: String,
    pub title: String,
    pub author: String,
    pub chapter_count: usize,
    pub chapter_titles: Vec<String>,
    pub chapter_index: usize,
    pub chapter_title: String,
    pub block_index: usize,
    pub char_offset: usize,
    pub chapter: FeedChapter,
    /// Reserved for a future image-enabled protocol; always empty in v1.
    pub images: Vec<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedSnapshot {
    pub session_id: String,
    pub index: usize,
    pub books: Vec<FeedBookView>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FeedChapterView {
    pub path: String,
    pub chapter_index: usize,
    pub chapter: FeedChapter,
}

impl FeedService {
    pub fn new(cache_dir: PathBuf) -> Self {
        let dir = cache_dir.join("ebook_feed");
        let _ = std::fs::create_dir_all(&dir);
        FeedService {
            cache_dir: dir,
            sessions: Mutex::new(HashMap::new()),
            mem: Mutex::new(MemCache::new()),
        }
    }

    fn cache_key(path: &str) -> String {
        let (size, mtime) = std::fs::metadata(path)
            .map(|m| {
                let mtime = m
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                (m.len(), mtime)
            })
            .unwrap_or((0, 0));
        let mut hasher = Sha1::new();
        hasher.update(path.as_bytes());
        hasher.update(b"|");
        hasher.update(size.to_string().as_bytes());
        hasher.update(b"|");
        hasher.update(mtime.to_string().as_bytes());
        hasher
            .finalize()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    }

    fn cached_path(cache_dir: &Path, key: &str) -> PathBuf {
        cache_dir.join(format!("{key}.json"))
    }

    fn load_book(&self, path: &str) -> Result<Arc<ParsedBook>> {
        let key = Self::cache_key(path);
        if let Ok(mut mem) = self.mem.lock() {
            if let Some(book) = mem.get(&key) {
                return Ok(book);
            }
        }
        let file = Self::cached_path(&self.cache_dir, &key);
        if let Ok(text) = std::fs::read_to_string(&file) {
            if let Ok(book) = serde_json::from_str::<ParsedBook>(&text) {
                let book = Arc::new(book);
                if let Ok(mut mem) = self.mem.lock() {
                    mem.put(key, book.clone());
                }
                return Ok(book);
            }
        }
        let parsed = parse_epub(Path::new(path))?;
        if let Ok(text) = serde_json::to_string(&parsed) {
            let _ = std::fs::write(&file, text);
        }
        let book = Arc::new(parsed);
        if let Ok(mut mem) = self.mem.lock() {
            mem.put(key, book.clone());
        }
        Ok(book)
    }

    fn random_position(book: &ParsedBook) -> (usize, usize, usize) {
        let mut rng = rand::thread_rng();
        let candidates: Vec<usize> = book
            .chapters
            .iter()
            .enumerate()
            .filter(|(_, c)| {
                c.blocks
                    .iter()
                    .map(|b| b.text.chars().count())
                    .sum::<usize>()
                    >= MIN_CHAPTER_CHARS
            })
            .map(|(i, _)| i)
            .collect();
        let chapter = if candidates.is_empty() {
            rng.gen_range(0..book.chapters.len())
        } else {
            candidates[rng.gen_range(0..candidates.len())]
        };
        let blocks: Vec<usize> = book.chapters[chapter]
            .blocks
            .iter()
            .enumerate()
            .filter(|(_, b)| !b.text.trim().is_empty())
            .map(|(i, _)| i)
            .collect();
        if blocks.is_empty() {
            return (chapter, 0, 0);
        }
        let block = blocks[rng.gen_range(0..blocks.len())];
        let len = book.chapters[chapter].blocks[block].text.chars().count();
        let char = if len == 0 { 0 } else { rng.gen_range(0..len) };
        (chapter, block, char)
    }

    fn make_item(&self, path: String) -> Option<FeedItem> {
        let book = self.load_book(&path).ok()?;
        let (chapter, block, char) = Self::random_position(&book);
        Some(FeedItem {
            path,
            book,
            chapter,
            block,
            char,
        })
    }

    fn make_resume_item(&self, resume: &FeedResume) -> Option<FeedItem> {
        let book = self.load_book(&resume.path).ok()?;
        let chapter = resume.chapter.min(book.chapters.len().saturating_sub(1));
        let block = resume
            .block
            .min(book.chapters[chapter].blocks.len().saturating_sub(1));
        let char = resume
            .char
            .min(book.chapters[chapter].blocks.get(block).map(|b| b.text.len()).unwrap_or(0));
        Some(FeedItem {
            path: resume.path.clone(),
            book,
            chapter,
            block,
            char,
        })
    }

    fn snapshot(&self, session_id: &str, session: &FeedSession) -> FeedSnapshot {
        let index = if session.queue.len() >= 2 { 1 } else { 0 };
        let books = session
            .queue
            .iter()
            .map(|item| {
                let chapter = item
                    .book
                    .chapters
                    .get(item.chapter)
                    .cloned()
                    .unwrap_or(FeedChapter {
                        title: String::new(),
                        blocks: Vec::new(),
                    });
                FeedBookView {
                    path: item.path.clone(),
                    title: item.book.title.clone(),
                    author: item.book.author.clone(),
                    chapter_count: item.book.chapters.len(),
                    chapter_titles: item
                        .book
                        .chapters
                        .iter()
                        .map(|c| c.title.clone())
                        .collect(),
                    chapter_index: item.chapter,
                    chapter_title: chapter.title.clone(),
                    block_index: item.block,
                    char_offset: item.char,
                    chapter,
                    images: Vec::new(),
                }
            })
            .collect();
        FeedSnapshot {
            session_id: session_id.to_string(),
            index,
            books,
        }
    }

    /// Starts a feed session. `pool` must already be validated by the caller.
    pub fn start(
        &self,
        _root_key: &str,
        pool: &[String],
        resume: Option<FeedResume>,
    ) -> Result<FeedSnapshot> {
        let mut pool = pool.to_vec();
        pool.sort();
        pool.dedup();
        if pool.is_empty() {
            return Err(anyhow!("random pool is empty"));
        }
        let mut rng = rand::thread_rng();
        pool.shuffle(&mut rng);

        let mut parsed: Vec<FeedItem> = Vec::new();
        let resume_item = resume
            .as_ref()
            .filter(|r| pool.iter().any(|p| p == &r.path))
            .and_then(|r| self.make_resume_item(r));
        let resume_path = resume_item.as_ref().map(|item| item.path.clone());
        if let Some(item) = resume_item {
            parsed.push(item);
        }
        for path in &pool {
            if parsed.len() >= WINDOW_SIZE {
                break;
            }
            if resume_path.as_ref().is_some_and(|p| p == path)
                || parsed.iter().any(|i| &i.path == path)
            {
                continue;
            }
            if let Some(item) = self.make_item(path.clone()) {
                parsed.push(item);
            }
        }
        if parsed.is_empty() {
            return Err(anyhow!("no parsable ebook in random pool"));
        }

        // Current book is always the middle slot when possible: the first
        // parsed book is "current", the remaining ones fill previous/next.
        let current = parsed.remove(0);
        let mut queue = VecDeque::new();
        if let Some(prev) = parsed.first() {
            queue.push_back(prev.clone());
        }
        queue.push_back(current);
        if let Some(next) = parsed.get(1) {
            queue.push_back(next.clone());
        }

        let id = uuid::Uuid::new_v4().to_string();
        let session = FeedSession {
            pool,
            queue,
            last_used: Instant::now(),
        };
        let snapshot = self.snapshot(&id, &session);
        let mut sessions = self
            .sessions
            .lock()
            .map_err(|_| anyhow!("feed session lock poisoned"))?;
        // 顺手清掉一段时间没动过的会话（客户端异常退出不会发 close）。
        let now = Instant::now();
        sessions.retain(|_, s| now.duration_since(s.last_used) < SESSION_TTL);
        sessions.insert(id, session);
        Ok(snapshot)
    }

    fn view(&self, session_id: &str) -> Result<FeedSnapshot> {
        let mut sessions = self
            .sessions
            .lock()
            .map_err(|_| anyhow!("feed session lock poisoned"))?;
        let session = sessions
            .get_mut(session_id)
            .ok_or_else(|| anyhow!("feed session expired"))?;
        session.last_used = Instant::now();
        Ok(self.snapshot(session_id, session))
    }

    /// Rotates the three-book window. `direction > 0` moves to the next book.
    pub fn turn(&self, session_id: &str, direction: i32) -> Result<FeedSnapshot> {
        if direction == 0 {
            return self.view(session_id);
        }
        let (pool, queue_paths) = {
            let sessions = self
                .sessions
                .lock()
                .map_err(|_| anyhow!("feed session lock poisoned"))?;
            let session = sessions
                .get(session_id)
                .ok_or_else(|| anyhow!("feed session expired"))?;
            (
                session.pool.clone(),
                session
                    .queue
                    .iter()
                    .map(|i| i.path.clone())
                    .collect::<Vec<_>>(),
            )
        };
        let mut rng = rand::thread_rng();
        let mut candidates = pool;
        candidates.shuffle(&mut rng);
        let mut fresh: Option<FeedItem> = None;
        for path in candidates {
            if queue_paths.iter().any(|p| p == &path) {
                continue;
            }
            if let Some(item) = self.make_item(path) {
                fresh = Some(item);
                break;
            }
        }

        let mut sessions = self
            .sessions
            .lock()
            .map_err(|_| anyhow!("feed session lock poisoned"))?;
        let session = sessions
            .get_mut(session_id)
            .ok_or_else(|| anyhow!("feed session expired"))?;
        session.last_used = Instant::now();
        if direction > 0 {
            if session.queue.len() > 1 {
                session.queue.pop_front();
            }
            if let Some(item) = fresh {
                session.queue.push_back(item);
            }
        } else {
            if session.queue.len() > 1 {
                session.queue.pop_back();
            }
            if let Some(item) = fresh {
                session.queue.push_front(item);
            }
        }
        Ok(self.snapshot(session_id, session))
    }

    /// Fetches one chapter for a book that belongs to this session. Used when
    /// the client goes back to a book whose chapter was evicted from history.
    pub fn chapter(
        &self,
        session_id: &str,
        path: &str,
        chapter: usize,
    ) -> Result<FeedChapterView> {
        let book = {
            let mut sessions = self
                .sessions
                .lock()
                .map_err(|_| anyhow!("feed session lock poisoned"))?;
            let session = sessions
                .get_mut(session_id)
                .ok_or_else(|| anyhow!("feed session expired"))?;
            session.last_used = Instant::now();
            if !session.pool.iter().any(|p| p == path)
                && !session.queue.iter().any(|i| i.path == path)
            {
                return Err(anyhow!("book does not belong to this feed session"));
            }
            match session.queue.iter().find(|i| i.path == path) {
                Some(item) => item.book.clone(),
                None => self.load_book(path)?,
            }
        };
        let chapter_view = book
            .chapters
            .get(chapter)
            .cloned()
            .ok_or_else(|| anyhow!("chapter out of range"))?;
        Ok(FeedChapterView {
            path: path.to_string(),
            chapter_index: chapter,
            chapter: chapter_view,
        })
    }

    pub fn close(&self, session_id: &str) {
        if let Ok(mut sessions) = self.sessions.lock() {
            sessions.remove(session_id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn book(chapters: usize) -> ParsedBook {
        ParsedBook {
            title: "t".into(),
            author: "a".into(),
            chapters: (0..chapters)
                .map(|i| FeedChapter {
                    title: format!("c{i}"),
                    blocks: vec![crate::ebook_parse::FeedBlock {
                        text: "x".repeat(200),
                        level: 0,
                    }],
                })
                .collect(),
        }
    }

    #[test]
    fn random_position_stays_inside_book() {
        let b = book(3);
        for _ in 0..50 {
            let (c, blk, ch) = FeedService::random_position(&b);
            assert!(c < 3);
            assert!(blk < b.chapters[c].blocks.len());
            assert!(ch <= b.chapters[c].blocks[blk].text.len());
        }
    }

    #[test]
    fn cache_key_changes_with_metadata() {
        let a = FeedService::cache_key("/definitely/not/here.epub");
        assert_eq!(a.len(), 40);
    }
}
