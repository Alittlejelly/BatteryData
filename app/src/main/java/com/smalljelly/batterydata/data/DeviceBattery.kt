package com.smalljelly.batterydata.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一台蓝牙设备的电量快照。
 *
 * [level] 为 null 表示还没读到 —— 设备不支持蓝牙标准的 GATT 电量服务（各家耳机私有协议很常见），
 * 或者这一刻还没连上。没读到的值会保留上一次的结果，不会因为一次失败就清空。
 *
 * [connected] 只活在内存里：系统此刻是否报告它已连接。不落盘，每轮刷新重算。
 */
data class DeviceBattery(
    /** MAC 地址，唯一标识 */
    val address: String,
    val name: String,
    /** 0..100；null = 未知 */
    val level: Int? = null,
    /** 最近一次读到电量的时间戳（毫秒） */
    val updatedAt: Long = 0L,
    /** 系统此刻是否报告它已连接；不落盘 */
    val connected: Boolean = false,
)

/**
 * 设备电量的本地缓存。
 *
 * 存的是**用户勾选要跟踪的设备列表**，不是"所有读到过的设备" ——
 * 后者会让列表越用越长，且无法区分"我关心的"和"路过被扫到的"。
 *
 * 读不到电量也照样落盘：用户勾选这个动作本身就表示"我要一直看到它"，
 * 设备暂时没连上时应该显示为「已断开」而不是消失。
 */
class DeviceBatteryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<DeviceBattery> = runCatching {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val address = obj.optString("address").takeIf { it.isNotEmpty() } ?: continue
                add(
                    DeviceBattery(
                        address = address,
                        name = obj.optString("name", address),
                        level = obj.optInt("level", -1).takeIf { it in 0..100 },
                        updatedAt = obj.optLong("at", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(devices: List<DeviceBattery>) {
        runCatching {
            val array = JSONArray()
            devices.forEach { device ->
                array.put(
                    JSONObject().apply {
                        put("address", device.address)
                        put("name", device.name)
                        // -1 = 还没读到过；不能用 0，那是"电量为 0%"这个真实状态
                        put("level", device.level ?: -1)
                        put("at", device.updatedAt)
                    },
                )
            }
            prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_DEVICES).apply()
    }

    private companion object {
        const val PREFS_NAME = "device_battery"
        const val KEY_DEVICES = "devices"
    }
}