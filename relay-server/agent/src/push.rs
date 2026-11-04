//! Host-side push pipeline: reads byte windows from the host backend, encrypts
//! them into fragments and pushes them to the relay on demand.

use crate::backend::LibraryBackend;
use crate::index::Index;
use anyhow::{anyhow, Result};
use pod_relay_protocol::crypto::{
    encrypt_fragment, file_salt, FRAGMENT_BYTES,
};
use pod_relay_protocol::msg::{FileId, Msg};
use pod_relay_server::log::{bytes, elapsed, file_short, kind_from_route};
use std::collections::{HashMap, HashSet};

use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use tokio::sync::Semaphore;

use crate::client::TlsStream;

#[derive(Debug, Clone)]
pub struct StatInfo {
    pub total: u64,
    pub frag: u32,
    pub count: u32,
    pub salt: [u8; 8],
    pub mime: String,
    pub key_id: u64,
}

pub struct PushState {
    backend: Arc<dyn LibraryBackend>,
    index: RwLock<Index>,
    stats: Mutex<HashMap<FileId, StatInfo>>,
    sent: Mutex<HashMap<(FileId, u32), i64>>,
    inflight: Mutex<HashSet<(FileId, u32)>>,
    pressure: AtomicU8,
    readahead: u32,
    sem: Arc<Semaphore>,
    writer: Arc<tokio::sync::Mutex<tokio::io::WriteHalf<TlsStream>>>,
    key: [u8; 32],
    pub key_id: u64,
}

impl PushState {
    pub fn new(
        backend: Arc<dyn LibraryBackend>,
        index: Index,
        readahead: u32,
        max_inflight: usize,
        writer: Arc<tokio::sync::Mutex<tokio::io::WriteHalf<TlsStream>>>,
        key: [u8; 32],
        key_id: u64,
    ) -> Arc<PushState> {
        Arc::new(PushState {
            backend,
            index: RwLock::new(index),
            stats: Mutex::new(HashMap::new()),
            sent: Mutex::new(HashMap::new()),
            inflight: Mutex::new(HashSet::new()),
            pressure: AtomicU8::new(0),
            readahead: readahead.max(1),
            sem: Arc::new(Semaphore::new(max_inflight.max(1))),
            writer,
            key,
            key_id,
        })
    }

    pub fn set_index(&self, index: Index) {
        if let Ok(mut slot) = self.index.write() {
            *slot = index;
        }
    }

    pub fn set_pressure(&self, pressure: u8) {
        self.pressure.store(pressure, Ordering::Relaxed);
    }

    pub fn forget(&self, file_id: &FileId) {
        if let Ok(mut sent) = self.sent.lock() {
            sent.retain(|(id, _), _| id != file_id);
        }
    }

    fn already_sent(&self, file_id: &FileId, index: u32) -> bool {
        let Ok(sent) = self.sent.lock() else {
            return false;
        };
        let now = now_secs();
        match sent.get(&(*file_id, index)) {
            // 中继的淘汰不保证通知得到，超过 10 分钟视为需要重推。
            Some(at) => now - *at < 600,
            None => false,
        }
    }

    fn mark_sent(&self, file_id: &FileId, index: u32) {
        if let Ok(mut sent) = self.sent.lock() {
            sent.insert((*file_id, index), now_secs());
        }
    }

    pub fn route(&self, file_id: &FileId) -> Option<String> {
        self.index
            .read()
            .ok()
            .and_then(|index| index.route(file_id).map(|s| s.to_string()))
    }

    fn known_stat(&self, file_id: &FileId) -> Option<StatInfo> {
        self.stats.lock().ok()?.get(file_id).cloned()
    }

    pub async fn stat(&self, file_id: FileId) -> Result<StatInfo> {
        if let Some(stat) = self.known_stat(&file_id) {
            return Ok(stat);
        }
        let route = self
            .route(&file_id)
            .ok_or_else(|| anyhow!("unknown file id"))?;
        let raw = self.backend.stat(&route).await?;
        let count = raw.total.div_ceil(FRAGMENT_BYTES as u64) as u32;
        let stat = StatInfo {
            total: raw.total,
            frag: FRAGMENT_BYTES as u32,
            count,
            salt: file_salt(&self.key, &file_id),
            mime: raw.mime,
            key_id: self.key_id,
        };
        if let Ok(mut stats) = self.stats.lock() {
            stats.insert(file_id, stat.clone());
        }
        Ok(stat)
    }

    pub async fn respond_stat(&self, file_id: FileId, req: u32) {
        let msg = match self.stat(file_id).await {
            Ok(stat) => Msg::StatOk {
                file_id,
                total: stat.total,
                frag: stat.frag,
                count: stat.count,
                salt: stat.salt,
                mime: stat.mime.clone(),
                key_id: stat.key_id,
            },
            Err(err) => {
                eprintln!(
                    "[pod-relay] stat for {} failed: {err}",
                    file_short(&file_id)
                );
                Msg::StatErr {
                    file_id,
                    code: pod_relay_protocol::msg::err::NOT_FOUND,
                }
            }
        };
        let _ = self.send_req(req, msg).await;
    }

    pub async fn send_msg(&self, msg: Msg) -> Result<()> {
        self.send_req(0, msg).await
    }

    pub async fn send_req(&self, req: u32, msg: Msg) -> Result<()> {
        use pod_relay_protocol::frame::encode;
        use pod_relay_protocol::to_frame;
        let frame = to_frame(&msg, req);
        let bytes = encode(&frame);
        let mut writer = self.writer.lock().await;
        use tokio::io::AsyncWriteExt;
        writer.write_all(&bytes).await?;
        Ok(())
    }

    pub fn spawn_want(self: &Arc<Self>, file_id: FileId, index: u32, priority: u8) {
        let key = (file_id, index);
        {
            let Ok(mut inflight) = self.inflight.lock() else {
                return;
            };
            if !inflight.insert(key) {
                return;
            }
        }
        if self.already_sent(&file_id, index) {
            if let Ok(mut inflight) = self.inflight.lock() {
                inflight.remove(&key);
            }
            return;
        }
        let me = self.clone();
        tokio::spawn(async move {
            let permit = me.sem.clone().acquire_owned().await;
            if let Ok(permit) = permit {
                if let Err(err) = me.push_window(file_id, index, priority).await {
                    eprintln!(
                        "[pod-relay] push failed for {} fragment {index}: {err}",
                        file_short(&file_id)
                    );
                }
                drop(permit);
            }
            if let Ok(mut inflight) = me.inflight.lock() {
                inflight.remove(&key);
            }
        });
    }

    async fn push_window(self: &Arc<Self>, file_id: FileId, index: u32, priority: u8) -> Result<()> {
        let started = std::time::Instant::now();
        let stat = self.stat(file_id).await?;
        if index >= stat.count {
            return Ok(());
        }
        if self.already_sent(&file_id, index) {
            return Ok(());
        }
        let window = if self.pressure.load(Ordering::Relaxed) == 0 {
            self.readahead as u64
        } else {
            1
        };
        let start_index = index as u64;
        let end_index = (start_index + window).min(stat.count as u64);
        if start_index >= end_index {
            return Ok(());
        }
        let pending: Vec<u32> = (start_index..end_index)
            .map(|value| value as u32)
            .filter(|value| !self.already_sent(&file_id, *value))
            .collect();
        let Some(&first) = pending.first() else {
            return Ok(());
        };
        let last = *pending.last().unwrap();

        let start_byte = first as u64 * FRAGMENT_BYTES as u64;
        let end_byte = ((last as u64 + 1) * FRAGMENT_BYTES as u64).min(stat.total);
        if start_byte >= stat.total {
            return Ok(());
        }
        let route = self
            .route(&file_id)
            .ok_or_else(|| anyhow!("unknown file id"))?;
        let kind = kind_from_route(&route);
        let data = self
            .backend
            .fetch_range(&route, start_byte, end_byte - 1)
            .await?;
        if data.is_empty() {
            return Ok(());
        }

        // `HOST_PUSH_BEGIN` is idempotent on the relay: it keeps fragments that
        // were already pushed for the same layout.
        self.send_msg(Msg::HostPushBegin {
            file_id,
            total: stat.total,
            frag: stat.frag,
            count: stat.count,
            salt: stat.salt,
            mime: stat.mime.clone(),
        })
        .await?;

        for frag_index in first..=last {
            let frag_start = frag_index as u64 * FRAGMENT_BYTES as u64;
            let frag_len = ((stat.total - frag_start).min(FRAGMENT_BYTES as u64)) as usize;
            let offset = (frag_start - start_byte) as usize;
            if offset + frag_len > data.len() {
                break;
            }
            if !pending.contains(&frag_index) {
                continue;
            }
            if self.already_sent(&file_id, frag_index) {
                continue;
            }
            self.mark_sent(&file_id, frag_index);
            let plain = &data[offset..offset + frag_len];
            let (_, ct) = encrypt_fragment(&self.key, &file_id, &stat.salt, frag_index, plain)?;
            self.send_msg(Msg::HostPushFrag {
                file_id,
                index: frag_index,
                ct,
            })
            .await?;
        }

        self.send_msg(Msg::HostPushEnd { file_id, ok: true }).await?;
        eprintln!(
            "[pod-relay] pushed {kind} {} fragments {first}..={last} ({} from {route}, priority {priority}) in {}",
            file_short(&file_id),
            bytes(data.len() as u64),
            elapsed(started.elapsed().as_millis())
        );
        Ok(())
    }
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}
