# NeoUnifiedPlayer 控制层统一方案

> 状态：实施中
> 范围：只统一控制层。音频输出仍是「音乐 ExoPlayer + TTS ExoPlayer」两条 AudioTrack，由系统混音；投屏只投音乐，本机继续输出 TTS。

## 1. 目标

把现在散落在 `PlaybackService`、`UnifiedPlayer`、`PlayerViewModel`、`FloatingWindowManager`、`SongIntroController` 里的播放仲裁逻辑收进一个协调器 `NeoUnifiedPlayer`：

- 两个通道：音乐（含有声书）、电子书（TTS 听书）。
- 对外暴露全局 `play / pause / next / previous`，以及分通道 `musicPlay / musicPause / musicToggle / musicUnload / bookPlay / bookStop`。
- 一份 `UnifiedState` 给悬浮窗、通知、UI 使用。
- 歌曲介绍 TTS 与听书 TTS 共用一条串行队列，永不同时发声。
- 音乐区分「暂停」和「卸载」：暂停保留装载，卸载后只有用户手动装载才会回来。
- 悬浮窗在专辑封面上叠加当前朗读句（书在播时）。

## 2. 结构

```
PlaybackService
  └─ NeoUnifiedPlayer            ← 新增，唯一控制层
       ├─ MusicChannel           ← 包住现有 player（ExoPlayer）
       ├─ EbookChannel           ← 包住现有 ebookTts（EbookTtsController）
       ├─ TtsQueue               ← 新增，intro/ebook 串行闸门
       ├─ UnifiedState (StateFlow) ← 悬浮窗/UI/通知统一读这份
       └─ sessionPlayer 选择器    ← 内部决定 session 指向 music 还是 book shadow
```

`NeoUnifiedPlayer` 是协调器，不是新的音频引擎。`MediaSession` 仍然会在「音乐 ExoPlayer」和「书的影子 player」之间切换，但切换变成 `NeoUnifiedPlayer` 的内部实现。

## 3. 状态机

每个通道三个布尔：

```kotlin
data class ChannelState(
    val loaded: Boolean = false,   // 有内容/已装载
    val playing: Boolean = false,  // 正在出声
    val armed: Boolean = false,    // 暂停前在播，全局播放时恢复
)
```

| 事件 | 音乐通道 | 电子书通道 | 前台/通知 | 说明 |
|---|---|---|---|---|
| 用户点歌 | load + play | 不变 | MUSIC 或 DUAL | 手动装载 |
| 书开播 | 在播则压低，否则不变 | play | BOOK/DUAL | 沿用现有 duck |
| 全局 pause | pause，`armed=playing` | stop，`armed=playing` | 保持前台 | 不卸载 |
| 全局 play | `armed && loaded` → play；`armed && !loaded` → 不动 | `armed` → `playFromBookmark` | 按实际 | 卸载不重载 |
| 音乐通道 pause（书在播） | unload，`armed=false` | 不变 | BOOK | 用户主动卸载 |
| 音乐通道 pause（书没播） | pause，`armed=true` | 不变 | MUSIC | 保留装载 |
| 音乐通道 play | `!loaded` → load+play；`loaded` → play | 不变 | MUSIC/DUAL | 手动装载 |
| 全局 next/prev | 书在播 → 书；否则音乐 | | | 有声书属于音乐通道 |
| 外部打断（来电/拔耳机） | pause，`armed=playing` | stop，`armed=playing` | 保持 | 记 `autoResume` |
| 外部恢复 | `armed` → play | `armed` → play | | 瞬态自动，常停等用户 |

关键点：

- 暂停 ≠ 卸载。全局 pause 只暂停，`armed` 保留；全局 play 会恢复已装载的音乐。
- 卸载只发生在「书在播时用户主动暂停音乐」。卸载后 `armed=false`，全局 play 不会重载，只有用户点歌/点音乐播放才 `load()`。
- 外部打断用 `armed` + `autoResume` 取代原来的 `ExternalSnapshot`。
- 有声书是音乐队列项，`book.playing` 只代表 TTS 听书；有声书的上一首/下一首仍由音乐 player 的 ±步长拦截处理。

## 4. 命令路由

```kotlin
// 全局
fun play()
fun pause()
fun next()
fun previous()

// 分通道
fun musicPlay()
fun musicPause()      // 书在播 → 卸载；否则暂停
fun musicToggle()
fun musicUnload()     // 显式卸载
fun bookPlay()
fun bookStop()
fun bookNext()
fun bookPrevious()

// 状态
val state: StateFlow<UnifiedState>
```

- `next()/previous()`：`book.playing` → `ebookTts.next()/prev()`；否则 `player.seekToNext/PreviousMediaItem()`。
- 系统通知栏/耳机/小部件/磁贴：全部走 `NeoUnifiedPlayer` 的全局命令。
- App 内音乐选项卡/音乐气泡：走 `musicToggle()`，书在播时暂停即卸载。
- 阅读页底栏：走 `bookPlay()/bookStop()`。

## 5. 音乐卸载/装载

```kotlin
data class MusicSnapshot(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val speed: Float,
    val pitch: Float,
)
```

- `unload()`：保存快照 → `player.stop()` → `player.clearMediaItems()` → `loaded=false, playing=false, armed=false`。
- `load()`：从快照恢复 `setMediaItems` + `prepare` + `seekTo(index, position)` + `play()`。
- 卸载后 `UnifiedState` 保留最后一首歌的元数据，悬浮窗仍能显示专辑封面；只是 `musicLoaded=false`。

## 6. TTS 队列（intro + ebook 串行）

不改两个 TTS player，只在 `TtsWorker` 加一个全局串行闸门：

```kotlin
private val playbackGate = Mutex()   // 公平 FIFO
```

- `openStream(owner)`：先 `playbackGate.lock()`，再创建流。
- `TtsStream.awaitDone()` / `close()`：在 `finally` 里 `unlock()`。
- `EbookTtsController.playLoop` 本来就是每段开一个流，所以 intro 会排在「当前段读完」之后、下一段之前。
- `SongIntroController` 的整段介绍是一个流，会等当前 ebook 段结束再播；播完 ebook 继续。
- 两个 controller 的流使用处都要加 `try/finally { stream.close() }`，避免取消时闸门泄漏。
- `TtsWorker.stop(owner)` 要同时关闭该 owner 的活动流并释放闸门。

intro 暂停音乐走 `NeoUnifiedPlayer.pauseMusicForIntro()` / `resumeMusicForIntro()`，这是临时暂停，不触发卸载规则。

## 7. 悬浮窗

### 布局

```
Box (size, clip, gestures)
  Artwork (fillMaxSize)
  Column (fillMaxSize)
    TopBar        (0xFF14161B @ 0.62)
    MiddleBox     (weight 1f；书在播时同色遮罩，否则透明)
      AutoSizeText(当前朗读句)
    BottomBar     (0xFF14161B @ 0.62)
```

- 书没在播：`MiddleBox` 透明，封面完整露出。
- 书在播：`MiddleBox` 用同样的 `Color(0xFF14161B).copy(alpha = 0.62f)`，文字白色、居中、`Ios5Sans`。
- 顶栏/底栏高度由现有内容决定，`weight(1f)` 自动吃掉剩余空间。

### 自动字号

Compose BOM 2024.12 没有 `TextAutoSize`，用 `TextMeasurer` 二分字号，保证不 overflow。最小 8sp；极端长句退化为省略号。

### 句子来源

第一版用 `EbookTtsController.position?.readText`（当前朗读单位）。句子模式下就是「这句话」；段落模式下是整段。后续可在 `TtsWorker` 增加 `currentChunk` 做严格句子级。

### 显示条件

- 音乐已装载 → 显示（封面 + 可选句子）。
- 音乐已卸载但书在播 → 仍显示，背景用书的封面。
- 两者都没有 → 不显示。

## 8. 文件改动

**新增**

| 文件 | 内容 |
|---|---|
| `playback/NeoUnifiedPlayer.kt` | 协调器、状态机、命令路由 |
| `playback/UnifiedState.kt` | `ChannelState`、`MusicSnapshot`、`UnifiedState` |
| `ui/overlay/AutoSizeText.kt` | 二分字号文本组件 |

**修改**

| 文件 | 改动 |
|---|---|
| `playback/PlaybackService.kt` | 创建 `NeoUnifiedPlayer`；删除 `dualArmed`/`externalSnapshot`/`refreshArbitration`/`takeSessionForMusic`/`musicSoloIfBookIdle`/`resolveSinglePlayTarget`/`routeSystemNav` 的散落逻辑；`onStartCommand` 的 ACTION_* 改为调 `neo`；`onIsPlayingChanged`/`ebookTts.playing.collect` 改为通知 `neo` |
| `playback/UnifiedPlayer.kt` | 保留为书的影子 player，由 `NeoUnifiedPlayer` 内部使用；`dualControl`/`onDualSystemPause`/`onDualSystemResume` 接到协调器 |
| `data/AppContainer.kt` | 增加 `unifiedState: StateFlow<UnifiedState>`；`musicState` 保留给音乐 UI |
| `viewmodel/PlayerViewModel.kt` | 音乐操作继续走自定义命令；新增 `pauseForIntro()`/`resumeForIntro()` |
| `ui/overlay/FloatingWindowManager.kt` | 收集 `unifiedState`；`FloatingUiState` 增加 `bookActive`/`bookSentence`/`bookCover`；显示条件放宽 |
| `ui/overlay/FloatingWindowContent.kt` | `Column` 布局 + 中间遮罩 + `AutoSizeText` |
| `tts/TtsWorker.kt` | 全局 `playbackGate`；`TtsStream.close()`；`stop()` 释放闸门 |
| `data/ebook/EbookTtsController.kt` | 流使用加 `try/finally close()` |
| `data/SongIntroController.kt` | 走 TTS 闸门；音乐暂停/恢复改走 `pauseForIntro()/resumeForIntro()` |
| `ui/ios5/Ios5App.kt` | `IntroMusicControl` 接到新的临时暂停方法 |

## 9. 迁移顺序

1. 骨架：新增 `UnifiedState` + `NeoUnifiedPlayer`，只读现有状态，不接管命令。
2. 接管仲裁：把 `dualArmed`/`externalSnapshot`/`refreshArbitration` 搬进 `NeoUnifiedPlayer`。
3. 卸载/装载：实现 `musicPause()` 的卸载分支、`musicPlay()` 的装载分支、`MusicSnapshot`。
4. TTS 队列：加 `playbackGate`，两个 controller 加 `try/finally close()`。
5. 悬浮窗：`Column` 布局 + `AutoSizeText` + 句子来源。
6. 清理：删除不再使用的旧逻辑。

## 10. 风险与默认处理

| 风险 | 默认处理 |
|---|---|
| `player.clearMediaItems()` 卸载时触发 UI 清空 | `UnifiedState` 保留最后一首歌元数据；音乐选项卡显示「已卸载，点播放重新装载」 |
| TTS 闸门泄漏导致永久卡住 | 所有流使用处 `try/finally close()`；`stop()` 兜底释放 |
| intro 暂停音乐误触发卸载 | 走 `pauseMusicForIntro()` 临时暂停，不经过 `musicPause()` |
| 投屏时 `castMute` 让音乐 `isPlaying` 变 false | 音乐通道的 `playing` 以 `player.isPlaying` 为准，`castMute` 只影响本地音量 |
| 悬浮窗在音乐卸载后消失 | 显示条件改为「音乐已装载 或 书在播」；书在播时用书封面做背景 |
| 句子级显示需要 chunk 跟踪 | 第一版用 `position.readText`；确认后再加 `currentChunk` |

## 11. 实施记录

已落地（编译 + `assembleDebug` 通过）：

- 新增 `playback/UnifiedState.kt`：`UnifiedChannel`、`ChannelState`、`MusicSnapshot`、`UnifiedState`。
- 新增 `playback/NeoUnifiedPlayer.kt`：协调器（不是 `SimpleBasePlayer`），持有音乐 ExoPlayer 与书的影子 player，负责状态机、命令路由、会话 player 选择。
- 新增 `ui/overlay/AutoSizeText.kt`：`TextMeasurer` 二分字号，保证不 overflow。
- `PlaybackService`：
  - 删除 `dualArmed` / `externalSnapshot` / `lastBookPara` / `refreshArbitration` / `noteExternalInterruption` / `resumeExternalSnapshot` / `toggleMusicFromApp` / `takeSessionForMusic` / `musicSoloIfBookIdle`。
  - 创建 `NeoUnifiedPlayer`，把 `onIsPlayingChanged`、`onPlayWhenReadyChanged`、`onPlaybackSuppressionReasonChanged`、`ebookTts.playing`、`onUserStop`、耳机拔出广播、书焦点变化全部转给它。
  - `ACTION_PLAY_PAUSE` / `ACTION_NEXT` / `ACTION_PREV` 改为 `neo` 全局命令。
  - `CMD_MUSIC_TOGGLE` / `PAUSE` / `PLAY` / `SOLO` 改为 `neo` 分通道命令；新增 `CMD_MUSIC_PAUSE_TEMP` / `CMD_MUSIC_RESUME_TEMP` 给歌曲介绍临时暂停。
  - `onDestroy` 取消服务 scope，避免协调器收集器泄漏。
- `UnifiedPlayer`：保留为书的影子 player；`dualControl` / `onDualSystemPause` / `onDualSystemResume` 由协调器接线。
- `AppContainer`：新增 `unifiedState: MutableStateFlow<UnifiedState>`。
- `PlayerViewModel`：新增 `pauseForIntro()` / `resumeForIntro()`；`Ios5App` 的 `IntroMusicControl` 改接这两个方法。
- `TtsWorker`：新增全局 `playbackGate`（公平 `Mutex`）与 `activeStreams`；`openStream` 先排队再开流；`TtsStream.close()` 释放闸门；`stop(owner)` 关闭活动流。
- `EbookTtsController.playLoop` / `SongIntroController.runIntro`：流使用处加 `try/finally { stream.close() }`。
- `FloatingWindowManager`：收集 `unifiedState`；`FloatingUiState` 增加 `bookActive` / `bookSentence` / `bookCover`；音乐卸载后仍用最后一首的元数据/封面。
- `FloatingWindowContent`：改为 `Column { 顶栏 / 中间遮罩+AutoSizeText / 底栏 }`；书在播时中间区域用同色遮罩铺满当前朗读句。

实现细节：

- `NeoUnifiedPlayer` 用 `musicEffectivelyPlaying()`（`isPlaying || playWhenReady && STATE_BUFFERING`）判断音乐是否在播，与 UI/服务快照同口径。
- 外部打断用 `interrupted` 标志保证“第一次快照生效”，避免瞬态抑制和 `onPlayWhenReadyChanged` 互相覆盖 `autoResume`。
- `takeSessionForMusic()` 直接 `setSessionPlayer(music)`，即使音乐尚未装载，后续 `setMediaItems` 也会落到音乐 ExoPlayer。
- 音乐卸载的快照只存在内存里；进程被杀后由 `QueueStore` 的常规恢复路径接管（恢复为暂停态，不会自动播放）。

### 11.1 后续修复

- **音乐气泡播放键显示错误**：`PlayerViewModel` 的 `isPlaying` 在音乐卸载后不再回落到会话 player（书）的 `isPlaying`，改看 `container.unifiedState.music.playing`；`syncFromController`、`ticker`、新增的 `unifiedState` 收集器三处统一口径。
- **阅读页播放键只起 TTS**：新增 `NeoUnifiedPlayer.onBookStartedFromApp()` 与 `CMD_BOOK_STARTED`；`ReaderBody` 增加 `onBookStarted` 回调，`EbookReaderScreen` / `RandomReaderScreen` 在起播朗读后调用 `playerVM.onBookStartedFromApp()`，两者都 arm 时音乐一起恢复。
