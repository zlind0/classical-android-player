//! Pushes the encrypted library snapshot to the relay.

use crate::client::HostLink;
use crate::config::AgentConfig;
use anyhow::Result;
use pod_relay_protocol::crypto::meta_key;
use pod_relay_protocol::msg::Msg;
use pod_relay_protocol::snapshot::pack;
use pod_relay_server::log::bytes;

pub async fn push(
    link: &HostLink,
    config: &AgentConfig,
    scope: u64,
    version: i64,
    json: &[u8],
) -> Result<()> {
    let master = config.media_key()?;
    let key = meta_key(&master);
    let key_id = pod_relay_protocol::crypto::key_id(&master);
    let (header, chunks) = pack(&key, version, key_id, json)?;
    link.send(Msg::SnapshotBegin {
        scope,
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
    let wire: u64 = chunks.iter().map(|chunk| chunk.len() as u64).sum();
    eprintln!(
        "[pod-relay] pushed snapshot scope {scope:016x} v{version}: {} plain -> {} compressed in {} chunk(s), {} on the wire",
        bytes(header.plain_len),
        bytes(header.gz_len),
        header.chunks,
        bytes(wire)
    );
    Ok(())
}
