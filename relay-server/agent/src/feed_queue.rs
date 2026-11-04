//! Per-client prefetched random-reading books.
//!
//! The host parses a batch of books and pushes them here; the agent serves
//! `feed/batch` from the queue. When the queue drops below half of the target
//! the agent asks the host for another batch, so a page turn never waits for an
//! EPUB parse.

use serde_json::Value;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};

/// How many parsed books the agent keeps per client.
pub const FEED_QUEUE_TARGET: usize = 50;
/// Refill when the queue falls below this many books.
pub const FEED_QUEUE_LOW: usize = FEED_QUEUE_TARGET / 2;

struct ClientFeed {
    root_key: String,
    session_id: String,
    books: VecDeque<Value>,
    refilling: bool,
}

pub struct FeedQueues {
    inner: Mutex<HashMap<[u8; 16], ClientFeed>>,
}

impl FeedQueues {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(HashMap::new()),
        })
    }

    /// Starts a new random-reading session for a client, dropping old books.
    pub fn reset(&self, client_id: [u8; 16], root_key: &str) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.insert(
                client_id,
                ClientFeed {
                    root_key: root_key.to_string(),
                    session_id: String::new(),
                    books: VecDeque::new(),
                    refilling: false,
                },
            );
        }
    }

    pub fn root_key(&self, client_id: [u8; 16]) -> Option<String> {
        self.inner
            .lock()
            .ok()?
            .get(&client_id)
            .map(|feed| feed.root_key.clone())
    }

    /// Pops up to `count` books. Returns `(session_id, books, needs_refill)`.
    pub fn take(&self, client_id: [u8; 16], count: usize) -> (String, Vec<Value>, bool) {
        let Ok(mut inner) = self.inner.lock() else {
            return (String::new(), Vec::new(), false);
        };
        let Some(feed) = inner.get_mut(&client_id) else {
            return (String::new(), Vec::new(), false);
        };
        let mut books = Vec::new();
        while books.len() < count {
            match feed.books.pop_front() {
                Some(book) => books.push(book),
                None => break,
            }
        }
        let needs_refill = feed.books.len() < FEED_QUEUE_LOW && !feed.refilling;
        (feed.session_id.clone(), books, needs_refill)
    }

    /// Marks a refill as in flight. Returns false when one is already running.
    pub fn begin_refill(&self, client_id: [u8; 16]) -> bool {
        let Ok(mut inner) = self.inner.lock() else {
            return false;
        };
        let Some(feed) = inner.get_mut(&client_id) else {
            return false;
        };
        if feed.refilling {
            return false;
        }
        feed.refilling = true;
        true
    }

    /// Appends a freshly parsed batch, de-duplicating by book path.
    pub fn push(&self, client_id: [u8; 16], session_id: &str, books: Vec<Value>) {
        let Ok(mut inner) = self.inner.lock() else {
            return;
        };
        let Some(feed) = inner.get_mut(&client_id) else {
            return;
        };
        if !session_id.is_empty() {
            feed.session_id = session_id.to_string();
        }
        let mut known: HashSet<String> = feed
            .books
            .iter()
            .filter_map(|book| book.get("path").and_then(|path| path.as_str()).map(str::to_string))
            .collect();
        for book in books {
            let path = book
                .get("path")
                .and_then(|path| path.as_str())
                .unwrap_or_default()
                .to_string();
            if path.is_empty() || !known.insert(path) {
                continue;
            }
            if feed.books.len() >= FEED_QUEUE_TARGET {
                break;
            }
            feed.books.push_back(book);
        }
        feed.refilling = false;
    }

    pub fn clear_refilling(&self, client_id: [u8; 16]) {
        if let Ok(mut inner) = self.inner.lock() {
            if let Some(feed) = inner.get_mut(&client_id) {
                feed.refilling = false;
            }
        }
    }

    pub fn remove(&self, client_id: [u8; 16]) {
        if let Ok(mut inner) = self.inner.lock() {
            inner.remove(&client_id);
        }
    }
}
