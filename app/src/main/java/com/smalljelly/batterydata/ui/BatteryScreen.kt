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
import androidx.compose.material3.Icon
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.zIndex
import com.smalljelly.batterydata.data.BatteryRepository
import com.smalljelly.batterydata.data.ChargeRecordStore
import com.smalljelly.batterydata.data.ChargeRecorder
import com.smalljelly.batterydata.data.RecorderState
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.data.BatterySample
import com.smalljelly.batterydata.data.ScreenTimeTracker
import com.smalljelly.batterydata.data.ChargeReportAnalyzer
import com.smalljelly.batterydata.ui.components.BatteryInsightsCard
import com.smalljelly.batterydata.ui.components.rememberSlideProgress
import com.smalljelly.batterydata.ui.components.HealthTrendCard
import com.smalljelly.batterydata.data.UiDiagnostics
import com.smalljelly.batterydata.data.formatEta
import com.smalljelly.batterydata.ui.components.BatteryHealthCard
import com.smalljelly.batterydata.ui.components.BatteryHeroCard
import com.smalljelly.batterydata.ui.components.CardExpandOverlay
import com.smalljelly.batterydata.ui.components.navSlideEnter
import com.smalljelly.batterydata.ui.components.navSlideExit
import com.smalljelly.batterydata.ui.components.reportPressTo
import com.smalljelly.batterydata.ui.components.InfoRow
import com.smalljelly.batterydata.ui.components.InnerDivider
import com.smalljelly.batterydata.ui.components.LiveChartCard
import com.smalljelly.batterydata.ui.components.ManualCapacityDialog
import com.smalljelly.batterydata.ui.components.NexioTopBar
import com.smalljelly.batterydata.ui.components.TopBarActionSize
import com.smalljelly.batterydata.ui.components.NexioTopContentSpacer
import com.smalljelly.batterydata.ui.components.rememberNexioTopBarState
import com.smalljelly.batterydata.ui.components.DischargeTabIcon
import com.smalljelly.batterydata.ui.components.BatteryTabIcon
import com.smalljelly.batterydata.ui.components.RecordsTabIcon
import com.smalljelly.batterydata.ui.components.RealtimeTabIcon
import com.smalljelly.batterydata.ui.components.SettingsTabIcon
import com.smalljelly.batterydata.ui.components.menuDropdownColors
import com.smalljelly.batterydata.ui.components.capacityText
import com.smalljelly.batterydata.ui.components.designSourceSuffix
import com.smalljelly.batterydata.ui.components.RealtimeStatsCard
import com.haooz.nexioui.bottombar.BottomBar
import com.haooz.nexioui.bottombar.BottomBarDefaults
import com.haooz.nexioui.bottombar.BottomBarItem
import com.haooz.nexioui.topappbar.CollapsibleTopAppBarDefaults
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.button.FlatTextButton
import com.haooz.nexioui.button.ButtonDefaults
import com.smalljelly.batterydata.ui.components.NexioIconButton
import com.haooz.nexioui.dropdown.DropdownEntry
import com.haooz.nexioui.dropdown.DropdownItem
import com.haooz.nexioui.dropdown.DropdownMaskMode
import com.haooz.nexioui.dropdown.DropdownPanelDragSelectState
import com.haooz.nexioui.dropdown.OverlayDropdownPopup
import com.haooz.nexioui.theme.NexioTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import com.smalljelly.batterydata.ui.theme.AppMaterial
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

internal const val HISTORY_SIZE = 60

/** 刷新间隔：30 秒 */
internal const val REFRESH_INTERVAL_MS = 10_000L

/** 没有历史电流数据时，估算亮屏时长采用的典型亮屏电流（mA） */
internal const val TYPICAL_SCREEN_ON_CURRENT_MA = 450f

/** 亮屏电流样本的滑动窗口：30 分钟（10 秒一采） */
internal const val SCREEN_ON_WINDOW = 180

/** 少于这个数量就不信样本，退回典型值 */
internal const val MIN_SCREEN_ON_SAMPLES = 6

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
    BottomBarItem(icon = RealtimeTabIcon, label = "实时", contentDescription = "实时"),
    BottomBarItem(icon = RecordsTabIcon, label = "充电", contentDescription = "充电记录"),
    // 放电记录独立成页：两种记录的开始/结束条件正好相反（插电↔拔电），
    // 混在一页里既难讲清楚，也没法各自承载自己的数据（放电页有应用耗电排行）。
    BottomBarItem(icon = DischargeTabIcon, label = "放电", contentDescription = "放电记录"),
    // 多设备电量暂时下线：耳机电量在 MIUI 上第三方读不到（系统蓝牙独占 GATT，
    // 兜底的 SecurityCenter provider 是 signature 级权限）。代码保留在
    // DevicesScreen / BluetoothBatteryScanner，等找到可用数据源再接回底栏。
    BottomBarItem(icon = BatteryTabIcon, label = "电池", contentDescription = "电池"),
    BottomBarItem(icon = SettingsTabIcon, label = "设置", contentDescription = "设置"),
)

/** 底栏页数 */
private const val TAB_COUNT = 5

/**
 * 页签宽度比例（占整屏宽的比例，NexioUI 会乘上页签数）。
 *
 * NexioUI 默认 0.21 是按 4 个页签设计的：4 × 0.21 = 84%，胶囊两侧各留 8% 边距正好居中。
 * 5 个页签时 5 × 0.21 = 105% 会**比屏还宽**，胶囊被撑满并向右溢出、相邻图标间距也被拉大，
 * 所以收到 0.174：每页签中心距 ≈ 67dp，胶囊占 87% 屏宽、两侧各留 6.5% 边距，
 * 单页签 264px ≈ 96dp，远大于 48dp 的最小触控尺寸。
 */
private const val TAB_WIDTH_FRACTION = 0.174f

@Composable
fun BatteryScreen() {
    val context = LocalContext.current
    val store = remember { ChargeRecordStore(context.applicationContext) }
    // 单次记录的记录器是全局单例：状态与落盘都在它内部，切页、进详情都不受影响
    val recorderState by ChargeRecorder.state.collectAsState()
    val recordsVersion by ChargeRecorder.recordsVersion.collectAsState()

    LaunchedEffect(Unit) {
        // 预见式返回开关：启动时把持久化的值灌进全局状态（AppPage 都读它）
        runCatching {
            com.smalljelly.batterydata.ui.theme.AppMotion.applyPredictiveBack(
                BatterySettings(context.applicationContext).predictiveBackEnabled(),
            )
        }
        ChargeRecorder.ensure(context.applicationContext)
        ScreenTimeTracker.init(context.applicationContext)
        BatteryRepository(context.applicationContext).stream(REFRESH_INTERVAL_MS).collect { sample ->
            // 记录器自己会去重，前台页面也喂一份，服务万一没起来也不至于漏采
            ChargeRecorder.onSample(sample)
            ScreenTimeTracker.onSample(sample)
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
    // 四个主页面用 Pager 承载：跟手横滑、页面不重建，所以不会闪
    val pagerState = rememberPagerState(initialPage = 0) { TAB_COUNT }
    // 哪一页的「更多」菜单展开着（-1 = 没展开）。
    // 不能用单个 Boolean：两个主页面在 Pager 里始终同时活着，共用一个 bool 会让
    // 两页的菜单同时挂载，出现两份重叠的面板。
    // 顶栏玻璃按钮正被按住/拖拽（拉伸交互）：期间禁用 Pager 横滑切页。
    // 按下瞬间就置 true（手势尚无位移），比按位移阈值拦截更稳，斜向拖也不漏。
    var topBarDragging by remember { mutableStateOf(false) }
    // 页内弹窗（删除确认 / 设计容量）打开时，底栏要让位
    var modalVisible by remember { mutableStateOf(false) }
    // 底栏选中项：点击/拖拽时先乐观更新，再由 pager 的落位回写，避免"滑块卡在两格之间"
    var barIndex by remember { mutableIntStateOf(0) }
    // 二级页：关于（右侧推入）/ 单次充电详情（卡片原地展开）
    var showLogs by remember { mutableStateOf(false) }
    // 日志详情：内容和可见性分开存。关闭时只把可见性置 false，内容留着播完滑出动画，
    // 否则滑出去的是个空面板，看起来像"啪一下消失"。
    var viewingLog by remember { mutableStateOf<Pair<com.smalljelly.batterydata.data.LogFile, String>?>(null) }
    var logDetailVisible by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<Long?>(null) }
    var detailVisible by remember { mutableStateOf(false) }
    // 正在播收起动画：这期间仍算"二级页打开"，底栏不能提前滑进来盖住缩小的面板
    var detailClosing by remember { mutableStateOf(false) }
    // 设置页里是否开着二级页（关于 / 通知与显示 / 电池容量 …）：底栏归这一层管
    var settingsSubPage by remember { mutableStateOf(false) }
    // 被点卡片在 root 里的位置，作为展开动画的起点
    var detailOrigin by remember { mutableStateOf<Rect?>(null) }
    // 展开进度放在容器层：面板和它后面的页面共用这一条，节奏才一致
    val detailAnim = remember { Animatable(0f) }
    val detailBlurPx = with(LocalDensity.current) { DetailBlurRadius.toPx() }
    // 被点卡片的图层快照：展开开头用它顶替卡片画面
    var detailSnapshot by remember { mutableStateOf<GraphicsLayer?>(null) }
    val overlayOpen = showLogs || logDetailVisible || detailVisible || detailClosing ||
        settingsSubPage

    LaunchedEffect(detailId) {
        // 不变量：卡片已经卸载（detailId 清空）时，detailVisible 不允许再为 true。
        // CardExpandOverlay 自己接管返回时会绕过宿主的 onBack，直接把 detailId 清掉 ——
        // 之前只有 overlayOpen 依赖 detailVisible，它一残留，底栏就永远不回来。
        if (detailId == null) detailVisible = false
    }

    // 收起动画的看门狗：detailClosing 只在"卡片正在缩回去"这段时间为 true，
    // 正常路径由 CardExpandOverlay 的 onDismissed 复位。但动画一旦被打断
    // （页面切走、手势取消、系统回收渲染帧），那个回调就不会来了 ——
    // 标志永久卡在 true，底栏就再也不出现。这里兜一个超时强制复位。
    LaunchedEffect(detailClosing) {
        if (detailClosing) {
            kotlinx.coroutines.delay(700)
            detailClosing = false
        }
    }

    // 底栏的玻璃采样源：整页内容。底栏自己画在这层**外面**，
    // 否则"采样层里包含采样者"会让 HWUI 渲染树成环（之前踩过，直接 SIGSEGV）。
    val barBackdrop = rememberLayerBackdrop()

    // 顶栏按钮行（状态栏 + 折叠行）的底边 Y：在这行里横向拖拽是在拖玻璃按钮（拉伸交互），
    // 不应触发左右切页。按钮的 inspectDragGestures observeConsumed=true，吃掉横向事件后
    // 它仍能观察到拉伸，Pager 却不会翻页。
    val topRowBottomPx = with(LocalDensity.current) {
        (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            CollapsibleTopAppBarDefaults.CollapsedHeight).toPx()
    }

    LaunchedEffect(pagerState.currentPage) {
        barIndex = pagerState.currentPage
    }
    // 之前是一次性把 viewingLog / showLogs / showAbout 全清掉，
    // 从"日志详情"按返回就直接掉回主页面（连跳两级），像是没进过日志页。
    BackHandler(enabled = overlayOpen || pagerState.currentPage != 0) {
        when {
            logDetailVisible -> logDetailVisible = false
            showLogs -> showLogs = false
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
                // 在顶栏按钮行里横滑是在拖玻璃按钮（拉伸），不许触发翻页：
                // 在 Initial 阶段吃掉横向位移，Pager 在 Main 阶段看到已消费就不翻页。
                // 按钮的 inspectDragGestures observeConsumed=true，仍能观察到拉伸。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val inTopButtonRow = down.position.y < topRowBottomPx
                        var blocking = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull { it.pressed }
                            if (change == null || !change.pressed) break
                            if (!blocking) {
                                // 顶栏按钮行里的横滑是拖玻璃钮（拉伸），不该翻页
                                if (!inTopButtonRow) break
                                val total = change.position - down.position
                                if (abs(total.x) > viewConfiguration.touchSlop &&
                                    abs(total.x) > abs(total.y)
                                ) {
                                    blocking = true
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
                userScrollEnabled = !topBarDragging,
                // 滑到头不要回弹拉伸（否则会露出页面外的空白，像是"滑出界")
                overscrollEffect = null,
            ) { page ->
                when (page) {
                    0 -> RealtimeScreen()

                    1 -> {
                        val records = remember(recordsVersion) { store.all() }
                        RecordsScreen(
                            records = records,
                            recorder = recorderState,
                            // 只有记录页真的在当前可见位置时，新记录才播入场动画
                            isActive = pagerState.currentPage == 1 && !overlayOpen,
                            mode = RecordMode.Charge,
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
                            onModalVisibleChange = { modalVisible = it },
                            onTopBarPressChange = { topBarDragging = it },
                        )
                    }

                    // 放电记录独立一页：开始/结束条件与充电正相反（拔电开始、插电结束）
                    2 -> {
                        val records = remember(recordsVersion) { store.all() }
                        RecordsScreen(
                            records = records,
                            recorder = recorderState,
                            isActive = pagerState.currentPage == 2 && !overlayOpen,
                            mode = RecordMode.Discharge,
                            onStartRecording = { ChargeRecorder.startDischarge(context) },
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
                            onModalVisibleChange = { modalVisible = it },
                            onTopBarPressChange = { topBarDragging = it },
                        )
                    }

                    3 -> BatteryInfoScreen()

                    else -> SettingsPage(
                        onOpenLogs = { showLogs = true },
                        // 设置里进二级页时同样要让底栏让位（关于页就是从这里进去的）
                        onSubPageVisibleChange = { settingsSubPage = it },
                    )
                }
            }

            // 日志诊断页：NexioSchedule 那套侧滑载入（整屏推入 + 长尾缓动）
            AnimatedVisibility(
                visible = showLogs,
                enter = navSlideEnter(),
                exit = navSlideExit(),
            ) {
                LogDiagnosticScreen(
                    onBack = { showLogs = false },
                    // 进度只在页面挂载期间有意义：它随 AnimatedVisibility 一起进出
                    slideProgress = rememberSlideProgress(true),
                    onOpenLog = { log ->
                        viewingLog = log to com.smalljelly.batterydata.data.LogFileStore(
                            context.applicationContext,
                        ).read(log)
                        logDetailVisible = true
                    },
                )
            }

            // 日志详情：同一套侧滑载入（压在日志页上面）
            AnimatedVisibility(
                visible = logDetailVisible,
                enter = navSlideEnter(),
                exit = navSlideExit(),
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
                    predictiveBack = { com.smalljelly.batterydata.ui.theme.AppMotion.predictiveBack },
                    onDismissed = {
                        // ⚠ 必须把 detailVisible 也复位：这个回调既可能来自宿主的 onBack，
                        // 也可能来自 CardExpandOverlay 自己接管的返回手势/预测性返回。
                        // 后一条路径以前只清 detailId，detailVisible 永远停在 true ——
                        // 于是 overlayOpen 恒为真，底栏再也不回来（"退出后底栏消失"）。
                        detailVisible = false
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
                    barIndex = index
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
                backdrop = barBackdrop,
            )
        }
    }
}

/** 悬浮底栏：避开导航栏，胶囊宽度与拖拽手势都由 NexioUI 的 BottomBar 负责 */
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
        BottomBar(
            items = AppTabs,
            selectedIndex = selected,
            onSelect = onSelect,
            backdrop = backdrop,
            // 收窄页签宽度，压掉 5 页签下过大的图标间距（见 TAB_WIDTH_FRACTION）
            tabWidthFraction = TAB_WIDTH_FRACTION,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}
