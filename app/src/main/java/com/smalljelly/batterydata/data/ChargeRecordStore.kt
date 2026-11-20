package com.smalljelly.batterydata.data

import android.content.Context
import com.smalljelly.batterydata.provider.BatteryWidgetProvider
import org.json.JSONArray

/**
 * 充电记录的本地存储（SharedPreferences 里存一段 JSON 数组）。
 * 记录量很小（每条几十字节），不需要数据库。
 */
class ChargeRecordStore(context: Context) {

    private val prefs = context.getSharedPreferences("battery_charge_records", Context.MODE_PRIVATE)

    /** 只用来给 widget provider 发变更通知，不持有 Activity */
    private val appContext = context.applicationContext

    /** 全部记录，按时间倒序（最新在前） */
    fun all(): List<ChargeRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(ChargeRecord.fromJson(array.getJSONObject(i)))
                }
            }.sortedByDescending { it.dateMillis }
        }.getOrDefault(emptyList())
    }

    fun add(record: ChargeRecord) {
        val current = all().filterNot { it.id == record.id }
        write(current + record)
    }

    fun delete(id: Long) {
        write(all().filterNot { it.id == id })
    }

    private fun write(records: List<ChargeRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
        // 记录一改，小部件那边（ContentProviderBinder）下次刷新就能拿到新数据
        runCatching { BatteryWidgetProvider.notifyChanged(appContext) }
    }

    private companion object {
        const val KEY = "records"
    }
}
