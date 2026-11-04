//! Random-reading feed: parse caching, three-book sliding window and random
//! chapter/block/char positions.
//!
//! The phone is the only renderer.  This service just keeps three parsed books
//! ready at all times and hands out one chapter per book, so a vertical swipe
//! can switch books without waiting for an EPUB parse.

use crate::ebook_parse::{parse_epub, FeedBlock, FeedChapter, ParsedBook};
use crate::feed_rules::{self, RuleEngine, DEFAULT_RULES};
use anyhow::{anyhow, Result};
use rand::seq::SliceRandom;
use rand::Rng;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha1::{Digest, Sha1};
use std::collections::{HashMap, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const WINDOW_SIZE: usize = 3;
const MEM_CACHE_BOOKS: usize = 6;
const MIN_CHAPTER_CHARS: usize = 80;
/// 每本书最多随机几次；全部被规则拒绝后交给调用方换一本书。
const MAX_RANDOM_ATTEMPTS: usize = 5;
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
    rules_path: PathBuf,
    rules: Mutex<String>,
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
    pub fn new(cache_dir: PathBuf, data_dir: &Path) -> Self {
        let dir = cache_dir.join("ebook_feed");
        let _ = std::fs::create_dir_all(&dir);
        let rules_path = data_dir.join("feed_rules.js");
        let saved = std::fs::read_to_string(&rules_path)
            .ok()
            .filter(|script| feed_rules::validate(script).is_ok());
        let script = match saved {
            Some(script) => script,
            None => {
                let _ = std::fs::write(&rules_path, DEFAULT_RULES);
                DEFAULT_RULES.to_string()
            }
        };
        FeedService {
            cache_dir: dir,
            rules_path,
            rules: Mutex::new(script),
            sessions: Mutex::new(HashMap::new()),
            mem: Mutex::new(MemCache::new()),
        }
    }

    pub fn rules(&self) -> String {
        self.rules
            .lock()
            .map(|script| script.clone())
            .unwrap_or_else(|_| DEFAULT_RULES.to_string())
    }

    /// Persists a user script after checking that it compiles and defines
    /// `accept(ctx)`.  A broken script never replaces the last good one.
    pub fn set_rules(&self, script: &str) -> Result<()> {
        feed_rules::validate(script)?;
        std::fs::write(&self.rules_path, script)?;
        if let Ok(mut current) = self.rules.lock() {
            *current = script.to_string();
        }
        Ok(())
    }

    pub fn reset_rules(&self) -> Result<()> {
        self.set_rules(DEFAULT_RULES)
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
        // 随机位置只落在正文段落上；标题块不是可读的起始位置。
        let mut blocks: Vec<usize> = book.chapters[chapter]
            .blocks
            .iter()
            .enumerate()
            .filter(|(_, b)| b.level == 0 && !b.text.trim().is_empty())
            .map(|(i, _)| i)
            .collect();
        if blocks.is_empty() {
            blocks = book.chapters[chapter]
                .blocks
                .iter()
                .enumerate()
                .filter(|(_, b)| !b.text.trim().is_empty())
                .map(|(i, _)| i)
                .collect();
        }
        if blocks.is_empty() {
            return (chapter, 0, 0);
        }
        let block = blocks[rng.gen_range(0..blocks.len())];
        let len = book.chapters[chapter].blocks[block].text.chars().count();
        let char = if len == 0 { 0 } else { rng.gen_range(0..len) };
        (chapter, block, char)
    }

    /// The body paragraphs that belong to the heading section containing
    /// `block`.  A chapter can hold several headings; rules such as "at least
    /// ten paragraphs" must only look at the section the reader would see.
    fn heading_blocks(blocks: &[FeedBlock], block: usize) -> (Vec<(usize, &str)>, usize) {
        let block = block.min(blocks.len().saturating_sub(1));
        let mut heading_start = 0usize;
        let mut heading_level = 0u8;
        for index in (0..=block).rev() {
            if blocks[index].level > 0 {
                heading_start = index + 1;
                heading_level = blocks[index].level;
                break;
            }
        }
        let heading_end = blocks
            .iter()
            .enumerate()
            .skip(heading_start)
            .find(|(_, b)| b.level > 0 && (heading_level == 0 || b.level <= heading_level))
            .map(|(index, _)| index)
            .unwrap_or(blocks.len());
        let paragraphs: Vec<(usize, &str)> = blocks[heading_start..heading_end]
            .iter()
            .enumerate()
            .filter(|(_, b)| b.level == 0 && !b.text.trim().is_empty())
            .map(|(offset, b)| (heading_start + offset, b.text.as_str()))
            .collect();
        let picked = paragraphs
            .iter()
            .position(|(index, _)| *index == block)
            .unwrap_or(0);
        (paragraphs, picked)
    }

    fn rule_payload(book: &ParsedBook, chapter: usize, block: usize) -> serde_json::Value {
        let chapter_data = &book.chapters[chapter];
        let (heading_blocks, heading_index) = Self::heading_blocks(&chapter_data.blocks, block);
        json!({
            "book": {
                "title": book.title,
                "author": book.author,
            },
            "chapterIndex": chapter,
            "chapterTitle": chapter_data.title,
            "blockIndex": block,
            "blocks": chapter_data
                .blocks
                .iter()
                .enumerate()
                .map(|(index, b)| json!({
                    "index": index,
                    "text": b.text,
                    "level": b.level,
                }))
                .collect::<Vec<_>>(),
            "headingBlocks": heading_blocks
                .iter()
                .map(|(index, text)| json!({ "index": index, "text": text }))
                .collect::<Vec<_>>(),
            "headingIndex": heading_index,
            "headingParagraphs": heading_blocks.len(),
        })
    }

    fn make_item(&self, path: String) -> Option<FeedItem> {
        let book = self.load_book(&path).ok()?;
        let script = self.rules();
        let mut engine = RuleEngine::new(&script)
            .or_else(|_| RuleEngine::new(DEFAULT_RULES))
            .ok()?;
        // 同一本书最多随机 5 次；五次都被规则拒绝就换一本书。
        for _ in 0..MAX_RANDOM_ATTEMPTS {
            let (chapter, block, char) = Self::random_position(&book);
            let payload = Self::rule_payload(&book, chapter, block);
            if engine.accepts(&payload).unwrap_or(false) {
                return Some(FeedItem {
                    path,
                    book,
                    chapter,
                    block,
                    char,
                });
            }
        }
        None
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
        let mut fresh: Option<FeedItem> = None;
        if !pool.is_empty() {
            // Sample a handful of random slots instead of cloning and shuffling
            // the whole pool. With a 100k-book library the old clone allocated a
            // fresh Vec<String> on every single page turn.
            let mut tried = Vec::new();
            let attempts = pool.len().min(64);
            for _ in 0..attempts {
                let index = rng.gen_range(0..pool.len());
                if tried.contains(&index) {
                    continue;
                }
                tried.push(index);
                let path = &pool[index];
                if queue_paths.iter().any(|p| p == path) {
                    continue;
                }
                if let Some(item) = self.make_item(path.clone()) {
                    fresh = Some(item);
                    break;
                }
            }
            // Small pools (or an unlucky sample) get a linear fallback so the
            // window still advances whenever any parsable book is left.
            if fresh.is_none() && attempts < pool.len() {
                for path in &pool {
                    if queue_paths.iter().any(|p| p == path) {
                        continue;
                    }
                    if let Some(item) = self.make_item(path.clone()) {
                        fresh = Some(item);
                        break;
                    }
                }
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

    #[test]
    fn heading_blocks_only_cover_current_section() {
        let blocks = vec![
            FeedBlock {
                text: "第一章".into(),
                level: 1,
            },
            FeedBlock {
                text: "一".into(),
                level: 0,
            },
            FeedBlock {
                text: "二".into(),
                level: 0,
            },
            FeedBlock {
                text: "第二节".into(),
                level: 2,
            },
            FeedBlock {
                text: "三".into(),
                level: 0,
            },
        ];
        // The h2 subsection still belongs to the h1 section.
        let (paragraphs, picked) = FeedService::heading_blocks(&blocks, 2);
        assert_eq!(paragraphs.len(), 3);
        assert_eq!(picked, 1);
        assert_eq!(paragraphs[1].1, "二");

        let (paragraphs, picked) = FeedService::heading_blocks(&blocks, 4);
        assert_eq!(paragraphs.len(), 1);
        assert_eq!(picked, 0);
    }
}
