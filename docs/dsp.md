# 音乐效果器链

## 1. 处理顺序

所有自定义 DSP 都是 Media3 `AudioProcessor`，装进 `DefaultAudioSink`：

```
解码 PCM
  → MonoAudioProcessor          单声道下混
  → ConvolutionProcessor        设备校正 FIR（AutoEq / squig / 自定义）
  → AuroraDspProcessor          43 段双二阶 EQ + 增益 + 饱和 + 声场 + 压缩 + 限幅 + 延时
  → ConvolutionProcessor        用户脉冲响应（房间/耳机 IR）
  → SilenceSkippingAudioProcessor   ┐ Media3 默认链，
  → SonicAudioProcessor            ┘ 排在自定义处理器之后
  → AudioTrack
```

两个直接后果：

1. **EQ 和动态处理发生在变速/跳静音之前**。0.8× 播放时，压缩器看到的是原始时间尺度
   的信号；跳静音也不会跳掉应该被压缩的段落。
2. **可视化取的是 EQ 之前的原始 PCM**（`TappingAudioSink` 在委托 sink 之前抓 buffer），
   所以频谱显示的是"文件里有什么"，不是"你听到什么"。

同一条链在三个地方各建一份（处理器有状态，不能共享）：

- 本地音乐：`PlaybackService` 的 `buildAudioSink`
- 投屏接收：`DspAudioChain`（URL 播放和裸 PCM 两个入口）
- 朗读 / 有声书旁白：`EbookTtsPlayer`（固定 48 kHz）

参数装配只有一个入口 `buildDspParams()`：同一份 `AudioPrefs` 必须算出完全一致的
`DspParams`，自动增益、驱动模式覆盖等逻辑只维护一份。

## 2. 一个关键前提：16-bit / 立体声

所有自定义处理器只接受 `ENCODING_PCM_16BIT` + 2 声道。其他格式直接返回
`AudioFormat.NOT_SET`，处理器不生效，链路透传。

- 内部用 float 计算，入口 `short / 32768`，出口 clamp 回 16-bit。
- 好处：实现简单、所有 Android 设备一致、CPU 可控。
- 代价：24-bit / float / 多声道内容可能整条链被绕过（取决于 Media3 是否先降到 16-bit）。
  这是需要实测确认的点，不应在文档里假设它一定生效。

另有一个更隐蔽的坑：**float 输出（Hi-Res）会完全跳过自定义处理器**。
Media3 在 float 管线里只挂内置的格式转换处理器，不加载 `audioProcessorChain`。
所以"Hi-Res 输出"和"自定义 DSP"在设计上互斥，必须在启动时二选一
（`useFloat = bitPerfectUsb || (preferHighRes && dspMode != CUSTOM)`）。

## 3. AuroraDspProcessor：单处理器内的固定顺序

为保证"改参数不炸音"，43 个双二阶滤波器组成**固定大小的状态银行**：
31 个图示均衡 + 12 个参数均衡，L/R 各一套 x1/x2/y1/y2，永不重建、永不重映射。
切换频段数量只是改系数，滤波器状态连续，所以不会因为"乐队数量变了"而产生咔哒声。

每采样的处理顺序（左右独立或立体声联动，见括号）：

1. 43 段双二阶级联（L/R 独立）
2. 前级 × 平衡 × 每声道 trim（前级/平衡平滑，trim 不平滑）
3. 驱动响度补偿增益
4. 谐波饱和（可选，tanh + 二次项）
5. 立体声宽度（M/S，0 = 单声道，2 = 加倍侧信号）
6. 交叉馈送（可选，延迟 + 700 Hz 一阶低通，混合对侧）
7. 压缩器（峰值检测，立体声联动，软拐点）
8. 补偿增益（静态或自动，自动每 buffer 最多 +6 dB）
9. 限幅器（峰值，立体声联动，2ms attack / 100ms release）
10. 每声道延时（对齐用，0–20ms）
11. 写回 16-bit

表头每 buffer 发布一次压缩/限幅的增益衰减，UI 的 GR 表就是读这两个值（下限 -30 dB）。

## 4. 效果清单

| 类别 | 内容 |
|---|---|
| 图示 EQ | 10 / 15 / 31 段，±12 dB；另有 16 段"用户 EQ"布局 |
| 参数 EQ | 最多 12 段，peaking / low-shelf / high-shelf，20Hz–20kHz，Q 0.3–8，±15 dB |
| 增益 | 前级 ±12 dB、平衡、自动 headroom（按用户 EQ 与校正曲线的最大正增益取反） |
| 声场 | 宽度 0–2、交叉馈送 0–100% |
| 谐波 | 饱和度 0–100% |
| 动态 | 压缩器（阈值/比率/attack/release/拐点/补偿）、限幅器（-6–0 dBTP） |
| 驱动模式 | Natural / Balanced / Strong / Custom，前三者覆盖静态压缩参数 |
| 响度 | 驱动响度（按曲目 LUFS 补到目标 -24…-8 LUFS）、ReplayGain（track/album） |
| 卷积 | 用户 IR（WAV：16/24/32-bit int、float、8-bit，单声道复制到双声道） |
| 校正 | 128 点对数频率响应 → 4095-tap 线性相位 FIR；强度 0–120%、trim、低切/高切 |

### ReplayGain 和驱动响度不同时生效

- ReplayGain 是 `player.volume` 乘数，只衰减不放大（0.1–1.0）。
- 驱动响度是 DSP 链内的增益（±12 dB）。
- 两者同时开会叠加，所以代码里"驱动开启时 ReplayGain 直接返回 1"。

## 5. 系数计算

- 全部用 RBJ cookbook 公式，在**非音频线程**算好，发布成一个不可变的 `Coeffs` 引用；
  音频线程每个 buffer 只读一次引用到局部变量，不会读到半套参数。
- 采样率来自处理器实际配置的输入格式；格式变化时重建并清空滤波器状态。
- `f0 <= 0 || f0 >= fs/2` 的频段直接退化为恒等，避免 Nyquist 附近的数值爆炸。
- 参数 EQ 的 shelf 用的是 Q 参数化，不是 cookbook 的 shelf slope `S`——这点和标准
  "Q"的听感不完全一致，属于已知偏差。

### 校正 FIR

1. 任意来源（AutoEq / GraphicEQ / Equalizer APO / 自定义）先统一成 128 个对数频点增益。
2. 在 8192 点频谱上做对数插值（20kHz 以上保持），镜像成实数偶对称谱。
3. 逆 FFT → 循环移位 2047 点变成因果线性相位 → Hann 窗 → 截断到 4095 tap。
4. 用 overlap-save 卷积：1024 帧一块、2048 点 FFT，每声道 K 个分区。

选择 4095 tap 的原因写在测试注释里：255 tap（约 188 Hz 分辨率）在低频明显校正不足，
4095 tap @48k 约 12 Hz 分辨率，接近桌面卷积器的水平。

## 6. 运行时更新与平滑

- 参数变化 → 重建 43 段系数 → volatile 引用替换。**没有 debounce**，拖滑块会触发
  多次重建。
- 只有前级、平衡、宽度做了 5ms 时间常数的一阶平滑；EQ 系数、驱动增益、trim、
  交叉馈送、压缩/限幅参数都是立即跳变。
- 大跳变可能听到底噪/咔哒，但滤波器状态保留，所以不会"炸"。
- 卷积换 IR 会清空 FIFO，最多丢 1023 帧并改变延迟；开/关卷积也会有约 21ms 的
  时间跳变。

### 旁路

`DspParams.isNeutral()` 为真时 `auroraDsp.enabled = false`，直接 memcpy。
各模块开关（图示/参数/增益/声场/谐波/对齐/卷积/限幅/压缩）在 `buildDspParams` 里
强制中性值，用户设置原样保留，重新打开即恢复。

## 7. 预设与设备绑定

- 单个效果参数存在 `AudioPrefs`（DataStore），大多是一行字符串（如
  `"f:g:q:type;..."`），便于导入导出和向前兼容。
- `CorrectionProfile`：一个设备/耳机的 128 点校正曲线 + trim + 强度 + 低切/高切。
- `AudioProfile`：整条链的快照（约 30 个键），可以"保存当前 / 应用 / 绑定设备"。
- 设备键是 `音频设备类型:产品名`。同一型号的两个 DAC 会撞键，内置扬声器的键还依赖 OEM。
- 绑定优先级：BT A2DP → BLE → USB → 有线。注意播放服务的信号路径显示用的是另一套
  排序（USB 优先），两处可能给出不同结论。
- 未绑定的设备不会被自动套用任何曲线，避免覆盖用户手动设置。

### 导入

支持两种文本：

- **GraphicEQ**：`GraphicEQ: 20 -9.5; 21 -9.5; ...`，插值到 128 点。
- **Equalizer APO**：`Preamp:` + `Filter N: ON PK|LS|HS Fc ... Gain ... Q ...`。

解析是严格的：不认识的行、超过 12 段、正增益都会让整次导入失败，而不是静默丢弃。
**没有导出功能**，预设只存在 DataStore 里。

内置 AutoEq 库是构建期生成的二进制包（`AEQ2`），运行时用 `pread` 从 APK 里按偏移读，
不整体解压；包含数千个 `ParametricEQ.txt` 预设，解析覆盖率有单测保证。

## 8. 与 USB 直通的关系

- 开启 bit-perfect 后，播放服务走一条**完全不挂自定义处理器**的 sink：
  `UsbAudioSink` + float 输出 + FFmpeg 解码器。
- 如果曲目是 FLAC，进一步走 C++ `NativeAudioEngine`：直接解码/混音到 DAC，
  Kotlin 侧 `handleBuffer` 只推进 buffer 指针。此时 DSP、Sonic 变速、音量、
  跳静音全部不参与。
- 非 FLAC 或引擎启动失败时，PCM 交给 `UsbStreamingThread` 写原始字节。
- 只在使用独占混音（API 34+ `MIXER_BEHAVIOR_BIT_PERFECT`）且非蓝牙时才算真正直通。

这不是"效果器链的一个分支"，而是**另一条信号路径**。UI 上应该明确显示当前走的是
`signal_bp_exclusive` / `signal_dsp` / `signal_hires_float` 中的哪一条。

## 9. 取舍与已知问题

1. **16-bit 上限**：简单、稳，但高解析素材可能绕过整条链。
2. **float 输出静默禁用 DSP**：最危险的组合是"Hi-Res 开 + dspMode 还是 SYSTEM"，
   用户以为 EQ 生效，实际没有；信号路径提示也可能仍显示 DSP。
   `dspMode` 只有应用校正/配置时才会变成 CUSTOM，单独开 EQ 不会。
3. **延迟**：4095-tap 线性相位约 42.6ms @48k，加上卷积块 21ms，再加用户 IR 21ms，
   总计可达 ~85ms。视频/投屏要对齐必须知道这个数。
4. **线性相位有预振铃**：换最小相位可以去掉预振铃，但会引入相位失真。
5. **参数没有普遍平滑**：拖 EQ 可能有轻微咔哒；应用配置会连续写 ~30 个键，
   产生一串中间状态。
6. **交叉馈送没有等响补偿**，会抬高总电平，可能提前触发限幅。
7. **延时上限按采样数 clamp**（9600），96k 时实际最大时间减半。
8. **旧的系统音效控制器**（`AudioEffectsController`）仍在分配 session 效果，
   但主开关被永久关闭，相关偏好是死配置。
9. **无导出**：校正/配置只能导入，不能导出分享。

## 关键文件

`playback/{AuroraDspProcessor,DspCoeffBuilder,DspChain,DspAudioChain,ConvolutionProcessor,CorrectionCompiler,Fft,MonoAudioProcessor,TappingAudioSink}.kt`；
`data/{AutoEqController,AutoEqRepository,CorrectionProfiles,EqTextImport,ReplayGainScanner}.kt`；
`ui/screens/settings/{EqualizerScreen,PlaybackSettingsScreen}.kt`；
`decent/decent-usb-audio-wrapper-media3/.../UsbAudioSink.kt`。
