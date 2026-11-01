# 公网中继（PodRelay）实施方案 v1

本文是公网中继的完整实施方案：协议、加密、认证、中继服务器、NAS 端（Windows 上的
`desktop-app/`）、Android 客户端接入方式、部署与测试。实施完成后本文件即作为协议文档使用。

- 产品名：**PodRelay**
- 协议版本：`relay/v1`
- 传输：`TLS 1.3`（`rustls`）承载自定义二进制帧，**不是 HTTP/HTTPS 应用层协议**
- 中继服务器端口：单个高位 TCP 端口，默认 `21443`（服务器 80/443 在防火墙保持屏蔽）
- 使用规模：单人、单 NAS、少量客户端设备
- 依赖约束：中继服务器与 NAS 端都用 Rust，共享同一个协议 crate，不引入新的语言工具链

### 命名与代码归属

**所有中继相关代码集中放在仓库根目录的 `relay-server/`**，它是一个独立的 Cargo workspace，
包含协议、公网服务端、NAS 侧 agent 库和一个多用途 CLI，四个 crate：

| 位置 | crate | 说明 |
|---|---|---|
| `relay-server/protocol` | `pod-relay-protocol` | 帧、消息、加解密、快照格式；服务端与 NAS 端共用 |
| `relay-server/server` | `pod-relay-server` | TLS 监听、会话、存储、调度等服务器核心（库） |
| `relay-server/agent` | `pod-relay-agent` | NAS 侧的连接/索引/推送逻辑，纯库，不依赖 Tauri（复用 server crate 的 TLS/证书辅助函数） |
| `relay-server/cli` | `pod-relay-cli` | 唯一二进制 `relay-server`：`server` / `gencert` / `keygen` / `fingerprint` / `status` / `fetch` / `selftest` |

`desktop-app/` 只通过 path 依赖引入 `pod-relay-agent` 与 `pod-relay-protocol`，
并提供一个把现有后端适配成 agent 所需接口的薄适配器；中继的业务逻辑不写进 `desktop-app/`。
公网服务器上的部署路径统一为 `/etc/pod-relay`、`/var/lib/pod-relay`，systemd 单元名 `pod-relay`。

---

## 1. 目标与约束

### 1.1 目标

1. NAS 上的音乐、有声书、电子书在公网可访问，访问入口只有公网中继服务器。
2. 中继服务器**不接触媒体明文**，也不需要 NAS 的完整文件系统视图；它保存的是客户端可直接取用的加密分片。
3. NAS 主动把后续要听的分片预推到中继服务器，客户端按自己的消费节奏取用；补推由中继服务器按需触发。
4. 客户端现有的资料库同步、播放、封面缓存、电子书下载、投屏代理逻辑**不做结构性重写**。
5. NAS 上只有一个进程：中继逻辑以 `relay-server/agent` 库的形式链接进 `desktop-app/`，不新增部署单元；Windows 上双击运行后即可用。

### 1.2 约束

| 约束 | 影响 |
|---|---|
| NAS 是 Windows，后端已在 `desktop-app/` 内 | NAS 侧以 Tauri 进程内模块形式实现，复用同一份 DB/HTTP/媒体代码，不额外起服务、不引入环回 HTTP |
| 服务器 80/443 已被防火墙屏蔽 | 中继只监听一个高位端口；不使用 ACME、不使用 `GET /health` 之类 HTTP 探活；自签证书 + 指纹固定 |
| 公网中继不能装 NAS 的解密密钥 | 媒体与元数据一律应用层加密，中继只做容器 |
| 一人使用 | 不做账号体系，端点用证书指纹固定 |
| 客户端是 Android，播放器只认 HTTP + Range | Android 侧在本机回环端口上提供 HTTP 代理，把 Range 请求翻译成分片请求 |

---

## 2. 总体架构

```
        ┌──────────────────────────┐
        │  Android 客户端          │
        │  ExoPlayer / 同步 / 下载 │
        │        │ http://127.0.0.1:47001 (仅本机)
        │        ▼                 │
        │  RelayProxy（进程内）    │
        └────────┬─────────────────┘
                 │ TLS + 自定义帧（出站）
                 ▼
        ┌──────────────────────────┐
        │  relayd（公网服务器）    │
        │  会话 / 密文分片存储 /   │
        │  快照版本 / WANT 调度    │
        └────────┬─────────────────┘
                 ▲ TLS + 自定义帧（NAS 主动出站，可过 NAT）
                 │
        ┌────────┴─────────────────┐
        │  desktop-app（Windows NAS）│
        │  Tauri 进程               │
        │  ├ 现有 HTTP/资料库/媒体  │
        │  └ relay agent 模块       │
        └──────────────────────────┘
```

职责边界：

| 组件 | 职责 |
|---|---|
| NAS（desktop-app） | 提供现有 v1 协议；agent 在进程内调用同一套路由取数据，切片加密后推送；响应 `WANT`；推送加密后的资料库快照 |
| 中继服务器的职责仅限 | mTLS 双向认证、放行帧、密文分片落盘与淘汰、快照版本缓存、把 `WANT` 转发给 NAS、向 NAS 反馈缓存水位 |
| Android 客户端 | 本机 HTTP 代理把 v1 协议翻译为帧协议；索引计算、解密、Range 拼接、本机缓存 |

两层加密各司其职：

- **传输层**：TLS 1.3 保护 NAS↔中继、中继↔客户端两条链路，并用证书指纹固定端点。
- **应用层**：NAS 与客户端共享一把 256 位对称密钥，所有媒体分片、资料库快照、直通请求都单独 AES-256-GCM 加密；密钥不存在于中继服务器。

---

## 3. 关键设计决策

1. **中继按"加密分片仓库"实现，不做通用隧道。** 需要中继做的事只有三件：存、发、催。任何让中继解析 HTTP 的设计都会强制它在 TLS 终止处看到明文，与目标 2 冲突。
2. **NAS 侧不经过环回 HTTP。** agent 与 `http_server` 同进程、同 `HttpState`，直接用 `tower::ServiceExt::oneshot` 调 `build_router` 生成的路由。因此不依赖端口、不受 Windows 防火墙影响、也不会出现"端口变了 agent 连不上"。路径、Range、封面回退、视频抽音轨缓存、电子书懒解析全部沿用现有实现。
3. **Android 侧用本机代理而不是改播放器。** `RemoteLibraryManager`、`RemoteDownloadManager`、`RemoteArtCache`、`RemoteMediaCache`、投屏代理都只认 `http://host:port` 与 `Range`。把"远端端点"换成 `127.0.0.1:47001`，这些代码零改动。
4. **一条设备对应多个端点。** 中继返回的资料库与原 LAN 设备是同一个 `deviceId`，因此客户端仍只有一条资料库记录；同步时按「mDNS/UDP → 上次 LAN 地址 → 本机中继代理」顺序尝试，谁先成功用谁。在家走局域网，在外走中继。
5. **文件标识用不可逆哈希。** `file_id = SHA-256(kind ‖ 0x00 ‖ key)` 取前 16 字节。客户端本来就有 `id/path`，NAS 也有，两侧可独立算出同一个 `file_id`；中继服务器只见到 16 字节不透明 ID，看不到任何路径与曲目 id。
6. **快照也加密并缓存。** 曲名、书名、作者、绝对路径都在 `/library/all` 里；不加密就等于送给中继服务器。快照按版本缓存后，NAS 短暂离线时客户端仍能浏览目录。
7. **服务器不使用 80/443。** 中继只监听高位端口，TLS 之上的应用层是自定义帧；防火墙只为该端口放行 TCP 入站。健康检查用同一协议的 `ADMIN_STATS`，不额外开 HTTP 端口。

---

## 4. 组件与代码落位

```
aurora/
├── relay-server/                       # 独立 Cargo workspace：所有中继代码
│   ├── Cargo.toml                      # [workspace] members = ["protocol", "server", "agent", "cli"]
│   ├── protocol/                       # pod-relay-protocol
│   │   └── src/{lib,frame,msg,crypto,snapshot,error,file_id}.rs
│   ├── server/                         # pod-relay-server：服务器核心（库）
│   │   └── src/{lib,config,cert,tls,store,snapshot,server}.rs
│   ├── agent/                          # pod-relay-agent（NAS 侧，纯库）
│   │   ├── src/{lib,config,client,index,snapshot,push,stats}.rs
│   │   └── src/backend.rs              # trait LibraryBackend：由宿主实现
│   ├── cli/                            # pod-relay-cli，[[bin]] name = "relay-server"
│   │   └── src/{main,gencert,fingerprint,status,fetch,selftest}.rs
│   └── README.md
├── desktop-app/
│   ├── src-tauri/
│   │   ├── Cargo.toml                  # 依赖 pod-relay-agent / pod-relay-protocol（path）
│   │   └── src/
│   │       ├── relay_backend.rs        # 薄适配器：实现 LibraryBackend，走 oneshot
│   │       ├── relay_host.rs           # relay.json / media.key / 证书导入 / 启停与状态
│   │       ├── commands.rs             # + relay 配置/状态命令
│   │       └── lib.rs                  # 启动/停止 agent，挂到 AppState
│   └── src/components/RelayPanel.tsx   # 设置与状态 UI
└── app/src/main/java/com/aurora/music/data/relay/   # Android 侧（新增）
    ├── RelayConfig.kt / RelayProto.kt / RelayCrypto.kt / RelayTls.kt
    ├── RelayClient.kt / RelayStore.kt / RelayProxy.kt / RelayService.kt
```

`pod-relay-agent` 与宿主（`desktop-app`）的边界只有一个 trait：

```rust
// relay-server/agent/src/backend.rs
#[async_trait]
pub trait LibraryBackend: Send + Sync {
    async fn library_version(&self) -> Result<i64>;
    async fn snapshot_json(&self) -> Result<Vec<u8>>;
    async fn stat(&self, path: &str) -> Result<Stat>;              // path 由 file_id 索引得到
    async fn fetch_range(&self, path: &str, start: u64, end: u64) -> Result<Vec<u8>>;
    async fn live(&self, req: LiveRequest) -> Result<LiveResponse>;
}
```

工程约定：`relay-server/target/`、`relay-server/tmp/` 需加入仓库 `.gitignore`；临时产物只放
`relay-server/tmp/` 或仓库根 `tmp/`，不写系统临时目录。

`desktop-app/src-tauri/src/relay_backend.rs` 用现有的 `HttpState` + `build_router()` 实现这五个方法
（见 §10.2），其余（连接、重连、索引、快照、推送、节流）全部在 `relay-server/agent` 内。

新增 Rust 依赖（按 crate 各取所需）：

```
# relay-server/protocol
serde, serde_json, aes-gcm, hmac, sha2, flate2, rand, thiserror
# relay-server/server
tokio, tokio-rustls, rustls, rustls-pemfile, rcgen, anyhow, pod-relay-protocol
# relay-server/agent
tokio, tokio-rustls, rustls, rustls-pemfile, base64, anyhow, async-trait, pod-relay-protocol
# desktop-app/src-tauri
pod-relay-agent, pod-relay-protocol, tower = { features = ["util"] }   # oneshot
```

---

## 5. 协议规范 relay/v1

### 5.1 帧

```
+---------+--------+-----------+------------------+
| u32 len | u8 typ | u32 req   | payload (len B)  |
+---------+--------+-----------+------------------+
```

- 全大端；`len` 只计 payload；上限 `max_frame = 4 MiB`（分片 256 KiB、快照块 512 KiB 均在上限内）。
- `req` 由请求方单调递增；响应原样带回，用于并发多路复用；中继主动下发用 `0`。
- 收到超长帧、未知类型、读超时、TLS 指纹不匹配，一律断开并记录原因。

### 5.2 握手与会话

| 类型 | 方向 | 载荷 |
|---|---|---|
| `0x01 HELLO` | 两端 → 中继 | `role:u8`（1=NAS，2=CLIENT），`ver:u16=1`，`key_id:u64`，`caps:u32` |
| `0x02 HELLO_OK` | 中继 → 两端 | `ver:u16`，`session_id:u64`，`relay_ms:i64`，`host_online:u8`，`host_lib_version:i64`，`store_used:u64`，`store_cap:u64` |
| `0x03 PING` / `0x04 PONG` | 双向 | `nonce:u64` |
| `0x7F ERROR` | 双向 | `code:u16`，`msg:utf8` |

- 认证完全由 mTLS 承担；`HELLO` 只协商版本、角色、`key_id` 一致性。
- 每 30 秒一次 `PING`；90 秒无任何帧判死并断开。
- 错误码：`1 bad_frame`、`2 unsupported_version`、`3 unauthorized`、`4 key_mismatch`、`5 host_offline`、`6 not_found`、`7 throttled`、`8 internal`、`9 upstream_error`。
- 角色约束：NAS 会话每台中继服务器只允许一个，新连接顶掉旧连接；客户端会话可多个。

### 5.3 资料库快照

| 类型 | 方向 | 载荷 |
|---|---|---|
| `0x10 LIB_VERSION_REQ` | C→R | 空 |
| `0x11 LIB_VERSION_OK` | R→C | `version:i64`，`key_id:u64`，`plain_len:u64`，`gz_len:u64`，`chunks:u32`，`salt[8]`，`host_online:u8` |
| `0x12 LIB_SNAPSHOT_REQ` | C→R | `version:i64`，`chunk_index:u32` |
| `0x13 LIB_SNAPSHOT_CHUNK` | R→C | `version:i64`，`chunk_index:u32`，`total:u64`，`data` |
| `0x14 SNAPSHOT_BEGIN` | N→R | `version:i64`，`key_id:u64`，`plain_len:u64`，`gz_len:u64`，`chunks:u32`，`salt[8]` |
| `0x15 SNAPSHOT_CHUNK` | N→R | `version:i64`，`chunk_index:u32`，`data` |
| `0x16 SNAPSHOT_END` | N→R | `version:i64`，`ok:u8` |

- 快照内容 = `/library/all` 的 JSON → gzip → 按 512 KiB 分块独立加密。
- 中继只解析 `SNAPSHOT_BEGIN` 里的明文头（版本、长度、块数）；块内容原样落盘。
- 中继保留「当前版本 + 上一版本」，收到新版本后删除更早的版本目录，避免客户端跨版本读到半套数据。

### 5.4 媒体分片

| 类型 | 方向 | 载荷 |
|---|---|---|
| `0x20 STAT_REQ` | C→R | `file_id[16]` |
| `0x21 STAT_OK` | R→C | `file_id[16]`，`total:u64`，`frag:u32`，`count:u32`，`salt[8]`，`mime_len:u8`，`mime`，`key_id:u64` |
| `0x22 FRAG_REQ` | C→R | `file_id[16]`，`index:u32` |
| `0x23 FRAG_DATA` | R→C | `file_id[16]`，`index:u32`，`nonce[12]`，`ct` |
| `0x24 PENDING` | R→C | `file_id[16]`，`index:u32`，`retry_ms:u32`，`host_online:u8` |
| `0x25 WANT_AHEAD` | C→R | `file_id[16]`，`from:u32`，`count:u16` |
| `0x26 PIN` | C→R | `file_id[16]`，`ttl_sec:u32` |
| `0x31 HOST_PUSH_BEGIN` | N→R | `file_id[16]`，`total:u64`，`frag:u32`，`count:u32`，`salt[8]`，`mime_len:u8`，`mime` |
| `0x32 HOST_PUSH_FRAG` | N→R | `file_id[16]`，`index:u32`，`ct` |
| `0x33 HOST_PUSH_END` | N→R | `file_id[16]`，`ok:u8` |
| `0x35 WANT` | R→N | `file_id[16]`，`index:u32`，`priority:u8`（1=客户端直取，2=预读） |
| `0x36 STAT_REQ` | R→N | `file_id[16]`（实现上复用 `0x20`，解码相同；编号保留） |
| `0x37 STAT_OK` | N→R | 同 `0x21` |
| `0x38 STAT_ERR` | N→R | `file_id[16]`，`code:u16` |
| `0x30 CACHE_STATUS` | R→N | `used:u64`，`cap:u64`，`pressure:u8`（0 正常 / 1 偏高 / 2 已满） |
| `0x39 EVICT_NOTICE` | R→N | `file_id[16]`（仅日志用） |

`STAT_OK` 是代理生成 `Content-Length` 与 `Content-Type` 的唯一来源；缺失时客户端无法回 HTTP 响应。

`HOST_PUSH_BEGIN` 是幂等的：中继收到同一 `file_id` 且 `total/count` 未变时，保留已缓存分片，只更新 mime/salt/key_id；只有布局变化才重建容器。因此 NAS 可以按窗口多次推送同一文件，客户端拖动进度条时不会丢已缓存的分片。

### 5.5 直通请求（懒解析）

现有客户端会对快速扫描书库调用 `GET/POST /library/ebook/scan`，这类请求必须实时到 NAS。

| 类型 | 方向 | 载荷 |
|---|---|---|
| `0x40 LIVE_REQ` | C→R | `seq:u32`，`ct` |
| `0x41 LIVE_RESP` | R→C | `seq:u32`，`status:u16`，`ct` |
| `0x42 LIVE_FWD` | R→N | `seq:u32`，`ct` |
| `0x43 LIVE_REPLY` | N→R | `seq:u32`，`status:u16`，`ct` |

- `ct` 为加密后的 JSON：`{"method":"POST","path":"/library/ebook/scan","body":"<base64>"}`。
- 白名单仅 `GET/POST /library/ebook/scan`；响应正文上限 1 MiB；超时 20 秒。
- 中继服务器看不到路径与正文，只能看到密文长度。

### 5.6 管理

| 类型 | 方向 | 载荷 |
|---|---|---|
| `0x50 ADMIN_STATS_REQ` | C→R | 空 |
| `0x51 ADMIN_STATS_OK` | R→C | `sessions:u32`，`host_online:u8`，`store_used:u64`，`store_cap:u64`，`hit:u64`，`miss:u64`，`evicted:u64`，`lib_version:i64` |

只允许携带合法客户端证书的会话调用（由 `relay-server status` 使用），不开放其他管理端口。

### 5.7 一次播放的时序

```
client  STAT_REQ(f)
relay   本地无 stat → STAT_REQ(f) → NAS
NAS     stat(f) → STAT_OK(total, frag, count, salt, mime)
relay   缓存 stat → STAT_OK 回客户端

client  FRAG_REQ(f, 0)
relay   未命中 → 入队 WANT(f,0,p=1)，回 PENDING(150ms)
NAS     读窗口 [0, 8) 共 2 MiB → 逐片 HOST_PUSH_FRAG

client  FRAG_REQ(f, 0) 重试
relay   命中 → FRAG_DATA

client  WANT_AHEAD(f, 0, 32)
relay   按 p=2 排入队列，NAS 顺序补推
```

---

## 6. 文件标识与索引

```
file_id = SHA-256( UTF8(kind) ‖ 0x00 ‖ UTF8(key) )[0..16]
```

| kind | key | 客户端映射来源 |
|---|---|---|
| `music` | `music[].id` | `/media/music?id=` |
| `audiobook` | `chapters[].path` | `/media/audiobook?path=` |
| `ebook` | `ebooks[].path` | `/media/ebook?path=` |
| `songart` | `music[].id` | `/media/songart?id=` |
| `cover` | `kind ‖ 0x1f ‖ key`（如 `ebook\x1f/books/a.epub`） | `/media/cover?kind=&key=` |
| `defaultart` | 固定字符串 `"default"` | 后端返回内置占位图的场景 |

- 两侧都能独立计算，不需要任何协商；中继服务器与网络上都不出现明文路径。
- NAS agent 在每次快照变化后重建内存索引 `file_id → 路由路径与查询串`；索引来自自己生成的 `/library/all`，与客户端看到的一致。
- 未知 `file_id` → `STAT_ERR not_found` → 代理回 `404`。

---

## 7. 加密与密钥

### 7.1 密钥材料

- `media_key`：32 字节随机，NAS 首次启用中继时生成，写入 app data 目录 `relay/media.key`。
- `key_id = BE64( HMAC-SHA256(media_key, "pod-relay/v1/id")[0..8] )`，用于两端核对。
- 派生：

```
k_media = HMAC-SHA256(media_key, "pod-relay/v1/media")
k_meta  = HMAC-SHA256(media_key, "pod-relay/v1/meta")
k_live  = HMAC-SHA256(media_key, "pod-relay/v1/live")
```

`media_key` 只存在于 NAS 与客户端；中继服务器配置里没有这把钥匙。

### 7.2 媒体分片

- 分片大小 `F = 256 KiB`；`count = ceil(total / F)`；`index ∈ [0, count)`。
- `salt[8]` 由 `HMAC-SHA256(media_key, "salt" ‖ file_id)` 前 8 字节确定性派生，明文出现在 `STAT_OK` / `HOST_PUSH_BEGIN`。确定性派生的目的是：中继淘汰后重新推送同一文件时 nonce 不变，客户端缓存的旧分片不会因 salt 变化而失效。
- `nonce = BE32(index) ‖ salt[8]`；`AAD = file_id ‖ BE32(index)`。
- 密文长度：非末片 `F + 16`，末片 `(total - index·F) + 16`。因为 `total` 已知，长度可精确推算，中继无需额外记录长度。
- 明文是"NAS 后端返回给客户端的那些字节"，即视频文件经 ffmpeg 抽出的 m4a，而不是原始视频。

### 7.3 资料库快照

```
明文 = gzip(/library/all JSON)
块大小 512 KiB
nonce = BE32(chunk_index) ‖ salt[8]
AAD   = "AURLIB1\0" ‖ BE64(version) ‖ BE32(chunk_index)
```

客户端解密后校验 gzip 与版本号；失败则丢弃并向中继重取，绝不把半份资料库写进本地库。

### 7.4 直通

`LIVE_*` 使用 `k_live`，随机 12 字节 nonce 前置，`AAD = BE32(seq) ‖ 方向字节`。

### 7.5 中继服务器可见的信息（验收口径）

可见：`file_id`（不可逆）、分片数量、文件总大小、MIME 类型名、访问时间与频率、会话角色。
不可见：任何路径、曲名、书名、作者、封面与媒体内容、直通请求的路径与正文。

---

## 8. 证书与端点认证

### 8.1 证书

三对自签证书，ECDSA P-256，有效期 10 年，无需 CA：

```bash
# 在任意一台机器上生成，产物拷到对应端点
relay-server gencert --role relay  --cn relay.example.com --san dns:relay.example.com,ip:203.0.113.10 --out certs/relay
relay-server gencert --role host   --cn aurora-nas     --out certs/nas
relay-server gencert --role client --cn pixel-9        --out certs/pixel9
relay-server fingerprint certs/relay.crt      # 打印 sha256/....（证书 DER 的 SHA-256）
```

- 固定指纹的对象是**叶子证书 DER 的 SHA-256**，不用 CA、不做链校验，直接相等比较；证书换新就同时更新两端配置。
- NAS 与 Android 把中继服务器证书指纹写进配置；中继服务器把 NAS 与各客户端的证书指纹写进白名单。
- 中继服务器要求 `ClientAuth: RequireAnyClientCert`，在自定义校验里比对白名单，不匹配立即断开。
- 白名单还区分角色：只有 `host_fingerprints` 里的证书可以发 `HELLO(role=NAS)`，只有 `client_fingerprints` 里的证书可以发 `HELLO(role=CLIENT)`；两者都通过 TLS 证书校验后才会进入会话。
- 修改白名单后 `SIGHUP` 重载，已建立的会话不中断；证书文件本身不放公网服务器之外的位置。

### 8.2 密钥分发

1. NAS 启用中继时生成 `media.key`，UI 显示 Base64 与 `key_id` 前 8 位。
2. 手机端在中继设置里粘贴该 Base64；两端 `key_id` 不一致时设置页直接报"密钥不匹配"。
3. 证书文件通过局域网文件传输或数据线导入；中继服务器的 `relay.crt` 指纹由用户手工核对（前 8 位足够）。

---

## 9. 中继服务器（`relay-server`）

### 9.1 运行时结构

```
main → 读配置 → 构建 rustls ServerConfig（mTLS + 指纹白名单）
     → TcpListener 绑定一个高位端口
     → accept 循环：每连接一个 tokio 任务
        ├ 握手：HELLO → HELLO_OK
        ├ 角色分流：NAS 会话登记 / 客户端会话登记
        ├ 读循环：解帧 → 分发
        └ 写循环：有界发送队列（512 帧），满则断开慢客户端
```

### 9.2 存储布局

```
<store>/frag/<aa>/<file_id_hex>.dat     # 定长槽容器，槽长 = F + 16
<store>/frag/<aa>/<file_id_hex>.bits    # 位图，每分片 1 字节
<store>/frag/<aa>/<file_id_hex>.meta    # JSON：total / frag / count / salt / mime / key_id / updated_at / last_access
<store>/snap/<version>/meta.json        # 明文头：version / key_id / plain_len / gz_len / chunks / salt
<store>/snap/<version>/<chunk_index>.bin
```

- 写入：`pwrite` 到 `index * (F+16)`，随后写 `.bits`；崩溃最多丢一个分片，位图未置位即视为缺失。
- 读取：按 `total` 推算末片密文长度，直接 `pread`。
- 启动：不预扫描；首次访问某文件时惰性载入 `.meta` 与 `.bits`。
- 磁盘上只有密文；即使中继服务器被攻陷，也无法还原媒体与资料库。

### 9.3 淘汰与容量

- 单文件为单位淘汰（不淘汰半个文件），按 `last_access` 升序，跳过被 `PIN` 保护或在最近 5 分钟内有会话读取的文件。
- 超过 `file_ttl_days`（默认 14 天）未访问的先淘汰，再按 LRU 补足。
- `used + need > capacity` 时先淘汰再写入；仍不足则拒绝本次推送（`ERROR throttled`），客户端退回 `PENDING`。
- 淘汰后向 NAS 发 `EVICT_NOTICE`，仅用于日志与统计。

### 9.4 水位与背压

| 水位 | `pressure` | NAS 行为 |
|---|---|---|
| `< 0.85` | 0 | 允许显式推送 + 预读窗口 |
| `≥ 0.85` | 1 | 只推显式 `WANT`（`priority=1`），暂停 `WANT_AHEAD` 预读 |
| `≥ 0.98` | 2 | 拒绝新的 `WANT_AHEAD` 并回 `ERROR throttled`；`CACHE_STATUS` 每 5 秒下发一次 |

### 9.5 WANT 调度

- 以 `(file_id, index)` 去重；每个文件维护升序队列，连续段优先，减少 NAS 侧的随机读。
- 优先级：`PIN` 文件 > 客户端显式 `FRAG_REQ` > `WANT_AHEAD`。
- 每客户端在途 ≤ 32、全局在途 ≤ 128；超出丢弃级别最低、最旧的项。
- NAS 离线时每个文件只保留最近 16 条，其余丢弃；客户端收到 `PENDING(host_online=0)`。

### 9.6 日志与健康检查

- 结构化单行日志（时间、会话、事件、`file_id` 前缀、字节数）；不打印任何明文。
- 健康检查只走协议：`relay-server status` 携带客户端证书发 `ADMIN_STATS_REQ`。
- **不开任何 HTTP/HTTPS 监听端口**（服务器 80/443 已屏蔽，本方案也不需要）。

---

## 10. NAS 端（`desktop-app/` 适配 + `relay-server/agent`）

### 10.1 职责划分

中继逻辑全部在 `relay-server/agent` 里（连接、重连、`file_id` 索引、快照推送、分片推送、节流、
水位响应、状态统计）。`desktop-app/` 只做三件事：

1. `src-tauri/src/relay_backend.rs`：实现 `pod_relay_agent::LibraryBackend`，把现有后端能力暴露给 agent；
2. `src-tauri/src/relay_host.rs`：`relay.json`、`relay/media.key`、证书导入、agent 启停与状态；
3. `src-tauri/src/lib.rs` 与 `commands.rs`：把 manager 挂进 `AppState` 并暴露 `relay_*` 命令；
4. `src/components/RelayPanel.tsx`：配置、证书导入、密钥展示、状态展示。

因此 `desktop-app/` 的改动保持在两百行以内，中继本身的演进不触碰资料库后端代码。

```rust
// src-tauri/src/relay_backend.rs（示意）
pub struct HostBackend {
    router: axum::Router,
    state: HttpState,     // db / info / cache_dir
}

#[async_trait]
impl pod_relay_agent::LibraryBackend for HostBackend {
    async fn library_version(&self) -> Result<i64> { /* db::library_version */ }
    async fn snapshot_json(&self) -> Result<Vec<u8>> { /* db::wire_snapshot → serde_json */ }
    async fn stat(&self, path: &str) -> Result<Stat> { /* oneshot + Range: bytes=0-0 */ }
    async fn fetch_range(&self, path: &str, start: u64, end: u64) -> Result<Vec<u8>> { /* oneshot */ }
    async fn live(&self, req: LiveRequest) -> Result<LiveResponse> { /* oneshot POST */ }
}
```

### 10.2 数据来源：复用现有后端，不经网络

`relay_backend.rs` 持有与 `http_server` 相同的 `HttpState`（`db`、`info`、`cache_dir`）以及
`build_router()` 产出的 `Router`：

```rust
// src-tauri/src/relay_backend.rs
let req = Request::builder()
    .method("GET")
    .uri(format!("/media/music?id={}", enc(id)))
    .header("range", format!("bytes={start}-{end}"))
    .body(Body::empty())?;
let res = router.clone().oneshot(req).await?;
let bytes = axum::body::to_bytes(res.into_body(), MAX_WINDOW).await?;
```

- `GET /media/music`、`/media/audiobook`、`/media/ebook`、`/media/songart`、`/media/cover` 都走这条路径，因此自动获得：
  - 只允许已登记 `id/path`（不按客户端传入路径直接读文件）；
  - 标准 `Range` 与 `416` 语义；
  - 视频文件的 ffmpeg 抽取与 `cache/audio` 缓存；
  - 封面"内嵌 → 目录 → 默认图"回退。
- `stat()` 用 `Range: bytes=0-0`，从 `Content-Range: bytes 0-0/<total>` 与 `Content-Type` 得到 `total` 与 `mime`；没有 `Content-Range`（封面、默认图）时用 `Content-Length`。
- 快照直接调用 `db::wire_snapshot(conn, device_id, device_name)` 序列化，保证与 `/library/all` 完全一致。
- 懒解析走 `POST /library/ebook/scan` 的 oneshot，逻辑与 HTTP 路径完全同源。

这条设计决定带来两个直接收益：不占端口、不受 Windows 防火墙影响；agent 与 HTTP 服务器永远看到同一份数据。

### 10.3 主循环（`relay-server/agent`）

```
loop {
    connect_tls(relay_addr, pin, nas_cert, nas_key)
    send HELLO(role=NAS, key_id)
    on HELLO_OK:
        若 host_lib_version != 本地 library_version → 推快照
    loop {
        select {
            ping/keepalive       : 30s
            library_version_poll : 60s；变化则重建索引 + 推快照
            WANT(f, i, prio)     : 入队（按文件合并成 8 分片窗口）
            STAT_REQ(f)          : backend::stat → STAT_OK / STAT_ERR
            LIVE_FWD(seq, ct)    : 解密 → backend::live → 加密 → LIVE_REPLY
            CACHE_STATUS         : 更新水位状态（暂停/恢复预读）
        }
    }
    断线：指数退避 1s→60s（带抖动），重连后重推缺失快照
}
```

### 10.4 快照推送（`relay-server/agent/src/snapshot.rs`）

1. 起连接后与每 60 秒：调 `backend.library_version()`。
2. 与中继服务器 `HELLO_OK.host_lib_version` 不同时：`backend.snapshot_json()` → `gzip`。
3. `SNAPSHOT_BEGIN` + 512 KiB `SNAPSHOT_CHUNK` 序列 + `SNAPSHOT_END`。
4. 失败（断线/被顶号）则丢弃本次推送，下一轮重来；快照始终可整体重推，不做增量。

### 10.5 分片推送（`relay-server/agent/src/push.rs`）

- **显式 `WANT`**：立即调度，优先级最高。按文件把相邻请求合并成一个窗口（默认 8 分片 = 2 MiB），一次 oneshot 取回后切片加密推送。
- **`WANT_AHEAD`**：进预读队列，仅在 `pressure=0` 时执行；每文件最多提前 32 分片。
- **并发**：全局 ≤ 4 个在途读取、≤ 2 个在途推送；按文件去重，同一文件同一时刻只有一个读取者。
- **失败**：源文件不存在/路径失效时回 `STAT_ERR` 或 `PUSH_END(ok=0)`，客户端收到 404/503 并沿用现有"未缓存不可播"逻辑。
- 推送队列在断线后清空，不做断点续推；中继已落盘的分片不会重复推送（推送前不查询，由中继去重落盘）。

### 10.6 配置与本地文件

`relay-server/agent/src/config.rs` 定义配置结构；`desktop-app` 负责把它持久化到 app data 目录
（Windows：`%APPDATA%\com.aurora.desktop\`）：

```
relay.json          # 配置
relay/media.key     # 32 字节，Base64 文本
relay/nas.crt       # 导入的客户端证书
relay/nas.key       # 导入的私钥
```

`relay.json`：

```json
{
  "enabled": true,
  "addr": "relay.example.com:21443",
  "serverFingerprint": "sha256/…",
  "certPath": "C:\\Users\\you\\AppData\\Roaming\\com.aurora.desktop\\relay\\nas.crt",
  "keyPath": "C:\\Users\\you\\AppData\\Roaming\\com.aurora.desktop\\relay\\nas.key",
  "readaheadFragments": 8,
  "maxInflightReads": 4,
  "snapshotPollSec": 60
}
```

`relay/media.key` 是 Base64 文本；首次启用时由 `relay_host.rs` 生成，面板里展示同样的内容与 `key_id`。

### 10.7 Tauri 命令与 UI

新增命令（`commands.rs`）：

| 命令 | 作用 |
|---|---|
| `relay_get_status` | 返回配置 + 运行状态：连接中/已连接、NAS 在线、快照版本、缓存水位、最近错误 |
| `relay_set_config` | 保存地址、指纹、证书路径、开关；保存后重建 agent |
| `relay_import_cert` | 打开文件选择器导入 `.crt`/`.key` 到 `relay/` |
| `relay_get_media_key` | 返回 `media_key` 的 Base64 与 `key_id` |
| `relay_regenerate_media_key` | 生成新密钥（提示会清空中继缓存，客户端需重新配置） |

前端新增 `src/components/RelayPanel.tsx`，挂在现有侧栏的一个「公网中继」分区：

- 开关、地址、服务器指纹（前 8 位校验提示）、证书导入按钮；
- 媒体密钥展示与复制；
- 状态区：连接状态、最近一次快照版本、缓存水位条、最近错误；
- 所有配置持久化在 `relay.json`，重启后保留（遵循"回到界面不需要重新调节"）。

`lib.rs` 的 `setup` 中：读取配置 → 若 `enabled` 则 `pod_relay_agent::start(HostBackend::new(...), config)`；
配置变更时 `stop()` 再 `start()`。返回的 `AgentHandle` 放进现有 `AppState`，进程退出时随 `Drop` 关闭连接。

---

## 11. Android 客户端接入

### 11.1 模块

| 文件 | 职责 |
|---|---|
| `RelayConfig.kt` | 配置持久化：启用、地址、指纹、客户端证书/私钥 PEM、`media_key`、代理端口；文件写入 `filesDir/relay/` |
| `RelayProto.kt` | 帧编解码与消息常量（与 Rust 一一对应）；`fileId(kind, key)` 同算法 |
| `RelayCrypto.kt` | HMAC 派生、`AES/GCM/NoPadding`、快照解密与 gzip 解压 |
| `RelayTls.kt` | PEM 解析、`KeyManagerFactory`、指纹固定的 `X509TrustManager`、建连 |
| `RelayClient.kt` | 长连接、`req` 多路复用、PING、重连退避、STAT/资料库版本缓存、在途请求管理 |
| `RelayStore.kt` | 解密后的快照 JSON 落盘（按版本）；分片内存 LRU（32 MiB） |
| `RelayProxy.kt` | `127.0.0.1:47001` 的 HTTP/1.1 服务（复用手写 HTTP 与 `HttpUtil` 风格） |
| `RelayService.kt` | 生命周期与状态 `StateFlow`：连接状态、`deviceId/deviceName`、端点 |

### 11.2 代理路由与映射

```
GET  /library/version        → LIB_VERSION_REQ → {"version": n}
GET  /library/all            → LIB_SNAPSHOT_REQ 全块 → 解密/解压 → JSON（本地已缓存则直接回）
GET|POST /library/ebook/scan → LIVE_REQ（20s 超时；失败回 503）
GET  /media/music|audiobook|ebook|songart|cover → STAT + Range 分片拼接
GET  /relay/status           → 本机诊断 JSON（不经过中继服务器）
```

Range → 分片：

- 先 `STAT`（按 `file_id` 缓存 5 分钟）得到 `total`、`mime`、`salt`；失败回 404。
- 把 `bytes=a-b` / `bytes=a-` / `bytes=-n` 归一化为 `[start, end]`，按 `F=256 KiB` 计算分片区间。
- 流水：保持 8 个在途 `FRAG_REQ`，逐片解密、按需裁掉首尾多余字节、写入响应流。
- `PENDING`：按 `retry_ms` 重试，最多 20 秒；`host_online=0` 时最多 3 秒。超时且尚未写出任何响应体时返回 `503`（避免播放器拿到半截数据）。
- 每次播放前发 `PIN(file_id, 600)`；请求窗口之外发 `WANT_AHEAD(file_id, start, 32)`。

### 11.3 与现有资料库逻辑的衔接

- 中继快照里带的是 NAS 的 `deviceId`，因此**中继与局域网是同一台设备、同一条 `payloads[deviceId]` 记录**，不会出现两份重复资料库。
- `RemoteLibraryManager.syncLoop` 改为按端点尝试：`mDNS/UDP 发现` → `上次缓存的 host/port` → `RelayService.endpoint()`（`127.0.0.1:proxyPort`，仅在启用且握手成功时加入）。第一个 `/library/version` 成功者作为本轮端点写入快照。
- 快照里的 `host/port` 被 `toLibrary()` 用来生成 `streamUrl`、封面 URL、电子书下载 URL；因此 `RemoteMediaCache`、`RemoteArtCache`、`RemoteDownloadManager`、投屏 `/audio` 代理全部无需改动，自动使用本机代理。
- 代理端口固定 `47001`，占用时顺延 `47001..47009`，写回配置；重启后 URL 不变。
- 离线判定沿用现有逻辑：代理回 503 → 本设备标记离线、保留缓存、未缓存条目置灰。

### 11.4 设置界面

`IntegrationsSettingsScreen` 增加「公网中继」入口 → 新页面：

- 开关、地址、服务器指纹；
- 客户端证书与私钥（PEM 粘贴或文件导入）、`media_key`（显示 `key_id` 前 8 位核对）；
- 「连接测试」：显示中继版本、NAS 在线、快照版本、缓存水位；
- 保存后重建 `RelayService`，配置持久化。

---

## 12. 失败与降级矩阵

| 场景 | 行为 |
|---|---|
| NAS 关机 / 中继看不到 NAS | 客户端仍可从中继读快照浏览目录；媒体未命中收到 `PENDING(host_online=0)`，代理 3 秒后回 503，UI 按现有"未缓存不可播"处理 |
| 中继服务器宕机 | 代理 503 → 同步失败 → 设备离线，本地缓存保留 |
| 客户端无公网 | 同上；局域网端点优先，不受影响 |
| NAS 上行带宽受限 | 按窗口流式推送；中继 `pressure` 抑制预读，保障当前播放 |
| 中继磁盘将满 | `pressure=1/2`；按 TTL + LRU 淘汰；必要时拒绝预读 |
| 分片损坏 / 篡改 | GCM 校验失败 → 丢弃该分片并向中继重取一次；仍失败回 503 |
| 密钥不匹配 | `HELLO_OK.key_id` 与本地不符 → 设置页提示，不同步 |
| 证书更换 | 两端更新指纹/证书，中继 `SIGHUP` 重载白名单 |
| NAS 端后端端口变化 | agent 不经端口，直接走内存路由，不受影响 |
| 超大文件（如 100 MB EPUB） | 约 400 分片整本下载；代理按流水持续供片，单分片远小于客户端 45 秒读超时 |
| 重复 `WANT` 风暴 | 中继去重 + 在途上限 + 客户端重试退避 |
| Windows 断开重连 | agent 指数退避重连；重连后按 `host_lib_version` 决定是否重推快照 |

---

## 13. 配置样例

### 13.1 中继服务器 `relay.json`

```json
{
  "listen": "0.0.0.0:21443",
  "tls": { "cert": "/etc/pod-relay/certs/relay.crt", "key": "/etc/pod-relay/certs/relay.key" },
  "auth": {
    "hostFingerprints": ["sha256/…"],
    "clientFingerprints": ["sha256/…"]
  },
  "store": {
    "dir": "/var/lib/pod-relay/store",
    "capacityBytes": 21474836480,
    "fileTtlDays": 14
  },
  "limits": {
    "maxFrame": 4194304,
    "maxSessions": 8,
    "sessionIdleSec": 90,
    "wantPerSec": 40,
    "inflightPerClient": 32,
    "inflightGlobal": 128
  },
  "watermark": { "high": 0.85, "full": 0.98 }
}
```

### 13.2 NAS `relay.json`

见 10.6。

### 13.3 客户端

由设置页写入 SharedPreferences，`media_key` 与证书文件落在 `filesDir/relay/`。

---

## 14. 部署

### 14.1 公网服务器

```bash
# 1. 只放行一个高位端口（80/443 保持屏蔽，方案不需要它们）
firewall-cmd --add-port=21443/tcp --permanent && firewall-cmd --reload   # 或对应云安全组规则

# 2. 生成证书与指纹
relay-server gencert --role relay  --cn relay.example.com --san dns:relay.example.com,ip:203.0.113.10 --out certs/relay
relay-server gencert --role host   --cn aurora-nas --out certs/nas
relay-server gencert --role client --cn pixel-9   --out certs/pixel9
relay-server fingerprint certs/nas.crt
relay-server fingerprint certs/pixel9.crt

# 3. 启动
relay-server server --config /etc/pod-relay/relay.json
systemctl enable --now pod-relay
```

构建：`cd relay-server && cargo build --release -p pod-relay-cli`，产物 `target/release/relay-server` 可直接部署；
也可用 `--target x86_64-unknown-linux-musl` 交叉编译静态二进制。
无外部运行时依赖，无 HTTP 端口，无反向代理。

### 14.2 NAS（Windows）

1. 把 `certs/nas.crt`、`certs/nas.key` 拷到 Windows，在「公网中继」面板导入。
2. 填入中继地址 `relay.example.com:21443` 与 `relay.crt` 的指纹。
3. 复制面板显示的 `media_key`。

NAS 只需出站访问 `21443/tcp`，不需要任何入站端口，NAT 后可直接工作。

### 14.3 Android

安装后在「设置 → 集成 → 公网中继」填入地址与指纹，粘贴客户端证书、私钥与 `media_key`，保存后即可在资料库列表里看到同一台 NAS；在家会自动回落到局域网端点。

---

## 15. 测试方案

### 15.1 Rust

- `pod-relay-protocol`：帧边界、超长帧拒绝、GCM 往返、篡改检测、快照分块与 gzip、`file_id` 向量。
- `pod-relay-server`：内存 store 单测；`selftest` 子命令启动"假 NAS + 假客户端"，推送 10 MiB 随机文件 → 逐片取回校验；容量压到 2 MiB 触发淘汰；`pressure` 切换；NAS 掉线/重连；慢客户端被断开。
- `pod-relay-agent`：索引重建（`music/audiobook/ebook/songart/cover` 五类映射）、推送窗口切分、水位状态机（用假 `LibraryBackend`）。
- `desktop-app`：`cargo build`；`relay_backend.rs` 的 stat/Range 适配走本地 router 的 oneshot。
- 端到端：`relay-server selftest` 用内存后端跑完整链路（快照 / STAT / 分片 / 管理统计）。

### 15.2 Android

- 纯 JVM 单测：`RelayProto` 帧编解码、消息解码、`file_id` 固定向量、query 编码对齐。
- 加密与 Range 映射在端到端联调中验证（`relay-server fetch --range` 可独立比对字节）。
- 只做编译与单测；安装、真机播放、流量观察由你本人在手机上完成（不模拟点击、不截图）。

### 15.3 端到端（本机）

`relay-server selftest` 覆盖协议与存储；`desktop-app` 打开中继、指向本机 `relay-server`，
用 `relay-server fetch --range` 逐字节比对原文件，并比对 `/library/all` 解密结果与桌面端 HTTP 返回。

---

## 16. 分阶段实施

| 阶段 | 内容 | 完成标准 |
|---|---|---|
| P0 | `relay-server/protocol`：帧、消息、加解密、快照格式、测试向量 | `cd relay-server && cargo test -p pod-relay-protocol` 全绿 |
| P1 | `relay-server/server`：mTLS、会话、存储、STAT/FRAG、WANT、水位、快照版本、`selftest` | `cargo run -p pod-relay-cli -- selftest` 通过；抓包确认无明文 |
| P2 | `relay-server/agent` + `desktop-app` 适配：`LibraryBackend`、索引、快照推送、分片推送 | 本机端到端取任意 Range 与原文件一致；快照解密正确 |
| P3 | `desktop-app` UI 与命令：面板、状态、密钥展示、证书导入 | 面板状态正确；重启后配置保留 |
| P4 | Android 传输层：Proto/Crypto/Tls/Client/Store + 单测 | `compileDebugKotlin` + `testDebugUnitTest` 通过 |
| P5 | Android 代理与接入：Proxy、Service、多端点同步、设置页 | 播放/封面/下载/电子书全部可用；断 Wi-Fi 自动切中继 |
| P6 | 加固与运维：节流、日志、systemd、SIGHUP、`relay-server status` | 断线 5 秒内恢复；连续播放 30 分钟无内存增长 |

提交拆分（英文，写明背景/改动/效果）：

1. `relay-server: add framed protocol crate with AES-GCM payloads`
2. `relay-server: implement relay server with encrypted fragment store`
3. `relay-server: add NAS agent library with backend trait`
4. `desktop-app: implement relay backend adapter over the library router`
5. `desktop-app: expose relay configuration and status in the UI`
6. `android: add relay transport and loopback HTTP proxy`
7. `android: add relay settings and endpoint fallback`
8. `docs: document the PodRelay protocol`

---

## 17. 风险与取舍

1. **不减少 NAS 的总上行**：预推只是把字节提前搬到中继，重复收听才真正省上行；主要收益是"公网可达、起播稳定、跨网络一致"。
2. **切换端点会换 URL**：封面与媒体缓存按 URL 键控，切换后可能重新下载；如需彻底消除，可把缓存键改成 `deviceId + file_id`（列为后续优化）。
3. **播放中断网**：正在播的 URL 仍是旧端点，可能中断；下一次同步（≤15 秒）才切端点。彻底解决需要"始终走本机代理"，不在本期。
4. **Android 自签证书管理**是最容易出错的一环（PEM 解析、KeyStore、TrustManager）；用 Rust 导出的测试向量严格对齐。
5. **资料库快照体积**：数万曲目 gzip 后约 1–3 MB，每次库变化推一次；增量同步不在 v1。
6. **单点**：中继服务器宕机则公网不可用，但局域网路径不受影响。

---

## 18. 本期不做

多用户/账号体系、CA/PKI、HTTP/2/QUIC、中继侧转码或缩略图、播放状态跨设备同步、自动扫描调度、在线视频播放（沿用"只共享音轨"）、CRL/OCSP、中继管理用的 HTTP 面板。

---

## 19. 实现状态

本方案已落地为可编译、可自测的代码，仓库内对应关系：

| 组件 | 位置 | 验证方式 |
|---|---|---|
| 协议 / 加密 / 快照格式 | `relay-server/protocol` | `cargo test -p pod-relay-protocol` |
| 公网服务器 | `relay-server/server` + `relay-server/cli` | `cargo run -p pod-relay-cli -- selftest` |
| NAS agent | `relay-server/agent` | 与上一条同一套 selftest；在桌面端进程内运行 |
| 桌面端适配与面板 | `desktop-app/src-tauri/src/relay_backend.rs`、`relay_host.rs`、`desktop-app/src/components/RelayPanel.tsx` | `cargo check`（`desktop-app/src-tauri`）、`npm run build` |
| Android 客户端 | `app/src/main/java/com/aurora/music/data/relay/*`、`ui/screens/settings/RelaySettingsScreen.kt` | `./gradlew :app:compileDebugKotlin`、`./gradlew :app:testDebugUnitTest --tests 'com.aurora.music.data.relay.*'` |

常用命令：

```bash
cd relay-server
cargo test                       # 协议单测
cargo run -p pod-relay-cli -- selftest   # 中继 + agent + 客户端完整链路
cargo build --release -p pod-relay-cli    # 产物 target/release/relay-server
```

第一次部署：`relay-server gencert` 生成三对证书 → 把 `nas.crt/nas.key` 导入桌面端面板 →
面板复制 `media_key` → 手机端填写地址、指纹、证书、私钥、`media_key`。

已知限制与后续项见 §17、§18；`relay-server/tmp/` 是自测产物目录，已加入 `.gitignore`。
