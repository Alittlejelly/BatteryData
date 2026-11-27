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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh

/**
 * 「设备」页：把自己那几台蓝牙设备的电量汇聚到一处。
 *
 * **只统计系统里已配对的设备** —— 逐台连上读标准电量服务（0x180F / 0x2A19），
 * 不做 BLE 扫描：扫出来的是周围别人的耳机、手环，那不叫"我的设备电量"。
 *
 * 连不上的那几台用缓存里的旧电量显示，标成「已断开」当历史留着，可以手动删。
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
            // Android 12 之前没有这个运行时权限，直接读
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

    // 进页面自动刷一轮（列表先用缓存铺满，不会白屏）
    LaunchedEffect(Unit) {
        if (permissionGranted && scanner.isReady()) scanner.refresh()
    }
    // 离开页面就取消：连接 GATT 是有成本的，没理由在后台跑
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
                if (state.devices.isNotEmpty()) {
                    DeviceSummaryCard(state.devices)
                    Spacer(Modifier.height(12.dp))
                }

                val connected = state.devices.filter { it.connected }
                val lost = state.devices.filterNot { it.connected }

                when {
                    state.refreshing && state.devices.isEmpty() -> RefreshingCard(state)

                    state.devices.isEmpty() -> EmptyDeviceCard(
                        refreshed = state.refreshedAt > 0,
                        onOpenSettings = openBluetoothSettings,
                    )

                    else -> {
                        connected.forEachIndexed { index, device ->
                            DeviceCard(device = device, onDelete = null)
                            if (index != connected.lastIndex) Spacer(Modifier.height(10.dp))
                        }

                        if (lost.isNotEmpty()) {
                            if (connected.isNotEmpty()) Spacer(Modifier.height(18.dp))
                            NexioText(
                                text = "已断开",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                            )
                            lost.forEachIndexed { index, device ->
                                DeviceCard(
                                    device = device,
                                    // 断开的历史可以删；已连接的点一下刷新就回来，删了没意义
                                    onDelete = { scanner.remove(device.address) },
                                )
                                if (index != lost.lastIndex) Spacer(Modifier.height(10.dp))
                            }
                        }

                        if (state.refreshing) {
                            Spacer(Modifier.height(12.dp))
                            ReadingHint(state.readingName)
                        }
                    }
                }

                if (state.devices.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    NexioText(
                        text = "只读取系统里已配对的设备，附近其他人的设备不会被扫进来。" +
                            "电量来自蓝牙标准的电量服务（0x180F），不支持该服务的设备" +
                            "（部分耳机走私有协议）会显示「未知」。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/** 汇总卡：已连接几台 / 其中最低电量 / 有多少历史记录 */
@Composable
private fun DeviceSummaryCard(devices: List<DeviceBattery>) {
    val colors = NexioTheme.colorScheme
    val connected = devices.filter { it.connected }
    val known = connected.filter { it.level != null }
    val lowest = known.minByOrNull { it.level ?: 100 }
    val lost = devices.size - connected.size

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
            SummaryMetric(label = "已连接", value = connected.size.toString())
            SummaryMetric(
                label = "最低电量",
                value = lowest?.level?.let { "$it%" } ?: "—",
                color = lowest?.level?.let { levelColor(it) } ?: colors.onSurfaceVariantSummary,
            )
            SummaryMetric(
                label = "已断开",
                value = lost.toString(),
                color = if (lost > 0) colors.onSurfaceVariantSummary else colors.onSurfaceContainer,
            )
        }
        if (connected.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            NexioText(
                text = "现在一台都没连上，下面显示的是上回读到过的电量。",
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
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

/** 单台设备：名称 + 状态 + 地址 + 电量条 + 最近更新时间；断开的历史多一个删除按钮 */
@Composable
private fun DeviceCard(device: DeviceBattery, onDelete: (() -> Unit)?) {
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
                if (onDelete != null) {
                    Spacer(Modifier.height(6.dp))
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "删除这条记录",
                        tint = BatteryRed.copy(alpha = 0.85f),
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onDelete)
                            .padding(5.dp),
                    )
                }
            }
        }
    }
}

/** 连接状态小胶囊：绿=现在连着，灰=只剩下历史 */
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

/** 电量条：和首页大电池同一套配色（低红 / 中黄 / 高绿），数字侧不加干扰 */
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
private fun RefreshingCard(state: DevicesState) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                NexioText(
                    text = state.readingName ?: "正在读取已配对设备…",
                    fontSize = 14.sp,
                    color = colors.onSurfaceContainer,
                )
                Spacer(Modifier.height(3.dp))
                NexioText(
                    text = "正在逐台连接读取电量，每台约 1~4 秒",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
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
private fun EmptyDeviceCard(refreshed: Boolean, onOpenSettings: () -> Unit) {
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
                text = if (refreshed) "没有读到任何设备" else "还没有设备电量",
                fontSize = 16.sp,
                color = colors.onSurfaceContainer,
            )
            Spacer(Modifier.height(6.dp))
            NexioText(
                text = "先在系统蓝牙里配对耳机 / 手环 / 手表，再回到这里刷新。" +
                    "只统计已配对的设备，不会扫描附近其他人的设备。",
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
            text = "打开蓝牙，让设备处于连接状态，再点右上角刷新。",
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
            text = "读取已配对设备的电量需要「附近的设备」权限。设备名与电量只保存在本机，不会上传。",
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

/** 最近一次读到电量的时间：刚才 / X 分钟前 / 具体时刻 */
private fun updateText(device: DeviceBattery): String {
    if (device.connected) {
        return if (device.level == null) "已连接 · 设备未提供电量" else "刚刚更新"
    }
    if (device.level == null) return "未读到电量"
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