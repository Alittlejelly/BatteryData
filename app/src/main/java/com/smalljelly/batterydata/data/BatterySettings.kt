package com.smalljelly.batterydata.data

import android.content.Context

/**
 * 用户手动设置（SharedPreferences 持久化）。
 */
class BatterySettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 手动指定的设计容量，未设置时返回 null。 */
    fun manualDesignCapacityMah(): Float? =
        prefs.getFloat(KEY_MANUAL_DESIGN_MAH, 0f).takeIf { it > 0f }

    /** 设置 / 清除（传 null 恢复自动识别）。 */
    fun setManualDesignCapacityMah(mah: Float?) {
        prefs.edit().apply {
            if (mah == null) {
                remove(KEY_MANUAL_DESIGN_MAH)
            } else {
                putFloat(KEY_MANUAL_DESIGN_MAH, mah)
            }
        }.apply()
    }

    /** 顶栏模糊自检：不过滤、不淡出，直接贴内容副本（排查"看不见模糊"用） */
    fun topBlurSelfTest(): Boolean = prefs.getBoolean(KEY_TOP_BLUR_SELF_TEST, false)

    fun setTopBlurSelfTest(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOP_BLUR_SELF_TEST, enabled).apply()
    }

    /** 更新源：auto / github / gitee，默认自动（两个源都查） */
    fun updateSource(): String = prefs.getString(KEY_UPDATE_SOURCE, "auto") ?: "auto"

    fun setUpdateSource(value: String) {
        prefs.edit().putString(KEY_UPDATE_SOURCE, value).apply()
    }

    /** NexioUI 材质档：soft / liquid / frosted / none，默认柔和 */
    fun materialLevel(): String = prefs.getString(KEY_MATERIAL_LEVEL, "soft") ?: "soft"

    fun setMaterialLevel(value: String) {
        prefs.edit().putString(KEY_MATERIAL_LEVEL, value).apply()
    }

    /** 小米超级岛：充电时在顶部岛显示电量/功率，默认开启（不支持的设备自动 no-op） */
    fun islandEnabled(): Boolean = prefs.getBoolean(KEY_ISLAND_ENABLED, true)

    fun setIslandEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ISLAND_ENABLED, enabled).apply()
    }

    /**
     * 插电电量提醒：充电时发的那条电量通知（超级岛 / 实况兜底），默认开启。
     *
     * 它比「超级岛」更外层：关掉它以后，**单纯插着电**不会再出现任何通知，
     * 只有正在进行中的充电记录才会有自己的记录通知。
     */
    fun chargeNoticeEnabled(): Boolean = prefs.getBoolean(KEY_CHARGE_NOTICE, true)

    fun setChargeNoticeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CHARGE_NOTICE, enabled).apply()
    }

    /**
     * 预见式返回动画：二级页跟手预览返回（手指滑到哪、页面退到哪），默认开启。
     * 关掉就退回普通返回动画，老系统 / 不习惯跟手预览的用户可以关。
     */
    fun predictiveBackEnabled(): Boolean = prefs.getBoolean(KEY_PREDICTIVE_BACK, true)

    fun setPredictiveBackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREDICTIVE_BACK, enabled).apply()
    }

    /** 自动充电记录：插电自动开始、拔电自动保存，默认关闭 */
    fun autoRecordEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_RECORD, false)

    fun setAutoRecordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()
    }

    /** 实时电量悬浮窗，默认关闭 */
    fun overlayEnabled(): Boolean = prefs.getBoolean(KEY_OVERLAY, false)

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "battery_settings"
        const val KEY_MANUAL_DESIGN_MAH = "manual_design_capacity_mah"
        const val KEY_TOP_BLUR_SELF_TEST = "top_blur_self_test"
        const val KEY_UPDATE_SOURCE = "update_source"
        const val KEY_MATERIAL_LEVEL = "material_level"
        const val KEY_ISLAND_ENABLED = "island_enabled"
        const val KEY_CHARGE_NOTICE = "charge_notice"
        const val KEY_PREDICTIVE_BACK = "predictive_back"
        const val KEY_AUTO_RECORD = "auto_record"
        const val KEY_OVERLAY = "overlay"
    }
}
