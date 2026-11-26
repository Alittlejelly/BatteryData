package com.smalljelly.batterydata.data

import android.content.Context
import org.xmlpull.v1.XmlPullParser

/** 从系统固件的功耗配置读取出厂设计容量，不依赖充电记录或隐藏 API。 */
internal object SystemDesignCapacityReader {

    /** 固件配置在进程生命周期内不会变；读不到也记下来，避免每个采样周期重读系统资源。 */
    @Volatile
    private var cached: Float? = null

    @Volatile
    private var attempted = false

    @Synchronized
    fun read(context: Context): Float? {
        if (attempted) return cached
        attempted = true
        cached = runCatching {
            val resources = context.packageManager.getResourcesForApplication("android")
            val profileId = resources.getIdentifier("power_profile", "xml", "android")
            if (profileId == 0) return null
            resources.getXml(profileId).use { parse(it) }
        }.getOrNull()
        return cached
    }

    internal fun parse(parser: XmlPullParser): Float? {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item" &&
                parser.getAttributeValue(null, "name") == "battery.capacity"
            ) {
                // AOSP 默认配置的 1000 mAh 是占位值，不作为手机的出厂容量。
                return parser.nextText().trim().toFloatOrNull()
                    ?.takeIf { it.isFinite() && it in 1500f..30_000f }
            }
            event = parser.next()
        }
        return null
    }
}
