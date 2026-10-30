# Aurora Desktop

Aurora 局域网资料库共享协议的桌面提供端（Windows / macOS / Linux）。

用 Tauri 2（Rust + React）实现。把本机文件夹添加为共享目录后，扫描结果存入本地 SQLite，
并通过局域网 HTTP 暴露给 Aurora 手机端浏览 / 播放。协议细节见仓库根目录的
[`remote_database.md`](../remote_database.md)。

## 功能

- **三类共享目录**
  - 音乐：按曲目浏览
  - 有声书：每个子文件夹是一本书
  - 书籍：EPUB / MOBI / AZW3 / PDF / TXT
- **音乐两种归类方式**
  - `ID3 标签`：艺人 / 专辑 / 碟号 / 曲号取自内嵌标签
  - `兼容模式`：外层文件夹 = 艺人，内层文件夹 = 专辑
- **视频只共享音频**：视频容器会被扫描为曲目 / 章节，播放时用 ffmpeg 提取音轨并缓存为 m4a，
  客户端不会下载视频数据。
- **扫描后预览**：艺人 / 专辑 / 曲目、有声书章节、书籍列表都可在应用内直接查看与试听。
- **协议服务端**：mDNS + UDP 发现、`/library/*`、`/media/*`，支持 HTTP Range。
- **高效 SQLite 存储**：WAL、批量事务、预编译语句、增量扫描（按 `size + mtime` 跳过未变化文件），
  封面按目录去重存储。

## 目录结构

```text
desktop-app/
├── index.html
├── package.json
├── vite.config.ts
├── scripts/gen-default-cover.mjs   # 生成无封面时的占位图
├── src/                            # React 前端
│   ├── App.tsx
│   ├── api.ts                      # invoke 封装与 TS 类型
│   ├── player.ts
│   └── components/
└── src-tauri/
    ├── Cargo.toml
    ├── tauri.conf.json
    ├── capabilities/default.json
    ├── icons/
    ├── assets/default-cover.png
    └── src/
        ├── lib.rs                  # Tauri 启动、状态、命令注册
        ├── commands.rs             # 前端可调用的命令
        ├── db.rs                   # SQLite schema / 查询 / 版本签名
        ├── scanner.rs              # 三类目录扫描与增量写入
        ├── meta.rs                 # lofty 音频标签 + EPUB 元数据
        ├── media.rs                # ffmpeg 检测、视频音轨提取缓存
        ├── http_server.rs          # 协议 HTTP 服务（axum）
        ├── discovery.rs            # mDNS + UDP probe/offer
        ├── models.rs
        └── util.rs
```

## 开发与构建

需要 Node.js 20+、Rust 1.85+，以及各平台 Tauri 的系统依赖
（macOS 需要 Xcode Command Line Tools，Linux 需要 WebKitGTK 等，Windows 需要 WebView2）。

```bash
cd desktop-app
npm install
npm run tauri:dev          # 开发
npm run tauri:build        # 生成当前平台安装包
```

跨平台打包建议分别在各自系统上执行 `npm run tauri:build`（Tauri 不提供可靠的交叉编译）。

可选依赖：`ffmpeg` / `ffprobe` 需在 `PATH` 中。没有 ffmpeg 时：

- 视频文件的时长会显示为未知；
- 视频音轨会回退为直接返回原文件（音频仍可播放，但客户端会拿到视频容器）；
- 普通音频文件不受影响。

可以用环境变量指定可执行文件路径：`FFMPEG_PATH` / `FFPROBE_PATH`。

## 数据存储

数据库位于 Tauri 的 app data 目录：

- macOS：`~/Library/Application Support/com.aurora.desktop/library.db`
- Windows：`%APPDATA%\com.aurora.desktop\library.db`
- Linux：`~/.local/share/com.aurora.desktop/library.db`

同目录下的 `cache/audio/` 保存从视频提取出来的 m4a 音轨缓存。

主要表：

| 表 | 说明 |
| --- | --- |
| `roots` | 共享目录：路径、类型、音乐模式、扫描状态、条目数 |
| `tracks` | 音乐曲目：标签、时长、曲号/碟号、`from_video`、封面键 |
| `audiobooks` / `audiobook_chapters` | 有声书与章节 |
| `books` | 书籍：书名、作者、格式、封面 |
| `art` | 封面二进制，按 `album:<root>:<dir-hash>` / `audiobook:*` / `book:*` 去重 |
| `meta` | 设备 id / 名称、资料库版本与内容签名 |

写入策略：

- 扫描先收集文件、读取标签（**不持有数据库锁**），随后在一个事务里批量 upsert；
- 用 `scan_token` 标记本轮见到的行，扫描结束删除本轮未见的行；
- 未变化文件（`size` 与 `mtime` 相同）跳过标签读取，只刷新 `scan_token`；
- `/library/all` 使用带索引的排序查询，一次构建完整快照。

## 与协议的对应关系

| 协议接口 | 实现 |
| --- | --- |
| `GET /library/version` | `meta.library_version`，内容签名变化时才更新 |
| `GET /library/all` | 由 SQLite 生成，`musicPathOrder` 在有兼容模式目录时为 `true` |
| `GET /media/music?id=` | 曲目 id（数字字符串） |
| `GET /media/audiobook?path=` | 已登记的章节绝对路径 |
| `GET /media/ebook?path=` | 已登记的书籍绝对路径 |
| `GET /media/songart?id=` | `tracks.art_key` → `art` 表，无封面返回内置占位图 |
| `GET /media/cover?kind=&key=` | 有声书 id / 书籍路径 |

- HTTP 端口优先 `8080..8099`，全部占用时使用系统临时端口；
- 所有媒体接口支持 `Range`，非法范围返回 `416` 与 `Content-Range: bytes */<total>`；
- mDNS 服务类型 `_aurora-cast._tcp.local.`，UDP `47823` 处理 `probe` 并周期广播 `offer`；
- 只返回数据库中已登记的 id / path，不按客户端传入路径直接读文件。

## 扫描规则细节

### 音乐

- 支持的音频扩展名见 `util.rs`，视频容器同样会被收录（`from_video = 1`）。
- ID3 模式：`artist` 缺失时回退 `album artist`，仍为空则显示为“未知艺人”；
  `album` 缺失时回退所在文件夹名。
- 兼容模式：`相对路径/艺人/专辑/文件`，曲号缺失时从文件名前缀数字推断。
- 专辑封面优先使用目录内的 `cover.*` / `folder.*` / `front.*`，否则取该目录第一个
  内嵌封面，整个目录只存一份。
- `path_order`（专辑内按路径排序）是协议中的全局开关，因此只要存在一个兼容模式音乐目录，
  整个资料库都会按路径排序。

### 有声书

- 根目录下每个子文件夹是一本书；直接放在根目录的音频归为“以目录名命名”的一本。
- 章节顺序：曲号（优先，缺失沉底），其次按路径自然序。
- 书本封面优先目录封面，其次第一章的内嵌封面。

### 书籍

- EPUB 从 `META-INF/container.xml` → OPF 读取 `dc:title` / `dc:creator` 与封面图；
- 其他格式使用文件名作为书名，不提取封面。

## 已知限制

- 视频音轨提取依赖 ffmpeg；首次播放某个视频文件时会有一次转码延迟，之后走缓存。
- 兼容模式与 ID3 模式可以同时存在，但排序开关是全局的（协议限制）。
- 扫描是手动的；不会监听文件系统变化。重新扫描是增量的。
- 不做鉴权，安全性依赖局域网本身（与协议一致）。
