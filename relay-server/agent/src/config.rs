use anyhow::{anyhow, Result};
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

fn default_poll() -> u64 {
    60
}
fn default_readahead() -> u32 {
    8
}
fn default_reads() -> usize {
    4
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RelayEndpoint {
    pub addr: String,
    pub server_fingerprint: String,
    pub client_cert: PathBuf,
    pub client_key: PathBuf,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MediaConfig {
    pub key_b64: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushConfig {
    #[serde(default = "default_readahead")]
    pub readahead_fragments: u32,
    #[serde(default = "default_reads")]
    pub max_inflight_reads: usize,
}

impl Default for PushConfig {
    fn default() -> Self {
        PushConfig {
            readahead_fragments: default_readahead(),
            max_inflight_reads: default_reads(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SnapshotConfig {
    #[serde(default = "default_poll")]
    pub poll_sec: u64,
}

impl Default for SnapshotConfig {
    fn default() -> Self {
        SnapshotConfig {
            poll_sec: default_poll(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentConfig {
    pub relay: RelayEndpoint,
    pub media: MediaConfig,
    #[serde(default)]
    pub push: PushConfig,
    #[serde(default)]
    pub snapshot: SnapshotConfig,
}

impl AgentConfig {
    pub fn media_key(&self) -> Result<[u8; 32]> {
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(self.media.key_b64.trim())
            .map_err(|err| anyhow!("media key is not valid base64: {err}"))?;
        if bytes.len() != 32 {
            return Err(anyhow!("media key must be 32 bytes, got {}", bytes.len()));
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&bytes);
        Ok(key)
    }

    pub fn key_id(&self) -> Result<u64> {
        Ok(pod_relay_protocol::crypto::key_id(&self.media_key()?))
    }
}

/// Generated once by the desktop app and shown in its relay panel.
pub fn generate_media_key() -> (String, u64) {
    use rand::RngCore;
    let mut key = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut key);
    let encoded = base64::engine::general_purpose::STANDARD.encode(key);
    (encoded, pod_relay_protocol::crypto::key_id(&key))
}
