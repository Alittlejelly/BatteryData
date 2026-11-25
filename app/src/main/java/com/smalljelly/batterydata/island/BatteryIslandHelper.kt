package com.smalljelly.batterydata.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smalljelly.batterydata.MainActivity
import com.smalljelly.batterydata.R
import com.smalljelly.batterydata.data.BatterySample
import com.smalljelly.batterydata.data.ChargeRecorder
import com.smalljelly.batterydata.data.RecorderState
import com.smalljelly.batterydata.shizuku.ShizukuManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 小米超级岛（HyperOS 焦点通知）适配，移植自 NexioSchedule 的 IslandNotificationHelper，
 * 把课程倒计时换成电池充电状态：充电中在屏幕顶部岛里显示电量 / 功率，展开看温度与预计充满时间。
 *
 * 岛通知走 XMSF 通道，系统默认掐其网络 → 借 Shizuku 临时放行（见 [ShizukuManager]）。
 * 不支持岛的设备（persist.sys.feature.island=false）所有调用直接 no-op。
 */
object BatteryIslandHelper {
    private const val TAG = "BatteryIslandHelper"
    private const val CHANNEL_ID = "battery_charge_island"
    private const val LIVE_CHANNEL_ID = "battery_charge_live"
    private const val BUSINESS_TAG = "battery_charge"
    const val ISLAND_NOTIFICATION_ID = 2001
    // 超级岛数据刷新间隔：同 ID 重发通知即"更新数据"（不新增、不重弹大岛）
    private const val ISLAND_UPDATE_MS = 5_000L
    // 超级岛不可用时的谷歌原生实况通知（Live Update）兜底 ID
    const val LIVE_NOTIFICATION_ID = 2002

    private val sequenceCounter = AtomicLong(System.currentTimeMillis() / 1000)
    private val scope = CoroutineScope(Dispatchers.IO)
    // 串行化 disable→notify→enable，避免并发把 XMSF 网络状态搞乱
    private val bypassMutex = Mutex()
    // 岛是否已在显示：只有首次弹出大岛（enableFloat=true），后续刷新静默更新缩略态，
    // 与 NexioSchedule 一致——否则每次采样都弹大岛会很吵。
    private var islandShowing = false
    // 普通通知去重 + 节流：内容没变不重发；电量变化立即更新，功率抖动限频 20s，
    // 避免每 5 秒采样都 notify() 导致通知不断刷新。
    private var lastFallbackKey: String? = null
    private var lastFallbackAt = 0L
    private var lastFallbackLevel = -1
    // 岛更新节流：首次必发；之后内容没变不重发，电量没变则限频 5s——
    // 功率每 5s 更新一次；内容没变不重发，避免无谓地反复 toggle XMSF 网络。
    private var lastIslandKey: String? = null
    private var lastIslandAt = 0L
    private var lastIslandLevel = -1

    /**
     * 前台服务钉住回调：岛 / 兜底通知每次发出后，把 (id, notification) 交给
     * [com.smalljelly.batterydata.data.ChargeRecordService] 去 startForeground。
     * 岛模式下前台通知就是岛通知本身，不多出一条常驻通知。服务销毁时置空。
     */
    var foregroundPin: ((Int, Notification) -> Unit)? = null

    fun init(context: Context) {
        ShizukuManager.init(context)
    }

    /** 设备是否支持超级岛（小米 HyperOS 特性开关）。 */
    fun isIslandSupported(context: Context): Boolean = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod(
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.invoke(null, "persist.sys.feature.island", false) as Boolean
    } catch (e: Exception) {
        false
    }

    fun isShizukuAvailable(): Boolean =
        ShizukuManager.isShizukuRunning() && ShizukuManager.checkSelfPermission()

    fun requestShizukuPermission(callback: (Boolean) -> Unit) {
        ShizukuManager.requestPermission(callback)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "充电超级岛",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "充电时在小米超级岛显示电量与功率"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 充电状态通知由采样链每次采样驱动：
     * - 充电中：支持超级岛且开启 → 发岛；否则回退谷歌原生实况通知（Live Update）。
     * - 非充电：收起岛与兜底通知。
     * 记录中（[RecorderState.Recording]）已有 ChargeRecordService 的实况通知(1001)，
     * 兜底不重复发；岛则与记录通知并存（岛看电量/功率，记录通知带「结束并保存」）。
     */
    fun onSample(context: Context, sample: BatterySample, enabled: Boolean) {
        if (!sample.isCharging) {
            cancel(context)
            return
        }
        val supported = isIslandSupported(context)
        val recording = ChargeRecorder.state.value is RecorderState.Recording
        if (supported && enabled) {
            sendOrUpdate(context, sample)
        } else if (!recording) {
            Log.d(TAG, "island unavailable (supported=$supported enabled=$enabled) → Google live fallback")
            sendGoogleLiveFallback(context, sample)
        }
    }

    fun sendOrUpdate(context: Context, sample: BatterySample) {
        if (!isIslandSupported(context)) return
        val firstPop = !islandShowing
        val now = System.currentTimeMillis()
        // 只按时间节流，不做内容去重。
        //
        // 超级岛的数据刷新在系统层面只有一条路：用**同一个通知 ID** 重新 notify，
        // 换掉 extras 里的 miui.focus.param。系统按 update 处理（不新增、不重弹大岛，
        // 因为后续都是 enableFloat=false）。所以"只刷新数据"= 定期重发同一条通知。
        //
        // 以前这里还有一道 key 去重（"$level-${powerW.toInt()}"）：功率整数没变就直接
        // return —— 电量 86% 长时间不动、功率在 18.4~18.6W 抖动时 key 恒为 "86-18"，
        // 岛就永远冻结在初始值。这是"数据都不会变"的根因。
        if (!firstPop && now - lastIslandAt < ISLAND_UPDATE_MS) return
        lastIslandKey = "${sample.level}-${sample.powerW?.toInt()}"
        lastIslandAt = now
        lastIslandLevel = sample.level

        ensureChannel(context)
        // 岛与谷歌兜底互斥：发岛时收掉兜底
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(LIVE_NOTIFICATION_ID)

        val notification = buildIslandNotification(context, sample, firstPop)
        // 首次弹大岛，之后静默更新缩略态（firstPop 在函数开头已算好）
        islandShowing = true

        scope.launch { withShizukuBypass(context, notification) }
    }

    /**
     * 构建岛通知（纯构建、不发送）：前台服务接管岛模式时用它立刻钉住前台通知，
     * 不必等节流后的下一次发送。渠道需调用方先 [ensureChannel]。
     */
    fun buildIslandNotification(context: Context, sample: BatterySample, firstPop: Boolean): Notification {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            ISLAND_NOTIFICATION_ID,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val powerText = sample.powerW?.let { "${it.toInt()}W" } ?: "充电中"
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle("充电中 ${sample.level}%")
            .setContentText(powerText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            // 每 5s 都会重发一次做数据刷新：只要不重新提示（不响铃/不震动）。
            // 大岛不会重弹，因为后续 param 里 enableFloat=false。
            .setOnlyAlertOnce(true)

        val picsBundle = Bundle().apply {
            putParcelable("miui.focus.pic_app_icon", Icon.createWithResource(context, R.drawable.ic_launcher_flat))
            putParcelable("miui.focus.pic_app_icon_dark", Icon.createWithResource(context, R.drawable.ic_launcher_flat))
            putParcelable("miui.focus.pic_small", Icon.createWithResource(context, R.drawable.ic_launcher_flat))
            putParcelable("miui.focus.pic_small_dark", Icon.createWithResource(context, R.drawable.ic_launcher_flat))
        }
        builder.addExtras(Bundle().apply { putBundle("miui.focus.pics", picsBundle) })

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", buildIslandParams(sample, firstPop))
        return notification
    }

    /**
     * 同步构建「此刻该钉住的那条」通知（岛或谷歌兜底），供前台服务立即 startForeground。
     * 会先建好对应渠道。不发送、不改节流状态。
     *
     * 岛一律按缩略态（firstPop=false）构建：钉住只是占位满足前台服务 5 秒时限，真正的首次
     * 大岛弹出交给随后 [resetThrottle] + [onSample] 的那次 bypass 发送（firstPop=!islandShowing）。
     * 若这里也用 firstPop=true，钉住会先弹一次大岛、首个采样 tick 再弹一次，造成「双弹」。
     */
    fun currentNotification(context: Context, sample: BatterySample): Pair<Int, Notification> =
        if (isIslandSupported(context)) {
            ensureChannel(context)
            ISLAND_NOTIFICATION_ID to buildIslandNotification(context, sample, firstPop = false)
        } else {
            ensureLiveChannel(context)
            LIVE_NOTIFICATION_ID to buildGoogleLiveNotification(context, sample)
        }

    /** 清空发送节流：下一次 [onSample] 必发（前台服务接管时用它立刻补发 + 钉住）。 */
    fun resetThrottle() {
        lastIslandKey = null
        lastIslandAt = 0L
        lastIslandLevel = -1
        lastFallbackKey = null
        lastFallbackAt = 0L
        lastFallbackLevel = -1
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(ISLAND_NOTIFICATION_ID)
        manager.cancel(LIVE_NOTIFICATION_ID)
        islandShowing = false
        lastFallbackKey = null
        lastFallbackLevel = -1
        lastFallbackAt = 0L
        lastIslandKey = null
        lastIslandLevel = -1
        lastIslandAt = 0L
    }

    /** 超级岛不可用时的兜底：谷歌原生实况通知（Android 16 Live Update，低版本退回进度条）。 */
    fun sendGoogleLiveFallback(context: Context, sample: BatterySample) {
        val now = System.currentTimeMillis()
        val key = "${sample.level}-${sample.powerW?.toInt()}"
        val levelChanged = sample.level != lastFallbackLevel
        // 内容没变 → 不重发；功率抖动（电量没变）→ 距上次不足 20s 也不重发
        if (key == lastFallbackKey) return
        if (!levelChanged && now - lastFallbackAt < 20_000) return
        lastFallbackKey = key
        lastFallbackAt = now
        lastFallbackLevel = sample.level

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureLiveChannel(context)
        manager.cancel(ISLAND_NOTIFICATION_ID)

        val notification = buildGoogleLiveNotification(context, sample)
        // 普通通知不挂进度条：纯文本（标题电量 + 正文功率），安静常驻
        manager.notify(LIVE_NOTIFICATION_ID, notification)
        foregroundPin?.invoke(LIVE_NOTIFICATION_ID, notification)
    }

    /** 构建谷歌兜底实况通知（纯构建、不发送）。渠道需调用方先 [ensureLiveChannel]。 */
    fun buildGoogleLiveNotification(context: Context, sample: BatterySample): Notification {
        val open = PendingIntent.getActivity(
            context,
            LIVE_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val level = sample.level.coerceIn(0, 100)
        val powerText = sample.powerW?.let { String.format(java.util.Locale.US, "%.1fW", it) } ?: "充电中"
        return NotificationCompat.Builder(context, LIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle("充电中 $level%")
            .setContentText(powerText)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureLiveChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(LIVE_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(LIVE_CHANNEL_ID, "充电实况", NotificationManager.IMPORTANCE_LOW).apply {
                description = "充电时的谷歌原生实况通知（超级岛不可用时的兜底）"
                setShowBadge(false)
            },
        )
    }

    private suspend fun withShizukuBypass(context: Context, notification: Notification) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!isShizukuAvailable()) {
            // 没授权也发：部分机型不掐 XMSF 网络仍能弹岛
            manager.notify(ISLAND_NOTIFICATION_ID, notification)
            foregroundPin?.invoke(ISLAND_NOTIFICATION_ID, notification)
            return
        }
        bypassMutex.withLock {
            val disabled = try {
                ShizukuManager.setXmsfNetworkingEnabled(context, false)
            } catch (e: Exception) {
                Log.e(TAG, "disable XMSF networking failed", e)
                false
            }
            if (!disabled) {
                manager.notify(ISLAND_NOTIFICATION_ID, notification)
                foregroundPin?.invoke(ISLAND_NOTIFICATION_ID, notification)
                return@withLock
            }
            try {
                manager.notify(ISLAND_NOTIFICATION_ID, notification)
                foregroundPin?.invoke(ISLAND_NOTIFICATION_ID, notification)
                delay(100)
            } finally {
                try {
                    ShizukuManager.setXmsfNetworkingEnabled(context, true)
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: restore XMSF networking failed", e)
                }
            }
        }
    }

    /** 超级岛参数 JSON：缩略态左电量右功率，展开态温度/预计充满。结构对齐官方 param_v2。 */
    private fun buildIslandParams(sample: BatterySample, firstPop: Boolean): String {
        val powerText = sample.powerW?.let { "${it.toInt()}W" } ?: "充电中"
        val tempText = sample.temperatureC?.let { String.format(java.util.Locale.US, "%.1f°C", it) } ?: ""
        // 记录中：岛显示这次记录的状态（时长 / 充入电能），而不是通用的"预计充满"。
        // 记录与普通充电共用同一条岛通知，靠这里的内容切换体现"已接入记录"。
        val recording = ChargeRecorder.state.value as? RecorderState.Recording
        val baseTitle = if (recording != null) "充电记录中" else "电池充电中"
        // etaMinutes 是自定义 getter，先取到局部变量才能 smart cast
        val eta = sample.etaMinutes
        val baseContent = when {
            recording != null -> buildString {
                append("已记录 ${recording.minutes} 分钟")
                recording.energyWh?.let {
                    append(String.format(java.util.Locale.US, " · 充入 %.1fWh", it))
                }
            }
            eta != null -> "预计 ${eta / 60} 小时 ${eta % 60} 分后充满"
            else -> "正在充电"
        }

        val paramV2 = JSONObject().apply {
            put("business", BUSINESS_TAG)
            put("protocol", 1)
            // 官方语义：enableFloat = 通知**更新时**是否自动展开为展开态（默认 false）。
            // 只有首次让大岛弹出，后续静默更新缩略态。
            put("enableFloat", firstPop)
            // 官方语义：islandFirstFloat = 通知**第一次出现时**是否展开（默认 true）。
            // 只在首次传 —— 每次更新都传 true 会被系统当成"首次出现"重新展开，
            // 表现为岛反复弹开（这也是之前"每次刷新都重弹"的一个来源）。
            if (firstPop) put("islandFirstFloat", true)
            put("updatable", true)
            put("reopen", "reopen")
            put("sequence", sequenceCounter.incrementAndGet())
            put("aodTitle", if (recording != null) "记录中 ${sample.level}%" else "充电中 ${sample.level}%")
            // OS2 状态栏焦点文案（官方推荐显式传）
            put("ticker", if (recording != null) "充电记录中 ${sample.level}%" else "充电中 $powerText")

            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", baseTitle)
                put("content", baseContent)
                put("subTitle", "")
                put("extraTitle", "")
                put("specialTitle", "")
                put("subContent", "")
                put("picFunction", "")
                put("showDivider", true)
                put("showContentDivider", false)
                put("colorTitle", "#111111")
                put("colorTitleDark", "#ffffff")
                put("colorContent", "#333333")
                put("colorContentDark", "#cccccc")
            })

            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "")
            })

            put("hintInfo", JSONObject().apply {
                put("type", 2)
                put("title", powerText)
                put("content", "充电功率")
                put("timerInfo", JSONObject().apply {
                    put("timerType", 0)
                    put("timerWhen", 0)
                    put("timerTotal", 0)
                    put("timerSystemCurrent", 0)
                })
                put("subContent", "电池温度")
                put("subTitle", tempText.ifEmpty { "—" })
                put("colorContent", "#666666")
                put("colorContentDark", "#aaaaaa")
                put("colorTitle", "#222222")
                put("colorTitleDark", "#eeeeee")
                put("colorSubContent", "#666666")
                put("colorSubContentDark", "#aaaaaa")
                put("colorSubTitle", "#222222")
                put("colorSubTitleDark", "#eeeeee")
            })

            put("param_island", JSONObject().apply {
                put("islandProperty", 1)
                put("islandTimeout", 3600)
                put("bigIslandArea", JSONObject().apply {
                    put("templateNo", 2)
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("textInfo", JSONObject().apply {
                            put("title", "${sample.level}%")
                            put("content", "")
                            put("showHighlightColor", false)
                            put("narrowFont", false)
                        })
                    })
                    put("textInfo", JSONObject().apply {
                        put("frontTitle", "")
                        put("title", powerText)
                        put("content", "")
                        put("showHighlightColor", false)
                        put("narrowFont", false)
                    })
                })
                put("smallIslandArea", JSONObject().apply {
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_small")
                        put("picDark", "miui.focus.pic_small_dark")
                    })
                })
            })
        }
        return JSONObject().apply { put("param_v2", paramV2) }.toString()
    }
}