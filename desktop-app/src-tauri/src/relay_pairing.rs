//! LAN pairing: the QR only carries a short-lived token plus the NAS' LAN
//! address. Certificates, keys and the media key are handed over on the LAN.

use anyhow::{anyhow, Result};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use tokio::sync::mpsc;

#[derive(Debug, Clone, Default)]
pub struct PairContext {
    pub relay_addr: String,
    pub relay_fingerprint: String,
    pub media_key_b64: String,
    pub key_id: u64,
    pub device_id: String,
    pub device_name: String,
    pub lan_host: String,
    pub lan_port: u16,
}

#[derive(Debug, Clone)]
pub struct PairOutcome {
    pub client_id: [u8; 16],
    pub label: String,
    pub fingerprint: String,
}

struct Pending {
    token: String,
    client_id: [u8; 16],
    fingerprint: String,
    cert_pem: String,
    key_pem: String,
    label: String,
    expires_at: i64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairStart {
    pub uri: String,
    pub svg: String,
    pub label: String,
    pub expires_at: i64,
}

/// Remote pairing is a self-contained string: the phone can import it without
/// sharing a LAN with the NAS. The payload is gzip-compressed JSON encoded as
/// URL-safe base64, so it survives chat apps and QR scanners unchanged.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RemotePairStart {
    pub code: String,
    pub label: String,
    pub expires_at: i64,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct RemoteBundle {
    version: u8,
    relay: RelayInfo,
    key_id: u64,
    media_key_b64: String,
    cert_pem: String,
    key_pem: String,
    device_id: String,
    device_name: String,
    expires_at: i64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairRequest {
    pub token: String,
    #[serde(default)]
    pub device_name: String,
    #[serde(default)]
    pub platform: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairResponse {
    pub version: u8,
    pub relay: RelayInfo,
    pub key_id: u64,
    pub media_key_b64: String,
    pub cert_pem: String,
    pub key_pem: String,
    pub device_id: String,
    pub device_name: String,
}

#[derive(Debug, Serialize)]
pub struct RelayInfo {
    pub addr: String,
    pub fingerprint: String,
}

pub struct PairingState {
    inner: Mutex<Option<Pending>>,
    context: Mutex<PairContext>,
    events: mpsc::UnboundedSender<PairOutcome>,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn random_token() -> String {
    use base64::Engine;
    let mut raw = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut raw);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(raw)
}

pub fn random_client_id() -> [u8; 16] {
    let mut raw = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut raw);
    raw
}

impl PairingState {
    pub fn new(events: mpsc::UnboundedSender<PairOutcome>) -> PairingState {
        PairingState {
            inner: Mutex::new(None),
            context: Mutex::new(PairContext::default()),
            events,
        }
    }

    pub fn set_context(&self, context: PairContext) {
        if let Ok(mut slot) = self.context.lock() {
            *slot = context;
        }
    }

    pub fn context(&self) -> PairContext {
        self.context.lock().map(|c| c.clone()).unwrap_or_default()
    }

    /// Creates a pairing offer. Requires the relay to be configured and online.
    pub fn start(&self, label: &str) -> Result<PairStart> {
        let context = self.context();
        if context.relay_addr.is_empty() || context.relay_fingerprint.is_empty() {
            return Err(anyhow!("中继尚未配置完成"));
        }
        if context.lan_host.is_empty() || context.lan_port == 0 {
            return Err(anyhow!("局域网地址尚未就绪"));
        }
        let label = if label.trim().is_empty() {
            "Aurora 手机".to_string()
        } else {
            label.trim().to_string()
        };
        let (cert_pem, key_pem, fingerprint) =
            pod_relay_server::cert::generate_pem(&format!("aurora-{}", random_token()))?;
        let token = random_token();
        let expires_at = now_ms() + 10 * 60 * 1000;
        let client_id = random_client_id();
        let uri = format!(
            "podrelay://pair?v=2&h={}&p={}&t={}&n={}&id={}",
            url_encode(&context.lan_host),
            context.lan_port,
            url_encode(&token),
            url_encode(&context.device_name),
            url_encode(&context.device_id),
        );
        let svg = qrcode::QrCode::new(uri.as_bytes())
            .map_err(|err| anyhow!("二维码生成失败: {err}"))?
            .render::<qrcode::render::svg::Color>()
            .min_dimensions(220, 220)
            .quiet_zone(true)
            .build();
        if let Ok(mut slot) = self.inner.lock() {
            *slot = Some(Pending {
                token,
                client_id,
                fingerprint,
                cert_pem,
                key_pem,
                label: label.clone(),
                expires_at,
            });
        }
        Ok(PairStart {
            uri,
            svg,
            label,
            expires_at,
        })
    }

    /// Creates a LAN-free pairing bundle. Unlike [start], there is no second
    /// HTTP step: the returned string already contains the client certificate,
    /// private key, relay pin and media key. The TTL is embedded in the bundle
    /// and checked by the phone when it is imported.
    pub fn start_remote(&self, label: &str, ttl_seconds: u64) -> Result<RemotePairStart> {
        let context = self.context();
        if context.relay_addr.is_empty() || context.relay_fingerprint.is_empty() {
            return Err(anyhow!("中继尚未配置完成"));
        }
        let label = if label.trim().is_empty() {
            "Aurora 手机".to_string()
        } else {
            label.trim().to_string()
        };
        let (cert_pem, key_pem, fingerprint) =
            pod_relay_server::cert::generate_pem(&format!("aurora-{}", random_token()))?;
        let ttl = ttl_seconds.clamp(60, 7 * 24 * 60 * 60);
        let expires_at = now_ms() + ttl as i64 * 1000;
        let bundle = RemoteBundle {
            version: 2,
            relay: RelayInfo {
                addr: context.relay_addr.clone(),
                fingerprint: context.relay_fingerprint.clone(),
            },
            key_id: context.key_id,
            media_key_b64: context.media_key_b64.clone(),
            cert_pem: cert_pem.clone(),
            key_pem: key_pem.clone(),
            device_id: context.device_id.clone(),
            device_name: context.device_name.clone(),
            expires_at,
        };
        let json = serde_json::to_vec(&bundle)?;
        let mut encoder =
            flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
        std::io::Write::write_all(&mut encoder, &json)?;
        let gz = encoder.finish()?;
        use base64::Engine;
        let encoded = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(gz);
        let code = format!("podrelay://remote?v=1&d={encoded}");
        let _ = self.events.send(PairOutcome {
            client_id: random_client_id(),
            label: label.clone(),
            fingerprint,
        });
        eprintln!("[pod-relay] device paired remotely: {label} (ttl {ttl}s)");
        Ok(RemotePairStart {
            code,
            label,
            expires_at,
        })
    }

    pub fn cancel(&self) {
        if let Ok(mut slot) = self.inner.lock() {
            *slot = None;
        }
    }

    /// Called by the LAN endpoint. Consumes the pending offer on success.
    pub fn fulfill(&self, request: &PairRequest) -> Result<PairResponse> {
        let context = self.context();
        let mut slot = self
            .inner
            .lock()
            .map_err(|_| anyhow!("配对状态不可用"))?;
        let pending = slot.as_ref().ok_or_else(|| anyhow!("没有待使用的配对码"))?;
        if now_ms() > pending.expires_at {
            *slot = None;
            return Err(anyhow!("配对码已过期，请重新生成"));
        }
        if request.token != pending.token {
            return Err(anyhow!("配对码不正确"));
        }
        let pending = slot.take().expect("checked above");
        let label = if request.device_name.trim().is_empty() {
            pending.label.clone()
        } else {
            request.device_name.trim().to_string()
        };
        let response = PairResponse {
            version: 2,
            relay: RelayInfo {
                addr: context.relay_addr.clone(),
                fingerprint: context.relay_fingerprint.clone(),
            },
            key_id: context.key_id,
            media_key_b64: context.media_key_b64.clone(),
            cert_pem: pending.cert_pem.clone(),
            key_pem: pending.key_pem.clone(),
            device_id: context.device_id.clone(),
            device_name: context.device_name.clone(),
        };
        let _ = self.events.send(PairOutcome {
            client_id: pending.client_id,
            label: label.clone(),
            fingerprint: pending.fingerprint.clone(),
        });
        eprintln!(
            "[pod-relay] device paired over LAN: {} ({})",
            label, pending.fingerprint
        );
        Ok(response)
    }
}

fn url_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.as_bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}
