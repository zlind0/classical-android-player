# Aurora 局域网资料库共享协议

本文描述 Aurora 在局域网内共享音乐库、电子书库、有声书库的实现约定，供后续开发其他平台的共享端 / 接收端使用。

- 协议版本：`v1`
- 传输方式：局域网明文 HTTP，只读
- 发现方式：mDNS/NSD + UDP 广播
- 数据编码：JSON（UTF-8）
- 二进制媒体：HTTP GET + 标准 `Range`
- 服务端不要求登录；同一局域网内可访问到端口即可读取。安全边界依赖局域网本身。

> 重要：协议只保证“已登记资料库条目”的只读访问。服务端必须在处理任何 `path` / `id` 参数时先查库，不允许直接按客户端传入的任意文件路径读文件。

---

## 1. 角色

- **提供端（Server / Host）**：手机 A，暴露资料库元信息与媒体文件。
- **使用端（Client / Peer）**：手机 B，发现 A、同步元信息、缓存封面和媒体、展示远程资料库。
- 同一台设备同时具备两种角色。

---

## 2. 发现协议

### 2.1 mDNS / NSD

- 服务类型：`_aurora-cast._tcp.`
- 服务名：`Aurora-<设备 id 前 6 位>`
- 端口：实际 HTTP 服务端口
- TXT 记录：

| Key | 说明 |
| --- | --- |
| `v` | 协议版本，当前固定 `1` |
| `id` | 设备唯一 id，首次启动随机 UUID，持久保存 |
| `name` | 设备显示名，默认 `<机型> · <id 前 4 位大写>` |
| `state` | `idle` / `receiving`，投屏接收状态；资料库同步不依赖 |

NSD 只在 IPv4 上工作。IPv6 literal 不参与发现。

### 2.2 UDP 广播

- UDP 端口：`47823`
- 广播地址：`255.255.255.255` + 每个可用网卡的广播地址
- 消息格式：JSON，UTF-8

#### probe

使用端探测：

```json
{
  "t": "probe",
  "v": 1,
  "id": "本机设备 id",
  "name": "本机名称",
  "port": 本机 HTTP 端口
}
```

#### offer

提供端回应 probe，也用于主动广播“我在线”：

```json
{
  "t": "offer",
  "v": 1,
  "id": "提供端设备 id",
  "name": "提供端名称",
  "port": 8787,
  "state": "idle"
}
```

规则：

- `v != 1`、`id` 为空、`id == 本机 id` 的消息忽略。
- 收到 `probe`：向来源地址和来源端口回 `offer`。
- 收到 `offer`：`port > 0` 时按 `id` 去重加入设备列表。
- 提供端会周期性主动广播 `offer`，不能只依赖 mDNS 重新回调。
- 使用端会周期性发送 `probe`，并在周期内主动广播自己的 `offer`。

---

## 3. HTTP 服务

### 3.1 地址

客户端使用发现到的 `http://<host>:<port>` 作为 base URL。

提供端当前端口选择：

1. 依次尝试 `8080..8099`；
2. 全部占用时使用系统分配的临时端口。

> 其他平台建议尽可能保持端口稳定。客户端对“已缓存设备”会用上次 host/port 主动探活；如果端口变化且发现协议又失效，会找不到该设备。

### 3.2 通用响应头

所有响应包含：

```http
Content-Type: <mime>
Content-Length: <length>
Cache-Control: no-store
Accept-Ranges: bytes
Connection: close
```

- HTTP/1.1
- 不支持 keep-alive
- JSON 使用 `Content-Type: application/json; charset=utf-8`
- 文本错误使用 `text/plain; charset=utf-8`
- 服务器不保证支持 `HEAD`；客户端应使用 `GET`。

### 3.3 请求头

设备客户端请求时带：

```http
X-Aurora-Device: 1
```

该头用于投屏会话心跳；资料库接口本身不要求它，但建议其他平台对设备请求也带上，便于服务端统计/兼容。

---

## 4. 资料库接口

### 4.1 `GET /library/version`

轻量版本探测，不返回元信息。

响应：

```json
{
  "version": 1730000000000
}
```

字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `version` | long | 资料库内容版本，毫秒时间戳。只有内容变化时才更新。 |

约定：

- `version > 0`
- 同一份资料库在多次请求、App 重启后必须返回相同值。
- 内容变化时必须更新。
- 客户端只在 `cached.libraryUpdatedAt == version` 时跳过全量同步。
- 不得用“当前时间”作为 version；否则每次启动都会触发全量传输。
- 老版本设备可能没有此接口，返回 404 即可；客户端会回退到全量同步。

当前实现的内容签名包含：

- 音乐栈排序模式：FILE 路径序 / MEDIA 标签序；
- 曲目：`id`、`title`、`artist`、`album`、`path`、`durationSec`、`trackNumber`、`mergeTitles`；
- 电子书根：`key`、`rootPath`、`displayName`；
- 电子书：`path`、`title`、`author`、`size`；
- 有声书：`id`、`title`、章节数量、章节 `path`、`title`、`size`。

签名使用持久化 FNV 风格哈希，结果存本地；其他平台只要保证“内容变化才变、不变则稳定”即可，不要求与 Aurora 使用完全相同的哈希算法。

> 注意：当前实现不把封面内容、`discNumber`、`artistId`、`albumId` 等纳入签名。新平台如果希望封面/碟号变化也触发同步，应把这些字段也加入自己的签名。

---

### 4.2 `GET /library/all`

返回该设备完整资料库快照。这个响应可能很大（数万曲目），客户端应设置较长读超时。

响应示例（字段省略）：

```json
{
  "deviceId": "0f3c...",
  "deviceName": "22041216G · 0F3C",
  "generatedAt": 1730000123456,
  "libraryUpdatedAt": 1730000000000,
  "musicPathOrder": false,
  "music": [
    {
      "id": "12345",
      "title": "Symphony No. 5 - I. Allegro",
      "artist": "Beethoven",
      "album": "Symphony No. 5",
      "albumId": "88",
      "artistId": "12",
      "durationSec": 420,
      "trackNumber": 1,
      "discNumber": 1,
      "hasArt": true,
      "mergeTitles": true,
      "path": "/storage/emulated/0/Music/Beethoven/05-01.flac"
    }
  ],
  "ebookRoots": [
    {
      "key": "shelf",
      "rootPath": "",
      "displayName": "默认书架",
      "shelf": true,
      "bookCount": 3
    },
    {
      "key": "1730000000123",
      "rootPath": "/storage/emulated/0/Books",
      "displayName": "我的书库",
      "shelf": false,
      "bookCount": 42
    }
  ],
  "ebooks": [
    {
      "path": "/storage/emulated/0/Books/三体.epub",
      "title": "三体",
      "author": "刘慈欣",
      "size": 1234567,
      "hasCover": true,
      "rootKey": "1730000000123"
    }
  ],
  "audiobooks": [
    {
      "id": "/storage/emulated/0/Audiobooks/三体",
      "title": "三体",
      "hasCover": true,
      "chapters": [
        {
          "path": "/storage/emulated/0/Audiobooks/三体/01.mp3",
          "title": "第 1 章",
          "trackNumber": 1,
          "durationSec": 1800,
          "size": 28800000
        }
      ]
    }
  ]
}
```

#### 顶层字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `deviceId` | string | 设备唯一 id |
| `deviceName` | string | 设备显示名 |
| `generatedAt` | long | 本次响应生成时间（毫秒） |
| `libraryUpdatedAt` | long | 内容版本，必须与 `/library/version` 的 `version` 一致 |
| `musicPathOrder` | boolean | `true` = 音乐来自 FILE 栈，专辑内按路径排序；`false` = 按标签碟号/曲号排序 |
| `music` | array | 曲目列表 |
| `ebookRoots` | array | 电子书顶层目录 |
| `ebooks` | array | 电子书条目 |
| `audiobooks` | array | 有声书条目 |

未知字段必须忽略；缺失字段按类型默认值处理。

#### 4.2.1 `music[]`

| 字段 | 类型 | 必需 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 服务端稳定曲目 id；后续 `/media/music?id=` 原样使用 |
| `title` | string | 是 | 曲名 |
| `artist` | string | 否 | 艺人 |
| `album` | string | 否 | 专辑名 |
| `albumId` | string | 否 | 专辑 id；为空时客户端用 `artist|album` 聚合 |
| `artistId` | string | 否 | 艺人 id |
| `durationSec` | int | 否 | 时长，秒 |
| `trackNumber` | int | 否 | 曲号；`0` 表示未知 |
| `discNumber` | int | 否 | 碟号；`0` 表示未知 |
| `hasArt` | boolean | 否 | 是否有封面可请求 |
| `mergeTitles` | boolean | 否 | 该曲目所在库是否允许“曲名合成” |
| `path` | string | 否 | 仅用于排序；客户端不得把它当成本地可读路径 |

客户端聚合规则：

- 专辑键：`albumId` 非空用 `albumId`，否则用 `artist + "|" + album`。
- 艺人键：`artist`，空值归入“未知艺人”。
- 艺人按名称小写升序。
- 专辑按标题小写升序。

排序规则（必须与 Aurora 本地完全一致）：

```text
musicPathOrder == true（FILE 栈）:
    按 path 小写升序

musicPathOrder == false（MEDIA 栈）:
    1. discNumber（0 视为 Int.MAX）
    2. trackNumber（0 视为 Int.MAX）
    3. title 小写升序
    4. path 文件名小写升序
```

这是共享函数 `albumTrackComparator(pathFirst)` 的行为，本地库、远程资料库和扫描预计算都使用同一份。

#### 4.2.2 `ebookRoots[]`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `key` | string | 根标识；默认书架固定为 `"shelf"`，扫描根使用 root id 字符串 |
| `rootPath` | string | 扫描根绝对路径；默认书架为空 |
| `displayName` | string | 显示名 |
| `shelf` | boolean | 是否默认书架 |
| `bookCount` | int | 该根下书数量 |
| `fastScan` | boolean | 快速扫描书库：首轮只用文件名建索引，书籍元信息按需解析；启用后不可撤销 |

`ebooks[].rootKey` 必须与该数组中的 `key` 对应。

老版本如果没有 `ebookRoots`，客户端会把整个电子书列表当成一个平铺根。

#### 4.2.3 `ebooks[]`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `path` | string | 服务端稳定路径，同时作为 `/media/ebook?path=` 的 key |
| `title` | string | 书名 |
| `author` | string | 作者 |
| `size` | long | 字节数 |
| `hasCover` | boolean | 是否有封面 |
| `metaScanned` | boolean | 是否已解析过书名/作者/封面；`false` 表示仍是快速扫描占位，可调用 `/library/ebook/scan` |
| `rootKey` | string | 所属根；对应 `ebookRoots[].key` |

目录层级由 `path` 在 `rootPath` 下的相对路径推导。客户端不应自行扫描服务端文件系统。

#### 4.2.3.1 `GET /library/ebook/scan`

快速扫描书库的单本按需解析接口。服务端解析后写回数据库，后续直接返回缓存。该接口不会改变 `libraryUpdatedAt`，避免懒解析触发客户端全量同步。

请求：

```http
GET /library/ebook/scan?path=<urlencoded-path>
```

响应：

```json
{
  "path": "/books/example.epub",
  "title": "Example",
  "author": "Someone",
  "hasCover": true
}
```

#### 4.2.3.2 `POST /library/ebook/scan`

批量按需解析接口。客户端每次随机展示一批书时只发一次请求，服务端批量解析并一次写库。

请求：

```http
POST /library/ebook/scan
Content-Type: application/json

{"paths":["/books/a.epub","/books/b.epub"]}
```

响应：

```json
{
  "books": [
    {"path": "/books/a.epub", "title": "A", "author": "Someone", "hasCover": true},
    {"path": "/books/b.epub", "title": "B", "author": "", "hasCover": false}
  ]
}
```

约束：

- 只接受已在资料库中登记过的电子书路径。
- 单次最多处理 200 个路径。
- 老服务端没有批量接口时返回 404/405，客户端应继续使用 `ebooks[]` 中的文件名占位信息。
- 懒解析不会改变资料库版本；客户端直接使用本响应更新本地缓存。
- 服务端可能在每次解析后改变 `libraryUpdatedAt`，客户端应允许渐进刷新。

#### 4.2.4 `audiobooks[]`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | 书 id，当前实现是书的目录绝对路径 |
| `title` | string | 书名 |
| `hasCover` | boolean | 是否有封面 |
| `chapters` | array | 章节，顺序即播放顺序 |

`chapters[]`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `path` | string | 章节文件路径，同时作为 `/media/audiobook?path=` 的 key |
| `title` | string | 章节标题 |
| `trackNumber` | int | 章节序号；`0` 表示未知 |
| `durationSec` | int | 时长，秒 |
| `size` | long | 字节数 |

服务端已经按“trackNumber 优先、缺失沉底，其次 path 自然序”排好；客户端按返回顺序播放即可。

---

## 5. 媒体接口

所有媒体接口都是只读 GET。参数值必须 URL 编码（UTF-8）。

### 5.1 `GET /media/music?id=<track id>`

返回音乐文件字节流。

- `id`：`music[].id` 原样 URL 编码。
- 支持 `Range: bytes=...`。
- 支持 `200` 和 `206`。
- Range 非法返回 `416`，并带 `Content-Range: bytes */<total>`。
- `Content-Type` 按文件扩展名推断：

| 扩展名 | MIME |
| --- | --- |
| mp3 | `audio/mpeg` |
| m4a / mp4 / alac | `audio/mp4` |
| aac | `audio/aac` |
| flac | `audio/flac` |
| ogg / oga / opus | `audio/ogg` |
| wav | `audio/wav` |
| aif / aiff | `audio/aiff` |
| wma | `audio/x-ms-wma` |

Range 语法：

```http
Range: bytes=start-end
Range: bytes=start-
Range: bytes=-suffixLength
```

成功 Range 响应：

```http
HTTP/1.1 206 Partial Content
Content-Range: bytes <start>-<end>/<total>
Content-Length: <end-start+1>
Accept-Ranges: bytes
```

### 5.2 `GET /media/audiobook?path=<chapter path>`

返回有声书章节文件，语义与 `/media/music` 相同，同样支持 Range。

`path` 必须是 `audiobooks[].chapters[].path` 中已登记的值。

### 5.3 `GET /media/ebook?path=<book path>`

返回电子书原文件字节流。

- `path` 必须是 `ebooks[].path` 中已登记的值。
- 主要用于客户端整本下载。
- 服务端实现同样支持 Range，但电子书客户端当前按整文件下载。
- MIME：

| 扩展名 | MIME |
| --- | --- |
| epub | `application/epub+zip` |
| mobi | `application/x-mobipocket-ebook` |
| azw / azw3 | `application/vnd.amazon.ebook` |
| 其他 | `application/octet-stream` |

### 5.4 `GET /media/songart?id=<track id>`

返回曲目封面图片。

- 优先返回该文件内嵌封面；
- 其次专辑图 / 目录封面；
- 没有封面时返回服务端默认封面（`image/png`）；
- 服务端不保证 `Content-Length` 之外的分块行为，客户端按普通图片 GET 处理。

### 5.5 `GET /media/cover?kind=<ebook|audiobook>&key=<key>`

返回电子书 / 有声书封面。

- `kind=ebook`：`key` 使用 `ebooks[].path`
- `kind=audiobook`：`key` 使用 `audiobooks[].id`
- 没有封面时返回默认封面。

### 5.6 错误响应

| 状态 | 场景 |
| --- | --- |
| `400` | 参数缺失/格式错误（当前实现多数情况直接 404） |
| `404` | id/path 不在资料库中，或文件已不存在 |
| `405` | 非 GET/HEAD 请求（资料库接口只读） |
| `416` | Range 越界 |
| `200` / `206` | 成功 |

---

## 6. 客户端同步流程

推荐流程：

```text
1. 发现设备（mDNS + UDP），得到 id / host / port。
2. GET /library/version
3. 若本地有该设备缓存，且 version == cached.libraryUpdatedAt：
     只更新“最后连接时间”，标记在线，不获取 /library/all。
4. 否则 GET /library/all，完整替换缓存。
5. 后台预取封面（艺人/专辑/电子书/有声书）。
6. 每 15 秒重复 2-5；首次启动可 1.5 秒后先跑一轮。
```

### 6.1 失败与离线

- `/library/version` 网络失败：设备标记离线。
- `/library/version` 返回 404：按老服务端处理，直接走 `/library/all`。
- `/library/all` 失败：设备标记离线，保留旧缓存。
- 设备离线后：
  - UI **不展示**该设备的远程资料库、远程最近阅读/最近听书；
  - 已进入播放队列的歌曲/章节继续播放，不因离线主动停止；
  - 已缓存的音频仍可播放；
  - 未缓存的有声书章节在章节列表显示灰色且不可点。

### 6.2 缓存重连

即使发现协议暂时没有重新报告某台设备，客户端也会使用缓存中的 `host/port` 主动请求 `/library/version` 探活。提供端重新启动后，只要端口不变即可自动恢复。

因此其他平台实现时建议：

- 尽量固定端口；
- App 重启后重新注册 mDNS 并主动广播 UDP `offer`；
- 不要依赖对端一定重新触发 `onServiceFound`。

### 6.3 30 天清理

- 每次成功版本探测或全量同步都更新 `lastSeenAt`。
- 超过 **30 天**没有成功连接：
  - 删除该设备的资料库元信息缓存；
  - 删除该设备的封面缓存目录。
- 当前实现不会因此删除已下载的远程音频缓存或已下载的电子书阅读缓存；那些属于独立媒体缓存。

### 6.4 资料库缓存

客户端建议落盘：

- 每台设备一份 `/library/all` JSON 快照；
- 内容版本 `libraryUpdatedAt`；
- host / port / deviceName；
- `lastSeenAt`。

在线状态 `online` 不写入长期缓存；启动时按“未连接”处理。

---

## 7. 封面缓存

客户端缓存以下封面：

- 艺人图：当前实现取该艺人第一张可用专辑封面；
- 专辑图：`/media/songart?id=`；
- 电子书封面：`/media/cover?kind=ebook&key=`;
- 有声书封面：`/media/cover?kind=audiobook&key=`。

缓存目录（Aurora 实现）：

```text
filesDir/remote_library/art/<sha1(deviceId)>/<sha1(url)>.img
```

规则：

- 已缓存时 UI 直接使用 `file://` 本地文件；
- 未缓存时先用原始 http URL 显示，同时后台下载；
- 资料库版本变化时清空该设备封面目录并重新预取；
- 设备 30 天不连接时随元信息一起删除。

---

## 8. 客户端标识与播放映射

### 8.1 音乐曲目 id

客户端把远程曲目映射成自己的播放用 id：

```text
remote:<deviceId>:<base64url(track.id)>
```

其中 `base64url` 使用 UTF-8 字节、URL-safe alphabet、无 padding。

这只是客户端内部 id，不参与服务端协议。播放时仍使用：

```text
<baseUrl>/media/music?id=<URL 编码后的 track.id>
```

### 8.2 有声书章节 id

```text
audiobook:remote:<deviceId>:<base64url(book.id)>#<chapterIndex>
```

服务端只需要认 `book.id` 和 `chapters[].path`。

### 8.3 音频 URL

- 音乐：`<baseUrl>/media/music?id=<encoded track.id>`
- 有声书：`<baseUrl>/media/audiobook?path=<encoded chapter.path>`
- 电子书下载：`<baseUrl>/media/ebook?path=<encoded book.path>`
- 曲目封面：`<baseUrl>/media/songart?id=<encoded track.id>`
- 电子书封面：`<baseUrl>/media/cover?kind=ebook&key=<encoded path>`
- 有声书封面：`<baseUrl>/media/cover?kind=audiobook&key=<encoded book.id>`

---

## 9. 曲名合成

服务端通过 `music[].mergeTitles` 告诉客户端该曲目所在库是否开启曲名合成。

- `mergeTitles=false`：客户端逐曲显示，不合并。
- `mergeTitles=true`：客户端对该专辑使用与本地相同的 `lib-titlemerge` 规则把连续曲目合并成：
  - 大标题（major）
  - 若干小标题（minor）
- 合并只对“专辑详情”生效；艺人页全部曲目、歌曲列表仍逐曲显示。
- 合并前必须先按第 4.2.1 节的专辑排序规则排序，否则合并结果与本地不一致。
- 正在播放页也显示合并后的“大标题 + 小标题”。

其他平台如果不能实现完全相同的分词/合并算法，应至少保证：

1. 顺序与 Aurora 相同；
2. `mergeTitles=false` 时不合并；
3. 不因合并改变实际播放的曲目 id 和顺序。

---

## 10. 投屏与资料库的关系

投屏协议和资料库协议并行存在：

- 资料库接口供对端浏览、缓存、播放。
- 当本机播放的是远程资料库音乐时，如果又把音乐投屏到第三台设备：
  - 本机作为发送端，会把远程音频流代理到 `/audio`；
  - 必须转发 `Range` / `Content-Range`；
  - 本机同时静音本地输出。
- 资料库共享服务本身不负责投屏控制；`/cast/*`、`/audio`、`/state`、`/queue` 属于投屏协议。

其他平台如果只实现资料库共享，不需要实现 `/cast/*`。

---

## 11. 兼容性与版本演进

- 当前协议版本：`1`。
- 新增字段应保持向后兼容：
  - 老客户端忽略未知字段；
  - 新客户端对缺失字段使用默认值。
- 不得改变已有字段语义。
- 新增必需接口时，应提供 404 回退路径（参考 `/library/version`）。
- 服务端可以在同一 HTTP 端口同时提供投屏协议和资料库协议；客户端只应请求自己需要的路径。

### 11.1 最小实现清单

一个只提供资料库共享的服务端至少需要：

1. mDNS 注册 `_aurora-cast._tcp.`（可选但推荐）；
2. UDP 47823 处理 `probe` / 发送 `offer`（可选但推荐）；
3. `GET /library/version`（推荐）；
4. `GET /library/all`；
5. `GET/POST /library/ebook/scan`（快速扫描书库可选，用于按需解析）；
6. `GET /media/music`；
7. `GET /media/audiobook`；
8. `GET /media/ebook`；
9. `GET /media/songart`；
10. `GET /media/cover`。

没有 mDNS/UDP 时，也可以让用户手动输入 `host:port`，只要后续 HTTP 协议一致即可。

---

## 12. 诊断建议

- 两台设备必须处于同一局域网，且 AP 未开启客户端隔离。
- 端口被占用时服务端可能换端口，必须依赖发现协议上报的新端口。
- `/library/all` 很大时客户端读超时应至少 20 秒。
- 如果对端重启后单边看不到：
  - 检查 UDP `offer` 是否主动广播；
  - 检查客户端是否用缓存 host/port 主动探活；
  - 检查端口是否变化。
- 如果远程歌曲投屏后本机仍出声：
  - 确认服务端把远程曲目识别为 `hasAudio=true`；
  - 确认 `/audio` 能把远程流代理给接收端，而不是返回 404。

---

## 13. 示例请求

```http
GET /library/version?t=1730000123456 HTTP/1.1
Host: 192.168.1.23:8080
X-Aurora-Device: 1
```

```http
GET /library/all?t=1730000123456 HTTP/1.1
Host: 192.168.1.23:8080
X-Aurora-Device: 1
```

```http
GET /media/music?id=12345 HTTP/1.1
Host: 192.168.1.23:8080
Range: bytes=1048576-
```

```http
HTTP/1.1 206 Partial Content
Content-Type: audio/flac
Content-Length: 8388608
Content-Range: bytes 1048576-9437183/10485760
Accept-Ranges: bytes
Connection: close
```

```http
GET /media/cover?kind=ebook&key=%2Fstorage%2Femulated%2F0%2FBooks%2F%E4%B8%89%E4%BD%93.epub HTTP/1.1
Host: 192.168.1.23:8080
```
