package com.smalljelly.batterydata.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.smalljelly.batterydata.data.BatteryRepository
import com.smalljelly.batterydata.data.BatterySample
import com.smalljelly.batterydata.data.ChargeRecord
import com.smalljelly.batterydata.data.ChargeRecordStore
import com.smalljelly.batterydata.data.formatEta
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 给**小米小部件（主题格式）**读数据用的只读 ContentProvider。
 *
 * 和小部件那一侧的关系和 Nexio 课表一致：小部件自己跑在桌面/主题引擎里，没有我们的
 * 代码，只能靠 `ContentProviderBinder` 按 uri + columns 拉一张表。所以这里把「电池现在
 * 什么样」和「历史充电记录」压成几张**扁平的表**，列名固定、值都是基本类型。
 *
 * ```
 * content://com.smalljelly.batterydata.widget/live      当前电池/充电状态（1 行）
 * content://com.smalljelly.batterydata.widget/last      最近一次充电记录（0 或 1 行）
 * content://com.smalljelly.batterydata.widget/today     今天/累计统计（1 行）
 * content://com.smalljelly.batterydata.widget/records   全部充电记录（N 行，最新在前）
 * ```
 *
 * 小部件 XML 里的写法（示例见仓库外的「电池数据」主题包）：
 *
 * ```xml
 * <ContentProviderBinder name="getBattery" uri="content://com.smalljelly.batterydata.widget/live"
 *     columns="level,status_text,power_w,temperature_w,eta_text" countName="hasBattery">
 *     <Variable name="_level"    type="int"    column="level" />
 *     <Variable name="_status"   type="string" column="status_text" />
 * </ContentProviderBinder>
 * ```
 *
 * 约定（和小部件表达式配合，别轻易改）：
 * - **数值列一律是 Int / Long，不是 Float**。`MatrixCursor.getInt()` 遇到 Float 会抛
 *   `ClassCastException`，而小部件那边是按 XML 里的 `type="int"` 取值的。
 *   所以小数用「放大整数」：`temperature_x10`=312 表示 31.2℃，`power_x100`=1860 表示 18.6W。
 * - **没有数据不给 null**，给 0 或空串，小部件用 `ifelse(#x==0,'--',...)` 判空即可。
 * - 只支持全表查询：`selection` / `selectionArgs` / `sortOrder` 一律忽略（记录本来就不多）。
 */
class BatteryWidgetProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val match = uriMatcher.match(uri)
        require(match != UriMatcher.NO_MATCH) { "Unsupported URI: $uri" }
        val ctx = requireNotNull(context) { "Provider not attached" }

        val defaultColumns = when (match) {
            LIVE -> LIVE_COLUMNS
            LAST -> LAST_COLUMNS
            TODAY -> TODAY_COLUMNS
            else -> RECORD_COLUMNS
        }
        val columns = projection?.toList()?.takeIf { it.isNotEmpty() } ?: defaultColumns
        require(columns.all { it in defaultColumns }) { "Unsupported column requested: $columns" }

        val rows: List<Map<String, Any>> = when (match) {
            LIVE -> listOf(liveRow(BatteryRepository(ctx).snapshot()))
            LAST -> ChargeRecordStore(ctx).all().firstOrNull()?.let { listOf(lastRow(it)) } ?: emptyList()
            TODAY -> listOf(todayRow(ChargeRecordStore(ctx).all()))
            else -> ChargeRecordStore(ctx).all().mapIndexed { index, record -> recordRow(index, record) }
        }

        val cursor = MatrixCursor(columns.toTypedArray(), rows.size)
        rows.forEach { values ->
            val row = cursor.newRow()
            columns.forEach { column -> row.add(values[column]) }
        }
        cursor.setNotificationUri(
            ctx.contentResolver,
            Uri.parse("content://${authority(ctx)}/${matchPath(match)}"),
        )
        return cursor
    }

    override fun getType(uri: Uri): String = when (uriMatcher.match(uri)) {
        LIVE -> "vnd.android.cursor.item/vnd.com.smalljelly.batterydata.widget.live"
        LAST, TODAY -> "vnd.android.cursor.item/vnd.com.smalljelly.batterydata.widget.summary"
        RECORDS -> "vnd.android.cursor.dir/vnd.com.smalljelly.batterydata.widget.record"
        else -> throw IllegalArgumentException("Unsupported URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = readOnly()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = readOnly()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = readOnly()

    private fun <T> readOnly(): T =
        throw UnsupportedOperationException("Battery widget provider is read-only")

    // ------------------------------------------------------------------ 行构造

    private fun liveRow(s: BatterySample): Map<String, Any> {
        val currentMa = s.currentMa ?: 0f
        val powerW = s.powerW ?: 0f
        val eta = s.etaMinutes
        return mapOf(
            COL_LEVEL to s.level,
            COL_STATUS to s.status,
            COL_STATUS_TEXT to s.statusText,
            COL_PLUGGED to s.plugged,
            COL_PLUGGED_TEXT to s.pluggedText,
            COL_IS_CHARGING to if (s.isCharging) 1 else 0,
            COL_IS_PLUGGED to if (s.isPlugged) 1 else 0,
            COL_VOLTAGE_MV to s.voltageMv,
            COL_VOLTAGE_X100 to (s.voltageMv / 10f).roundToInt(),
            COL_CURRENT_MA to currentMa.roundToInt(),
            COL_CURRENT_X10 to (abs(currentMa) / 100f).roundToInt(),
            COL_CURRENT_SIGNED to if (currentMa >= 0f) 1 else 0,
            COL_POWER_X100 to (abs(powerW) * 100f).roundToInt(),
            COL_POWER_TEXT to if (powerW > 0f) String.format(Locale.CHINA, "%.1f", powerW) else "",
            COL_TEMPERATURE_X10 to ((s.temperatureC ?: 0f) * 10f).roundToInt(),
            COL_TEMPERATURE_TEXT to (s.temperatureC?.let { String.format(Locale.CHINA, "%.1f", it) } ?: ""),
            COL_TECHNOLOGY to s.technology,
            COL_CHARGE_COUNTER to (s.chargeCounterMah ?: 0f).roundToInt(),
            COL_FULL_CHARGE to (s.estimatedFullChargeMah ?: 0f).roundToInt(),
            COL_DESIGN_CAPACITY to (s.designCapacityMah ?: 0f).roundToInt(),
            COL_HEALTH_PERCENT to (s.healthPercent ?: 0f).roundToInt(),
            COL_HEALTH_TEXT to s.healthText,
            COL_CYCLE_COUNT to (s.cycleCount ?: -1),
            COL_ETA_MINUTES to (eta ?: 0),
            COL_ETA_TEXT to (formatEta(eta) ?: ""),
            COL_UPDATED_AT to s.updatedAt,
        )
    }

    private fun lastRow(record: ChargeRecord): Map<String, Any> = mapOf(
        COL_RECORD_ID to record.id,
        COL_DATE_MILLIS to record.dateMillis,
        COL_DATE_TEXT to dateFormat().format(Date(record.dateMillis)),
        COL_FROM_LEVEL to record.fromLevel,
        COL_TO_LEVEL to record.toLevel,
        COL_ADDED_PERCENT to (record.toLevel - record.fromLevel).coerceAtLeast(0),
        COL_DURATION_MINUTES to (record.durationMinutes ?: 0),
        COL_AVG_POWER_X100 to ((record.avgPowerW ?: 0f) * 100f).roundToInt(),
        COL_AVG_POWER_TEXT to (record.avgPowerW?.let { String.format(Locale.CHINA, "%.1f", it) } ?: ""),
        COL_ENERGY_X100 to ((record.energyWh ?: 0f) * 100f).roundToInt(),
        COL_LOSS_PERCENT to (record.lossPercent ?: 0f).roundToInt(),
        COL_MEASURED_CAPACITY to (record.measuredCapacityMah ?: 0f).roundToInt(),
        COL_RECORD_HEALTH to (record.healthPercent ?: 0f).roundToInt(),
        COL_HAS_SERIES to if (record.hasSeries) 1 else 0,
        COL_PEAK_POWER_X100 to (record.powerSeries.maxOrNull()?.times(100f)?.roundToInt() ?: 0),
    )

    private fun todayRow(all: List<ChargeRecord>): Map<String, Any> {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = all.filter { it.dateMillis >= startOfToday }
        return mapOf(
            COL_TODAY_SESSIONS to today.size,
            COL_TODAY_MINUTES to today.sumOf { it.durationMinutes ?: 0 },
            COL_TODAY_ENERGY_X100 to (today.sumOf { (it.energyWh ?: 0f).toDouble() } * 100).roundToInt(),
            COL_TODAY_ADDED_PERCENT to today.sumOf { (it.toLevel - it.fromLevel).coerceAtLeast(0) },
            COL_TOTAL_SESSIONS to all.size,
            COL_LAST_DATE_TEXT to (all.firstOrNull()?.let { dateFormat().format(Date(it.dateMillis)) } ?: ""),
        )
    }

    private fun recordRow(index: Int, record: ChargeRecord): Map<String, Any> = mapOf(
        COL_INDEX to index,
        COL_RECORD_ID to record.id,
        COL_DATE_MILLIS to record.dateMillis,
        COL_FROM_LEVEL to record.fromLevel,
        COL_TO_LEVEL to record.toLevel,
        COL_ADDED_PERCENT to (record.toLevel - record.fromLevel).coerceAtLeast(0),
        COL_DURATION_MINUTES to (record.durationMinutes ?: 0),
        COL_AVG_POWER_X100 to ((record.avgPowerW ?: 0f) * 100f).roundToInt(),
        COL_ENERGY_X100 to ((record.energyWh ?: 0f) * 100f).roundToInt(),
        COL_RECORD_HEALTH to (record.healthPercent ?: 0f).roundToInt(),
    )

    // ------------------------------------------------------------------ 通知

    companion object {
        /** 授权名后缀：完整授权是 `<包名>.widget`（小部件 XML 里要一字不差） */
        const val AUTHORITY_SUFFIX = ".widget"

        fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

        /** 记录新增/删除后调一次，小部件下次刷新就能拿到新数据 */
        fun notifyChanged(context: Context) {
            val resolver = context.contentResolver
            val authority = authority(context)
            PATHS.forEach { path ->
                resolver.notifyChange(Uri.parse("content://$authority/$path"), null)
            }
        }

        private const val PATH_LIVE = "live"
        private const val PATH_LAST = "last"
        private const val PATH_TODAY = "today"
        private const val PATH_RECORDS = "records"

        private val PATHS = listOf(PATH_LIVE, PATH_LAST, PATH_TODAY, PATH_RECORDS)

        private const val LIVE = 1
        private const val LAST = 2
        private const val TODAY = 3
        private const val RECORDS = 4

        private fun matchPath(match: Int): String = when (match) {
            LIVE -> PATH_LIVE
            LAST -> PATH_LAST
            TODAY -> PATH_TODAY
            else -> PATH_RECORDS
        }

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI("com.smalljelly.batterydata" + AUTHORITY_SUFFIX, PATH_LIVE, LIVE)
            addURI("com.smalljelly.batterydata" + AUTHORITY_SUFFIX, PATH_LAST, LAST)
            addURI("com.smalljelly.batterydata" + AUTHORITY_SUFFIX, PATH_TODAY, TODAY)
            addURI("com.smalljelly.batterydata" + AUTHORITY_SUFFIX, PATH_RECORDS, RECORDS)
        }

        private fun dateFormat(): SimpleDateFormat = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

        // —— /live：当前状态 ——
        const val COL_LEVEL = "level"                       // int  0..100
        const val COL_STATUS = "status"                     // int  BatteryManager.STATUS_*
        const val COL_STATUS_TEXT = "status_text"           // string 充电中/放电中/已充满…
        const val COL_PLUGGED = "plugged"                   // int  BatteryManager.BATTERY_PLUGGED_*
        const val COL_PLUGGED_TEXT = "plugged_text"         // string 有线充电器/USB 供电…
        const val COL_IS_CHARGING = "is_charging"           // int  0/1
        const val COL_IS_PLUGGED = "is_plugged"             // int  0/1
        const val COL_VOLTAGE_MV = "voltage_mv"             // int  mV
        const val COL_VOLTAGE_X100 = "voltage_x100"         // int  0.01V（4200mV → 420）
        const val COL_CURRENT_MA = "current_ma"             // int  mA，充电为正
        const val COL_CURRENT_X10 = "current_x10"           // int  0.1A 绝对值（1860mA → 18）
        const val COL_CURRENT_SIGNED = "current_signed"     // int  1=充电 0=放电
        const val COL_POWER_X100 = "power_x100"             // int  0.01W 绝对值（1860 → 18.6W）
        const val COL_POWER_TEXT = "power_text"             // string "18.6"，无数据为 ""
        const val COL_TEMPERATURE_X10 = "temperature_x10"   // int  0.1℃（312 → 31.2℃）
        const val COL_TEMPERATURE_TEXT = "temperature_text" // string "31.2"
        const val COL_TECHNOLOGY = "technology"             // string Li-ion…
        const val COL_CHARGE_COUNTER = "charge_counter"     // int  mAh 当前电量计数
        const val COL_FULL_CHARGE = "full_charge"           // int  mAh 满电估计
        const val COL_DESIGN_CAPACITY = "design_capacity"   // int  mAh 设计容量
        const val COL_HEALTH_PERCENT = "health_percent"     // int  0..100
        const val COL_HEALTH_TEXT = "health_text"           // string 良好/过热…
        const val COL_CYCLE_COUNT = "cycle_count"           // int  -1 表示不可用
        const val COL_ETA_MINUTES = "eta_minutes"           // int  0 表示不可用
        const val COL_ETA_TEXT = "eta_text"                 // string "1 小时 12 分"
        const val COL_UPDATED_AT = "updated_at"             // long 采样时间戳

        // —— /last /records：充电记录 ——
        const val COL_INDEX = "idx"                         // int  仅 /records，0 = 最新
        const val COL_RECORD_ID = "record_id"               // long
        const val COL_DATE_MILLIS = "date_millis"           // long
        const val COL_DATE_TEXT = "date_text"               // string "11月20日 13:40"（仅 /last）
        const val COL_FROM_LEVEL = "from_level"             // int
        const val COL_TO_LEVEL = "to_level"                 // int
        const val COL_ADDED_PERCENT = "added_percent"       // int
        const val COL_DURATION_MINUTES = "duration_minutes" // int
        const val COL_AVG_POWER_X100 = "avg_power_x100"     // int  0.01W
        const val COL_AVG_POWER_TEXT = "avg_power_text"     // string（仅 /last）
        const val COL_PEAK_POWER_X100 = "peak_power_x100"   // int  0.01W（仅 /last）
        const val COL_ENERGY_X100 = "energy_x100"           // int  0.01Wh
        const val COL_LOSS_PERCENT = "loss_percent"         // int（仅 /last）
        const val COL_MEASURED_CAPACITY = "measured_capacity" // int mAh（仅 /last）
        const val COL_RECORD_HEALTH = "record_health"       // int %（仅 /last /records）
        const val COL_HAS_SERIES = "has_series"             // int 0/1（仅 /last）

        // —— /today：统计 ——
        const val COL_TODAY_SESSIONS = "today_sessions"     // int
        const val COL_TODAY_MINUTES = "today_minutes"       // int
        const val COL_TODAY_ENERGY_X100 = "today_energy_x100" // int 0.01Wh
        const val COL_TODAY_ADDED_PERCENT = "today_added_percent" // int
        const val COL_TOTAL_SESSIONS = "total_sessions"     // int
        const val COL_LAST_DATE_TEXT = "last_date_text"     // string

        val LIVE_COLUMNS = listOf(
            COL_LEVEL, COL_STATUS, COL_STATUS_TEXT, COL_PLUGGED, COL_PLUGGED_TEXT,
            COL_IS_CHARGING, COL_IS_PLUGGED, COL_VOLTAGE_MV, COL_VOLTAGE_X100,
            COL_CURRENT_MA, COL_CURRENT_X10, COL_CURRENT_SIGNED, COL_POWER_X100, COL_POWER_TEXT,
            COL_TEMPERATURE_X10, COL_TEMPERATURE_TEXT, COL_TECHNOLOGY,
            COL_CHARGE_COUNTER, COL_FULL_CHARGE, COL_DESIGN_CAPACITY,
            COL_HEALTH_PERCENT, COL_HEALTH_TEXT, COL_CYCLE_COUNT,
            COL_ETA_MINUTES, COL_ETA_TEXT, COL_UPDATED_AT,
        )

        val LAST_COLUMNS = listOf(
            COL_RECORD_ID, COL_DATE_MILLIS, COL_DATE_TEXT, COL_FROM_LEVEL, COL_TO_LEVEL,
            COL_ADDED_PERCENT, COL_DURATION_MINUTES, COL_AVG_POWER_X100, COL_AVG_POWER_TEXT,
            COL_PEAK_POWER_X100, COL_ENERGY_X100, COL_LOSS_PERCENT, COL_MEASURED_CAPACITY,
            COL_RECORD_HEALTH, COL_HAS_SERIES,
        )

        val TODAY_COLUMNS = listOf(
            COL_TODAY_SESSIONS, COL_TODAY_MINUTES, COL_TODAY_ENERGY_X100,
            COL_TODAY_ADDED_PERCENT, COL_TOTAL_SESSIONS, COL_LAST_DATE_TEXT,
        )

        val RECORD_COLUMNS = listOf(
            COL_INDEX, COL_RECORD_ID, COL_DATE_MILLIS, COL_FROM_LEVEL, COL_TO_LEVEL,
            COL_ADDED_PERCENT, COL_DURATION_MINUTES, COL_AVG_POWER_X100, COL_ENERGY_X100,
            COL_RECORD_HEALTH,
        )
    }
}
