package com.smalljelly.batterydata.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.haooz.nexioui.button.FlatTextButton
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.icon.NexioIcons
import com.haooz.nexioui.icon.basic.ArrowRight
import com.haooz.nexioui.scaffold.Scaffold
import com.haooz.nexioui.switch.Switch
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.nexioui.topappbar.CollapsibleTopAppBarDefaults
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.data.BatterySettings
import com.smalljelly.batterydata.data.ChargeRecorder
import com.smalljelly.batterydata.island.BatteryIslandHelper
import com.smalljelly.batterydata.shizuku.ShizukuManager
import com.smalljelly.batterydata.ui.components.AppPage
import com.smalljelly.batterydata.ui.components.SlideProgress
import com.smalljelly.batterydata.ui.components.rememberSlideProgress
import com.smalljelly.batterydata.ui.components.NexioIconButton
import com.smalljelly.batterydata.ui.components.NexioTopBar
import com.smalljelly.batterydata.ui.components.rememberNexioTopBarState
import com.smalljelly.batterydata.ui.components.rememberTopBarPull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「通知与显示」二级页：只放会直接影响通知栏和桌面显示的开关。
 *
 * 从设置一级页进来，长说明和授权引导留在这里，一级页保持干净。
 */
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    slideProgress: SlideProgress = rememberSlideProgress(true),
) {
    val context = LocalContext.current
    val colors = NexioTheme.colorScheme
    val scope = rememberCoroutineScope()
    val settings = remember { BatterySettings(context.applicationContext) }

    val islandSupported = remember { BatteryIslandHelper.isIslandSupported(context) }
    var islandEnabled by remember { mutableStateOf(settings.islandEnabled()) }
    var autoRecordEnabled by remember { mutableStateOf(settings.autoRecordEnabled()) }
    var overlayEnabled by remember { mutableStateOf(settings.overlayEnabled()) }
    var chargeNotice by remember { mutableStateOf(settings.chargeNoticeEnabled()) }

    // 启动悬浮窗并回查结果：部分 ROM 会给权限但拒绝窗口，失败就把开关复位，
    // 避免出现「开关显示已开启、实际没有悬浮窗」的假状态
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

    AppPage(
        title = "通知与显示",
        largeTitle = "通知与显示",
        onBack = onBack,
        slideProgress = slideProgress,
        horizontalPadding = 24.dp,
    ) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // 总开关：所有「插电才出现」的电量通知都归它管（超级岛 / 实况兜底）。
                    // 放在最上面，是因为它一关，下面的超级岛也就没有意义了。
                    ToggleRow(
                        title = "插电电量提醒",
                        summary = if (chargeNotice) {
                            "充电时在通知栏与超级岛显示电量 / 功率，关掉就一条都不弹"
                        } else {
                            "已关闭：单纯插着电不会再出现电量提醒与超级岛"
                        },
                        checked = chargeNotice,
                        onCheckedChange = { on ->
                            chargeNotice = on
                            settings.setChargeNoticeEnabled(on)
                            if (on) {
                                // 立刻按当前状态补一次：正插着电就马上把岛 / 通知挂回来
                                ChargeRecorder.ensure(context.applicationContext)
                                com.smalljelly.batterydata.data.ChargeKeepAliveJobService
                                    .schedule(context.applicationContext)
                            } else {
                                // 关掉就把已经挂在通知栏 / 岛上的那条收掉，不用等下次采样
                                BatteryIslandHelper.cancel(context)
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    ToggleRow(
                        title = "小米超级岛",
                        summary = when {
                            !islandSupported -> "当前设备不支持超级岛"
                            !chargeNotice -> "已被上面的「插电电量提醒」关闭"
                            else -> "充电时在顶部岛显示电量 / 功率"
                        },
                        checked = islandEnabled,
                        enabled = islandSupported && chargeNotice,
                        onCheckedChange = {
                            islandEnabled = it
                            settings.setIslandEnabled(it)
                            if (!it) {
                                BatteryIslandHelper.cancel(context)
                            } else {
                                // 开着岛且正插着电：立刻挂上保活服务，不等下次插电
                                ChargeRecorder.ensure(context.applicationContext)
                                // 排期充电 job：进程被杀后下次插电自动复活岛
                                com.smalljelly.batterydata.data.ChargeKeepAliveJobService
                                    .schedule(context.applicationContext)
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    ToggleRow(
                        title = "自动充电记录",
                        summary = "插电自动开始记录，拔电后保存",
                        checked = autoRecordEnabled,
                        onCheckedChange = {
                            autoRecordEnabled = it
                            settings.setAutoRecordEnabled(it)
                            ChargeRecorder.onAutoRecordSettingChanged(context.applicationContext, it)
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    ToggleRow(
                        title = "实时电量悬浮窗",
                        summary = if (overlayEnabled) {
                            "小胶囊显示在其他应用上方，点击展开详情"
                        } else {
                            "在其他应用上方显示电量 / 功率 / 温度"
                        },
                        checked = overlayEnabled,
                        onCheckedChange = { wanted ->
                            if (wanted && !android.provider.Settings.canDrawOverlays(context)) {
                                runCatching {
                                    overlayPermissionLauncher.launch(
                                        Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
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
            NexioText(
                text = "悬浮窗与超级岛都需要保持应用在后台运行；系统回收进程后，下次打开应用或插电会自动恢复。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(40.dp))
    }
}

/**
 * 「后台与权限」二级页：Shizuku 授权、电池优化白名单、自启动引导。
 *
 * 这些操作要跳系统页面，长文案也集中在这里，一级设置页只留一句摘要。
 */
@Composable
fun BackgroundSettingsScreen(
    onBack: () -> Unit,
    slideProgress: SlideProgress = rememberSlideProgress(true),
) {
    val context = LocalContext.current
    val colors = NexioTheme.colorScheme
    val scope = rememberCoroutineScope()

    val shizukuRunning = remember { mutableStateOf(ShizukuManager.isShizukuRunning()) }
    val shizukuAuthorized = remember { mutableStateOf(ShizukuManager.checkSelfPermission()) }

    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    var ignoringBattery by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    // 从系统设置页返回时刷新状态，避免刚授权回来还显示「未忽略」
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery =
                    powerManager.isIgnoringBatteryOptimizations(context.packageName)
                shizukuRunning.value = ShizukuManager.isShizukuRunning()
                shizukuAuthorized.value = ShizukuManager.checkSelfPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppPage(
        title = "后台与权限",
        largeTitle = "后台与权限",
        onBack = onBack,
        slideProgress = slideProgress,
        horizontalPadding = 24.dp,
    ) {

            // Shizuku：只用于超级岛通知放行 XMSF 网络和系统白名单，不参与电池数据读取
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
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
                Spacer(Modifier.height(8.dp))
                NexioText(
                    text = "Shizuku 只用于放行超级岛网络与加入系统白名单，电池数据仍由系统接口读取。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }

            Spacer(Modifier.height(12.dp))
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
                        text = "插电时应用会挂前台服务持续刷新通知，拔电即停。系统划掉后台仍可能杀进程，建议配好下面的白名单。",
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
                                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
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
                        text = "在打开的页面里允许自启动、省电策略选「无限制」；再到最近任务里长按本应用卡片加锁。",
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
                                        ShizukuManager.applyKeepAliveWhitelist(
                                            context.applicationContext,
                                        )
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
            Spacer(Modifier.height(40.dp))
    }
}

/**
 * 二级页 / 一级页共用的开关行：标题 + 一句摘要 + 右侧开关。
 *
 * 整行可点：系统设置里这类开关行也是"点哪儿都能切"，只让 40dp 的开关本身可点太小了，
 * 尤其是左侧文案占了绝大部分面积却点不动。开关自己也带点击，两处同向切换不会互相冲突。
 */
@Composable
internal fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = NexioTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = { onCheckedChange(!checked) },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            NexioText(text = title, fontSize = 16.sp, color = colors.onSurfaceContainer)
            NexioText(text = summary, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** 入口行：标题 + 摘要 + 右箭头。设置一级页用它做分组导航。 */
@Composable
internal fun EntryRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    val colors = NexioTheme.colorScheme
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
            modifier = Modifier.size(12.dp),
            tint = colors.onSurfaceVariantSummary,
        )
    }
}

private fun openAutoStartSettings(context: Context) {
    val intents = listOf(
        Intent().setComponent(
            android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
            Uri.parse("package:${context.packageName}"),
        ),
    )
    for (intent in intents) {
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
            return
        }
    }
    Toast.makeText(context, "未找到可打开的自启动设置", Toast.LENGTH_SHORT).show()
}