package com.smalljelly.batterydata.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一台蓝牙设备的电量快照。
 *
 * [level] 为 null 表示没读到 —— 设备不支持蓝牙标准的 GATT 电量服务（各家耳机私有协议很常见）。
 * [connected] 只活在内存里：这一轮是否真的连上了。连上的那几台电量是实时读出来的，
 * 没连上的用缓存里的旧值，作为「历史」留在列表里，用户可手动删掉。
 */
data class DeviceBattery(
    /** MAC 地址，唯一标识 */
    val address: String,
    val name: String,
    /** 0..100；null = 未知 */
    val level: Int? = null,
    /** 最近一次读到电量的时间戳（毫秒） */
    val updatedAt: Long = 0L,
    /** 这一轮是否连上；不落盘，下次刷新重算 */
    val connected: Boolean = false,
)

/**
 * 设备电量的本地缓存。
 *
 * 只存「读到过电量」的结果，有两个用处：进页面先用缓存铺满列表（不用等一轮连接），
 * 以及设备断开之后仍能以历史形式留在列表中。
 *
 * 读不到电量的设备不落盘：这类记录没有任何信息量，只会让列表永久性地堆满「未知」。
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
                val level = device.level ?: return@forEach
                array.put(
                    JSONObject().apply {
                        put("address", device.address)
                        put("name", device.name)
                        put("level", level)
                        put("at", device.updatedAt)
                    },
                )
            }
            prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
        }
    }

    /** 删掉一台设备的历史记录（界面上每张卡片的删除按钮） */
    fun remove(devices: List<DeviceBattery>, address: String) {
        save(devices.filterNot { it.address == address })
    }

    fun clear() {
        prefs.edit().remove(KEY_DEVICES).apply()
    }

    private companion object {
        const val PREFS_NAME = "device_battery"
        const val KEY_DEVICES = "devices"
    }
}