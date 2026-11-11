# 电子书：阅读、进度与随机阅读

## 1. 内容模型：一切位置都落在"章 → 段 → 字符"上

```
Book
 └── Spine（章节，spineIndex）
       └── Block（段落/标题，blockIndex）
             └── 字符（charOffset）
```

页号是**派生量**，不进入任何持久化锚点。这是整个阅读器最重要的约束：

- 改字号、换字体、旋转屏幕、换设备 → 重新分页 → 把 `(chapter, block, char)`
  换算成新页号，永远停在原段落。
- 页缓存键包含 `字号 × 字体 × 宽 × 高`，任何一项变化都会失效重排。
- 代价：大章节改字号后有一次可感知的卡顿，换来的是"永不漂移"。

解析层把 EPUB/MOBI/TXT 统一成这套模型：

- EPUB：`container.xml → OPF → (NCX | nav.xhtml) → spine`，逐 spine 抽块
  （标题按 h1–h6 分级，其余按段落），并解析内链/脚注。
- MOBI：PDB 头 → MOBI header → EXTH → 文本记录（MOBI6 分页 / KF8 skeleton+fragment
  重组）；DRM 加密文件直接拒绝入库。
- 解析结果按 `md5` 缓存在 `ebook_cache/<md5>/parsed_v3.json`。缓存键带版本号，
  解析器升级即失效（不做迁移）。

## 2. 存储

### 表结构

`ebooks`（主键 = 绝对路径 `path`）：

| 列 | 含义 |
|---|---|
| `spineIndex` / `blockIndex` / `charOffset` | 位置真值 |
| `anchorCode` | 真值的可读投影，`chapter:block`（1 基） |
| `pageIndex` | 派生页号，只用于快速恢复/显示 |
| `progressPct` | 全书百分比，用于列表排序 |
| `lastReadAt` | 毫秒时间戳，**并发写的仲裁依据** |
| `md5` / `size` | 解析缓存命中与去重 |
| 标题/作者/格式/封面 | 元数据 |

`ebook_roots`：书架根目录（含一个默认"书架"哨兵 id）。
`ebook_progress_history`：主键 `(path, minuteBucket)`，每本最多 30 行，用于阅读热力图，
**不参与位置恢复**。

### 写入规则

- DAO 用**条件更新**：`UPDATE ... WHERE path = :path AND lastReadAt <= :at`。
  晚到的旧进度不会覆盖新进度，把并发问题压到 SQL 层解决。
- 写入时机：滚动停止后节流、`ON_PAUSE`、退出阅读器。
- **"眼 vs 耳"仲裁**：朗读和手动阅读可以同时推进位置。落盘时比较
  `eyeAt`（视觉翻页时间）与 `tts.positionAt`（朗读位置时间），谁的时间戳新写谁。
  离开阅读器时用 `stop(savePos = false)`，防止"耳"的旧位置覆盖"眼"的新位置。
- 恢复优先级：`rememberSaveable` 的本地快照（比初始化新）> 数据库 > TTS 内存位置。
  锚点还没换算成页时用 `anchorPending` 抑制渲染，避免先闪到第 0 页再跳。

### 持久化的边界

- **没有书签/高亮/笔记表**。朗读时的句子底色是瞬态的 `SpanStyle`，不落库。
- 书库进度**不做跨设备同步**。只有"随机阅读"的会话在中继侧有 30 分钟 TTL 的续读状态。
- 主键是绝对路径，**移动或重命名文件就丢进度**。这比"重排丢位置"更现实，也更难修。

## 3. 分页与页窗口

### 分页

`ChapterPaginator` 用 Compose `TextMeasurer` 做**二分查找切分点**：在"这段能放下 /
放不下"之间收敛，标题按层级缩放（h1–h6 = 1.45 / 1.28 / 1.16 / 1.08），行高 1.55×。
复杂度 O(n log n)，大章节是主要耗时来源。

缓存键：

```
<字号> x <字体 hash | "sys"> @ <宽> x <高>
```

随机阅读另有一个**仅内存**的缓存，避免把磁盘页缓存写爆。

### 窗口

- 缓冲区是一个 **41 槽环形窗口**（前后各 20 页），中心槽固定。
- 视口只渲染 **7 页**（`HorizontalPager`，`beyondViewportPageCount = 3`），中心恒为 3。
- 翻到窗口边界时环形旋转，旋转后 `scrollToPage(3)` 复位，用户感知不到跳变。
- 前后各预排 **2 章**；随机阅读模式改为 0 章预排 + 24 条内存 LRU。

### 阅读器 UI

- 只有一种阅读模式：**水平分页**。没有垂直滚动阅读。
- 点击屏幕左右 18% 区域翻页，中间呼出工具栏。
- 阅读器是覆盖层而不是路由：关闭时只是黑色覆盖层淡出，下面的导航/列表不重新加载。
- 字体、主题、字号、朗读单元、随机阅读缓存大小存在 `EbookPrefs`（DataStore），
  重新进入界面保持原设置。

## 4. 随机阅读

### 交互模型

垂直方向是"换书"的 3 页 Pager（上/下是上一本/下一本），水平方向仍是正常的翻页阅读。
每次滑动触发一条流水线：拉新书 → 解析 → 分页 → 定位到随机段。

本地有 `history` / `forward` 各 10 本的导航栈：栈内跳转**零网络**，越界才请求。

### 客户端：三级取书

```
session（feed_start）
   └── batch（feed_batch，一次若干本）
         └── chapter（feed_chapter，按需单章正文）
```

`RandomFeedManager` 维护本地缓存，默认 20 本（可配 5–50），剩余降到一半时后台补货，
失败 3 秒后重试。没有中继/服务端时降级为"从本地书库随机"，保证离线可用。

随机模式对 TTS 有两个特殊约定：

- `persistProgress = false`：不污染书库里的正式进度。
- `resumeFromBlockStart = true`：从段落开头读，不从句子中间开始。

### 服务端：3 本滑窗

- 服务端为每个会话维护 3 本的滑动窗口，每前进一本只补一本。
- 随机定位最多尝试 5 次；候选必须是 `level == 0` 的正文段（不取标题），
  且段落长度有下限（80 字符），避免随机到"第三章"这种一行字。
- `feed_rules.js` 是一个 JS 规则引擎，按章节标题上下文过滤/加权段落。
  规则与 Rust 解耦，可以热改而不重编译。

### 中继侧：预解析队列

- 每客户端维护 **50 本已解析**的书，降到 25 本时后台补货；按路径去重；会话 TTL 30 分钟。
- `feed_start` / `feed_batch` **只从队列取，绝不在请求路径上解析**。
  解析成本被挪到后台，请求延迟恒定。

## 5. 搭配中继的流量策略

必须说准确：

> 随机阅读一次只下发**一章正文**，不是整本 EPUB。批量预取省的是请求数、RTT、
> 端上解析与等待时间，**不是总字节数**。

真正的省流量点：

| 策略 | 效果 |
|---|---|
| 只传单章 | 一次几 KB 到几十 KB，而不是整本几 MB |
| 批量会话 | 把多次"随机一本"合成一次 `feed_batch`，减少 RTT 与鉴权开销 |
| 服务端滑窗 + 预解析 | 请求路径不做解析，起播稳定 |
| 客户端 20 本缓存 | 来回滑动命中缓存，零请求 |
| 历史/前进栈 | 栈内跳转完全离线 |
| 失败重试 3s | 避免网络抖动时打爆请求 |
| 30 分钟会话 TTL | 过期即释放，避免长期占用内存 |

反过来，如果把 `remoteCacheSize` 调大，内存和预取流量都会上升——它是"体验 vs 资源"
的旋钮，不是纯优化。

## 6. 已知缺口

- 没有书签、高亮、笔记的持久化。
- 主键是路径，文件移动即丢进度；也没有稳定的 `ebook_id`。
- 没有跨设备进度同步（随机阅读除外）。
- 朗读播放器没有 MediaSession，锁屏/蓝牙/车机控制缺失。
- 只有水平分页，没有滚动阅读模式。
- 改字号/旋转会重排整章，超大章节有卡顿。
- `remote_database_relay.md` 未记录 `feed/batch`，文档滞后于实现。

## 关键文件

`data/ebook/{EbookDb,EbookStore,EbookScanner,EpubParser,MobiParser,EbookModels,EbookAnchorCode,EbookPrefs}.kt`；
`ui/ebook/{EbookReaderScreen,EbookPagination,EbookPageWindow,RandomReaderScreen,EbookLibraryScreen}.kt`；
`data/cast/RandomFeedManager.kt`；`desktop-app/src-tauri/src/{ebook_feed.rs,feed_rules.js}`；
`relay-server/agent/src/feed_queue.rs`。
