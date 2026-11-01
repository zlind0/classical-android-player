# 随机阅读（ebook feed）

随机书库里的“开始阅读”不是把整本书下载到手机，而是由桌面端负责解析与随机定位，
手机只按章取正文、按当前字号排版。上下滑切书、左右滑翻页、底栏“入库”都在同一套阅读器里完成。

## 角色划分

| 端 | 职责 |
| --- | --- |
| 桌面端 `FeedService` | EPUB 解析缓存、维护 3 本“已解析 + 已随机定位”的窗口、按章发正文 |
| 手机 `RandomFeedManager` | 会话、本地历史/前进栈、章节缓存、整本静默缓存、入库、阅读位置 |
| 手机 `RandomReaderScreen` | 上下切书、书间界限、耳机切书、恢复/保存位置 |
| 手机 `ReaderBody` | 复用普通阅读器：分页、左右翻页、听书、选项 |

桌面端只在内存里保留会话，手机退出时主动 `close`；异常退出没发出去的，服务端会话 30 分钟后自动清理。

## HTTP 协议

所有路径都在 `RelayProxy` 的白名单里，经公网中继原样透传到桌面端。
请求/响应字段一律 camelCase。

### POST /library/ebook/feed/start

```json
{
  "rootKey": "root-id",
  "paths": ["Books/a.epub", "Books/b.epub"],
  "includeImages": false,
  "resume": { "path": "Books/a.epub", "chapter": 3, "block": 12, "char": 40 }
}
```

- `paths` 必须属于 `rootKey` 这个书库，桌面端会用 `db::root_book_paths` 再校验一次。
- `resume` 可省略；带 `path` 时优先恢复这本书，其余槽位随机补满。
- 返回 `{ sessionId, index, books[] }`，`books` 就是当前 3 本窗口。
- 每本书带 `chapterCount`、`chapterTitles` 和当前章的 `chapter` 正文，
  客户端打开即可先渲染第一屏，不用再等一轮请求。

### POST /library/ebook/feed/turn

```json
{ "sessionId": "…", "direction": 1 }
```

- `direction > 0` 下一本，`< 0` 上一本，`0` 只回当前快照。
- 滑动窗口整体前后移动一格，空出来的一侧补一本新的随机书。
- 池子里不足 3 本可解析的书时，窗口会变短（客户端仍能读到 `index`/`books`）。

### GET /library/ebook/feed/chapter?sessionId=…&path=…&chapter=N

```json
{
  "path": "Books/a.epub",
  "chapterIndex": 3,
  "chapter": { "title": "第三章", "blocks": [{ "text": "…", "level": 0 }] }
}
```

- 只允许取当前会话池/窗口里的书。
- 普通阅读器按“章”懒加载：翻到章节边界、TTS 读完本章时才请求。

### POST /library/ebook/feed/close

```json
{ "sessionId": "…" }
```

释放服务端窗口；失败无妨，长时间未使用的会话也会自动过期。

## 服务端缓存

- 解析结果先写内存 LRU（6 本），再落盘 `cache_dir/ebook_feed/<sha1>.json`。
- 缓存键 = 路径 + 文件大小 + mtime；换文件会自动失效重解析。
- 只支持 EPUB；MOBI/AZW3 等解析失败的书在组窗口时直接跳过，不会让整个随机阅读失败。
- 随机定位：优先选正文 ≥ 80 字的章节，再随机段、随机字；短章不会把起点落在空白页。

## 客户端行为

### 打开与恢复

`RandomFeedManager.start()` 会等设备发现/书库缓存最多约 6 秒，避免冷启动直接报离线。
恢复位置来自 `EbookPrefs.saveFeedResume()`（设备 + 书库 + 书路径 + 章/段/字），
只有在同一设备、同一书库、池子里仍有这本书时才生效。

### 翻页与切书

- 左右翻页完全复用普通阅读器的 41 页窗口；随机阅读 `prefetchChapters = 0`，只排当前章。
- 上下滑：外层 `VerticalPager` 的上一页/下一页是“上一本/下一本”界限卡，
  中间页才是正文；落定后把 pager 复位到中间，再按方向调 `turn`。
- 本地保留最近 10 本历史与前进栈：来回切书先走本地栈，不消耗服务端随机窗口。
- 耳机/通知栏“上一首/下一首”在随机阅读里切成上一本/下一本；
  底栏“上一章/下一章”在本书里随机跳到**另一个**章节（正在朗读时停顿一下后接着读新章）。
- 上下滑动切书时，如果原本正在朗读，新书会自动从它的随机位置继续朗读；
  静音翻书则保持静音。
- 耳机切书后固定停顿约 750ms 再开始朗读，先让旧书音频彻底停止，避免两本书串音。

### 章节边界与整本缓存

翻到某章第一页/最后一页时，`RandomReaderScreen` 调 `cacheWholeBook()`：
后台下载整本 EPUB → 本地解析 → 后续章节优先读本机，不再逐章请求。
听书跨章由 `EbookTtsController.chapterTailLoader` 接续：读完本章自动取下一章并接在同一播放流里。

### 入库

底栏“目录”位置换成“入库”（下载图标）。点击后把整本 EPUB 存进默认书架，
并把当前“章/段/字”一起写成这本书的阅读进度；之后它就是一本普通本地书
（有封面、进度、最近阅读），从书架打开会直接落到随机阅读停下的位置。
随机阅读过程本身不写本地书库/最近阅读。

### 状态

- 阅读位置只写 `EbookPrefs` 的 feed 恢复点，不写 `EbookStore` 的进度/分页缓存。
- 随机阅读的分页缓存按“内存键”隔离，不同书/不同位置互不污染。
- 插图开关已经预留在协议和设置里（`includeImages` / `feedImages`），v1 服务端忽略该参数且 `images` 恒为空。

## 相关代码

- 桌面端：`desktop-app/src-tauri/src/ebook_parse.rs`、`ebook_feed.rs`、`http_server.rs`
- 手机端：`data/cast/RandomFeedManager.kt`、`ui/ebook/RandomReaderScreen.kt`、
  `ui/ebook/EbookReaderScreen.kt`、`ui/ebook/EbookPageWindow.kt`、
  `data/ebook/EbookTtsController.kt`、`data/ebook/EbookPrefs.kt`

## 验证

```bash
# 桌面端解析/窗口单测 + 编译
cargo test --manifest-path desktop-app/src-tauri/Cargo.toml --lib ebook_
cargo check --manifest-path desktop-app/src-tauri/Cargo.toml

# 手机端只验证编译（安装与真机测试交给用户）
./gradlew :app:compileDebugKotlin
```
