use anyhow::{anyhow, Result};
use pod_relay_agent::AgentConfig;
use pod_relay_protocol::frame::{encode, FrameDecoder};
use pod_relay_protocol::msg::{Msg, PROTOCOL_VERSION, ROLE_CLIENT};
use pod_relay_protocol::{from_frame, to_frame};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

pub type TlsStream = tokio_rustls::client::TlsStream<TcpStream>;

pub struct ClientLink {
    stream: TlsStream,
    decoder: FrameDecoder,
    buf: Vec<u8>,
    seq: u32,
}

impl ClientLink {
    pub async fn connect(config: &AgentConfig) -> Result<ClientLink> {
        let tls = pod_relay_server::tls::client_config(
            &config.relay.server_fingerprint,
            &config.relay.client_cert,
            &config.relay.client_key,
        )?;
        let connector = tokio_rustls::TlsConnector::from(tls);
        let tcp = TcpStream::connect(config.relay.addr.as_str()).await?;
        let name = rustls::pki_types::ServerName::try_from("pod-relay.local")
            .map_err(|err| anyhow!("server name: {err}"))?
            .to_owned();
        let stream = connector.connect(name, tcp).await?;
        let mut link = ClientLink {
            stream,
            decoder: FrameDecoder::new(),
            buf: vec![0u8; 64 * 1024],
            seq: 1,
        };
        link.write(Msg::Hello {
            role: ROLE_CLIENT,
            ver: PROTOCOL_VERSION,
            key_id: config.key_id().unwrap_or(0),
            caps: 0,
        })
        .await?;
        let (_, hello) = link.recv(Duration::from_secs(10)).await?;
        match hello {
            Msg::HelloOk { .. } => Ok(link),
            other => Err(anyhow!("unexpected handshake reply: {other:?}")),
        }
    }

    async fn write(&mut self, msg: Msg) -> Result<()> {
        let req = self.seq;
        self.seq = self.seq.wrapping_add(1).max(1);
        let frame = to_frame(&msg, req);
        self.stream.write_all(&encode(&frame)).await?;
        Ok(())
    }

    async fn recv(&mut self, timeout: Duration) -> Result<(u32, Msg)> {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            if let Some(frame) = self.decoder.next()? {
                return Ok((frame.req, from_frame(&frame)?));
            }
            match tokio::time::timeout_at(deadline, self.stream.read(&mut self.buf)).await {
                Ok(Ok(0)) => return Err(anyhow!("relay closed connection")),
                Ok(Ok(n)) => self.decoder.push(&self.buf[..n])?,
                Ok(Err(err)) => return Err(err.into()),
                Err(_) => return Err(anyhow!("relay read timeout")),
            }
        }
    }

    /// Sends a request and waits for the frame that carries its request id.
    pub async fn call(&mut self, msg: Msg) -> Result<Msg> {
        self.write(msg).await?;
        let want = self.seq.wrapping_sub(1).max(1);
        loop {
            let (req, reply) = self.recv(Duration::from_secs(30)).await?;
            if req == want {
                return Ok(reply);
            }
        }
    }

    pub async fn request(&mut self, msg: Msg, timeout: Duration) -> Result<Msg> {
        self.write(msg).await?;
        let want = self.seq.wrapping_sub(1).max(1);
        loop {
            let (req, reply) = self.recv(timeout).await?;
            if req == want {
                return Ok(reply);
            }
        }
    }
}
