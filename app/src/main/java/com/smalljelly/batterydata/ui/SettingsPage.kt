package com.smalljelly.batterydata.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.haooz.nexioui.button.FlatTextButton
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.basic.ArrowRight
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.nexioui.topappbar.CollapsibleTopAppBarDefaults
import com.haooz.softglass.SoftGlassTheme
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.data.BatteryRepository
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.ui.components.NexioIconButton
import com.smalljelly.batterydata.ui.components.NexioTopBar
import com.smalljelly.batterydata.ui.components.NexioTopContentSpacer
import com.smalljelly.batterydata.ui.components.rememberNexioTopBarState
import com.smalljelly.batterydata.ui.theme.AppMaterial

/**
 * 「设置」一级页：只列分组入口，所有细节都在二级页。
 *
 * 一级页保持一屏可看完，不在首页堆通知开关、授权说明和保活动作。
 */
@Composable
fun SettingsPage(onOpenLogs: () -> Unit) {
    var subPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    BackHandler(enabled = subPage != null) { subPage = null }

    Box(Modifier.fillMaxSize()) {
        SettingsHome(onOpen = { subPage = it }, onOpenLogs = onOpenLogs)
        AnimatedVisibility(
            visible = subPage != null,
            enter = slideInHorizontally(spring(dampingRatio = 0.92f, stiffness = 420f)) { it / 5 } +
                fadeIn(tween(160)),
            exit = slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(160)),
            modifier = Modifier.zIndex(2f),
        ) {
            when (subPage) {
                SettingsSubPage.Notification -> NotificationSettingsScreen(onBack = { subPage = null })
                SettingsSubPage.Background -> BackgroundSettingsScreen(onBack = { subPage = null })
                SettingsSubPage.About -> AboutScreen(onBack = { subPage = null }, onOpenLogs = onOpenLogs)
                SettingsSubPage.Capacity -> CapacitySettingsScreen(onBack = { subPage = null })
                null -> Unit
            }
        }
    }
}

private enum class SettingsSubPage { Notification, Background, Capacity, About }

@Composable
private fun SettingsHome(
    onOpen: (SettingsSubPage) -> Unit,
    onOpenLogs: () -> Unit,
) {
    val colors = NexioTheme.colorScheme
    val context = LocalContext.current
    val settings = remember { BatterySettings(context.applicationContext) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    val scrollState = rememberScrollState()
    val topBarState = rememberNexioTopBarState()
    val backdrop = rememberLayerBackdrop()

    SoftGlassTheme(
        level = AppMaterial.current.glassLevel,
        isDark = androidx.compose.foundation.isSystemInDarkTheme(),
        hasWallpaper = false,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topBarState.scrollBehavior.nestedScrollConnection)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
            ) {
                NexioTopContentSpacer(topBarState)
                Spacer(Modifier.height(8.dp))

                SettingsGroup {
                    EntryRow(
                        title = "通知与显示",
                        summary = "超级岛 · 自动记录 · 悬浮窗",
                        onClick = { onOpen(SettingsSubPage.Notification) },
                    )
                    Spacer(Modifier.height(14.dp))
                    EntryRow(
                        title = "后台与权限",
                        summary = "Shizuku · 电池优化 · 自启动",
                        onClick = { onOpen(SettingsSubPage.Background) },
                    )
                    Spacer(Modifier.height(14.dp))
                    EntryRow(
                        title = "电池容量",
                        summary = settings.manualDesignCapacityMah()?.let { "已手动设为 ${it.toInt()} mAh" }
                            ?: "自动识别，可手动覆盖",
                        onClick = { onOpen(SettingsSubPage.Capacity) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                SettingsGroup {
                    EntryRow(
                        title = "关于与更新",
                        summary = "版本 $versionName · 更新源 · 材质",
                        onClick = { onOpen(SettingsSubPage.About) },
                    )
                    Spacer(Modifier.height(14.dp))
                    EntryRow(
                        title = "日志诊断",
                        summary = "查看运行日志与诊断快照",
                        onClick = onOpenLogs,
                    )
                }

                Spacer(Modifier.height(18.dp))
                NexioText(
                    text = "电池数据来自系统 BatteryManager 与 power_supply 节点",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp + 76.dp))
            }

            NexioTopBar(
                state = topBarState,
                title = "设置",
                largeTitle = "设置",
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * 「电池容量」二级页：手动覆盖出厂容量。
 *
 * 一级页只显示当前来源摘要，具体数值和设置入口都在这里。
 */
@Composable
private fun CapacitySettingsScreen(onBack: () -> Unit) {
    val colors = NexioTheme.colorScheme
    val context = LocalContext.current
    val settings = remember { BatterySettings(context.applicationContext) }
    val repository = remember { BatteryRepository(context.applicationContext) }
    var sample by remember { mutableStateOf(repository.snapshot()) }
    var showDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val topBarState = rememberNexioTopBarState()
    val backdrop = rememberLayerBackdrop()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        repository.stream(REFRESH_INTERVAL_MS).collect { sample = it }
    }

    SoftGlassTheme(
        level = AppMaterial.current.glassLevel,
        isDark = androidx.compose.foundation.isSystemInDarkTheme(),
        hasWallpaper = false,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(topBarState.scrollBehavior.nestedScrollConnection)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(
                    Modifier.height(
                        CollapsibleTopAppBarDefaults.CollapsedHeight + 16.dp,
                    ),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    val manual = settings.manualDesignCapacityMah()
                    NexioText(
                        text = "出厂容量",
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    NexioText(
                        text = com.smalljelly.batterydata.ui.components.capacityText(sample.designCapacityMah) +
                            com.smalljelly.batterydata.ui.components.designSourceSuffix(sample.designSource),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurfaceContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    NexioText(
                        text = if (manual != null) {
                            "当前使用手动值 ${manual.toInt()} mAh，优先级高于系统节点、系统配置与机型库。"
                        } else {
                            "当前为自动识别。识别顺序：系统节点 → 系统配置 → 机型库；都读不到时可以手动填写。"
                        },
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(14.dp))
                    FlatTextButton(
                        text = if (manual != null) "修改手动容量" else "手动设定容量",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showDialog = true },
                    )
                    if (manual != null) {
                        Spacer(Modifier.height(8.dp))
                        FlatTextButton(
                            text = "恢复自动识别",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                settings.setManualDesignCapacityMah(null)
                                sample = repository.snapshot()
                            },
                        )
                    }
                }
                Spacer(Modifier.height(40.dp))
            }

            NexioTopBar(
                state = topBarState,
                title = "电池容量",
                largeTitle = "电池容量",
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.TopCenter),
                navigation = { backdropAlpha, shadowAlpha ->
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

            com.smalljelly.batterydata.ui.components.ManualCapacityDialog(
                show = showDialog,
                manualCapacity = settings.manualDesignCapacityMah(),
                currentDesign = com.smalljelly.batterydata.ui.components.capacityText(sample.designCapacityMah) +
                    com.smalljelly.batterydata.ui.components.designSourceSuffix(sample.designSource),
                backdrop = backdrop,
                onSet = { value ->
                    settings.setManualDesignCapacityMah(value)
                    sample = repository.snapshot()
                    showDialog = false
                },
                onDismiss = { showDialog = false },
            )
        }
    }
}
