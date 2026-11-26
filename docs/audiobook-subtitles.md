# 有声书离线字幕（ASR）

听有声书时，用 80% 黑遮罩盖住封面，显示「上一句 / 这一句 / 下一句」三行字幕；长按任意一行复制。
识别完全在本机做，音频不出设备。

## 入口

- 播放页：进度条下方五键里的「字幕」键（替换了原来的章节介绍「?」键）。
- 设置 → 有声书设置 → 离线字幕：开关、模型管理、提前识别量、线程数、下载源、缓存清理。

## 模型

运行时是 sherpa-onnx 2.0.2（官方 AAR，含 ONNX Runtime），**AAR 也不进仓库**：
`fetchSherpaAar` 在构建时从 GitHub release 下载 52 MB 的包并校验 SHA-256，
文件落在 `app/libs/`（已 gitignore）。换版本只改 `app/build.gradle.kts` 里的
版本号和校验值；国内可用 `-PsherpaAarUrl=<镜像前缀>` 覆盖下载地址。

权重和 VAD 同样不打包，运行时按需下载到 `filesDir/asr-models/`：

- 识别模型 → `asr-models/<modelId>/`
- 语音切句 Silero VAD（629 KB）→ `asr-models/vad/silero_vad.onnx`，
  下载模型时顺带准备好；这个文件只有 GitHub release 有，固定从那里下。

完整的目录/命名/完整性契约（供其他应用共用同一份权重）见 [model-storage.md](model-storage.md)。

| 模型 | 体积 | 语言 | 特点 |
|---|---|---|---|
| SenseVoiceSmall int8 | 239 MB | 中英日韩粤 | 最快（约 40× 实时），英文专名弱 |
| Whisper base.en int8 | 160 MB | 英语 | 英文均衡档 |
| Whisper small.en int8 | 375 MB | 英语 | 英文最准，约为 SenseVoice 的 1/10 速度 |
| Whisper tiny.en int8 | 103 MB | 英语 | 最小最快，准确度一般 |
| Whisper base / small int8 | 160 / 375 MB | 99 种语言 | 多语言 |

下载源：`hf-mirror.com`（默认，国内直连）/ `huggingface.co`，单文件 Range 续传。
**ModelScope 上没有这些 ONNX**：`iic/SenseVoiceSmall` 只有 PyTorch `model.pt`，
Android 端要的是 sherpa-onnx 导出的 ONNX，只有 HF/GitHub 有。

## 运行时打包

- AAR 打进 APK；`splits.abi` 按 ABI 出包，arm64 手机装 `app-arm64-v8a-*.apk`，
  x86_64 模拟器装 `app-x86_64-*.apk`，各自只带自己那份 `.so`（约 +31 MB）。
- 不做「运行时下载」：Android 10+ 限制从可写目录 `dlopen`，Play 也禁止动态下发原生代码。

## 包体积

实测（release、arm64-v8a、同一份代码开/关字幕功能、都按 ABI 分包）：

| | 之前 | 现在 | 差 |
|---|---|---|---|
| arm64 APK | 241.5 MiB* | 273.1 MiB | **+31.6 MiB** |
| x86_64 APK | 214.4 MiB* | 250.0 MiB | +35.6 MiB |

\* 之前那次基线构建没跑 AutoEq 打包任务，已把 5.42 MiB 的 `assets/autoeq` 补回去。

增量几乎全是运行时：`libonnxruntime.so` 21.2 MiB + sherpa 的 3 个 `.so` 10.2 MiB，
Kotlin/Java 类进 dex 只有约 0.1 MiB。**VAD（0.6 MiB）和模型权重都不在包里**，首次使用时下载。

顺带：因为改成了按 ABI 分包，手机上的 arm64 包不再带 x86_64 的 `.so`——
和之前那个 arm64+x86_64 的通用包（约 309 MiB）比，现在反而小了约 36 MiB。

## 转写流程

```
章节音频 --(MediaExtractor/MediaCodec, 按需 seek 到播放位置)--> 16 kHz 单声道 PCM
        --(Silero VAD, 静音 0.35s 切句)--> 语音段
        --(SenseVoice / Whisper)--> 文本
        --(标点切句 + 按字数分摊时间)--> cues(章节相对秒) --> 缓存 JSON
```

- 调度在 `SubtitleSession`：始终把字幕生成到「播放位置 + 提前量（默认 60s）」，
  播 1 秒只识别 1 秒多的音频；用户向前拖进度条超过已生成范围时，从新位置重开管线。
- 缓存：`filesDir/subtitles/<book>/<chapter>.json`，二次收听零延迟；换模型后自动重算。
- 引擎只在字幕遮罩打开时加载（约 1s、几百 MB 内存），关掉即释放。

## 已知限制

- 专有名词（希腊人名、术语）在 SenseVoice 上错误明显；需要准确就换 Whisper base/small.en。
- 逐词高亮没有：SenseVoice 不给词级时间戳，句级时间来自 VAD，句内时间按字数分摊。
- 远程书 / 未缓存章节没有本地文件，无法转写（会提示）。
- ALAC 编码的 m4b 依赖系统解码器；系统没有对应 codec 时会报「无法读取章节音频」。
- 重采样是线性插值（够用，但不是带限重采样）。

## 自测

1. 设置 → 有声书设置 → 离线字幕 → 下载 SenseVoice（或 base.en）。
2. 打开一本本地有声书，播放页点「字幕」键：应看到 80% 黑遮罩 + 三行字。
3. 1×/1.5× 播放，观察换句是否跟得上；把提前量调到 20s 看是否更容易卡。
4. 长按当前句 → 剪贴板里应有该句文本。
5. 退出重进同一章：字幕应立即出现（走缓存），状态显示「本章已完成」。
