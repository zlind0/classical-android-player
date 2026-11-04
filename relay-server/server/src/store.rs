//! Encrypted fragment store. One aligned container file per media file plus a
//! bitmap of present fragments and a JSON metadata sidecar.

use crate::log::{bytes, file_short, kind_from_mime};
use anyhow::{Context, Result};
use pod_relay_protocol::crypto::{FRAGMENT_BYTES, TAG_BYTES};
use pod_relay_protocol::file_id::hex;
use pod_relay_protocol::FileId;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

pub const SLOT: u64 = FRAGMENT_BYTES as u64 + TAG_BYTES as u64;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatInfo {
    pub total: u64,
    pub frag: u32,
    pub count: u32,
    pub salt: [u8; 8],
    pub mime: String,
    pub key_id: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct MetaFile {
    total: u64,
    frag: u32,
    count: u32,
    salt: [u8; 8],
    mime: String,
    key_id: u64,
    updated_at: i64,
}

struct Entry {
    stat: StatInfo,
    bits: Vec<u8>,
    path: PathBuf,
    updated_at: i64,
    last_access: i64,
    pinned_until: i64,
}

#[derive(Default)]
struct Inner {
    files: HashMap<FileId, Entry>,
}

pub struct FragmentStore {
    root: PathBuf,
    capacity: u64,
    used: AtomicU64,
    inner: Mutex<Inner>,
    stat_cache: Mutex<HashMap<FileId, StatInfo>>,
    pub hit: AtomicU64,
    pub miss: AtomicU64,
    pub evicted: AtomicU64,
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn dir_for(root: &Path, id: &FileId) -> PathBuf {
    root.join("frag").join(&hex(id)[..2])
}

fn paths_for(root: &Path, id: &FileId) -> (PathBuf, PathBuf, PathBuf) {
    let dir = dir_for(root, id);
    let name = hex(id);
    (
        dir.join(format!("{name}.dat")),
        dir.join(format!("{name}.bits")),
        dir.join(format!("{name}.meta")),
    )
}

fn cipher_len(stat: &StatInfo, index: u32) -> u64 {
    let start = index as u64 * FRAGMENT_BYTES as u64;
    let plain = (stat.total - start).min(FRAGMENT_BYTES as u64);
    plain + TAG_BYTES as u64
}

impl FragmentStore {
    pub fn open(root: &Path, capacity: u64) -> Result<Self> {
        fs::create_dir_all(root.join("frag"))?;
        let store = FragmentStore {
            root: root.to_path_buf(),
            capacity,
            used: AtomicU64::new(0),
            inner: Mutex::new(Inner::default()),
            stat_cache: Mutex::new(HashMap::new()),
            hit: AtomicU64::new(0),
            miss: AtomicU64::new(0),
            evicted: AtomicU64::new(0),
        };
        let used = store.scan_used()?;
        store.used.store(used, Ordering::Relaxed);
        Ok(store)
    }

    fn scan_used(&self) -> Result<u64> {
        let mut total = 0u64;
        let frag = self.root.join("frag");
        for shard in fs::read_dir(&frag).context("walk fragment store")? {
            let shard = shard?;
            if !shard.file_type()?.is_dir() {
                continue;
            }
            for file in fs::read_dir(shard.path())? {
                let file = file?;
                if file.path().extension().and_then(|e| e.to_str()) == Some("dat") {
                    total += file.metadata().map(|m| m.len()).unwrap_or(0);
                }
            }
        }
        Ok(total)
    }

    /// Drops every cached fragment (used when the media key changes).
    pub fn clear_all(&self) {
        let before = self.used();
        let files = self.file_count();
        if let Ok(mut inner) = self.inner.lock() {
            inner.files.clear();
        }
        if let Ok(mut cache) = self.stat_cache.lock() {
            cache.clear();
        }
        let frag = self.root.join("frag");
        if let Ok(shards) = fs::read_dir(&frag) {
            for shard in shards.flatten() {
                if shard.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    let _ = fs::remove_dir_all(shard.path());
                }
            }
        }
        let _ = fs::create_dir_all(&frag);
        self.used.store(0, Ordering::Relaxed);
        eprintln!(
            "[pod-relay] cache purge complete: dropped {files} file(s), {} freed",
            bytes(before)
        );
    }

    /// Number of `.dat` containers currently on disk (also correct before the
    /// in-memory map has been populated at startup).
    pub fn file_count(&self) -> usize {
        if let Ok(inner) = self.inner.lock() {
            if !inner.files.is_empty() {
                return inner.files.len();
            }
        }
        let frag = self.root.join("frag");
        let mut count = 0;
        if let Ok(shards) = fs::read_dir(&frag) {
            for shard in shards.flatten() {
                if !shard.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    continue;
                }
                if let Ok(files) = fs::read_dir(shard.path()) {
                    count += files
                        .flatten()
                        .filter(|file| {
                            file.path().extension().and_then(|e| e.to_str()) == Some("dat")
                        })
                        .count();
                }
            }
        }
        count
    }

    pub fn capacity(&self) -> u64 {
        self.capacity
    }

    pub fn used(&self) -> u64 {
        self.used.load(Ordering::Relaxed)
    }

    pub fn cached_stat(&self, id: &FileId) -> Option<StatInfo> {
        self.stat_cache.lock().ok()?.get(id).cloned()
    }

    pub fn remember_stat(&self, id: FileId, stat: StatInfo) {
        if let Ok(mut map) = self.stat_cache.lock() {
            map.insert(id, stat);
        }
    }

    fn load_entry(&self, id: &FileId) -> Option<Entry> {
        let (dat, bits_path, meta_path) = paths_for(&self.root, id);
        if !dat.is_file() || !meta_path.is_file() {
            return None;
        }
        let meta: MetaFile = serde_json::from_slice(&fs::read(&meta_path).ok()?).ok()?;
        let mut bits = fs::read(&bits_path).unwrap_or_default();
        if bits.len() != meta.count as usize {
            bits.resize(meta.count as usize, 0);
        }
        Some(Entry {
            stat: StatInfo {
                total: meta.total,
                frag: meta.frag,
                count: meta.count,
                salt: meta.salt,
                mime: meta.mime,
                key_id: meta.key_id,
            },
            bits,
            path: dat,
            updated_at: meta.updated_at,
            last_access: now_secs(),
            pinned_until: 0,
        })
    }

    fn with_entry<R>(&self, id: &FileId, f: impl FnOnce(&mut Entry) -> R) -> Option<R> {
        let mut inner = self.inner.lock().ok()?;
        if !inner.files.contains_key(id) {
            let entry = self.load_entry(id)?;
            inner.files.insert(*id, entry);
        }
        inner.files.get_mut(id).map(f)
    }

    pub fn stat(&self, id: &FileId) -> Option<StatInfo> {
        let cached = self.with_entry(id, |entry| {
            entry.last_access = now_secs();
            entry.stat.clone()
        });
        cached.or_else(|| self.cached_stat(id))
    }

    pub fn has_fragment(&self, id: &FileId, index: u32) -> bool {
        self.with_entry(id, |entry| {
            entry
                .bits
                .get(index as usize)
                .map(|b| *b != 0)
                .unwrap_or(false)
        })
        .unwrap_or(false)
    }

    pub fn read_fragment(&self, id: &FileId, index: u32) -> Option<Vec<u8>> {
        let (path, len) = self.with_entry(id, |entry| {
            entry.last_access = now_secs();
            if entry.bits.get(index as usize).copied().unwrap_or(0) == 0 {
                return None;
            }
            Some((entry.path.clone(), cipher_len(&entry.stat, index)))
        })??;
        let mut file = File::open(path).ok()?;
        file.seek(SeekFrom::Start(index as u64 * SLOT)).ok()?;
        let mut buf = vec![0u8; len as usize];
        file.read_exact(&mut buf).ok()?;
        self.hit.fetch_add(1, Ordering::Relaxed);
        Some(buf)
    }

    fn evict_to_fit(&self, need: u64) -> Result<()> {
        let used = self.used();
        if used + need <= self.capacity {
            return Ok(());
        }
        let now = now_secs();
        let mut victims: Vec<(i64, FileId, String)> = Vec::new();
        {
            let inner = self.inner.lock().unwrap();
            for (id, entry) in inner.files.iter() {
                if entry.pinned_until > now {
                    continue;
                }
                victims.push((entry.last_access, *id, entry.stat.mime.clone()));
            }
        }
        victims.sort_by_key(|(access, _, _)| *access);
        let mut freed = 0u64;
        let mut removed = 0usize;
        eprintln!(
            "[pod-relay] cache eviction: need {} but only {} free, checking {} candidate(s)",
            bytes(need),
            bytes(self.capacity.saturating_sub(used)),
            victims.len()
        );
        for (last_access, id, mime) in victims {
            if used - freed + need <= self.capacity {
                break;
            }
            let size = {
                let inner = self.inner.lock().unwrap();
                inner
                    .files
                    .get(&id)
                    .map(|e| SLOT * e.stat.count as u64)
                    .unwrap_or(0)
            };
            self.remove(&id);
            freed += size;
            removed += 1;
            self.evicted.fetch_add(1, Ordering::Relaxed);
            eprintln!(
                "[pod-relay] cache evicted {} {} ({}), unused for {}s",
                kind_from_mime(&mime),
                file_short(&id),
                bytes(size),
                now - last_access
            );
        }
        let remaining = used.saturating_sub(freed);
        self.used.store(remaining, Ordering::Relaxed);
        if remaining + need > self.capacity {
            eprintln!(
                "[pod-relay] cache eviction failed: removed {removed} file(s), still {} over capacity",
                bytes(remaining + need - self.capacity)
            );
            anyhow::bail!("relay store is full");
        }
        if removed > 0 {
            eprintln!(
                "[pod-relay] cache eviction done: removed {removed} file(s), {} freed, {} used of {}",
                bytes(freed),
                bytes(remaining),
                bytes(self.capacity)
            );
        }
        Ok(())
    }

    /// Ensures the container exists. Repeated pushes for the same file keep the
    /// already cached fragments; only a different layout resets the container.
    pub fn begin(&self, id: FileId, stat: StatInfo) -> Result<()> {
        let size = SLOT * stat.count as u64;
        let mut inner = self.inner.lock().unwrap();
        if !inner.files.contains_key(&id) {
            if let Some(entry) = self.load_entry(&id) {
                inner.files.insert(id, entry);
            }
        }
        if let Some(entry) = inner.files.get_mut(&id) {
            if entry.stat.count == stat.count && entry.stat.total == stat.total {
                entry.stat.mime = stat.mime.clone();
                entry.stat.salt = stat.salt;
                entry.stat.key_id = stat.key_id;
                entry.last_access = now_secs();
                drop(inner);
                self.remember_stat(id, stat);
                return Ok(());
            }
            let old_size = SLOT * entry.stat.count as u64;
            eprintln!(
                "[pod-relay] cache reset for {}: layout changed from {} to {} fragments, dropping {}",
                file_short(&id),
                entry.stat.count,
                stat.count,
                bytes(old_size)
            );
            inner.files.remove(&id);
            self.used
                .store(self.used().saturating_sub(old_size), Ordering::Relaxed);
        }
        drop(inner);

        self.evict_to_fit(size)?;
        let (dat, bits_path, meta_path) = paths_for(&self.root, &id);
        if let Some(parent) = dat.parent() {
            fs::create_dir_all(parent)?;
        }
        let existed = dat.is_file();
        let file = File::create(&dat)?;
        file.set_len(size)?;
        drop(file);
        fs::write(&bits_path, vec![0u8; stat.count as usize])?;
        let meta = MetaFile {
            total: stat.total,
            frag: stat.frag,
            count: stat.count,
            salt: stat.salt,
            mime: stat.mime.clone(),
            key_id: stat.key_id,
            updated_at: now_secs(),
        };
        fs::write(&meta_path, serde_json::to_vec(&meta)?)?;
        let mut inner = self.inner.lock().unwrap();
        inner.files.insert(
            id,
            Entry {
                stat: stat.clone(),
                bits: vec![0u8; stat.count as usize],
                path: dat,
                updated_at: meta.updated_at,
                last_access: now_secs(),
                pinned_until: 0,
            },
        );
        drop(inner);
        if !existed {
            self.used.fetch_add(size, Ordering::Relaxed);
        }
        self.remember_stat(id, stat);
        Ok(())
    }

    pub fn write_fragment(&self, id: FileId, index: u32, ct: &[u8]) -> Result<()> {
        let path = {
            let mut inner = self.inner.lock().unwrap();
            let entry = match inner.files.get_mut(&id) {
                Some(entry) => entry,
                None => return Err(anyhow::anyhow!("push for unknown file")),
            };
            if index as usize >= entry.bits.len() {
                return Err(anyhow::anyhow!("fragment index out of range"));
            }
            entry.bits[index as usize] = 1;
            entry.last_access = now_secs();
            fs::write(
                paths_for(&self.root, &id).1,
                &entry.bits,
            )?;
            entry.path.clone()
        };
        let mut file = OpenOptions::new().write(true).open(&path)?;
        file.seek(SeekFrom::Start(index as u64 * SLOT))?;
        file.write_all(ct)?;
        Ok(())
    }

    pub fn pin(&self, id: &FileId, ttl_sec: u32) {
        let now = now_secs();
        let Ok(mut inner) = self.inner.lock() else {
            return;
        };
        if !inner.files.contains_key(id) {
            if let Some(entry) = self.load_entry(id) {
                inner.files.insert(*id, entry);
            }
        }
        if let Some(entry) = inner.files.get_mut(id) {
            entry.pinned_until = now + ttl_sec as i64;
        }
    }

    pub fn remove(&self, id: &FileId) {
        let (dat, bits, meta) = paths_for(&self.root, id);
        let _ = fs::remove_file(dat);
        let _ = fs::remove_file(bits);
        let _ = fs::remove_file(meta);
        if let Ok(mut inner) = self.inner.lock() {
            inner.files.remove(id);
        }
        if let Ok(mut cache) = self.stat_cache.lock() {
            cache.remove(id);
        }
    }
}
