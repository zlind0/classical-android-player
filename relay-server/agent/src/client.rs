//! Outbound TLS link from the NAS to the relay, with certificate pinning.

use crate::config::AgentConfig;
use anyhow::{anyhow, Context, Result};
use pod_relay_protocol::frame::{encode, FrameDecoder};
use pod_relay_protocol::msg::{Msg, PROTOCOL_VERSION, ROLE_HOST};
use pod_relay_protocol::{from_frame, to_frame};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt, ReadHalf, WriteHalf};
use tokio::net::TcpStream;
use tokio::sync::Mutex;

pub type TlsStream = tokio_rustls::client::TlsStream<TcpStream>;

#[derive(Debug, Clone, Copy)] 
pub struct HostHello {
    pub session_id: u64,
    pub host_online: bool,
    pub lib_version: i64,
    pub store_used: u64,
    pub store_cap: u64,
}

pub struct HostLink {
    reader: ReadHalf<TlsStream>,
    pub writer: Arc<Mutex<WriteHalf<TlsStream>>>,
    decoder: FrameDecoder,
    buf: Vec<u8>,
    pub hello: HostHello,
    pub key_id: u64,
}

impl HostLink {
    pub async fn connect(config: &AgentConfig, key_id: u64) -> Result<HostLink> {
        let tls = pod_relay_server::tls::client_config(
            &config.relay.server_fingerprint,
            &config.relay.client_cert,
            &config.relay.client_key,
        )?;
        let connector = tokio_rustls::TlsConnector::from(tls);
        let tcp = TcpStream::connect(config.relay.addr.as_str())
            .await
            .with_context(|| format!("connect relay {}", config.relay.addr))?;
        tcp.set_nodelay(true).ok();
        let name = rustls::pki_types::ServerName::try_from("pod-relay.local")
            .map_err(|err| anyhow!("invalid server name: {err}"))?
            .to_owned();
        let stream = connector
            .connect(name, tcp)
            .await
            .context("relay TLS handshake")?;

        let (reader, writer) = tokio::io::split(stream);
        let writer = Arc::new(Mutex::new(writer));
        let mut link = HostLink {
            reader,
            writer,
            decoder: FrameDecoder::new(),
            buf: vec![0u8; 64 * 1024],
            hello: HostHello {
                session_id: 0,
                host_online: false,
                lib_version: 0,
                store_used: 0,
                store_cap: 0,
            },
            key_id,
        };

        link.send(Msg::Hello {
            role: ROLE_HOST,
            ver: PROTOCOL_VERSION,
            key_id,
            caps: 0,
            auth: config.relay.host_token.as_bytes().to_vec(),
        })
        .await?;

        loop {
            let (_, msg) = link.recv(Duration::from_secs(15)).await?;
            match msg {
                Msg::HelloOk {
                    session_id,
                    host_online,
                    host_lib_version,
                    store_used,
                    store_cap,
                    ..
                } => {
                    link.hello = HostHello {
                        session_id,
                        host_online,
                        lib_version: host_lib_version,
                        store_used,
                        store_cap,
                    };
                    return Ok(link);
                }
                Msg::Error { code, msg } => {
                    return Err(anyhow!("relay rejected host: {code} {msg}"));
                }
                _ => continue,
            }
        }
    }

    pub async fn send(&self, msg: Msg) -> Result<()> {
        let frame = to_frame(&msg, 0);
        let bytes = encode(&frame);
        let mut writer = self.writer.lock().await;
        writer.write_all(&bytes).await?;
        Ok(())
    }

    pub async fn send_req(&self, req: u32, msg: Msg) -> Result<()> {
        let frame = to_frame(&msg, req);
        let bytes = encode(&frame);
        let mut writer = self.writer.lock().await;
        writer.write_all(&bytes).await?;
        Ok(())
    }

    pub async fn recv(&mut self, timeout: Duration) -> Result<(u32, Msg)> {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            if let Some(frame) = self.decoder.next()? {
                return Ok((frame.req, from_frame(&frame)?));
            }
            let read = tokio::time::timeout_at(deadline, self.reader.read(&mut self.buf)).await;
            match read {
                Ok(Ok(0)) => return Err(anyhow!("relay closed the connection")),
                Ok(Ok(n)) => self.decoder.push(&self.buf[..n])?,
                Ok(Err(err)) => return Err(err.into()),
                Err(_) => return Err(anyhow!("relay read timeout")),
            }
        }
    }
}
