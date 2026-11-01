# 公网中继（PodRelay）实施方案 v2

本文是公网中继的完整实施方案：协议、加密、认证、中继服务器、NAS 端（Windows 上的
`desktop-app/`）、Android 客户端接入方式、部署与测试。实施完成后本文件即作为协议文档使用。

- 产品名：**PodRelay**
- 协议版本：`relay/v2`（认证模型变更自 v1；`pod-relay/v1/*` 是密钥派生域分隔符，保持不变以兼容旧密钥）
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
| `relay-server/cli` | `pod-relay-cli` | 唯一二进制 `relay-server`：`server` / `show` / `gencert`（高级） / `keygen` / `fingerprint` / `status` / `fetch` / `selftest` |

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
| 一人使用 | 不做账号体系；端点用 mTLS 证书，指纹由 NAS 动态登记，用户不手改 relay 配置 |
| 手机加入必须与 NAS 同局域网 | 二维码只做握手，证书/密钥/配置在局域网内通过 NAS 的 HTTP 接口交接，不经过公网中继 |
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
8. **首次启动零手工证书。** relay 自动生成自己的证书与 host token；桌面端自动生成 NAS 证书与媒体密钥；用户只需把 relay 打印的一行引导串粘到 NAS。
9. **设备授权由 NAS 决定，不由 relay 配置决定。** 手机证书在配对时由 NAS 现场生成，NAS 通过 host 会话把指纹登记到 relay；relay 只保存"已授权指纹"这份运行时状态。
10. **二维码只做局域网握手。** 码里只有 NAS 的局域网地址与一个一次性 token；证书、私钥、`media_key` 全部在局域网内交接，二维码很小，也不会把密钥送到公网中继。

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
│   │   └── src/{main,gencert,fingerprint,status,fetch,selftest}.rs  # server 启动时自动 bootstrap
│   └── README.md
├── desktop-app/
│   ├── src-tauri/
│   │   ├── Cargo.toml                  # 依赖 pod-relay-agent / pod-relay-protocol（path）
│   │   └── src/
│   │       ├── relay_backend.rs        # 薄适配器：实现 LibraryBackend，走 oneshot
│   │       ├── relay_host.rs           # relay.json / 自动证书 / 配对登记 / 启停与状态
│   │       ├── relay_pairing.rs        # 二维码内容、pairing token、手机证书签发
│   │       ├── relay_http.rs           # 局域网 /relay/pair 接口（挂到现有 router）
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

## 5. 协议规范 relay/v2

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
| `0x01 HELLO` | 两端 → 中继 | `role:u8`（1=NAS，2=CLIENT），`ver:u16=2`，`key_id:u64`，`caps:u32`，`auth_len:u8`，`auth`（NAS 首次绑定时是 host token，之后为空；客户端为空） |
| `0x02 HELLO_OK` | 中继 → 两端 | `ver:u16`，`session_id:u64`，`relay_ms:i64`，`host_online:u8`，`host_lib_version:i64`，`store_used:u64`，`store_cap:u64` |
| `0x03 PING` / `0x04 PONG` | 双向 | `nonce:u64` |
| `0x7F ERROR` | 双向 | `code:u16`，`msg:utf8` |

- TLS 仍是双向认证：两端都必须出示证书；但**证书只在客户端缓存其指纹，授权判定在 `HELLO` 之后完成**（TLS 握手阶段 relay 还拿不到角色与 token）。
- 授权规则：
  - NAS：连接证书的指纹已在 `auth.json.host` 中，或本次 `HELLO.auth` 是有效的 host token（首次绑定/换证书后重新绑定）。绑定成功后 relay 把该指纹写入 `auth.json.host`，旧指纹作废。
  - 客户端：连接证书的指纹必须已由 NAS 通过 `AUTHORIZE_CLIENT` 登记且未撤销。
  - 不满足则回 `ERROR unauthorized` 并断开；同一来源 IP 连续失败超过阈值时限速。
- 每 30 秒一次 `PING`；90 秒无任何帧判死并断开。
- 错误码：`1 bad_frame`、`2 unsupported_version`、`3 unauthorized`、`4 key_mismatch`、`5 host_offline`、`6 not_found`、`7 throttled`、`8 internal`、`9 upstream_error`、`10 invalid_token`、`11 token_expired`、`12 device_revoked`、`13 rate_limited`。
- 角色约束：NAS 会话每台中继服务器只允许一个，新连接顶掉旧连接；客户端会话可多个。
- `HELLO.key_id` 与 relay 记录不一致时，relay 在完成授权后清空全部分片与快照（说明媒体密钥已被更换）。

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
| `0x52 AUTHORIZE_CLIENT` | N→R | `client_id[16]`，`label:utf8`，`fingerprint:utf8` |
| `0x53 REVOKE_CLIENT` | N→R | `client_id[16]` |
| `0x56 RESET_CLIENTS` | N→R | 空 |
| `0x57 ACK` | R→N | `req`（回帧的 req_id），`ok:u8` |

- `AUTHORIZE_CLIENT` 只接受 host 会话；relay 持久化到 `auth.json`，客户端在下一次连接时即可通过授权。
- 设备列表以 NAS 本地 `devices.json` 为准；relay 端只负责授权/撤销，不提供列表查询。
- `REVOKE_CLIENT` 让该指纹立即失效（已建立的会话在下一次心跳/请求时断开）。
- `RESET_CLIENTS` 清空所有客户端授权，不影响 host。
- 管理类消息只允许 host 会话；`ADMIN_STATS_REQ` 允许任何已授权会话（`relay-server status` 使用）。不开放其他管理端口。

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

## 8. 凭据、自动签发与端点认证

### 8.1 凭据清单

| 凭据 | 生成者 | 存放 | 用途 |
|---|---|---|---|
| `relay.crt` / `relay.key` | relay 首次启动自动生成 | relay 磁盘 | 服务端身份；NAS 与手机用**证书 DER 的 SHA-256** 固定 |
| host token（32B） | relay 首次启动自动生成 | relay `auth.json` + NAS `relay.json`（`relay-server show` 可再次打印） | NAS 首次绑定与换证书后重新绑定 |
| `nas.crt` / `nas.key` | 桌面端首次启用自动生成 | 桌面端 `relay/` 目录 | NAS 的 mTLS 身份 |
| `phone.crt` / `phone.key` | 桌面端每次配对时自动生成 | 手机 `filesDir/relay/` | 该手机的 mTLS 身份；每台设备各一份 |
| `media_key`（32B） | 桌面端首次启用自动生成 | NAS + 手机 | **内容端到端加密**，relay 没有 |
| `key_id` | 由 `media_key` 派生 | 三端 | 校验密钥一致、触发 relay 清缓存 |
| pairing token（32B） | 桌面端每次配对时生成 | 只存在 NAS 内存 + 二维码 | 局域网配对的单次凭证，TTL 10 分钟 |

relay 上只有它自己的证书与私钥；端点证书与私钥只在 NAS / 手机本地，relay 只保存**已授权端点的指纹**（公开信息，且由 NAS 通过连接动态登记，不再手改配置）。

### 8.2 首次启动：全部自动

**relay**（`relay-server server` 启动时检查）：

1. `certs/relay.crt|key` 不存在 → 用 `rcgen` 生成自签证书（ECDSA P-256，10 年），打印 `sha256/...` 指纹。
2. `auth.json` 不存在 → 生成 32 字节 host token 并保存（relay 磁盘本身就是最高信任边界；`relay-server show` 依赖它重新打印引导串）。
3. 打印一行引导串（`relay-server show` 可再次打印）：

```
podrelay://relay?v=2&a=relay.example.com:21443&p=sha256/…&t=<hostToken>
```

4. 配置里不再需要 `auth.hostFingerprints` / `auth.clientFingerprints`；TLS 证书路径缺省时自动创建。

**桌面端**（首次启用中继）：

1. 生成 `media_key`（已有逻辑）与 NAS 的 `nas.crt`/`nas.key`（新增，`rcgen`）。
2. 粘贴 relay 的引导串，自动填入地址、指纹、host token。
3. 连接成功后 relay 把 NAS 指纹写入 `auth.json.host`；面板显示"已连接 · 快照版本 N"。
4. 之后 NAS 换证书只需重新粘贴一次 host token，relay 自动替换旧指纹。

### 8.3 局域网配对：二维码只做握手

**约束**：配对时手机与 NAS 必须处于同一局域网。二维码里不放证书、密钥或公网地址，只放局域网配对入口。

二维码内容（约 90 字符）：

```
podrelay://pair?v=2&h=<NAS 局域网 IP>&p=<NAS HTTP 端口>&t=<pairing token>&n=<设备名>&id=<deviceId>
```

流程：

1. 桌面端「添加设备」→ 生成 `pairing token`（TTL 10 分钟、单次使用）+ 该手机的密钥对与自签证书；二维码由后端用 `qrcode` 生成 SVG 返回给面板，面板显示倒计时与"作废"。
2. 手机（系统相机扫 → `podrelay://` 深链进入 App，或手动粘贴）调用 NAS 的局域网接口：

```http
POST http://<h>:<p>/relay/pair
Content-Type: application/json

{"token":"<pairing token>","deviceName":"Pixel 9","platform":"android"}
```

3. NAS 校验 token（存在、未过期、未使用）；成功后返回：

```json
{
  "version": 2,
  "relay": { "addr": "relay.example.com:21443", "fingerprint": "sha256/…" },
  "keyId": 123456789,
  "mediaKeyB64": "…",
  "certPem": "-----BEGIN CERTIFICATE-----…",
  "keyPem": "-----BEGIN PRIVATE KEY-----…",
  "deviceId": "…",
  "deviceName": "Aurora NAS"
}
```

4. 手机把证书/私钥写入 `filesDir/relay/`，保存中继配置（地址/指纹/`media_key`），启动 `RelayService`。
5. NAS 在手机成功取走凭据后，通过 host 会话发 `AUTHORIZE_CLIENT{client_id, label, fingerprint}`；relay 持久化。若此时 relay 断线，NAS 把授权项放进待办队列，重连后补发。
6. 手机随后与 relay 建立 mTLS 连接并正常同步。

为什么可以这样：现有局域网协议本身就是明文 HTTP，安全边界是"可信局域网"；配对只在这个边界内发生。二维码即使被同一局域网的人扫到，也只能拿到一次配对机会；NAS 端有 TTL、单次使用与随时撤销，默认不弹确认（扫码即加入）。

### 8.4 撤销、换证书与换密钥

**撤销单台设备**：面板设备列表 → 撤销 → NAS 从本地登记表删除，并向 relay 发 `REVOKE_CLIENT`。手机重连时 TLS 仍能完成（relay 接受任意客户端证书），但 `HELLO` 授权失败，收到 `ERROR device_revoked`，App 清空中继配置并提示重新配对。

**撤销全部设备**：`RESET_CLIENTS`，用于换手机、怀疑二维码泄漏。

**更换 NAS 证书**：面板「重置中继身份」→ 重新生成 `nas.crt/key` → 用已保存的 host token 重新绑定。已配对手机不受影响（它们信任的是 relay 证书与 `media_key`）。

**更换媒体密钥**：面板「重置端到端加密」→ 明确警告：**relay 上已缓存的加密分片、NAS 上已推送的快照、所有手机的中继配置全部作废，需要重新配对**。确认后生成新 `media_key`，relay 在 host 重连时发现 `HELLO.key_id` 变化，删除全部 fragment 与 snapshot。建议同时撤销全部设备。

### 8.5 为什么不是"证书不上 relay"

relay 上现在只有**它自己的**证书与私钥，以及"哪些端点指纹已授权"这份列表。前者是任何 TLS 服务端都必须持有的身份；后者是公开信息的散列，且由 NAS 动态登记，用户不再手工维护。端点的私钥从未离开 NAS 或手机，因此 relay 既不能冒充 NAS，也不能解密内容。

---

## 9. 中继服务器（`relay-server`）

### 9.1 运行时结构

```
main → 读配置/自动生成证书 → 构建 rustls ServerConfig（mTLS；授权在 HELLO 阶段完成）
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
2. `src-tauri/src/relay_host.rs`：`relay.json`、自动生成 `media.key` 与 NAS 证书、按引导串连接、agent 启停与状态；
3. `src-tauri/src/relay_pairing.rs` + `relay_http.rs`：配对 token、手机证书签发、局域网 `/relay/pair` 接口、设备登记；
4. `src-tauri/src/lib.rs` 与 `commands.rs`：把 manager 挂进 `AppState` 并暴露 `relay_*` 命令；
5. `src/components/RelayPanel.tsx`：引导串、二维码、设备列表、密钥与状态。

`desktop-app/` 只承担"身份与配对"这一层：证书/密钥的生成、二维码、局域网接口、设备登记；中继本身的协议与推送逻辑仍全部在 `relay-server/`，资料库后端代码不受影响。

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
    send HELLO(role=NAS, key_id, host_token)
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
relay.json            # 中继配置（地址/指纹/host token/开关）
relay/media.key       # 32 字节，Base64 文本，首次启用自动生成
relay/nas.crt|.key    # NAS 的 mTLS 证书，首次启用自动生成
relay/devices.json    # 本机记录的已配对设备（client_id/label/指纹/时间）
```

`relay.json`：

```json
{
  "enabled": true,
  "addr": "relay.example.com:21443",
  "serverFingerprint": "sha256/…",
  "hostToken": "…",
  "readaheadFragments": 8,
  "maxInflightReads": 4,
  "snapshotPollSec": 60
}
```

- `hostToken` 来自 relay 首次启动打印的 `podrelay://relay?...` 引导串；面板粘贴一次即可。
- `nas.crt/key`、`media.key` 缺失时自动生成，用户不再需要准备或导入任何文件。
- `devices.json` 只用于面板展示与撤销；真正的授权列表在 relay 的 `auth.json`。

### 10.7 Tauri 命令与 UI

新增命令（`commands.rs`）：

| 命令 | 作用 |
|---|---|
| `relay_status` | 配置 + 运行状态 + 媒体密钥/`key_id` + 已配对设备 + 局域网地址 |
| `relay_apply_bootstrap` | 解析 `podrelay://relay?...` 引导串，写入地址/指纹/host token 并重连 |
| `relay_apply_settings` | 高级模式下手动保存配置 |
| `relay_start_pairing` | 生成 pairing token、手机证书，返回二维码 SVG 与过期时间 |
| `relay_cancel_pairing` | 作废当前配对码 |
| `relay_revoke_device` | 撤销单台设备（同步 `REVOKE_CLIENT` 到 relay） |
| `relay_reset_identity` | 重新生成 NAS 证书并用 host token 重新绑定（不影响已配对设备） |
| `relay_reset_encryption` | 换 `media_key` 并撤销全部设备（强警告：全部缓存作废） |

前端 `src/components/RelayPanel.tsx`：

- 首次使用：一个「粘贴中转服务器信息」输入框（`podrelay://relay?...`）；
- 连接成功后显示快照版本、缓存水位、媒体密钥与 `key_id`；
- 「添加设备」：二维码 + 倒计时 + 作废按钮 + 可选的"需要在本机确认"开关；
- 设备列表、撤销、"重置中继身份"、"重置端到端加密"（红色确认框）；
- 所有配置持久化在 `relay.json`，重启后保留。

`lib.rs` 的 `setup` 中：读取配置 → 若 `enabled` 则 `pod_relay_agent::start(HostBackend::new(...), config)`；
配置变更时 `stop()` 再 `start()`。返回的 `AgentHandle` 放进现有 `AppState`，进程退出时随 `Drop` 关闭连接。

---

## 11. Android 客户端接入

### 11.1 模块

| 文件 | 职责 |
|---|---|
| `RelayConfig.kt` | 配置持久化：启用、地址、指纹、`media_key`、代理端口；证书/私钥由配对流程写入 `filesDir/relay/`，手动 PEM 仅作为高级项 |
| `RelayProto.kt` | 帧编解码与消息常量（与 Rust 一一对应）；`fileId(kind, key)` 同算法 |
| `RelayCrypto.kt` | HMAC 派生、`AES/GCM/NoPadding`、快照解密与 gzip 解压 |
| `RelayTls.kt` | PEM 解析、`KeyManagerFactory`、指纹固定的 `X509TrustManager`、建连 |
| `RelayClient.kt` | 长连接、`req` 多路复用、PING、重连退避、STAT/资料库版本缓存、在途请求管理 |
| `RelayStore.kt` | 解密后的快照 JSON 落盘（按版本）；分片内存 LRU（32 MiB） |
| `RelayProxy.kt` | `127.0.0.1:47001` 的 HTTP/1.1 服务（复用手写 HTTP 与 `HttpUtil` 风格） |
| `RelayService.kt` | 生命周期与状态 `StateFlow`：连接状态、`deviceId/deviceName`、端点 |
| `RelayPairing.kt` | 局域网配对：解析 `podrelay://pair` 深链、调用 NAS `/relay/pair`、写入证书与密钥 |
| `RelayPairUri.kt` | 配对 URI 的解析与校验（纯 JVM 可测） |

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

### 11.4 加入流程与设置界面

入口保持不变：「设置 → 通用 → 公网中继」。默认界面只有三件事：

1. **扫码/粘贴配对**：`podrelay://pair?...` 深链（系统相机扫码后点击）或手动粘贴；
2. 手机与 NAS 在同一局域网时，点击「加入」→ 调用 `POST http://<h>:<p>/relay/pair` → 自动拿到证书、私钥、`media_key` 与中继地址/指纹 → 写入 `filesDir/relay/` 并启动服务；
3. 状态区：已连接 / 源设备在线 / 快照版本 / 最近错误。

手动配置（地址、指纹、证书 PEM、`media_key`）折叠在「高级」里，供调试与跨网段场景使用。

配对失败要区分原因：不在同一局域网（连接超时）、配对码过期/已使用/已作废、NAS 未连接中继。

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
| 证书更换 | NAS 重新生成证书后用 host token 重新绑定；relay 自动替换指纹，无需手改配置 |
| 设备被撤销 | relay 在 `HELLO` 阶段回 `device_revoked`；手机清空中继配置并提示重新配对，已下载媒体不受影响 |
| 配对码过期/误用 | NAS 端丢弃并按 `pairing token` TTL 拒绝；面板提示重新生成 |
| 手机与 NAS 不同局域网 | 配对请求连不上 NAS；设置页提示"需要与 NAS 在同一 Wi-Fi" |
| 更换媒体密钥 | relay 检测 `key_id` 变化并清空 fragment/snapshot；所有手机需重新配对 |
| NAS 端后端端口变化 | agent 不经端口，直接走内存路由，不受影响 |
| 超大文件（如 100 MB EPUB） | 约 400 分片整本下载；代理按流水持续供片，单分片远小于客户端 45 秒读超时 |
| 重复 `WANT` 风暴 | 中继去重 + 在途上限 + 客户端重试退避 |
| Windows 断开重连 | agent 指数退避重连；重连后按 `host_lib_version` 决定是否重推快照 |

---

## 13. 配置样例

### 13.1 中继服务器 `relay.json`

完整示例文件：`relay-server/relay.json.example`（单元测试会校验它始终能解析）。

```json
{
  "listen": "0.0.0.0:21443",
  "publicAddr": "relay.example.com:21443",
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

`certs/` 与 `auth.json` 首次启动自动生成，无需手写：

```json
{
  "host": "sha256/…",
  "hostToken": "…",
  "keyId": 4602678819172646912,
  "clients": [
    { "id": "5f2c…", "label": "Pixel 9", "fingerprint": "sha256/…",
      "createdAt": 1730000000, "lastSeenAt": 1730000600 }
  ]
}
```

`publicAddr` 决定引导串里打印的公网地址；缺省时用 `listen`。`tls` 字段也可省略，表示证书自动生成在 `store/certs/`。

### 13.2 NAS `relay.json`

见 10.6；`hostToken` 来自 relay 首次启动打印的引导串。

### 13.3 客户端

由配对流程写入 SharedPreferences 与 `filesDir/relay/`；手动配置折叠在高级设置里。

---

## 14. 部署

部署只保留三步：启动 relay → 复制一行 → 在 NAS 粘贴。

### 14.1 公网服务器

```bash
# 1. 只放行一个高位端口（80/443 保持屏蔽，方案不需要它们）
firewall-cmd --add-port=21443/tcp --permanent && firewall-cmd --reload   # 或对应云安全组规则

# 2. 首次启动：自动生成证书与 host token，并打印引导串
#    在配置里加 "publicAddr": "relay.example.com:21443"，引导串才会带上公网地址
relay-server server --config /etc/pod-relay/relay.json
#   [pod-relay] generated TLS certificate  sha256/AbCd...
#   [pod-relay] host enrollment ready
#   podrelay://relay?v=2&a=relay.example.com:21443&p=sha256/AbCd...&t=...

systemctl enable --now pod-relay
```

`relay.json` 只保留 `listen` / `store` / `limits` / `watermark`；证书与 `auth.json` 自动生成在 `store` 同级目录。忘记引导串时执行 `relay-server show` 再打印一次。

构建：`cd relay-server && cargo build --release -p pod-relay-cli`，产物 `target/release/relay-server`；无外部运行时依赖、无 HTTP 端口、无反向代理。

### 14.2 NAS（Windows）

1. 打开桌面端「公网中继」，把 `podrelay://relay?...` 整行粘进去（地址、指纹、host token 一次填好）。
2. 首次启用会自动生成 `media_key` 与 NAS 证书，无需任何证书文件操作。
3. 面板显示"已连接 · 快照版本 N"即完成。

NAS 只需出站访问 `21443/tcp`，不需要任何入站端口；但**配对时手机必须与 NAS 在同一局域网**。

### 14.3 Android

1. NAS 面板点「添加设备」，显示二维码（10 分钟有效）。
2. 手机连同一个 Wi-Fi，用系统相机扫码并点击链接，确认后自动完成证书与密钥交接。
3. 回到资料库列表即可看到 NAS；在家走局域网，在外走中继。

换手机：撤销旧设备 → 重新扫码。换媒体密钥：面板「重置端到端加密」→ 所有手机重新扫码。

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
| P0 | 协议 v2：`HELLO.auth`、设备管理消息、错误码；relay 首次启动自动生成证书与 host token | `cargo run -p pod-relay-cli -- selftest` 覆盖 host 绑定/客户端授权/撤销/换 key 清库 |
| P1 | relay 动态授权：`auth.json`、`AUTHORIZE/REVOKE/LIST/RESET`、限速、`key_id` 变化清库 | 手改配置不再需要；重启后授权仍在 |
| P2 | 桌面端自动签发：NAS 证书、引导串粘贴、手机证书生成、`POST /relay/pair`、`devices.json` | 面板完成引导 + 配对 + 撤销全流程 |
| P3 | 桌面端 UI：二维码（SVG）、倒计时、设备列表、重置中继身份/端到端加密 | 配置全部持久化；警告文案与二次确认齐全 |
| P4 | Android：`podrelay` 深链、局域网配对、设置页重构、错误提示 | `compileDebugKotlin` + 配对 URI 单测通过 |
| P5 | 端到端联调（本机模拟手机）与文档更新 | 配对 → 连接 → 播放 → 撤销 → 重新配对全链路走通 |

提交拆分（英文）：

1. `relay-server: add token-based host binding and dynamic client authorization`
2. `relay-server: bootstrap TLS material and enrollment token on first start`
3. `relay-server: purge cached media when the media key changes`
4. `desktop-app: auto-generate relay identity and serve LAN pairing`
5. `desktop-app: add QR pairing, device management and encryption reset`
6. `android: join a relay through LAN pairing`
7. `docs: document the zero-touch relay setup`

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

v2（本文档的目标形态）已经落地：

| 组件 | 位置 | 验证方式 |
|---|---|---|
| 协议 v2（HELLO.auth、动态授权、错误码） | `relay-server/protocol` | `cargo test -p pod-relay-protocol` |
| relay 自动 bootstrap、`auth.json`、授权/撤销、换 key 清库 | `relay-server/server` | `cargo run -p pod-relay-cli -- selftest` |
| `relay-server show` / `server` / `gencert` | `relay-server/cli` | 首次启动会打印 `podrelay://relay?...` |
| NAS agent（host token、控制通道） | `relay-server/agent` | selftest 会走 host 绑定 + 客户端授权 |
| 桌面端自动证书、引导串、局域网配对、设备登记、二维码 | `desktop-app/src-tauri/src/relay_host.rs`、`relay_pairing.rs`、`RelayPanel.tsx` | `cargo check`、`npm run build` |
| Android 深链配对、证书/密钥落地、状态页 | `app/src/main/java/com/aurora/music/data/relay/*`、`RelaySettingsScreen.kt` | `compileDebugKotlin`、`testDebugUnitTest` |

常用命令：

```bash
cd relay-server
cargo test
cargo run -p pod-relay-cli -- selftest
cargo build --release -p pod-relay-cli
```

现在的接入流程就是 §14 的三步：启动 relay（自动生成证书与 token）→ 把打印的
`podrelay://relay?...` 粘到 NAS 面板 → NAS 点「添加设备」，手机系统相机扫码。

### 19.1 兼容与迁移

- v1（手改指纹）与 v2（动态授权）协议不同，不能混用；升级后 NAS 与手机各配对一次。
- 升级时保留现有 `media_key` 可继续使用已缓存分片；只有「重置端到端加密」才清空 relay 缓存并让所有设备重新配对。
- 旧的 `hostFingerprints` / `clientFingerprints` / `certPath` / `keyPath` 配置项在 v2 中被忽略，可删除；面板会回到"粘贴引导串"状态。
- `relay-server/tmp/` 是自测产物目录，已加入 `.gitignore`，由人工清理。
