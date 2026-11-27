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
    private const val SEQ_PREFS = "island_state"
    private const val SEQ_KEY = "last_sequence"
    const val ISLAND_NOTIFICATION_ID = 2001
    // 超级岛数据刷新间隔：同 ID 重发通知即"更新数据"（不新增、不重弹大岛）
    private const val ISLAND_UPDATE_MS = 5_000L
    // 超级岛不可用时的谷歌原生实况通知（Live Update）兜底 ID
    const val LIVE_NOTIFICATION_ID = 2002

    private val scope = CoroutineScope(Dispatchers.IO)
    private val sequenceLock = Any()
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
    // 最近一次构建并发送出去的岛通知体：前台服务复用它钉住，不再重复构建（见 lastSentIsland）
    private var lastBuilt: Notification? = null

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

    /**
     * 系统焦点通知协议版本：1 = OS1，2 = OS2，3 = OS3。
     *
     * 官方文档：OS2/OS3 支持焦点通知，**只有 OS3 才真正支持超级岛**。同一个 param
     * 在 OS2 上只会被渲染成一张焦点卡片，排查「岛上没有内容」时这是第一个要看的值。
     */
    fun focusProtocolVersion(context: Context): Int = runCatching {
        android.provider.Settings.System.getInt(
            context.contentResolver,
            "notification_focus_protocol",
            0,
        )
    }.getOrDefault(0)

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
    fun onSample(context: Context, sample: BatterySample, enabled: Boolean, notice: Boolean = true) {
        // 「插电电量提醒」关掉时这类通知一条都不该出现（记录通知是另一回事，不受它管）
        if (!notice) {
            cancel(context)
            return
        }
        val recording = ChargeRecorder.state.value as? RecorderState.Recording
        // 放电记录期间也要有岛：此时设备**没在充电**，不能沿用"没充电就取消"的判断。
        val discharging = recording != null && recording.recordType == "discharge"
        if (!sample.isCharging && !discharging) {
            cancel(context)
            return
        }
        val supported = isIslandSupported(context)
        if (supported && enabled) {
            sendOrUpdate(context, sample)
        } else if (recording == null) {
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

        // ★ 唯一一次构建：序号、通知体、首次弹出标志都从这里出去。
        //   以前这里构建一次、ChargeRecordService.pinIsland 又构建一次，两次都会
        //   调用 nextSequence() 消耗一个序号 —— 真正发出去的那条序号比系统手里的小，
        //   于是第一条岛通知被判 old 丢弃，卡片停在建卡那一刻（空白）。
        val notification = buildIslandNotification(context, sample, firstPop)
        // 首次弹大岛，之后静默更新缩略态（firstPop 在函数开头已算好）
        islandShowing = true
        lastBuilt = notification

        scope.launch { withShizukuBypass(context, notification) }
    }

    /**
     * 最近一次构建并发送出去的岛通知（下次构建前一直有效）。
     *
     * 前台服务需要把「当前该钉住的通知」立刻交给 startForeground，但它**不能重新构建**：
     * 重新构建 = 重新消耗一个序号 = 真正发送出去的那条被判 old。这里直接复用已经发出去
     * 的那一身 notification 对象；还没发过（进程刚起、首个采样没到）时才按缩略态补构建一次。
     */
    fun lastSentIsland(sample: BatterySample): Notification? = lastBuilt

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
            // 锁屏必须能看见内容：默认的 VISIBILITY_PRIVATE 在「锁屏通知内容」设为隐藏时
            // 会被系统整条脱敏 —— 卡片壳还在（黑底圆角），里面的字全没了，
            // 表现就是「锁屏上是张空黑卡片、没有信息」。电量/功率不属于敏感信息，直接 PUBLIC。
            .setVisibility(Notification.VISIBILITY_PUBLIC)
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

        val params = buildIslandParams(context, sample, firstPop)
        // 每次新的充电会话按一次完整参数：出问题时这行日志能直接看出「系统收到了什么」
        if (firstPop) Log.i(TAG, "焦点协议=${focusProtocolVersion(context)} 岛参数=$params")
        val notification = builder.build()
        notification.extras.putString("miui.focus.param", params)
        return notification
    }

    /**
     * 同步取「此刻该钉住的那条」通知（岛或谷歌兜底），供前台服务立即 startForeground。
     * 会先建好对应渠道。**不发送**。
     *
     * 岛模式下优先复用 [lastSentIsland] 已经发出去的那一身对象 —— 前台服务只是把它
     * 钉住，绝不能重新构建。每次构建都会从 [nextSequence] 拿走一个序号，被丢掉的那个
     * 序号会让系统手里的值跑到前面，真正发出去的那条更新就会被判 old 整条丢弃。
     *
     * 还没有发过任何岛（进程刚起、首个采样 tick 没到）时才按缩略态补构建一次 ——
     * 这是唯一可以构建的时刻：此后 [resetThrottle] + 那次 bypass 发送会用 firstPop=true
     * 再弹一次大岛，所以这里必须是 firstPop=false，否则会出现「双弹」。
     */
    fun currentNotification(context: Context, sample: BatterySample): Pair<Int, Notification> =
        if (isIslandSupported(context)) {
            ensureChannel(context)
            ISLAND_NOTIFICATION_ID to (lastSentIsland(sample)
                ?: buildIslandNotification(context, sample, firstPop = false))
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
        lastBuilt = null
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
                return@withLock
            }
            try {
                manager.notify(ISLAND_NOTIFICATION_ID, notification)
                // 这条通知已经由前台服务 startForeground 钉住了（pinIsland 每次都紧跟在这里），
                // 再调 foregroundPin 会把同一身 notification 再 startForeground 一次 ——
                // 系统侧看到的是「同一序号又更新了一次」，正是 filterOut 的现场。
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

    /**
     * 岛通知的 `sequence`：系统拿它判「这条更新是不是比手上的旧」，判旧就整条丢弃。
     *
     * 现场证据（SystemUI 日志，焦点通知名为 FocusNotifPreHandler）：
     * `filterOut old 0|com.smalljelly.batterydata|2001|null|10351, 1795692554<1795692554`
     * ——新序号和系统记住的旧序号相等，于是这次更新被丢掉。丢弃是**整条**丢，
     * 卡片内容不会跟着变，只会停在建卡那一刻的样子（表现为「黑卡片里一个字都没有」）。
     *
     * 为什么会相等/变小：以前序号是 `AtomicLong(currentTimeMillis() / 1000)`，进程启动时取
     * 当前秒数、之后每发一次 +1。发得比时钟快（前台页面 10s 一次 +
     * 服务 5s 一次 + 每次接管前台都 resetThrottle 补发）计数器就会跑到真实时间前面；
     * 进程被杀（HyperOS 常态）后重建，序号又从「当前秒数」重新起算 —— 于是新值恒不大于
     * 系统记住的旧值，之后每一条更新都被判旧，岛/焦点卡片就永远刷不出内容。
     *
     * 现在把「下一可用序号」落到 SharedPreferences，取 max(当前毫秒, 上次+1)：
     * 跨进程严格递增；用毫秒也让升级前的秒级旧值一定更小，不会升级后又卡一次。
     *
     * ⚠ **只在真正 notify 的那一刻调用一次**。每多调一次就白白消耗一个序号：
     * 被消耗却没发出去的那个值，会让系统手里的序号跑到前面，
     * 于是下一条真正发出的更新反而被判 old —— 2026-11-27 现场就是这样丢的
     * （`filterOut old …, 1795750427740<1795750427761`，差 21ms 的正是在构建阶段被吃掉的那次）。
     */
    private fun nextSequence(context: Context): Long = synchronized(sequenceLock) {
        val prefs = context.applicationContext
            .getSharedPreferences(SEQ_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(SEQ_KEY, 0L)
        // +10 而不是 +1：构建与发送之间隔了几毫秒，留出余量让序号明显跑在系统记住的值之上，
        // 避免「同一毫秒里连续两次发送」被判成相等
        val next = maxOf(System.currentTimeMillis(), last + 10L)
        prefs.edit().putLong(SEQ_KEY, next).apply()
        next
    }

    /** 超级岛参数 JSON：缩略态左电量右功率，展开态温度/预计充满。结构对齐官方 param_v2。 */
    private fun buildIslandParams(
        context: Context,
        sample: BatterySample,
        firstPop: Boolean,
    ): String {
        val recording = ChargeRecorder.state.value as? RecorderState.Recording
        val discharging = recording?.recordType == "discharge"
        // 放电记录时设备没在充电，功率取绝对值（放电电流是负的），文案也要换
        val powerW = sample.powerW?.let { if (discharging) kotlin.math.abs(it) else it }
        val powerText = when {
            powerW != null -> "${powerW.toInt()}W"
            discharging -> "放电中"
            else -> "充电中"
        }
        val tempText = sample.temperatureC?.let { String.format(java.util.Locale.US, "%.1f°C", it) } ?: ""
        // 记录中：岛显示这次记录的状态（时长 / 电能），而不是通用的"预计充满"。
        // 记录与普通充电共用同一条岛通知，靠这里的内容切换体现"已接入记录"。
        val baseTitle = when {
            recording == null -> "电池充电中"
            discharging -> "放电记录中"
            else -> "充电记录中"
        }
        // etaMinutes 是自定义 getter，先取到局部变量才能 smart cast
        val eta = sample.etaMinutes
        val baseContent = when {
            recording != null -> buildString {
                append("已记录 ${recording.minutes} 分钟")
                recording.energyWh?.let {
                    append(
                        String.format(
                            java.util.Locale.US,
                            if (discharging) " · 消耗 %.1fWh" else " · 充入 %.1fWh",
                            it,
                        ),
                    )
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
            put("sequence", nextSequence(context))
            put("aodTitle", when {
                recording == null -> "充电中 ${sample.level}%"
                discharging -> "放电中 ${sample.level}%"
                else -> "记录中 ${sample.level}%"
            })
            // OS2 状态栏焦点文案（官方推荐显式传）
            put("ticker", when {
                recording == null -> "充电中 $powerText"
                discharging -> "放电记录中 $powerText"
                else -> "充电记录中 ${sample.level}%"
            })

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