//! Relay server: TLS accept loop, session handling, fragment dispatch.

use crate::auth::{AuthStore, ClientAccess};
use crate::config::Config;
use crate::log::{bytes, elapsed, file_short, kind_from_mime};
use crate::snapshot::SnapshotStore;
use crate::store::{FragmentStore, StatInfo};
use crate::tls;
use anyhow::{anyhow, Context, Result};
use pod_relay_protocol::access::scope_id;
use pod_relay_protocol::frame::{encode, FrameDecoder};
use pod_relay_protocol::msg::{err, FileId, Msg, ROLE_CLIENT, ROLE_HOST};
use pod_relay_protocol::snapshot::SnapshotHeader;
use pod_relay_protocol::{from_frame, to_frame};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::atomic::{AtomicU32, AtomicU64, AtomicU8, Ordering};
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
    client_seq: u32,
}

struct RootMap {
    version: i64,
    roots: HashMap<FileId, i64>,
}

/// Counters for one connection, reported when the session closes.
#[derive(Default)]
struct SessionStats {
    fragments: AtomicU64,
    fragment_bytes: AtomicU64,
    fragment_misses: AtomicU64,
    stats: AtomicU64,
    snapshot_chunks: AtomicU64,
    snapshot_bytes: AtomicU64,
    live: AtomicU64,
}

struct SessionCtx {
    session_id: u64,
    peer: String,
    fingerprint: String,
    label: String,
    role: &'static str,
    stats: SessionStats,
}

impl SessionCtx {
    /// Stable prefix used by every line belonging to this connection.
    fn who(&self) -> String {
        let id = self.fingerprint.get(..12).unwrap_or(&self.fingerprint);
        format!("{} \"{}\" [{}]", self.role, self.label, id)
    }
}

struct PushProgress {
    started_ms: i64,
    fragments: u32,
    mime: String,
}

pub struct Shared {
    pub config: Config,
    pub store: Arc<FragmentStore>,
    pub snapshots: Arc<SnapshotStore>,
    pub auth: Arc<AuthStore>,
    nas: Mutex<Option<NasLink>>,
    root_map: Mutex<Option<RootMap>>,
    failures: Mutex<HashMap<IpAddr, (u32, i64)>>,
    pending_stats: Mutex<HashMap<u32, PendingStat>>,
    pending_live: Mutex<HashMap<u32, PendingLive>>,
    pushes: Mutex<HashMap<FileId, PushProgress>>,
    last_pressure: AtomicU8,
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
        self.snapshots.max_version()
    }

    fn file_allowed(&self, access: &ClientAccess, file_id: &FileId) -> bool {
        if access.all {
            return true;
        }
        let map = match self.root_map.lock() {
            Ok(map) => map,
            Err(_) => return false,
        };
        let Some(map) = map.as_ref() else {
            return false;
        };
        map.roots
            .get(file_id)
            .map(|root| access.roots.contains(root))
            .unwrap_or(false)
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

    fn auth_failed(&self, ip: Option<IpAddr>) -> bool {
        let Some(ip) = ip else {
            return false;
        };
        let now = now_secs();
        let mut failures = self.failures.lock().unwrap();
        failures.retain(|_, (_, at)| now - *at < 60);
        let entry = failures.entry(ip).or_insert((0, now));
        entry.0 += 1;
        entry.1 = now;
        entry.0 > 12
    }

    fn clear_failures(&self, ip: Option<IpAddr>) {
        if let Some(ip) = ip {
            if let Ok(mut failures) = self.failures.lock() {
                failures.remove(&ip);
            }
        }
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

pub struct Relay {
    shared: Arc<Shared>,
    listener: TcpListener,
    acceptor: tokio_rustls::TlsAcceptor,
}

impl Relay {
    pub async fn bind(config: Config) -> Result<Relay> {
        std::fs::create_dir_all(config.store.dir.join("frag"))?;

        let certs_dir = config.store.dir.join("certs");
        let (cert_path, key_path) = match &config.tls {
            Some(tls) => (tls.cert.clone(), tls.key.clone()),
            None => (certs_dir.join("relay.crt"), certs_dir.join("relay.key")),
        };
        let host = config
            .public_addr
            .clone()
            .unwrap_or_else(|| config.listen.clone());
        let cn = host.rsplit_once(':').map(|(h, _)| h.to_string()).unwrap_or_else(|| host.clone());
        let (fingerprint, created) = crate::cert::ensure_self_signed(
            &cert_path,
            &key_path,
            &cn,
            &[
                format!("dns:{cn}"),
                "dns:pod-relay.local".to_string(),
            ],
        )?;
        let tls_config = tls::server_config(&cert_path, &key_path)?;

        let auth = Arc::new(AuthStore::open(&config.store.dir)?);
        if let Some(token) = auth.ensure_token()? {
            eprintln!("[pod-relay] host enrollment token created");
            eprintln!(
                "podrelay://relay?v=2&a={}&p={}&t={}",
                url_encode(&host),
                url_encode(&fingerprint),
                url_encode(&token)
            );
        } else {
            eprintln!(
                "[pod-relay] already enrolled (host {}), certificate {}",
                auth.host_fingerprint().unwrap_or_else(|| "not bound".into()),
                fingerprint
            );
        }
        if created {
            eprintln!("[pod-relay] generated TLS certificate {fingerprint}");
        }

        let store = Arc::new(FragmentStore::open(
            &config.store.dir,
            config.store.capacity_bytes,
        )?);
        let snapshots = Arc::new(SnapshotStore::open(&config.store.dir)?);
        eprintln!(
            "[pod-relay] cache recovered: {} used of {} capacity ({} files)",
            bytes(store.used()),
            bytes(store.capacity()),
            store.file_count(),
        );

        let listener = TcpListener::bind(&config.listen)
            .await
            .with_context(|| format!("bind {}", config.listen))?;
        eprintln!("[pod-relay] listening on {}", config.listen);

        Ok(Relay {
            shared: Arc::new(Shared {
                config,
                store,
                snapshots,
                auth,
                nas: Mutex::new(None),
                root_map: Mutex::new(None),
                failures: Mutex::new(HashMap::new()),
                pending_stats: Mutex::new(HashMap::new()),
                pending_live: Mutex::new(HashMap::new()),
                pushes: Mutex::new(HashMap::new()),
                last_pressure: AtomicU8::new(0),
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
    let active = shared.sessions.fetch_add(1, Ordering::Relaxed) + 1;
    let mut guard = SessionGuard {
        shared: shared.clone(),
        session_id,
        started_ms: now_ms(),
        ctx: None,
    };

    let peer_fingerprint = stream
        .get_ref()
        .1
        .peer_certificates()
        .and_then(|certs| certs.first())
        .map(|cert| crate::cert::fingerprint_der(cert.as_ref()))
        .unwrap_or_default();
    let peer_ip = stream
        .get_ref()
        .0
        .peer_addr()
        .ok()
        .map(|addr| addr.ip());
    let peer_text = peer_ip
        .map(|ip| ip.to_string())
        .unwrap_or_else(|| "unknown".to_string());
    let fingerprint_short = peer_fingerprint
        .get(..12)
        .unwrap_or(&peer_fingerprint)
        .to_string();
    eprintln!(
        "[pod-relay] session {session_id}: TLS established from {peer_text} ({fingerprint_short}), {active} active",
    );

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
    let (role, hello_key_id, hello_auth) = match hello {
        Msg::Hello {
            role,
            ver,
            key_id,
            auth,
            ..
        } => {
            let version_ok = match role {
                ROLE_HOST => ver == pod_relay_protocol::msg::PROTOCOL_VERSION,
                ROLE_CLIENT => {
                    ver >= pod_relay_protocol::msg::MIN_CLIENT_VERSION
                        && ver <= pod_relay_protocol::msg::PROTOCOL_VERSION
                }
                _ => false,
            };
            if version_ok {
                eprintln!(
                    "[pod-relay] session {session_id}: HELLO from {peer_text} role {role} protocol v{ver}"
                );
            }
            if !version_ok {
                eprintln!(
                    "[pod-relay] session {session_id}: rejected {peer_text} role {role} protocol v{ver} (relay host v{} / client v{}–v{})",
                    pod_relay_protocol::msg::PROTOCOL_VERSION,
                    pod_relay_protocol::msg::MIN_CLIENT_VERSION,
                    pod_relay_protocol::msg::PROTOCOL_VERSION
                );
                let _ = tx.send((
                    0,
                    Msg::Error {
                        code: err::UNSUPPORTED_VERSION,
                        msg: format!("unsupported protocol version {ver} for role {role}"),
                    },
                ));
                drop(guard);
                drop(tx);
                let _ = writer_task.await;
                return Err(anyhow!(
                    "unsupported protocol version {ver} for role {role} (relay expects host v{} and client v{}–v{})",
                    pod_relay_protocol::msg::PROTOCOL_VERSION,
                    pod_relay_protocol::msg::MIN_CLIENT_VERSION,
                    pod_relay_protocol::msg::PROTOCOL_VERSION
                ));
            }
            (role, key_id, auth)
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

    let supplied_token = std::str::from_utf8(&hello_auth).ok().filter(|t| !t.is_empty());
    let authorized = match role {
        ROLE_HOST => shared.auth.host_allowed(&peer_fingerprint, supplied_token),
        ROLE_CLIENT => shared.auth.client_allowed(&peer_fingerprint),
        _ => false,
    };
    if !authorized {
        let limited = shared.auth_failed(peer_ip);
        let (code, msg) = if limited {
            (err::RATE_LIMITED, "too many failed attempts")
        } else if role == ROLE_CLIENT {
            (err::DEVICE_REVOKED, "device is not authorized")
        } else {
            (err::UNAUTHORIZED, "host is not authorized")
        };
        eprintln!(
            "[pod-relay] rejected role {role} from {peer_fingerprint}: {msg}"
        );
        let _ = tx.send((0, Msg::Error { code, msg: msg.to_string() }));
        drop(guard);
        drop(tx);
        let _ = writer_task.await;
        return Err(anyhow!("authorization failed"));
    }
    shared.clear_failures(peer_ip);

    let client_access = if role == ROLE_CLIENT {
        shared.auth.client_access(&peer_fingerprint)
    } else {
        None
    };
    let label = if role == ROLE_HOST {
        "nas".to_string()
    } else {
        client_access
            .as_ref()
            .map(|access| access.label.clone())
            .unwrap_or_else(|| "unknown".to_string())
    };
    let ctx = Arc::new(SessionCtx {
        session_id,
        peer: peer_text.clone(),
        fingerprint: peer_fingerprint.clone(),
        label: label.clone(),
        role: if role == ROLE_HOST { "host" } else { "client" },
        stats: SessionStats::default(),
    });
    guard.ctx = Some(ctx.clone());

    if role == ROLE_HOST {
        if let Err(err) = shared.auth.bind_host(&peer_fingerprint) {
            eprintln!("[pod-relay] persist host binding failed: {err}");
        }
        if hello_key_id != 0 && shared.auth.key_id() != hello_key_id {
            eprintln!(
                "[pod-relay] media key changed ({} -> {}), purging relay cache before host takes over",
                shared.auth.key_id(),
                hello_key_id
            );
            shared.store.clear_all();
            shared.snapshots.clear_all();
            let _ = shared.auth.set_key_id(hello_key_id);
        }
        eprintln!(
            "[pod-relay] session {session_id}: host connected from {peer_text} ({fingerprint_short}), key {} / library v{}, cache {} used of {}",
            hello_key_id,
            shared.host_lib_version(),
            bytes(shared.store.used()),
            bytes(shared.store.capacity())
        );
    } else if let Some(access) = client_access.as_ref() {
        let access_text = if access.all {
            "all library roots".to_string()
        } else {
            format!("{} library root(s)", access.roots.len())
        };
        eprintln!(
            "[pod-relay] session {session_id}: client \"{}\" connected from {peer_text} ({fingerprint_short}) with {access_text}",
            access.label
        );
    }

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

    if role == ROLE_HOST {
        {
            let mut nas = shared.nas.lock().unwrap();
            *nas = Some(NasLink {
                session_id,
                tx: tx.clone(),
            });
        }
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
                send_cache_status(&ticker_shared);
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

    let mut pending_snapshot: Option<(u64, SnapshotHeader, Vec<Vec<u8>>)> = None;
    let mut pending_root_map: Option<(i64, u32, Vec<u8>)> = None;
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
            (ROLE_CLIENT, msg) => {
                let Some(access) = shared.auth.client_access(&peer_fingerprint) else {
                    let _ = tx.send((
                        0,
                        Msg::Error {
                            code: err::DEVICE_REVOKED,
                            msg: "device is not authorized".to_string(),
                        },
                    ));
                    drop(guard);
                    drop(tx);
                    let _ = writer_task.await;
                    return Ok(());
                };
                handle_client(&shared, &tx, req, msg, &access, &ctx);
            }
            (ROLE_HOST, msg) => handle_host(
                &shared,
                &tx,
                req,
                msg,
                &mut pending_snapshot,
                &mut pending_root_map,
                &ctx,
            ),
            _ => {}
        }
    }
}

fn send_cache_status(shared: &Shared) {
    let used = shared.store.used();
    let cap = shared.store.capacity();
    let pressure = shared.pressure();
    let previous = shared.last_pressure.swap(pressure, Ordering::Relaxed);
    if previous != pressure {
        let label = match pressure {
            2 => "full",
            1 => "high",
            _ => "normal",
        };
        eprintln!(
            "[pod-relay] cache pressure -> {label} ({} of {} used)",
            bytes(used),
            bytes(cap)
        );
    }
    shared.send_to_host(Msg::CacheStatus {
        used,
        cap,
        pressure,
    });
}

/// Short description of a stored file for log lines. Uses the cached stat so a
/// pull never blocks on disk just to be logged.
fn media_desc(shared: &Shared, file_id: &FileId) -> String {
    match shared
        .store
        .cached_stat(file_id)
        .or_else(|| shared.store.stat(file_id))
    {
        Some(stat) => format!(
            "{} {} (mime {}, {} frags, {})",
            kind_from_mime(&stat.mime),
            file_short(file_id),
            stat.mime,
            stat.count,
            bytes(stat.total)
        ),
        None => format!("unknown file {}", file_short(file_id)),
    }
}

fn handle_client(
    shared: &Shared,
    tx: &Outbox,
    req: u32,
    msg: Msg,
    access: &ClientAccess,
    ctx: &SessionCtx,
) {
    let who = ctx.who();
    let scope = scope_id(&access.roots);
    match msg {
        Msg::LibVersionReq => {
            let client_payload = match shared.snapshots.latest(scope) {
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
            if let Msg::LibVersionOk {
                version,
                host_online,
                ..
            } = &client_payload
            {
                eprintln!(
                    "[pod-relay] session {}: {who} asked library version scope {:016x} -> v{} (host {})",
                    ctx.session_id,
                    scope,
                    version,
                    if *host_online { "online" } else { "offline" }
                );
            }
            let _ = tx.send((req, client_payload));
        }
        Msg::LibSnapshotReq {
            version,
            chunk_index,
        } => {
            if let Some(data) = shared.snapshots.chunk(scope, version, chunk_index) {
                let header = shared.snapshots.header(scope, version);
                let total = header.as_ref().map(|h| h.gz_len).unwrap_or(0);
                let chunks = header.as_ref().map(|h| h.chunks).unwrap_or(0);
                let data_len = data.len() as u64;
                ctx.stats.snapshot_chunks.fetch_add(1, Ordering::Relaxed);
                ctx.stats
                    .snapshot_bytes
                    .fetch_add(data_len, Ordering::Relaxed);
                eprintln!(
                    "[pod-relay] session {}: {who} pulled library snapshot v{version} chunk {}/{} ({})",
                    ctx.session_id,
                    chunk_index + 1,
                    chunks,
                    bytes(data_len)
                );
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
                eprintln!(
                    "[pod-relay] session {}: {who} requested missing library snapshot v{version} chunk {chunk_index} (scope {scope:016x})",
                    ctx.session_id
                );
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
            ctx.stats.stats.fetch_add(1, Ordering::Relaxed);
            if !shared.file_allowed(access, &file_id) {
                eprintln!(
                    "[pod-relay] session {}: {who} stat {} denied (outside granted roots)",
                    ctx.session_id,
                    file_short(&file_id)
                );
                let _ = tx.send((
                    req,
                    Msg::StatErr {
                        file_id,
                        code: err::NOT_FOUND,
                    },
                ));
                return;
            }
            if let Some(stat) = shared.store.stat(&file_id) {
                eprintln!(
                    "[pod-relay] session {}: {who} stat {} -> {} (cache hit)",
                    ctx.session_id,
                    file_short(&file_id),
                    media_desc(shared, &file_id)
                );
                let _ = tx.send((req, stat_ok(file_id, &stat)));
                return;
            }
            let Some(nas) = shared.host_tx() else {
                eprintln!(
                    "[pod-relay] session {}: {who} stat {} failed: host offline",
                    ctx.session_id,
                    file_short(&file_id)
                );
                let _ = tx.send((
                    req,
                    Msg::StatErr {
                        file_id,
                        code: err::HOST_OFFLINE,
                    },
                ));
                return;
            };
            eprintln!(
                "[pod-relay] session {}: {who} stat {} not cached, asking host",
                ctx.session_id,
                file_short(&file_id)
            );
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
            if !shared.file_allowed(access, &file_id) {
                eprintln!(
                    "[pod-relay] session {}: {who} pull {} denied (outside granted roots)",
                    ctx.session_id,
                    file_short(&file_id)
                );
                let _ = tx.send((
                    req,
                    Msg::StatErr {
                        file_id,
                        code: err::NOT_FOUND,
                    },
                ));
                return;
            }
            let count = shared
                .store
                .cached_stat(&file_id)
                .map(|stat| stat.count)
                .unwrap_or(0);
            if let Some(data) = shared.store.read_fragment(&file_id, index) {
                let data_len = data.len() as u64;
                ctx.stats.fragments.fetch_add(1, Ordering::Relaxed);
                ctx.stats
                    .fragment_bytes
                    .fetch_add(data_len, Ordering::Relaxed);
                eprintln!(
                    "[pod-relay] session {}: {who} pulled {} fragment {}/{} ({}) [cache hit]",
                    ctx.session_id,
                    media_desc(shared, &file_id),
                    index + 1,
                    count,
                    bytes(data_len)
                );
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
            ctx.stats.fragment_misses.fetch_add(1, Ordering::Relaxed);
            eprintln!(
                "[pod-relay] session {}: {who} pulled {} fragment {}/{} [cache miss], asking host (host {})",
                ctx.session_id,
                media_desc(shared, &file_id),
                index + 1,
                count,
                if shared.host_online() {
                    "online"
                } else {
                    "offline"
                }
            );
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
            if !shared.file_allowed(access, &file_id) {
                eprintln!(
                    "[pod-relay] session {}: {who} read-ahead for {} denied (outside granted roots)",
                    ctx.session_id,
                    file_short(&file_id)
                );
                return;
            }
            let limit = count.min(64);
            eprintln!(
                "[pod-relay] session {}: {who} read-ahead for {} from fragment {} (+{})",
                ctx.session_id,
                media_desc(shared, &file_id),
                from + 1,
                limit
            );
            for index in from..from.saturating_add(limit as u32) {
                shared.send_to_host(Msg::Want {
                    file_id,
                    index,
                    priority: 2,
                });
            }
        }
        Msg::Pin { file_id, ttl_sec } => {
            if shared.file_allowed(access, &file_id) {
                eprintln!(
                    "[pod-relay] session {}: {who} pinned {} for {ttl_sec}s",
                    ctx.session_id,
                    media_desc(shared, &file_id)
                );
                shared.store.pin(&file_id, ttl_sec);
            } else {
                eprintln!(
                    "[pod-relay] session {}: {who} pin {} denied (outside granted roots)",
                    ctx.session_id,
                    file_short(&file_id)
                );
            }
        }
        Msg::LiveReq { seq, ct } => {
            ctx.stats.live.fetch_add(1, Ordering::Relaxed);
            let Some(nas) = shared.host_tx() else {
                eprintln!(
                    "[pod-relay] session {}: {who} live request {seq} ({} body) failed: host offline",
                    ctx.session_id,
                    bytes(ct.len() as u64)
                );
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
            eprintln!(
                "[pod-relay] session {}: {who} live request {seq} ({} body) relayed to host",
                ctx.session_id,
                bytes(ct.len() as u64)
            );
            // Allocate a relay-side sequence so two clients can never collide
            // or steal each other's pending live reply.
            let relay_seq = shared.seq.fetch_add(1, Ordering::Relaxed);
            shared.pending_live.lock().unwrap().insert(
                relay_seq,
                PendingLive {
                    at: now_secs(),
                    client: tx.clone(),
                    client_req: req,
                    client_seq: seq,
                },
            );
            let _ = nas.send((
                relay_seq,
                Msg::LiveFwd {
                    seq: relay_seq,
                    client_seq: seq,
                    client_id: access.id,
                    ct,
                },
            ));
        }
        Msg::AdminStatsReq => {
            eprintln!(
                "[pod-relay] session {}: {who} requested relay stats",
                ctx.session_id
            );
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
        _ => {
            eprintln!(
                "[pod-relay] session {}: {who} sent a client message the relay does not handle",
                ctx.session_id
            );
        }
    }
}

fn handle_host(
    shared: &Shared,
    tx: &Outbox,
    req: u32,
    msg: Msg,
    pending_snapshot: &mut Option<(u64, SnapshotHeader, Vec<Vec<u8>>)>,
    pending_root_map: &mut Option<(i64, u32, Vec<u8>)>,
    ctx: &SessionCtx,
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
            eprintln!(
                "[pod-relay] session {}: host stat {} -> mime {}, {} frags, {} ({})",
                ctx.session_id,
                file_short(&file_id),
                stat.mime,
                stat.count,
                bytes(stat.total),
                if pending.is_some() {
                    "forwarded to client"
                } else {
                    "no waiting client"
                }
            );
            if let Some(pending) = pending {
                let _ = pending
                    .client
                    .send((pending.client_req, stat_ok(file_id, &stat)));
            }
        }
        Msg::StatErr { file_id, code } => {
            let pending = shared.pending_stats.lock().unwrap().remove(&req);
            eprintln!(
                "[pod-relay] session {}: host stat {} failed with code {code} ({})",
                ctx.session_id,
                file_short(&file_id),
                if pending.is_some() {
                    "forwarded to client"
                } else {
                    "no waiting client"
                }
            );
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
                eprintln!(
                    "[pod-relay] session {}: host push for {} has zero fragments, ignored",
                    ctx.session_id,
                    file_short(&file_id)
                );
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
            eprintln!(
                "[pod-relay] session {}: host started pushing {} ({} fragments of {}, mime {})",
                ctx.session_id,
                file_short(&file_id),
                stat.count,
                bytes(stat.total),
                stat.mime
            );
            match shared.store.begin(file_id, stat.clone()) {
                Ok(()) => {
                    if let Ok(mut pushes) = shared.pushes.lock() {
                        pushes.insert(
                            file_id,
                            PushProgress {
                                started_ms: now_ms(),
                                fragments: 0,
                                mime: stat.mime.clone(),
                            },
                        );
                    }
                }
                Err(err) => {
                    eprintln!(
                        "[pod-relay] session {}: push begin for {} rejected: {err}",
                        ctx.session_id,
                        file_short(&file_id)
                    );
                    let _ = tx.send((
                        req,
                        Msg::Error {
                            code: err::THROTTLED,
                            msg: "store full".to_string(),
                        },
                    ));
                }
            }
        }
        Msg::HostPushFrag { file_id, index, ct } => {
            match shared.store.write_fragment(file_id, index, &ct) {
                Ok(()) => {
                    if let Ok(mut pushes) = shared.pushes.lock() {
                        if let Some(progress) = pushes.get_mut(&file_id) {
                            progress.fragments += 1;
                        }
                    }
                }
                Err(err) => {
                    eprintln!(
                        "[pod-relay] session {}: write fragment {index} for {} ({} ciphertext) failed: {err}",
                        ctx.session_id,
                        file_short(&file_id),
                        bytes(ct.len() as u64)
                    );
                }
            }
        }
        Msg::HostPushEnd { file_id, ok } => {
            let progress = shared.pushes.lock().ok().and_then(|mut pushes| pushes.remove(&file_id));
            if !ok {
                shared.store.remove(&file_id);
                eprintln!(
                    "[pod-relay] session {}: host aborted push for {}, dropped cached fragments",
                    ctx.session_id,
                    file_short(&file_id)
                );
                return;
            }
            let stat = shared.store.cached_stat(&file_id);
            match (&progress, stat) {
                (Some(progress), Some(stat)) => eprintln!(
                    "[pod-relay] session {}: host finished pushing {} ({} fragments of {}, mime {}) in {} — {} cached ({}), {} used of {}",
                    ctx.session_id,
                    file_short(&file_id),
                    progress.fragments,
                    stat.count,
                    bytes(stat.total),
                    progress.mime,
                    elapsed((now_ms() - progress.started_ms).max(0) as u128),
                    if progress.fragments >= stat.count {
                        "complete"
                    } else {
                        "partial"
                    },
                    bytes(shared.store.used()),
                    bytes(shared.store.capacity())
                ),
                _ => eprintln!(
                    "[pod-relay] session {}: host finished pushing unknown file {}",
                    ctx.session_id,
                    file_short(&file_id)
                ),
            }
        }
        Msg::SnapshotBegin {
            scope,
            version,
            key_id,
            plain_len,
            gz_len,
            chunks,
            salt,
        } => {
            eprintln!(
                "[pod-relay] session {}: host pushing library snapshot scope {scope:016x} v{version} ({} plain -> {} compressed, {chunks} chunks, key {key_id})",
                ctx.session_id,
                bytes(plain_len),
                bytes(gz_len)
            );
            *pending_snapshot = Some((
                scope,
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
            if let Some((_, header, chunks)) = pending_snapshot.as_mut() {
                if header.version == version && chunks.len() == chunk_index as usize {
                    chunks.push(data);
                }
            }
        }
        Msg::SnapshotEnd { version, ok } => {
            if let Some((scope, header, chunks)) = pending_snapshot.take() {
                let received: u64 = chunks.iter().map(|chunk| chunk.len() as u64).sum();
                if ok && header.version == version && chunks.len() == header.chunks as usize {
                    if let Err(err) = shared.snapshots.put(scope, &header, &chunks) {
                        eprintln!(
                            "[pod-relay] session {}: store snapshot scope {scope:016x} v{version} failed: {err}",
                            ctx.session_id
                        );
                    } else {
                        eprintln!(
                            "[pod-relay] session {}: snapshot scope {scope:016x} v{version} stored ({} in {} chunks)",
                            ctx.session_id,
                            bytes(received),
                            chunks.len()
                        );
                    }
                } else {
                    eprintln!(
                        "[pod-relay] session {}: snapshot scope {scope:016x} v{version} rejected (ok={ok}, got {}/{} chunks, {} received)",
                        ctx.session_id,
                        chunks.len(),
                        header.chunks,
                        bytes(received)
                    );
                }
            }
        }
        Msg::RootMapBegin { version, count } => {
            eprintln!(
                "[pod-relay] session {}: host pushing root map v{version} ({count} files)",
                ctx.session_id
            );
            *pending_root_map = Some((version, count, Vec::new()));
        }
        Msg::RootMapChunk {
            version,
            index,
            data,
        } => {
            if let Some((map_version, count, payload)) = pending_root_map.as_mut() {
                let expected = index as usize * pod_relay_protocol::msg::ROOT_MAP_CHUNK_ITEMS * 24;
                if *map_version == version
                    && expected == payload.len()
                    && payload.len() + data.len() <= *count as usize * 24
                {
                    payload.extend_from_slice(&data);
                }
            }
        }
        Msg::RootMapEnd { version, ok } => {
            if let Some((map_version, count, payload)) = pending_root_map.take() {
                if ok
                    && map_version == version
                    && payload.len() == count as usize * 24
                {
                    let mut roots = HashMap::with_capacity(count as usize);
                    for chunk in payload.chunks_exact(24) {
                        let mut id = [0u8; 16];
                        id.copy_from_slice(&chunk[..16]);
                        let mut root_bytes = [0u8; 8];
                        root_bytes.copy_from_slice(&chunk[16..]);
                        roots.insert(id, i64::from_be_bytes(root_bytes));
                    }
                    let entries = roots.len();
                    if let Ok(mut slot) = shared.root_map.lock() {
                        *slot = Some(RootMap { version, roots });
                    }
                    eprintln!(
                        "[pod-relay] session {}: root map v{version} stored ({} files, {})",
                        ctx.session_id,
                        entries,
                        bytes(payload.len() as u64)
                    );
                } else {
                    eprintln!(
                        "[pod-relay] session {}: root map v{version} rejected (ok={ok}, {} of {} bytes)",
                        ctx.session_id,
                        payload.len(),
                        count as usize * 24
                    );
                }
            }
        }
        Msg::AuthorizeClient {
            client_id,
            label,
            fingerprint,
            roots,
            all,
        } => {
            let root_count = roots.len();
            let ok = shared
                .auth
                .authorize(client_id, &label, &fingerprint, roots, all)
                .is_ok();
            if ok {
                eprintln!(
                    "[pod-relay] authorized device {} ({}, {} roots)",
                    crate::auth::hex(&client_id),
                    label,
                    root_count
                );
            }
            let _ = tx.send((req, Msg::Ack { ok }));
        }
        Msg::SetClientAccess {
            client_id,
            roots,
            all,
        } => {
            let ok = shared.auth.set_access(&client_id, roots, all).is_ok();
            if ok {
                eprintln!(
                    "[pod-relay] access updated for {} (all={})",
                    crate::auth::hex(&client_id),
                    all
                );
            }
            let _ = tx.send((req, Msg::Ack { ok }));
        }
        Msg::RevokeClient { client_id } => {
            let ok = shared.auth.revoke(&client_id).is_ok();
            eprintln!(
                "[pod-relay] revoked device {}",
                crate::auth::hex(&client_id)
            );
            let _ = tx.send((req, Msg::Ack { ok }));
        }
        Msg::ResetClients => {
            let ok = shared.auth.revoke_all().is_ok();
            eprintln!("[pod-relay] revoked all devices");
            let _ = tx.send((req, Msg::Ack { ok }));
        }
        Msg::LiveReply { seq, status, ct } => {
            let pending = shared.pending_live.lock().unwrap().remove(&seq);
            eprintln!(
                "[pod-relay] session {}: host answered live request {seq} with HTTP {status} ({} body, {})",
                ctx.session_id,
                bytes(ct.len() as u64),
                if pending.is_some() {
                    "forwarded to client"
                } else {
                    "no waiting client"
                }
            );
            if let Some(pending) = pending {
                let _ = pending.client.send((
                    pending.client_req,
                    Msg::LiveResp {
                        seq: pending.client_seq,
                        status,
                        ct,
                    },
                ));
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
    started_ms: i64,
    ctx: Option<Arc<SessionCtx>>,
}

impl Drop for SessionGuard {
    fn drop(&mut self) {
        let active = self
            .shared
            .sessions
            .fetch_sub(1, Ordering::Relaxed)
            .saturating_sub(1);
        if let Ok(mut nas) = self.shared.nas.lock() {
            if nas.as_ref().map(|link| link.session_id) == Some(self.session_id) {
                *nas = None;
                eprintln!(
                    "[pod-relay] host session {} disconnected, relay is now offline for clients",
                    self.session_id
                );
            }
        }
        if let Some(ctx) = self.ctx.as_ref() {
            let stats = &ctx.stats;
            eprintln!(
                "[pod-relay] session {} closed after {} ({}, from {}, {active} active): {} fragments / {} served, {} fragment misses, {} stat lookups, {} snapshot chunks / {}, {} live requests",
                self.session_id,
                elapsed((now_ms() - self.started_ms).max(0) as u128),
                ctx.who(),
                ctx.peer,
                stats.fragments.load(Ordering::Relaxed),
                bytes(stats.fragment_bytes.load(Ordering::Relaxed)),
                stats.fragment_misses.load(Ordering::Relaxed),
                stats.stats.load(Ordering::Relaxed),
                stats.snapshot_chunks.load(Ordering::Relaxed),
                bytes(stats.snapshot_bytes.load(Ordering::Relaxed)),
                stats.live.load(Ordering::Relaxed),
            );
        }
        if let Ok(mut pending) = self.shared.pending_stats.lock() {
            let now = now_secs();
            pending.retain(|_, item| now - item.at < 60);
        }
        if let Ok(mut pending) = self.shared.pending_live.lock() {
            let now = now_secs();
            pending.retain(|_seq, item| now - item.at < 60);
        }
    }
}
