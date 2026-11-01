use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

fn default_listen() -> String {
    "0.0.0.0:21443".to_string()
}
fn default_capacity() -> u64 {
    20 * 1024 * 1024 * 1024
}
fn default_ttl_days() -> u64 {
    14
}
fn default_max_frame() -> usize {
    4 * 1024 * 1024
}
fn default_max_sessions() -> usize {
    8
}
fn default_session_idle() -> u64 {
    90
}
fn default_want_per_sec() -> u32 {
    40
}
fn default_inflight_client() -> usize {
    32
}
fn default_inflight_global() -> usize {
    128
}
fn default_high() -> f64 {
    0.85
}
fn default_full() -> f64 {
    0.98
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TlsConfig {
    pub cert: PathBuf,
    pub key: PathBuf,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoreConfig {
    pub dir: PathBuf,
    #[serde(default = "default_capacity")]
    pub capacity_bytes: u64,
    #[serde(default = "default_ttl_days")]
    pub file_ttl_days: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LimitsConfig {
    #[serde(default = "default_max_frame")]
    pub max_frame: usize,
    #[serde(default = "default_max_sessions")]
    pub max_sessions: usize,
    #[serde(default = "default_session_idle")]
    pub session_idle_sec: u64,
    #[serde(default = "default_want_per_sec")]
    pub want_per_sec: u32,
    #[serde(default = "default_inflight_client")]
    pub inflight_per_client: usize,
    #[serde(default = "default_inflight_global")]
    pub inflight_global: usize,
}

impl Default for LimitsConfig {
    fn default() -> Self {
        LimitsConfig {
            max_frame: default_max_frame(),
            max_sessions: default_max_sessions(),
            session_idle_sec: default_session_idle(),
            want_per_sec: default_want_per_sec(),
            inflight_per_client: default_inflight_client(),
            inflight_global: default_inflight_global(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WatermarkConfig {
    #[serde(default = "default_high")]
    pub high: f64,
    #[serde(default = "default_full")]
    pub full: f64,
}

impl Default for WatermarkConfig {
    fn default() -> Self {
        WatermarkConfig {
            high: default_high(),
            full: default_full(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    #[serde(default = "default_listen")]
    pub listen: String,
    /// Address advertised in the bootstrap line; defaults to `listen`.
    #[serde(default)]
    pub public_addr: Option<String>,
    /// Optional explicit certificate paths; generated on first start when absent.
    #[serde(default)]
    pub tls: Option<TlsConfig>,
    pub store: StoreConfig,
    #[serde(default)]
    pub limits: LimitsConfig,
    #[serde(default)]
    pub watermark: WatermarkConfig,
}

impl Config {
    pub fn load(path: &Path) -> Result<Config> {
        let text = std::fs::read_to_string(path)
            .with_context(|| format!("read config {}", path.display()))?;
        let config: Config = serde_json::from_str(&text).context("parse relay config")?;
        Ok(config)
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn example_config_parses() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("relay.json.example");
        let config = super::Config::load(&path).expect("relay.json.example must parse");
        assert_eq!(config.listen, "0.0.0.0:21443");
        assert_eq!(config.public_addr.as_deref(), Some("relay.example.com:21443"));
        assert_eq!(config.store.capacity_bytes, 20 * 1024 * 1024 * 1024);
        assert!(config.tls.is_none());
    }
}
