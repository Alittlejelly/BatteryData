package com.smalljelly.batterydata.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 今日亮屏/息屏耗电统计。
 *
 * 由统一采样处喂入 [onSample]：电量下降按当前屏幕状态计入对应耗电，
 * 电量上升计入充入百分点；时间增量按屏幕状态分别累计。
 * 采样由页面和前台服务共同驱动，进程死亡期间的空档不会被补记。
 */
data class ScreenTimeStats(
    val dateKey: String = "",
    val screenOnMs: Long = 0L,
    val screenOffMs: Long = 0L,
    val screenOnDrainPercent: Float = 0f,
    val screenOffDrainPercent: Float = 0f,
    val chargePercent: Float = 0f,
    val sampleCount: Int = 0,
) {
    val totalDrainPercent: Float get() = screenOnDrainPercent + screenOffDrainPercent
    val screenOnHours: Float get() = screenOnMs / 3_600_000f
    val screenOffHours: Float get() = screenOffMs / 3_600_000f
    val screenOnDrainPerHour: Float get() =
        if (screenOnMs <= 0L) 0f else screenOnDrainPercent / screenOnHours
    val screenOffDrainPerHour: Float get() =
        if (screenOffMs <= 0L) 0f else screenOffDrainPercent / screenOffHours
}

object ScreenTimeTracker {

    private const val PREFS = "battery_screen_time"
    private const val KEY_STATS = "stats"
    /** 异常间隔（休眠/重启/采样暂停）不累计，避免假数据 */
    private const val MAX_GAP_MS = 10 * 60 * 1000L

    private val _state = MutableStateFlow(ScreenTimeStats())
    val state: StateFlow<ScreenTimeStats> = _state.asStateFlow()

    private var appContext: Context? = null
    private var powerManager: PowerManager? = null
    private var registered = false
    private var screenOn = true
    private var lastLevel: Int? = null
    private var lastAt = 0L
    private var pendingSave = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = when (intent.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> screenOn
            }
        }
    }

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext === app && registered) return
        appContext = app
        powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = runCatching { powerManager?.isInteractive == true }.getOrDefault(true)
        if (!registered) {
            runCatching {
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registered = true
            }
        }
        restore(app)
    }

    @Synchronized
    fun onSample(sample: BatterySample) {
        val ctx = appContext ?: return
        val today = todayKey()
        var stats = _state.value

        // 跨天清零
        if (stats.dateKey != today) {
            stats = ScreenTimeStats(dateKey = today)
            lastLevel = null
            lastAt = 0L
        }

        val now = sample.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val level = sample.level.coerceIn(0, 100)
        val previousLevel = lastLevel
        val previousAt = lastAt
        lastLevel = level
        lastAt = now

        // 屏幕状态优先取实时系统值，广播作为更新补充
        val isOn = runCatching { powerManager?.isInteractive == true }.getOrDefault(screenOn)
        screenOn = isOn

        if (previousLevel != null && previousAt > 0L) {
            val gap = now - previousAt
            if (gap in 1..MAX_GAP_MS) {
                val delta = previousLevel - level
                if (delta > 0) {
                    stats = if (isOn) {
                        stats.copy(screenOnDrainPercent = stats.screenOnDrainPercent + delta)
                    } else {
                        stats.copy(screenOffDrainPercent = stats.screenOffDrainPercent + delta)
                    }
                } else if (delta < 0 && (sample.isCharging || sample.isFull || sample.isPlugged)) {
                    stats = stats.copy(chargePercent = stats.chargePercent - delta)
                }
                stats = if (isOn) {
                    stats.copy(screenOnMs = stats.screenOnMs + gap)
                } else {
                    stats.copy(screenOffMs = stats.screenOffMs + gap)
                }
            }
        }

        stats = stats.copy(sampleCount = stats.sampleCount + 1)
        _state.value = stats
        if (++pendingSave >= 6) {
            pendingSave = 0
            save(ctx, stats)
        }
    }

    @Synchronized
    fun resetToday(context: Context) {
        val stats = ScreenTimeStats(dateKey = todayKey())
        _state.value = stats
        lastLevel = null
        lastAt = 0L
        save(context.applicationContext, stats)
    }

    private fun restore(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATS, null) ?: return
        val stats = runCatching {
            val json = JSONObject(raw)
            ScreenTimeStats(
                dateKey = json.optString("date"),
                screenOnMs = json.optLong("onMs"),
                screenOffMs = json.optLong("offMs"),
                screenOnDrainPercent = json.optDouble("onDrain").toFloat(),
                screenOffDrainPercent = json.optDouble("offDrain").toFloat(),
                chargePercent = json.optDouble("charge").toFloat(),
                sampleCount = json.optInt("samples"),
            )
        }.getOrNull() ?: return
        _state.value = if (stats.dateKey == todayKey()) stats else ScreenTimeStats(dateKey = todayKey())
    }

    private fun save(context: Context, stats: ScreenTimeStats) {
        val json = JSONObject().apply {
            put("date", stats.dateKey)
            put("onMs", stats.screenOnMs)
            put("offMs", stats.screenOffMs)
            put("onDrain", stats.screenOnDrainPercent.toDouble())
            put("offDrain", stats.screenOffDrainPercent.toDouble())
            put("charge", stats.chargePercent.toDouble())
            put("samples", stats.sampleCount)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATS, json.toString()).apply()
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
}
