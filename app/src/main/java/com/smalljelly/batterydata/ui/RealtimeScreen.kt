package com.smalljelly.batterydata.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.haooz.softglass.SoftGlassTheme
import com.haooz.softglass.backdrop.backdrops.layerBackdrop
import com.haooz.softglass.backdrop.backdrops.rememberLayerBackdrop
import com.smalljelly.batterydata.data.BatteryRepository
import com.smalljelly.batterydata.data.BatterySample
import com.smalljelly.batterydata.data.formatEta
import com.smalljelly.batterydata.ui.components.BatteryHeroCard
import com.smalljelly.batterydata.ui.components.LiveChartCard
import com.smalljelly.batterydata.ui.components.NexioTopBar
import com.smalljelly.batterydata.ui.components.NexioTopContentSpacer
import com.smalljelly.batterydata.ui.components.RealtimeStatsCard
import com.smalljelly.batterydata.ui.components.rememberNexioTopBarState
import com.smalljelly.batterydata.ui.theme.AppMaterial
import java.util.Locale

/**
 * 「实时」页：一屏看完当前电量、四项实时读数和最近曲线。
 *
 * 这里不放菜单、不放设置入口——那些都在「设置」页，
 * 首屏只保留实时读数，避免顶栏图标弹出和杂物堆叠。
 */
@Composable
fun RealtimeScreen() {
    val context = LocalContext.current
    val colors = NexioTheme.colorScheme
    val repository = remember { BatteryRepository(context.applicationContext) }
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    var sample by remember { mutableStateOf(repository.snapshot()) }
    val currentHistory = remember { mutableStateListOf<Float>() }
    val voltageHistory = remember { mutableStateListOf<Float>() }
    // 亮屏状态下的放电电流样本，只用来估「可亮屏时长」
    val screenOnCurrents = remember { mutableStateListOf<Float>() }

    LaunchedEffect(Unit) {
        repository.stream(REFRESH_INTERVAL_MS).collect { latest ->
            sample = latest
            latest.currentMa?.let { currentHistory.append(it) }
            if (latest.voltageMv > 0) voltageHistory.append(latest.voltageMv / 1_000f)
            val discharge = latest.currentMa
            if (!latest.isPlugged && discharge != null && discharge < 0f && powerManager.isInteractive) {
                screenOnCurrents.append(-discharge, SCREEN_ON_WINDOW)
            }
        }
    }

    val scrollState = rememberScrollState()
    val topBarState = rememberNexioTopBarState()
    val backdrop = rememberLayerBackdrop()
    val pageBackdrop = rememberLayerBackdrop()

    SoftGlassTheme(
        level = AppMaterial.current.glassLevel,
        isDark = isSystemInDarkTheme(),
        hasWallpaper = false,
    ) {
        Box(Modifier.fillMaxSize().layerBackdrop(pageBackdrop)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
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
                    BatteryHeroCard(
                        sample = sample,
                        etaText = etaText(sample, screenOnCurrents),
                    )
                    Spacer(Modifier.height(12.dp))
                    RealtimeStatsCard(sample = sample)
                    Spacer(Modifier.height(12.dp))
                    LiveChartCard(
                        currentHistory = currentHistory,
                        voltageHistory = voltageHistory,
                        intervalMs = REFRESH_INTERVAL_MS,
                    )
                    Spacer(Modifier.height(12.dp))
                    NexioText(
                        text = "数据每 10 秒从系统 BatteryManager 与 power_supply 节点读取",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = colors.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 悬浮底栏不占 Scaffold 的 bottomBar，这里手动留出栏高
                    Spacer(Modifier.height(28.dp + 76.dp))
                }
            }

            // 顶栏只有标题：不再挂菜单按钮，切页与设置都在底栏
            NexioTopBar(
                state = topBarState,
                title = "实时",
                largeTitle = "实时状态",
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/** 大电池卡下面那行说明：充满 / 充电预计 / 可用时长 / 温度，按可用信息逐级退回。 */
internal fun etaText(sample: BatterySample, screenOnCurrents: List<Float>): String {
    val temperature = sample.temperatureC?.let { String.format(Locale.US, "%.1f °C", it) }
    return when {
        sample.isFull -> "已充满，建议断开充电器"
        sample.isCharging -> formatEta(sample.etaMinutes)?.let { "预计 $it 后充满" }
            ?: temperature?.let { "电池温度 $it" }
            ?: "剩余电量 ${sample.level}%"
        !sample.isPlugged -> formatEta(estimateScreenOnMinutes(sample, screenOnCurrents))
            ?.let { "预计可用 $it" }
            ?: temperature?.let { "电池温度 $it，状态${sample.healthText}" }
            ?: "剩余电量 ${sample.level}%"
        temperature != null -> "电池温度 $temperature，状态${sample.healthText}"
        else -> "剩余电量 ${sample.level}%"
    }
}

/** 剩余电量（mAh）：优先用库仑计，读不到就按「电量% × 满电容量」反推 */
internal fun remainingMah(sample: BatterySample): Float? {
    sample.chargeCounterMah?.takeIf { it > 100f }?.let { return it }
    val full = sample.estimatedFullChargeMah ?: sample.designCapacityMah ?: return null
    if (full <= 0f) return null
    return full * sample.level / 100f
}

/**
 * 亮屏时长估算：剩余电量 ÷ 亮屏平均放电电流。
 * 只用屏幕亮着时的放电样本，样本不足时退回典型亮屏电流。
 */
internal fun estimateScreenOnMinutes(
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

internal fun MutableList<Float>.append(value: Float, cap: Int = HISTORY_SIZE) {
    if (size >= cap) removeAt(0)
    add(value)
}