package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.smalljelly.batterydata.data.ScreenTimeStats
import java.util.Locale

/**
 * 今日耗电洞察：亮屏/息屏时长与掉电速率、今日充入百分点。
 * 尚无采样数据时给出友好提示。
 */
@Composable
fun BatteryInsightsCard(
    stats: ScreenTimeStats,
    modifier: Modifier = Modifier,
) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        NexioText(
            text = "今日耗电洞察",
            style = NexioTheme.textStyles.title3,
            color = colors.onSurfaceContainer,
        )
        Spacer(Modifier.height(14.dp))

        if (stats.sampleCount == 0) {
            NexioText(
                text = "开始使用后显示今日统计",
                fontSize = 13.sp,
                color = colors.onSurfaceVariantSummary,
            )
        } else {
            NexioText(
                text = "今日共耗电 ${format1(stats.totalDrainPercent)}%",
                fontSize = 13.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))

            InnerDivider()
            InfoRow("亮屏时长", formatDurationMs(stats.screenOnMs))
            InnerDivider()
            InfoRow("息屏时长", formatDurationMs(stats.screenOffMs))
            InnerDivider()
            InfoRow("亮屏掉电", "${format1(stats.screenOnDrainPerHour)} %/小时")
            InnerDivider()
            InfoRow("息屏掉电", "${format1(stats.screenOffDrainPerHour)} %/小时")
            InnerDivider()
            InfoRow("今日充入", "${format1(stats.chargePercent)}%")
        }
    }
}

private fun format1(value: Number): String =
    String.format(Locale.US, "%.1f", value.toFloat())

private fun formatDurationMs(ms: Number): String {
    val minutes = (ms.toLong() / 60_000L).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0 -> "${hours}小时${rest}分"
        rest > 0 -> "${rest}分"
        else -> "不足 1 分"
    }
}
