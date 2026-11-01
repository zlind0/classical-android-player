//! Relay settings, media key management and agent lifecycle for the desktop app.

use crate::http_server;
use crate::relay_backend::HostBackend;
use anyhow::{anyhow, Context, Result};
use pod_relay_agent::config::{MediaConfig, PushConfig, RelayEndpoint, SnapshotConfig};
use pod_relay_agent::{generate_media_key, AgentConfig, AgentHandle};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub const DEFAULT_PORT: u16 = 21443;

fn default_readahead() -> u32 {
    8
}
fn default_reads() -> usize {
    4
}
fn default_poll() -> u64 {
    60
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RelaySettings {
    pub enabled: bool,
    pub addr: String,
    pub server_fingerprint: String,
    pub cert_path: String,
    pub key_path: String,
    pub readahead_fragments: u32,
    pub max_inflight_reads: usize,
    pub snapshot_poll_sec: u64,
}

impl Default for RelaySettings {
    fn default() -> Self {
        RelaySettings {
            enabled: false,
            addr: format!("relay.example.com:{DEFAULT_PORT}"),
            server_fingerprint: String::new(),
            cert_path: String::new(),
            key_path: String::new(),
            readahead_fragments: default_readahead(),
            max_inflight_reads: default_reads(),
            snapshot_poll_sec: default_poll(),
        }
    }
}

impl RelaySettings {
    fn validate(&self) -> Result<()> {
        if !self.enabled {
            return Ok(());
        }
        if self.addr.trim().is_empty() {
            return Err(anyhow!("relay address is empty"));
        }
        if self.server_fingerprint.trim().is_empty() {
            return Err(anyhow!("relay certificate fingerprint is empty"));
        }
        for path in [&self.cert_path, &self.key_path] {
            if path.trim().is_empty() || !Path::new(path).is_file() {
                return Err(anyhow!("certificate file missing: {path}"));
            }
        }
        Ok(())
    }
}

pub struct RelayManager {
    data_dir: PathBuf,
    http_state: http_server::HttpState,
    settings: Mutex<RelaySettings>,
    handle: Mutex<Option<AgentHandle>>,
}

impl RelayManager {
    pub fn load(data_dir: PathBuf, http_state: http_server::HttpState) -> Arc<RelayManager> {
        let settings_path = data_dir.join("relay.json");
        let settings = std::fs::read_to_string(&settings_path)
            .ok()
            .and_then(|text| serde_json::from_str::<RelaySettings>(&text).ok())
            .unwrap_or_default();
        let manager = Arc::new(RelayManager {
            data_dir,
            http_state,
            settings: Mutex::new(settings),
            handle: Mutex::new(None),
        });
        manager
    }

    fn relay_dir(&self) -> PathBuf {
        self.data_dir.join("relay")
    }

    fn key_path(&self) -> PathBuf {
        self.relay_dir().join("media.key")
    }

    pub fn settings(&self) -> RelaySettings {
        self.settings
            .lock()
            .map(|settings| settings.clone())
            .unwrap_or_default()
    }

    fn save(&self, settings: &RelaySettings) -> Result<()> {
        std::fs::create_dir_all(&self.data_dir)?;
        let text = serde_json::to_vec_pretty(settings)?;
        std::fs::write(self.data_dir.join("relay.json"), text)?;
        Ok(())
    }

    /// Loads the media key, generating it on first use.
    pub fn media_key(&self) -> Result<(String, u64)> {
        let path = self.key_path();
        if let Ok(text) = std::fs::read_to_string(&path) {
            let trimmed = text.trim().to_string();
            if !trimmed.is_empty() {
                if let Ok(bytes) = decode_key(&trimmed) {
                    return Ok((trimmed, pod_relay_protocol::crypto::key_id(&bytes)));
                }
            }
        }
        std::fs::create_dir_all(self.relay_dir())?;
        let (encoded, key_id) = generate_media_key();
        std::fs::write(&path, &encoded)?;
        Ok((encoded, key_id))
    }

    pub fn regenerate_media_key(&self) -> Result<(String, u64)> {
        std::fs::create_dir_all(self.relay_dir())?;
        let (encoded, key_id) = generate_media_key();
        std::fs::write(self.key_path(), &encoded)?;
        // Cached fragments are encrypted with the old key: restart so the new
        // key is pushed and drop the store contents by telling the agent to
        // rebuild the snapshot. The relay evicts fragments by LRU/TTL.
        let _ = self.stop();
        let settings = self.settings();
        if settings.enabled {
            let _ = self.start();
        }
        Ok((encoded, key_id))
    }

    pub fn apply(&self, next: RelaySettings) -> Result<()> {
        next.validate()?;
        self.save(&next)?;
        if let Ok(mut slot) = self.settings.lock() {
            *slot = next.clone();
        }
        self.stop()?;
        if next.enabled {
            self.start()?;
        }
        Ok(())
    }

    pub fn import_cert(&self, cert_src: &str, key_src: &str) -> Result<(String, String)> {
        let dir = self.relay_dir();
        std::fs::create_dir_all(&dir)?;
        let cert_dst = dir.join("nas.crt");
        let key_dst = dir.join("nas.key");
        std::fs::copy(cert_src, &cert_dst)
            .with_context(|| format!("copy {cert_src}"))?;
        std::fs::copy(key_src, &key_dst).with_context(|| format!("copy {key_src}"))?;
        let mut settings = self.settings();
        settings.cert_path = cert_dst.to_string_lossy().to_string();
        settings.key_path = key_dst.to_string_lossy().to_string();
        self.save(&settings)?;
        if let Ok(mut slot) = self.settings.lock() {
            *slot = settings.clone();
        }
        Ok((settings.cert_path, settings.key_path))
    }

    pub fn start_if_enabled(&self) -> Result<()> {
        if self.settings().enabled {
            self.start()
        } else {
            Ok(())
        }
    }

    pub fn start(&self) -> Result<()> {
        let settings = self.settings();
        settings.validate()?;
        let (key_b64, _) = self.media_key()?;
        let agent_config = AgentConfig {
            relay: RelayEndpoint {
                addr: settings.addr.clone(),
                server_fingerprint: settings.server_fingerprint.clone(),
                client_cert: PathBuf::from(&settings.cert_path),
                client_key: PathBuf::from(&settings.key_path),
            },
            media: MediaConfig { key_b64 },
            push: PushConfig {
                readahead_fragments: settings.readahead_fragments.max(1),
                max_inflight_reads: settings.max_inflight_reads.max(1),
            },
            snapshot: SnapshotConfig {
                poll_sec: settings.snapshot_poll_sec.max(10),
            },
        };
        let backend = HostBackend::new(self.http_state.clone());
        let handle = spawn_agent(agent_config, backend)?;
        if let Ok(mut slot) = self.handle.lock() {
            *slot = Some(handle);
        }
        Ok(())
    }

    pub fn stop(&self) -> Result<()> {
        if let Ok(mut slot) = self.handle.lock() {
            if let Some(handle) = slot.take() {
                handle.stop();
            }
        }
        Ok(())
    }

    pub fn status(&self) -> Value {
        let settings = self.settings();
        let (key_b64, key_id) = self.media_key().unwrap_or((String::new(), 0));
        let agent = self
            .handle
            .lock()
            .ok()
            .and_then(|slot| slot.as_ref().map(|handle| handle.status()));
        let agent = agent.map(|status| serde_json::to_value(status).unwrap_or(Value::Null));
        json!({
            "settings": settings,
            "mediaKey": key_b64,
            "keyId": key_id,
            "agent": agent,
        })
    }
}

fn decode_key(text: &str) -> Result<[u8; 32]> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(text.trim())
        .map_err(|err| anyhow!("media key is not base64: {err}"))?;
    if bytes.len() != 32 {
        return Err(anyhow!("media key must be 32 bytes"));
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&bytes);
    Ok(key)
}

/// Spawns the agent inside the Tauri async runtime. Kept separate so the
/// manager stays testable without a running runtime.
fn spawn_agent(
    config: AgentConfig,
    backend: Arc<HostBackend>,
) -> Result<AgentHandle> {
    let (tx, rx) = tokio::sync::watch::channel(false);
    let status = Arc::new(std::sync::RwLock::new(pod_relay_agent::AgentStatus::default()));
    let task_status = status.clone();
    let join = tauri::async_runtime::spawn(async move {
        pod_relay_agent::run(backend, config, rx, task_status).await;
    });
    drop(join);
    Ok(AgentHandle::from_parts(tx, status))
}
