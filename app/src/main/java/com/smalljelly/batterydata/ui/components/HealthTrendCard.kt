package com.smalljelly.batterydata.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haooz.nexioui.card.Card
import com.haooz.nexioui.text.NexioText
import com.haooz.nexioui.theme.NexioTheme
import com.smalljelly.batterydata.data.HealthTrendPoint
import com.smalljelly.batterydata.ui.theme.ChartBlue
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 健康度趋势折线图：横轴按时间顺序，纵轴为健康度百分比。
 * 标注首末值与有效点数；不足两个点时给出友好提示。
 */
@Composable
fun HealthTrendCard(
    points: List<HealthTrendPoint>,
    modifier: Modifier = Modifier,
) {
    val colors = NexioTheme.colorScheme
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NexioText(
                text = "健康度趋势",
                style = NexioTheme.textStyles.title3,
                color = colors.onSurfaceContainer,
            )
        }
        Spacer(Modifier.height(14.dp))

        if (points.size < 2) {
            NexioText(
                text = "完成 2 次以上有效充电后显示趋势",
                fontSize = 13.sp,
                color = colors.onSurfaceVariantSummary,
            )
        } else {
            val first = points.first().healthPercent
            val last = points.last().healthPercent
            Row(Modifier.fillMaxWidth()) {
                NexioText(
                    text = "首次 ${formatHealth(first)}%",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.weight(1f))
                NexioText(
                    text = "最新 ${formatHealth(last)}%  ·  ${points.size} 个点",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(10.dp))

            HealthLineChart(values = points.map { it.healthPercent })
        }
        Spacer(Modifier.height(12.dp))
        NexioText(
            text = "趋势基于历史充电数据估算，仅供参考；数值波动不一定代表电池实际衰减。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun HealthLineChart(values: List<Float>) {
    val colors = NexioTheme.colorScheme
    val lowHigh = healthRange(values)

    Row(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .weight(1f)
                .height(168.dp),
        ) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))
            // 横向虚线网格，与 LiveChartCard 保持一致
            for (i in 0..2) {
                val y = size.height * i / 2f
                drawLine(
                    color = colors.dividerLine,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }

            val stepX = size.width / (values.size - 1).coerceAtLeast(1)
            val offsets = values.mapIndexed { index, value ->
                val ratio = ((value - lowHigh.first) / (lowHigh.second - lowHigh.first)).coerceIn(0f, 1f)
                Offset(index * stepX, size.height - ratio * size.height)
            }

            val linePath = Path().apply {
                moveTo(offsets[0].x, offsets[0].y)
                for (i in 1 until offsets.size) {
                    val previous = offsets[i - 1]
                    val current = offsets[i]
                    val midX = (previous.x + current.x) / 2f
                    cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
                }
            }
            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(offsets.last().x, size.height)
                lineTo(0f, size.height)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(ChartBlue.copy(alpha = 0.24f), ChartBlue.copy(alpha = 0.01f)),
                ),
            )
            drawPath(
                path = linePath,
                color = ChartBlue,
                style = Stroke(
                    width = 2.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // 首末点强调，沿用 LiveChartCard 的“底色圆 + 主色圆”画法
            for (point in listOf(offsets.first(), offsets.last())) {
                drawCircle(color = colors.surfaceContainer, radius = 5.dp.toPx(), center = point)
                drawCircle(color = ChartBlue, radius = 3.dp.toPx(), center = point)
            }
        }

        Spacer(Modifier.width(46.dp))
        Box(modifier = Modifier.width(46.dp).height(168.dp)) {
            HealthAxisLabel(
                text = formatHealth(lowHigh.second),
                modifier = Modifier.align(Alignment.TopEnd).offset(y = (-7).dp),
            )
            HealthAxisLabel(
                text = formatHealth((lowHigh.first + lowHigh.second) / 2f),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            HealthAxisLabel(
                text = formatHealth(lowHigh.first),
                modifier = Modifier.align(Alignment.BottomEnd).offset(y = 7.dp),
            )
        }
    }
}

@Composable
private fun HealthAxisLabel(text: String, modifier: Modifier = Modifier) {
    NexioText(
        text = text,
        fontSize = 11.sp,
        color = NexioTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.End,
        modifier = modifier.fillMaxWidth(),
    )
}

private fun healthRange(values: List<Float>): Pair<Float, Float> {
    val min = values.min()
    val max = values.max()
    val span = (max - min).coerceAtLeast(4f)
    val center = (max + min) / 2f
    val padding = span * 0.18f
    val low = floor((center - span / 2f - padding) / 2f) * 2f
    val high = ceil((center + span / 2f + padding) / 2f) * 2f
    return low to high
}

private fun formatHealth(value: Float): String = String.format(Locale.US, "%.1f", value)
