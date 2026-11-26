package com.smalljelly.batterydata.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.haooz.nexioui.button.FlatTextButton
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.data.BatteryRepository
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.ui.components.AppPage
import com.smalljelly.batterydata.ui.components.SlideInHost
import com.smalljelly.batterydata.ui.components.ManualCapacityDialog
import com.smalljelly.batterydata.ui.components.capacityText
import com.smalljelly.batterydata.ui.components.designSourceSuffix

/**
 * 「设置」一级页：只列分组入口，所有细节都在二级页。
 *
 * 二级页统一用 [AppPage]：渐进式模糊顶栏 + 两端回弹 + 预见式返回，
 * 一级页本身也是同一套骨架，切进来切出去观感一致。
 */
@Composable
fun SettingsPage(
    onOpenLogs: () -> Unit,
    onSubPageVisibleChange: (Boolean) -> Unit = {},
) {
    var subPage by remember { mutableStateOf<SettingsSubPage?>(null) }

    // 二级页打开时要把底栏藏起来：底栏是 BatteryScreen 画的悬浮条（HOST 只在它那一层），
    // 设置页自己管不了 —— 之前从设置进关于页，底栏一直压在下面。
    LaunchedEffect(subPage != null) { onSubPageVisibleChange(subPage != null) }

    // ⚠ 退出动画期间要继续渲染"最后那一页"：直接把 null 喂给 overlayContent，
    // 页面会在 visible=false 的那一刻瞬间消失，滑出去的只是一个空壳 ——
    // 用户看到的就是"退出时闪一下"。用 displayPage 把内容留住，等动画播完再自然卸载。
    var displayPage by remember { mutableStateOf<SettingsSubPage?>(null) }
    // 同步赋值（带相等判断，不会反复触发重组）：用 LaunchedEffect 的话，
    // 进入动画的第一帧会先渲染上一次的旧页，闪一帧才切过来。
    if (subPage != null && displayPage != subPage) displayPage = subPage

    // 二级页用 NexioSchedule 那套侧滑载入：新页整屏从右侧推入，
    // 设置首页被推开（视差）并压暗，转场中二级页裁屏幕圆角，像一张卡片
    SlideInHost(
        visible = subPage != null,
        baseContent = {
            SettingsHome(onOpen = { subPage = it }, onOpenLogs = onOpenLogs)
        },
        overlayContent = {
            when (displayPage) {
                SettingsSubPage.Notification -> NotificationSettingsScreen(onBack = { subPage = null })
                SettingsSubPage.Background -> BackgroundSettingsScreen(onBack = { subPage = null })
                SettingsSubPage.Appearance -> AppearanceSettingsScreen(onBack = { subPage = null })
                SettingsSubPage.Capacity -> CapacitySettingsScreen(onBack = { subPage = null })
                // 日志诊断与检查更新都收进关于页了（关于是静态页，一屏放得下）
                SettingsSubPage.About -> AboutScreen(
                    onBack = { subPage = null },
                    onOpenLogs = onOpenLogs,
                )
                null -> Unit
            }
        },
    )
}

private enum class SettingsSubPage { Notification, Background, Appearance, Capacity, About }

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

    AppPage(title = "设置", largeTitle = "设置") {
        SettingsGroup {
            EntryRow(
                title = "通知与显示",
                summary = "超级岛 · 电量提醒 · 自动记录 · 悬浮窗",
                onClick = { onOpen(SettingsSubPage.Notification) },
            )
            Spacer(Modifier.height(14.dp))
            EntryRow(
                title = "后台与权限",
                summary = "Shizuku · 电池优化 · 自启动",
                onClick = { onOpen(SettingsSubPage.Background) },
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            EntryRow(
                title = "外观与动画",
                summary = "材质 ${materialLabel(settings)} · 预见式返回",
                onClick = { onOpen(SettingsSubPage.Appearance) },
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
                title = "关于",
                summary = "版本 $versionName · 更新 · 日志诊断",
                onClick = { onOpen(SettingsSubPage.About) },
            )
        }

        Spacer(Modifier.height(18.dp))
        NexioText(
            text = "电池数据来自系统 BatteryManager 与 power_supply 节点",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

private fun materialLabel(settings: BatterySettings): String =
    com.smalljelly.batterydata.ui.theme.materialOptionOf(settings.materialLevel()).label

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        content()
    }
}

/**
 * 「电池容量」二级页：手动覆盖出厂容量。
 *
 * 之前这一页没有跟随统一骨架：自己写死 `Spacer(CollapsedHeight)` 让位，
 * 漏掉了状态栏和大标题块，于是「电池容量」大标题直接压在「出厂容量」卡片上。
 * 换成 [AppPage] 后让位由 [com.smalljelly.batterydata.ui.components.NexioTopContentSpacer]
 * 按实测折叠高度给，滚动时内容也能真正滚进顶栏糊带。
 */
@Composable
private fun CapacitySettingsScreen(onBack: () -> Unit) {
    val colors = NexioTheme.colorScheme
    val context = LocalContext.current
    val settings = remember { BatterySettings(context.applicationContext) }
    val repository = remember { BatteryRepository(context.applicationContext) }
    val backdrop = rememberLayerBackdrop()
    var sample by remember { mutableStateOf(repository.snapshot()) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repository.stream(REFRESH_INTERVAL_MS).collect { sample = it }
    }

    AppPage(
        title = "电池容量",
        largeTitle = "电池容量",
        backdrop = backdrop,
        onBack = onBack,
        horizontalPadding = 24.dp,
    ) {
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
                text = capacityText(sample.designCapacityMah) + designSourceSuffix(sample.designSource),
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
    }

    ManualCapacityDialog(
        show = showDialog,
        manualCapacity = settings.manualDesignCapacityMah(),
        currentDesign = capacityText(sample.designCapacityMah) + designSourceSuffix(sample.designSource),
        backdrop = backdrop,
        onSet = { value ->
            settings.setManualDesignCapacityMah(value)
            sample = repository.snapshot()
            showDialog = false
        },
        onDismiss = { showDialog = false },
    )
}