use anyhow::{anyhow, Result};
use pod_relay_agent::config::{MediaConfig, RelayEndpoint, AgentConfig, PushConfig, SnapshotConfig};
use pod_relay_agent::{LibraryBackend, LiveRequest, LiveResponse, Stat};
use pod_relay_protocol::crypto::{decrypt_fragment, key_id, media_key, meta_key};
use pod_relay_protocol::file_id::file_id;
use pod_relay_protocol::msg::{Msg, PROTOCOL_VERSION};
use pod_relay_protocol::snapshot::{unpack, SnapshotHeader};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use crate::client::ClientLink;

struct MemoryBackend {
    version: i64,
    snapshot: Vec<u8>,
    files: HashMap<String, Vec<u8>>,
}

#[async_trait::async_trait]
impl LibraryBackend for MemoryBackend {
    async fn library_version(&self) -> Result<i64> {
        Ok(self.version)
    }
    async fn snapshot_json(&self) -> Result<Vec<u8>> {
        Ok(self.snapshot.clone())
    }
    async fn stat(&self, path: &str) -> Result<Stat> {
        let data = self
            .files
            .get(path)
            .ok_or_else(|| anyhow!("unknown route {path}"))?;
        Ok(Stat {
            total: data.len() as u64,
            mime: "audio/flac".to_string(),
        })
    }
    async fn fetch_range(&self, path: &str, start: u64, end: u64) -> Result<Vec<u8>> {
        let data = self
            .files
            .get(path)
            .ok_or_else(|| anyhow!("unknown route {path}"))?;
        let start = start as usize;
        let end = ((end as usize) + 1).min(data.len());
        if start >= end {
            return Err(anyhow!("bad range"));
        }
        Ok(data[start..end].to_vec())
    }
    async fn live(&self, _req: LiveRequest) -> Result<LiveResponse> {
        Ok(LiveResponse {
            status: 200,
            body: b"{\"books\":[]}".to_vec(),
        })
    }
}

fn fingerprint(path: &PathBuf) -> Result<String> {
    pod_relay_server::cert::fingerprint_file(path)
}

async fn wait_snapshot(client: &mut ClientLink) -> Result<SnapshotHeader> {
    for _ in 0..100 {
        match client.call(Msg::LibVersionReq).await? {
            Msg::LibVersionOk {
                version,
                key_id,
                plain_len,
                gz_len,
                chunks,
                salt,
                ..
            } if version > 0 => {
                return Ok(SnapshotHeader {
                    version,
                    key_id,
                    plain_len,
                    gz_len,
                    chunks,
                    salt,
                })
            }
            _ => tokio::time::sleep(Duration::from_millis(150)).await,
        }
    }
    Err(anyhow!("snapshot never arrived"))
}

pub async fn run() -> Result<()> {
    let base = std::env::current_dir()?.join("tmp").join(format!(
        "selftest-{}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0)
    ));
    std::fs::create_dir_all(&base)?;
    println!("[selftest] workdir {}", base.display());

    let relay_crt =
        pod_relay_server::cert::generate("relay", "relay.local", &["dns:relay.local".into()], &base.join("relay"))?;
    pod_relay_server::cert::generate("host", "nas", &["dns:nas".into()], &base.join("nas"))?;
    pod_relay_server::cert::generate("client", "phone", &["dns:phone".into()], &base.join("phone"))?;

    let relay_fp = fingerprint(&relay_crt)?;
    let nas_fp = fingerprint(&base.join("nas.crt"))?;
    let phone_fp = fingerprint(&base.join("phone.crt"))?;

    let master = [3u8; 32];
    let key_b64 = {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.encode(master)
    };

    let config = pod_relay_server::Config {
        listen: "127.0.0.1:0".to_string(),
        tls: pod_relay_server::config::TlsConfig {
            cert: base.join("relay.crt"),
            key: base.join("relay.key"),
        },
        auth: pod_relay_server::config::AuthConfig {
            host_fingerprints: vec![nas_fp],
            client_fingerprints: vec![phone_fp],
        },
        store: pod_relay_server::config::StoreConfig {
            dir: base.join("store"),
            capacity_bytes: 64 * 1024 * 1024,
            file_ttl_days: 1,
        },
        limits: Default::default(),
        watermark: Default::default(),
    };
    let relay = pod_relay_server::Relay::bind(config).await?;
    let addr = relay.local_addr()?.to_string();
    tokio::spawn(async move {
        let _ = relay.serve().await;
    });

    let payload: Vec<u8> = (0..(1024 * 1024 + 37u32))
        .map(|i| (i % 251) as u8)
        .collect();
    let library = serde_json::json!({
        "deviceId": "nas-selftest",
        "deviceName": "Selftest NAS",
        "generatedAt": 1,
        "libraryUpdatedAt": 1,
        "musicPathOrder": false,
        "music": [{
            "id": "7",
            "title": "Selftest",
            "path": "/music/7.flac"
        }],
        "ebookRoots": [],
        "ebooks": [],
        "audiobooks": []
    });
    let snapshot = serde_json::to_vec(&library)?;
    let mut files = HashMap::new();
    files.insert("/media/music?id=7".to_string(), payload.clone());
    let backend = Arc::new(MemoryBackend {
        version: 1,
        snapshot: snapshot.clone(),
        files,
    });

    let agent_config = AgentConfig {
        relay: RelayEndpoint {
            addr: addr.clone(),
            server_fingerprint: relay_fp,
            client_cert: base.join("nas.crt"),
            client_key: base.join("nas.key"),
        },
        media: MediaConfig { key_b64: key_b64.clone() },
        push: PushConfig::default(),
        snapshot: SnapshotConfig { poll_sec: 60 },
    };
    let client_config = AgentConfig {
        relay: RelayEndpoint {
            addr: addr.clone(),
            server_fingerprint: agent_config.relay.server_fingerprint.clone(),
            client_cert: base.join("phone.crt"),
            client_key: base.join("phone.key"),
        },
        media: MediaConfig {
            key_b64: key_b64.clone(),
        },
        push: PushConfig::default(),
        snapshot: SnapshotConfig { poll_sec: 60 },
    };
    let agent = pod_relay_agent::spawn(backend, agent_config);
    for _ in 0..50 {
        if agent.status().connected {
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    println!("[selftest] agent status {:?}", agent.status());

    let mut client = loop {
        match ClientLink::connect(&client_config).await {
            Ok(client) => break client,
            Err(_) => tokio::time::sleep(Duration::from_millis(100)).await,
        }
    };

    let header = wait_snapshot(&mut client).await?;
    let version = header.version;
    assert_eq!(version, 1);
    let mut chunk_data = Vec::new();
    for index in 0..header.chunks {
        match client
            .call(Msg::LibSnapshotReq {
                version,
                chunk_index: index,
            })
            .await?
        {
            Msg::LibSnapshotChunk { data, .. } => chunk_data.push(data),
            other => return Err(anyhow!("unexpected snapshot reply: {other:?}")),
        }
    }
    let meta = meta_key(&master);
    assert_eq!(header.key_id, key_id(&master));
    let decrypted = unpack(&meta, &header, &chunk_data)?;
    assert_eq!(decrypted, snapshot, "snapshot mismatch");
    println!("[selftest] snapshot roundtrip ok ({} bytes)", decrypted.len());

    let id = file_id("music", "7");
    let stat = match client.call(Msg::StatReq { file_id: id }).await? {
        Msg::StatOk {
            total, count, salt, ..
        } => (total, count, salt),
        other => return Err(anyhow!("unexpected stat reply: {other:?}")),
    };
    assert_eq!(stat.0, payload.len() as u64);
    println!("[selftest] stat ok: total={} fragments={}", stat.0, stat.1);

    let key = media_key(&master);
    let mut assembled = Vec::new();
    for index in 0..stat.1 {
        let (nonce, ct) = loop {
            match client
                .call(Msg::FragReq {
                    file_id: id,
                    index,
                })
                .await?
            {
                Msg::FragData {
                    nonce, ct, ..
                } => break (nonce, ct),
                Msg::Pending { retry_ms, .. } => {
                    tokio::time::sleep(Duration::from_millis(retry_ms.max(50) as u64)).await;
                }
                other => return Err(anyhow!("unexpected fragment reply: {other:?}")),
            }
        };
        let plain = decrypt_fragment(&key, &id, index, &nonce, &ct)?;
        assembled.extend_from_slice(&plain);
    }
    assert_eq!(assembled, payload, "media mismatch");
    println!("[selftest] fragment roundtrip ok ({} bytes)", assembled.len());

    let stats = match client.call(Msg::AdminStatsReq).await? {
        Msg::AdminStatsOk { .. } => "ok",
        _ => "unexpected",
    };
    println!("[selftest] admin stats {stats}");

    agent.stop();
    tokio::time::sleep(Duration::from_millis(100)).await;
    println!("[selftest] protocol v{PROTOCOL_VERSION} passed");
    Ok(())
}
