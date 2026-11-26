package com.smalljelly.batterydata.ui

import com.haooz.nexioui.overlaydialog.OverlayDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smalljelly.batterydata.R
import com.smalljelly.batterydata.data.ApkUpdater
import com.smalljelly.batterydata.data.ChargeRecorder
import com.smalljelly.batterydata.island.BatteryIslandHelper
import com.smalljelly.batterydata.shizuku.ShizukuManager
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.data.UpdateChecker
import com.smalljelly.batterydata.ui.components.NexioIconButton
import com.smalljelly.batterydata.ui.components.NexioTopBar
import com.smalljelly.batterydata.ui.components.menuDropdownColors
import com.smalljelly.batterydata.ui.components.pageTopBarHeight
import com.smalljelly.batterydata.ui.components.rememberNexioTopBarState
import com.smalljelly.batterydata.ui.components.rememberTopBarPull
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.haooz.nexioui.button.Button
import com.haooz.nexioui.button.ButtonDefaults
import com.haooz.nexioui.card.Card
import androidx.compose.material3.Icon
import com.haooz.nexioui.button.FlatIconButton
import com.haooz.nexioui.progressindicator.LinearProgressIndicator
import com.haooz.nexioui.scaffold.Scaffold
import com.haooz.nexioui.switch.Switch
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.button.FlatTextButton
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.basic.ArrowRight
import com.haooz.nexioui.icon.basic.Check
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.nexioui.icon.basic.ArrowUpDown
import com.haooz.nexioui.overscroll.overScrollVertical
import com.haooz.nexioui.dropdown.DropdownColors
import com.haooz.nexioui.dropdown.DropdownDefaults
import com.haooz.nexioui.dropdown.DropdownEntry
import com.haooz.nexioui.dropdown.DropdownItem
import com.haooz.nexioui.dropdown.DropdownMaskMode
import com.haooz.nexioui.dropdown.DropdownPanelDragSelectState
import com.haooz.nexioui.dropdown.OverlayDropdownPopup
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.haooz.softglass.backdrop.GlassBackdrop
import com.smalljelly.batterydata.ui.theme.AppMaterial
import com.smalljelly.batterydata.ui.theme.MaterialOption
import com.smalljelly.batterydata.ui.theme.MaterialOptions
import com.smalljelly.batterydata.ui.theme.materialOptionOf

/**
 * 材质档的实时预览：一小块彩色渐变，按当前档位做模糊 + 半透明白色覆盖，
 * 直接反映"通透 / 奶白"的差别，不用切到首页去比对底栏。
 */
@Composable
private fun MaterialSwatch(option: MaterialOption) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val shape = remember { RoundedCornerShape(5.dp) }
    // 弹窗卡是实心的，预览块只表达"背后糊到什么程度"
    val alpha = 1f
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 18.dp)
            .clip(shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF4C8DFF), Color(0xFF34C759), Color(0xFFFFB020)),
                    ),
                )
                .then(
                    if (option.panelBlurDp > 0.dp) {
                        Modifier.blur(option.panelBlurDp * 0.4f)
                    } else {
                        Modifier
                    },
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background((if (dark) Color(0xFF1C1C1E) else Color.White).copy(alpha = alpha)),
        )
    }
}

// 下拉配色统一走 components/MenuDropdownColors.kt 里的 menuDropdownColors()：
// 条目不铺底色，选中态只靠打勾 + 主色文字，玻璃面板上不会切出灰/白色小方块。

/**
 * 关于页：独立页面（不是对话框），版式参考系统"关于手机管家"——
 * 左上返回箭头 + 大标题，中部居中的应用图标 / 名称 / 版本号，底部是检查更新。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLogs: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = NexioTheme.colorScheme
    val scope = rememberCoroutineScope()

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 更新源：自动（GitHub + Gitee 并行）/ 手动锁定其中一个
    val settings = remember { BatterySettings(context.applicationContext) }
    var updateSource by remember {
        mutableStateOf(UpdateChecker.Source.fromStorage(settings.updateSource()))
    }
    var showSourceMenu by remember { mutableStateOf(false) }
    // NexioUI 全局材质档：改完立刻生效（NexioMaterial.level 是 SnapshotState），同时落盘
    var materialLevel by remember { mutableStateOf(settings.materialLevel()) }
    var showMaterialMenu by remember { mutableStateOf(false) }
    // 右侧值胶囊的实测尺寸：作为下拉「收起态」的起点，面板从这颗胶囊长开
    var sourceChipSize by remember { mutableStateOf(IntSize.Zero) }
    var materialChipSize by remember { mutableStateOf(IntSize.Zero) }
    // 胶囊文案的进出场过渡（NexioSchedule 同款）：面板自己会画一份「钉在锚点角」的文案，
    // 页面这份要在展开时**迅速淡出到 0 并伴随轻微模糊**（见各胶囊 graphicsLayer）——
    // 这样玻璃采样到的残留是「正在模糊淡出」的，而不是一团清晰鬼影，展开就不会闪一下。
    // 之前硬切（fraction>0.001 立刻 alpha=0 / 延后两帧硬切）都在动画中途 abrupt 变化，
    // 玻璃抓到清晰残留 → 闪一下再恢复正常。
    // - fullyDismissed=true：面板已彻底退场，文案正常显示（alpha=1）。
    // - 展开方向（show=true）：alpha 随 fraction 迅速 1→0（约 f=0.18 淡完）+ 轻微模糊。
    // - 退场方向（show=false）：保持 alpha=0，由面板收起态文案接手，onDismissFinished 才硬切回 1。
    // 节点保持渲染只用 alpha=0：胶囊 Row 挂着 onGloballyPositioned 上报 collapseSize，
    // 摘掉节点尺寸就变 0，面板不知道从哪长出来。fraction 用 FloatState 在绘制期读，逐帧不重组。
    var sourceFullyDismissed by remember { mutableStateOf(true) }
    var materialFullyDismissed by remember { mutableStateOf(true) }
    var sourceFraction by remember { mutableFloatStateOf(0f) }
    var materialFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(showSourceMenu) { if (showSourceMenu) sourceFullyDismissed = false }
    LaunchedEffect(showMaterialMenu) { if (showMaterialMenu) materialFullyDismissed = false }

    // Shizuku / 小米超级岛：授权状态 + 岛开关
    val shizukuRunning = remember { mutableStateOf(ShizukuManager.isShizukuRunning()) }
    val shizukuAuthorized = remember { mutableStateOf(ShizukuManager.checkSelfPermission()) }
    val islandSupported = remember { BatteryIslandHelper.isIslandSupported(context) }
    var islandEnabled by remember { mutableStateOf(settings.islandEnabled()) }
    // 新增电池功能开关：自动充电记录、实时悬浮窗
    var autoRecordEnabled by remember { mutableStateOf(settings.autoRecordEnabled()) }
    var overlayEnabled by remember { mutableStateOf(settings.overlayEnabled()) }
    // 启动悬浮窗并回查结果：部分 ROM 会给权限但拒绝窗口，失败就把开关复位，
    // 避免出现"开关显示已开启、实际没有悬浮窗"的假状态
    val startOverlayChecked: () -> Unit = {
        com.smalljelly.batterydata.data.BatteryOverlayService.start(context)
        scope.launch {
            delay(1_200)
            if (!com.smalljelly.batterydata.data.BatteryOverlayService.isRunning()) {
                overlayEnabled = false
                settings.setOverlayEnabled(false)
                Toast.makeText(context, "悬浮窗未能启动，请检查系统权限后重试", Toast.LENGTH_LONG).show()
            }
        }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = android.provider.Settings.canDrawOverlays(context)
        overlayEnabled = granted
        settings.setOverlayEnabled(granted)
        if (granted) {
            startOverlayChecked()
        } else {
            com.smalljelly.batterydata.data.BatteryOverlayService.stop(context)
            Toast.makeText(context, "未授予悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    // 后台保活：电池优化白名单状态（系统可读）。从设置页返回时刷新，
    // 避免用户刚授权回来卡片还显示"未忽略"
    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    }
    var ignoringBattery by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery =
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 下拉面板的玻璃素材：本页内容层（自带不透明底）
    val backdrop = rememberLayerBackdrop()
    // 折叠顶栏：大标题随滚动收成小标题，内容层同时驱动顶栏材质与折叠
    val topBarState = rememberNexioTopBarState()
    val (pullModifier, _) = rememberTopBarPull()

    Scaffold(
        topBar = {
            NexioTopBar(
                state = topBarState,
                title = "关于电池数据",
                largeTitle = "关于电池数据",
                backdrop = backdrop,
                navigation = { backdropAlpha, shadowAlpha ->
                    // 圆形玻璃钮旋转 180° 当返回箭头用（图标本身不支持下旋转参数）
                    Box(Modifier.rotate(180f)) {
                        NexioIconButton(
                            onClick = onBack,
                            backdrop = backdrop,
                            icon = NexioIcons.Basic.ArrowRight,
                            contentDescription = "返回",
                            backdropAlpha = backdropAlpha,
                            shadowAlpha = shadowAlpha,
                            iconTint = colors.onBackground,
                        )
                    }
                },
            )
        },
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { innerPadding ->
        // 让位 = Scaffold 实测顶栏高度；首帧没量到时退回常数，避免内容顶进顶栏
        val measuredTop = innerPadding.calculateTopPadding()
        val contentTopInset =
            (if (measuredTop > 0.dp) measuredTop else pageTopBarHeight()) + 8.dp
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Column(
            modifier = Modifier
                .fillMaxSize()
                // ★ 采样层必须包住页底色：layerBackdrop 只录它**内层**的内容，
                // 底色写在它前面就被录在图层外 —— 弹窗玻璃会只剩一块灰板。
                .layerBackdrop(backdrop)
                .background(colors.background)
                // 顶栏折叠 / 材质的滚动来源：作为回弹层的父连接
                .nestedScroll(topBarState.scrollBehavior.nestedScrollConnection)
                // 两端弹簧回弹（关掉 foundation 自带 overscrollEffect，避免叠加）
                .overScrollVertical()
                // ⚠ 必须在内层：嵌套滚动由内向外派发，内层才拿得到"列表没消费掉"的增量
                .then(pullModifier)
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(horizontal = 24.dp),
        ) {
            // 顶栏盖在内容上，这里只让出栏高；再给 logo 与顶栏留一段间距
            Spacer(Modifier.height(contentTopInset + 30.dp))

            // 应用信息（logo / 名称 / 版本）：
            // 折叠时**向纵深沉入**（缩放后退 + 淡出），不是沿屏幕平面下移；
            // 布局高度同步收缩（比淡出稍慢），收完后下面的列表自动吸附到顶栏下方。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val f = topBarState.scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
                        // 高度按 f⁴ 收缩：f 还小的时候几乎**一点不减**，
                        // 先把"缩下去 + 淡出"完整放完；只有接近收起时(>0.8)才快速塌陷补位。
                        // 用 f² 时刚滚一点点就会裁掉底部（版本号被切一半就是这个原因）。
                        val shrink = f * f * f * f
                        val visible = (placeable.height * (1f - shrink)).roundToInt().coerceAtLeast(0)
                        layout(placeable.width, visible) {
                            placeable.place(0, 0)
                        }
                    }
                    .graphicsLayer {
                        val f = topBarState.scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
                        // 往屏幕里退：整体缩小 + 淡出
                        val s = 1f - 0.3f * f
                        scaleX = s
                        scaleY = s
                        alpha = 1f - f
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(size = 96.dp)
                Spacer(Modifier.height(16.dp))
                NexioText(
                    text = "电池数据",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                NexioText(
                    text = "版本号：$versionName",
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 不要按压波纹：卡片内缩的矩形波纹很突兀，点击反馈只靠右侧状态文案变化
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !checking,
                        ) {
                            checking = true
                            scope.launch {
                                val checked = UpdateChecker.check(context.applicationContext, updateSource)
                                result = checked
                                checking = false
                                // 已是最新：没必要弹窗打断，一条 Toast 就够
                                if (!checked.hasUpdate && checked.error == null) {
                                    Toast.makeText(
                                        context,
                                        "当前已是最新版本 $versionName",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    showResultDialog = true
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    NexioText(
                        text = "检查更新",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NexioText(
                            text = when {
                                checking -> "正在检查…"
                                result == null -> "当前 $versionName"
                                result?.hasUpdate == true -> "发现新版本"
                                else -> "已是最新"
                            },
                            fontSize = 14.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = NexioIcons.Basic.ArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    showSourceMenu = true
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        NexioText(
                            text = "更新源",
                            fontSize = 16.sp,
                            color = colors.onSurfaceContainer,
                        )
                        // 锚点 = 这颗值胶囊：下拉收起态的矩形就是它，面板从胶囊里长开
                        Box {
                            Row(
                                modifier = Modifier
                                    .onGloballyPositioned { sourceChipSize = it.size }
                                    // graphicsLayer 只改绘制，不影响测量，收起态尺寸仍按原尺寸算。
                                    // 展开时随 fraction 迅速淡出 + 轻微模糊（绘制期读状态，逐帧不重组）
                                    .graphicsLayer {
                                        val f = sourceFraction
                                        val a = if (sourceFullyDismissed) 1f
                                            else if (showSourceMenu) (1f - f / 0.18f).coerceIn(0f, 1f)
                                            else 0f
                                        alpha = a
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            val r = (1f - a) * 3f * density
                                            renderEffect = if (r > 0.1f) BlurEffect(r, r, TileMode.Clamp) else null
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NexioText(
                                    text = updateSource.label,
                                    fontSize = 14.sp,
                                    color = colors.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.width(6.dp))
                                // 上下双箭头 = 这是下拉选择，不是跳转
                                Icon(
                                    imageVector = NexioIcons.Basic.ArrowUpDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = colors.onSurfaceVariantSummary,
                                )
                            }

                            // NexioUI 的浮层下拉：面板是独立的小卡片，压在页面之上
                            OverlayDropdownPopup(
                                entry = remember(updateSource) {
                                    DropdownEntry(
                                        items = UpdateChecker.Source.entries.map { option ->
                                            DropdownItem(
                                                text = option.label,
                                                selected = option == updateSource,
                                                onClick = {
                                                    updateSource = option
                                                    settings.setUpdateSource(option.storageValue)
                                                    // 换源后旧结果作废，别让「发现新版本」的文案留在卡片上误导人
                                                    result = null
                                                },
                                            )
                                        },
                                    )
                                },
                                show = showSourceMenu,
                                onDismiss = { showSourceMenu = false },
                                // 退场动画彻底跑完才把页面文案硬切回来（兜底：返回手势打断也走这里）
                                onDismissFinished = {
                                    sourceFullyDismissed = true
                                    sourceFraction = 0f
                                },
                                // 展开进度驱动页面文案的淡出 + 模糊
                                onFractionProgress = { sourceFraction = it },
                                // 跟手滑选：按住面板上下滑动连续选择更新源
                                dragSelectState = remember { DropdownPanelDragSelectState(maskMode = DropdownMaskMode.Sliding) },
                                maxHeight = null,
                                // 条目本身不铺底色，选中态只靠打勾 + 主色文字
                                dropdownColors = menuDropdownColors(),
                                // 挂到最外层 Scaffold 的弹层宿主：面板落在被采样的内容层之外。
                                // 页内宿主上采样者与被采样者同处一棵子树，动画期间玻璃帧偶发缺失
                                // 时会露出一块灰底 —— 用户看到的就是"灰色块闪烁"。
                                renderInRootScaffold = true,
                                // 面板玻璃采样本页内容层；材质档选「关闭」时不给采样层，
                                // 库就会跳过 RenderEffect，面板直接走不透明底
                                liquidGlassBackdrop = if (AppMaterial.current.panelBlurDp > 0.dp) backdrop else null,
                                // 收起态尺寸 / 文案：面板从胶囊尺寸插值到整块菜单，
                                // 胶囊文案随进度放大并位移，中途再淡出（库自带的行为）
                                collapseSize = sourceChipSize.takeIf { it.width > 0 && it.height > 0 },
                                // 收起态玻璃矩形必须与胶囊实测尺寸完全一致（零补偿）：
                                // 面板缩到哪、胶囊就在哪，归位才不会"缩到旁边再跳一下"。
                                // 文案交接靠 collapseContent 与页面胶囊同尺寸同位置（库按内容自身尺寸钉角）。
                                collapseExtra = DpSize.Zero,
                                // 面板就压在胶囊上：不留库默认的 7/5dp 视觉补偿，
                                // 否则收起终点比胶囊偏右下几 dp，交接时看得见位移
                                anchorOffsetX = 0.dp,
                                anchorOffsetY = 0.dp,
                                collapseContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        NexioText(
                                            text = updateSource.label,
                                            fontSize = 14.sp,
                                            color = colors.onSurfaceVariantSummary,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            imageVector = NexioIcons.Basic.ArrowUpDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = colors.onSurfaceVariantSummary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    showMaterialMenu = true
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        NexioText(
                            text = "材质",
                            fontSize = 16.sp,
                            color = colors.onSurfaceContainer,
                        )
                        // 锚点 = 这颗材质胶囊（预览块 + 名称 + 双箭头）
                        Box {
                            Row(
                                modifier = Modifier
                                    .onGloballyPositioned { materialChipSize = it.size }
                                    // 同更新源：展开随 fraction 迅速淡出 + 轻微模糊
                                    .graphicsLayer {
                                        val f = materialFraction
                                        val a = if (materialFullyDismissed) 1f
                                            else if (showMaterialMenu) (1f - f / 0.18f).coerceIn(0f, 1f)
                                            else 0f
                                        alpha = a
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            val r = (1f - a) * 3f * density
                                            renderEffect = if (r > 0.1f) BlurEffect(r, r, TileMode.Clamp) else null
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 一眼看出这档到底是什么观感：模糊越重越糊、底色越浓越奶白
                                MaterialSwatch(materialOptionOf(materialLevel))
                                Spacer(Modifier.width(8.dp))
                                NexioText(
                                    text = materialOptionOf(materialLevel).label,
                                    fontSize = 14.sp,
                                    color = colors.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    imageVector = NexioIcons.Basic.ArrowUpDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = colors.onSurfaceVariantSummary,
                                )
                            }

                            OverlayDropdownPopup(
                                entry = remember(materialLevel) {
                                    DropdownEntry(
                                        items = MaterialOptions.map { option ->
                                            DropdownItem(
                                                text = option.label,
                                                selected = option.storage == materialLevel,
                                                onClick = {
                                                    materialLevel = option.storage
                                                    settings.setMaterialLevel(option.storage)
                                                    // 立刻套到 NexioUI + SoftGlass，所有页面同步重组
                                                    AppMaterial.apply(option)
                                                },
                                            )
                                        },
                                    )
                                },
                                show = showMaterialMenu,
                                onDismiss = { showMaterialMenu = false },
                                onDismissFinished = {
                                    materialFullyDismissed = true
                                    materialFraction = 0f
                                },
                                onFractionProgress = { materialFraction = it },
                                // 跟手滑选：按住面板上下滑动连续选择材质档
                                dragSelectState = remember { DropdownPanelDragSelectState(maskMode = DropdownMaskMode.Sliding) },
                                maxHeight = null,
                                dropdownColors = menuDropdownColors(),
                                // 挂到最外层 Scaffold 的弹层宿主：面板落在被采样的内容层之外。
                                // 页内宿主上采样者与被采样者同处一棵子树，动画期间玻璃帧偶发缺失
                                // 时会露出一块灰底 —— 用户看到的就是"灰色块闪烁"。
                                renderInRootScaffold = true,
                                // 同上：关闭档不给采样层，直接走不透明底
                                liquidGlassBackdrop = if (AppMaterial.current.panelBlurDp > 0.dp) backdrop else null,
                                // 收起态尺寸 / 文案：面板从胶囊尺寸插值到整块菜单，
                                // 胶囊文案随进度放大并位移，中途再淡出
                                collapseSize = materialChipSize.takeIf { it.width > 0 && it.height > 0 },
                                // 同更新源：收起态玻璃矩形与胶囊实测尺寸零补偿贴合
                                collapseExtra = DpSize.Zero,
                                anchorOffsetX = 0.dp,
                                anchorOffsetY = 0.dp,
                                collapseContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MaterialSwatch(materialOptionOf(materialLevel))
                                        Spacer(Modifier.width(8.dp))
                                        NexioText(
                                            text = materialOptionOf(materialLevel).label,
                                            fontSize = 14.sp,
                                            color = colors.onSurfaceVariantSummary,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(
                                            imageVector = NexioIcons.Basic.ArrowUpDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = colors.onSurfaceVariantSummary,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenLogs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    NexioText(
                        text = "日志诊断",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    Icon(
                        imageVector = NexioIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = colors.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                // Shizuku 授权状态：超级岛通知借它放行 XMSF 网络
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    NexioText(
                        text = "Shizuku",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    NexioText(
                        text = when {
                            !shizukuRunning.value -> "未运行"
                            !shizukuAuthorized.value -> "未授权"
                            else -> "已就绪"
                        },
                        fontSize = 14.sp,
                        color = when {
                            !shizukuRunning.value -> Color(0xFFFF6B6B)
                            !shizukuAuthorized.value -> Color(0xFFFFB347)
                            else -> Color(0xFF4CAF50)
                        },
                    )
                }
                if (!shizukuRunning.value) {
                    Spacer(Modifier.height(8.dp))
                    NexioText(
                        text = "超级岛需要 Shizuku 放行 XMSF 网络，请先安装并启动 Shizuku。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                } else if (!shizukuAuthorized.value) {
                    Spacer(Modifier.height(10.dp))
                    FlatTextButton(
                        text = "授权 Shizuku",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            BatteryIslandHelper.requestShizukuPermission { granted ->
                                shizukuAuthorized.value = granted
                                shizukuRunning.value = ShizukuManager.isShizukuRunning()
                                Toast.makeText(
                                    context,
                                    if (granted) "Shizuku 已授权" else "Shizuku 授权失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(14.dp))
                // 超级岛开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        NexioText(
                            text = "小米超级岛",
                            fontSize = 16.sp,
                            color = colors.onSurfaceContainer,
                        )
                        NexioText(
                            text = if (islandSupported) "充电时在顶部岛显示电量 / 功率" else "当前设备不支持超级岛",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = islandEnabled,
                        onCheckedChange = {
                            islandEnabled = it
                            settings.setIslandEnabled(it)
                            if (!it) {
                                BatteryIslandHelper.cancel(context)
                            } else {
                                // 开着岛且正插着电：立刻挂上保活服务，不等下次插电
                                ChargeRecorder.ensure(context.applicationContext)
                                // 排期充电 job：进程被杀后下次插电自动复活岛
                                com.smalljelly.batterydata.data.ChargeKeepAliveJobService.schedule(context.applicationContext)
                            }
                        },
                        enabled = islandSupported,
                    )
                }
                Spacer(Modifier.height(14.dp))
                // 自动充电记录：插电自动开始、拔电自动保存
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        NexioText(
                            text = "自动充电记录",
                            fontSize = 16.sp,
                            color = colors.onSurfaceContainer,
                        )
                        NexioText(
                            text = "插电自动开始记录，拔电后保存",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = autoRecordEnabled,
                        onCheckedChange = {
                            autoRecordEnabled = it
                            settings.setAutoRecordEnabled(it)
                            ChargeRecorder.onAutoRecordSettingChanged(context.applicationContext, it)
                        },
                    )
                }
                Spacer(Modifier.height(14.dp))
                // 实时电量悬浮窗：需要 SYSTEM_ALERT_WINDOW 权限
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        NexioText(
                            text = "实时电量悬浮窗",
                            fontSize = 16.sp,
                            color = colors.onSurfaceContainer,
                        )
                        NexioText(
                            text = "在其他应用上方显示电量 / 功率 / 温度",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = overlayEnabled,
                        onCheckedChange = { wanted ->
                            if (wanted && !android.provider.Settings.canDrawOverlays(context)) {
                                runCatching {
                                    overlayPermissionLauncher.launch(
                                        Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        )
                                    )
                                }
                            } else {
                                overlayEnabled = wanted
                                settings.setOverlayEnabled(wanted)
                                if (wanted) startOverlayChecked()
                                else com.smalljelly.batterydata.data.BatteryOverlayService.stop(context)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            // 后台保活：岛模式前台服务已兜住"充电时进程不死"，这里引导用户配好
            // HyperOS 的系统白名单，降低划后台被杀的概率（厂商策略只能引导，代码管不到）
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    NexioText(
                        text = "后台保活",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    NexioText(
                        text = "超级岛靠进程活着持续刷新：插电时自动挂前台服务（充电不费电），拔电即停；被杀后下次插电自动恢复。HyperOS 划掉后台仍可能杀进程，建议配好下面的系统白名单。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        NexioText(
                            text = "忽略电池优化",
                            fontSize = 14.sp,
                            color = colors.onSurfaceContainer,
                        )
                        NexioText(
                            text = if (ignoringBattery) "已忽略" else "未忽略",
                            fontSize = 14.sp,
                            color = if (ignoringBattery) Color(0xFF4CAF50) else Color(0xFFFFB347),
                        )
                    }
                    if (!ignoringBattery) {
                        Spacer(Modifier.height(8.dp))
                        FlatTextButton(
                            text = "请求忽略电池优化",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}"),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    FlatTextButton(
                        text = "自启动 / 省电策略设置",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openAutoStartSettings(context) },
                    )
                    Spacer(Modifier.height(4.dp))
                    NexioText(
                        text = "在打开的页面里允许自启动、省电策略选「无限制」；再到最近任务里长按本应用卡片加锁，划后台才不掉岛。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                    if (shizukuRunning.value && shizukuAuthorized.value) {
                        Spacer(Modifier.height(8.dp))
                        FlatTextButton(
                            text = "一键加入系统白名单（Shizuku）",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        ShizukuManager.applyKeepAliveWhitelist(context.applicationContext)
                                    }
                                    Toast.makeText(
                                        context,
                                        result ?: "Shizuku 不可用",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            NexioText(
                text = "数据来自系统 BatteryManager 与 power_supply 节点",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // 底部留白延伸到导航栏下方：内容可沉浸滚到屏幕底，末项又能停在栏上
            Spacer(Modifier.height(navBottom + 20.dp))
        }

        // 弹窗面板自己采样这层背景做模糊，整页保持清晰（只压暗，不糊）
        UpdateResultDialog(
            show = showResultDialog,
            result = result,
            // 面板模糊的采样源：本页内容层（顶层那个 layerBackdrop）
            backdrop = backdrop,
            onDismiss = { showResultDialog = false },
            onOpenUrl = { url ->
                showResultDialog = false
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

@Composable
private fun UpdateResultDialog(
    show: Boolean,
    result: UpdateChecker.Result?,
    backdrop: GlassBackdrop?,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = NexioTheme.colorScheme
    val hasUpdate = result?.hasUpdate == true
    val downloadUrl = result?.downloadUrl
    val checkError = result?.error
    val notes = result?.notes

    // 下载状态：每次弹窗重新打开都重置
    var downloading by remember(show) { mutableStateOf(false) }
    var percent by remember(show) { mutableStateOf(0) }
    var downloaded by remember(show) { mutableStateOf(0L) }
    var readyApk by remember(show) { mutableStateOf<File?>(null) }
    var failure by remember(show) { mutableStateOf<String?>(null) }
    var needPermission by remember(show) { mutableStateOf(false) }
    var job by remember(show) { mutableStateOf<Job?>(null) }

    // 对话框关掉就掐掉下载，别在后台空跑流量
    DisposableEffect(show) {
        onDispose { job?.cancel() }
    }

    fun startInstall(apk: File) {
        if (!ApkUpdater.canInstall(context)) {
            // 系统会拦下安装：先把用户送到「安装未知应用」授权页，回来后仍点「安装」
            needPermission = true
            runCatching { context.startActivity(ApkUpdater.installSettingsIntent(context)) }
            return
        }
        if (ApkUpdater.install(context, apk)) {
            onDismiss()
        } else {
            failure = "无法拉起系统安装器，可前往 Release 页面手动下载"
        }
    }

    fun startDownload() {
        if (downloadUrl.isNullOrBlank()) return
        downloading = true
        percent = 0
        downloaded = 0L
        failure = null
        needPermission = false
        val version = result?.latestVersion.orEmpty()
        job = scope.launch {
            runCatching {
                ApkUpdater.download(
                    context = context.applicationContext,
                    url = downloadUrl,
                    version = version,
                    onProgress = { p, done, _ ->
                        if (p >= 0) percent = p
                        downloaded = done
                    },
                )
            }.onSuccess { apk ->
                downloading = false
                readyApk = apk
                startInstall(apk)
            }.onFailure { error ->
                downloading = false
                // 用户点「取消下载」也会走到这里（协程被取消），别把它当成失败弹出来
                if (error !is kotlinx.coroutines.CancellationException) {
                    failure = error.message ?: "下载失败"
                }
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
        job = null
        downloading = false
        percent = 0
        downloaded = 0L
    }

    val dialogTitle = when {
        checkError != null -> "检查更新失败"
        hasUpdate -> "发现新版本 ${result?.latestVersion.orEmpty()}"
        else -> "已是最新版本"
    }
    // 摘要只留一行基本信息：更新说明放到正文里按行显示，混在一起就是一团乱
    val dialogSummary = buildString {
        append("当前版本 ${result?.currentVersion.orEmpty()}")
        val size = result?.assetSize ?: 0L
        if (hasUpdate && size > 0) append("  ·  安装包 ${formatSize(size)}")
        // 自动模式下两家都可能应答，标一句来源，排查"为什么版本对不上"时有用
        if (hasUpdate && !result?.source.isNullOrBlank()) append("  ·  来自 ${result?.source}")
    }

    val statusLine = when {
        failure != null -> failure
        checkError != null -> checkError
        needPermission -> "已下载完成。在系统设置里允许「安装未知应用」后，回来再点「安装」。"
        downloading -> if (percent >= 0) {
            "下载中 $percent%  ·  ${formatSize(downloaded)}"
        } else {
            "正在下载  ·  ${formatSize(downloaded)}"
        }
        readyApk != null -> "下载完成 ${formatSize(readyApk!!.length())}"
        else -> null
    }

    val primaryLabel = when {
        downloading -> "下载中"
        failure != null -> "重试"
        readyApk != null -> "安装"
        downloadUrl == null -> "前往发布页"
        else -> "下载并安装"
    }

    // NexioUI 的对话框：玻璃、模糊、折射、动画都是库自己的实现
    OverlayDialog(
        show = show,
        title = dialogTitle,
        summary = dialogSummary,
        // 面板自己采样这一层做模糊，背后页面只压暗
        liquidGlassBackdrop = backdrop,
        onDismissRequest = onDismiss,
        // 挂到最外层 Scaffold 的弹层宿主，才能盖住顶栏 / 底栏
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 更新说明：按行显示（小节标题加粗），超出高度内部滚动，不把弹窗顶破
            if (!notes.isNullOrBlank() && !downloading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 196.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    notes.split("\n").forEach { line ->
                        val heading = line.length <= 8 && !line.contains("：") && !line.contains(":")
                        NexioText(
                            text = line,
                            fontSize = if (heading) 14.sp else 13.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (heading) FontWeight.Medium else FontWeight.Normal,
                            color = if (heading) colors.onSurfaceContainer else colors.onSurfaceVariantSummary,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (downloading) {
                LinearProgressIndicator(
                    progress = if (percent >= 0) percent / 100f else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
                Spacer(Modifier.height(10.dp))
            }

            statusLine?.let { line ->
                NexioText(
                    text = line,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = if (failure != null || checkError != null) colors.error else colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasUpdate) {
                    // 和删除记录弹窗同一套：两个等宽按钮，主操作是 Miuix 主色实心按钮
                    FlatTextButton(
                        text = if (downloading) "取消下载" else "稍后",
                        modifier = Modifier.weight(1f),
                        onClick = { if (downloading) cancelDownload() else onDismiss() },
                    )
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !downloading,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = {
                            when {
                                downloading -> Unit
                                readyApk != null -> startInstall(readyApk!!)
                                downloadUrl == null -> onOpenUrl(result?.releasePage ?: "")
                                else -> startDownload()
                            }
                        },
                    ) {
                        NexioText(
                            text = primaryLabel,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = if (downloading) colors.disabledOnPrimaryButton else Color.White,
                        )
                    }
                } else {
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        onClick = onDismiss,
                    ) {
                        NexioText(
                            text = "知道了",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "--"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
}

/**
 * 应用图标：用 design/app-icon-source.png 生成的静态位图（drawable-nodpi/ic_launcher_flat.png）。
 * 不用自适应图标 XML —— 它被普通 Image 绘制时会再按系统安全区缩放一次，显示会偏小。
 */
@Composable
private fun AppIcon(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_flat),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f)),
    )
}

/**
 * 打开 MIUI/HyperOS 的自启动管理页（省电策略「无限制」也在附近的应用信息里）。
 * 厂商页面各版本组件名可能变，逐个尝试，最后兜底系统应用详情页。
 */
private fun openAutoStartSettings(context: Context) {
    val candidates = listOf(
        Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
        Intent("miui.intent.action.OP_AUTO_START"),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
    for (intent in candidates) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}
