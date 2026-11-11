# 电子书 / 有声书 / 音乐的协同播放

## 1. 结论先行：一个服务、一个 MediaSession、三个"频道"

app 里能同时发声的引擎最多有四个：

1. 音乐 ExoPlayer（**有声书也走它**）
2. 电子书朗读播放器 `EbookTtsPlayer`
3. 歌曲前奏朗读播放器（第二个 `EbookTtsPlayer`）
4. 投屏接收解码器

但对系统只暴露**一个** `MediaLibrarySession`。会话的"播放器"可以在三者之间切换：

```
                     ┌──────────────────────────┐
                     │ PlaybackService          │
                     │  MediaLibrarySession     │
                     └────────────┬─────────────┘
                                  │ setSessionPlayer
          ┌───────────────────────┼───────────────────────┐
          ▼                       ▼                       ▼
    music ExoPlayer        UnifiedPlayer（影子）      CastSessionPlayer
    （音乐 + 有声书）         （朗读，无实体时间线）      （投屏接收）
```

`NeoUnifiedPlayer` 是协调器：它不发声，只维护"谁在前台、谁被 armed、谁来接管会话"。

## 2. 三个核心概念

- **armed**：本来在播、被别的事情打断的频道。全局播放键会把它拉回来。
  它是一个"意图快照"，不是暂停状态。
- **foreground**：UI 应该展示哪个频道。
- **lastPara**：最后一次朗读的书签（章/段/字符），`tts.stop()` 后仍然保留。

`UnifiedState` 对外暴露三个频道（MUSIC / BOOK / CAST）的 `loaded / playing / armed`，
以及给通知、悬浮窗、小组件用的扁平字段（标题、艺人、封面、当前句子）。

## 3. 仲裁规则

### 全局播放 / 暂停

- `play()`：先复活所有 armed 频道（顺序 cast → book → music）。如果什么都没 armed，
  就按 foreground 回退：BOOK 且有书签 → 朗读；否则 music。
- `pause()`：把**正在播的所有频道**都 arm 起来再停。朗读没有 pause，只有 stop，
  所以"暂停朗读"= `tts.stop()` + 记住书签。
- `toggle()` 只看"是否有任何一个在播"。
- `next/previous` 按 **cast → book → music** 路由，与 foreground 无关。
  音乐在播放超过 4 秒时，"上一首"先回到本曲开头。

### 会话归属

```
有投屏会话            → 投屏播放器（无条件优先）
否则 朗读在播          → 影子播放器
否则 音乐在播          → 音乐
否则 朗读 armed 且有书签 → 影子播放器
否则 音乐已加载        → 音乐
否则                  → 保持当前，不切换（sticky）
```

"sticky"是刻意的：暂停朗读的瞬间，通知不应该跳回上一首歌。

### 朗读进行时操作音乐

- 在朗读时暂停音乐 → **直接卸载音乐队列**（保存快照）。
  否则下一次全局 Play 会把用户刚停掉的音乐又拉起来。
- 只有显式"播放音乐"才会从快照恢复队列、索引、位置、随机/循环、播放参数。
- 朗读停止时音乐**不会**自动恢复；但从阅读器里"开始朗读"会恢复之前 armed 的音乐，
  形成"停止朗读 → 音乐暂停；继续朗读 → 音乐继续"的闭环。

### 投屏

- 投屏开始：如果音乐在播，暂停并 armed；**不动朗读**。
- 投屏结束：不自动恢复音乐；朗读保持原状。

### 外部打断

| 场景 | 策略 |
|---|---|
| 永久失去音频焦点 / 拔耳机 | 全部 arm + 停，**不自动恢复**，等用户或系统播放键 |
| 来电等短暂 focus loss | 朗读停止并 arm；音乐**不主动 pause**，交给 ExoPlayer 自己恢复 |
| 恢复焦点 | 走全局 `play()`，所有 armed 频道一起复活 |
| 有声书侧焦点 | 失去焦点从不停有声书，清掉 duck 标记后下一 tick 重新申请（"粘性"） |

## 4. 焦点与音量：为什么自己 duck

Media3 自带的 duck 是固定 ×0.2，会和 app 自己的"台内音乐音量"叠加，导致音乐几乎听不见。
所以：

- 朗读开始时，音乐播放器**主动放弃独占焦点**（`setAudioAttributes(..., false)`），
  由 service 每 tick 做一次精确的音量缩放。
- 音量公式：

```
player.volume = replayGain × ebookDuck × abookGain × (castMute ? 0 : 1)
```

`ebookDuck` 默认把台内音乐压到 20%（可在阅读器里 5%–100% 调），
`abookGain` 是有声书自己的 0.5–2.0 增益，100ms 一拍平滑靠近目标值。
投屏镜像期间 `castMute` 直接把本机音量归零。

- 朗读播放器只在"允许 duck 别人 + 正在出声"时申请 `MAY_DUCK`；默认**不**压其他 app。
- 有声书混音开启时，它会自己申请一个 `TRANSIENT_MAY_DUCK`（语音用途），
  并在检测到"后来有别的 app 开始播"时每 3 秒重新抢一次 duck。

## 5. 朗读（TTS）流水线

### 切分

两层：

1. **句子切分**：中英文标点 + 换行；`.` 夹在数字之间或后面跟字母时不算句末；
   省略号算句末；收尾引号/括号归到前一句。
2. **合成切分**：长段落按 450 字符再切，优先在标点处断。纯标点/空白不合成，
   避免引擎对 `"。"` 空转。

朗读单元可以是"段"或"句"（`EbookTtsUnit`），可在阅读器里切换。

### 合成与播放

- 一个 app 级 `TtsWorker`，两个 owner（EBOOK / INTRO），各自有 generation 计数器：
  切换书籍/语音会让旧任务的结果直接丢弃。
- **全局公平队列**：前奏朗读和电子书朗读共用一把 `playbackGate`，永不叠音；
  前奏会插在电子书两个单元之间。
- 合成结果统一转成 **48 kHz / 立体声 / 16-bit WAV**，在内存里播放，不落盘。
  开头补 25 ms 静音，抵消 `DefaultAudioSink` 每次 flush 后的淡入，否则每句开头会被削。
- 播放器**不按句重建**：只在第一次播放时搭好，之后不断 append。
- 预取：排完第 n 段后，后台合成第 n+1 段，只进缓存不碰播放器。
- 缓存：内存 LRU，32 MiB（约 170 秒 48k 立体声），键包含引擎/语音/语速/音高/音量/文本 hash。
  改音量会让缓存全部失效——这是"音量在 PCM 里做"的直接代价。
- 本地引擎优先用内嵌的微软离线语音（按需解压，一次一个 voice）；不可用时自动降级到
  系统 TTS，并提示用户。

### 与阅读器的位置映射

因为朗读和绘制用的是同一套 Block 模型，高亮不需要坐标换算：直接给当前 `Para` 的
`[startChar, endChar)` 加背景色。每 250ms 轮询朗读位置，需要时**只向前**翻一页，
避免抖动。

跨章时 `chapterTailLoader` 按需拉下一章，实现连续朗读（随机阅读也靠它）。

## 6. 有声书：本质上是音乐队列

有声书的章节在播放时被映射成 `Song`，id 是 `audiobook:<bookId>#<index>`，
直接复用音乐播放器。收益是 DSP、ReplayGain、交叉淡入、USB 直通、睡眠定时、
车机浏览全部免费获得。

代价：

- 有声书和音乐**不能同时播**（同一个 ExoPlayer 队列，开始一个会替换另一个）。
- 章节的"下一首/上一首"必须在两处拦截（通知命令和耳机按键），改成 ±N 秒快进。
- 朗读 duck 会把有声书也一起压小——代码没有阻止这种组合，靠导航路径避免。

### 数据模型

- **书 = 扫描根目录的直接子目录**；章节 = 目录内递归展开的音频文件，按 track 号、
  再按自然路径排序。
- 没有"系列/合集"实体，就是平铺列表 + 最近播放。
- 章节标题可用关键词过滤（默认过滤"目录/版权/注释/参考"），过滤后的列表就是
  播放索引空间。
- 进度是**每本书一个续读点**：`currentPath + positionMs`，外加 per-book 的
  `speed`（0.5–4×，0.25 步进，保持音高）和 `gain`。
  按路径而不是按索引存，重扫/改关键词后不会续到错误的章节。
- 没有"每章已完成/未完成"的位图。这是将来要补的 schema。

## 7. 睡眠定时

一个全局的 `SleepTimer`（基于 `elapsedRealtime`，一次性）：

- 到点后先对外部会话发一轮 PAUSE media key，再让音乐服务做 6 秒淡出
  （有声书也走这条路）。
- 如果正在朗读，最终收尾交给朗读循环：读完当前单元再停，不会切在半句话。
- 7 秒 / 12 秒再补两轮 PAUSE，防止第一轮被自己还在淡出的会话吃掉。

## 8. 状态保存矩阵

| 状态 | 存在哪 | 何时写 | 何时恢复 |
|---|---|---|---|
| 音乐队列/索引/位置/随机/循环/速度 | `queue_state.json` | 200ms tick，>2s 才落盘 | 服务队列为空时恢复 |
| "划掉时正在播" | `ui_state.json:resumeOnLaunch` | `onTaskRemoved` 且正在播 | 消费一次 |
| 有声书续读点/速度/增益 | `abook_progress` 表 | 同上 + 切书前同步落盘 | 打开书时按 `currentPath` 查 |
| 朗读位置 | `ebooks` 表 | 每个单元推进 / 停止时 | 打开书时读 `lastReadAt` |
| 随机阅读续读 | `EbookPrefs.feedResume` | 每个单元 | 会话恢复 |
| 合成音频 | **不持久化** | — | 重新合成 |
| 通知快照 | SharedPreferences `now_playing` | 状态发布时 | 小组件/磁贴 |

位置还有两个防抖规则：

- 单次 `position == 0` 的采样会被抑制 300ms，避免播放器切换时闪回 0:00。
- 双频道（朗读 + 音乐）时，UI 的位置以**服务快照的音乐状态**为准，
  而不是会话播放器——因为会话此时是朗读的占位时间线，位置恒为 0。

## 9. 取舍与缺口

- **单会话 + 影子播放器**：一个通知、一个蓝牙目标，代价是到处都要判断
  "现在会话是不是朗读"，代码里有六处类似分支。
- **朗读只有 stop 没有 pause**：状态简单，但每次暂停都是完整 teardown，
  重入点最多差一个朗读单元。
- **焦点是"协作式"而非"权威式"**：混音体验好，但依赖 OEM 的焦点实现。
- **有声书复用音乐队列**：功能白拿，但两者互斥，且朗读 duck 会误伤有声书。
- 朗读播放器没有 MediaSession，锁屏控制缺失。
- 有声书没有每章完成状态；加字段就会触发破坏性迁移。
- 合成缓存只在内存，进程死亡后重新合成。

## 关键文件

`playback/{NeoUnifiedPlayer,UnifiedPlayer,UnifiedState,PlaybackService,EbookTtsPlayer,CastSessionPlayer,PlaybackPosition,NowPlaying}.kt`；
`data/audiobook/{AudiobookDb,AudiobookStore,AudiobookScanner,AudiobookSession}.kt`；
`data/ebook/EbookTtsController.kt`；`tts/{TtsWorker,SentenceSplitter,Ssml,TtsWav,MsEmbeddedEngine}.kt`；
`ui/ebook/EbookMusicPanel.kt`；`data/{QueueStore,UiStateStore,SleepTimerWorker}.kt`。
