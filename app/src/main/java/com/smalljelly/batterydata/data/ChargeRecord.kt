package com.smalljelly.batterydata.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次完整的充电记录（用户点“开始记录”后，从插上充电器到拔电的全过程）。
 *
 * 曲线是全过程按 5 秒采样、结算时降采样后的结果，供详情页画图。
 * 健康度只在电量涨幅够大（≥15%）时才有值：用这段时间充入的电量 ΔQ 除以电量增量 ΔSoC
 * 反推满电容量，再和设计容量比。一次充电只能得到一次测量，所以“掉了多少健康度”
 * 是拿它和上一条有健康度的记录比出来的。
 */
data class ChargeRecord(
    /** 唯一 id，用创建时间戳 */
    val id: Long,
    /** 记录归属的日期（毫秒），列表按它排序 */
    val dateMillis: Long,
    val fromLevel: Int,
    val toLevel: Int,
    /** 平均功率（W），无法统计时为 null */
    val avgPowerW: Float? = null,
    /** 充电损耗百分比（输入电能中未进入电池的部分），无法估算时为 null */
    val lossPercent: Float? = null,
    /** 充电时长（分钟） */
    val durationMinutes: Int? = null,
    /** 本次充入的电能（Wh） */
    val energyWh: Float? = null,
    /** 本次反推的满电容量（mAh） */
    val measuredCapacityMah: Float? = null,
    /** 本次测得的电池健康度（%，满电容量 ÷ 设计容量） */
    val healthPercent: Float? = null,
    /** 充电过程曲线（已降采样） */
    val powerSeries: List<Float> = emptyList(),
    val currentSeries: List<Float> = emptyList(),
    val voltageSeries: List<Float> = emptyList(),
    /** 每个曲线点相对开始时间的偏移毫秒，与三条 series 等长（旧记录为空） */
    val seriesTimesMs: List<Long> = emptyList(),
    /** 每个曲线点的温度℃，与 series 等长（旧记录为空） */
    val temperatureSeries: List<Float> = emptyList(),
    /** 本次峰值功率（W），旧记录可从 powerSeries 回推 */
    val peakPowerW: Float? = null,
    /** 记录类型：charge=充电 / discharge=放电 / test=耗电测试 */
    val recordType: String = "charge",
    /** 耗电测试的场景名，如"游戏""视频""待机" */
    val scenarioName: String? = null,
) {
    val hasSeries: Boolean get() = powerSeries.isNotEmpty() || voltageSeries.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("date", dateMillis)
        put("from", fromLevel)
        put("to", toLevel)
        avgPowerW?.let { put("power", it) }
        lossPercent?.let { put("loss", it) }
        durationMinutes?.let { put("duration", it) }
        energyWh?.let { put("energy", it) }
        measuredCapacityMah?.let { put("capacity", it) }
        healthPercent?.let { put("health", it) }
        if (powerSeries.isNotEmpty()) put("powerSeries", powerSeries.toJsonArray())
        if (currentSeries.isNotEmpty()) put("currentSeries", currentSeries.toJsonArray())
        if (voltageSeries.isNotEmpty()) put("voltageSeries", voltageSeries.toJsonArray())
        if (seriesTimesMs.isNotEmpty()) {
            put("seriesTimes", JSONArray().apply { seriesTimesMs.forEach { put(it) } })
        }
        if (temperatureSeries.isNotEmpty()) put("tempSeries", temperatureSeries.toJsonArray())
        peakPowerW?.let { put("peakPower", it) }
        if (recordType != "charge") put("type", recordType)
        scenarioName?.let { put("scenario", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): ChargeRecord = ChargeRecord(
            id = json.optLong("id"),
            dateMillis = json.optLong("date"),
            fromLevel = json.optInt("from"),
            toLevel = json.optInt("to"),
            avgPowerW = if (json.has("power")) json.optDouble("power").toFloat() else null,
            lossPercent = if (json.has("loss")) json.optDouble("loss").toFloat() else null,
            durationMinutes = if (json.has("duration")) json.optInt("duration") else null,
            energyWh = if (json.has("energy")) json.optDouble("energy").toFloat() else null,
            measuredCapacityMah = if (json.has("capacity")) json.optDouble("capacity").toFloat() else null,
            healthPercent = if (json.has("health")) json.optDouble("health").toFloat() else null,
            powerSeries = json.optJSONArray("powerSeries").toFloatList(),
            currentSeries = json.optJSONArray("currentSeries").toFloatList(),
            voltageSeries = json.optJSONArray("voltageSeries").toFloatList(),
            seriesTimesMs = json.optJSONArray("seriesTimes").toLongList(),
            temperatureSeries = json.optJSONArray("tempSeries").toFloatList(),
            peakPowerW = if (json.has("peakPower")) json.optDouble("peakPower").toFloat() else null,
            recordType = json.optString("type", "charge").ifEmpty { "charge" },
            scenarioName = json.optString("scenario").takeIf { it.isNotEmpty() },
        )
    }
}

private fun List<Float>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { array.put((it * 100).toInt() / 100.0) }
    return array
}

private fun JSONArray?.toFloatList(): List<Float> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            add(optDouble(i).toFloat())
        }
    }
}

private fun JSONArray?.toLongList(): List<Long> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            add(optLong(i))
        }
    }
}
