//! Message payloads. Fixed fields are big-endian; variable payloads carry a
//! `u32` length in front. `liveRequest` blobs stay opaque to the relay.

use crate::error::{Error, Result};
use crate::frame::Frame;

pub type FileId = [u8; 16];

pub const ROLE_HOST: u8 = 1;
pub const ROLE_CLIENT: u8 = 2;
pub const PROTOCOL_VERSION: u16 = 2;

pub const HELLO: u8 = 0x01;
pub const HELLO_OK: u8 = 0x02;
pub const PING: u8 = 0x03;
pub const PONG: u8 = 0x04;
pub const ERROR: u8 = 0x7f;

pub const LIB_VERSION_REQ: u8 = 0x10;
pub const LIB_VERSION_OK: u8 = 0x11;
pub const LIB_SNAPSHOT_REQ: u8 = 0x12;
pub const LIB_SNAPSHOT_CHUNK: u8 = 0x13;
pub const SNAPSHOT_BEGIN: u8 = 0x14;
pub const SNAPSHOT_CHUNK: u8 = 0x15;
pub const SNAPSHOT_END: u8 = 0x16;

pub const STAT_REQ: u8 = 0x20;
pub const STAT_OK: u8 = 0x21;
pub const FRAG_REQ: u8 = 0x22;
pub const FRAG_DATA: u8 = 0x23;
pub const PENDING: u8 = 0x24;
pub const WANT_AHEAD: u8 = 0x25;
pub const PIN: u8 = 0x26;
pub const HOST_PUSH_BEGIN: u8 = 0x31;
pub const HOST_PUSH_FRAG: u8 = 0x32;
pub const HOST_PUSH_END: u8 = 0x33;
pub const CACHE_STATUS: u8 = 0x30;
pub const WANT: u8 = 0x35;
pub const STAT_REQ_HOST: u8 = 0x36;
pub const STAT_OK_HOST: u8 = 0x37;
pub const STAT_ERR_HOST: u8 = 0x38;
pub const EVICT_NOTICE: u8 = 0x39;

pub const LIVE_REQ: u8 = 0x40;
pub const LIVE_RESP: u8 = 0x41;
pub const LIVE_FWD: u8 = 0x42;
pub const LIVE_REPLY: u8 = 0x43;

pub const ADMIN_STATS_REQ: u8 = 0x50;
pub const ADMIN_STATS_OK: u8 = 0x51;
pub const AUTHORIZE_CLIENT: u8 = 0x52;
pub const REVOKE_CLIENT: u8 = 0x53;
pub const RESET_CLIENTS: u8 = 0x56;
pub const ACK: u8 = 0x57;

pub mod err {
    pub const BAD_FRAME: u16 = 1;
    pub const UNSUPPORTED_VERSION: u16 = 2;
    pub const UNAUTHORIZED: u16 = 3;
    pub const KEY_MISMATCH: u16 = 4;
    pub const HOST_OFFLINE: u16 = 5;
    pub const NOT_FOUND: u16 = 6;
    pub const THROTTLED: u16 = 7;
    pub const INTERNAL: u16 = 8;
    pub const UPSTREAM: u16 = 9;
    pub const INVALID_TOKEN: u16 = 10;
    pub const TOKEN_EXPIRED: u16 = 11;
    pub const DEVICE_REVOKED: u16 = 12;
    pub const RATE_LIMITED: u16 = 13;
}

/// A decoded message. `req` is carried by the frame itself.
#[derive(Debug, Clone)]
pub enum Msg {
    Hello {
        role: u8,
        ver: u16,
        key_id: u64,
        caps: u32,
        auth: Vec<u8>,
    },
    HelloOk {
        ver: u16,
        session_id: u64,
        relay_ms: i64,
        host_online: bool,
        host_lib_version: i64,
        store_used: u64,
        store_cap: u64,
    },
    Ping {
        nonce: u64,
    },
    Pong {
        nonce: u64,
    },
    Error {
        code: u16,
        msg: String,
    },
    LibVersionReq,
    LibVersionOk {
        version: i64,
        key_id: u64,
        plain_len: u64,
        gz_len: u64,
        chunks: u32,
        salt: [u8; 8],
        host_online: bool,
    },
    LibSnapshotReq {
        version: i64,
        chunk_index: u32,
    },
    LibSnapshotChunk {
        version: i64,
        chunk_index: u32,
        total: u64,
        data: Vec<u8>,
    },
    SnapshotBegin {
        version: i64,
        key_id: u64,
        plain_len: u64,
        gz_len: u64,
        chunks: u32,
        salt: [u8; 8],
    },
    SnapshotChunk {
        version: i64,
        chunk_index: u32,
        data: Vec<u8>,
    },
    SnapshotEnd {
        version: i64,
        ok: bool,
    },
    StatReq {
        file_id: FileId,
    },
    StatOk {
        file_id: FileId,
        total: u64,
        frag: u32,
        count: u32,
        salt: [u8; 8],
        mime: String,
        key_id: u64,
    },
    StatErr {
        file_id: FileId,
        code: u16,
    },
    FragReq {
        file_id: FileId,
        index: u32,
    },
    FragData {
        file_id: FileId,
        index: u32,
        nonce: [u8; 12],
        ct: Vec<u8>,
    },
    Pending {
        file_id: FileId,
        index: u32,
        retry_ms: u32,
        host_online: bool,
    },
    WantAhead {
        file_id: FileId,
        from: u32,
        count: u16,
    },
    Pin {
        file_id: FileId,
        ttl_sec: u32,
    },
    HostPushBegin {
        file_id: FileId,
        total: u64,
        frag: u32,
        count: u32,
        salt: [u8; 8],
        mime: String,
    },
    HostPushFrag {
        file_id: FileId,
        index: u32,
        ct: Vec<u8>,
    },
    HostPushEnd {
        file_id: FileId,
        ok: bool,
    },
    Want {
        file_id: FileId,
        index: u32,
        priority: u8,
    },
    CacheStatus {
        used: u64,
        cap: u64,
        pressure: u8,
    },
    EvictNotice {
        file_id: FileId,
    },
    LiveReq {
        seq: u32,
        ct: Vec<u8>,
    },
    LiveResp {
        seq: u32,
        status: u16,
        ct: Vec<u8>,
    },
    LiveFwd {
        seq: u32,
        ct: Vec<u8>,
    },
    LiveReply {
        seq: u32,
        status: u16,
        ct: Vec<u8>,
    },
    AdminStatsReq,
    AdminStatsOk {
        sessions: u32,
        host_online: bool,
        store_used: u64,
        store_cap: u64,
        hit: u64,
        miss: u64,
        evicted: u64,
        lib_version: i64,
    },
    AuthorizeClient {
        client_id: [u8; 16],
        label: String,
        fingerprint: String,
    },
    RevokeClient {
        client_id: [u8; 16],
    },
    ResetClients,
    Ack {
        ok: bool,
    },
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Reader { data, pos: 0 }
    }
    fn u8(&mut self) -> Result<u8> {
        if self.pos >= self.data.len() {
            return Err(Error::Protocol("short payload".into()));
        }
        let v = self.data[self.pos];
        self.pos += 1;
        Ok(v)
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(self.array()?))
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.array()?))
    }
    fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_be_bytes(self.array()?))
    }
    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_be_bytes(self.array()?))
    }
    fn array<const N: usize>(&mut self) -> Result<[u8; N]> {
        if self.pos + N > self.data.len() {
            return Err(Error::Protocol("short payload".into()));
        }
        let mut out = [0u8; N];
        out.copy_from_slice(&self.data[self.pos..self.pos + N]);
        self.pos += N;
        Ok(out)
    }
    fn bool(&mut self) -> Result<bool> {
        Ok(self.u8()? != 0)
    }
    fn bytes(&mut self) -> Result<Vec<u8>> {
        let len = self.u32()? as usize;
        if self.pos + len > self.data.len() {
            return Err(Error::Protocol("short payload".into()));
        }
        let out = self.data[self.pos..self.pos + len].to_vec();
        self.pos += len;
        Ok(out)
    }
    fn string(&mut self) -> Result<String> {
        String::from_utf8(self.bytes()?).map_err(|_| Error::Protocol("invalid utf8".into()))
    }
}

#[derive(Default)]
struct Writer {
    out: Vec<u8>,
}

impl Writer {
    fn u8(&mut self, v: u8) {
        self.out.push(v);
    }
    fn u16(&mut self, v: u16) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }
    fn u32(&mut self, v: u32) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }
    fn u64(&mut self, v: u64) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }
    fn i64(&mut self, v: i64) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }
    fn bool(&mut self, v: bool) {
        self.out.push(u8::from(v));
    }
    fn raw(&mut self, v: &[u8]) {
        self.out.extend_from_slice(v);
    }
    fn bytes(&mut self, v: &[u8]) {
        self.u32(v.len() as u32);
        self.out.extend_from_slice(v);
    }
    fn string(&mut self, v: &str) {
        self.bytes(v.as_bytes());
    }
}

impl Msg {
    pub fn encode(&self) -> (u8, Vec<u8>) {
        let mut w = Writer::default();
        let typ = match self {
            Msg::Hello {
                role,
                ver,
                key_id,
                caps,
                auth,
            } => {
                w.u8(*role);
                w.u16(*ver);
                w.u64(*key_id);
                w.u32(*caps);
                w.bytes(auth);
                HELLO
            }
            Msg::HelloOk {
                ver,
                session_id,
                relay_ms,
                host_online,
                host_lib_version,
                store_used,
                store_cap,
            } => {
                w.u16(*ver);
                w.u64(*session_id);
                w.i64(*relay_ms);
                w.bool(*host_online);
                w.i64(*host_lib_version);
                w.u64(*store_used);
                w.u64(*store_cap);
                HELLO_OK
            }
            Msg::Ping { nonce } => {
                w.u64(*nonce);
                PING
            }
            Msg::Pong { nonce } => {
                w.u64(*nonce);
                PONG
            }
            Msg::Error { code, msg } => {
                w.u16(*code);
                w.string(msg);
                ERROR
            }
            Msg::LibVersionReq => LIB_VERSION_REQ,
            Msg::LibVersionOk {
                version,
                key_id,
                plain_len,
                gz_len,
                chunks,
                salt,
                host_online,
            } => {
                w.i64(*version);
                w.u64(*key_id);
                w.u64(*plain_len);
                w.u64(*gz_len);
                w.u32(*chunks);
                w.raw(salt);
                w.bool(*host_online);
                LIB_VERSION_OK
            }
            Msg::LibSnapshotReq {
                version,
                chunk_index,
            } => {
                w.i64(*version);
                w.u32(*chunk_index);
                LIB_SNAPSHOT_REQ
            }
            Msg::LibSnapshotChunk {
                version,
                chunk_index,
                total,
                data,
            } => {
                w.i64(*version);
                w.u32(*chunk_index);
                w.u64(*total);
                w.bytes(data);
                LIB_SNAPSHOT_CHUNK
            }
            Msg::SnapshotBegin {
                version,
                key_id,
                plain_len,
                gz_len,
                chunks,
                salt,
            } => {
                w.i64(*version);
                w.u64(*key_id);
                w.u64(*plain_len);
                w.u64(*gz_len);
                w.u32(*chunks);
                w.raw(salt);
                SNAPSHOT_BEGIN
            }
            Msg::SnapshotChunk {
                version,
                chunk_index,
                data,
            } => {
                w.i64(*version);
                w.u32(*chunk_index);
                w.bytes(data);
                SNAPSHOT_CHUNK
            }
            Msg::SnapshotEnd { version, ok } => {
                w.i64(*version);
                w.bool(*ok);
                SNAPSHOT_END
            }
            Msg::StatReq { file_id } => {
                w.raw(file_id);
                STAT_REQ
            }
            Msg::StatOk {
                file_id,
                total,
                frag,
                count,
                salt,
                mime,
                key_id,
            } => {
                w.raw(file_id);
                w.u64(*total);
                w.u32(*frag);
                w.u32(*count);
                w.raw(salt);
                w.string(mime);
                w.u64(*key_id);
                STAT_OK
            }
            Msg::StatErr { file_id, code } => {
                w.raw(file_id);
                w.u16(*code);
                STAT_ERR_HOST
            }
            Msg::FragReq { file_id, index } => {
                w.raw(file_id);
                w.u32(*index);
                FRAG_REQ
            }
            Msg::FragData {
                file_id,
                index,
                nonce,
                ct,
            } => {
                w.raw(file_id);
                w.u32(*index);
                w.raw(nonce);
                w.bytes(ct);
                FRAG_DATA
            }
            Msg::Pending {
                file_id,
                index,
                retry_ms,
                host_online,
            } => {
                w.raw(file_id);
                w.u32(*index);
                w.u32(*retry_ms);
                w.bool(*host_online);
                PENDING
            }
            Msg::WantAhead {
                file_id,
                from,
                count,
            } => {
                w.raw(file_id);
                w.u32(*from);
                w.u16(*count);
                WANT_AHEAD
            }
            Msg::Pin { file_id, ttl_sec } => {
                w.raw(file_id);
                w.u32(*ttl_sec);
                PIN
            }
            Msg::HostPushBegin {
                file_id,
                total,
                frag,
                count,
                salt,
                mime,
            } => {
                w.raw(file_id);
                w.u64(*total);
                w.u32(*frag);
                w.u32(*count);
                w.raw(salt);
                w.string(mime);
                HOST_PUSH_BEGIN
            }
            Msg::HostPushFrag { file_id, index, ct } => {
                w.raw(file_id);
                w.u32(*index);
                w.bytes(ct);
                HOST_PUSH_FRAG
            }
            Msg::HostPushEnd { file_id, ok } => {
                w.raw(file_id);
                w.bool(*ok);
                HOST_PUSH_END
            }
            Msg::Want {
                file_id,
                index,
                priority,
            } => {
                w.raw(file_id);
                w.u32(*index);
                w.u8(*priority);
                WANT
            }
            Msg::CacheStatus {
                used,
                cap,
                pressure,
            } => {
                w.u64(*used);
                w.u64(*cap);
                w.u8(*pressure);
                CACHE_STATUS
            }
            Msg::EvictNotice { file_id } => {
                w.raw(file_id);
                EVICT_NOTICE
            }
            Msg::LiveReq { seq, ct } => {
                w.u32(*seq);
                w.bytes(ct);
                LIVE_REQ
            }
            Msg::LiveResp { seq, status, ct } => {
                w.u32(*seq);
                w.u16(*status);
                w.bytes(ct);
                LIVE_RESP
            }
            Msg::LiveFwd { seq, ct } => {
                w.u32(*seq);
                w.bytes(ct);
                LIVE_FWD
            }
            Msg::LiveReply { seq, status, ct } => {
                w.u32(*seq);
                w.u16(*status);
                w.bytes(ct);
                LIVE_REPLY
            }
            Msg::AdminStatsReq => ADMIN_STATS_REQ,
            Msg::AdminStatsOk {
                sessions,
                host_online,
                store_used,
                store_cap,
                hit,
                miss,
                evicted,
                lib_version,
            } => {
                w.u32(*sessions);
                w.bool(*host_online);
                w.u64(*store_used);
                w.u64(*store_cap);
                w.u64(*hit);
                w.u64(*miss);
                w.u64(*evicted);
                w.i64(*lib_version);
                ADMIN_STATS_OK
            }
            Msg::AuthorizeClient {
                client_id,
                label,
                fingerprint,
            } => {
                w.raw(client_id);
                w.string(label);
                w.string(fingerprint);
                AUTHORIZE_CLIENT
            }
            Msg::RevokeClient { client_id } => {
                w.raw(client_id);
                REVOKE_CLIENT
            }
            Msg::ResetClients => RESET_CLIENTS,
            Msg::Ack { ok } => {
                w.bool(*ok);
                ACK
            }
        };
        (typ, w.out)
    }

    pub fn decode(typ: u8, payload: &[u8]) -> Result<Msg> {
        let mut r = Reader::new(payload);
        let msg = match typ {
            HELLO => Msg::Hello {
                role: r.u8()?,
                ver: r.u16()?,
                key_id: r.u64()?,
                caps: r.u32()?,
                auth: r.bytes()?,
            },
            HELLO_OK => Msg::HelloOk {
                ver: r.u16()?,
                session_id: r.u64()?,
                relay_ms: r.i64()?,
                host_online: r.bool()?,
                host_lib_version: r.i64()?,
                store_used: r.u64()?,
                store_cap: r.u64()?,
            },
            PING => Msg::Ping { nonce: r.u64()? },
            PONG => Msg::Pong { nonce: r.u64()? },
            ERROR => Msg::Error {
                code: r.u16()?,
                msg: r.string()?,
            },
            LIB_VERSION_REQ => Msg::LibVersionReq,
            LIB_VERSION_OK => Msg::LibVersionOk {
                version: r.i64()?,
                key_id: r.u64()?,
                plain_len: r.u64()?,
                gz_len: r.u64()?,
                chunks: r.u32()?,
                salt: r.array::<8>()?,
                host_online: r.bool()?,
            },
            LIB_SNAPSHOT_REQ => Msg::LibSnapshotReq {
                version: r.i64()?,
                chunk_index: r.u32()?,
            },
            LIB_SNAPSHOT_CHUNK => Msg::LibSnapshotChunk {
                version: r.i64()?,
                chunk_index: r.u32()?,
                total: r.u64()?,
                data: r.bytes()?,
            },
            SNAPSHOT_BEGIN => Msg::SnapshotBegin {
                version: r.i64()?,
                key_id: r.u64()?,
                plain_len: r.u64()?,
                gz_len: r.u64()?,
                chunks: r.u32()?,
                salt: r.array::<8>()?,
            },
            SNAPSHOT_CHUNK => Msg::SnapshotChunk {
                version: r.i64()?,
                chunk_index: r.u32()?,
                data: r.bytes()?,
            },
            SNAPSHOT_END => Msg::SnapshotEnd {
                version: r.i64()?,
                ok: r.bool()?,
            },
            STAT_REQ | STAT_REQ_HOST => Msg::StatReq {
                file_id: r.array::<16>()?,
            },
            STAT_OK | STAT_OK_HOST => Msg::StatOk {
                file_id: r.array::<16>()?,
                total: r.u64()?,
                frag: r.u32()?,
                count: r.u32()?,
                salt: r.array::<8>()?,
                mime: r.string()?,
                key_id: r.u64()?,
            },
            STAT_ERR_HOST => Msg::StatErr {
                file_id: r.array::<16>()?,
                code: r.u16()?,
            },
            FRAG_REQ => Msg::FragReq {
                file_id: r.array::<16>()?,
                index: r.u32()?,
            },
            FRAG_DATA => Msg::FragData {
                file_id: r.array::<16>()?,
                index: r.u32()?,
                nonce: r.array::<12>()?,
                ct: r.bytes()?,
            },
            PENDING => Msg::Pending {
                file_id: r.array::<16>()?,
                index: r.u32()?,
                retry_ms: r.u32()?,
                host_online: r.bool()?,
            },
            WANT_AHEAD => Msg::WantAhead {
                file_id: r.array::<16>()?,
                from: r.u32()?,
                count: r.u16()?,
            },
            PIN => Msg::Pin {
                file_id: r.array::<16>()?,
                ttl_sec: r.u32()?,
            },
            HOST_PUSH_BEGIN => Msg::HostPushBegin {
                file_id: r.array::<16>()?,
                total: r.u64()?,
                frag: r.u32()?,
                count: r.u32()?,
                salt: r.array::<8>()?,
                mime: r.string()?,
            },
            HOST_PUSH_FRAG => Msg::HostPushFrag {
                file_id: r.array::<16>()?,
                index: r.u32()?,
                ct: r.bytes()?,
            },
            HOST_PUSH_END => Msg::HostPushEnd {
                file_id: r.array::<16>()?,
                ok: r.bool()?,
            },
            WANT => Msg::Want {
                file_id: r.array::<16>()?,
                index: r.u32()?,
                priority: r.u8()?,
            },
            CACHE_STATUS => Msg::CacheStatus {
                used: r.u64()?,
                cap: r.u64()?,
                pressure: r.u8()?,
            },
            EVICT_NOTICE => Msg::EvictNotice {
                file_id: r.array::<16>()?,
            },
            LIVE_REQ => Msg::LiveReq {
                seq: r.u32()?,
                ct: r.bytes()?,
            },
            LIVE_RESP => Msg::LiveResp {
                seq: r.u32()?,
                status: r.u16()?,
                ct: r.bytes()?,
            },
            LIVE_FWD => Msg::LiveFwd {
                seq: r.u32()?,
                ct: r.bytes()?,
            },
            LIVE_REPLY => Msg::LiveReply {
                seq: r.u32()?,
                status: r.u16()?,
                ct: r.bytes()?,
            },
            ADMIN_STATS_REQ => Msg::AdminStatsReq,
            ADMIN_STATS_OK => Msg::AdminStatsOk {
                sessions: r.u32()?,
                host_online: r.bool()?,
                store_used: r.u64()?,
                store_cap: r.u64()?,
                hit: r.u64()?,
                miss: r.u64()?,
                evicted: r.u64()?,
                lib_version: r.i64()?,
            },
            AUTHORIZE_CLIENT => Msg::AuthorizeClient {
                client_id: r.array::<16>()?,
                label: r.string()?,
                fingerprint: r.string()?,
            },
            REVOKE_CLIENT => Msg::RevokeClient {
                client_id: r.array::<16>()?,
            },
            RESET_CLIENTS => Msg::ResetClients,
            ACK => Msg::Ack { ok: r.bool()? },
            other => {
                return Err(Error::Protocol(format!("unknown message type {other:#04x}")))
            }
        };
        Ok(msg)
    }
}

pub fn to_frame(msg: &Msg, req: u32) -> Frame {
    let (typ, payload) = msg.encode();
    Frame::new(typ, req, payload)
}

pub fn from_frame(frame: &Frame) -> Result<Msg> {
    Msg::decode(frame.typ, &frame.payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn round(msg: Msg) {
        let (typ, payload) = msg.encode();
        Msg::decode(typ, &payload).expect("decode");
    }

    #[test]
    fn roundtrips() {
        round(Msg::Hello {
            role: ROLE_HOST,
            ver: PROTOCOL_VERSION,
            key_id: 42,
            caps: 0,
            auth: b"token".to_vec(),
        });
        round(Msg::AuthorizeClient {
            client_id: [1u8; 16],
            label: "phone".into(),
            fingerprint: "sha256/x".into(),
        });
        round(Msg::Ack { ok: true });
        round(Msg::LibVersionOk {
            version: 123,
            key_id: 42,
            plain_len: 100,
            gz_len: 9,
            chunks: 2,
            salt: [1u8; 8],
            host_online: true,
        });
        round(Msg::FragData {
            file_id: [7u8; 16],
            index: 3,
            nonce: [1u8; 12],
            ct: vec![1, 2, 3],
        });
        round(Msg::AdminStatsOk {
            sessions: 1,
            host_online: true,
            store_used: 1,
            store_cap: 2,
            hit: 3,
            miss: 4,
            evicted: 5,
            lib_version: 6,
        });
        round(Msg::LiveReply {
            seq: 1,
            status: 200,
            ct: vec![9, 9],
        });
    }
}
