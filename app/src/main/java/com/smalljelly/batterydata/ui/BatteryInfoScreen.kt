package com.smalljelly.batterydata.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.basic.ArrowRight
import com.haooz.nexioui.overscroll.overScrollVertical
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.SoftGlassTheme
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.data.BatteryRepository
import com.smalljelly.batterydata.data.BatterySample
import com.smalljelly.batterydata.data.ChargeRecorder
import com.smalljelly.batterydata.data.ChargeRecordStore
import com.smalljelly.batterydata.data.ChargeReportAnalyzer
import com.smalljelly.batterydata.data.ScreenTimeStats
import com.smalljelly.batterydata.data.ScreenTimeTracker
import com.smalljelly.batterydata.data.healthRatingText
import com.smalljelly.batterydata.ui.components.AppPage
import com.smalljelly.batterydata.ui.components.BatteryHealthCard
import com.smalljelly.batterydata.ui.components.BatteryInsightsCard
import com.smalljelly.batterydata.ui.components.HealthTrendCard
import com.smalljelly.batterydata.ui.components.InfoRow
import com.smalljelly.batterydata.ui.components.InnerDivider
import com.smalljelly.batterydata.ui.components.NexioTopBar
import com.smalljelly.batterydata.ui.components.NexioTopContentSpacer
import com.smalljelly.batterydata.ui.components.SlideInHost
import com.smalljelly.batterydata.ui.components.capacityText
import com.smalljelly.batterydata.ui.components.designSourceSuffix
import com.smalljelly.batterydata.ui.components.rememberNexioTopBarState
import com.smalljelly.batterydata.ui.theme.AppMaterial
import com.smalljelly.batterydata.ui.theme.BatteryAmber
import com.smalljelly.batterydata.ui.theme.BatteryGreen
import com.smalljelly.batterydata.ui.theme.BatteryRed

/**
 * 「电池」页：首屏只放健康度总览和三个入口，具体内容都在二级页。
 * 这样一级页不用下滑，深度信息也不会把首屏挤满。
 */
@Composable
fun BatteryInfoScreen() {
    val context = LocalContext.current
    val repository = remember { BatteryRepository(context.applicationContext) }
    val recordStore = remember { ChargeRecordStore(context.applicationContext) }
    val screenStats by ScreenTimeTracker.state.collectAsState()
    val recordsVersion by ChargeRecorder.recordsVersion.collectAsState()
    val healthPoints = remember(recordStore, recordsVersion) {
        ChargeReportAnalyzer.healthTrend(recordStore.all())
    }
    var sample by remember { mutableStateOf(repository.snapshot()) }

    LaunchedEffect(Unit) {
        repository.stream(REFRESH_INTERVAL_MS).collect { sample = it }
    }

    var subPage by remember { mutableStateOf<BatterySubPage?>(null) }
    // 退出动画期间继续渲染"最后那一页"：直接喂 null 会让页面在 visible=false 那一帧
    // 瞬间消失，滑出去的只是空壳 —— 用户看到的就是"退出闪一下"。
    var displayPage by remember { mutableStateOf<BatterySubPage?>(null) }
    // 同步赋值：用 LaunchedEffect 会让进入动画的第一帧先渲染上一次的旧页
    if (subPage != null && displayPage != subPage) displayPage = subPage

    val scrollState = rememberScrollState()
    val topBarState = rememberNexioTopBarState()
    val backdrop = rememberLayerBackdrop()

    BackHandler(enabled = subPage != null) { subPage = null }

    SoftGlassTheme(
        level = AppMaterial.current.glassLevel,
        isDark = isSystemInDarkTheme(),
        hasWallpaper = false,
    ) {
        // 二级页用 NexioSchedule 那套侧滑载入：整屏从右侧推入，
        // 一级页被推开（视差）并压暗；转场中二级页裁屏幕圆角，像一张卡片。
        //
        // 采样层只能有一层：二级页不能嵌在 layerBackdrop 里面 ——
        // 否则采样树会包含采样者自己，HWUI 递归准备渲染树时直接爆栈（SIGSEGV）。
        SlideInHost(
            visible = subPage != null,
            baseContent = {
                // Box 作用域：顶栏要用 align(TopCenter)
                Box(Modifier.fillMaxSize()) {
                    // 一级页：健康总览 + 入口
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // 采样层必须在底色之前：layerBackdrop 只录它内层，
                            // 底色写在前面就录在图层外，顶栏玻璃会只剩一块灰板
                            .layerBackdrop(backdrop)
                            .background(NexioTheme.colorScheme.background),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(topBarState.scrollBehavior.nestedScrollConnection)
                                // 两端弹簧回弹 + 关掉 foundation 自带的 overscroll（叠加会抖）
                                .overScrollVertical()
                                .verticalScroll(scrollState, overscrollEffect = null)
                                .padding(horizontal = 16.dp),
                        ) {
                            NexioTopContentSpacer(topBarState)
                            Spacer(Modifier.height(8.dp))
                            HealthSummaryCard(sample = sample)
                            Spacer(Modifier.height(12.dp))
                            BatteryEntryCard(
                                title = "健康趋势",
                                summary = if (healthPoints.size >= 2) {
                                    "已积累 ${healthPoints.size} 个有效充电点"
                                } else {
                                    "完成 2 次以上有效充电后显示"
                                },
                                onClick = { subPage = BatterySubPage.Trend },
                            )
                            Spacer(Modifier.height(10.dp))
                            BatteryEntryCard(
                                title = "今日耗电洞察",
                                summary = insightsSummary(screenStats),
                                onClick = { subPage = BatterySubPage.Insights },
                            )
                            Spacer(Modifier.height(10.dp))
                            BatteryEntryCard(
                                title = "电池信息",
                                summary = "容量 · 循环 · 技术 · 采样",
                                onClick = { subPage = BatterySubPage.Info },
                            )
                            Spacer(Modifier.height(28.dp + 76.dp))
                        }
                    }

                    NexioTopBar(
                        state = topBarState,
                        title = "电池",
                        largeTitle = "电池健康",
                        backdrop = backdrop,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            },
            overlayContent = {
                val page = displayPage
                if (page != null) {
                    BatterySubPageScreen(
                        page = page,
                        sample = sample,
                        stats = screenStats,
                        healthPoints = healthPoints,
                        onBack = { subPage = null },
                    )
                }
            },
        )
    }
}

private enum class BatterySubPage(val title: String) {
    Trend("健康趋势"),
    Insights("今日耗电洞察"),
    Info("电池信息"),
}

private fun insightsSummary(stats: ScreenTimeStats): String {
    if (stats.sampleCount <= 0) return "开启记录后统计今日亮屏与待机耗电"
    return "亮屏 ${formatPercent(stats.screenOnDrainPercent)}% · 待机 ${formatPercent(stats.screenOffDrainPercent)}%"
}

private fun formatPercent(value: Float): String =
    if (value >= 10f) value.toInt().toString()
    else String.format(java.util.Locale.US, "%.1f", value)

/** 首屏健康总览：只显示百分比、评级和一行容量，完整明细在二级页。 */
@Composable
private fun HealthSummaryCard(sample: BatterySample) {
    val colors = NexioTheme.colorScheme
    val percent = sample.healthPercent
    val rating = if (percent == null) sample.healthText else healthRatingText(percent)
    val accent = when {
        percent == null -> colors.onSurfaceVariantSummary
        percent >= 80f -> BatteryGreen
        percent >= 70f -> BatteryAmber
        else -> BatteryRed
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NexioText(
                text = "电池健康",
                style = NexioTheme.textStyles.title3,
                color = colors.onSurfaceContainer,
            )
            NexioText(text = rating, fontSize = 14.sp, color = accent, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(10.dp))
        if (percent != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                NexioText(
                    text = percent.toInt().toString(),
                    fontSize = 46.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.2).sp,
                    color = colors.onSurfaceContainer,
                )
                NexioText(
                    text = "%",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            NexioText(
                text = "出厂 ${capacityText(sample.designCapacityMah)}" +
                    designSourceSuffix(sample.designSource) +
                    " · 满电 ${capacityText(sample.estimatedFullChargeMah)}",
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
            )
        } else {
            NexioText(
                text = "未读到容量数据，可在设置页手动设定出厂容量",
                fontSize = 13.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}

/** 一级页入口行：标题 + 摘要 + 右箭头，和设置页的条目同一套观感。 */
@Composable
private fun BatteryEntryCard(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 15.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                NexioText(text = title, fontSize = 16.sp, color = colors.onSurfaceContainer)
                Spacer(Modifier.height(3.dp))
                NexioText(text = summary, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = NexioIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.onSurfaceVariantSummary,
            )
        }
    }
}

/** 电池页的二级详情页：走统一骨架（侧滑载入 + 渐进模糊顶栏 + 回弹 + 预见式返回）。 */
@Composable
private fun BatterySubPageScreen(
    page: BatterySubPage,
    sample: BatterySample,
    stats: ScreenTimeStats,
    healthPoints: List<com.smalljelly.batterydata.data.HealthTrendPoint>,
    onBack: () -> Unit,
) {
    // 让位由 AppPage 按 Scaffold 实测的顶栏高度给，和记录页同一套算法。
    AppPage(
        title = page.title,
        largeTitle = page.title,
        onBack = onBack,
    ) {
        when (page) {
            BatterySubPage.Trend -> HealthTrendCard(points = healthPoints)
            BatterySubPage.Insights -> BatteryInsightsCard(stats = stats)
            BatterySubPage.Info -> {
                BatteryHealthCard(sample = sample)
                Spacer(Modifier.height(12.dp))
                BatteryInfoCard(sample)
            }
        }
    }
}

/** 电池信息明细：技术、充电方式、状态、电量与采样间隔。 */
@Composable
private fun BatteryInfoCard(sample: BatterySample) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        NexioText(
            text = "电池信息",
            style = NexioTheme.textStyles.title3,
            color = colors.onSurfaceContainer,
        )
        Spacer(Modifier.height(10.dp))
        InfoRow("电池技术", sample.technology)
        InnerDivider()
        InfoRow("充电方式", sample.pluggedText)
        InnerDivider()
        InfoRow("当前状态", sample.statusText)
        InnerDivider()
        InfoRow("剩余电量", "${sample.level}%")
        InnerDivider()
        InfoRow("采样间隔", "10 秒")
    }
}