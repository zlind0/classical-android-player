//! Pushes the encrypted library snapshot to the relay.

use crate::client::HostLink;
use crate::config::AgentConfig;
use anyhow::Result;
use pod_relay_protocol::crypto::meta_key;
use pod_relay_protocol::msg::Msg;
use pod_relay_protocol::snapshot::pack;

pub async fn push(link: &HostLink, config: &AgentConfig, version: i64, json: &[u8]) -> Result<()> {
    let master = config.media_key()?;
    let key = meta_key(&master);
    let key_id = pod_relay_protocol::crypto::key_id(&master);
    let (header, chunks) = pack(&key, version, key_id, json)?;
    link.send(Msg::SnapshotBegin {
        version: header.version,
        key_id: header.key_id,
        plain_len: header.plain_len,
        gz_len: header.gz_len,
        chunks: header.chunks,
        salt: header.salt,
    })
    .await?;
    for (index, chunk) in chunks.iter().enumerate() {
        link.send(Msg::SnapshotChunk {
            version: header.version,
            chunk_index: index as u32,
            data: chunk.clone(),
        })
        .await?;
    }
    link.send(Msg::SnapshotEnd {
        version: header.version,
        ok: true,
    })
    .await?;
    eprintln!(
        "[pod-relay] pushed snapshot version {version} ({} chunks)",
        header.chunks
    );
    Ok(())
}
