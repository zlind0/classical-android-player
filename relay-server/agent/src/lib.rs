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
use pod_relay_protocol::access::{scope_version, AuthorizedDevice, Scope};
use pod_relay_protocol::crypto::{live_key, live_open, live_seal, FRAGMENT_BYTES};
use pod_relay_protocol::msg::Msg;
use push::PushState;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeSet;
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
    control: tokio::sync::mpsc::UnboundedSender<Msg>,
    devices: Arc<RwLock<Vec<AuthorizedDevice>>>,
    scopes: Arc<RwLock<Vec<Scope>>>,
    access_tick: watch::Sender<u64>,
    stopped: AtomicBool,
}

impl AgentHandle {
    /// Builds a handle around an externally spawned `run` future.
    pub fn from_parts(
        stop: watch::Sender<bool>,
        status: Arc<RwLock<AgentStatus>>,
        control: tokio::sync::mpsc::UnboundedSender<Msg>,
        devices: Arc<RwLock<Vec<AuthorizedDevice>>>,
        scopes: Arc<RwLock<Vec<Scope>>>,
        access_tick: watch::Sender<u64>,
    ) -> AgentHandle {
        AgentHandle {
            stop,
            status,
            control,
            devices,
            scopes,
            access_tick,
            stopped: AtomicBool::new(false),
        }
    }

    /// Authorizes a client certificate on the relay. Kept for compatibility;
    /// new code should update the full device list with [set_devices].
    pub fn authorize_client(&self, client_id: [u8; 16], label: &str, fingerprint: &str) {
        if let Ok(mut devices) = self.devices.write() {
            devices.retain(|device| device.id != client_id && device.fingerprint != fingerprint);
            devices.push(AuthorizedDevice {
                id: client_id,
                label: label.to_string(),
                fingerprint: fingerprint.to_string(),
                roots: Vec::new(),
                all: false,
            });
        }
        self.tick_access();
    }

    pub fn set_devices(&self, devices: Vec<AuthorizedDevice>) {
        if let Ok(mut slot) = self.devices.write() {
            *slot = devices;
        }
        self.tick_access();
    }

    pub fn set_scopes(&self, scopes: Vec<Scope>) {
        if let Ok(mut slot) = self.scopes.write() {
            *slot = scopes;
        }
        self.tick_access();
    }

    fn tick_access(&self) {
        self.access_tick.send_modify(|value| *value = value.wrapping_add(1));
    }

    pub fn revoke_client(&self, client_id: [u8; 16]) {
        if let Ok(mut devices) = self.devices.write() {
            devices.retain(|device| device.id != client_id);
        }
        let _ = self.control.send(Msg::RevokeClient { client_id });
        self.tick_access();
    }

    pub fn reset_clients(&self) {
        if let Ok(mut devices) = self.devices.write() {
            devices.clear();
        }
        let _ = self.control.send(Msg::ResetClients);
        self.tick_access();
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
    let (control_tx, control_rx) = tokio::sync::mpsc::unbounded_channel();
    let devices = Arc::new(RwLock::new(config.devices.clone()));
    let scopes = Arc::new(RwLock::new(config.scopes.clone()));
    let (access_tx, access_rx) = watch::channel(0u64);
    tokio::spawn(run_with_control(
        backend,
        config,
        rx,
        task_status,
        control_rx,
        devices.clone(),
        scopes.clone(),
        access_rx,
    ));
    AgentHandle {
        stop: tx,
        status,
        control: control_tx,
        devices,
        scopes,
        access_tick: access_tx,
        stopped: AtomicBool::new(false),
    }
}

pub async fn run_with_control(
    backend: Arc<dyn LibraryBackend>,
    config: AgentConfig,
    stop: watch::Receiver<bool>,
    status: Arc<RwLock<AgentStatus>>,
    mut control: tokio::sync::mpsc::UnboundedReceiver<Msg>,
    devices: Arc<RwLock<Vec<AuthorizedDevice>>>,
    scopes: Arc<RwLock<Vec<Scope>>>,
    access: watch::Receiver<u64>,
) {
    run_inner(
        backend,
        config,
        stop,
        status,
        &mut control,
        devices,
        scopes,
        access,
    )
    .await
}

pub async fn run(
    backend: Arc<dyn LibraryBackend>,
    config: AgentConfig,
    stop: watch::Receiver<bool>,
    status: Arc<RwLock<AgentStatus>>,
) {
    let (_tx, mut control) = tokio::sync::mpsc::unbounded_channel();
    let devices = Arc::new(RwLock::new(config.devices.clone()));
    let scopes = Arc::new(RwLock::new(config.scopes.clone()));
    let (_access_tx, access) = watch::channel(0u64);
    run_inner(
        backend,
        config,
        stop,
        status,
        &mut control,
        devices,
        scopes,
        access,
    )
    .await;
}

async fn run_inner(
    backend: Arc<dyn LibraryBackend>,
    config: AgentConfig,
    mut stop: watch::Receiver<bool>,
    status: Arc<RwLock<AgentStatus>>,
    control: &mut tokio::sync::mpsc::UnboundedReceiver<Msg>,
    devices: Arc<RwLock<Vec<AuthorizedDevice>>>,
    scopes: Arc<RwLock<Vec<Scope>>>,
    mut access: watch::Receiver<u64>,
) {
    let mut backoff = 1u64;
    loop {
        if *stop.borrow() {
            break;
        }
        let outcome = session(
            backend.clone(),
            config.clone(),
            &mut stop,
            control,
            status.clone(),
            &devices,
            &scopes,
            &mut access,
        )
        .await;
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
    control: &mut tokio::sync::mpsc::UnboundedReceiver<Msg>,
    status: Arc<RwLock<AgentStatus>>,
    devices: &Arc<RwLock<Vec<AuthorizedDevice>>>,
    scopes: &Arc<RwLock<Vec<Scope>>>,
    access: &mut watch::Receiver<u64>,
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

    // The relay may still hold grants for devices revoked while the NAS was
    // offline. Reset first, then re-authorize the current local list so a
    // reconnect never resurrects an old phone.
    let mut pushed: std::collections::HashSet<u64> = std::collections::HashSet::new();
    let mut root_map_version = i64::MIN;
    sync_access(
        &link,
        &config,
        devices,
        scopes,
        &index,
        version,
        &json,
        &mut pushed,
        &mut root_map_version,
        true,
    )
    .await?;

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
                    pushed.clear();
                    root_map_version = i64::MIN;
                    sync_access(
                        &link,
                        &config,
                        devices,
                        scopes,
                        &index,
                        version,
                        &json,
                        &mut pushed,
                        &mut root_map_version,
                        false,
                    )
                    .await?;
                    set_status(&status, |s| {
                        s.lib_version = version;
                        s.last_sync_ms = now_ms();
                    });
                }
            }
            changed = access.changed() => {
                if changed.is_err() {
                    // The handle was dropped; the stop channel will end us.
                    continue;
                }
                sync_access(
                    &link,
                    &config,
                    devices,
                    scopes,
                    &index,
                    version,
                    &json,
                    &mut pushed,
                    &mut root_map_version,
                    false,
                )
                .await?;
            }
            message = control.recv() => {
                if let Some(message) = message {
                    link.send(message).await?;
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
                    Msg::LiveFwd {
                        seq,
                        client_seq,
                        client_id,
                        ct,
                    } => {
                        let backend = backend.clone();
                        let push = push.clone();
                        let key = live_key(&master);
                        tokio::spawn(async move {
                            let _ = handle_live(
                                backend, push, key, seq, client_seq, client_id, ct,
                            )
                            .await;
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

/// Pushes the file→root map, the device grants and every snapshot scope that
/// is not on the relay yet. `reset` is only used right after connecting.
async fn sync_access(
    link: &HostLink,
    config: &AgentConfig,
    devices: &Arc<RwLock<Vec<AuthorizedDevice>>>,
    scopes: &Arc<RwLock<Vec<Scope>>>,
    index: &Index,
    library_version: i64,
    json: &[u8],
    pushed: &mut std::collections::HashSet<u64>,
    root_map_version: &mut i64,
    reset: bool,
) -> Result<()> {
    if reset {
        link.send(Msg::ResetClients).await?;
    }

    if *root_map_version != library_version {
        push_root_map(link, index, library_version).await?;
        *root_map_version = library_version;
    }

    let device_list = devices.read().map(|list| list.clone()).unwrap_or_default();
    for device in &device_list {
        link.send(Msg::AuthorizeClient {
            client_id: device.id,
            label: device.label.clone(),
            fingerprint: device.fingerprint.clone(),
            roots: device.roots.clone(),
            all: device.all,
        })
        .await?;
    }

    let mut wanted: Vec<Scope> = scopes.read().map(|list| list.clone()).unwrap_or_default();
    for device in &device_list {
        wanted.push(Scope::new(device.roots.clone(), device.all));
    }
    // The empty scope must always exist; devices with no grants get an empty
    // library instead of "relay has no snapshot yet".
    wanted.push(Scope::new(Vec::new(), false));

    let mut unique: std::collections::BTreeMap<u64, Scope> = std::collections::BTreeMap::new();
    for scope in wanted {
        if scope.id == 0 {
            unique.insert(0, Scope::new(Vec::new(), false));
        } else {
            unique.entry(scope.id).or_insert(scope);
        }
    }

    for (id, scope) in unique {
        if pushed.contains(&id) {
            continue;
        }
        let (version, bytes) = scoped_snapshot(json, &scope, library_version)?;
        snapshot::push(link, config, id, version, &bytes).await?;
        pushed.insert(id);
    }
    Ok(())
}

async fn push_root_map(link: &HostLink, index: &Index, version: i64) -> Result<()> {
    let entries = index.root_entries();
    let mut bytes = Vec::with_capacity(entries.len() * 24);
    for (file_id, root_id) in &entries {
        bytes.extend_from_slice(file_id);
        bytes.extend_from_slice(&root_id.to_be_bytes());
    }
    link.send(Msg::RootMapBegin {
        version,
        count: entries.len() as u32,
    })
    .await?;
    let chunk_bytes = pod_relay_protocol::msg::ROOT_MAP_CHUNK_ITEMS * 24;
    for (index, chunk) in bytes.chunks(chunk_bytes).enumerate() {
        link.send(Msg::RootMapChunk {
            version,
            index: index as u32,
            data: chunk.to_vec(),
        })
        .await?;
    }
    link.send(Msg::RootMapEnd { version, ok: true }).await?;
    eprintln!(
        "[pod-relay] pushed root map version {version} ({} files)",
        entries.len()
    );
    Ok(())
}

/// Filters a full snapshot down to one root set. `all=true` scopes keep the
/// original bytes (and the global library version) so the full-library case is
/// byte-for-byte what clients cached before folder ACLs existed.
fn scoped_snapshot(json: &[u8], scope: &Scope, library_version: i64) -> Result<(i64, Vec<u8>)> {
    if scope.all && scope.id != 0 {
        return Ok((library_version, json.to_vec()));
    }
    let version = scope_version(library_version, scope.id);
    let allowed: BTreeSet<i64> = scope.roots.iter().copied().collect();
    let mut value: Value = serde_json::from_slice(json)?;
    for key in ["music", "ebooks", "audiobooks"] {
        if let Some(items) = value.get_mut(key).and_then(|v| v.as_array_mut()) {
            items.retain(|item| item_root(item).map(|root| allowed.contains(&root)).unwrap_or(false));
        }
    }
    if let Some(roots) = value.get_mut("ebookRoots").and_then(|v| v.as_array_mut()) {
        roots.retain(|item| {
            item.get("key")
                .and_then(|v| v.as_str())
                .and_then(|text| text.parse::<i64>().ok())
                .map(|root| allowed.contains(&root))
                .unwrap_or(false)
        });
    }
    if let Some(object) = value.as_object_mut() {
        object.insert("libraryUpdatedAt".to_string(), Value::from(version));
    }
    Ok((version, serde_json::to_vec(&value)?))
}

fn item_root(item: &Value) -> Option<i64> {
    item.get("rootKey")?
        .as_str()
        .and_then(|text| text.parse::<i64>().ok())
}

async fn handle_live(
    backend: Arc<dyn LibraryBackend>,
    push: Arc<PushState>,
    key: [u8; 32],
    relay_seq: u32,
    client_seq: u32,
    client_id: [u8; 16],
    ct: Vec<u8>,
) -> Result<()> {
    // The client's AAD uses its own sequence number; the relay only swaps the
    // routing id, so crypto must stay on `client_seq`.
    let plain = live_open(&key, client_seq, 1, &ct)?;
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
            client_id: Some(client_id),
        })
        .await?;
    use base64::Engine;
    let encoded = LivePayload {
        method: String::new(),
        path: String::new(),
        body: base64::engine::general_purpose::STANDARD.encode(response.body),
    };
    let plain = serde_json::to_vec(&encoded)?;
    let ct = live_seal(&key, client_seq, 2, &plain)?;
    push.send_msg(Msg::LiveReply {
        seq: relay_seq,
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
