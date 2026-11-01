//! NAS-side relay agent. Runs inside the host process, reuses the host's library
//! router through `LibraryBackend`, and never exposes a local port.

pub mod backend;
pub mod client;
pub mod config;
pub mod index;
pub mod push;
pub mod snapshot;

pub use backend::{LibraryBackend, LiveRequest, LiveResponse, Stat};
pub use config::{generate_media_key, AgentConfig};
pub use push::StatInfo;

use anyhow::{anyhow, Result};
use client::HostLink;
use index::Index;
use pod_relay_protocol::crypto::{live_key, live_open, live_seal, FRAGMENT_BYTES};
use pod_relay_protocol::msg::Msg;
use push::PushState;
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};
use std::time::Duration;
use tokio::sync::watch;

#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentStatus {
    pub connected: bool,
    pub host_online: bool,
    pub lib_version: i64,
    pub store_used: u64,
    pub store_cap: u64,
    pub session_id: u64,
    pub key_id: u64,
    pub last_sync_ms: i64,
    pub last_error: Option<String>,
}

#[derive(Serialize, Deserialize)]
struct LivePayload {
    method: String,
    path: String,
    #[serde(default)]
    body: String,
}

pub struct AgentHandle {
    stop: watch::Sender<bool>,
    status: Arc<RwLock<AgentStatus>>,
    stopped: AtomicBool,
}

impl AgentHandle {
    /// Builds a handle around an externally spawned `run` future.
    pub fn from_parts(stop: watch::Sender<bool>, status: Arc<RwLock<AgentStatus>>) -> AgentHandle {
        AgentHandle {
            stop,
            status,
            stopped: AtomicBool::new(false),
        }
    }

    pub fn status(&self) -> AgentStatus {
        self.status
            .read()
            .map(|status| status.clone())
            .unwrap_or_default()
    }

    pub fn stop(&self) {
        if !self.stopped.swap(true, Ordering::SeqCst) {
            let _ = self.stop.send(true);
        }
    }
}

impl Drop for AgentHandle {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Spawns the agent on the current Tokio runtime.
pub fn spawn(backend: Arc<dyn LibraryBackend>, config: AgentConfig) -> AgentHandle {
    let (tx, rx) = watch::channel(false);
    let status = Arc::new(RwLock::new(AgentStatus::default()));
    let task_status = status.clone();
    tokio::spawn(run(backend, config, rx, task_status));
    AgentHandle {
        stop: tx,
        status,
        stopped: AtomicBool::new(false),
    }
}

pub async fn run(
    backend: Arc<dyn LibraryBackend>,
    config: AgentConfig,
    mut stop: watch::Receiver<bool>,
    status: Arc<RwLock<AgentStatus>>,
) {
    let mut backoff = 1u64;
    loop {
        if *stop.borrow() {
            break;
        }
        let outcome = session(backend.clone(), config.clone(), &mut stop, status.clone()).await;
        set_status(&status, |s| {
            s.connected = false;
        });
        if *stop.borrow() {
            break;
        }
        let wait = Duration::from_secs(backoff);
        backoff = (backoff * 2).min(60);
        let sleep = tokio::time::sleep(wait);
        tokio::select! {
            _ = sleep => {}
            _ = stop.changed() => break,
        }
        if let Err(err) = outcome {
            set_status(&status, |s| s.last_error = Some(err.to_string()));
            eprintln!("[pod-relay] session error: {err}");
        }
    }
}

fn set_status(status: &Arc<RwLock<AgentStatus>>, f: impl FnOnce(&mut AgentStatus)) {
    if let Ok(mut status) = status.write() {
        f(&mut status);
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

async fn session(
    backend: Arc<dyn LibraryBackend>,
    config: AgentConfig,
    stop: &mut watch::Receiver<bool>,
    status: Arc<RwLock<AgentStatus>>,
) -> Result<()> {
    let master = config.media_key()?;
    let key_id = pod_relay_protocol::crypto::key_id(&master);
    set_status(&status, |s| {
        s.key_id = key_id;
        s.last_error = None;
    });

    let mut link = HostLink::connect(&config, key_id).await?;
    let mut version = backend.library_version().await?;
    let mut json = backend.snapshot_json().await?;
    let mut index = Index::build(&json)?;

    let push = PushState::new(
        backend.clone(),
        index.clone(),
        config.push.readahead_fragments,
        config.push.max_inflight_reads,
        link.writer.clone(),
        pod_relay_protocol::crypto::media_key(&master),
        key_id,
    );

    set_status(&status, |s| {
        s.connected = true;
        s.host_online = link.hello.host_online;
        s.session_id = link.hello.session_id;
        s.store_used = link.hello.store_used;
        s.store_cap = link.hello.store_cap;
        s.lib_version = version;
        s.last_sync_ms = now_ms();
    });

    if link.hello.lib_version != version {
        snapshot::push(&link, &config, version, &json).await?;
    }

    let mut ticker = tokio::time::interval(Duration::from_secs(config.snapshot.poll_sec.max(10)));
    ticker.tick().await;

    loop {
        tokio::select! {
            _ = stop.changed() => {
                if *stop.borrow() {
                    return Ok(());
                }
            }
            _ = ticker.tick() => {
                let latest = backend.library_version().await?;
                if latest != version {
                    version = latest;
                    json = backend.snapshot_json().await?;
                    index = Index::build(&json)?;
                    push.set_index(index.clone());
                    snapshot::push(&link, &config, version, &json).await?;
                    set_status(&status, |s| {
                        s.lib_version = version;
                        s.last_sync_ms = now_ms();
                    });
                }
            }
            incoming = link.recv(Duration::from_secs(30)) => {
                let (req, msg) = match incoming {
                    Ok(pair) => pair,
                    Err(err) => {
                        let text = err.to_string();
                        if text.contains("timeout") {
                            continue;
                        }
                        return Err(err);
                    }
                };
                match msg {
                    Msg::Want { file_id, index, priority } => {
                        push.spawn_want(file_id, index, priority);
                    }
                    Msg::StatReq { file_id } => {
                        let push = push.clone();
                        tokio::spawn(async move {
                            push.respond_stat(file_id, req).await;
                        });
                    }
                    Msg::CacheStatus { used, cap, pressure } => {
                        push.set_pressure(pressure);
                        set_status(&status, |s| {
                            s.store_used = used;
                            s.store_cap = cap;
                        });
                    }
                    Msg::EvictNotice { file_id } => push.forget(&file_id),
                    Msg::LiveFwd { seq, ct } => {
                        let backend = backend.clone();
                        let push = push.clone();
                        let key = live_key(&master);
                        tokio::spawn(async move {
                            let _ = handle_live(backend, push, key, seq, ct).await;
                        });
                    }
                    Msg::Ping { nonce } => {
                        let _ = link.send(Msg::Pong { nonce }).await;
                    }
                    Msg::HelloOk { .. } => {}
                    Msg::Error { code, msg } => {
                        eprintln!("[pod-relay] relay error {code}: {msg}");
                    }
                    _ => {}
                }
            }
        }
    }
}

async fn handle_live(
    backend: Arc<dyn LibraryBackend>,
    push: Arc<PushState>,
    key: [u8; 32],
    seq: u32,
    ct: Vec<u8>,
) -> Result<()> {
    let plain = live_open(&key, seq, 1, &ct)?;
    let payload: LivePayload = serde_json::from_slice(&plain)?;
    let body = if payload.body.is_empty() {
        Vec::new()
    } else {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD
            .decode(payload.body.as_bytes())
            .unwrap_or_default()
    };
    let response = backend
        .live(LiveRequest {
            method: payload.method,
            path: payload.path,
            body,
        })
        .await?;
    use base64::Engine;
    let encoded = LivePayload {
        method: String::new(),
        path: String::new(),
        body: base64::engine::general_purpose::STANDARD.encode(response.body),
    };
    let plain = serde_json::to_vec(&encoded)?;
    let ct = live_seal(&key, seq, 2, &plain)?;
    push.send_msg(Msg::LiveReply {
        seq,
        status: response.status,
        ct,
    })
    .await?;
    Ok(())
}

/// Convenience for hosts: describe the fragment layout of a file.
pub fn fragment_count(total: u64) -> u32 {
    total.div_ceil(FRAGMENT_BYTES as u64) as u32
}

pub fn ensure_key(config: &AgentConfig) -> Result<u64> {
    if config.media.key_b64.trim().is_empty() {
        return Err(anyhow!("media key is empty"));
    }
    config.key_id()
}
