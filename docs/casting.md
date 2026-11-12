# 投屏：两种实现、UI 接口与网络优化

## 0. 先厘清"两种投屏"

代码里其实有**两条正交的轴**，把它们的名字混在一起是理解这套系统最大的障碍：

| 轴 | 方向 | 本机角色 | 代表实现 |
|---|---|---|---|
| **A. 接收端** | 远端 → 本机 | 当屏幕/音箱 | AirPlay 接收、Pod 接收 |
| **B. 远程库播放** | 本机 → 远端 | 当客户端，读对端的文件 | 库快照 + 虚拟 URL + HTTP Range |

轴 A 还可以再分两种协议，它们共用一个 Hub、一套 UI 接口，但解码、传输、控制通道
完全不同：

- **AirPlay**：RTSP 控制 + RTP 音频 + TCP 镜像视频。
- **Pod 协议**：HTTP 轮询对端状态 + ExoPlayer 拉流；电子书用 WebView 渲染对端页面。

轴 B 不是"把画面推出去"，它只是把另一台设备的库当成只读文件系统，播放仍在本机。
它和轴 A 唯一的交集是发现机制、缓存层和中继。

下面的"两种投屏"按**协议实现**来讲：AirPlay 与 Pod；轴 B 单独一节，因为它是
"投屏"这个词最容易被误解的部分。

## 1. 接收端总体结构

```
               ┌──────────────────────────────────────┐
   AirPlay ───→│ AirPlayReceiver                      │
   (RTSP/RTP)  │  RTSP server → RTP → 解码 → 时间线   │
               │  → PcmStreamPlayer → DSP → AudioTrack │
               │  → VideoRenderer (SurfaceTexture/GL)  │
               └───────────────┬──────────────────────┘
                               │
               ┌───────────────▼──────────────────────┐
   Pod ───────→│ CastReceiverController               │
   (HTTP 轮询) │  /state 700ms → ExoPlayer / WebView   │
               └───────────────┬──────────────────────┘
                               │
                    CastReceiverHub（唯一状态机）
                               │
              ReceiveNowPlaying（统一快照）
                               │
        ┌──────────────────────┼───────────────────────┐
        ▼                      ▼                       ▼
 CastSessionPlayer      CastReceiverOverlay     StandardCastOverlay
 （系统 MediaSession）     （Pod：队列/阅读器）      （AirPlay：镜像画面）
```

`CastReceiverHub` 是 UI 唯一对话的对象。它把两种协议抽象成同一个
`CastProtocolReceiver` 接口（start/stop/seek/play/pause/next/previous/
setVideoSurface + 一组 listener 回调），并用 `CastSessionArbiter` 处理
"两个协议同时有会话"时的抢占，避免双份音频焦点。

## 2. AirPlay 实现

### 2.1 发现与握手

- 用 `NsdManager` 同时广播 `_raop._tcp` 和 `_airplay._tcp`，固定设备 UUID，
  这样重启后发送端仍认得同一台设备。
- RTSP 服务器优先绑 5000，失败则 +1…+10，再失败用临时端口。
- 支持 `ANNOUNCE / SETUP / RECORD / PAUSE / FLUSH / TEARDOWN / SET_PARAMETER`
  以及 `/info`、`/fp-setup`。
- 流类型：96 = 音频，110 = 镜像视频。AirPlay 2 的缓冲音频类型 103 没有实现，
  所以现代设备大概率回落到 AirPlay 1 的 RAOP。

### 2.2 音频

- 编码：ALAC（JNI）、AAC/AAC-ELD（MediaCodec）、PCM 直通。
- 编码以 SETUP 里 96 号流的 `ct` 为准（2=ALAC，8=AAC-ELD，其他=AAC），
  不沿用上一条流残留的格式；否则镜像音频之后换回音乐会把 ALAC 包喂给
  AAC 解码器，表现为「包一直在来、解码失败猛涨、没有声音」。
- 发送端可以在同一会话里重新 SETUP 一套 FairPlay AES key。新 key 会推给
  正在跑的收包线程（`AirPlayAudioReceiver.updateKeys`）并重置重排窗口，
  不需要等新流建立，避免「换 key 之后每个包都解不出来」。
- FairPlay key 按 RTSP 连接缓存：macOS 一条连接只下过一次 `eiv/ekey`，换歌时
  TEARDOWN 后再 SETUP 的 96 流不会再带 key。新 Session 没有收到 ANNOUNCE、
  也没拿到新的 `eiv/ekey` 时沿用本连接上一次解出的 key，否则会把密文当明文
  交给 ALAC，表现为「换歌后解码失败狂涨、没有声音」。
- `00 68 34 00` 的 4 字节 no-data 包对所有编码都直接丢弃（镜像音频开头的
  几个包就是它，喂给 MediaCodec 会让解码器卡在错误状态）。
- AAC 解码器连续 100 个包没有 PCM 输出就重建一次（最多 3 次）。
- 乱序重排窗口 128 包；丢包通过控制通道发重传请求（每次 ≤64 包），
  等待 `resendWait` 后重试，最多 `resendMaxRetries` 次；仍不齐就 **1.5 秒后硬重同步**。
- 解码失败最多补 3 帧 PLC，之后静音；日志里会带当前 key 指纹和连续失败次数，
  方便对照 `audio stream:` / `FairPlay key installed` 判断是 key 还是 codec 不匹配。

### 2.3 抖动缓冲（重点）

`AirPlayTimelineBuffer` 是**固定 cushion**，不是自适应：

```
物理环形缓冲 2000 ms
  └── 目标提前量 bufferMs（默认 800，可调 500–1500）
        └── 每 10 ms 取一块交给 AudioSink（sink 目标 80 ms）
```

关键常数：

| 参数 | 值 | 作用 |
|---|---|---|
| `bufferMs` | 800（500–1500） | 抖动 cushion，同时决定上报的 `Audio-Latency` |
| 环形缓冲 | 2000 ms | 物理上限 |
| 调度余量 | 3 ms | 避免刚好卡在边界 |
| 不连续阈值 | 750 ms | 超过就重新锚定，而不是无限等待 |
| 修剪 | 下限 30 ms，持续 2s | 慢慢排掉积压，不产生可闻空隙 |
| PLC | 20ms 历史、0.85 衰减、3ms 淡入 | 丢包时用上一段音频过渡 |

**为什么不用自适应**：早期做过基于直方图的延迟跟踪，结果是在镜像时延迟会缓慢爬升，
音画偏移不可预测。固定 cushion 的代价是"网络差时只能靠用户把 800 调大"，收益是
延迟可解释、`Audio-Latency` 上报准确。代码里还留着已失效的 `DelayTracker`。

**音画同步的耦合**：`Audio-Latency` 头直接用 `bufferMs` 算。意味着调大抖动缓冲
必然让镜像视频更晚显示。这是"音频连续优先于口型同步"的明确取舍。

### 2.4 视频镜像

- TCP 接收，128 字节帧头（类型、长度、NTP 时间戳）。
- H.264/H.265 从 AVCC 转 Annex-B，AES 解密。
- 持久 GL 管线：`SurfaceTexture → EGL blit → UI 提供的 Surface`。
- MediaCodec 走低延迟阶梯配置；输出 buffer 用
  `releaseOutputBuffer(idx, renderTimestampNs)` 按 NTP 映射到墙钟渲染，
  而不是立即释放，避免和 VSYNC 打架。
- 丢帧/迟到统计每 1 秒重置。

### 2.5 控制与元数据

- RTSP `SET_PARAMETER` 携带音量、进度、元数据、DACP ID。
- 上一首/下一首只能通过 DACP 反向通道（解析发送端的 `_dacp._tcp` 后发
  `/ctrl-int/1/...`）。DACP 没解析成功时，`canNext/canPrevious` 为 false。

## 3. Pod 接收实现

- **轮询**而不是长连接：音乐每 700ms 拉 `/state`，电子书每 1000ms；失败 800ms 后重试，
  连续 8 次失败才断开。
- 队列只在版本号变化时重拉（`/queue?v=`）。
- 音频用独立的 ExoPlayer 拉 `<base>/audio?t=<id>`，服务端支持 Range。
- 进度条以本地播放器位置为准；只有本地与远端差值 > 1800ms 才 seek，避免频繁校正产生爆音。
- 控制走 `/cast/command?cmd=...`，seek 默认 250ms 节流（拖动结束才 commit）。
- 音量是"前级"语义：`player.volume = preamp × introDuck`，不改系统音量；
  本机朗读时 duck 到 0.25。
- 电子书不本地渲染，直接用 WebView 打开对端阅读器页面，位置通过锚点码同步。

轮询的取舍：控制延迟平均 350ms、位置精度粗，但实现极稳、对 NAT 友好
（接收端只需要向发送端发起出站 HTTP）。

## 4. 远程库播放（轴 B）

### 4.1 库快照

- 每 15 秒问一次对端 `/library/version`；变了才拉 `/library/all`。
- 拉到的快照按设备存成独立 SQLite（`remote_library/<sha1(deviceId)>.db`），
  表用 `ord` 序号主键保留对端顺序，整份事务重建。
- 连续 2 轮失败才标记离线（防止 Wi-Fi 漫游抖动）；30 天未访问删除缓存。
- 远程库**不并入本地库**，在 UI 里作为独立设备分区展示，离线时灰显。

### 4.2 虚拟 URL：让传输层可替换

媒体地址永远是：

```
pod-remote://device/<deviceId>/media/<kind>?<param>=<value>
```

真正的 `http://host:port/...` 在数据源打开时才解析。这样：

- Media3 缓存、封面缓存、队列持久化都以虚拟 URL 为键；
- 从局域网切到中继（或反过来）时，缓存继续命中，播放不中断。

代价是多一次解析跳转，排障时"缓存未命中"可能其实是 URL 映射错了。

### 4.3 缓存与下载

- 媒体：Media3 `SimpleCache`，`NoOpCacheEvictor`，靠显式保留窗口管理：
  音乐保留"上一首/当前/下一首"，有声书保留 3 章。
- 封面：单独落盘（`filesDir/remote_library/art/...`），不挤占媒体缓存。
- 下载：复用同一套虚拟 URL，下载完成的条目直接由缓存提供，不再走网络；
  下载项会合并回库视图，不会重复显示。
- 读取块 128 KB，服务端 206 响应。

## 5. UI 接口

### 5.1 统一快照

`ReceiveNowPlaying` 是两种协议唯一的公共契约：

- `ReceiveSource.POD` / `AIRPLAY`
- 能力位：`canSeek`、`canNext`、`canPrevious`
- 统一动词：play/pause/seek/next/previous/stop
- 元数据：标题、艺人、封面、进度

`CastSessionPlayer` 把这个快照包装成一个 `SimpleBasePlayer`，于是系统通知、蓝牙、
Android Auto 都能用，完全不需要知道底层协议。`CastReceiveService` 是保活的前台服务，
负责让 RTP/解码线程在后台存活。

### 5.2 两个 Overlay，而不是一个万能界面

- `CastReceiverOverlay`（Pod）：音乐队列、seek、电子书 WebView。
- `StandardCastOverlay`（AirPlay）：镜像视频 Surface + 极简音频控制。

两者都挂在导航最上层；`Idle/Connecting/Loading` 状态渲染空，避免协商期间闪一下。
AirPlay 纯音频**没有**全屏 Overlay，只在统一正在播放页/悬浮窗里出现；只有镜像才有
专属画面。这是有意的：共用"会话 + 快照"，不共用"视图"。

发送端的设备选择是另一个组件 `CastPickerCard`（在播放器里），与接收无关。

## 6. 网络优化汇总

| 层 | 机制 | 参数 |
|---|---|---|
| AirPlay 抖动 | 固定 cushion，同时作为上报延迟 | 500–1500ms，默认 800；环 2000ms |
| AirPlay 丢包 | NACK + 等待 + 重试 + 硬重同步 | 等 100ms、重试 60ms、2 次、128 包窗口、1.5s 重同步 |
| AirPlay PLC | 复用上一段音频 | 20ms 历史、0.85 衰减、最多 3 帧 |
| AirPlay socket | 大 UDP 缓冲抗镜像突发 | 数据 512KB / 控制 64KB |
| AirPlay 修剪 | 慢排积压不产生空隙 | 下限 30ms、持续 2s |
| 音频 sink | 小缓冲保持低延迟 | 10ms 块 / 80ms 目标 |
| Pod 轮询 | 粗粒度、便宜 | 700ms 音乐 / 1000ms 电子书 / 8 次失败 |
| Pod 漂移 | 避免频繁 seek | 差值 >1800ms 才校正 |
| 库同步 | 版本门控 + 防抖 + 滞后 | 15s / 首次 1.5s / 700ms 防抖 / 2 次失败 |
| 媒体流 | Range + 持久缓存 + 保留窗口 | 206 / 128KB / 前后各一首 / 有声书 3 章 |
| 中继 | 分片 pin + 预读 + 退避 | 256KB 分片 / 4MB 帧 / pin 600s / 预读 32 片 / 退避 0.5–8s |
| 发现 | mDNS + UDP 兜底 | `_aurora-cast._tcp` / 47823 / 4s 重注册 |

延迟预算（AirPlay）：抖动 cushion 800ms + 卷积校正 ~64ms + sink 80ms。
这就是镜像画面需要往后对齐的量级。

## 7. 取舍与已知问题

1. **固定 cushion vs 自适应**：可预测性换网络适应性；坏网络只能手动调。
2. **上报延迟 = 缓冲延迟**：调大缓冲必然牺牲口型同步。
3. **轮询 vs 流**：Pod 协议控制延迟高，但 NAT 友好、实现简单。
4. **虚拟 URL**：带来 LAN/中继无缝切换，也带来排障难度。
5. **`NoOpCacheEvictor`**：保留窗口之外没有全局容量上限，极端使用会无限增长。
6. **两个 Overlay**：避免把阅读器、镜像画面、队列硬塞进一个界面，代价是两条状态路径。
7. **中继当"本地设备"**：流媒体/缓存代码零改动，但多了一跳本地 HTTP。
8. **接收端零配置**：局域网内任何能连上端口的设备都能推送。个人曲库可接受，
   但必须写清楚。
9. **AirPlay 2 未实现**：只有 96/110，没有 103；新设备可能回落到 AirPlay 1。
10. **FairPlay 握手深度未验证**：镜像能否从 iOS 直接工作取决于这一步，属于高风险未知项。

## 关键文件

接收：`data/cast/receiver/{CastReceiverHub,CastProtocolReceiver,CastSessionArbiter}.kt`；
`receiver/airplay/*`（`AirPlayReceiver/AirPlayRtspServer/AirPlayRtp/AirPlayTimelineBuffer/AirPlayTuning/AirPlayAudioOutput/AirPlayDacp`）；
`data/cast/CastReceiver.kt`。

远程库：`data/cast/{RemoteLibraryManager,RemoteLibraryDb,RemoteMediaCache,RemoteUrls,RemoteArtCache,RemoteDownloadManager}.kt`；
`data/relay/*`。

UI：`ui/cast/{StandardCastOverlay,CastReceiverOverlay,CastPicker}.kt`；
`data/cast/ReceiveNowPlaying.kt`；`playback/{CastSessionPlayer,CastReceiveService}.kt`。
