# nostalgiaPod 设计文档

这组文档讲的是"为什么这样做"，不是 API 手册。每篇都围绕一个子系统，说明它的
职责边界、数据模型、关键数据流、做出的取舍，以及目前已知的缺口。代码里能一眼看到的
实现细节（具体函数签名、Compose 组合）尽量不重复，只在解释设计时引用文件名。

| 文档 | 内容 |
|---|---|
| [library.md](library.md) | 媒体库的组织与管理：双本地栈、扫描、身份、去重、智能歌单、状态持久化 |
| [sharing-and-relay.md](sharing-and-relay.md) | 局域网共享 + PodRelay 中继：发现、HTTP 服务、TLS/加密分帧、缓存与省流量 |
| [ebook.md](ebook.md) | 电子书：解析、分页、进度存储、随机阅读、搭配中继的流量策略 |
| [cooperative-playback.md](cooperative-playback.md) | 电子书朗读 / 有声书 / 音乐 / 投屏的协同播放与状态保存 |
| [dsp.md](dsp.md) | 音乐效果器链：处理顺序、EQ/动态/卷积、系数计算、设备绑定、USB 直通 |
| [casting.md](casting.md) | 两种投屏：接收端（AirPlay / Pod）与远程库播放，UI 接口与网络优化 |
| [layout.md](layout.md) | 横屏与竖屏：布局分支、播放器侧栏、电子书、旋转后的状态恢复 |

## 三个贯穿全局的设计基调

1. **本地优先，云端只是可选通道。** 音乐、电子书、有声书各自有独立的索引库；
   网络（局域网或中继）只负责"把另一台设备的库当成只读文件系统"。
2. **可视状态与播放状态分离。** UI 的导航栈、滚动位置、阅读器路径存在
   `ui_state.json`；播放队列、进度、音效参数各存各的。进程被杀后按层恢复，
   而不是依赖 Activity 的 `savedInstanceState`。
3. **物理隔离优于强行抽象。** 媒体库的 MediaStore 栈与文件夹栈各有独立数据库和
   ID 命名空间；四种 Room 库互不共享 schema。代价是跨源数据不通用，收益是
   一次 schema 变更不会波及其他子系统。

## 阅读顺序建议

- 只关心播放体验：先看 `cooperative-playback.md`，再看 `dsp.md`。
- 只关心网络/投屏：先看 `sharing-and-relay.md` 的拓扑图，再看 `casting.md`。
- 改 UI：先看 `layout.md`，它解释了为什么整个 app 只有一个方向判断。
