package com.smalljelly.batterydata.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.app.ServiceCompat
import com.smalljelly.batterydata.MainActivity
import com.smalljelly.batterydata.R
import com.smalljelly.batterydata.island.BatteryIslandHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 充电期间挂着的前台服务，两种模式：
 *
 * - **记录模式**：记录器处于等待 / 记录中。每 5 秒采样喂记录器与超级岛，前台通知是
 *   记录通知（1001），通知栏可随时结束或取消。
 * - **岛模式**：没在记录，但**正在充电且超级岛开关开着**。前台通知交给岛（2001）
 *   或谷歌兜底实况（2002）本身，采样持续驱动岛刷新。
 *
 * 为什么需要岛模式：HyperOS 划掉最近任务会连前台服务一起杀整个进程，岛通知断更
 * 就消失。只有前台服务能保住进程；而充电时手机插着电，保活没有电量成本——
 * 所以保活严格门控在充电状态上：**拔电或关岛立刻退出，不充电零常驻**。
 * 进程被杀后，下次插电由充电约束 job [ChargeKeepAliveJobService] 自动拉起（见 Manifest）。
 */
class ChargeRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings by lazy { BatterySettings(applicationContext) }
    private var lastSample: BatterySample? = null

    /** 当前钉住的前台通知 id：1001 记录 / 2001 岛 / 2002 兜底 */
    private var pinnedId = 0

    // 拔电且没在记录 → 立刻退出，不等下一次采样（岛模式零常驻的关键）
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_DISCONNECTED &&
                ChargeRecorder.state.value is RecorderState.Idle
            ) {
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 顺序很重要：先把状态恢复出来，再立刻进前台，避免 startForegroundService
        // 之后 5 秒内没调 startForeground 被系统判为 ANR
        ChargeRecorder.attach(this)
        createChannel()
        val initial = BatteryRepository(applicationContext).snapshot()
        lastSample = initial
        val state = ChargeRecorder.state.value
        when {
            // 记录中且岛可用：前台通知直接用岛，不另发 1001。
            // 否则 Android 16 会把 1001 提升成实时更新（Live Update），
            // 用户会同时看到「超级岛 + 原生实况」两条，重复且吵。
            state !is RecorderState.Idle && islandAllowed(initial) -> pinIsland(initial)
            state !is RecorderState.Idle -> startForegroundWith(state)
            islandAllowed(initial) -> pinIsland(initial)
            else -> {
                // 既没记录也不满足岛模式（误启动 / 重启时已拔电）：走一帧占位就退。
                // 占位这一帧同样必须带 type（见 enterForeground），传 0 会直接崩进程
                enterForeground(NOTIFICATION_ID, idlePlaceholder())
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }

        // 岛接管前台通知（岛模式，以及"岛可用的记录模式"）：helper 每发一条岛/兜底
        // 通知就交给这里钉住（同 id 重复 startForeground 即更新）。1001 记录通知期间不抢。
        BatteryIslandHelper.foregroundPin = { id, notification ->
            if (pinnedId == 0 || pinnedId == BatteryIslandHelper.ISLAND_NOTIFICATION_ID ||
                pinnedId == BatteryIslandHelper.LIVE_NOTIFICATION_ID
            ) {
                enterForeground(id, notification)
            }
        }

        runCatching {
            registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_POWER_DISCONNECTED))
        }

        // 采样：把数据喂给记录器（内部驱动岛）。岛功率按 5s 节流更新，
        // 采样也得 5s 一拍，否则岛只能 10s 一更——充电时插电，读 sysfs 的成本可忽略
        scope.launch {
            BatteryRepository(applicationContext).stream(5_000L).collect { sample ->
                lastSample = sample
                ChargeRecorder.onSample(sample)
                // 没在记录、又不满足岛模式（拔电 / 关岛）→ 没有留下的理由
                if (ChargeRecorder.state.value is RecorderState.Idle && !islandAllowed(sample)) {
                    stopSelf()
                }
            }
        }
        // 状态：岛可用时（含记录中）前台通知一律是岛；岛不可用才用 1001 记录通知。
        // 不再让两者并存 —— 1001 在 Android 16 会被提升成实时更新，和岛重复。
        scope.launch {
            ChargeRecorder.state.collect { state ->
                val s = lastSample
                when {
                    // 记录中且岛可用，或纯岛模式：前台通知固定为岛
                    s != null && islandAllowed(s) -> {
                        if (pinnedId != BatteryIslandHelper.ISLAND_NOTIFICATION_ID &&
                            pinnedId != BatteryIslandHelper.LIVE_NOTIFICATION_ID
                        ) {
                            pinIsland(s)
                        }
                    }

                    state is RecorderState.Idle -> {
                        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                        stopSelf()
                    }

                    pinnedId != NOTIFICATION_ID -> {
                        // 岛不可用：记录通知接管前台
                        val notification = buildNotification(state)
                        if (notification != null) {
                            if (enterForeground(NOTIFICATION_ID, notification)) {
                                getSystemService(NotificationManager::class.java)
                                    ?.cancel(BatteryIslandHelper.LIVE_NOTIFICATION_ID)
                            }
                        }
                        lastNotifKey = notifKey(state)
                    }

                    else -> updateNotification(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> ChargeRecorder.stopAndSave(this)
            ACTION_CANCEL -> ChargeRecorder.cancel(this)
            else -> {
                // 岛模式：前台通知已由 onCreate 的 pinIsland 钉住（同一身 notification、
                // 同一个序号）。这里**不再 pinIsland 一次** —— 重复钉住等于把同一序号
                // 往系统再推一遍，SystemUI 侧每次都记一条 `filterOut old …, x<x`。
                // 数据刷新交给采样链的 5s 节拍，不需要额外推一遍。
                if (pinnedId == BatteryIslandHelper.ISLAND_NOTIFICATION_ID ||
                    pinnedId == BatteryIslandHelper.LIVE_NOTIFICATION_ID
                ) {
                    return START_STICKY
                }
                val s = BatteryRepository(applicationContext).snapshot()
                lastSample = s
                val state = ChargeRecorder.state.value
                when {
                    // 岛可用（含记录中）：前台通知固定为岛
                    islandAllowed(s) -> pinIsland(s)
                    state !is RecorderState.Idle -> updateNotification(state)
                    else -> stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        BatteryIslandHelper.foregroundPin = null
        runCatching { unregisterReceiver(powerReceiver) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        // 服务退出（多为拔电）后重新排期充电 job：下次插电由系统拉起复活岛。
        runCatching { ChargeKeepAliveJobService.schedule(applicationContext) }
        super.onDestroy()
    }

    /**
     * 岛模式门控：正在充电 + 超级岛开关开 + 插电电量提醒开着。
     * 充电时插电，保活零电量成本。
     */
    private fun islandAllowed(sample: BatterySample): Boolean =
        sample.isCharging && settings.islandEnabled() && settings.chargeNoticeEnabled()

    /**
     * 把岛（或谷歌兜底）通知钉成前台通知，并清掉可能残留的 1001 记录通知。
     *
     * 岛可用时它就是唯一的一条通知：记录中也一样，避免 Android 16 把 1001 提升成
     * 实时更新、和超级岛同时出现两条。
     *
     * **不重新构建岛通知**：`currentNotification` 会优先复用 helper 刚发出去的那一身
     * notification 对象。以前这里每次钉住都重新构建一次，每次构建都要从
     * `nextSequence()` 拿走一个序号 —— 被丢弃的序号把系统手里的值顶到前面，
     * 真正发出去的那条更新被判定为 old 整条丢弃，卡片就停在无内容的空壳上。
     */
    private fun pinIsland(sample: BatterySample) {
        // resetThrottle 保证首个采样 tick 必走 bypass 发送一次，岛在掐 XMSF 网络的机型上也能渲染
        BatteryIslandHelper.resetThrottle()
        val (id, notification) = BatteryIslandHelper.currentNotification(applicationContext, sample)
        if (enterForeground(id, notification)) {
            getSystemService(NotificationManager::class.java)?.let { manager ->
                manager.cancel(NOTIFICATION_ID)
                if (id != BatteryIslandHelper.LIVE_NOTIFICATION_ID) {
                    manager.cancel(BatteryIslandHelper.LIVE_NOTIFICATION_ID)
                }
            }
        }
    }

    private fun fgsType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    /**
     * 进入前台，**永远带具体 type**。
     *
     * targetSdk 34+ 的 ROM 不允许 `startForeground(id, n, type = 0)`：type none 会被直接拒，
     * 抛 [android.app.InvalidForegroundServiceTypeException] 并把整个进程带走（不是本服务，
     * 是进程），现场崩溃日志：
     * `Starting FGS with type none ... targetSDK=37 has been prohibited`
     * `at ChargeRecordService.onCreate(ChargeRecordService.kt:80)`。
     * 之前两处「占一帧再退出」的路径传的是字面量 0，等于把误启动（插电瞬间状态还没切到
     * CHARGING、job 补试的前几次）变成了一次必崩 —— 进程一崩，岛和记录都没了。
     *
     * @return 是否真的进了前台
     */
    private fun enterForeground(id: Int, notification: Notification): Boolean {
        val ok = runCatching {
            ServiceCompat.startForeground(this, id, notification, fgsType())
        }.onFailure { Log.e(TAG, "startForeground 失败 id=$id", it) }.isSuccess
        if (ok) pinnedId = id
        return ok
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "充电记录",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "记录一次充电过程的电流、电压与功率"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWith(state: RecorderState) {
        val notification = buildNotification(state)
        if (notification == null) {
            // 没有进行中的记录：撤掉通知并退出（startForeground 仍要先调一次，
            // 否则由 startForegroundService 拉起的服务会触发"未进前台"的崩溃）
            enterForeground(NOTIFICATION_ID, idlePlaceholder())
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!enterForeground(NOTIFICATION_ID, notification)) {
            // 进不了前台就别赖着：留着只会被系统按"未进前台"杀掉整个进程
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 只在"误启动"时用一帧，随即被撤掉 */
    private fun idlePlaceholder(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle("充电记录")
            .setSilent(true)
            .setOngoing(true)
            .build()

    // 通知内容去重：每 5 秒采样都会走到这里，但只有「展示内容真的变了」才重发，
    // 否则系统会把同一条通知反复重排/刷新，体验差。电量/分钟/平均功率变化才更新。
    private var lastNotifKey: String? = null

    private fun updateNotification(state: RecorderState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildNotification(state)
        if (notification == null) {
            lastNotifKey = null
            runCatching { manager.cancel(NOTIFICATION_ID) }
            return
        }
        val key = notifKey(state)
        if (key == lastNotifKey) return
        lastNotifKey = key
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun notifKey(state: RecorderState): String = when (state) {
        is RecorderState.Waiting -> "w${state.level}"
        is RecorderState.Recording -> "r${state.level}-${state.minutes}-${state.avgPowerW?.toInt()}"
        RecorderState.Idle -> "idle"
    }

    /** 只有等待 / 记录中才有通知；Idle 返回 null，表示不该有这条通知 */
    private fun buildNotification(state: RecorderState): Notification? {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, text) = when (state) {
            is RecorderState.Waiting ->
                "等待接入充电器" to "插上充电器后开始记录，当前电量 ${state.level}%"

            is RecorderState.Recording -> {
                val avg = state.avgPowerW?.let {
                    "，平均 ${String.format(Locale.US, "%.1f", it)} W"
                } ?: ""
                val elapsed = when {
                    state.minutes >= 60 -> "${state.minutes / 60} 小时 ${state.minutes % 60} 分"
                    state.minutes > 0 -> "${state.minutes} 分钟"
                    else -> "刚刚开始"
                }
                "正在充电" to "已记录 $elapsed$avg"
            }

            RecorderState.Idle -> return null
        }


        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 默认前台通知会被延后 10 秒才显示，这里要求立刻出现
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (state) {
            is RecorderState.Recording -> {
                builder.addAction(
                    0,
                    "结束并保存",
                    serviceIntent(ACTION_STOP, STOP_REQUEST),
                )
                applyProgressOrLiveUpdate(builder, state, text)
            }

            is RecorderState.Waiting -> builder.addAction(
                0,
                "取消",
                serviceIntent(ACTION_CANCEL, CANCEL_REQUEST),
            )

            RecorderState.Idle -> Unit
        }
        return builder.build()
    }

    /**
     * 「记录中」这条通知：Android 16 起做成实时更新（Live Update），更早的系统退回普通进度条。
     *
     * 系统提升成实时更新的条件：ongoing、有 contentTitle、没有 RemoteViews、渠道不是
     * IMPORTANCE_MIN、清单里声明了 POST_PROMOTED_NOTIFICATIONS，并且显式请求提升。
     * 这里全部走 NotificationCompat（core 1.18 起带 `ProgressStyle` /
     * `setRequestPromotedOngoing` / `setShortCriticalText`，内部按版本适配），
     * 低版本就不会碰到只在 API 36 才有的类。
     */
    private fun applyProgressOrLiveUpdate(
        builder: NotificationCompat.Builder,
        state: RecorderState.Recording,
        text: String,
    ) {
        val level = state.level.coerceIn(0, 100)
        if (Build.VERSION.SDK_INT < 36) {
            // 老系统没有实时更新：电量写回正文，再挂一条普通进度条
            builder.setContentText("$text，电量 $level%")
            builder.setProgress(100, level, false)
            return
        }

        builder
            .setStyle(
                NotificationCompat.ProgressStyle()
                    // 进度就是电量百分比，总刻度 100
                    .setProgress(level)
                    .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100)))
                    .setProgressTrackerIcon(
                        IconCompat.createWithResource(this, R.drawable.ic_stat_charge),
                    )
                    // 让系统按进度决定配色；状态栏胶囊也会跟着走
                    .setStyledByProgress(true),
            )
            // 状态栏胶囊里直接显示充电功率，而不是电量：电量在状态栏本身就有，
            // 胶囊再重复一遍没有信息量。系统只给 96dp，取一位小数、单位紧跟数字。
            .setShortCriticalText(powerLabel(state))

        // 用户可能在系统设置里关掉本应用的实时更新：关了就照常发普通常驻通知，
        // 不请求提升，免得"请求了却显示不出来"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.canPostPromotedNotifications() != false) {
            builder.setRequestPromotedOngoing(true)
        }
    }

    /** 胶囊文案：优先瞬时功率，没有就退回平均值；两者都没有（刚插上还没采到）显示「充电中」 */
    private fun powerLabel(state: RecorderState.Recording): String {
        val power = state.lastPowerW ?: state.avgPowerW
        return if (power != null && power > 0f) {
            String.format(Locale.US, "%.1fW", power)
        } else {
            "充电中"
        }
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ChargeRecordService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /**
         * 服务是否已在运行。插电复活 job 会在拿到约束后补试两次，
         * 用它把重复的 startForegroundService 挡掉（服务本身幂等，但没必要反复拉起）。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val TAG = "ChargeRecordService"
        private const val CHANNEL_ID = "charge_recording"
        private const val NOTIFICATION_ID = 1001
        private const val STOP_REQUEST = 11
        private const val CANCEL_REQUEST = 12
        private const val ACTION_STOP = "com.smalljelly.batterydata.STOP_RECORDING"
        private const val ACTION_CANCEL = "com.smalljelly.batterydata.CANCEL_RECORDING"
    }
}
