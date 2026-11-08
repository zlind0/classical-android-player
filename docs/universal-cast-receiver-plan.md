# 通用投屏接收端实施方案（AirPlay / Chromecast / Miracast）

- 状态：草案（可执行）
- 适用范围：`app`（Android，`com.aurora.music`）
- 目标版本：首个版本只接收音频，视频预留接口
- 相关现有模块：
  - `data/cast/CastReceiver.kt`：已有的自有协议投屏接收端
  - `playback/PlaybackService.kt`：本机音乐播放与统一会话
  - `playback/EbookTtsPlayer.kt`：独立的 DSP 播放链参考实现
  - `playback/DspChain.kt`：`buildDspParams` / `isNeutral` 的公共装配
  - `ui/cast/CastReceiverOverlay.kt`：已有投屏浮层

---

## 1. 目标

在 App 进程存活期间，常驻监听三种标准投屏协议：

1. **AirPlay / RAOP**：接收 iPhone、iPad、Mac 的音频投送。
2. **Chromecast / Cast V2**：接收使用 Google Cast 协议的音频投送。
3. **Miracast / Wi-Fi Display**：接收 WFD 源的音频（受 Android 平台能力限制，见 §6）。

共同要求：

- App 打开即开始监听，不需要用户先进入某个页面或手动开启。
- 同一时间只允许一路投屏音频出声；新会话抢占旧会话。
- 投屏音频出现时，抢占本机正在播放的音乐、TTS、听书、旧协议投屏。
- 所有投屏音频统一经过音乐链的 DSP 效果器：
  - 图形 EQ / 参数 EQ
  - AutoEq 校正 FIR
  - 卷积 IR
  - 压缩器 / 限幅器 / 自动增益
  - 空间增强 / 交叉馈送 / 声道延迟与微调
  - 单声道 / 平衡 / 前置增益
  - 系统音效（共用 `container.audioSessionId`）
  - 可视化 PCM tap
- 投屏期间显示统一的全屏界面：
  - 专辑封面
  - 标题 / 艺术家 / 专辑
  - 播放进度
  - 上一首 / 播放暂停 / 下一首（按协议能力置灰）
- 视频只留接口：
  - 统一的状态字段（`hasVideo`、宽高、编解码器）
  - `CastVideoSink` 接口
  - UI 的视频区域占位
  - 本轮不实现 H.264/H.265 解码与渲染

### 非目标（本轮）

- AirPlay 屏幕镜像 / AirPlay 视频（FairPlay、H.264、HLS）。
- Chromecast 标签页镜像（WebRTC / Cast Streaming）。
- Miracast 视频解码与渲染。
- 多房间 / AirPlay 2 多路音频同步。
- 开机自启、退出 App 后继续接收。
- 投屏音频写入本机资料库、历史记录、Scrobble。

---

## 2. 现状与约束

### 2.1 现有自有协议投屏

`CastReceiverController` 已经能接收 App 自己的 `/cast/offer`：

- 音乐：轮询发送端 `/state`、`/queue`，用独立的 `ExoPlayer` 拉 `/audio`。
- 电子书：WebView 打开发送端页面。
- 该 `ExoPlayer` 目前没有接入音乐 DSP 链。

本方案会：

- 保留自有协议和它的浮层。
- 把它的播放器换到统一的 DSP Sink。
- 在自有协议投屏与标准协议投屏之间做单路互斥。

### 2.2 本机播放

`PlaybackService` 是单服务单会话，内部包含：

- 音乐 `ExoPlayer`
- 听书 `UnifiedPlayer` / `NeoUnifiedPlayer`
- 双播仲裁
- 音频焦点、耳机拔出、闹钟、睡眠定时

投屏抢占本机播放时，通过新增的服务 ACTION 完成，不直接操作 `PlaybackService` 私有字段。

### 2.3 外部依赖与平台事实

- **AirPlay 音频**：RAOP 是公开可实现的协议；ALAC 解码使用 Apple 开源的 `macosforge/alac`（Apache-2.0），可以放进现有 NDK/CMake。
- **Chromecast**：Cast V2 的发现、TLS、通道、媒体命令都能实现；官方发送端会在 `urn:x-cast:com.google.cast.tp.deviceauth` 阶段校验由 Google 签发的接收端证书。代码把认证抽成 `CastDeviceAuthProvider`，默认无证书；有证书数据时可以接入。
- **Miracast**：
  - Android 公共 SDK 没有 WFD sink 角色。
  - `android.media.RemoteDisplay` 是系统签名权限（`CONFIGURE_WIFI_DISPLAY`）的隐藏 API。
  - `WifiP2pManager` 允许建组/连接，但不允许 App 注入 WFD IE。
  - 因此标准 Miracast 源在普通零售机上通常不会发现本机；实现会保留完整的 RTSP/RTP/TS 音频管线，并把发现层做成可替换 transport，便于 ROM/手动连接/后续扩展。

---

## 3. 总体架构

### 3.1 分层

```text
┌─────────────────────────────────────────────────────────────┐
│ UI                                                          │
│  Ios5App → StandardCastOverlay（标准协议）                   │
│         → CastReceiverOverlay（自有协议，保留）              │
└──────────────────────────────┬──────────────────────────────┘
                               │ StateFlow
┌──────────────────────────────▼──────────────────────────────┐
│ CastReceiverHub                                             │
│  - 生命周期 / mDNS / 端口 / 前台服务                          │
│  - 统一状态：Idle / Connecting / Playing                     │
│  - 对外 API：play/pause/next/prev/seek/stop                  │
└───────────────┬──────────────────────────────┬──────────────┘
                │                              │
┌───────────────▼──────────────┐  ┌────────────▼─────────────┐
│ CastSessionArbiter           │  │ CastTransport            │
│  - generation                │  │  - 能力位                │
│  - 单路占用                  │  │  - 命令转发              │
│  - 抢占本机 / 旧投屏          │  └────────────┬─────────────┘
└───────────────┬──────────────┘               │
                │                              │
┌───────────────▼──────────────────────────────▼─────────────┐
│ CastProtocolReceiver 实现                                   │
│  AirPlayReceiver / ChromecastReceiver / MiracastReceiver    │
└───────────────┬──────────────────────────────┬─────────────┘
                │ PCM / URL                    │ 状态
┌───────────────▼──────────────┐  ┌────────────▼─────────────┐
│ DspAudioChain                │  │ CastVideoSink（本轮空）   │
│  PcmStreamPlayer             │  └──────────────────────────┘
│  ExoPlayer + DSP Sink        │
└──────────────────────────────┘
```

### 3.2 包结构

新增：

```text
com.aurora.music.data.cast.receiver
├── CastReceiverHub.kt
├── CastSessionArbiter.kt
├── CastSessionState.kt
├── CastProtocolReceiver.kt
├── CastTransport.kt
├── CastArtwork.kt
├── airplay/
│   ├── AirPlayReceiver.kt
│   ├── AirPlayMdns.kt
│   ├── AirPlayRtspServer.kt
│   ├── AirPlaySdp.kt
│   ├── AirPlayCrypto.kt
│   ├── AirPlayRtp.kt
│   ├── AirPlayMetadata.kt
│   └── AlacBridge.kt
├── chromecast/
│   ├── ChromecastReceiver.kt
│   ├── CastV2Server.kt
│   ├── CastV2Codec.kt
│   ├── CastV2Messages.kt
│   ├── CastDeviceAuthProvider.kt
│   └── CastMediaStatus.kt
├── miracast/
│   ├── MiracastReceiver.kt
│   ├── WifiP2pSinkTransport.kt
│   ├── WfdRtspServer.kt
│   ├── WfdRtp.kt
│   ├── WfdTsDemux.kt
│   └── WfdAudioDecode.kt
└── video/
    ├── CastVideoSink.kt
    └── DropVideoSink.kt
```

播放与 UI：

```text
com.aurora.music.playback
├── DspAudioChain.kt          # 公共 DSP 装配
├── PcmStreamPlayer.kt        # RTP/PCM 直接驱动 AudioSink
└── CastReceiveService.kt     # 投屏期间的 mediaPlayback 前台服务

com.aurora.music.ui.cast
└── StandardCastOverlay.kt    # 统一投屏浮层
```

### 3.3 统一状态模型

```kotlin
enum class CastProtocol { AIRPLAY, CHROMECAST, MIRACAST, LEGACY }

sealed interface CastReceiverState {
    data object Idle : CastReceiverState

    data class Connecting(
        val protocol: CastProtocol,
        val senderName: String = "",
    ) : CastReceiverState

    data class Playing(
        val protocol: CastProtocol,
        val senderName: String,
        val media: CastMediaInfo,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val capabilities: CastCapabilities,
        val video: CastVideoInfo? = null,
    ) : CastReceiverState
}

data class CastMediaInfo(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** file://、content:// 或 http(s)://，交给 Coil。 */
    val artworkUri: String = "",
)

data class CastCapabilities(
    val canPlayPause: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val canStop: Boolean = false,
)

data class CastVideoInfo(
    val codec: String = "",
    val width: Int = 0,
    val height: Int = 0,
)
```

状态只由 Hub 在主线程更新；协议线程通过 `Hub.postEvent(...)` 投递。

### 3.4 协议接口

```kotlin
interface CastProtocolReceiver {
    val protocol: CastProtocol
    fun start(listener: Listener)
    fun stop()
    fun stopSession()

    interface Listener {
        fun onSessionStarted(session: CastProtocolSession)
        fun onMediaChanged(media: CastMediaInfo)
        fun onProgress(positionMs: Long, durationMs: Long, playing: Boolean)
        fun onCapabilities(capabilities: CastCapabilities)
        fun onVideo(info: CastVideoInfo?)
        fun onSessionEnded()
    }
}

interface CastProtocolSession {
    val protocol: CastProtocol
    val senderName: String
    val capabilities: CastCapabilities

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun stop()
}
```

---

## 4. 单路仲裁

### 4.1 规则

1. 任意协议收到新会话：
   - 自增 `generation`；
   - 当前活动会话收到 `stop()`（协议层发 TEARDOWN / STOP / CLOSE）；
   - 旧会话后续回调因 generation 不匹配被丢弃；
   - 清空旧会话的 Player / RTP socket / 元数据；
   - 新会话进入 `Connecting`。
2. 抢占本机播放：
   - `PlaybackService.pauseForCast()`：暂停音乐、停听书、退出双播；
   - `container.ebookTts.stop()`：停朗读；
   - `container.castReceiver.stop(sendStop = true)`：停自有协议投屏；
   - 新播放器请求音频焦点，作为第二道保险。
3. 反向抢占：
   - 本机音乐开始播放、听书开始朗读、自有协议收到新 offer 时，Hub 停止当前标准会话。
   - 通过显式 hook 触发，不靠轮询。
4. 同一协议内新发送端：
   - AirPlay：新 `ANNOUNCE` 顶掉旧连接；
   - Chromecast：新 sender 的 `LOAD` 顶掉旧 sender；
   - Miracast：新 `SETUP/PLAY` 顶掉旧会话。

### 4.2 generation

```kotlin
class CastSessionArbiter {
    private var generation = 0L

    @Synchronized
    fun begin(protocol: CastProtocol): Long {
        generation += 1
        return generation
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation

    @Synchronized
    fun end(token: Long) {
        if (token == generation) generation += 1
    }
}
```

所有异步回调先检查 `isCurrent(token)`，避免旧 RTP 包、旧轮询、旧播放器事件把新会话覆盖。

### 4.3 音频焦点

- `PcmStreamPlayer` 和 Chromecast 的 `ExoPlayer` 都请求 `AUDIOFOCUS_GAIN`。
- 本机 `PlaybackService` 的 ExoPlayer 已启用 `handleAudioFocus`，收到焦点丢失会自动暂停。
- TTS 使用 `handleAudioFocus = false`，所以必须显式 `stop()`。
- 投屏暂停/结束时释放焦点；是否恢复本机音乐由用户决定，不自动恢复。

---

## 5. 音频链路

### 5.1 为什么拆两条路径

- **Chromecast** 的媒体是 URL，交给 `ExoPlayer` 最稳（HLS、Range、缓存、缓冲控制都现成）。
- **AirPlay / Miracast** 的音频是 RTP 包，需要自己解密、重排、解码成 PCM；用 `ExoPlayer` 的 `MediaSource` 反而要写自定义 `SampleStream`，不如直接驱动 `AudioSink`。

两条路径共用同一组 `AudioProcessor` 和同一套参数，输出效果一致。

### 5.2 `DspAudioChain`

职责：

- 持有处理器实例（每个播放器一套，不能跨播放器共享状态）：
  - `MonoAudioProcessor`
  - `ConvolutionProcessor`（校正 FIR）
  - `AuroraDspProcessor`
  - `ConvolutionProcessor`（用户 IR）
- 订阅设置：
  - `settingsStore.audioPrefs`
  - `settingsStore.correctionProfiles`
  - `settingsStore.activeCorrectionId`
  - `settingsStore.correctionLowcutHz` / `correctionHighcutHz`
  - `settingsStore.unifiedTts`（可选，投屏不需要 ducking）
- 校正 FIR：
  - 与 `EbookTtsPlayer` 一致，统一按 48 kHz 编译；
  - `ConvolutionProcessor` 内部按实际输出采样率重采样 IR；
  - 切歌/切采样率不需要重新编译。
- 参数：
  - `buildDspParams(ap, correctionMaxGainDb, correctionTrimDb, 0f)`
  - 投屏没有单曲响度数据，`driveGainDb = 0`；压缩、限幅、EQ、空间等照常。
- 输出：
  - `createSink(context): AudioSink`
    - `DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(...)).setEnableFloatOutput(false).build()`
    - 外面包 `TappingAudioSink`，接 `container.visualizer`
  - `audioSessionId = container.audioSessionId`。

### 5.3 URL 路径（Chromecast）

```text
ExoPlayer
  ├─ DefaultMediaSourceFactory(DefaultDataSource.Factory)
  ├─ DefaultRenderersFactory.buildAudioSink → DspAudioChain.createSink()
  ├─ setAudioSessionId(container.audioSessionId)
  ├─ setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC, handleAudioFocus = true)
  └─ 视频轨通过 TrackSelectionParameters 默认禁用
```

- 支持渐进式 HTTP、HLS（已有 `media3-exoplayer-hls`）。
- 不启用 USB bit-perfect 路径；投屏音频是压缩流，走普通 DSP 链。
- 播放状态通过 `Player.Listener` 推给 Hub，再转成 `MEDIA_STATUS`。

### 5.4 PCM 路径（AirPlay / Miracast）：`PcmStreamPlayer`

不要自己写一份 AudioTrack 输出循环，直接复用 `AudioSink`：

```kotlin
val sink = DspAudioChain.createSink(context)   // 已含 processors + TappingAudioSink
sink.setAudioAttributes(mediaAttributes)
sink.setAudioSessionId(container.audioSessionId)
sink.configure(
    Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelCount(2)
        .build(),
    0,
    null,
)
sink.play()

// 解码线程
sink.handleBuffer(pcmBuffer, presentationTimeUs, 1)
```

要点：

- `DefaultAudioSink` 内部有播放线程，`handleBuffer` 在缓冲满时会阻塞，天然按播放速度节流。
- 暂停：`sink.pause()` + `sink.flush()`；恢复：`sink.play()`，AirPlay 跳到最新包。
- 结束：`sink.handleEndOfStream()`。
- 采样率支持 44.1 kHz / 48 kHz；ALAC 常见 44.1 kHz。
- 统一转成 16-bit stereo；源若不是 16-bit，在解码层降/转位深。
- 音频焦点由 `PcmStreamPlayer` 的 `AudioManager` 请求/释放；焦点丢失时暂停并通知 Hub。
- 处理线程优先级：`THREAD_PRIORITY_AUDIO`。
- 抖动缓冲目标：
  - AirPlay：约 1.0–1.5 s；
  - Miracast：约 0.5–1.0 s；
  - 以 RTP 时间戳重排，sequence 断档先补静音再继续。

### 5.5 旧协议投屏接入 DSP

`CastReceiverController.ensurePlayer()` 改为：

- 使用 `DspAudioChain.createSink()`；
- `setAudioSessionId(container.audioSessionId)`；
- 继续保留原有的发送端 `/state` 轮询和 `/cast/command` 双向控制。

这样“所有投射过来的音频流”都走同一套效果器。

### 5.6 可视化

- `TappingAudioSink` 已经在音乐链使用，直接复用。
- 投屏音频进入 `VisualizerController`，投屏浮层背后的可视化页面仍然可用（浮层不显示可视化，但数据不断）。

---

## 6. 三个协议的实现细节

## 6.1 AirPlay / RAOP

### 6.1.1 发现

- 使用 `NsdManager` 注册 `_raop._tcp`。
- 服务名：`<12位伪MAC>@<castIdentity.name>`。
  - Android 不暴露真实 Wi-Fi MAC；用 `castIdentity.id` 派生稳定的 12 位十六进制。
- TXT（音频-only）：
  - `txtvers=1`
  - `ch=2`
  - `cn=0,1`
  - `et=0,3,5`
  - `sv=false`
  - `da=true`
  - `sr=44100`
  - `ss=16`
  - `pw=false`
  - `vn=65537`
  - `tp=UDP`
  - `md=0,1,2`
  - `sm=false`
  - `ek=1`
  - `vs=130.14`
  - `am=AppleTV3,2`
  - `sf=0x4`
  - `ft=<音频能力位>`
- 首版只注册 `_raop._tcp`，避免发送端把本机当成 AirPlay 2 视频接收端而进入配对流程。
- 网络变化时重新注册（与 `CastDiscovery` 的做法一致）。

### 6.1.2 RTSP

- TCP 5000；端口被占用时在 5000–5010 内选一个，mDNS 广播实际端口。
- 支持方法：
  - `OPTIONS`：返回 `Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER`
  - `ANNOUNCE`：解析 SDP，准备会话
  - `SETUP`：分配 RTP / control / timing 端口
  - `RECORD`：开始收流
  - `SET_PARAMETER`：音量、进度、元数据
  - `GET_PARAMETER`：返回音量/进度
  - `FLUSH`：丢弃当前缓冲
  - `TEARDOWN`：结束会话
  - `PAUSE`：可选支持
- 每个连接一个读写协程；RTSP 会话与 RTP 会话绑定。
- `CSeq` 原样回显；`Session` 头生成随机 ID。
- 请求体支持 `text/parameters`、`application/x-dmap-tagged`、`application/x-apple-binary-plist`（首版只解析前两种，plist 记录日志）。

### 6.1.3 SDP

解析：

- `m=audio 0 RTP/AVP 96`
- `a=rtpmap:96 AppleLossless` 或 `a=rtpmap:96 mpeg4-generic/44100/2`
- `a=fmtp:96 ...`
- `a=rsaaeskey:<base64>`
- `a=aesiv:<base64>`
- `a=min_latency`
- `a=ptime`

输出：

```kotlin
data class AirPlayAudioFormat(
    val codec: AirPlayCodec,     // ALAC / AAC
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val aesKey: ByteArray,
    val aesIv: ByteArray,
    val compressionType: Int,
)
```

### 6.1.4 加密

- 接收端预置一对固定 RSA 私钥（AirPort Express 兼容密钥），发送端使用对应公钥加密 AES key。
- `Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")` 解密 `rsaaeskey`。
- `AES/CBC/NoPadding` 解密每个 RTP payload。
- IV 与填充细节按 RAOP 规范实现，并用公开实现（shairport 文档/规范）交叉核对：
  - 每个包独立使用 SDP 的 IV；
  - 解密后去掉 PKCS 风格填充/尾部零；
  - 解密失败计入坏包，不崩溃。

### 6.1.5 RTP 与抖动缓冲

- UDP 端口由 `SETUP` 协商。
- RTP 头：version、padding、extension、CSRC、sequence、timestamp、SSRC。
- 维护：
  - `sequence` 重排；
  - 3 秒窗口；
  - 丢包先补静音；
  - 迟到包只接受窗口内的；
  - `timestamp` 转成 PCM 帧序号。
- 每包 payload 解密后交给解码器。
- 支持 `FLUSH`：清空 jitter buffer 和 `AudioSink`，跳到新时间戳。

### 6.1.6 解码

**ALAC**

- 引入 `macosforge/alac`（Apache-2.0）到 `app/src/main/cpp/alac`。
- 新增 `aurora_alac` 共享库 + JNI：
  - `nativeCreate(size, channels, bitDepth)` → decoder handle
  - `nativeDecode(handle, packet, outPcm)` → 帧数
  - `nativeDestroy(handle)`
- Kotlin 侧每会话一个 decoder 实例，不在实时线程做 JNI 之外的分配。
- 输出 16-bit little-endian stereo。

**AAC**

- 使用 `MediaCodec`（`audio/mp4a-latm`）：
  - 配置 44.1/48 kHz、stereo；
  - 输入 `bufferInfo.presentationTimeUs` 用 RTP 时间戳换算；
  - 输出 PCM 16-bit。
- AAC 不是主要路径，但保留以兼容部分发送端。

### 6.1.7 元数据与进度

- `SET_PARAMETER` 的 `text/parameters`：
  - `title`
  - `artist`
  - `album`
  - `volume`（dB，转成线性增益）
  - `progress: start/current/end`
- `progress` 单位是 RTP 采样帧，按采样率换成毫秒。
- 元数据变化时更新 Hub 状态；进度更新做节流（约 500 ms）。
- 进度显示以发送端为锚点，本地按已播放时间插值；暂停时冻结。
- 封面：
  - `application/x-dmap-tagged` 中的 `asal` / `asar` / `minm` 用于文本；
  - 若后续支持 artwork 请求，下载到 `filesDir/cast_art/`，用 `file://` 交给 Coil；
  - 首版无封面时使用默认封面。

### 6.1.8 控制能力

AirPlay/RAOP 没有“接收端反向下一首/上一首”的标准命令，因此：

- `canPlayPause = true`：暂停/恢复本地输出（发送端继续推流，恢复时跳到最新）。
- `canNext = false`、`canPrevious = false`、`canSeek = false`。
- 停止：关闭 RTP socket、结束 RTSP 会话；发送端会收到 TEARDOWN/连接断开。

如果后续发现发送端支持 `POST /command` 的 MediaRemote 控制，再补能力位。

### 6.1.9 生命周期

- 收到 `ANNOUNCE` → `Connecting`。
- 收到 `RECORD` 且首个 RTP 包到达 → `Playing`。
- 发送端 `TEARDOWN` / 连接断开 / 30 秒无 RTP → 结束会话，Hub 回到 `Idle`。
- 新 `ANNOUNCE` 抢占旧会话。

---

## 6.2 Chromecast / Cast V2

### 6.2.1 发现

- `NsdManager` 注册 `_googlecast._tcp`。
- TXT：
  - `id=<deviceId>`
  - `cd=<deviceId>`
  - `ve=05`
  - `md=Chromecast`
  - `ic=/setup/icon.png`
  - `fn=<castIdentity.name>`
  - `ca=5`
  - `st=0`
  - `bs=<12 hex>`
  - `nf=1`
  - `rm=`
  - `rs=`
- 设备 ID 使用 `castIdentity.id`（去掉连字符），保证重启稳定。

### 6.2.2 TLS

- TCP 8009。
- 自签名 PKCS12 放在 `app/src/main/assets/cast/receiver-cert.p12`，密码写在代码常量。
- `SSLContext` + `KeyManagerFactory` 启动 `SSLServerSocket`。
- 每个 sender 一个连接、一个读循环、一个写队列。
- 证书有效期 10 年；启动时读取一次。
- 发送端可能校验 `deviceauth`，见 §6.2.5。

### 6.2.3 帧格式与 protobuf

Cast V2 帧：

```text
4-byte big-endian length
+ protobuf CastMessage
```

`CastMessage` 只需要以下字段：

```proto
enum ProtocolVersion { CASTV2_1_0 = 0; }
enum PayloadType { STRING = 0; BINARY = 1; }

message CastMessage {
  required ProtocolVersion protocol_version = 1;
  required string source_id = 2;
  required string destination_id = 3;
  required string namespace = 4;
  required PayloadType payload_type = 5;
  optional string payload_utf8 = 6;
  optional bytes payload_binary = 7;
}
```

实现一个最小手写编解码器：

- varint 读写；
- length-delimited 字段；
- 未知字段跳过；
- 不引入 `protobuf-javalite` 依赖。

### 6.2.4 命名空间与消息流

典型流程：

```text
sender → receiver-0
  CONNECT { }
  GET_STATUS
  LAUNCH { appId: "CC1AD845" }
receiver → sender
  RECEIVER_STATUS {
    applications: [{
      appId: "CC1AD845",
      sessionId: "...",
      transportId: "transport-...",
      statusText: "..."
    }]
  }
sender → transport-id
  CONNECT
  LOAD { media: {...}, autoplay: true, currentTime: 0 }
receiver → sender
  MEDIA_STATUS { mediaSessionId: 1, playbackState: "PLAYING", ... }
```

支持：

- `urn:x-cast:com.google.cast.tp.connection`
  - `CONNECT` / `CLOSE`
- `urn:x-cast:com.google.cast.tp.heartbeat`
  - `PING` → `PONG`
- `urn:x-cast:com.google.cast.receiver`
  - `GET_STATUS`
  - `LAUNCH`
  - `STOP`
  - 返回 `RECEIVER_STATUS`
- `urn:x-cast:com.google.cast.media`
  - `LOAD`
  - `PLAY`
  - `PAUSE`
  - `STOP`
  - `SEEK`
  - `GET_STATUS`
  - `SET_VOLUME`（映射到播放器增益）
  - `MEDIA_STATUS` 主动推送
  - `QUEUE_LOAD` / `QUEUE_INSERT` / `QUEUE_UPDATE`（可选，首版只解析 `LOAD` 的 `items`/队列）

`supportedMediaCommands` 位：

- `PAUSE = 1`
- `SEEK = 2`
- `STREAM_VOLUME = 4`
- `STREAM_MUTE = 8`
- `NEXT = 16`（有队列时）
- `PREVIOUS = 32`（有队列时）

### 6.2.5 设备认证

- 命名空间：`urn:x-cast:com.google.cast.tp.deviceauth`
- 收到 `AuthChallenge` 时调用：

```kotlin
interface CastDeviceAuthProvider {
    fun authenticate(challenge: ByteArray): AuthResponse?
}
```

- 默认实现 `NoCastDeviceAuth`：返回 `null`，不伪造证书。
- 如果 App 私有目录存在合法的接收端证书/签名数据，可通过实现类加载。
- UI/日志里记录认证阶段失败原因，便于真机排查。

### 6.2.6 媒体播放

- `LOAD` 解析：
  - `media.contentId`：媒体 URL；
  - `media.contentType`：MIME；
  - `media.streamType`：`BUFFERED` / `LIVE`；
  - `media.metadata.title/artist/albumName/images`；
  - `media.duration`；
  - `autoplay`、`currentTime`。
- URL 交给 `ExoPlayer`：
  - `DefaultMediaSourceFactory` 自动识别渐进式/HLS；
  - 视频轨默认关闭（`TrackSelectionParameters` 禁用 `TRACK_TYPE_VIDEO`）；
  - 音频 Sink 来自 `DspAudioChain`。
- `MEDIA_STATUS`：
  - `mediaSessionId` 自增；
  - `playbackState`：`IDLE` / `BUFFERING` / `PLAYING` / `PAUSED`；
  - `currentTime`；
  - `media` 原样回显；
  - `volume`；
  - `supportedMediaCommands`。
- 播放器事件（播放/暂停/缓冲/结束/seek 完成）触发状态推送，做 250 ms 节流。
- 结束（`STATE_ENDED`）时发 `IDLE`，保留会话等待新 `LOAD`。
- 上一首/下一首：
  - `LOAD` 带 `items` 队列时本地切歌；
  - 只有单条媒体时置灰。

### 6.2.7 封面

- `metadata.images[0].url`：
  - 下载到 `filesDir/cast_art/`；
  - key 用 URL 的 SHA-1；
  - 成功后 `file://` 交给 Coil。
- 下载失败保留远程 URL，让 Coil 直接加载。

---

## 6.3 Miracast / Wi-Fi Display

### 6.3.1 平台限制

- 普通 Android App 不能把本机注册成标准 WFD sink。
- `WifiP2pManager` 可以建组，但不能在 probe response 里带 WFD IE。
- `RemoteDisplay` 等隐藏 API 需要系统签名权限。
- 因此实现拆成两层：
  - `WfdRtspServer` / `WfdRtp` / `WfdTsDemux`：协议与音频管线，完整实现；
  - `WifiP2pSinkTransport`：发现与连接，做成可替换；默认走手动/ROM 可用路径。

### 6.3.2 P2P 传输

- `WifiP2pManager`：
  - `initialize`
  - `createGroup`（作为 GO）
  - `requestGroupInfo`
  - `requestConnectionInfo`
- 权限：
  - API < 33：`ACCESS_FINE_LOCATION`
  - API ≥ 33：`NEARBY_WIFI_DEVICES`
  - `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE`
- 未授权：
  - 不启动 P2P 部分；
  - AirPlay/Chromecast 不受影响；
  - Hub 状态里记录 `MIRACAST_PERMISSION_REQUIRED`。
- 建组后：
  - 显示组信息（SSID/密码/PIN）到设置页，便于手动连接；
  - 发现源连接后启动 RTSP 7236。

### 6.3.3 WFD RTSP

TCP 7236，实现 M1–M7：

1. **M1 RTSP OPTIONS**（源 → sink）
2. **M2 OPTIONS 响应**（sink → 源）
   - `wfd_video_formats`：首版返回 `none` 或最小占位；
   - `wfd_audio_codecs`：
     - LPCM 48 kHz 2ch
     - AAC 48 kHz 2ch
   - `wfd_client_rtp_ports`
   - `wfd_uibc_capability`
   - `wfd_content_protection: none`
3. **M3 SET_PARAMETER**
   - 源选择音频格式；
   - 解析 `wfd_audio_codecs`、`wfd_presentation_URL`。
4. **M4 SETUP**
   - 分配 RTP 端口；
   - 返回 `Session`、`Transport`。
5. **M5 PLAY**
   - 开始收流。
6. **M6 PAUSE**（可选）
7. **M7 TEARDOWN**
   - 结束会话。

RTSP 请求/响应使用 `CSeq`、`Session`、`Content-Length`；`GET_PARAMETER` 作为 keepalive。

### 6.3.4 RTP / TS 音频

- 视频格式首版不协商，源仍可能发视频；本机只处理音频。
- RTP payload 可能有两种形态：
  1. **MPEG2-TS**（payload 以 `0x47` 开头）：
     - 解析 PAT → PMT → 音频 PES；
     - 音频 codec 从 PMT 的 `stream_type` 判断：
       - `0x03` / `0x04` MPEG audio
       - `0x0F` AAC ADTS
       - `0x80` LPCM（WFD 常见）
     - PES 拆包，得到 ADTS / LPCM 帧。
  2. **直接 RTP 音频**（部分实现）：
     - payload type 96/97；
     - 按协商的 LPCM/AAC 处理。
- 时间戳：RTP 时间戳按 90 kHz（视频）或协商时钟；音频 PES PTS 换算成毫秒；没有 PTS 时按帧数累加。
- 解码：
  - LPCM：直接转 16-bit stereo；
  - AAC：`MediaCodec`；
  - MPEG audio：`MediaCodec`（`audio/mpeg`）。
- PCM 进 `PcmStreamPlayer`。

### 6.3.5 视频钩子

- `WfdRtspServer` 解析 `wfd_video_formats`，但首版返回不支持/只记录日志。
- 如果未来开启：
  - 新增 `WfdVideoDecoder`（MediaCodec H.264/H.265）；
  - 输出到 `CastVideoSink` 的 `Surface`；
  - RTSP M3 返回真实视频格式；
  - UI 的 `CastVideoSurface` 显示。

### 6.3.6 元数据

WFD 没有标准曲目元数据：

- 发送端设备名从 P2P/RTSP 头尽量解析；
- UI 显示 `Miracast` + 设备名；
- 标题/艺术家/专辑为空；
- 进度按音频时钟估算，没有总时长。

### 6.3.7 控制能力

- `canPlayPause = true`（本地暂停输出）；
- `canNext = false`、`canPrevious = false`、`canSeek = false`；
- `canStop = true`（发 M7 TEARDOWN）。

---

## 7. UI

### 7.1 浮层结构

`StandardCastOverlay` 挂在 `Ios5App` 根 `Box` 的最上层，和 `CastReceiverOverlay` 同级：

```kotlin
when (val s = state) {
    CastReceiverState.Idle -> Unit
    is CastReceiverState.Connecting -> ConnectingScreen(s)
    is CastReceiverState.Playing -> CastNowPlayingScreen(s)
}
```

### 7.2 `CastNowPlayingScreen`

布局：

```text
┌────────────────────────────────────────────┐
│  AirPlay · iPhone 13        [结束接收]      │  ← 顶栏
├────────────────────────────────────────────┤
│                                            │
│              专辑封面（方形）                │
│                                            │
├────────────────────────────────────────────┤
│  标题（粗体，最多两行）                      │
│  艺术家                                     │
│  专辑                                       │
│                                            │
│  ───────●────────────────────  01:23/04:56 │
│                                            │
│        ⏮        ⏯        ⏭                │
└────────────────────────────────────────────┘
```

细节：

- 封面：`TrackArtwork`（构造一个 `Song`，`id = media.id`，`title/artist/album/artworkUrl` 填入）。
- 标题/艺术家/专辑：
  - 标题最多 2 行；
  - 艺术家/专辑各 1 行，Ellipsis；
  - 空字段隐藏。
- 进度：
  - 用 `Slider`；
  - 拖动中只更新本地 UI；
  - 松手时若 `canSeek` 为 true，调 `seekTo`；
  - `canSeek = false` 时 Slider 只读。
- 按钮：
  - 大号上一首 / 播放暂停 / 下一首；
  - `capabilities` 为 false 时置灰；
  - 停止按钮始终可用。
- 视频：
  - `video != null` 时用 `CastVideoSurface` 替代封面；
  - 首版不会出现。
- 背景：
  - 沿用 `Ios5Colors.linenBrush` 或深色渐变；
  - 保持和现有播放页一致的视觉语言。
- 状态恢复：
  - 浮层状态完全来自 Hub；
  - 旋转/重建不重置会话；
  - 进度、播放态持续更新。

### 7.3 设置页状态

在 `IntegrationsSettingsScreen` 增加“无线投屏接收”分组：

- 设备名：`castIdentity.name`
- AirPlay：`监听中 · 端口 5000` / `未启动`
- Chromecast：`监听中 · 端口 8009` / `未启动`
- Miracast：`监听中` / `需要“附近的设备”权限` / `平台不支持自动发现`
- 当前活动会话：协议 + 发送端 + 媒体标题

不做开关，符合“永远开启”的要求。

---

## 8. 生命周期、服务与权限

### 8.1 启动

- 在 `AppContainer` 构造末尾创建并启动 `CastReceiverHub`。
- 启动顺序：
  1. 读取/生成设备名与稳定 ID；
  2. 启动 AirPlay RTSP server；
  3. 启动 Chromecast TLS server；
  4. 注册 mDNS；
  5. 按权限启动 Miracast P2P；
  6. 注册网络变化回调。
- 任一步失败只记录状态，不影响其他协议。

### 8.2 网络变化

- 监听 `ConnectivityManager.registerDefaultNetworkCallback`：
  - `onAvailable`：重注册 mDNS、重建失败的 listener；
  - `onLost`：标记未注册，等待恢复。
- 端口固定（5000/8009/7236），避免 mDNS 与已建立连接不一致。
- Wi-Fi 关闭时停止 RTP 接收，保留 TCP listener。

### 8.3 前台服务

新增 `CastReceiveService`：

- `foregroundServiceType="mediaPlayback"`。
- 会话进入 `Playing` 时启动。
- 通知：
  - 标题：`正在接收 AirPlay 投屏`
  - 内容：`标题 — 艺术家`
  - 操作：停止。
- 会话结束进入 `Idle` 时 `stopSelf()`。
- 如果 App 在后台时收到投屏，API 31+ 可能禁止后台启动 FGS：
  - `runCatching` 包住；
  - 不启动服务也继续播放；
  - 记录日志。

### 8.4 权限

`AndroidManifest.xml` 新增：

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="33" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

已有：

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `CHANGE_WIFI_MULTICAST_STATE`
- `WAKE_LOCK`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `MODIFY_AUDIO_SETTINGS`

Miracast 的 `ACCESS_FINE_LOCATION` 在旧系统上按需申请；不申请也不影响 AirPlay/Chromecast。

---

## 9. 文件改动清单

### 新增

```text
app/src/main/java/com/aurora/music/data/cast/receiver/
  CastReceiverHub.kt
  CastSessionArbiter.kt
  CastSessionState.kt
  CastProtocolReceiver.kt
  CastTransport.kt
  CastArtwork.kt
  airplay/...
  chromecast/...
  miracast/...
  video/...

app/src/main/java/com/aurora/music/playback/
  DspAudioChain.kt
  PcmStreamPlayer.kt
  CastReceiveService.kt

app/src/main/java/com/aurora/music/ui/cast/
  StandardCastOverlay.kt

app/src/main/cpp/alac/
  （vendored Apple ALAC，Apache-2.0）
app/src/main/cpp/alac_jni.c

app/src/main/assets/cast/
  receiver-cert.p12

app/src/test/java/com/aurora/music/cast/
  CastMessageCodecTest.kt
  AirPlaySdpTest.kt
  AirPlayRtpTest.kt
  WfdRtspTest.kt
  CastSessionArbiterTest.kt
```

### 修改

```text
app/src/main/java/com/aurora/music/data/AppContainer.kt
  - 创建/启动 CastReceiverHub
  - 旧投屏 offer 与标准投屏互斥
  - 本机播放开始时停止标准投屏

app/src/main/java/com/aurora/music/playback/PlaybackService.kt
  - 新增 ACTION_CAST_TAKEOVER / pauseForCast()
  - 暂停音乐、停听书、退出双播

app/src/main/java/com/aurora/music/data/cast/CastReceiver.kt
  - ensurePlayer() 改用 DspAudioChain.createSink()
  - setAudioSessionId(container.audioSessionId)

app/src/main/java/com/aurora/music/ui/ios5/Ios5App.kt
  - 挂载 StandardCastOverlay
  - receivingCast 条件包含标准投屏状态

app/src/main/java/com/aurora/music/ui/screens/settings/IntegrationsSettingsScreen.kt
  - 接收状态分组

app/src/main/AndroidManifest.xml
  - 权限
  - CastReceiveService

app/src/main/cpp/CMakeLists.txt
  - 新增 aurora_alac 共享库

app/build.gradle.kts
  - 如需（ALAC JNI 无新依赖，通常不改）
```

---

## 10. 分阶段实施

### Phase 0：基础设施（先保证编译）

- 引入状态模型、Hub、Arbiter、协议接口。
- `DspAudioChain`、`PcmStreamPlayer`。
- 旧协议投屏接 DSP。
- `StandardCastOverlay`（先用模拟状态验证 UI）。
- `CastReceiveService`、权限、Manifest。
- 验收：`assembleDebug` 通过；旧投屏音频仍正常；DSP 参数与音乐链一致。

### Phase 1：AirPlay 音频

- mDNS `_raop._tcp`。
- RTSP/SDP/加密。
- RTP + jitter buffer。
- ALAC JNI（vendor + CMake）。
- AAC 兜底。
- 元数据/进度/音量。
- 验收：iPhone/Mac 能看到设备；音频从本机播放；EQ/校正生效；新发送端抢占旧发送端。

### Phase 2：Chromecast

- mDNS `_googlecast._tcp`。
- TLS + 证书。
- 帧/protobuf codec。
- receiver/media 通道。
- ExoPlayer + DSP。
- MEDIA_STATUS / 队列 / 封面。
- `CastDeviceAuthProvider` 接口。
- 验收：发送端能发现并发起 LAUNCH/LOAD；音频播放；暂停/seek/状态同步；认证失败有明确日志。

### Phase 3：Miracast

- P2P 建组 + 权限门控。
- WFD RTSP M1–M7。
- RTP/TS 解复用。
- LPCM/AAC 解码。
- 手动连接/状态页。
- 验收：权限授予后 RTSP 可协商；音频可出声；新会话抢占；视频包被安全丢弃。

### Phase 4：收尾

- 设置页状态。
- 封面缓存清理策略。
- 单元测试补齐。
- 文档与日志。
- 性能：
  - 音频线程优先级；
  - 避免每包分配；
  - 状态更新节流。

---

## 11. 测试计划

### 11.1 编译

```bash
./gradlew :app:assembleDebug
```

只要求编译通过；安装和真机测试由用户执行。

### 11.2 JVM 单测

- `CastMessageCodecTest`：CastMessage 编解码往返、未知字段跳过。
- `AirPlaySdpTest`：ALAC/AAC、AES key/iv、采样率解析。
- `AirPlayRtpTest`：RTP 头解析、序列重排、丢包补静音。
- `WfdRtspTest`：M1–M7 请求/响应字段解析。
- `CastSessionArbiterTest`：generation 抢占、旧回调丢弃。
- `CastCapabilitiesTest`：各协议能力位映射。

### 11.3 真机验收清单（用户执行）

AirPlay：

- [ ] iPhone 控制中心能看到设备。
- [ ] Mac 声音输出能看到设备。
- [ ] 播放音乐时本机音乐自动暂停。
- [ ] 封面/标题/艺术家/专辑显示。
- [ ] 进度跟随。
- [ ] 暂停/恢复可用。
- [ ] 新发送端抢占旧发送端。
- [ ] 断开后 2 秒内浮层消失。

Chromecast：

- [ ] Cast 发送端能发现设备。
- [ ] LAUNCH/LOAD 成功。
- [ ] 音频播放、暂停、seek 同步。
- [ ] 封面显示。
- [ ] 多 sender 抢占。
- [ ] 认证失败时日志明确。

Miracast：

- [ ] 权限授予后 P2P 建组。
- [ ] RTSP M1–M7 协商成功。
- [ ] 音频出声。
- [ ] 无视频包导致崩溃。
- [ ] TEARDOWN 后资源释放。

---

## 12. 风险与处理

| 风险 | 处理 |
|---|---|
| AirPlay 新系统偏好 AirPlay 2，RAOP 发现不到 | 只注册 `_raop._tcp`；必要时再补 `_airplay._tcp` + pair-setup（后续阶段） |
| ALAC 解码 JNI 不稳定 | decoder 实例按会话隔离；坏包只丢帧；native 层不做 JNI 回调 |
| RTP 乱序/丢包导致爆音 | jitter buffer + 静音补齐 + `FLUSH` 清空 |
| 多协议同时到达 | 单路 Arbiter + generation，任何新会话先停旧会话 |
| 本机音乐被抢占后自动恢复 | 不自动恢复；焦点释放后由用户操作 |
| 旧协议投屏与新协议同时播放 | Hub 启动时 `castReceiver.stop()`，旧协议 offer 时 `hub.stopActive()` |
| Chromecast 官方发送端在 deviceauth 拒绝 | 提供 `CastDeviceAuthProvider` 接口；日志记录；无证书时明确提示 |
| Miracast 普通零售机发现不到 | 发现层可替换；提供手动连接/状态页；音频管线独立可测 |
| 前台服务后台启动受限 | `runCatching`；播放继续，服务启动失败只记日志 |
| 端口被占用 | AirPlay 5000–5010 自选；Chromecast/Miracast 固定，失败重试并记录 |
| 旋转/重建导致会话丢失 | 会话状态在 Hub（App 作用域），UI 只读 |

---

## 13. 关键决策记录

1. **不在三个协议里各写一套播放器**：统一 `DspAudioChain` + `PcmStreamPlayer` / ExoPlayer 两条路径。
2. **PCM 路径直接驱动 `AudioSink`**，不手写 AudioTrack 循环，保证 DSP 与音乐链完全一致。
3. **旧协议投屏也接 DSP**，满足“所有投射过来的音频流”要求。
4. **单路仲裁放在 App 作用域**，不依赖 Activity 生命周期。
5. **视频只留接口**：`CastVideoSink` + `CastVideoInfo` + UI 占位，不在本轮引入解码器。
6. **Chromecast 认证抽接口**，不把任何外部证书/签名数据硬编码进仓库。
7. **Miracast 发现层可替换**，音频协议实现与平台发现解耦。
8. **不做自动恢复本机播放**：投屏结束回到 `Idle`，本机播放由用户决定。

---

## 14. 后续扩展

- AirPlay 屏幕镜像：H.264 RTP + FairPlay（如需要）、`CastVideoSink` 接 MediaCodec + Surface。
- Chromecast Cast Streaming（WebRTC）：Opus/H.264，视频钩子。
- Miracast 视频：WFD H.264 + MediaCodec。
- AirPlay 2 多房间：buffered audio + `/stream` + 配对。
- 投屏历史/统计：Hub 事件接入 `PlayHistoryStore`。
- 通知栏媒体控制：为投屏会话建立独立 `MediaSession`。
