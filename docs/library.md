# 媒体库的组织与管理

## 1. 总体模型

这个 app 曾经是多服务器客户端（Subsonic / Jellyfin / Green Music）。现在后端已经
收敛为**本地双栈**，但保留了当年的抽象缝：

```
UI / ViewModel
      │
 MusicRepository        统一门面：home / 列表 / 详情 / 搜索 / 歌单导入导出
      │
 MediaBackend (interface)          ← 能力默认值：不支持的接口返回空/false
      ├── LocalBackend  ←→ library_mediastore.db  ← MediaStore 快照
      └── FileBackend   ←→ library_files.db       ← 用户选择的文件夹
      │
 LocalStore (filesDir/local_store.json)  歌单 + 喜欢，两个栈共用
```

- **同一时刻只有一个音乐源生效**，由 `SettingsStore.library_source` 决定
  （持久化值为 `FILE` 时用文件夹栈，其余一律用 MediaStore 栈），
  `AppContainer` 监听并热切换 backend。
- 两个栈**不合并**：ID 命名空间、封面机制、专辑排序都不同。合并会带来
  "同一个文件两个身份"的问题，所以选择物理隔离。
- 代价：喜欢、歌单、播放历史都以原始 `Song.id` 为键，**切换源后这些数据不跟随**。
  这是有意的取舍——宁可丢关联，也不做不可靠的跨源映射。

### 为什么保留 MediaBackend

即使只剩本地实现，接口仍然有用：所有调用方（ViewModel、歌词、封面、文件夹浏览）
都面向统一模型，不需要知道当前是 MediaStore 还是 SAF 文件夹。后端不支持的能力
（服务器歌词、服务器端标签编辑）通过接口默认值退化为空，调用方无需分支。

## 2. 两套索引库

| | MediaStore 栈 | 文件夹栈 |
|---|---|---|
| 数据库 | `library_mediastore.db`（v3） | `library_files.db`（v4） |
| 主键 | `mediaId` | 绝对路径 `path` |
| 来源 | 系统媒体库快照 | 用户添加的根目录深扫 |
| 刷新 | 手动"重新同步系统库" | 手动扫描 / 断点续扫 |
| 封面 | `content://media/.../albumart` | 内嵌图（content-addressed 缓存）+ 文件夹封面 |
| 专辑键 | 标题归一化 | 标题归一化 |
| 曲目 id | 数字字符串 | `file:<绝对路径>` |

共同点：

- 都有 `tracks` / `albums` / `merges` 三张表。`merges` 存"标题合并"的预计算结果
  （JSON），避免 UI 每次渲染时重新分词。
- 都使用 `fallbackToDestructiveMigration()`。库只是**可重建的索引**，真值在文件系统，
  所以 schema 变更直接清库重扫，不写迁移。代价是升级后到重扫前库是空的。
- 启动时**从不全量扫描**：只读数据库，再做一次廉价的存在性检查，把失踪文件标成
  `available = false`。深扫只发生在加根目录、手动重扫、断点续扫。

### 身份规则

| 对象 | 规则 |
|---|---|
| 文件曲目 | `file:` + 规范化绝对路径 |
| MediaStore 曲目 | 原始 `mediaId` |
| 专辑 | `album::` + 标题 trim + 小写（未知专辑统一为 `unknown-album`） |
| 文件夹栈艺人 | 艺人名本身（trim 后） |
| MediaStore 艺人 | 系统 `ARTIST_ID` |
| 远程曲目 | `remote:<deviceId>:<base64url(对端 id)>` |
| 有声书章节 | `audiobook:<bookId>#<序号>` |

专辑身份**故意忽略 `ALBUM_ID`**：系统库会把同名专辑按艺人/文件夹拆开，而本地曲库
更希望多碟、分文件夹的同名专辑合成一张。代价是不同艺人各自的《Greatest Hits》
会合并——这是明确接受的。

`base64url` 包一层是为了防止对端 id 里的 `:` `#` 破坏复合键。

## 3. 文件夹扫描

`RootScanner` 分两个阶段，目的是把"遍历"和"读标签"解耦：

1. **发现**：迭代 DFS，跳过隐藏目录/文件，全程不读标签，结果按路径排序，
   保证多次运行顺序一致（断点、进度才有意义）。
2. **元数据**：4 个 IO 协程从共享游标取文件，用 `MediaMetadataRetriever` 读
   title/artist/album/duration，并在同一个会话里取内嵌封面。单个文件有 15s 超时，
   超时后用文件名兜底（`Artist - Title`）。

关键规则：

- **能复用就复用**：路径 + size + mtime 没变且上次可用，就不重读标签。
- **短音频直接丢弃**：< 10 秒的条目视为无效（也顺带过滤掉读不出时长的文件）。
- **编码嗅探**：用 `MediaExtractor` 读 MIME，主要为了区分 ALAC-in-m4a 与
  AAC-in-m4a，这决定后面能否走无损/直通路径。
- **扫描从不删数据**：文件消失只把行标成 `available = false`（灰显、排最后）。
  只有用户点"清理"才真正删除。
- **按根目录原子替换**：每个根目录的扫描结果在一个事务里"删该根 + 插新行"，
  一个根失败不会污染其他根。

### 断点续扫

每 200 个文件写一次 checkpoint（JSON，先写 `.tmp` 再 rename），内容是"本次已完成的
行 + 上次的基础快照"。启动时发现残留 checkpoint 会自动续扫。checkpoint 会校验
根路径：存储卷换挂载点时旧 checkpoint 直接作废。

选择 JSON 而不是数据库表：扫描恢复逻辑与 Room schema 解耦，代价是每次要重写整份
列表（超大曲库时是真实的 IO 成本）。

### 可用性是双向的

- 扫描发现文件没了 → `available = false`。
- 播放失败 → 标记不可用并自动跳下一首。
- 之后播放成功 → 自动恢复可用。

这比"相信文件系统"更贴近真实体验：外置 SD 卡、SAF 授权、网络存储都会出现
"文件在但读不了"和"文件不在但能播"的情况。

## 4. 封面

封面缓存是**内容寻址**的：`cache/track_art/c/<图片字节 md5>.jpg`。
歌曲行里只存 `artMd5`，不维护额外的"歌 → 图"索引文件。

解析优先级：

1. 文件内嵌封面
2. 歌曲的 `artworkUrl`（专辑封面 / 文件夹封面）
3. 标准文件夹封面名（cover/folder/front）
4. 文件夹里任意一张图（按目录缓存选择结果，避免每次重绘都换图）

写缓存时会先按 bounds 解码验证，再原子改名，避免半张图被当成有效缓存。

## 5. 标题合并（古典乐场景）

古典乐的"乐章"经常被拆成多条 track。合并引擎 `lib-titlemerge` 在**扫描时**对
专辑内曲目做一次合并，结果序列化成 `merges.rowsJson` 存库。

- 合并表里的索引对应"专辑内排序后的曲目列表"，所以两个栈必须用同一个比较器：
  MediaStore 按 disc/track/title，文件夹栈按完整路径（文件名通常自带曲号）。
- 每个根目录有 `mergeTitles` 开关；一批曲目只有在**全部**来自开启合并的根时才合并。
  这个决定会写进远程库快照，保证接收端看到的效果和发送端一致。
- 预计算换 O(1) 渲染；代价是任何成员变化都要全局重算一次合并。

## 6. 去重

两件事要分开：

- **避免重复**：列表装配处统一 `distinctBy { id }`；一个路径一行；添加根目录时
  拒绝重复的规范化路径。嵌套根目录理论上会重复索引同一文件，目前没有包含性检查。
- **发现重复**：`DuplicateFinder` 按 `(归一化艺人, 归一化标题)` 分组，再按
  ±4 秒的时长做**贪心链式聚类**（不是两两比较）。它只出报告，不提供删除操作；
  删除是用户的决定，不做自动化清理。

## 7. 智能歌单

规则模型是"字段 + 操作符 + 值"的纯字符串结构，存 DataStore 的 JSON。字符串而不是
枚举，是为了持久化 JSON 在字段改名后仍能解析；未知字段按"匹配"处理（开放世界）。

- 输入集合是**整个库**，在内存里过滤；每次打开都重新求值，不缓存结果。
- 可用字段：标题/艺人/专辑/流派/格式/时长/码率/播放次数/距上次播放天数/喜欢/已下载。
- 支持 AND/OR、排序字段、降序、条数上限。
- 播放次数来自最近 3000 条播放历史，所以"最常播放"会随时间漂移。
- `downloaded` 目前查的是旧的下载索引，远程下载的曲目可能不满足——已知不一致。

## 8. 队列与界面状态

### 队列：`filesDir/queue_state.json`

保存曲目列表、当前索引、位置、随机/循环、播放速度、音高、`wasPlaying`。
每 3 秒或有变更时落盘；写入串行化，避免旧快照后落地。

设计要点：

- `SavedTrack` 与 `Song` 形状几乎一致，但**不存 `accent` 颜色**（Compose 类型
  Gson 无法往返），恢复时用 id 重新计算。
- 恢复时**服务端的队列优先**：只有 controller 队列为空才恢复文件里的队列，防止
  后台服务已经在播时被旧快照覆盖。
- 随机模式保存的是"打乱后的显式顺序"，恢复时原样下发，不做二次随机。
- 大队列分批下发（先给一部分，再前后补），避免一次把上万条 timeline 灌给 Media3。

### 界面状态：`filesDir/ui_state.json`

保存导航栈、最后 tab、播放器是否展开、每页滚动位置、展开的分组、阅读器路径、
`resumeOnLaunch` 等。1 秒防抖落盘，退到后台/任务被划掉时强制 flush。

不使用 `onSaveInstanceState` 的原因写在字段注释里：任务被划掉或被系统杀进程时，
它不一定被调用。`resumeOnLaunch` 是一次性标志：划掉时正在播，下次启动恢复一次，
之后手动暂停不会被它重新拉起。

## 9. 变更传播

`AppContainer.libraryReload` 是一个自增的 `StateFlow<Int>`，任何库变更
（扫描完、改标签、切源）都只是 +1。各 ViewModel 监听它并重新查询自己那一小片数据。

用计数器而不是 diff 事件：读操作基本是内存 O(1) 或一次过滤，重查比维护增量事件
更简单，也不会漏事件。

## 10. 已知缺口

- 换源后喜欢/歌单/历史不通用；没有跨源 ID 迁移。
- 根目录 id 用 `System.currentTimeMillis()`，同一毫秒加两个根会撞。
- 备份只包含偏好、歌单、播放历史；库根目录、队列、UI 状态、各类 Room 库不在备份里，
  新设备必须重新添加根目录并扫描。
- 删除扫描结果只能手动"清理"，否则灰行会一直留着。
- 远程库不进入本地库，见 `casting.md`。

## 关键文件

`data/MediaBackend.kt`、`MusicRepository.kt`、`LocalBackend.kt`、`FileBackend.kt`、
`LocalLibrary.kt`、`MusicRootsStore.kt`、`RootScanner.kt`、`ScanCheckpoints.kt`、
`LibraryScanManager.kt`、`db/FilesDb.kt`、`db/MediastoreDb.kt`、`TitleMerge.kt`、
`TrackArtworkCache.kt`、`DuplicateFinder.kt`、`SmartPlaylists.kt`、`QueueStore.kt`、
`UiStateStore.kt`、`PlayHistoryStore.kt`。
