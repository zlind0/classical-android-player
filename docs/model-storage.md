# 大模型目录规范（LLM / ASR）

这份文档是**跨应用的落盘契约**：只要按这里的目录树和命名放文件，nostalgiaPod 和其他自家
应用就能共用同一份模型权重，不用各下各的 2–4 GB。

一句话版本：

```
<root>/llm-models/<modelId>/<上游发布的权重文件>
<root>/asr-models/<modelId>/<上游发布的权重文件>
<root>/asr-models/vad/silero_vad.onnx
<root>/litert-cache/                     # 引擎编译缓存，可随时删
```

`<root>` 由用户选：默认是应用私有 `filesDir`（只有本应用能读），也可以在设置里换到
安卓共享目录（SD 卡 / `Download/models` 之类），这时其他应用才能复用。

本文的 MUST / SHOULD 是给"读这份目录的其他应用"的约束，不是内部实现细节。

---

## 1. 根目录

### 1.1 两种模式

| 模式 | `<root>` | 谁能读 | 用途 |
|---|---|---|---|
| 应用存储（默认） | `<app>/files/`（即 `context.filesDir`） | 只有本应用 | 单应用使用，卸载即清 |
| 安卓共享目录 | 用户选的任意可写目录 | 拿到存储权限的应用 | 多应用共用、换机/换 ROM 后保留 |

共享目录的硬性要求：**必须能用普通 `File` API 直接读写**。原生推理库（LiteRT-LM、
sherpa-onnx）只接受文件路径并做 `mmap`，只有 SAF 的 `content://` 权限是**不够**的。
所以选目录时要真的创建并删除一个探针文件验证一次。

nostalgiaPod 的入口：设置 → 存储与下载 → 大模型存储。
- 「应用存储」= `filesDir`
- 「安卓目录」= 通过「所有文件访问」权限或系统目录选择器（SAF tree Uri 会反解成真实路径）
  选一个目录。Android 11+ 没给所有文件访问时，SAF 选出来的目录通常写不了，会明确提示。

### 1.2 nostalgiaPod 自己怎么记住选择

应用私有文件 `<app>/files/model-storage.properties`（Java `Properties` 格式）：

```properties
mode=APP            # APP | ANDROID
access=ALL_FILES    # ALL_FILES | SAF，仅用于展示/重新授权
tree=content://...  # SAF tree Uri，APP 模式为空
path=/storage/emulated/0/Download/models   # 真实绝对路径，APP 模式为空
label=Download/models                      # 用户可见名，仅展示
```

为什么不用 DataStore：LiteRT-LM 跑在独立的 `:llm` 进程里，每次加载引擎都要拿最新目录，
DataStore 不保证跨进程立即可见，而一个 properties 文件按 mtime 读最省事。

**其他应用不要读这个文件**（沙箱里也读不到）。每个应用自己存自己的指针，指向同一个
`<root>` 即可。`path` 为空时 nostalgiaPod 退回应用存储；目录存在但不可写时不会偷偷搬文件，
而是在界面上提示「已下载的模型会显示为未安装」。

### 1.3 目录里允许出现什么

- 根目录下只放 §2 的子树；确实需要额外缓存时，用 `<root>/litert-cache/<own-name>/` 这样的
  子目录，别在顶层堆文件。
- 读取方 MUST 忽略不认识的目录/文件（包括其他应用新增的模型 id），不要因为多余文件报错。
- 删除方 MUST 只删自己认识的 `<modelId>` 目录，不要整棵 `llm-models/` / `asr-models/` 删掉。
- `asr-models/vad/` 是**共用**的（所有 ASR 模型共用一份 VAD），删单个 ASR 模型时不要动它。

---

## 2. 完整目录树

```
<root>/
├── llm-models/                          # 多模态大模型（LiteRT-LM）
│   ├── gemma4-e2b/
│   │   └── gemma-4-E2B-it.litertlm      # 2.59 GB
│   ├── gemma4-e4b/
│   │   └── gemma-4-E4B-it.litertlm      # 3.66 GB
│   └── gpu-crashed                      # 仅应用存储模式，见 §7
├── asr-models/                          # 离线字幕（sherpa-onnx）
│   ├── sensevoice/
│   │   ├── model.int8.onnx              # 239 MB
│   │   └── tokens.txt                   # 311 KB
│   ├── whisper-tiny-en/
│   │   ├── tiny.en-encoder.int8.onnx
│   │   ├── tiny.en-decoder.int8.onnx
│   │   └── tiny.en-tokens.txt
│   ├── whisper-base-en/
│   ├── whisper-small-en/
│   ├── whisper-base/
│   ├── whisper-small/
│   └── vad/
│       └── silero_vad.onnx              # 643 854 字节，全局共用
└── litert-cache/                        # 仅共享模式；引擎编译缓存，见 §6
```

注意 `llm-models/gpu-crashed` 是**文件**不是目录，且只在应用存储模式下和模型放在一起；
共享模式下它仍然待在应用私有 `filesDir/llm-models/` 里（§7）。

---

## 3. 文件格式

| 后缀 / 文件名 | 格式 | 谁读 | 说明 |
|---|---|---|---|
| `.litertlm` | LiteRT-LM bundle（单文件容器，含权重 + tokenizer + 模型元数据） | LiteRT-LM 运行时 | 一个模型一个文件，2–5 GB，只读 `mmap` |
| `.onnx` | ONNX，int8 量化 | sherpa-onnx / ONNX Runtime | SenseVoice 是单文件；Whisper 拆成 encoder + decoder 两个 |
| `tokens.txt` / `*-tokens.txt` | UTF-8 文本，每行 `token id`（空格分隔） | sherpa-onnx | 词表；Whisper 的文件名带模型前缀 |
| `silero_vad.onnx` | ONNX | sherpa-onnx VAD | 固定文件名，所有 ASR 模型共用 |

都是**上游发布时的原始文件，不做任何再加工**（不重打包、不改名、不压缩）。这样别的应用
可以从同一份 HF 仓库直接校验/续传。

### 3.1 模型清单（LLM）

运行时随 APK（`litertlm-android` AAR），权重按需下载。下载源固定 commit，避免上游更新导致
同一模型文件大小变化。

| id（目录名） | 文件名 | 字节数 | 仓库 | commit | 最低内存 |
|---|---|---|---|---|---|
| `gemma4-e2b` | `gemma-4-E2B-it.litertlm` | 2 588 147 712 | `litert-community/gemma-4-E2B-it-litert-lm` | `6e5c4f1e395deb959c494953478fa5cec4b8008f` | 8 GB |
| `gemma4-e4b` | `gemma-4-E4B-it.litertlm` | 3 659 530 240 | `litert-community/gemma-4-E4B-it-litert-lm` | `28299f30ee4d43294517a4ac93abd6163412f07f` | 12 GB |

- 只收录原生支持音频输入（30 秒/段）的模型，用于有声书「语音 → 文本翻译」。
- 旧的 Gemma 3n 仓库是 GatedRepo，需要 HF 登录，没有收录。
- 下载 URL：
  - HF / 镜像：`https://hf-mirror.com/<repo>/resolve/<commit>/<file>?download=true`
  - ModelScope：`https://modelscope.cn/models/<repo>/resolve/master/<file>`

### 3.2 模型清单（ASR）

| id（目录名） | 文件 | 字节数（参考） | 语言 | 仓库 |
|---|---|---|---|---|
| `sensevoice` | `model.int8.onnx`, `tokens.txt` | 239 233 841 + 311 000 | 中英日韩粤 | `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` |
| `whisper-tiny-en` | `tiny.en-encoder.int8.onnx`, `tiny.en-decoder.int8.onnx`, `tiny.en-tokens.txt` | 12.9M + 89.9M + 816K | 英语 | `csukuangfj/sherpa-onnx-whisper-tiny.en` |
| `whisper-base-en` | `base.en-encoder.int8.onnx`, `base.en-decoder.int8.onnx`, `base.en-tokens.txt` | 29.1M + 130.7M + 816K | 英语 | `csukuangfj/sherpa-onnx-whisper-base.en` |
| `whisper-small-en` | `small.en-encoder.int8.onnx`, `small.en-decoder.int8.onnx`, `small.en-tokens.txt` | 112.4M + 262.2M + 816K | 英语 | `csukuangfj/sherpa-onnx-whisper-small.en` |
| `whisper-base` | `base-encoder.int8.onnx`, `base-decoder.int8.onnx`, `base-tokens.txt` | 29.1M + 130.7M + 816K | 99 种 | `csukuangfj/sherpa-onnx-whisper-base` |
| `whisper-small` | `small-encoder.int8.onnx`, `small-decoder.int8.onnx`, `small-tokens.txt` | 112.4M + 262.2M + 816K | 99 种 | `csukuangfj/sherpa-onnx-whisper-small` |
| `vad` | `silero_vad.onnx` | 643 854 | — | GitHub `k2-fsa/sherpa-onnx` release `asr-models` |

- ASR 只有 HF 系下载源（含 `hf-mirror.com`）。ModelScope 上的 `iic/SenseVoiceSmall` 只有
  PyTorch `model.pt`，不是 sherpa-onnx 导出的 ONNX，不能直接用。
- `vad/` 不是可选模型，是所有 ASR 模型共用的切句模型；单独删掉它只会让下次开字幕时重下。
- 下载 URL：`<base>/<repo>/resolve/main/<file>?download=true`（不带 commit，上游没打 tag）。
- VAD 没有 HF 仓库，固定从 GitHub release 下：
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`

### 3.3 这些**不**属于模型目录

不要往 `<root>` 里放这些（它们是各自应用的私有缓存，换应用没有复用价值）：

| 内容 | nostalgiaPod 里的位置 |
|---|---|
| 有声书字幕 JSON | `filesDir/subtitles/<book>/<chapter>.json` |
| AI 翻译结果 / 断点 | 应用内 Room 数据库 |
| 翻译用的音频切片 | **纯内存**，不落盘（历史 `cacheDir/llm-slices` 会被清理） |
| 模型下载任务/进度 | 内存状态，进程重启后按文件存在性重算 |
| GPU 崩溃标记 | `filesDir/llm-models/gpu-crashed`（§7） |
| 音乐/电子书/封面/图片缓存 | `filesDir`、`cacheDir` 下的其他目录 |

---

## 4. 命名规范

### 4.1 `<modelId>`

- 正则：`^[a-z0-9]+(-[a-z0-9]+)*$`（小写 ASCII，连字符分隔）。
- **稳定不变**：它同时是目录名、设置里持久化的值、日志里的标识。改 id 等于换模型。
- 同一个 id 只对应一份确定的上游文件（见 §3.1 / §3.2 表）。

### 4.2 权重文件名

- MUST 与上游发布完全一致，包含大小写。原因：
  - ASR：`AsrModelSpec` 里 encoder / decoder / tokens 的文件名是直接拼路径的，改名就加载失败。
  - LLM：LiteRT-LM 的编译缓存键由**模型文件名 + mtime + size** 派生，改名/改 mtime 会让缓存
    失效（不致命，只是首次加载变慢）。
- 不要加前缀/后缀、不要放 `model.bin` 这种通用名（多个模型目录并存时容易混淆，也破坏缓存键）。

### 4.3 临时文件

| 名称 | 含义 | 读取方 |
|---|---|---|
| `<最终文件名>.part` | 未完成的下载（Range 续传用） | MUST 忽略 |
| `<最终文件名>.migrating` | nostalgiaPod 迁移中的半成品 | MUST 忽略 |
| `.nostalgia-write-test` | 目录可写性探针，用完即删 | 忽略 |

---

## 5. 完整性判定（其他应用必须用同一规则）

**只有"正式文件名"存在，才算这个模型已安装。**

1. `File(dir, fileName).isFile && length() > 0`。
2. LLM 还应等于 §3.1 的字节数（下载完成后校验，不等就报"下载不完整"并保留 `.part` 续传）。
3. ASR 不做逐字节比对——镜像站偶尔和官方差几 KB，硬比会误判；只要求 `> 0`，
   由"`.part` 只在下载完成后才 rename"保证完整性。
4. MUST NOT 用 mtime、权限、目录存在性当判据。
5. MUST NOT 把 `.part` / `.migrating` 当模型加载（原生库会读到半截文件然后崩）。

---

## 6. 编译缓存 `litert-cache/`

| 模式 | 位置 |
|---|---|
| 应用存储 | `<app>/cache/litert-lm/`（系统可回收，`Context.cacheDir`） |
| 安卓共享目录 | `<root>/litert-cache/`（跟模型放一起，别让缓存继续占内部存储） |

- 内容是 LiteRT-LM 生成的 GPU/CPU 编译产物（形如 `*_mldrift_program_cache.bin`、
  `*_mldrift_weight_cache.bin`），文件名/校验键由引擎按模型文件指纹生成，**属于实现细节**，
  不要解析、不要依赖。
- 语义：**纯派生数据**。删掉只会让下一次加载重新编译（几十秒），不会丢模型。
- 因此：任何应用都可以删（最好在模型没加载时）；迁移/切换目录时 nostalgiaPod **不搬缓存，
  直接删**——跨目录复制会改 mtime，旧缓存已经对不上号，搬过去只是白占空间。
- 共享目录不可写（权限被撤、卡拔了）时，nostalgiaPod 会退回 `cacheDir/litert-lm`，保证推理能用。
- 引擎（`litertlm-android`）升级后旧缓存可能整批失效，属正常现象；缓存文件也按模型文件的
  mtime + size 校验，跨目录复制过的模型会自动重建。
- nostalgiaPod 的缓存预算：默认 1 GB / 30 天，按 LRU + TTL 清理（设置 → 存储 → 缓存 →
  「大模型编译缓存」）。其他应用如果也写这个目录，请接受"可能被别人清掉"。

---

## 7. GPU 崩溃标记

- 路径：`<app>/files/llm-models/gpu-crashed`，内容是 `pid=<pid>`。
- 语义：上一次 GPU 推理把进程带崩了（Vulkan 驱动 SIGSEGV，Java 层 catch 不到）。文件存在
  时下一次加载强制走 CPU；一次 GPU 推理成功后删除。
- **故意留在应用私有目录**：如果跟着共享目录走，A 设备的驱动崩溃会让 B 设备也跟着禁用 GPU。
- 其他应用不要读写它；自己遇到同类问题就用自己应用私有目录里的标记。

---

## 8. 写入约定（多应用共存）

1. **先查后下**：正式文件已存在且 `> 0` 就直接用，不要重下。
2. **`.part` + rename**：下到 `<最终名>.part`，校验后 `rename` 成正式名。任何时刻被打断，
   目标目录里都不会出现"看起来完整、实际是半截"的文件。
3. **续传**：`Range: bytes=<已下载>-`；本地 `.part` 已经等于预期大小时直接 rename，不必再发请求；
   服务器忽略 Range 回 200 时从头覆盖；416 视为"已下完"。
4. **同一模型同时只允许一个写入者**。两个进程同时写同名 `.part` 会互相截断。需要并发时：
   用 `<最终名>.<pid>.part` 之类的独立临时名，或对 `<dir>/.lock` 加 `FileChannel.lock()`。
5. **写前探测目录**：在目标目录创建再删除一个探针文件；失败就退回自己的私有目录并提示，
   不要半途失败留下一地垃圾。
6. **不要在模型被 `mmap` 时原地改写文件**：要换权重就用新 id / 新文件名，或者先确保没有
   进程在加载它。

---

## 9. 演进规则

- **新增模型**：加一个 `<modelId>/` 目录即可，不需要迁移。老应用看到不认识的 id 忽略。
- **同 id 换权重**：不要原地替换（别人可能正在用，缓存键也会变）。用新 id，或在文件名里
  带版本。
- **改目录结构**：必须保持向后兼容读取（例如继续认 `<root>/llm-models/`），或引入
  `<root>/v2/` 这样的新根并保留旧根只读兼容。写进本文档再改代码。
- 根目录切换时 nostalgiaPod 的迁移策略：**先复制到新根（旧根保持完整、配置仍指旧根）→
  全部成功后写配置 → 最后删旧文件**。中途失败不会丢模型。冲突时由用户选择"覆盖新目录"
  或"保留新目录（删掉已下载的）"。`gpu-crashed` 与 `*.migrating` 不参与迁移。

---

## 10. 参考实现（nostalgiaPod）

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/aurora/music/data/ModelStorage.kt` | 根目录/配置、路径解析、可写探测、迁移、编译缓存目录 |
| `app/src/main/java/com/aurora/music/data/llm/LlmModelCatalog.kt` | LLM 模型清单、`llmModelDir()`、下载源、GPU 标记 |
| `app/src/main/java/com/aurora/music/data/llm/LlmModelStore.kt` | LLM 下载（`.part` 续传、大小校验、删除） |
| `app/src/main/java/com/aurora/music/data/llm/LlmRuntime.kt` | LiteRT-LM 引擎加载（`cacheDir` 走 `ModelStorage.litertCacheDir`） |
| `app/src/main/java/com/aurora/music/data/subtitle/AsrModelCatalog.kt` | ASR 模型清单、`asrModelDir()`、VAD 路径 |
| `app/src/main/java/com/aurora/music/data/subtitle/AsrModelStore.kt` | ASR 下载、VAD 准备、删除 |
| `app/src/main/java/com/aurora/music/data/CacheManager.kt` | 编译缓存分类（`llm_compile`）的统计/清理/淘汰 |
| `app/src/main/java/com/aurora/music/ui/screens/settings/StorageSettingsScreen.kt` | 「大模型存储」设置界面 |

现有模型清单以 §3.1 / §3.2 为准；代码里的 `LlmModelCatalog` / `AsrModelCatalog` 是唯一事实来源，
本文档在增删模型时必须同步更新。
