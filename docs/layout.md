# 横屏与竖屏设计

## 1. 一个判断，两种布局

整个 app 只有一个方向判断，写在根组合里：

```kotlin
BoxWithConstraints(Modifier.fillMaxSize()) {
    val landscape = maxWidth > maxHeight
    ...
}
```

没有 `WindowSizeClass`、没有 `NavigationRail`、没有 `ModalNavigationDrawer`。
适配策略是**手机优先的单一断点**：宽 > 高即横屏，其余情况一律按竖屏处理。

选择这种做法的理由：

- 目标设备是手机，平板/折叠屏不是主要场景；
- 只有一处分支，行为可预测，不需要维护多套 breakpoint；
- 真正的"多栏"只在横屏播放器出现，其余界面保持单列，避免"为了填满宽度而拉宽"。

代价：平板上列表会变得很宽，没有列表-详情双栏；这是明确接受的结果。

### 旋转不重建 Activity

`MainActivity` 声明了 `configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize"`，
所以旋转只是重组，不会走 `onCreate`。这带来两个好处：

- ViewModel、导航栈、播放器状态天然存活；
- 旋转没有转场动画，是即时重排（只有播放器翻转、阅读器淡入这类显式动画）。

## 2. 竖屏：整页与翻转

竖屏是"iPod 式"的单页结构：

```
┌──────────────────────┐
│ 状态栏               │
│ 页面内容（列表/详情） │
│                      │
│ 迷你播放条（38dp 封面）│
│ 5 格 TabBar          │
└──────────────────────┘
```

- 正在播放是**整页翻转**：`rotationY` 从 0 到 180，600ms，`cameraDistance = 8×density`。
- 翻转过程中只在中途显示黑底（"转到侧面露黑场"），结束后**卸载**播放器页，
  避免两棵树常驻。
- 返回键处理是**条件组合** `BackHandler`，而不是 `enabled=`：横竖屏切换时
  `ContentFace` 会换父容器，持久的手势处理器会抢不过 `NavHost`。
- 播放器几何用一组等式保证不溢出：

```
topBase + 灰条(100dp) + 封面 + bottomBase = 可用高度
封面 = min(屏宽, 剩余高度)
剩余空间 40% 给上方、60% 给下方
```

## 3. 横屏：左内容 + 右播放侧栏

```
┌───────────────────────────────┬──────────────┐
│ 合并顶栏（46dp，标题/返回/搜索）│              │
├───────────────────────────────┤   播放侧栏    │
│                               │  封面正方形   │
│        页面内容                │  进度条浮层   │
│      （宽度自适应）             │              │
│                               │              │
├───────────────────────────────┴──────────────┤
│ 5 格 TabBar（左，weight 1） │ 传输控制（右）   │
└──────────────────────────────────────────────┘
```

### 尺寸推导

```
topBarH    = 状态栏 + 46dp
bottomBarH = 60dp + 导航栏
middleH    = 屏高 - topBarH - bottomBarH
panelWidth = min(middleH, 屏宽)      // 侧栏宽度 = 中部可用高度
```

`panelWidth` 直接用高度，是为了让封面**永远是正方形且铺满**：进度条是透明浮层，
不占高度；极端窄屏时再用屏宽兜底。

底部条左边是 TabBar，右边是传输控制，宽度与侧栏对齐。

### 顶栏合并：页面把标题"上报"给 App

横屏不显示每页自己的导航栏，而是由 App 渲染一条统一顶栏。实现方式是一个
CompositionLocal：

```
TopBarHost { spec: TopBarSpec(title, onBack, onSearch, action, ...) }
```

- 每页的 `Ios5NavBar` 在横屏时**不上栏**，只把 spec 写进 host，然后返回。
- `Ios5App` 在横屏内容外层 provide 这个 host，并统一渲染 46dp 顶栏。
- 竖屏不 provide，页面维持自己的导航栏。

这个"页面拥有导航语义、App 拥有导航外观"的拆分，避免了几十个页面各自写两套栏。

### 播放器侧栏

- 侧栏本身不出现"播放器页"，它就是播放器的横屏形态。
- 封面 `min(宽, 高)`，进度条叠在封面上（62% 黑底），极窄时退化成普通进度条。
- 标题/艺人移到合并顶栏，用 10sp/14sp/11sp 两级。
- 传输控制有三种：音乐（上一首/播放/下一首）、有声书（回退/播放/章节/快进）、
  投屏（能力位决定按钮）。
- 气泡（睡眠定时、章节、增益）必须**盖住整屏**而不是只盖右栏，所以挂在根节点，
  否则会被侧栏裁剪。

### 横屏下的阅读器

电子书**没有双页跨页**，也不做横屏专属边距/分栏。阅读器只根据宽高重新分页，
然后用块级锚点回到原段落。旋转 = 重新分页，不丢位置，但大章节会卡一下。

阅读器是覆盖层：横屏下若同时有投屏接收页，阅读器仍显示在侧栏旁边，不会被隐藏
（竖屏才会隐藏给投屏页让位）。

## 4. 其余界面

| 界面 | 竖屏 | 横屏 |
|---|---|---|
| 首页/资料库 | 单列分组卡片 + 横向货架 | 同样的单列，只是更宽 |
| 详情页 | 头部封面 + 分组列表 | 路由 push 不变，没有双栏 |
| 设置 | 单列分组列表 | 只合并导航栏，内容不封顶宽 |
| 队列 | 全屏上滑 | 同样全屏，盖住侧栏 |
| 对话框 | 居中浮层 | 居中浮层 |
| 底部面板 | `ModalBottomSheet` | 仍是底部面板，不变侧栏 |

设置页没有横屏专属布局：`Ios5SettingsPage` 只是"导航栏 + 可滚动列表"，没有
宽度上限，也没有两列分组。这在平板横屏会显得很宽，但保持了实现简单。

`libraryColumns`（2/3/4）是一个**无效偏好**：UI 能改，代码没有任何消费者。
横向货架的封面固定 120dp，也不随屏幕变化。

## 5. 状态保存

旋转不重建 Activity，但**换父容器会重建子树**：竖屏的 `ContentFace` 在根 Box 里，
横屏的 `ContentFace` 在 Row 里。因此普通 `remember` 会丢，只有三层状态能活下来：

1. **ViewModel + NavController**：Activity 存活即存活。
2. **`rememberSaveable`**：每个返回栈条目有自己的 `SaveableStateHolder`，
   旋转后恢复。
3. **持久化状态**（`ui_state.json` / 各业务库）：冷启动也能恢复。

`rememberPersistentLazyListState(key)` 把这三层串起来：

```
rememberSaveable(seeded from UiStateStore.scrollOf(key))
  + 250ms 防抖写回 UiStateStore
```

滚动 key 是命名空间字符串：`tab:home`、`browse:songs`、`detail:<kind>|<id>`、
`ebook_search`、`remote_detail:<title>`。

播放器页的 `expanded` 状态在横屏下虽然不用，但**保留**，所以转回竖屏时还是那个页面。

## 6. 状态栏与系统栏

- 横屏默认隐藏状态栏（可关），用 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，
  离开横屏时在 `DisposableEffect.onDispose` 里恢复。
- 全面 edge-to-edge：状态栏/导航栏透明，禁用导航栏对比度强制。
- 输入法用 `adjustResize`，搜索框/编辑框不会被键盘挡住。

## 7. 取舍与已知问题

1. **单一断点**：实现简单，但平板/折叠屏没有真正的多栏体验。
2. **横屏侧栏宽度 = 高度**：保证正方形封面，代价是窄屏时侧栏可能偏宽。
3. **没有列表-详情双栏**：横屏看列表点进详情仍是整页替换，浪费了宽度。
4. **阅读器无双页**：横屏仍是一页，只是更宽；长行阅读舒适度一般。
5. **设置页不封顶宽**：平板上行会拉得很长。
6. **旋转丢普通 `remember`**：源码注释里"remember 状态旋转前后一致"是乐观说法，
   真正可靠的只有 saveable / ViewModel / 持久化。
7. **无效偏好**：`libraryColumns`、`playerShowUtilities`、部分手势偏好只被读取或
   只被写入，没有实际消费者；文档不应把它们当作已实现能力。
8. **投屏 Overlay 不区分方向**：始终全屏覆盖。
9. **没有旋转动画**：只做即时重排，避免复杂的方向转场。

## 关键文件

`ui/ios5/Ios5App.kt`（唯一方向判断）、`ui/ios5/Ios5Chrome.kt`（`TopBarHost`、TabBar）、
`ui/player/Ios5Player.kt`（竖屏整页 + 横屏侧栏）、`ui/player/Ios5AudiobookPlayer.kt`、
`ui/ebook/EbookReaderScreen.kt`、`ui/StateSaving.kt`、`data/UiStateStore.kt`、
`AndroidManifest.xml`（`configChanges`）。
