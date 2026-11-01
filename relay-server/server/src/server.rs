//! Relay server: TLS accept loop, session handling, fragment dispatch.

use crate::config::Config;
use crate::snapshot::SnapshotStore;
use crate::store::{FragmentStore, StatInfo};
use crate::tls;
use anyhow::{anyhow, Context, Result};
use pod_relay_protocol::frame::{encode, FrameDecoder};
use pod_relay_protocol::msg::{err, FileId, Msg, ROLE_CLIENT, ROLE_HOST};
use pod_relay_protocol::snapshot::SnapshotHeader;
use pod_relay_protocol::{from_frame, to_frame};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;

type Outbox = mpsc::UnboundedSender<(u32, Msg)>;

struct NasLink {
    session_id: u64,
    tx: Outbox,
}

struct PendingStat {
    at: i64,
    client: Outbox,
    client_req: u32,
    file_id: FileId,
}

struct PendingLive {
    at: i64,
    client: Outbox,
    client_req: u32,
}

pub struct Shared {
    pub config: Config,
    pub store: Arc<FragmentStore>,
    pub snapshots: Arc<SnapshotStore>,
    nas: Mutex<Option<NasLink>>,
    pending_stats: Mutex<HashMap<u32, PendingStat>>,
    pending_live: Mutex<HashMap<u32, PendingLive>>,
    seq: AtomicU32,
    sessions: AtomicU32,
    started_ms: i64,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn now_secs() -> i64 {
    now_ms() / 1000
}

impl Shared {
    fn host_online(&self) -> bool {
        self.nas.lock().map(|nas| nas.is_some()).unwrap_or(false)
    }

    fn host_lib_version(&self) -> i64 {
        self.snapshots.latest().map(|h| h.version).unwrap_or(0)
    }

    fn pressure(&self) -> u8 {
        let cap = self.store.capacity();
        if cap == 0 {
            return 2;
        }
        let ratio = self.store.used() as f64 / cap as f64;
        if ratio >= self.config.watermark.full {
            2
        } else if ratio >= self.config.watermark.high {
            1
        } else {
            0
        }
    }

    fn send_to_host(&self, msg: Msg) {
        if let Ok(nas) = self.nas.lock() {
            if let Some(link) = nas.as_ref() {
                let _ = link.tx.send((0, msg));
            }
        }
    }

    fn host_tx(&self) -> Option<Outbox> {
        self.nas
            .lock()
            .ok()
            .and_then(|nas| nas.as_ref().map(|link| link.tx.clone()))
    }
}

pub struct Relay {
    shared: Arc<Shared>,
    listener: TcpListener,
    acceptor: tokio_rustls::TlsAcceptor,
}

impl Relay {
    pub async fn bind(config: Config) -> Result<Relay> {
        let mut allowed = std::collections::HashSet::new();
        allowed.extend(config.auth.host_fingerprints.iter().cloned());
        allowed.extend(config.auth.client_fingerprints.iter().cloned());
        let tls_config = tls::server_config(&config.tls.cert, &config.tls.key, allowed)?;

        std::fs::create_dir_all(config.store.dir.join("frag"))?;
        let store = Arc::new(FragmentStore::open(
            &config.store.dir,
            config.store.capacity_bytes,
        )?);
        let snapshots = Arc::new(SnapshotStore::open(&config.store.dir)?);

        let listener = TcpListener::bind(&config.listen)
            .await
            .with_context(|| format!("bind {}", config.listen))?;
        eprintln!("[pod-relay] listening on {}", config.listen);

        Ok(Relay {
            shared: Arc::new(Shared {
                config,
                store,
                snapshots,
                nas: Mutex::new(None),
                pending_stats: Mutex::new(HashMap::new()),
                pending_live: Mutex::new(HashMap::new()),
                seq: AtomicU32::new(1),
                sessions: AtomicU32::new(0),
                started_ms: now_ms(),
            }),
            listener,
            acceptor: tokio_rustls::TlsAcceptor::from(tls_config),
        })
    }

    pub fn shared(&self) -> Arc<Shared> {
        self.shared.clone()
    }

    pub fn local_addr(&self) -> std::io::Result<std::net::SocketAddr> {
        self.listener.local_addr()
    }

    pub async fn serve(self) -> Result<()> {
        loop {
            let (stream, peer) = self.listener.accept().await?;
            let acceptor = self.acceptor.clone();
            let shared = self.shared.clone();
            tokio::spawn(async move {
                let tls_stream = match acceptor.accept(stream).await {
                    Ok(stream) => stream,
                    Err(err) => {
                        eprintln!("[pod-relay] tls handshake failed from {peer}: {err}");
                        return;
                    }
                };
                if let Err(err) = session(tls_stream, shared.clone()).await {
                    eprintln!("[pod-relay] session from {peer} ended: {err}");
                }
            });
        }
    }
}

async fn read_msg<R: AsyncReadExt + Unpin>(
    reader: &mut R,
    decoder: &mut FrameDecoder,
    buf: &mut [u8],
) -> Result<(u32, Msg)> {
    loop {
        if let Some(frame) = decoder.next()? {
            let req = frame.req;
            return Ok((req, from_frame(&frame)?));
        }
        let n = reader.read(buf).await?;
        if n == 0 {
            return Err(anyhow!("connection closed"));
        }
        decoder.push(&buf[..n])?;
    }
}

async fn write_msg<W: AsyncWriteExt + Unpin>(writer: &mut W, req: u32, msg: &Msg) -> Result<()> {
    let frame = to_frame(msg, req);
    writer.write_all(&encode(&frame)).await?;
    Ok(())
}

async fn session(
    stream: tokio_rustls::server::TlsStream<TcpStream>,
    shared: Arc<Shared>,
) -> Result<()> {
    let session_id = shared.seq.fetch_add(1, Ordering::Relaxed) as u64;
    shared.sessions.fetch_add(1, Ordering::Relaxed);
    let guard = SessionGuard {
        shared: shared.clone(),
        session_id,
    };

    let peer_fingerprint = stream
        .get_ref()
        .1
        .peer_certificates()
        .and_then(|certs| certs.first())
        .map(|cert| crate::cert::fingerprint_der(cert.as_ref()))
        .unwrap_or_default();

    let (mut reader, mut writer) = tokio::io::split(stream);
    let (tx, mut rx) = mpsc::unbounded_channel::<(u32, Msg)>();
    let writer_task = tokio::spawn(async move {
        while let Some((req, msg)) = rx.recv().await {
            if write_msg(&mut writer, req, &msg).await.is_err() {
                break;
            }
        }
    });

    let mut decoder = FrameDecoder::new();
    let mut buf = vec![0u8; 64 * 1024];

    let idle = Duration::from_secs(shared.config.limits.session_idle_sec.max(10));
    let (_, hello) =
        tokio::time::timeout(idle, read_msg(&mut reader, &mut decoder, &mut buf)).await??;
    let role = match hello {
        Msg::Hello { role, ver, .. } => {
            if ver != pod_relay_protocol::msg::PROTOCOL_VERSION {
                let _ = tx.send((
                    0,
                    Msg::Error {
                        code: err::UNSUPPORTED_VERSION,
                        msg: format!("unsupported protocol version {ver}"),
                    },
                ));
                drop(guard);
                drop(tx);
                let _ = writer_task.await;
                return Err(anyhow!("unsupported protocol version"));
            }
            role
        }
        _ => {
            let _ = tx.send((
                0,
                Msg::Error {
                    code: err::BAD_FRAME,
                    msg: "expected HELLO".to_string(),
                },
            ));
            drop(guard);
            drop(tx);
            let _ = writer_task.await;
            return Err(anyhow!("expected hello"));
        }
    };

    let _ = tx.send((
        0,
        Msg::HelloOk {
            ver: pod_relay_protocol::msg::PROTOCOL_VERSION,
            session_id,
            relay_ms: now_ms(),
            host_online: shared.host_online() || role == ROLE_HOST,
            host_lib_version: shared.host_lib_version(),
            store_used: shared.store.used(),
            store_cap: shared.store.capacity(),
        },
    ));

    let role_allowed = if role == ROLE_HOST {
        shared
            .config
            .auth
            .host_fingerprints
            .contains(&peer_fingerprint)
    } else {
        shared
            .config
            .auth
            .client_fingerprints
            .contains(&peer_fingerprint)
    };
    if !role_allowed {
        let _ = tx.send((
            0,
            Msg::Error {
                code: err::UNAUTHORIZED,
                msg: "certificate is not allowed for this role".to_string(),
            },
        ));
        drop(guard);
        drop(tx);
        let _ = writer_task.await;
        return Err(anyhow!("role not allowed for peer certificate"));
    }

    if role == ROLE_HOST {
        {
            let mut nas = shared.nas.lock().unwrap();
            *nas = Some(NasLink {
                session_id,
                tx: tx.clone(),
            });
        }
        eprintln!("[pod-relay] host session {session_id} connected");
        send_cache_status(&shared);
        let ticker_shared = shared.clone();
        let ticker_tx = tx.clone();
        let ticker_nas_id = session_id;
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(5));
            loop {
                interval.tick().await;
                if ticker_tx.is_closed() {
                    break;
                }
                let online = ticker_shared
                    .nas
                    .lock()
                    .map(|nas| nas.as_ref().map(|n| n.session_id) == Some(ticker_nas_id))
                    .unwrap_or(false);
                if !online {
                    break;
                }
                let _ = ticker_tx.send((
                    0,
                    Msg::CacheStatus {
                        used: ticker_shared.store.used(),
                        cap: ticker_shared.store.capacity(),
                        pressure: ticker_shared.pressure(),
                    },
                ));
            }
        });
    } else if role != ROLE_CLIENT {
        let _ = tx.send((
            0,
            Msg::Error {
                code: err::BAD_FRAME,
                msg: "unknown role".to_string(),
            },
        ));
        drop(guard);
        drop(tx);
        let _ = writer_task.await;
        return Err(anyhow!("unknown role"));
    }

    let mut pending_snapshot: Option<(SnapshotHeader, Vec<Vec<u8>>)> = None;
    loop {
        let read = tokio::time::timeout(idle, read_msg(&mut reader, &mut decoder, &mut buf)).await;
        let (req, msg) = match read {
            Ok(Ok(pair)) => pair,
            Ok(Err(err)) => {
                drop(guard);
                drop(tx);
                let _ = writer_task.await;
                return Err(err);
            }
            Err(_) => {
                drop(guard);
                drop(tx);
                let _ = writer_task.await;
                return Err(anyhow!("session idle timeout"));
            }
        };

        match (role, msg) {
            (_, Msg::Ping { nonce }) => {
                let _ = tx.send((0, Msg::Pong { nonce }));
            }
            (ROLE_CLIENT, msg) => handle_client(&shared, &tx, req, msg),
            (ROLE_HOST, msg) => handle_host(&shared, &tx, req, msg, &mut pending_snapshot),
            _ => {}
        }
    }
}

fn send_cache_status(shared: &Shared) {
    shared.send_to_host(Msg::CacheStatus {
        used: shared.store.used(),
        cap: shared.store.capacity(),
        pressure: shared.pressure(),
    });
}

fn handle_client(shared: &Shared, tx: &Outbox, req: u32, msg: Msg) {
    match msg {
        Msg::LibVersionReq => {
            let client_payload = match shared.snapshots.latest() {
                Some(header) => Msg::LibVersionOk {
                    version: header.version,
                    key_id: header.key_id,
                    plain_len: header.plain_len,
                    gz_len: header.gz_len,
                    chunks: header.chunks,
                    salt: header.salt,
                    host_online: shared.host_online(),
                },
                None => Msg::LibVersionOk {
                    version: 0,
                    key_id: 0,
                    plain_len: 0,
                    gz_len: 0,
                    chunks: 0,
                    salt: [0u8; 8],
                    host_online: shared.host_online(),
                },
            };
            let _ = tx.send((req, client_payload));
        }
        Msg::LibSnapshotReq {
            version,
            chunk_index,
        } => {
            if let Some(data) = shared.snapshots.chunk(version, chunk_index) {
                let total = shared
                    .snapshots
                    .header(version)
                    .map(|h| h.gz_len)
                    .unwrap_or(0);
                let _ = tx.send((
                    req,
                    Msg::LibSnapshotChunk {
                        version,
                        chunk_index,
                        total,
                        data,
                    },
                ));
            } else {
                let _ = tx.send((
                    req,
                    Msg::Error {
                        code: err::NOT_FOUND,
                        msg: "snapshot chunk not available".to_string(),
                    },
                ));
            }
        }
        Msg::StatReq { file_id } => {
            if let Some(stat) = shared.store.stat(&file_id) {
                let _ = tx.send((req, stat_ok(file_id, &stat)));
                return;
            }
            let Some(nas) = shared.host_tx() else {
                let _ = tx.send((
                    req,
                    Msg::StatErr {
                        file_id,
                        code: err::HOST_OFFLINE,
                    },
                ));
                return;
            };
            let seq = shared.seq.fetch_add(1, Ordering::Relaxed);
            shared.pending_stats.lock().unwrap().insert(
                seq,
                PendingStat {
                    at: now_secs(),
                    client: tx.clone(),
                    client_req: req,
                    file_id,
                },
            );
            let _ = nas.send((seq, Msg::StatReq { file_id }));
        }
        Msg::FragReq { file_id, index } => {
            if let Some(data) = shared.store.read_fragment(&file_id, index) {
                let nonce = nonce_for(shared, &file_id, index);
                let _ = tx.send((
                    req,
                    Msg::FragData {
                        file_id,
                        index,
                        nonce,
                        ct: data,
                    },
                ));
                return;
            }
            shared.store.miss.fetch_add(1, Ordering::Relaxed);
            shared.send_to_host(Msg::Want {
                file_id,
                index,
                priority: 1,
            });
            let _ = tx.send((
                req,
                Msg::Pending {
                    file_id,
                    index,
                    retry_ms: 150,
                    host_online: shared.host_online(),
                },
            ));
        }
        Msg::WantAhead {
            file_id,
            from,
            count,
        } => {
            let limit = count.min(64);
            for index in from..from.saturating_add(limit as u32) {
                shared.send_to_host(Msg::Want {
                    file_id,
                    index,
                    priority: 2,
                });
            }
        }
        Msg::Pin { file_id, ttl_sec } => {
            shared.store.pin(&file_id, ttl_sec);
        }
        Msg::LiveReq { seq, ct } => {
            let Some(nas) = shared.host_tx() else {
                let _ = tx.send((
                    req,
                    Msg::LiveResp {
                        seq,
                        status: 503,
                        ct: Vec::new(),
                    },
                ));
                return;
            };
            shared.pending_live.lock().unwrap().insert(
                seq,
                PendingLive {
                    at: now_secs(),
                    client: tx.clone(),
                    client_req: req,
                },
            );
            let _ = nas.send((seq, Msg::LiveFwd { seq, ct }));
        }
        Msg::AdminStatsReq => {
            let _ = tx.send((
                req,
                Msg::AdminStatsOk {
                    sessions: shared.sessions.load(Ordering::Relaxed),
                    host_online: shared.host_online(),
                    store_used: shared.store.used(),
                    store_cap: shared.store.capacity(),
                    hit: shared.store.hit.load(Ordering::Relaxed),
                    miss: shared.store.miss.load(Ordering::Relaxed),
                    evicted: shared.store.evicted.load(Ordering::Relaxed),
                    lib_version: shared.host_lib_version(),
                },
            ));
        }
        _ => {}
    }
}

fn handle_host(
    shared: &Shared,
    tx: &Outbox,
    req: u32,
    msg: Msg,
    pending_snapshot: &mut Option<(SnapshotHeader, Vec<Vec<u8>>)>,
) {
    match msg {
        Msg::StatOk {
            file_id,
            total,
            frag,
            count,
            salt,
            mime,
            key_id,
        } => {
            let stat = StatInfo {
                total,
                frag,
                count,
                salt,
                mime,
                key_id,
            };
            shared.store.remember_stat(file_id, stat.clone());
            let pending = shared.pending_stats.lock().unwrap().remove(&req);
            if let Some(pending) = pending {
                let _ = pending
                    .client
                    .send((pending.client_req, stat_ok(file_id, &stat)));
            }
        }
        Msg::StatErr { file_id, code } => {
            let pending = shared.pending_stats.lock().unwrap().remove(&req);
            if let Some(pending) = pending {
                let _ = pending
                    .client
                    .send((pending.client_req, Msg::StatErr { file_id, code }));
            }
        }
        Msg::HostPushBegin {
            file_id,
            total,
            frag,
            count,
            salt,
            mime,
        } => {
            if count == 0 {
                return;
            }
            let key_id = shared
                .store
                .cached_stat(&file_id)
                .map(|stat| stat.key_id)
                .unwrap_or(0);
            let stat = StatInfo {
                total,
                frag,
                count,
                salt,
                mime,
                key_id,
            };
            if let Err(err) = shared.store.begin(file_id, stat) {
                eprintln!("[pod-relay] push begin rejected: {err}");
                let _ = tx.send((
                    req,
                    Msg::Error {
                        code: err::THROTTLED,
                        msg: "store full".to_string(),
                    },
                ));
            }
        }
        Msg::HostPushFrag { file_id, index, ct } => {
            if let Err(err) = shared.store.write_fragment(file_id, index, &ct) {
                eprintln!("[pod-relay] write fragment failed: {err}");
            }
        }
        Msg::HostPushEnd { file_id, ok } => {
            if !ok {
                shared.store.remove(&file_id);
            }
        }
        Msg::SnapshotBegin {
            version,
            key_id,
            plain_len,
            gz_len,
            chunks,
            salt,
        } => {
            *pending_snapshot = Some((
                SnapshotHeader {
                    version,
                    key_id,
                    plain_len,
                    gz_len,
                    chunks,
                    salt,
                },
                Vec::new(),
            ));
        }
        Msg::SnapshotChunk {
            version,
            chunk_index,
            data,
        } => {
            if let Some((header, chunks)) = pending_snapshot.as_mut() {
                if header.version == version && chunks.len() == chunk_index as usize {
                    chunks.push(data);
                }
            }
        }
        Msg::SnapshotEnd { version, ok } => {
            if let Some((header, chunks)) = pending_snapshot.take() {
                if ok && header.version == version && chunks.len() == header.chunks as usize {
                    if let Err(err) = shared.snapshots.put(&header, &chunks) {
                        eprintln!("[pod-relay] store snapshot failed: {err}");
                    } else {
                        eprintln!("[pod-relay] snapshot version {version} stored");
                    }
                }
            }
        }
        Msg::LiveReply { seq, status, ct } => {
            let pending = shared.pending_live.lock().unwrap().remove(&seq);
            if let Some(pending) = pending {
                let _ = pending
                    .client
                    .send((pending.client_req, Msg::LiveResp { seq, status, ct }));
            }
        }
        _ => {}
    }
}

fn stat_ok(file_id: FileId, stat: &StatInfo) -> Msg {
    Msg::StatOk {
        file_id,
        total: stat.total,
        frag: stat.frag,
        count: stat.count,
        salt: stat.salt,
        mime: stat.mime.clone(),
        key_id: stat.key_id,
    }
}

fn nonce_for(shared: &Shared, file_id: &FileId, index: u32) -> [u8; 12] {
    let salt = shared
        .store
        .stat(file_id)
        .map(|stat| stat.salt)
        .unwrap_or([0u8; 8]);
    pod_relay_protocol::crypto::fragment_nonce(index, &salt)
}

struct SessionGuard {
    shared: Arc<Shared>,
    session_id: u64,
}

impl Drop for SessionGuard {
    fn drop(&mut self) {
        self.shared.sessions.fetch_sub(1, Ordering::Relaxed);
        if let Ok(mut nas) = self.shared.nas.lock() {
            if nas.as_ref().map(|link| link.session_id) == Some(self.session_id) {
                *nas = None;
                eprintln!("[pod-relay] host session {} disconnected", self.session_id);
            }
        }
        if let Ok(mut pending) = self.shared.pending_stats.lock() {
            let now = now_secs();
            pending.retain(|_, item| now - item.at < 60);
        }
        if let Ok(mut pending) = self.shared.pending_live.lock() {
            pending.retain(|_seq, item| now_secs() - now_secs() < 60 && now_secs() > 0 && item.client_req != u32::MAX);
        }
    }
}
