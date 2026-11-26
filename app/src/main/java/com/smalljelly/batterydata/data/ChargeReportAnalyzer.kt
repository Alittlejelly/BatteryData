package com.smalljelly.batterydata.data

import kotlin.math.roundToInt

/** 充电过程中的一个区间：电量范围、时长与功率概览。 */
data class ChargeSegment(
    val label: String,
    val fromLevel: Int,
    val toLevel: Int,
    val durationMs: Long,
    val avgPowerW: Float?,
    val peakPowerW: Float?,
)

/** 单次充电报告：基础指标 + 快充/涓流段 + 三段概览。 */
data class ChargeReport(
    val durationMs: Long = 0L,
    val peakPowerW: Float? = null,
    val avgPowerW: Float? = null,
    val tempRiseC: Float? = null,
    val maxTempC: Float? = null,
    val fastChargeSegment: ChargeSegment? = null,   // 20%→80%
    val trickleSegment: ChargeSegment? = null,      // 80%→100%
    val segments: List<ChargeSegment> = emptyList(),
)

/** 健康度趋势上的一个点。 */
data class HealthTrendPoint(
    val dateMillis: Long,
    val healthPercent: Float,
    val measuredCapacityMah: Float?,
)

/** 充电分段报告与电池健康趋势分析（纯计算，仅依赖 Kotlin 标准库）。 */
object ChargeReportAnalyzer {

    /**
     * 分析一次充电：优先使用记录中已结算的标量，曲线缺失时退化为仅基础字段的报告。
     * 分段用「索引进度近似电量进度」：把 [fromLevel, toLevel] 线性映射到曲线下标 0..last。
     */
    fun analyze(record: ChargeRecord): ChargeReport {
        val totalDurationMs = record.seriesTimesMs.lastOrNull()
            ?: record.durationMinutes?.toLong()?.times(60_000L)
            ?: 0L

        val powerSeries = record.powerSeries
        val finitePower = powerSeries.filter { it.isFinite() }
        val peakPowerW = record.peakPowerW ?: finitePower.maxOrNull()
        val avgPowerW = record.avgPowerW
            ?: finitePower.takeIf { it.isNotEmpty() }?.average()?.toFloat()

        val tempSeries = record.temperatureSeries
        val tempRiseC = if (tempSeries.isNotEmpty()) {
            val first = tempSeries.first()
            val last = tempSeries.last()
            if (first.isFinite() && last.isFinite()) last - first else null
        } else null
        val maxTempC = tempSeries.filter { it.isFinite() }.maxOrNull()

        // 曲线为空或电量区间无效时，没有可切分的段
        if (powerSeries.isEmpty() || record.fromLevel >= record.toLevel) {
            return ChargeReport(
                durationMs = totalDurationMs,
                peakPowerW = peakPowerW,
                avgPowerW = avgPowerW,
                tempRiseC = tempRiseC,
                maxTempC = maxTempC,
            )
        }

        val from = record.fromLevel
        val to = record.toLevel
        val lastIndex = powerSeries.lastIndex

        fun indexOfLevel(level: Int): Int =
            ((level - from).toFloat() / (to - from) * lastIndex)
                .roundToInt().coerceIn(0, lastIndex)

        fun segment(label: String, rawLo: Int, rawHi: Int): ChargeSegment? {
            val lo = rawLo.coerceIn(from, to)
            val hi = rawHi.coerceIn(from, to)
            if (lo >= hi) return null
            val start = indexOfLevel(lo)
            val end = indexOfLevel(hi).coerceAtLeast(start)
            val slice = powerSeries.subList(start, end + 1).filter { it.isFinite() }
            val times = record.seriesTimesMs
            val durationMs = if (times.size > end) {
                (times[end] - times[start]).coerceAtLeast(0L)
            } else {
                val fraction = (end - start + 1).toFloat() / powerSeries.size
                (totalDurationMs * fraction).toLong()
            }
            return ChargeSegment(
                label = label,
                fromLevel = lo,
                toLevel = hi,
                durationMs = durationMs,
                avgPowerW = slice.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                peakPowerW = slice.maxOrNull(),
            )
        }

        // 快充段 20%→80%，涓流段 80%→终点（终点不超过 80 时无涓流）
        val fastLo = maxOf(from, 20)
        val fastHi = minOf(to, 80)
        val fastChargeSegment = segment("$fastLo% → $fastHi%", fastLo, fastHi)

        val trickleLo = maxOf(from, 80)
        val trickleSegment = if (to > 80) segment("80% → 100%", trickleLo, to) else null

        // 三段概览互斥覆盖 [from, to]：起始段、快充段、末段
        val startEnd = minOf(from + 20, 80).coerceAtMost(to)
        val midStart = startEnd
        val midEnd = minOf(to, 80)
        val segments = listOfNotNull(
            segment("起始段", from, startEnd),
            segment("快充段", midStart, midEnd),
            segment("末段", midEnd, to),
        )

        return ChargeReport(
            durationMs = totalDurationMs,
            peakPowerW = peakPowerW,
            avgPowerW = avgPowerW,
            tempRiseC = tempRiseC,
            maxTempC = maxTempC,
            fastChargeSegment = fastChargeSegment,
            trickleSegment = trickleSegment,
            segments = segments,
        )
    }

    /** 健康度趋势：只取充电与带健康度的测试记录，按日期升序，剔除越界异常值。 */
    fun healthTrend(records: List<ChargeRecord>): List<HealthTrendPoint> =
        records
            .filter {
                it.healthPercent != null &&
                    (it.recordType.equals("charge", ignoreCase = true) ||
                        it.recordType.equals("test", ignoreCase = true))
            }
            .mapNotNull { record ->
                val health = record.healthPercent ?: return@mapNotNull null
                if (!health.isFinite() || health < 40f || health > 105f) return@mapNotNull null
                HealthTrendPoint(record.dateMillis, health, record.measuredCapacityMah)
            }
            .sortedBy { it.dateMillis }
}
