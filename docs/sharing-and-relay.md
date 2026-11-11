# 局域网共享与中继

两套机制解决的是同一个问题的两端：

- **局域网共享**：同一 Wi-Fi 下，把本机资料库以 HTTP 暴露给另一台 nostalgiaPod。
- **PodRelay 中继**：两台设备不在同一网络时，通过一台公网 relay 转发，家庭侧设备
  主动出站，不需要公网 IP 或端口映射。

两者互不复用：局域网是明文 HTTP + mDNS，中继是 TLS 上的自定义二进制帧。
上层的"远程库"只认虚拟 URL，所以传输层可以互换（见 `casting.md`）。

## 1. 局域网发现与服务

### 发现

```
             mDNS  _aurora-cast._tcp
  手机 A  ←────────────────────────→  手机 B
             UDP 47823 probe/offer（兜底）
```

- 身份：一个持久化 UUID（SharedPreferences），跨重启稳定。
- TXT 记录：协议版本、id、设备名、状态。
- UDP 兜底用于 mDNS 被过滤的网络（AP 隔离、部分 mesh 路由）：空闲时 2 秒探一次，
  稳定后 10 秒一次；收到 probe 回 offer。
- 网络切换后重新注册并重播 offer，避免对端缓存旧 IP。

### HTTP 服务

本机是一个小型 HTTP 服务器，端口优先 8080–8099，占用则用临时端口。

| 路径 | 用途 |
|---|---|
| `/library/version` | 库版本签名（单调递增/时间戳） |
| `/library/all` | 完整库快照（音乐 + 电子书根 + 电子书 + 有声书） |
| `/media/*` | 媒体字节流，支持 `Range` |
| `/art`、`/font` | 封面、阅读器字体 |
| `/state`、`/queue` | 投送端播放状态与队列 |
| `/anchor`、`/chapter` | 电子书阅读锚点与章节内容 |
| `/cast/*` | 播放控制（play/pause/seek/next/jump/shuffle/repeat/like） |
| `/`（`?recv=1`） | 接收端用的网页阅读器 |

关键实现点：

- **只服务索引里登记过的路径**，没有任意文件读取入口，因此不存在路径穿越面。
- `content://`（SAF）文件通过 `AssetFileDescriptor` 做区间读取，不需要先复制到磁盘。
  这是它能对 SAF 文件支持 `Range` 的原因。
- `Range` 解析和 206 响应是手写的，复制缓冲区 128 KB。
- 库版本签名是持久化的 FNV 风格摘要，避免每次请求重算全库。

### 权限模型

局域网投送**没有配对、没有 token、没有 PIN**：只要 offer 合法就接受。
安全边界就是"局域网可达"。代码里留有信任列表/二次确认的 TODO，但未实现。
对个人曲库这是可接受的取舍，但文档必须说明：能连上端口的人就能推音频/视频。

## 2. 中继拓扑

```
┌────────────┐   出站 TLS 21443    ┌──────────────┐   出站 TLS    ┌────────────┐
│ Android    │ ──────────────────→ │ relay server │ ←──────────── │ NAS agent  │
│ ROLE_CLIENT│                     │  (公网)      │               │ ROLE_HOST  │
└────────────┘                     └──────────────┘               └────────────┘
```

- 两端都主动连 relay，所以家庭网络不需要开放端口。
- relay 只转发密文分片，**永远拿不到媒体密钥**——"不可信中继"是核心假设。
- relay 是单点：挂了公网路径失效，但局域网路径不受影响。

### 协议栈

```
TLS 1.2/1.3（自签证书 + SHA-256 pinning）
  └── 自定义帧： [u32 len][u8 type][u32 req][payload]   大端，最大 4 MiB
        └── 消息： HELLO / PING / AUTHORIZE / ROOT_MAP_* / SNAPSHOT_* /
                   STAT / WANT / FRAGMENT / LIVE_* / CACHE_STATUS / ...
```

`req` 是多路复用 id：同一个连接上并发多个请求，relay 自己发起的用 0。

不用 HTTP/2 是刻意的：省掉 header 膨胀、HPACK 状态和 100-continue 之类的坑，
协议只有几十种消息，自己分帧更可控。

### 加密分层

- 传输层：TLS + 证书 pinning。证书只做通道鉴权，真正的授权在 `HELLO` 之后判断，
  这样换证书不需要动授权表。
- 应用层：AES-256-GCM。密钥由主密钥按用途派生（id / media / meta / live），
  `key_id` 用于检测密钥轮换（变了就清缓存）。
- 分片 nonce 用确定性 salt（`HMAC(k, "salt"‖file_id)`），同一文件重复推送不会
  破坏 nonce 稳定性，也不需要额外存随机 salt。
- 快照密文带 AAD 魔数，防止跨上下文拼接。
- 文件身份 `file_id = SHA256(kind‖0‖key)[0..16]`，两端各自实现并对齐。

### 配对

三种 URI：

| URI | 用途 |
|---|---|
| `podrelay://relay?v=2&a=&p=&t=` | 引导：relay 地址 + 证书指纹 + host token |
| `podrelay://pair?v=2&h&p&t&n&id` | 设备配对（桌面端生成二维码，TTL 10 分钟） |
| `podrelay://remote?v=1&d=<b64url(gzip(json))>` | 远端一次性访问凭证，带过期时间 |

### 授权（ACL）

- agent 把 `file_id → root_id` 的映射分块推给 relay（每块 4096 项）。
- 每台客户端被授予若干 root；`all=true` 直接放行。
- 映射未知或未就绪 → **拒绝**（fail closed）。宁可暂时不可用，不可越权。
- 快照按 root 过滤，只下发该客户端可见的音乐/电子书/有声书。

## 3. 省流量设计

这是中继最容易被误解的部分。先给结论：

> 预取**不减少**家庭 NAS 的总上行。它只是把字节提前搬到 relay。
> 真正省的是"重复访问命中 relay 缓存"，以及"库没变时只发一个版本号"。

### 机制清单

| 机制 | 做法 | 参数 |
|---|---|---|
| 版本门控 | 客户端每 15 秒问一次 `/library/version`，变了才拉全量快照 | 15s |
| 快照压缩 | gzip + 加密，512 KiB 一块，只保留最新两版 | — |
| 分片缓存 | 256 KiB 一片，`.dat` + `.bits` 位图 + `.meta` | 默认 20 GiB |
| 淘汰 | LRU，跳过被 PIN 的文件 | — |
| 水位节流 | 缓存压力 0/1/2 通知客户端降低预读 | 0.85 / 0.98 |
| 预推 | agent 提前推分片，优先级 1=显式 WANT，2=预读 | `readAheadFragments` |
| 去重 | agent 侧记录已发送分片 | — |
| 限速 | 每客户端 40 want/s，在途 32，全局 128，最多 8 会话 | — |
| 客户端缓存 | 32 MiB 明文分片 LRU + 快照缓存 | — |
| 播放缓存 | Media3 `SimpleCache` + 虚拟 URL，LAN/中继切换命中同一份缓存 | — |

具体收益：

- **库没变**：每 15 秒一个几十字节的请求，而不是 1–3 MB 的快照。
- **重复听同一首**：第二次直接命中 relay 分片缓存，NAS 上行 0。
- **切网络**：虚拟 URL 不变，Media3 缓存继续命中，不用重下。

`CACHE_STATUS pressure` 到 0.85 后客户端会缩小预读窗口，到 0.98 进一步降级。
这比"让 relay 拒绝请求"体验好：播放不断，只是预取变慢。

## 4. 协议与实现的状态差

`remote_database_relay.md` 是设计规格，比代码更"理想化"。当前差异：

- `WANT` 的排队/优先级在服务端没有实现，服务端收到即转发；去重只在 agent 的
  `sent` map 里。
- `fileTtlDays`（默认 14 天）写在配置和文档里，但存储层淘汰只按 LRU，没有强制 TTL。
- `EVICT_NOTICE` 有定义，服务端从不发送。
- `ADMIN_STATS` 没有限制为 host，任何已授权会话都能读。
- Android 只实现协议子集：不处理 `SNAPSHOT_*` 推送、`ROOT_MAP`、`WANT`、
  `CACHE_STATUS`。手机端是"拉模式"，中继的推送优化对手机暂不生效。
- PING 周期文档写 30s，Android 实际 5s；agent 靠 30s 读超时兜底，不发显式 PING。

这些不影响可用性，但如果照着文档做容量规划会高估优化效果。

## 5. 设计取舍

1. **明文 HTTP + 无鉴权的局域网**：换零配置体验。适合家庭网络，不适合公共 Wi-Fi。
2. **自签证书 + pinning**：不需要 CA，不需要域名；代价是证书轮换、备份、多端同步
   指纹都要自己处理，这也是最容易出错的环节。
3. **预推而不是纯拉取**：起播稳定、跨网一致；代价是 relay 磁盘和 NAS 上行都被
   提前消耗，而且收益依赖缓存命中率。
4. **固定 256 KiB 分片**：实现简单、位图紧凑；代价是尾部碎片浪费和预读粒度偏粗。
5. **快照全量推送**：没有增量同步。几万首曲目 gzip 后 1–3 MB，可接受；再大就需要
   真正的增量协议。

## 关键文件

局域网：`data/ebook/EbookCast.kt`、`data/cast/LocalLibraryHttp.kt`、
`CastDiscovery.kt`、`CastHttp.kt`、`CastPermission.kt`、`RemoteUrls.kt`。

中继：`data/relay/{RelayClient,RelayService,RelayProxy,RelayProto,RelayCrypto,RelayTls,RelayStore,RelayPairing}.kt`；
`relay-server/{protocol,server,agent,cli}`；桌面端 `src-tauri/src/relay_*.rs`。

设计规格：`remote_database.md`（局域网 v1）、`remote_database_relay.md`（中继）。
