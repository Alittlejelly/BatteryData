package com.smalljelly.batterydata.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.button.FlatTextButton
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.data.BluetoothBatteryScanner
import com.smalljelly.batterydata.data.DeviceBattery
import com.smalljelly.batterydata.data.DevicesState
import com.smalljelly.batterydata.ui.components.AppPage
import com.smalljelly.batterydata.ui.components.NexioIconButton
import com.smalljelly.batterydata.ui.theme.BatteryAmber
import com.smalljelly.batterydata.ui.theme.BatteryGreen
import com.smalljelly.batterydata.ui.theme.BatteryRed
import java.util.Locale
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * 「设备」页：把**正在连接**的蓝牙设备电量汇聚到一处。
 *
 * 两条关键设计：
 * 1. **只读系统报告"已连接"的设备**（profile 代理），不读配对列表、不扫描附近 ——
 *    配对列表里有车机、旧手机、没插电的耳机，绝大多数此刻没连上，读电量必然失败。
 * 2. **由用户勾选要跟踪哪几台**。连上的设备也不都值得显示（键盘、车机），
 *    自动全加进来会挤满列表还得逐个删。
 *
 * 页面骨架和其余页一致（[AppPage]）：渐进式模糊顶栏 + 两端回弹 + 预见式返回。
 */
@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val colors = NexioTheme.colorScheme
    val scanner = remember { BluetoothBatteryScanner(context.applicationContext) }
    val state by scanner.state.collectAsState()
    // 顶栏玻璃按钮要采样本页内容：AppPage 与 actions 必须用同一份采样层
    val backdrop = rememberLayerBackdrop()

    var permissionGranted by remember { mutableStateOf(scanner.permissionsGranted()) }
    var probeTarget by remember { mutableStateOf<DeviceBattery?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted || scanner.permissionsGranted()
        if (permissionGranted) scanner.refresh()
    }

    // 只申请 BLUETOOTH_CONNECT：不扫描就不该要「附近的设备」权限
    val requestPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionGranted = true
            scanner.refresh()
        }
    }

    val openBluetoothSettings: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // 进页面自动刷一轮
    LaunchedEffect(Unit) {
        if (permissionGranted && scanner.isReady()) scanner.refresh()
    }
    // 调试用：进页面 3 秒后自动探测一次已连接设备，结果进 logcat。
    // 挂在 LaunchedEffect 而不是按钮上 —— 自动化点不到按钮，代码路径一样能跑到。
    LaunchedEffect(Unit) {
        delay(3_000)
        scanner.firstConnectedDevice()?.let { device ->
            probeTarget = device
            scanner.probeAsync(device)
        }
    }
    DisposableEffect(scanner) {
        onDispose { scanner.stop() }
    }

    AppPage(
        title = "设备",
        largeTitle = "多设备电量",
        backdrop = backdrop,
        actions = { backdropAlpha, shadowAlpha ->
            NexioIconButton(
                onClick = { if (permissionGranted) scanner.refresh() else requestPermission() },
                backdrop = backdrop,
                icon = MiuixIcons.Refresh,
                contentDescription = "刷新",
                backdropAlpha = backdropAlpha,
                shadowAlpha = shadowAlpha,
                iconTint = colors.onBackground,
                enabled = !state.refreshing,
            )
        },
    ) {
        when {
            !permissionGranted -> PermissionCard(onGrant = requestPermission)

            !scanner.isReady() -> BluetoothOffCard(onOpenSettings = openBluetoothSettings)

            else -> {
                if (state.tracked.isNotEmpty()) {
                    TrackedSummaryCard(state.tracked)
                    Spacer(Modifier.height(12.dp))
                }

                // ① 我的设备：用户勾选过的
                if (state.tracked.isNotEmpty()) {
                    SectionTitle("我的设备")
                    state.tracked.forEachIndexed { index, device ->
                        DeviceCard(
                            device = device,
                            onRemove = { scanner.untrack(device.address) },
                        )
                        if (index != state.tracked.lastIndex) Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                }

                // ② 已连接但还没勾选的：点一下加入
                SectionTitle("已连接的设备")
                if (state.connected.isEmpty()) {
                    NoConnectedCard(onOpenSettings = openBluetoothSettings)
                } else {
                    val candidates = state.available
                    if (candidates.isEmpty()) {
                        NexioText(
                            text = "已连接的设备都已经加入列表了。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                    } else {
                        NexioText(
                            text = "点一下加入我的设备，只有加入的才会读电量。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                        candidates.forEachIndexed { index, device ->
                            CandidateCard(
                                device = device,
                                onAdd = { scanner.track(context, device) },
                            )
                            if (index != candidates.lastIndex) Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                if (state.refreshing) {
                    Spacer(Modifier.height(12.dp))
                    ReadingHint(state.readingName)
                }

                Spacer(Modifier.height(12.dp))
                NexioText(
                    text = "只读取系统里正在连接的设备。电量来自蓝牙标准的电量服务（0x180F），" +
                        "不支持该服务的设备（部分耳机走私有协议）会显示「—」。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariantSummary,
                )

                // 探测入口：把设备暴露的全部 GATT 服务打进 logcat。
                // 私有协议的耳机电量只能照抄厂商那条服务，而 UUID 得先抓出来。
                Spacer(Modifier.height(10.dp))
                FlatTextButton(
                    text = "探测已连接设备的 GATT 服务",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val target = scanner.firstConnectedDevice()
                        probeTarget = target
                        if (target != null) scanner.probeAsync(target)
                    },
                )
                probeTarget?.let { d ->
                    Spacer(Modifier.height(6.dp))
                    NexioText(
                        text = "正在探测 ${d.name}，结果写入 logcat（tag: DeviceBattery）",
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = NexioTheme.colorScheme
    NexioText(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = colors.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** 汇总卡：跟踪设备数 / 最低电量 / 已连接数 */
@Composable
private fun TrackedSummaryCard(devices: List<DeviceBattery>) {
    val colors = NexioTheme.colorScheme
    val known = devices.filter { it.level != null }
    val lowest = known.minByOrNull { it.level ?: 100 }
    val online = devices.count { it.connected }

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        NexioText(
            text = "设备电量",
            style = NexioTheme.textStyles.title3,
            color = colors.onSurfaceContainer,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryMetric(label = "跟踪", value = devices.size.toString())
            SummaryMetric(
                label = "最低电量",
                value = lowest?.level?.let { "$it%" } ?: "—",
                color = lowest?.level?.let { levelColor(it) } ?: colors.onSurfaceVariantSummary,
            )
            SummaryMetric(
                label = "已连接",
                value = online.toString(),
                color = if (online > 0) BatteryGreen else colors.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    color: Color = NexioTheme.colorScheme.onSurfaceContainer,
) {
    val colors = NexioTheme.colorScheme
    Column {
        NexioText(
            text = value,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        Spacer(Modifier.height(2.dp))
        NexioText(text = label, fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
    }
}

/** 已跟踪的设备：名称 + 状态 + 地址 + 电量条 + 更新时间 + 移除按钮 */
@Composable
private fun DeviceCard(device: DeviceBattery, onRemove: () -> Unit) {
    val colors = NexioTheme.colorScheme
    val level = device.level
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NexioText(
                        text = device.name,
                        fontSize = 16.sp,
                        color = colors.onSurfaceContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusChip(connected = device.connected)
                }
                Spacer(Modifier.height(4.dp))
                NexioText(
                    text = device.address,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                DeviceLevelBar(level = level)
                Spacer(Modifier.height(6.dp))
                NexioText(
                    text = updateText(device),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                NexioText(
                    text = level?.let { "$it%" } ?: "—",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = level?.let { levelColor(it) } ?: colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "从列表移除",
                    tint = BatteryRed.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onRemove)
                        .padding(5.dp),
                )
            }
        }
    }
}

/** 已连接但未跟踪：整卡可点，点一下加入 */
@Composable
private fun CandidateCard(device: DeviceBattery, onAdd: () -> Unit) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                NexioText(
                    text = device.name,
                    fontSize = 16.sp,
                    color = colors.onSurfaceContainer,
                )
                Spacer(Modifier.height(4.dp))
                NexioText(
                    text = device.address,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                NexioText(
                    text = "加入",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary,
                )
            }
        }
    }
}

/** 连接状态小胶囊：绿=已连接，灰=已断开 */
@Composable
private fun StatusChip(connected: Boolean) {
    val colors = NexioTheme.colorScheme
    val tint = if (connected) BatteryGreen else colors.onSurfaceVariantSummary
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = if (connected) 0.16f else 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        NexioText(
            text = if (connected) "已连接" else "已断开",
            fontSize = 10.sp,
            color = tint,
        )
    }
}

/** 电量条：和首页大电池同一套配色（低红 / 中黄 / 高绿） */
@Composable
private fun DeviceLevelBar(level: Int?) {
    val colors = NexioTheme.colorScheme
    val track = colors.onSurfaceVariantSummary.copy(alpha = 0.16f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(track),
    ) {
        val fraction = (level ?: 0).coerceIn(0, 100) / 100f
        if (fraction > 0f) {
            val color = levelColor(level ?: 0)
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(color, color))),
            )
        }
    }
}

@Composable
private fun ReadingHint(name: String?) {
    val colors = NexioTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 1.8.dp,
            color = colors.primary,
        )
        Spacer(Modifier.width(10.dp))
        NexioText(
            text = "正在读取 ${name ?: "…"} 的电量",
            fontSize = 11.sp,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun NoConnectedCard(onOpenSettings: () -> Unit) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NexioText(
                text = "现在没有设备连着",
                fontSize = 16.sp,
                color = colors.onSurfaceContainer,
            )
            Spacer(Modifier.height(6.dp))
            NexioText(
                text = "在系统蓝牙里连上耳机 / 手环 / 手表，再回到这里刷新。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            FlatTextButton(
                text = "打开蓝牙设置",
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun BluetoothOffCard(onOpenSettings: () -> Unit) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        NexioText(
            text = "蓝牙未开启",
            fontSize = 16.sp,
            color = colors.onSurfaceContainer,
        )
        Spacer(Modifier.height(6.dp))
        NexioText(
            text = "打开蓝牙并连上设备，再点右上角刷新。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(14.dp))
        FlatTextButton(
            text = "打开蓝牙设置",
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        NexioText(
            text = "需要蓝牙权限",
            fontSize = 16.sp,
            color = colors.onSurfaceContainer,
        )
        Spacer(Modifier.height(6.dp))
        NexioText(
            text = "读取已连接设备的电量需要「附近的设备」权限。设备名与电量只保存在本机，不会上传。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(14.dp))
        FlatTextButton(
            text = "授予权限",
            modifier = Modifier.fillMaxWidth(),
            onClick = onGrant,
        )
    }
}

private fun levelColor(level: Int): Color = when {
    level >= 60 -> BatteryGreen
    level >= 25 -> BatteryAmber
    else -> BatteryRed
}

/** 最近一次读到电量的时间 */
private fun updateText(device: DeviceBattery): String {
    if (device.level == null) {
        return if (device.connected) "已连接 · 设备未提供电量" else "还没读到过电量"
    }
    if (!device.connected) return "已断开 · 最后一次读数"
    if (device.updatedAt <= 0L) return "来自缓存"
    val elapsed = System.currentTimeMillis() - device.updatedAt
    return when {
        elapsed < 60_000L -> "刚刚更新"
        elapsed < 3_600_000L -> "${elapsed / 60_000L} 分钟前更新"
        else -> {
            val calendar = java.util.Calendar.getInstance().apply { timeInMillis = device.updatedAt }
            String.format(
                Locale.US,
                "%02d:%02d 更新",
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE),
            )
        }
    }
}