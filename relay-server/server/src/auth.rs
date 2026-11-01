//! Dynamic endpoint authorization. The relay only stores fingerprints of hosts
//! and clients that the NAS explicitly authorized; no certificate material is
//! kept here.

use anyhow::{Context, Result};
use base64::Engine;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientRecord {
    pub id: String,
    pub label: String,
    pub fingerprint: String,
    #[serde(default)]
    pub created_at: i64,
    #[serde(default)]
    pub last_seen_at: i64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct AuthFile {
    host: Option<String>,
    host_token: Option<String>,
    key_id: u64,
    clients: Vec<ClientRecord>,
}

pub struct AuthStore {
    path: PathBuf,
    inner: Mutex<AuthFile>,
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

pub fn token_hash(token: &str) -> String {
    let digest = Sha256::digest(token.as_bytes());
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

impl AuthStore {
    pub fn open(dir: &Path) -> Result<AuthStore> {
        fs::create_dir_all(dir)?;
        let path = dir.join("auth.json");
        let file = fs::read_to_string(&path)
            .ok()
            .and_then(|text| serde_json::from_str::<AuthFile>(&text).ok())
            .unwrap_or_default();
        Ok(AuthStore {
            path,
            inner: Mutex::new(file),
        })
    }

    fn save_locked(&self, file: &AuthFile) -> Result<()> {
        fs::write(&self.path, serde_json::to_vec_pretty(file)?)
            .with_context(|| format!("write {}", self.path.display()))
    }

    /// Returns the plaintext token the first time it is created.
    pub fn ensure_token(&self) -> Result<Option<String>> {
        let mut file = self.inner.lock().unwrap();
        if file.host_token.is_some() {
            return Ok(None);
        }
        let mut raw = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut raw);
        let token = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(raw);
        file.host_token = Some(token.clone());
        self.save_locked(&file)?;
        Ok(Some(token))
    }

    pub fn host_token(&self) -> Option<String> {
        self.inner.lock().ok()?.host_token.clone()
    }

    pub fn host_allowed(&self, fingerprint: &str, token: Option<&str>) -> bool {
        let file = match self.inner.lock() {
            Ok(file) => file,
            Err(_) => return false,
        };
        if let Some(expected) = file.host_token.as_deref() {
            if let Some(supplied) = token {
                if constant_eq(expected.as_bytes(), supplied.as_bytes()) {
                    return true;
                }
            }
        }
        file.host.as_deref() == Some(fingerprint)
    }

    pub fn bind_host(&self, fingerprint: &str) -> Result<()> {
        let mut file = self.inner.lock().unwrap();
        if file.host.as_deref() != Some(fingerprint) {
            eprintln!("[pod-relay] host bound to {fingerprint}");
        }
        file.host = Some(fingerprint.to_string());
        self.save_locked(&file)
    }

    pub fn host_fingerprint(&self) -> Option<String> {
        self.inner.lock().ok()?.host.clone()
    }

    pub fn client_allowed(&self, fingerprint: &str) -> bool {
        let mut file = match self.inner.lock() {
            Ok(file) => file,
            Err(_) => return false,
        };
        let found = file
            .clients
            .iter_mut()
            .find(|client| client.fingerprint == fingerprint);
        match found {
            Some(client) => {
                client.last_seen_at = now_secs();
                let snapshot = file.clone();
                let _ = self.save_locked(&snapshot);
                true
            }
            None => false,
        }
    }

    pub fn authorize(&self, id: [u8; 16], label: &str, fingerprint: &str) -> Result<()> {
        let id = hex(&id);
        let mut file = self.inner.lock().unwrap();
        if let Some(existing) = file.clients.iter_mut().find(|client| client.id == id) {
            existing.label = label.to_string();
            existing.fingerprint = fingerprint.to_string();
        } else {
            file.clients.retain(|client| client.fingerprint != fingerprint);
            file.clients.push(ClientRecord {
                id,
                label: label.to_string(),
                fingerprint: fingerprint.to_string(),
                created_at: now_secs(),
                last_seen_at: 0,
            });
        }
        self.save_locked(&file)
    }

    pub fn revoke(&self, id: &[u8; 16]) -> Result<()> {
        let id = hex(id);
        let mut file = self.inner.lock().unwrap();
        file.clients.retain(|client| client.id != id);
        self.save_locked(&file)
    }

    pub fn revoke_all(&self) -> Result<()> {
        let mut file = self.inner.lock().unwrap();
        file.clients.clear();
        self.save_locked(&file)
    }

    pub fn key_id(&self) -> u64 {
        self.inner.lock().map(|file| file.key_id).unwrap_or(0)
    }

    pub fn set_key_id(&self, key_id: u64) -> Result<()> {
        let mut file = self.inner.lock().unwrap();
        file.key_id = key_id;
        self.save_locked(&file)
    }
}

pub fn hex(id: &[u8; 16]) -> String {
    let mut out = String::with_capacity(32);
    for byte in id {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

fn constant_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}
