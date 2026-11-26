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
import com.smalljelly.batterydata.data.ChargeReport
import com.smalljelly.batterydata.data.ChargeSegment
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 充电报告：整体功率/温度概览 + 快充段、涓流段与各分段的用时与平均功率。
 * 缺失字段统一显示「—」。
 */
@Composable
fun ChargeReportCard(
    report: ChargeReport,
    modifier: Modifier = Modifier,
) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        NexioText(
            text = "充电报告",
            style = NexioTheme.textStyles.title3,
            color = colors.onSurfaceContainer,
        )
        Spacer(Modifier.height(14.dp))

        InfoRow("总时长", formatDurationMs(report.durationMs))
        InnerDivider()
        InfoRow("峰值功率", formatPower(report.peakPowerW))
        InnerDivider()
        InfoRow("平均功率", formatPower(report.avgPowerW))
        InnerDivider()
        InfoRow("温升", formatTemp(report.tempRiseC, signed = true))
        InnerDivider()
        InfoRow("最高温度", formatTemp(report.maxTempC))

        report.fastChargeSegment?.let { segment ->
            Spacer(Modifier.height(12.dp))
            SectionLabel("快充段")
            SegmentInfo(segment)
        }
        report.trickleSegment?.let { segment ->
            Spacer(Modifier.height(12.dp))
            SectionLabel("涓流段")
            SegmentInfo(segment)
        }

        if (report.segments.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionLabel("分段明细")
            report.segments.forEachIndexed { index, segment ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                NexioText(
                    text = segment.label,
                    fontSize = 13.sp,
                    color = colors.onSurfaceContainer,
                )
                Spacer(Modifier.height(2.dp))
                SegmentInfo(segment)
            }
        }
    }
}

/** 单个充电分段的用时 / 平均功率 / 峰值功率。 */
@Composable
private fun SegmentInfo(segment: ChargeSegment) {
    InfoRow("用时", formatDurationMs(segment.durationMs))
    InnerDivider()
    InfoRow("平均功率", formatPower(segment.avgPowerW))
    InnerDivider()
    InfoRow("峰值功率", formatPower(segment.peakPowerW))
}

private fun formatPower(value: Float?): String =
    value?.let { String.format(Locale.US, "%.1f W", it) } ?: "—"

private fun formatTemp(value: Float?, signed: Boolean = false): String =
    value?.let {
        val pattern = if (signed) "%+.1f" else "%.1f"
        String.format(Locale.US, "$pattern°C", it)
    } ?: "—"

private fun formatDurationMs(ms: Number): String {
    val minutes = (abs(ms.toLong()) / 60_000L).toInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0 -> "${hours}小时${rest}分"
        rest > 0 -> "${rest}分"
        else -> "不足 1 分"
    }
}
