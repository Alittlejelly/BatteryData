package com.smalljelly.batterydata.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.basic.ArrowRight
import com.haooz.nexioui.overscroll.overScrollVertical
import com.haooz.nexioui.scaffold.Scaffold
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.nexioui.topappbar.SharedScrollBehavior
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.R
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.data.UpdateChecker
import com.smalljelly.batterydata.ui.components.CheckUpdateCard
import com.smalljelly.batterydata.ui.components.PredictiveBackBox
import com.smalljelly.batterydata.ui.components.SlideProgress
import com.smalljelly.batterydata.ui.components.rememberSlideProgress
import com.smalljelly.batterydata.ui.components.NexioIconButton
import com.smalljelly.batterydata.ui.components.NexioTopBar
import com.smalljelly.batterydata.ui.components.UpdateResultDialog
import com.smalljelly.batterydata.ui.components.UpdateSourceCard
import com.smalljelly.batterydata.ui.components.rememberNexioTopBarState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 关于页：**静态页，内容不滚动**（一屏放得下）。
 *
 * 一屏之内放完：应用身份（图标 / 名称 / 版本）+ 检查更新 + 更新源 + 日志诊断入口。
 *
 * 但纵向拖动仍然有两套反馈，和其他页保持一致：
 * - **两端弹簧回弹**（[overScrollVertical]）；
 * - **大标题随拖动收起**（见 [collapseOnOverscroll]）。
 *
 * 玻璃采样层写在底色之前（顶栏玻璃 / 下拉面板都要采样本页内容）。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    slideProgress: SlideProgress = rememberSlideProgress(true),
) {
    val colors = NexioTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { BatterySettings(context.applicationContext) }
    val backdrop = rememberLayerBackdrop()
    val topBarState = rememberNexioTopBarState()

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    var updateSource by remember {
        mutableStateOf(
            UpdateChecker.Source.entries.firstOrNull {
                it.storageValue == settings.updateSource()
            } ?: UpdateChecker.Source.AUTO,
        )
    }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var showResultDialog by remember { mutableStateOf(false) }

    // 预见式返回：位移由宿主的 SlideInHost 渲染（slideProgress 是唯一进度），
    // 这里只负责把返回手势交给它 —— 以前直接 BackHandler，没有跟手
    PredictiveBackBox(onBack = onBack, progress = slideProgress)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                NexioTopBar(
                    state = topBarState,
                    title = "关于",
                    largeTitle = "关于",
                    backdrop = backdrop,
                    navigation = { backdropAlpha, shadowAlpha ->
                        // 圆形玻璃钮旋转 180° 当返回箭头（图标本身不支持旋转参数）
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
            // 外层 Box 负责底注定位，内层滚动容器只放卡片内容。
            // ⚠ 底注**不能**放在 verticalScroll 里靠 weight 撑到底：滚动容器给子项的是
            // 无限高度约束，weight 没有剩余空间可分配，底注会紧贴最后一张卡片（实测只差 14dp）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(colors.background),
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        // 两端弹簧回弹（关掉 foundation 自带的 overscrollEffect，避免两套回弹互抢）
                        .overScrollVertical()
                        // 折叠连接夹在「回弹」和「滚动」之间：嵌套滚动由内向外派发，
                        // 这个位置才拿得到列表消费完剩下的位移（见 collapseOnOverscroll）
                        .collapseOnOverscroll(topBarState.scrollBehavior)
                        .verticalScroll(rememberScrollState(), overscrollEffect = null),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = innerPadding.calculateTopPadding())
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(size = 88.dp)
                        Spacer(Modifier.height(14.dp))
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

                        Spacer(Modifier.height(28.dp))
                        CheckUpdateCard(
                            versionName = versionName,
                            checking = checking,
                            result = result,
                            onClick = {
                                checking = true
                                scope.launch {
                                    val checked = UpdateChecker.check(
                                        context.applicationContext,
                                        updateSource,
                                    )
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
                        )

                        Spacer(Modifier.height(12.dp))
                        UpdateSourceCard(
                            source = updateSource,
                            onPick = { option ->
                                updateSource = option
                                settings.setUpdateSource(option.storageValue)
                                // 换源后旧结果作废，别让「发现新版本」留在卡片上误导人
                                result = null
                            },
                            backdrop = backdrop,
                        )

                        Spacer(Modifier.height(12.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 20.dp,
                            insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                        ) {
                            EntryRow(
                                title = "日志诊断",
                                summary = "查看运行日志与诊断快照",
                                onClick = onOpenLogs,
                            )
                        }
                    }
                }

                // 底注固定在页面底部（不随内容滚动）：外层 Box 里定位，
                // 这样它永远沉底，且和卡片之间自然隔开一整段空白。
                NexioText(
                    text = "数据来自系统 BatteryManager 与 power_supply 节点",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        }

        UpdateResultDialog(
            show = showResultDialog,
            result = result,
            backdrop = backdrop,
            onDismiss = { showResultDialog = false },
            onOpenUrl = { url ->
                showResultDialog = false
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

/**
 * 「超出滑动 → 大标题收起」。
 *
 * **为什么之前标题不会大小切换**：顶栏的折叠量来自 [SharedScrollBehavior] 对滚动增量的消费，
 * 而这一页是静态页 —— 内容不满一屏，`verticalScroll` 永远滚不动，一次拖动产生的位移
 * 全被 `overScrollVertical` 当回弹吃掉了。折叠逻辑从来没被喂过增量，
 * 大标题就恒定停在展开态：回弹看得见、标题不动，看着就"少了点什么"。
 *
 * **现在的做法**：把回弹的那段位移自己翻译成折叠量 —— 往上拖就把大标题收成小标题，
 * 松手后因为这页没有可停留的滚动位置，弹簧回到完全展开。
 *
 * 两个细节：
 * - **只观察、不消费**（返回 `Offset.Zero`）：位移继续往外层传，回弹照旧，
 *   于是"标题变小的同时内容跟着弹"，和别的页面手感一致；
 * - **靠空闲超时兜底回弹**：静态页没有 fling，`onPostFling` 不一定来，
 *   手指停在原处 120ms 后自己弹回大标题。
 */
@Composable
private fun Modifier.collapseOnOverscroll(behavior: SharedScrollBehavior): Modifier {
    val scope = rememberCoroutineScope()
    val connection = remember(behavior, scope) {
        object : NestedScrollConnection {
            private var idle: Job? = null

            /** 空闲即回弹（拖动中反复调用，旧的计时作废） */
            fun settleSoon() {
                idle?.cancel()
                idle = scope.launch {
                    // 手势可能只是瞬时停顿，别在拖动中途就把标题弹回去
                    delay(120)
                    behavior.expand()
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // 只处理"往上拖"（内容向上走）：那正是别的页面里收起大标题的方向
                if (available.y >= -0.5f) return Offset.Zero
                val limit = behavior.state.heightOffsetLimit
                // limit >= -1 表示大标题还没量出来（或本就是无大标题形态），不要乱动
                if (limit >= -1f) return Offset.Zero
                // setter 自带 coerceIn(limit, 0)，不用自己夹范围
                behavior.state.heightOffset = behavior.state.heightOffset + available.y
                settleSoon()
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                settleSoon()
                return Velocity.Zero
            }
        }
    }
    return this.nestedScroll(connection)
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