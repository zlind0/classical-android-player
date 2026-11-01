//! Relay settings, identity material, device registry and agent lifecycle.

use crate::discovery;
use crate::http_server;
use crate::relay_backend::HostBackend;
use crate::relay_pairing::{PairContext, PairingState};
use anyhow::{anyhow, Result};
use pod_relay_agent::config::{MediaConfig, PushConfig, RelayEndpoint, SnapshotConfig};
use pod_relay_agent::{generate_media_key, AgentConfig, AgentHandle};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::path::PathBuf;
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
    pub host_token: String,
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
            host_token: String::new(),
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
            return Err(anyhow!("中继地址为空"));
        }
        if self.server_fingerprint.trim().is_empty() {
            return Err(anyhow!("服务器指纹为空"));
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceRecord {
    pub id: String,
    pub label: String,
    pub fingerprint: String,
    #[serde(default)]
    pub created_at: i64,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

pub struct RelayManager {
    data_dir: PathBuf,
    http_state: http_server::HttpState,
    settings: Mutex<RelaySettings>,
    handle: Mutex<Option<AgentHandle>>,
    devices: Mutex<Vec<DeviceRecord>>,
    pairing: Arc<PairingState>,
}

impl RelayManager {
    pub fn load(
        data_dir: PathBuf,
        http_state: http_server::HttpState,
        pairing: Arc<PairingState>,
    ) -> Arc<RelayManager> {
        let settings = std::fs::read_to_string(data_dir.join("relay.json"))
            .ok()
            .and_then(|text| serde_json::from_str::<RelaySettings>(&text).ok())
            .unwrap_or_default();
        let devices = std::fs::read_to_string(data_dir.join("relay").join("devices.json"))
            .ok()
            .and_then(|text| serde_json::from_str::<Vec<DeviceRecord>>(&text).ok())
            .unwrap_or_default();
        Arc::new(RelayManager {
            data_dir,
            http_state,
            settings: Mutex::new(settings),
            handle: Mutex::new(None),
            devices: Mutex::new(devices),
            pairing,
        })
    }

    fn relay_dir(&self) -> PathBuf {
        self.data_dir.join("relay")
    }

    fn key_path(&self) -> PathBuf {
        self.relay_dir().join("media.key")
    }

    fn devices_path(&self) -> PathBuf {
        self.relay_dir().join("devices.json")
    }

    pub fn settings(&self) -> RelaySettings {
        self.settings
            .lock()
            .map(|settings| settings.clone())
            .unwrap_or_default()
    }

    fn save_settings(&self, settings: &RelaySettings) -> Result<()> {
        std::fs::create_dir_all(&self.data_dir)?;
        std::fs::write(
            self.data_dir.join("relay.json"),
            serde_json::to_vec_pretty(settings)?,
        )?;
        Ok(())
    }

    fn save_devices(&self) -> Result<()> {
        std::fs::create_dir_all(self.relay_dir())?;
        let devices = self.devices.lock().map(|d| d.clone()).unwrap_or_default();
        std::fs::write(self.devices_path(), serde_json::to_vec_pretty(&devices)?)?;
        Ok(())
    }

    /// Generates the NAS certificate on first use and returns its paths.
    pub fn ensure_identity(&self) -> Result<(PathBuf, PathBuf)> {
        let dir = self.relay_dir();
        std::fs::create_dir_all(&dir)?;
        let cert = dir.join("nas.crt");
        let key = dir.join("nas.key");
        if !cert.is_file() || !key.is_file() {
            pod_relay_server::cert::generate("host", "aurora-nas", &[], &dir.join("nas.crt"))?;
            eprintln!("[pod-relay] generated NAS certificate in {}", dir.display());
        }
        Ok((cert, key))
    }

    pub fn media_key(&self) -> Result<(String, u64)> {
        let path = self.key_path();
        if let Ok(text) = std::fs::read_to_string(&path) {
            let trimmed = text.trim().to_string();
            if let Ok(bytes) = decode_key(&trimmed) {
                return Ok((trimmed, pod_relay_protocol::crypto::key_id(&bytes)));
            }
        }
        std::fs::create_dir_all(self.relay_dir())?;
        let (encoded, key_id) = generate_media_key();
        std::fs::write(&path, &encoded)?;
        Ok((encoded, key_id))
    }

    pub fn apply(&self, next: RelaySettings) -> Result<()> {
        next.validate()?;
        self.save_settings(&next)?;
        if let Ok(mut slot) = self.settings.lock() {
            *slot = next.clone();
        }
        self.stop();
        if next.enabled {
            self.start()?;
        } else {
            self.pairing.cancel();
        }
        Ok(())
    }

    /// Parses `podrelay://relay?v=2&a=…&p=…&t=…` and applies it.
    pub fn apply_bootstrap(&self, line: &str) -> Result<RelaySettings> {
        let line = line.trim();
        let query = line
            .split_once('?')
            .map(|(_, q)| q)
            .ok_or_else(|| anyhow!("引导串格式不正确"))?;
        let mut addr = None;
        let mut pin = None;
        let mut token = None;
        for part in query.split('&') {
            let (key, value) = part.split_once('=').unwrap_or((part, ""));
            match key {
                "a" => addr = Some(percent_decode(value)),
                "p" => pin = Some(percent_decode(value)),
                "t" => token = Some(percent_decode(value)),
                _ => {}
            }
        }
        let mut settings = self.settings();
        settings.addr = addr.ok_or_else(|| anyhow!("引导串缺少地址"))?;
        settings.server_fingerprint = pin.ok_or_else(|| anyhow!("引导串缺少指纹"))?;
        settings.host_token = token.ok_or_else(|| anyhow!("引导串缺少 token"))?;
        settings.enabled = true;
        self.apply(settings.clone())?;
        Ok(settings)
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
        let (key_b64, key_id) = self.media_key()?;
        let (cert, key) = self.ensure_identity()?;
        let agent_config = AgentConfig {
            relay: RelayEndpoint {
                addr: settings.addr.clone(),
                server_fingerprint: settings.server_fingerprint.clone(),
                client_cert: cert,
                client_key: key,
                host_token: settings.host_token.clone(),
            },
            media: MediaConfig { key_b64: key_b64.clone() },
            push: PushConfig {
                readahead_fragments: settings.readahead_fragments.max(1),
                max_inflight_reads: settings.max_inflight_reads.max(1),
            },
            snapshot: SnapshotConfig {
                poll_sec: settings.snapshot_poll_sec.max(10),
            },
        };

        self.pairing.set_context(PairContext {
            relay_addr: settings.addr.clone(),
            relay_fingerprint: settings.server_fingerprint.clone(),
            media_key_b64: key_b64,
            key_id,
            device_id: self.http_state.info.device_id.clone(),
            device_name: self.http_state.info.device_name.clone(),
            lan_host: discovery::local_ipv4(),
            lan_port: self.http_state.info.port,
        });

        let backend = HostBackend::new(self.http_state.clone());
        let handle = spawn_agent(agent_config, backend)?;
        if let Ok(mut slot) = self.handle.lock() {
            *slot = Some(handle);
        }
        Ok(())
    }

    pub fn stop(&self) {
        if let Ok(mut slot) = self.handle.lock() {
            if let Some(handle) = slot.take() {
                handle.stop();
            }
        }
    }

    pub fn status(&self) -> Value {
        let settings = self.settings();
        let (key_b64, key_id) = self.media_key().unwrap_or((String::new(), 0));
        let agent = self
            .handle
            .lock()
            .ok()
            .and_then(|slot| slot.as_ref().map(|handle| handle.status()))
            .map(|status| serde_json::to_value(status).unwrap_or(Value::Null));
        let devices = self.devices.lock().map(|d| d.clone()).unwrap_or_default();
        json!({
            "settings": settings,
            "mediaKey": key_b64,
            "keyId": key_id,
            "agent": agent,
            "devices": devices,
            "pairing": {
                "lanHost": discovery::local_ipv4(),
                "lanPort": self.http_state.info.port,
            },
        })
    }

    pub fn start_pairing(&self, label: &str) -> Result<Value> {
        let start = self.pairing.start(label)?;
        Ok(serde_json::to_value(start)?)
    }

    pub fn cancel_pairing(&self) {
        self.pairing.cancel();
    }

    pub fn devices(&self) -> Vec<DeviceRecord> {
        self.devices.lock().map(|d| d.clone()).unwrap_or_default()
    }

    /// Registers a freshly paired phone on the relay and in the local registry.
    pub fn authorize_client(&self, outcome: crate::relay_pairing::PairOutcome) {
        let id = hex(&outcome.client_id);
        if let Ok(mut devices) = self.devices.lock() {
            devices.retain(|device| device.id != id && device.fingerprint != outcome.fingerprint);
            devices.push(DeviceRecord {
                id: id.clone(),
                label: outcome.label.clone(),
                fingerprint: outcome.fingerprint.clone(),
                created_at: now_ms(),
            });
        }
        let _ = self.save_devices();
        if let Ok(slot) = self.handle.lock() {
            if let Some(handle) = slot.as_ref() {
                handle.authorize_client(outcome.client_id, &outcome.label, &outcome.fingerprint);
            }
        }
    }

    pub fn revoke_device(&self, id_hex: &str) -> Result<()> {
        let mut client_id = [0u8; 16];
        if id_hex.len() == 32 {
            for index in 0..16 {
                client_id[index] =
                    u8::from_str_radix(&id_hex[index * 2..index * 2 + 2], 16).unwrap_or(0);
            }
        }
        if let Ok(mut devices) = self.devices.lock() {
            devices.retain(|device| device.id != id_hex);
        }
        self.save_devices()?;
        if let Ok(slot) = self.handle.lock() {
            if let Some(handle) = slot.as_ref() {
                handle.revoke_client(client_id);
            }
        }
        Ok(())
    }

    /// Regenerates the NAS certificate; the host token rebinds it on reconnect.
    pub fn reset_identity(&self) -> Result<()> {
        self.stop();
        let dir = self.relay_dir();
        let _ = std::fs::remove_file(dir.join("nas.crt"));
        let _ = std::fs::remove_file(dir.join("nas.key"));
        if self.settings().enabled {
            self.start()?;
        }
        Ok(())
    }

    /// Rotates the media key. Everything cached on the relay becomes unreadable
    /// and every paired phone has to pair again.
    pub fn reset_encryption(&self) -> Result<()> {
        self.stop();
        let (encoded, _) = generate_media_key();
        std::fs::create_dir_all(self.relay_dir())?;
        std::fs::write(self.key_path(), encoded)?;
        if let Ok(mut devices) = self.devices.lock() {
            devices.clear();
        }
        self.save_devices()?;
        if self.settings().enabled {
            self.start()?;
            if let Ok(slot) = self.handle.lock() {
                if let Some(handle) = slot.as_ref() {
                    // 旧设备的证书仍然有效，但它们无法解密新内容；同时清空中继授权。
                    handle.reset_clients();
                }
            }
        }
        Ok(())
    }

    pub fn set_pairing_context_now(&self) {
        let settings = self.settings();
        let (key_b64, key_id) = self.media_key().unwrap_or((String::new(), 0));
        self.pairing.set_context(PairContext {
            relay_addr: settings.addr,
            relay_fingerprint: settings.server_fingerprint,
            media_key_b64: key_b64,
            key_id,
            device_id: self.http_state.info.device_id.clone(),
            device_name: self.http_state.info.device_name.clone(),
            lan_host: discovery::local_ipv4(),
            lan_port: self.http_state.info.port,
        });
    }
}

fn hex(id: &[u8; 16]) -> String {
    let mut out = String::with_capacity(32);
    for byte in id {
        out.push_str(&format!("{byte:02x}"));
    }
    out
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

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' && index + 2 < bytes.len() {
            if let Ok(byte) = u8::from_str_radix(&value[index + 1..index + 3], 16) {
                out.push(byte);
                index += 3;
                continue;
            }
        }
        out.push(bytes[index]);
        index += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

fn spawn_agent(config: AgentConfig, backend: Arc<HostBackend>) -> Result<AgentHandle> {
    let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);
    let status = Arc::new(std::sync::RwLock::new(pod_relay_agent::AgentStatus::default()));
    let task_status = status.clone();
    let (control_tx, control_rx) = tokio::sync::mpsc::unbounded_channel();
    let join = tauri::async_runtime::spawn(async move {
        pod_relay_agent::run_with_control(backend, config, stop_rx, task_status, control_rx).await;
    });
    drop(join);
    Ok(AgentHandle::from_parts(stop_tx, status, control_tx))
}
