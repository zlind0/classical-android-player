package com.aurora.music.ui.ebook

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.music.AuroraApplication
import com.aurora.music.data.ebook.EbookAnchorCode
import com.aurora.music.data.ebook.EbookCastDisplayPrefs
import com.aurora.music.data.ebook.EbookCastMode
import com.aurora.music.data.ebook.EbookCastState
import com.aurora.music.data.ebook.EbookCastTurnUnit
import com.aurora.music.data.ebook.EbookStore
import com.aurora.music.data.ebook.EbookTheme
import com.aurora.music.data.ebook.MAX_CAST_COLUMN_WIDTH_PX
import com.aurora.music.data.ebook.MAX_CAST_FONT_PX
import com.aurora.music.data.ebook.MIN_CAST_COLUMN_WIDTH_PX
import com.aurora.music.data.ebook.MIN_CAST_FONT_PX
import com.aurora.music.ui.ios5.Ios5ActionRow
import com.aurora.music.ui.ios5.Ios5ButtonTone
import com.aurora.music.ui.ios5.Ios5Cell
import com.aurora.music.ui.ios5.Ios5CellDivider
import com.aurora.music.ui.ios5.Ios5CheckRow
import com.aurora.music.ui.ios5.Ios5Colors
import com.aurora.music.ui.ios5.Ios5Dimens
import com.aurora.music.ui.ios5.Ios5Group
import com.aurora.music.ui.ios5.Ios5NavBar
import com.aurora.music.ui.ios5.Ios5NavRow
import com.aurora.music.ui.ios5.Ios5SectionTitle
import com.aurora.music.ui.ios5.Ios5SegmentRow
import com.aurora.music.ui.ios5.Ios5SliderRow
import com.aurora.music.ui.ios5.Ios5SmallButton
import com.aurora.music.ui.ios5.Ios5StaticText
import com.aurora.music.viewmodel.PlayerUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.awaitCancellation

// 投屏遥控页：网页端 / HDMI 共用。
// 未投屏：iOS5 选择页；投屏中：不可滚动的 iOS5 遥控页（亚麻底 + 金属顶栏），
// 显示系统状态栏与导航栏，只有「结束投屏」能退出。
// 自上而下：地址+结束、设置、音乐、大翻页条。

private val castBg = Color(0xFF0E1013)
private val castPageBrush = Brush.verticalGradient(
    0f to Color(0xFF3D434C),
    1f to Color(0xFF14161B),
)

// 走带栏「播放列表」开关按下态：深色渐变上要够亮的蓝
private val castListOnBlue = Color(0xFF3B8CFA)

/** 当前可用的 HDMI/投屏副屏（无副屏时为空）。 */
fun currentPresentationDisplays(context: Context): List<Display> {
    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
    return runCatching { dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) }
        .getOrNull()
        ?.toList()
        .orEmpty()
}

@Composable
fun rememberPresentationDisplays(): List<Display> {
    val context = LocalContext.current
    val dm = remember(context) {
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }
    fun current(): List<Display> = currentPresentationDisplays(context)
    var displays by remember { mutableStateOf(current()) }
    DisposableEffect(dm) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displays = current()
            }

            override fun onDisplayRemoved(displayId: Int) {
                displays = current()
            }

            override fun onDisplayChanged(displayId: Int) {
                displays = current()
            }
        }
        dm?.registerDisplayListener(listener, null)
        onDispose { dm?.unregisterDisplayListener(listener) }
    }
    return displays
}

/** Presentation 模式：把同一个网页放到 HDMI 副屏上。拔线时回调 onUnavailable。 */
@Composable
fun EbookCastPresentationHost(active: Boolean, url: String, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val displays = rememberPresentationDisplays()
    val display = displays.firstOrNull()
    LaunchedEffect(active, url, display?.displayId) {
        if (!active) return@LaunchedEffect
        if (url.isBlank()) return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        val target = display
        if (target == null) {
            onUnavailable()
            return@LaunchedEffect
        }
        val presentation = EbookCastPresentation(act, target, url)
        val shown = runCatching { presentation.show() }.isSuccess
        if (!shown) {
            onUnavailable()
            return@LaunchedEffect
        }
        try {
            awaitCancellation()
        } finally {
            runCatching { presentation.dismiss() }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class EbookCastPresentation(
    context: Context,
    display: Display,
    url: String,
) : Presentation(context, display) {

    private val web = WebView(context)
    private var destroyed = false

    init {
        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        web.loadUrl(url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(web)
    }

    override fun dismiss() {
        if (!destroyed) {
            destroyed = true
            runCatching { web.stopLoading() }
            runCatching { web.destroy() }
        }
        super.dismiss()
    }
}

@Composable
fun EbookCastPage(
    state: EbookCastState,
    prefs: EbookCastDisplayPrefs,
    playerState: PlayerUiState,
    readerFontPath: String,
    recentFonts: List<String>,
    /** 当前书的路径 / 章节标题：投屏设置里的“进度历史”用 */
    bookPath: String,
    chapterTitles: List<String>,
    onRestoreCastAnchor: (chapter: Int, block: Int) -> Unit,
    onBack: () -> Unit,
    onStartWeb: () -> Unit,
    onStartPresentation: () -> Unit,
    onStop: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpTrack: (Int) -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSetShowMusic: (Boolean) -> Unit,
    onSetTurnUnit: (EbookCastTurnUnit) -> Unit,
    onSetMusicOnCast: (Boolean) -> Unit,
    onSetTheme: (EbookTheme) -> Unit,
    onSetFontSize: (Int) -> Unit,
    onSetColumnWidth: (Int) -> Unit,
    onSetFontPath: (String) -> Unit,
) {
    val displays = rememberPresentationDisplays()
    var settingsOpen by remember { mutableStateOf(false) }
    var fontOpen by remember { mutableStateOf(false) }
    val active = state.mode != EbookCastMode.OFF
    // 设置气泡可以用返回键收起；投屏遥控页本身仍只有「结束投屏」能退出
    BackHandler(enabled = settingsOpen) {
        if (fontOpen) fontOpen = false else settingsOpen = false
    }
    LaunchedEffect(active) {
        if (!active) {
            settingsOpen = false
            fontOpen = false
        }
    }
    Box(Modifier.fillMaxSize().background(castBg)) {
        if (!active) {
            CastSelectionPage(
                state = state,
                displays = displays,
                onBack = onBack,
                onStartWeb = onStartWeb,
                onStartPresentation = onStartPresentation,
            )
        } else {
            CastRemotePage(
                state = state,
                prefs = prefs,
                playerState = playerState,
                readerFontPath = readerFontPath,
                bookPath = bookPath,
                chapterTitles = chapterTitles,
                onRestoreCastAnchor = onRestoreCastAnchor,
                onStop = onStop,
                onPrevPage = onPrevPage,
                onNextPage = onNextPage,
                onTogglePlay = onTogglePlay,
                onPrevious = onPrevious,
                onNext = onNext,
                onJumpTrack = onJumpTrack,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onSetShowMusic = onSetShowMusic,
                onSetTurnUnit = onSetTurnUnit,
                onSetMusicOnCast = onSetMusicOnCast,
                onOpenSettings = {
                    fontOpen = false
                    settingsOpen = true
                },
            )
        }
        if (settingsOpen && active) {
            CastSettingsOverlay(
                prefs = prefs,
                web = state.mode == EbookCastMode.WEB,
                readerFontPath = readerFontPath,
                bookPath = bookPath,
                chapterTitles = chapterTitles,
                onRestoreCastAnchor = onRestoreCastAnchor,
                recentFonts = recentFonts,
                fontOpen = fontOpen,
                onFontOpen = { fontOpen = true },
                onDismiss = { settingsOpen = false },
                onSetTheme = onSetTheme,
                onSetShowMusic = onSetShowMusic,
                onSetTurnUnit = onSetTurnUnit,
                onSetFontSize = onSetFontSize,
                onSetColumnWidth = onSetColumnWidth,
                onSetFontPath = {
                    onSetFontPath(it)
                    fontOpen = false
                },
            )
        }
    }
}

@Composable
private fun CastSelectionPage(
    state: EbookCastState,
    displays: List<Display>,
    onBack: () -> Unit,
    onStartWeb: () -> Unit,
    onStartPresentation: () -> Unit,
) {
    val hdmiReady = displays.isNotEmpty()
    Column(Modifier.fillMaxSize().background(Ios5Colors.linenBrush)) {
        Ios5NavBar(title = "投屏", onBack = onBack)
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 10.dp, bottom = 16.dp),
        ) {
            Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                Ios5Cell(
                    title = "网页端投屏",
                    subtitle = if (state.webRunning) {
                        "${state.lanUrl.ifBlank { state.url }} · 网页已在线，点按进入遥控"
                    } else {
                        "同一 Wi-Fi 下用浏览器打开手机上的网址"
                    },
                    onClick = onStartWeb,
                )
                Ios5CellDivider(startInset = 12.dp)
                Box(Modifier.alpha(if (hdmiReady) 1f else 0.45f)) {
                    Ios5Cell(
                        title = "Presentation（HDMI）",
                        subtitle = if (hdmiReady) {
                            "输出到 ${displays.firstOrNull()?.name ?: "外接显示器"}"
                        } else {
                            "未检测到 HDMI 显示器，接入后自动可选"
                        },
                        showChevron = hdmiReady,
                        onClick = { if (hdmiReady) onStartPresentation() },
                    )
                }
            }
            if (state.starting) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "正在启动投屏服务…",
                    color = Ios5Colors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (state.error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    state.error,
                    color = Color(0xFFD63A3A),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "手机当遥控器，大屏按自己的屏幕重新排版",
                color = Ios5Colors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "网页服务常驻在线：进遥控页由遥控端翻页，退出遥控后网页跟随手机阅读进度",
                color = Ios5Colors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CastRemotePage(
    state: EbookCastState,
    prefs: EbookCastDisplayPrefs,
    playerState: PlayerUiState,
    readerFontPath: String,
    bookPath: String,
    chapterTitles: List<String>,
    onRestoreCastAnchor: (chapter: Int, block: Int) -> Unit,
    onStop: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpTrack: (Int) -> Unit,
    onSeek: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSetShowMusic: (Boolean) -> Unit,
    onSetTurnUnit: (EbookCastTurnUnit) -> Unit,
    onSetMusicOnCast: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Ios5Colors.linenBrush),
    ) {
        val tight = maxHeight < 560.dp
        // 曲目列表开关：默认展开（蓝色），再按一次收起，只留标题 / 艺术家 / 进度
        var listOpen by rememberSaveable { mutableStateOf(true) }
        val themeLabel = when (prefs.theme) {
            EbookTheme.WHITE -> "白色"
            EbookTheme.SEPIA -> "护眼"
            EbookTheme.DARK -> "夜间"
        }
        val fontLabel = if (readerFontPath.isBlank()) "系统字体" else File(readerFontPath).name
        // 网页投屏与 HDMI 投屏各自存一套字号/栏宽，摘要和设置面板都按当前方式取值
        val isWebCast = state.mode == EbookCastMode.WEB
        val modeLabel = if (isWebCast) "网页" else "HDMI"
        val castFontPx = prefs.fontSizePx(isWebCast)
        val castColumnPx = prefs.maxColumnWidthPx(isWebCast)
        val density = LocalDensity.current
        // 底部导航键高度：走带栏背景铺到它下方，翻页键的可用高度要扣掉它
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val barTopPad = if (tight) 4.dp else 6.dp
        val transportH = if (tight) 54.dp else 64.dp
        val basePageH = if (tight) 76.dp else 92.dp
        // 实测：顶栏、设置卡片、音乐面板头部 —— 列表收起时翻页键能长到多大由它们决定
        var topBarH by remember { mutableStateOf(0.dp) }
        var settingsH by remember { mutableStateOf(0.dp) }
        var headerH by remember { mutableStateOf(0.dp) }
        val expandedPageH = if (listOpen || headerH <= 0.dp) {
            basePageH
        } else {
            // 剩余高度 = 屏幕 - 顶栏 - 设置 - 面板头部 - 固定间距 - 走带行 - 导航键，留 6dp 余量
            val used = topBarH + settingsH + headerH + 26.dp + transportH + barTopPad + 1.dp + navInset
            (maxHeight - used - 6.dp).coerceAtLeast(basePageH)
        }
        // 展开/收起列表时，翻页键行高用动画过渡（同一段动画里列表也在收起）
        val pageRowH by animateDpAsState(
            targetValue = expandedPageH,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "castPageRowHeight",
        )
        Column(Modifier.fillMaxSize()) {
            // 金属顶栏 + 地址行
            CastTopBar(
                state = state,
                onStop = onStop,
                modifier = Modifier.onSizeChanged {
                    topBarH = with(density) { it.height.toDp() }
                },
            )
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            ) {
                // 投屏设置入口紧贴地址栏下方
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.onSizeChanged {
                        settingsH = with(density) { it.height.toDp() }
                    },
                ) {
                    CastSettingsCard(
                        summary = "$modeLabel · $themeLabel · 字号 $castFontPx · 一栏≤$castColumnPx · $fontLabel",
                        onClick = onOpenSettings,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 音乐面板内嵌：卡片高度随曲目列表自适应，列表超长时占满可用高度并内部滚动
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val panelShape = RoundedCornerShape(12.dp)
                    EbookMusicPanel(
                        state = playerState,
                        onJump = onJumpTrack,
                        onSeek = onSeek,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = maxHeight)
                            .shadow(1.dp, panelShape)
                            .clip(panelShape)
                            .background(Color.White)
                            .border(1.dp, Color(0xFF9AA0A8), panelShape),
                        showList = listOpen,
                        onHeaderHeight = { headerH = it },
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            // 走带栏贴住屏幕底边：深色渐变一直铺到系统导航键下方，翻页键放在最底行
            CastControlBar(
                state = playerState,
                compact = tight,
                pageRowH = pageRowH,
                musicOnCast = prefs.musicOnCast(isWebCast),
                onToggleMusicOnCast = { onSetMusicOnCast(!prefs.musicOnCast(isWebCast)) },
                listOpen = listOpen,
                onToggleList = { listOpen = !listOpen },
                onPrevPage = onPrevPage,
                onNextPage = onNextPage,
                onTogglePlay = onTogglePlay,
                onPrevious = onPrevious,
                onNext = onNext,
            )
        }
    }
}

@Composable
private fun CastTopBar(
    state: EbookCastState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    val isWeb = state.mode == EbookCastMode.WEB
    val url = state.lanUrl.ifBlank { state.url }
    val anchor = state.anchor?.let { EbookAnchorCode.encode(it.chapter, it.block) }.orEmpty()
    val subtitle = listOfNotNull(
        state.chapterTitle.takeIf { it.isNotBlank() },
        anchor.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val address = if (isWeb) url else "HDMI · ${state.displayName}"
    Column(modifier.fillMaxWidth()) {
        // iOS5 金属顶栏：标题居中，右上角红色 gloss「结束投屏」键
        Box(
            Modifier.fillMaxWidth()
                .background(Ios5Colors.barBrush)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Box(Modifier.fillMaxWidth().height(46.dp)) {
                Text(
                    "阅读投屏",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 110.dp),
                    style = TextStyle(
                        shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f)),
                    ),
                )
                // 网页服务常驻：这里只退出遥控页（之后网页跟随手机阅读进度）
                Ios5SmallButton(
                    text = "退出遥控",
                    onClick = onStop,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    icon = Icons.Filled.Stop,
                    tone = Ios5ButtonTone.Red,
                )
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp)
                    .background(Color(0xFF3B4553)),
            )
        }
        // 地址行：点一下复制网址（iOS5 分组行 + 蓝色图标）
        Ios5Group(Modifier.padding(horizontal = 12.dp).padding(top = 10.dp)) {
            Ios5Cell(
                title = address.ifBlank { "投屏地址准备中…" },
                subtitle = subtitle,
                count = if (copied) "已复制" else "",
                showChevron = false,
                onClick = {
                    if (isWeb && address.isNotBlank()) {
                        clipboard.setText(AnnotatedString(address))
                        copied = true
                    }
                },
                trailing = if (isWeb) {
                    {
                        Icon(
                            if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            if (copied) "已复制" else "复制网址",
                            tint = Ios5Colors.IosBlue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun CastSettingsCard(summary: String, onClick: () -> Unit) {
    Ios5Group {
        Ios5Cell(
            title = "投屏设置",
            subtitle = summary,
            onClick = onClick,
        )
    }
}

// 走带栏：参照有声书播放页的控制区，翻页与音乐走带合并成两行。
// 深色渐变一直铺到系统导航键下方（贴住屏幕底边）；上行音乐走带（含列表开关），
// 最底行是上一页 / 下一页：收起列表时行高由动画放大，箭头变大但文字固定小号、始终在箭头下方。
@Composable
private fun CastControlBar(
    state: PlayerUiState,
    compact: Boolean,
    pageRowH: Dp,
    musicOnCast: Boolean,
    onToggleMusicOnCast: () -> Unit,
    listOpen: Boolean,
    onToggleList: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(castPageBrush)
            // 让出导航栏高度，但背景仍然铺满到屏幕最底部
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(top = if (compact) 4.dp else 6.dp),
    ) {
        // 上行：投屏出声开关 / 上一首 / 播放暂停 / 下一首 / 播放列表开关（透明 cell）
        Row(
            Modifier.fillMaxWidth().height(if (compact) 54.dp else 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 投屏图标：点亮 = 音乐在投屏设备（网页 / HDMI）播放，白色 = 在手机播放
            CastTransportKey(
                icon = if (musicOnCast) Icons.Filled.CastConnected else Icons.Filled.Cast,
                label = if (musicOnCast) "音乐在投屏设备播放" else "音乐在手机播放",
                iconSize = if (compact) 30.dp else 34.dp,
                tint = if (musicOnCast) castListOnBlue else Color.White,
                modifier = Modifier.weight(1f),
                onClick = onToggleMusicOnCast,
            )
            CastTransportKey(
                icon = Icons.Filled.SkipPrevious, label = "上一首",
                iconSize = if (compact) 30.dp else 34.dp,
                modifier = Modifier.weight(1f),
                onClick = onPrevious,
            )
            CastTransportKey(
                icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = if (state.isPlaying) "暂停" else "播放",
                iconSize = if (compact) 44.dp else 50.dp,
                modifier = Modifier.weight(1f),
                onClick = onTogglePlay,
            )
            CastTransportKey(
                icon = Icons.Filled.SkipNext, label = "下一首",
                iconSize = if (compact) 30.dp else 34.dp,
                modifier = Modifier.weight(1f),
                onClick = onNext,
            )
            // 列表开关：展开时蓝色（按下态），收起时白色
            CastTransportKey(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = if (listOpen) "收起播放列表" else "显示播放列表",
                iconSize = if (compact) 30.dp else 34.dp,
                tint = if (listOpen) castListOnBlue else Color.White,
                modifier = Modifier.weight(1f),
                onClick = onToggleList,
            )
        }
        // 最底行：翻页键，行高随列表收起/展开做动画；箭头随行高变大，文字固定小号在箭头下方
        Row(
            Modifier.fillMaxWidth().height(pageRowH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CastPageKey(
                label = "上一页",
                pointsLeft = true,
                compact = compact,
                rowHeight = pageRowH,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = onPrevPage,
            )
            CastPageKey(
                label = "下一页",
                pointsLeft = false,
                compact = compact,
                rowHeight = pageRowH,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = onNextPage,
            )
        }
    }
}

// 翻页键：箭头在上、小号文字在下；箭头尺寸跟着行高长大，文字大小固定。
@Composable
private fun CastPageKey(
    label: String,
    pointsLeft: Boolean,
    compact: Boolean,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val iconSize = (rowHeight * 0.5f).coerceIn(if (compact) 36.dp else 44.dp, 104.dp)
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CastChevron(pointsLeft = pointsLeft, size = iconSize)
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        Text(
            label,
            color = Color.White,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f))),
        )
    }
}

// Material 的 ArrowBackIos / ArrowForwardIos 字形在 24dp 画布里是偏心的（尖端不在正中），
// 放大后看起来没对齐；这里自己画两条线，包围盒严格以画布中心对称。
@Composable
private fun CastChevron(pointsLeft: Boolean, size: Dp, color: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val halfH = h * 0.27f
        val depth = w * 0.15f
        val stroke = w * 0.14f
        val tipX = if (pointsLeft) cx - depth else cx + depth
        val armX = if (pointsLeft) cx + depth else cx - depth
        drawLine(
            color = color,
            start = Offset(armX, cy - halfH),
            end = Offset(tipX, cy),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(tipX, cy),
            end = Offset(armX, cy + halfH),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

// 与有声书播放页同构：透明可点 cell，图标居中。
@Composable
private fun CastTransportKey(
    icon: ImageVector,
    label: String,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun CastSettingsOverlay(
    prefs: EbookCastDisplayPrefs,
    web: Boolean,
    readerFontPath: String,
    bookPath: String,
    chapterTitles: List<String>,
    onRestoreCastAnchor: (chapter: Int, block: Int) -> Unit,
    recentFonts: List<String>,
    fontOpen: Boolean,
    onFontOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSetTheme: (EbookTheme) -> Unit,
    onSetShowMusic: (Boolean) -> Unit,
    onSetTurnUnit: (EbookCastTurnUnit) -> Unit,
    onSetFontSize: (Int) -> Unit,
    onSetColumnWidth: (Int) -> Unit,
    onSetFontPath: (String) -> Unit,
) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.94f)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(Ios5Dimens.CornerGroup))
                .background(Ios5Colors.linenBrush)
                .border(1.dp, Color(0xFF6E7C8C), RoundedCornerShape(Ios5Dimens.CornerGroup))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        fontOpen -> "投屏字体"
                        web -> "网页投屏设置"
                        else -> "HDMI 投屏设置"
                    },
                    color = Ios5Colors.TableHeaderInk,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(
                        shadow = Shadow(Color.White.copy(alpha = 0.85f), Offset(0f, 1f)),
                    ),
                )
                Ios5SmallButton(
                    text = "完成",
                    onClick = onDismiss,
                    tone = Ios5ButtonTone.Blue,
                )
            }
            if (fontOpen) {
                CastFontList(
                    readerFontPath = readerFontPath,
                    recentFonts = recentFonts,
                    onPick = onSetFontPath,
                )
            } else {
                CastSettingsContent(
                    prefs = prefs,
                    web = web,
                    readerFontPath = readerFontPath,
                    bookPath = bookPath,
                    chapterTitles = chapterTitles,
                    // 恢复后直接收起设置面板，让用户看到跳过去的结果
                    onRestoreCastAnchor = { c, b ->
                        onRestoreCastAnchor(c, b)
                        onDismiss()
                    },
                    onFontOpen = onFontOpen,
                    onSetTheme = onSetTheme,
                    onSetShowMusic = onSetShowMusic,
                    onSetTurnUnit = onSetTurnUnit,
                    onSetFontSize = onSetFontSize,
                    onSetColumnWidth = onSetColumnWidth,
                )
            }
        }
    }
}

@Composable
private fun CastSettingsContent(
    prefs: EbookCastDisplayPrefs,
    web: Boolean,
    readerFontPath: String,
    bookPath: String,
    chapterTitles: List<String>,
    onRestoreCastAnchor: (chapter: Int, block: Int) -> Unit,
    onFontOpen: () -> Unit,
    onSetTheme: (EbookTheme) -> Unit,
    onSetShowMusic: (Boolean) -> Unit,
    onSetTurnUnit: (EbookCastTurnUnit) -> Unit,
    onSetFontSize: (Int) -> Unit,
    onSetColumnWidth: (Int) -> Unit,
) {
    val themeIdx = when (prefs.theme) {
        EbookTheme.WHITE -> 0
        EbookTheme.SEPIA -> 1
        EbookTheme.DARK -> 2
    }
    Ios5SegmentRow(
        title = "底色",
        options = listOf("白色", "护眼", "夜间"),
        selected = themeIdx,
        onSelect = {
            onSetTheme(
                when (it) {
                    1 -> EbookTheme.SEPIA
                    2 -> EbookTheme.DARK
                    else -> EbookTheme.WHITE
                },
            )
        },
    )
    Ios5SegmentRow(
        title = "翻页单位",
        options = listOf("一屏", "一栏"),
        selected = if (prefs.turnUnit(web) == EbookCastTurnUnit.COLUMN) 1 else 0,
        onSelect = { onSetTurnUnit(if (it == 1) EbookCastTurnUnit.COLUMN else EbookCastTurnUnit.SCREEN) },
    )
    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
        Ios5CheckRow(
            title = "显示音乐播放内容",
            subtitle = if (prefs.showMusic(web)) {
                "投屏画面底部显示正在播放的曲目和进度"
            } else {
                "投屏画面只显示正文，底部不显示音乐条"
            },
            checked = prefs.showMusic(web),
            onClick = { onSetShowMusic(!prefs.showMusic(web)) },
        )
    }
    Text(
        "翻页单位：一屏 = 每次翻过整屏（同屏多栏时一起翻）；一栏 = 每次只前进一栏。",
        color = Ios5Colors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, top = 4.dp, end = 20.dp),
    )
    Spacer(Modifier.height(8.dp))
    // 只显示/修改当前投屏方式（网页或 HDMI）那一份设置
    val activeColumnPx = prefs.maxColumnWidthPx(web)
    val activeFontPx = prefs.fontSizePx(web)
    var widthDraft by remember(activeColumnPx) {
        mutableFloatStateOf(activeColumnPx.toFloat())
    }
    var fontDraft by remember(activeFontPx) { mutableFloatStateOf(activeFontPx.toFloat()) }
    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
        Ios5SliderRow(
            title = "一栏最大宽度",
            valueLabel = "${widthDraft.toInt()}px",
            value = widthDraft,
            range = MIN_CAST_COLUMN_WIDTH_PX.toFloat()..MAX_CAST_COLUMN_WIDTH_PX.toFloat(),
            steps = ((MAX_CAST_COLUMN_WIDTH_PX - MIN_CAST_COLUMN_WIDTH_PX) / 20) - 1,
            onValueChangeFinished = { onSetColumnWidth(widthDraft.toInt()) },
            onValueChange = { widthDraft = it },
        )
        Ios5CellDivider(startInset = 12.dp)
        Ios5SliderRow(
            title = "字号",
            valueLabel = "${fontDraft.toInt()}",
            value = fontDraft,
            range = MIN_CAST_FONT_PX.toFloat()..MAX_CAST_FONT_PX.toFloat(),
            steps = MAX_CAST_FONT_PX - MIN_CAST_FONT_PX - 1,
            onValueChangeFinished = { onSetFontSize(fontDraft.toInt()) },
            onValueChange = { fontDraft = it },
        )
    }
    Text(
        "分几栏只由屏幕宽度和一栏最大宽度决定，改字号不会改变栏数。",
        color = Ios5Colors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, top = 4.dp, end = 20.dp),
    )
    Spacer(Modifier.height(8.dp))
    val fontName = if (readerFontPath.isBlank()) "系统字体" else File(readerFontPath).name
    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
        Ios5Cell(
            title = "字体",
            subtitle = fontName,
            onClick = onFontOpen,
        )
    }
    Text(
        "投屏字体与手机阅读字体一致；在这里选择会同时改阅读页字体。",
        color = Ios5Colors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    CastProgressHistory(
        bookPath = bookPath,
        chapterTitles = chapterTitles,
        onRestore = onRestoreCastAnchor,
    )
}

// 进度历史：与阅读设置里同款（每自然分钟一条，最多 30 条），点一条把大屏和手机都带到那里。
@Composable
private fun CastProgressHistory(
    bookPath: String,
    chapterTitles: List<String>,
    onRestore: (chapter: Int, block: Int) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as AuroraApplication).container
    var history by remember(bookPath) { mutableStateOf(emptyList<EbookStore.EbookProgressItem>()) }
    var expanded by remember(bookPath) { mutableStateOf(false) }
    LaunchedEffect(bookPath) {
        if (bookPath.isNotBlank()) history = container.ebookStore.progressHistory(bookPath)
    }
    Ios5SectionTitle("进度历史")
    if (history.isEmpty()) {
        Ios5StaticText("暂无历史：投屏翻页时每分钟会记一条")
        return
    }
    val visible = if (expanded) history else history.take(5)
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Ios5Group(Modifier.padding(horizontal = 12.dp)) {
        visible.forEachIndexed { i, h ->
            if (i > 0) Ios5CellDivider()
            val chapter = chapterTitles.getOrNull(h.spine)?.trim().orEmpty()
            Ios5NavRow(
                title = h.anchorCode,
                subtitle = (if (chapter.isNotBlank()) "$chapter · " else "") +
                    fmt.format(Date(h.updatedAt)),
                onClick = { onRestore(h.spine, h.block) },
            )
        }
        if (history.size > 5) {
            Ios5CellDivider()
            Ios5ActionRow(
                title = if (expanded) "收起" else "更多",
                onClick = { expanded = !expanded },
            )
        }
    }
    Text(
        "按自然分钟去重，最多保留 30 条；点一条把大屏和手机一起带过去，并写成当前进度。",
        color = Ios5Colors.TextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun CastFontList(
    readerFontPath: String,
    recentFonts: List<String>,
    onPick: (String) -> Unit,
) {
    val custom = remember(readerFontPath, recentFonts) {
        (listOf(readerFontPath).filter { it.isNotBlank() } + recentFonts)
            .distinct()
            .filter { File(it).isFile }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        item {
            Ios5Group(Modifier.padding(horizontal = 12.dp)) {
                Ios5CheckRow(
                    title = "系统字体",
                    subtitle = "与手机阅读页的系统字体一致",
                    checked = readerFontPath.isBlank(),
                    onClick = { onPick("") },
                )
                custom.forEach { path ->
                    Ios5CellDivider(startInset = 12.dp)
                    Ios5CheckRow(
                        title = File(path).name,
                        subtitle = "自定义字体",
                        checked = readerFontPath == path,
                        onClick = { onPick(path) },
                    )
                }
            }
        }
        item {
            Text(
                "字体文件在阅读页「选项 → 字体 → 选择字体文件」中添加；这里列出的字体与阅读页互通。",
                color = Ios5Colors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}
