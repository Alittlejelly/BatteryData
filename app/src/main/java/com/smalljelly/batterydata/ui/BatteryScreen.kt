package com.smalljelly.batterydata.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.zIndex
import com.smalljelly.batterydata.data.BatteryRepository
import com.smalljelly.batterydata.data.ChargeRecordStore
import com.smalljelly.batterydata.data.ChargeRecorder
import com.smalljelly.batterydata.data.RecorderState
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.data.BatterySample
import com.smalljelly.batterydata.data.UiDiagnostics
import com.smalljelly.batterydata.data.formatEta
import com.smalljelly.batterydata.ui.components.BatteryHealthCard
import com.smalljelly.batterydata.ui.components.BatteryHeroCard
import com.smalljelly.batterydata.ui.components.CardExpandOverlay
import com.smalljelly.batterydata.ui.components.DurationCard
import com.smalljelly.batterydata.ui.components.InfoRow
import com.smalljelly.batterydata.ui.components.InnerDivider
import com.smalljelly.batterydata.ui.components.LiveChartCard
import com.smalljelly.batterydata.ui.components.ManualCapacityDialog
import com.smalljelly.batterydata.ui.components.MoreMenuOverlay
import com.smalljelly.batterydata.ui.components.ProgressiveTopBlurBand
import com.smalljelly.batterydata.ui.components.GlassMoreButton
import com.smalljelly.batterydata.ui.components.BottomSwitcher
import com.smalljelly.batterydata.ui.components.BottomTab
import com.smalljelly.batterydata.ui.components.BottomTabIcon
import com.smalljelly.batterydata.ui.components.GlassMoreButtonSize
import com.smalljelly.batterydata.ui.components.capacityText
import com.smalljelly.batterydata.ui.components.designSourceSuffix
import com.smalljelly.batterydata.ui.components.RealtimeStatsCard
import com.smalljelly.batterydata.ui.components.rememberTopBarMaterialAlpha
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.haooz.softglass.GlassMaterialLevel
import com.haooz.softglass.SoftGlassTheme
import com.haooz.softglass.backdrop.GlassBackdrop
import com.haooz.softglass.backdrop.GlassEffectScope
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.haooz.softglass.backdrop.drawBackdrop
import com.haooz.softglass.backdrop.effects.blur
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import java.util.Locale

private const val HISTORY_SIZE = 60

/** 刷新间隔：30 秒 */
private const val REFRESH_INTERVAL_MS = 10_000L

/** 没有历史电流数据时，估算亮屏时长采用的典型亮屏电流（mA） */
private const val TYPICAL_SCREEN_ON_CURRENT_MA = 450f

/** 亮屏电流样本的滑动窗口：30 分钟（10 秒一采） */
private const val SCREEN_ON_WINDOW = 180

/** 少于这个数量就不信样本，退回典型值 */
private const val MIN_SCREEN_ON_SAMPLES = 6

/** 记录详情展开时，下层页面最终虚化的半径 */
private val DetailBlurRadius = 12.dp

/** 页面：底栏两页 + 关于页 + 充电详情 */
private sealed interface Screen {
    data object Home : Screen
    data object Records : Screen
    data object About : Screen
    data class Detail(val id: Long) : Screen
}

private fun Screen.depth(): Int = when (this) {
    Screen.Home -> 0
    Screen.Records -> 1
    is Screen.Detail -> 2
    Screen.About -> 2
}

private val AppTabs = listOf(
    BottomTab(label = "电池", icon = BottomTabIcon.Battery),
    BottomTab(label = "记录", icon = BottomTabIcon.Records),
)

@Composable
fun BatteryScreen() {
    val context = LocalContext.current
    val store = remember { ChargeRecordStore(context.applicationContext) }
    // 单次记录的记录器是全局单例：状态与落盘都在它内部，切页、进详情都不受影响
    val recorderState by ChargeRecorder.state.collectAsState()
    val recordsVersion by ChargeRecorder.recordsVersion.collectAsState()

    LaunchedEffect(Unit) {
        ChargeRecorder.ensure(context.applicationContext)
        BatteryRepository(context.applicationContext).stream(REFRESH_INTERVAL_MS).collect { sample ->
            // 记录器自己会去重，前台页面也喂一份，服务万一没起来也不至于漏采
            ChargeRecorder.onSample(sample)
        }
    }

    // 顶栏模糊自检的入口（日志页的「界面诊断」）已经去掉，这个开关不再暴露给用户。
    // 启动时强制归零：之前开着自检就等于永久关掉了模糊，而界面上再也关不掉。
    LaunchedEffect(Unit) {
        val settings = BatterySettings(context.applicationContext)
        if (settings.topBlurSelfTest()) settings.setTopBlurSelfTest(false)
        UiDiagnostics.selfTest = false
    }

    val scope = rememberCoroutineScope()
    // 两个主页面用 Pager 承载：跟手横滑、页面不重建，所以不会闪
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    // 哪一页的「更多」菜单展开着（-1 = 没展开）。
    // 不能用单个 Boolean：两个主页面在 Pager 里始终同时活着，共用一个 bool 会让
    // 两页的菜单同时挂载，出现两份重叠的面板。
    var menuPage by remember { mutableIntStateOf(-1) }
    // 手势闭包只创建一次，必须实时读菜单状态，否则会一直拿到首次组合时的值
    val menuPageState by rememberUpdatedState(menuPage)
    // 页内弹窗（删除确认 / 设计容量）打开时，底栏要让位
    var modalVisible by remember { mutableStateOf(false) }
    // 底栏选中项：点击/拖拽时先乐观更新，再由 pager 的落位回写，避免"滑块卡在两格之间"
    var barIndex by remember { mutableIntStateOf(0) }
    // 二级页：关于（右侧推入）/ 单次充电详情（卡片原地展开）
    var showAbout by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    // 日志详情：内容和可见性分开存。关闭时只把可见性置 false，内容留着播完滑出动画，
    // 否则滑出去的是个空面板，看起来像"啪一下消失"。
    var viewingLog by remember { mutableStateOf<Pair<com.smalljelly.batterydata.data.LogFile, String>?>(null) }
    var logDetailVisible by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<Long?>(null) }
    var detailVisible by remember { mutableStateOf(false) }
    // 正在播收起动画：这期间仍算"二级页打开"，底栏不能提前滑进来盖住缩小的面板
    var detailClosing by remember { mutableStateOf(false) }
    // 被点卡片在 root 里的位置，作为展开动画的起点
    var detailOrigin by remember { mutableStateOf<Rect?>(null) }
    // 展开进度放在容器层：面板和它后面的页面共用这一条，节奏才一致
    val detailAnim = remember { Animatable(0f) }
    val detailBlurPx = with(LocalDensity.current) { DetailBlurRadius.toPx() }
    // 被点卡片的图层快照：展开开头用它顶替卡片画面
    var detailSnapshot by remember { mutableStateOf<GraphicsLayer?>(null) }
    val overlayOpen = showAbout || showLogs || logDetailVisible || detailVisible || detailClosing

    // 底栏的玻璃采样源：整页内容。底栏自己画在这层**外面**，
    // 否则"采样层里包含采样者"会让 HWUI 渲染树成环（之前踩过，直接 SIGSEGV）。
    val barBackdrop = rememberLayerBackdrop()

    LaunchedEffect(pagerState.currentPage) {
        barIndex = pagerState.currentPage
        // 换了页，上一页的菜单不该还挂着
        if (menuPage >= 0) menuPage = -1
    }

    // 返回键一级一级退：日志详情 → 日志收集 → 关于 → 主页面。
    // 之前是一次性把 viewingLog / showLogs / showAbout 全清掉，
    // 从"日志详情"按返回就直接掉回主页面（连跳两级），像是没进过日志页。
    BackHandler(enabled = overlayOpen || pagerState.currentPage != 0) {
        when {
            logDetailVisible -> logDetailVisible = false
            showLogs -> showLogs = false
            showAbout -> showAbout = false
            detailVisible -> {
                detailVisible = false
                detailClosing = true
            }
            else -> scope.launch { pagerState.animateScrollToPage(0) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(barBackdrop)
                // 菜单展开时，横向滑动只用来收菜单，页面不动：在 Initial 阶段就把它吃掉，
                // Pager 在 Main 阶段看到事件已被消费就不会启动翻页。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        var blocking = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull { it.pressed }
                            if (change == null || !change.pressed) break
                            if (!blocking) {
                                // 菜单没开就别掺和，让 Pager 正常翻页
                                if (menuPageState < 0) break
                                val total = change.position - down.position
                                if (abs(total.x) > viewConfiguration.touchSlop &&
                                    abs(total.x) > abs(total.y)
                                ) {
                                    blocking = true
                                    // 先播菜单收起动画，这次手势剩下的位移全部吃掉
                                    menuPage = -1
                                } else if (abs(total.y) > viewConfiguration.touchSlop) {
                                    // 纵向：交给列表滚动 / 顶栏折叠
                                    break
                                }
                            }
                            if (blocking) change.consume()
                        }
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    // 记录详情展开时下层页面逐渐虚化：压暗 + 模糊一起上，
                    // 面板和背景的层次才拉开。模糊半径跟着展开进度走，
                    // 读的是 detailAnim（不在组合期读，只更新图层属性）
                    .graphicsLayer {
                        val p = detailAnim.value.coerceIn(0f, 1f)
                        renderEffect = if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && p > 0.01f
                        ) {
                            val r = detailBlurPx * p
                            BlurEffect(r, r, TileMode.Clamp)
                        } else {
                            null
                        }
                    },
                beyondViewportPageCount = 1,
                // 菜单展开时整页锁死：横滑只用来收菜单（下面的 Initial 拦截负责），
                // 页面一动不动。光靠消费事件不够稳 —— Pager 的拖动检测有时已经启动，
                // 这一条是硬开关，保证不会再出现"菜单开着页面滑走一半"。
                userScrollEnabled = menuPage < 0,
                // 滑到头不要回弹拉伸（否则会露出页面外的空白，像是"滑出界")
                overscrollEffect = null,
            ) { page ->
                when (page) {
                    0 -> HomeScreen(
                        menuOpen = menuPage == 0,
                        onMenuOpenChange = { open -> menuPage = if (open) 0 else -1 },
                        onOpenAbout = { showAbout = true },
                        onModalVisibleChange = { modalVisible = it },
                    )

                    else -> {
                        val records = remember(recordsVersion) { store.all() }
                        RecordsScreen(
                            records = records,
                            recorder = recorderState,
                            // 只有记录页真的在当前可见位置时，新记录才播入场动画
                            isActive = pagerState.currentPage == 1 && !overlayOpen,
                            menuOpen = menuPage == 1,
                            onMenuOpenChange = { open -> menuPage = if (open) 1 else -1 },
                            onStartRecording = { ChargeRecorder.arm(context) },
                            onCancelRecording = { ChargeRecorder.cancel(context) },
                            onFinishRecording = { ChargeRecorder.stopAndSave(context) },
                            onDelete = { id ->
                                store.delete(id)
                                ChargeRecorder.notifyRecordsChanged()
                            },
                            onOpenRecord = { record, rect, layer ->
                                detailOrigin = rect
                                detailSnapshot = layer
                                detailId = record.id
                                detailClosing = false
                                detailVisible = true
                            },
                            onOpenAbout = { showAbout = true },
                            onModalVisibleChange = { modalVisible = it },
                        )
                    }
                }
            }

            // 关于页从右侧推入；两个主页面始终活在 Pager 里，回来时状态不丢
            AnimatedVisibility(
                visible = showAbout,
                enter = slideInHorizontally(spring(dampingRatio = 0.92f, stiffness = 420f)) { it / 5 } +
                    fadeIn(tween(160)),
                exit = slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(160)),
            ) {
                AboutScreen(
                    onBack = { showAbout = false },
                    onOpenLogs = { showLogs = true },
                )
            }

            // 日志诊断页：和关于页同一套右侧推入动画
            AnimatedVisibility(
                visible = showLogs,
                enter = slideInHorizontally(spring(dampingRatio = 0.92f, stiffness = 420f)) { it / 5 } +
                    fadeIn(tween(160)),
                exit = slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(160)),
            ) {
                LogDiagnosticScreen(
                    onBack = { showLogs = false },
                    onOpenLog = { log ->
                        viewingLog = log to com.smalljelly.batterydata.data.LogFileStore(
                            context.applicationContext,
                        ).read(log)
                        logDetailVisible = true
                    },
                )
            }

            // 日志详情：和日志页同一套右侧推入动画
            AnimatedVisibility(
                visible = logDetailVisible,
                enter = slideInHorizontally(spring(dampingRatio = 0.92f, stiffness = 420f)) { it / 5 } +
                    fadeIn(tween(160)),
                exit = slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(160)),
            ) {
                viewingLog?.let { (log, text) ->
                    LogDetailScreen(log = log, content = text, onBack = { logDetailVisible = false })
                }
            }

            // 单次记录详情：从被点的那张卡片原地长成一整页（容器转换）
            val detailRecord = remember(detailId) {
                detailId?.let { id -> store.all().firstOrNull { it.id == id } }
            }
            if (detailRecord != null) {
                CardExpandOverlay(
                    visible = detailVisible,
                    origin = detailOrigin,
                    snapshot = detailSnapshot,
                    progress = detailAnim,
                    onDismissed = {
                        detailId = null
                        detailSnapshot = null
                        detailClosing = false
                    },
                ) {
                    ChargeDetailScreen(
                        record = detailRecord,
                        onBack = {
                            detailVisible = false
                            detailClosing = true
                        },
                    )
                }
            }
        }

        // 底栏在两个主页面始终显示（菜单展开时也留着，点它就收回菜单并切页）；
        // 只有二级页 / 页内弹窗时才让位
        AnimatedVisibility(
            visible = !overlayOpen && !modalVisible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 2 },
            modifier = Modifier.zIndex(1f),
        ) {
            PageBottomBar(
                selected = barIndex,
                onSelect = { index ->
                    // 菜单是盖在页面上的，先收回它再切页
                    if (menuPage >= 0) menuPage = -1
                    barIndex = index
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                backdrop = barBackdrop,
            )
        }
    }
}

/** 悬浮底栏：避开导航栏，胶囊自身宽度由 BottomSwitcher 按每格 21% 屏宽算 */
@Composable
private fun PageBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: com.haooz.softglass.backdrop.GlassBackdrop,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        BottomSwitcher(
            tabs = AppTabs,
            selected = selected,
            onSelect = onSelect,
            backdrop = backdrop,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun HomeScreen(
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onModalVisibleChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val repository = remember { BatteryRepository(context.applicationContext) }
    val settings = remember { BatterySettings(context.applicationContext) }
    var sample by remember { mutableStateOf(repository.snapshot()) }
    val currentHistory = remember { mutableStateListOf<Float>() }
    val voltageHistory = remember { mutableStateListOf<Float>() }
    // 亮屏状态下的放电电流样本。只这一个池子用来估「可亮屏时长」：
    // 混进熄屏待机的电流会把时长算得离谱地长，跟系统那个混合使用的预估也完全对不上。
    val screenOnCurrents = remember { mutableStateListOf<Float>() }
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    LaunchedEffect(Unit) {
        repository.stream(REFRESH_INTERVAL_MS).collect { latest ->
            sample = latest
            latest.currentMa?.let { currentHistory.append(it) }
            if (latest.voltageMv > 0) voltageHistory.append(latest.voltageMv / 1_000f)
            // 放电 + 屏幕亮着 才算有效样本（充电时的负电流、待机时的低电流都不能用）
            val discharge = latest.currentMa
            if (!latest.isPlugged && discharge != null && discharge < 0f && powerManager.isInteractive) {
                screenOnCurrents.append(-discharge, SCREEN_ON_WINDOW)
            }
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()
    // 顶栏材质：页面在顶部时没有模糊带、按钮没有玻璃和阴影，下滑后一起淡入
    val topBarAlpha = rememberTopBarMaterialAlpha(scrollBehavior)
    val backdrop = rememberLayerBackdrop()
    val pageBackdrop = rememberLayerBackdrop()
    // menuOpen 由容器层持有（底栏要能在菜单展开时点），menuMounted 是本页的挂载态：
    // 收起动画播完才移除
    var menuMounted by remember { mutableStateOf(false) }
    var openAboutAfterMenu by remember { mutableStateOf(false) }
    var openCapacityDialogAfterMenu by remember { mutableStateOf(false) }
    // 菜单形变的锚点（右上角那颗圆按钮的右上角，root 坐标）
    var menuAnchorRightPx by remember { mutableStateOf(0f) }
    var menuAnchorTopPx by remember { mutableStateOf(0f) }
    var showManualCapacityDialog by remember { mutableStateOf(false) }

    // 外部（底栏点击）把 menuOpen 置 false 时，本页照样要挂载出来播收起动画
    LaunchedEffect(menuOpen) { if (menuOpen) menuMounted = true }

    BackHandler(enabled = menuOpen || menuMounted) { onMenuOpenChange(false) }

    // 设计容量弹窗打开时，底栏让位
    LaunchedEffect(showManualCapacityDialog) { onModalVisibleChange(showManualCapacityDialog) }

    SoftGlassTheme(
        level = GlassMaterialLevel.FROSTED,
        isDark = isSystemInDarkTheme(),
        hasWallpaper = false,
    ) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.layerBackdrop(pageBackdrop),
        topBar = {
            Box(Modifier.fillMaxWidth().zIndex(10f)) {
                // 顶栏材质：SoftGlass 直接采样下面那份内容（内容列已 layerBackdrop 录进去）
                ProgressiveTopBlurBand(
                    backdrop = backdrop,
                    page = "home",
                    materialAlpha = topBarAlpha,
                    modifier = Modifier.matchParentSize(),
                )
                TopAppBar(
                    title = "电池",
                    largeTitle = "电池数据",
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        Box(
                            Modifier.onGloballyPositioned {
                                val rect = it.boundsInRoot()
                                menuAnchorRightPx = rect.right
                                menuAnchorTopPx = rect.top
                            },
                        ) {
                            if (menuMounted) {
                                // 展开期间圆按钮由浮层自己画（形变起点就是它），这里只占位保持布局
                                Spacer(Modifier.size(GlassMoreButtonSize))
                            } else {
                                GlassMoreButton(
                                    backdrop = backdrop,
                                    materialAlpha = topBarAlpha,
                                    onClick = {
                                        menuMounted = true
                                        onMenuOpenChange(true)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .layerBackdrop(backdrop)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
        ) {
            // 顶栏改成渐进模糊后内容要从栏底下穿过，顶部留白改成随内容滚动的 Spacer
            Spacer(Modifier.height(innerPadding.calculateTopPadding() + 10.dp))
            BatteryHeroCard(sample = sample, etaText = etaText(sample, screenOnCurrents))
            // 放电时的「可亮屏时长」已经和上面那句「预计可用」重复，只在充电时留这张卡
            durationInfo(sample)?.let { duration ->
                Spacer(Modifier.height(12.dp))
                DurationCard(
                    label = duration.label,
                    value = duration.value,
                    note = duration.note,
                )
            }
            Spacer(Modifier.height(12.dp))
            RealtimeStatsCard(sample = sample)
            Spacer(Modifier.height(12.dp))
            BatteryHealthCard(sample = sample)
            Spacer(Modifier.height(12.dp))
            LiveChartCard(
                currentHistory = currentHistory,
                voltageHistory = voltageHistory,
                intervalMs = REFRESH_INTERVAL_MS,
            )
            Spacer(Modifier.height(12.dp))
            InfoCard(sample)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "数据每 10 秒从系统 BatteryManager 与 power_supply 节点读取\n个别机型不开放电流、容量数据时会显示“未公开”",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // 悬浮底栏不再占 Scaffold 的 bottomBar 槽，这里手动留出栏高（56dp + 边距）
            Spacer(Modifier.height(28.dp + 76.dp))
        }

        ManualCapacityDialog(
            show = showManualCapacityDialog,
            manualCapacity = settings.manualDesignCapacityMah(),
            currentDesign = capacityText(sample.designCapacityMah) + designSourceSuffix(sample.designSource),
            onSet = { value ->
                settings.setManualDesignCapacityMah(value)
                sample = repository.snapshot()
                showManualCapacityDialog = false
            },
            onDismiss = { showManualCapacityDialog = false },
        )
        }

        if (menuMounted) {
            MoreMenuOverlay(
                show = menuOpen,
                anchorRightPx = menuAnchorRightPx,
                anchorTopPx = menuAnchorTopPx,
                pageBackdrop = pageBackdrop,
                onDismiss = { onMenuOpenChange(false) },
                onExitFinished = {
                    menuMounted = false
                    // 菜单播完收起动画再切页面 / 弹对话框，避免两层动画叠在一起，
                    // 也避免菜单的玻璃把刚弹出来的对话框一起采样进面板
                    if (openAboutAfterMenu) {
                        openAboutAfterMenu = false
                        onOpenAbout()
                    } else if (openCapacityDialogAfterMenu) {
                        openCapacityDialogAfterMenu = false
                        showManualCapacityDialog = true
                    }
                },
                onAbout = {
                    onMenuOpenChange(false)
                    openAboutAfterMenu = true
                },
                onManualCapacity = {
                    onMenuOpenChange(false)
                    openCapacityDialogAfterMenu = true
                },
            )
        }

    }
    }
}


@Composable
private fun InfoCard(sample: BatterySample) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = "电池信息",
            style = MiuixTheme.textStyles.title3,
            color = colors.onSurfaceContainer,
        )
        Spacer(Modifier.height(10.dp))
        InfoRow("电池技术", sample.technology)
        InnerDivider()
        InfoRow("充电方式", sample.pluggedText)
        InnerDivider()
        InfoRow("当前状态", sample.statusText)
        InnerDivider()
        InfoRow(
            label = "剩余电量",
            value = "${sample.level}%",
        )
        InnerDivider()
        InfoRow("采样间隔", "10 秒")
    }
}

private fun etaText(sample: BatterySample, screenOnCurrents: List<Float>): String {
    val temperature = sample.temperatureC?.let { String.format(Locale.US, "%.1f °C", it) }
    return when {
        sample.isFull -> "已充满，建议断开充电器"
        sample.isCharging -> formatEta(sample.etaMinutes)?.let { "预计 $it 后充满" }
            ?: temperature?.let { "电池温度 $it" }
            ?: "剩余电量 ${sample.level}%"
        // 和「可亮屏时长」共用同一套估算：大电池下面和时长卡里的数字必须一致，
        // 否则同一屏上两个"还能用多久"互相打架
        !sample.isPlugged -> formatEta(estimateScreenOnMinutes(sample, screenOnCurrents))
            ?.let { "预计可用 $it" }
            ?: temperature?.let { "电池温度 $it，状态${sample.healthText}" }
            ?: "剩余电量 ${sample.level}%"
        temperature != null -> "电池温度 $temperature，状态${sample.healthText}"
        else -> "剩余电量 ${sample.level}%"
    }
}

private data class DurationEstimate(
    val label: String,
    val value: String?,
    val note: String?,
)

/**
 * 只在充电时给一张「剩余充电时长」。
 * 放电时的可亮屏时长去掉了：大电池卡下面的「预计可用」用的是同一套估算，
 * 两个数字并排显示只是重复。
 */
private fun durationInfo(sample: BatterySample): DurationEstimate? = when {
    sample.isCharging -> DurationEstimate(
        label = "剩余充电时长",
        value = formatEta(sample.etaMinutes),
        note = "按当前充电功率推算",
    )
    else -> null
}

/** 剩余电量（mAh）：优先用库仑计，读不到就按「电量% × 满电容量」反推 */
private fun remainingMah(sample: BatterySample): Float? {
    sample.chargeCounterMah?.takeIf { it > 100f }?.let { return it }
    val full = sample.estimatedFullChargeMah ?: sample.designCapacityMah ?: return null
    if (full <= 0f) return null
    return full * sample.level / 100f
}

/**
 * 亮屏时长估算：剩余电量 ÷ 亮屏平均放电电流。
 * 只用屏幕亮着时的放电样本，样本不足时退回典型亮屏电流。
 */
private fun estimateScreenOnMinutes(
    sample: BatterySample,
    screenOnCurrents: List<Float>,
): Int? {
    val mah = remainingMah(sample) ?: return null
    val averageMa = screenOnCurrents
        .takeIf { it.size >= MIN_SCREEN_ON_SAMPLES }
        ?.average()
        ?.toFloat()
        ?.takeIf { it > 0f }
        ?: TYPICAL_SCREEN_ON_CURRENT_MA
    return (mah / averageMa * 60f).toInt().takeIf { it > 0 }
}

private fun MutableList<Float>.append(value: Float, cap: Int = HISTORY_SIZE) {
    if (size >= cap) removeAt(0)
    add(value)
}
