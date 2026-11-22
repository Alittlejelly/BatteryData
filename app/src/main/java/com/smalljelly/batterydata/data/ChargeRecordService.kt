package com.smalljelly.batterydata.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.app.ServiceCompat
import com.smalljelly.batterydata.MainActivity
import com.smalljelly.batterydata.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 记录一次充电时挂着的前台服务。
 *
 * 为什么需要它：一次充电动辄一小时，用户不可能一直开着界面。前台服务可以持续
 * 每 10 秒采样一次，锁屏、切后台、退到桌面都不会断，直到拔电自动结算。
 * 通知栏里也能随时结束或取消这次记录。
 */
class ChargeRecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // 顺序很重要：先把状态恢复出来，再立刻进前台，避免 startForegroundService
        // 之后 5 秒内没调 startForeground 被系统判为 ANR
        ChargeRecorder.attach(this)
        createChannel()
        startForegroundWith(ChargeRecorder.state.value)

        // 采样：把数据喂给记录器
        scope.launch {
            BatteryRepository(applicationContext).stream(10_000L).collect { sample ->
                ChargeRecorder.onSample(sample)
            }
        }
        // 状态：刷新通知；记录结束（Idle）就撤掉通知并退出 —— 不留常驻的"记录已结束"
        scope.launch {
            ChargeRecorder.state.collect { state ->
                if (state is RecorderState.Idle) {
                    getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                    stopSelf()
                } else {
                    updateNotification(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> ChargeRecorder.stopAndSave(this)
            ACTION_CANCEL -> ChargeRecorder.cancel(this)
            else -> {
                val state = ChargeRecorder.state.value
                // 没有在记录就把自己收掉：不要留一条"记录已结束"的常驻通知
                if (state is RecorderState.Idle) {
                    stopSelf()
                } else {
                    updateNotification(state)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
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
            ServiceCompat.startForeground(this, NOTIFICATION_ID, idlePlaceholder(), 0)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /** 只在"误启动"时用一帧，随即被撤掉 */
    private fun idlePlaceholder(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_charge)
            .setContentTitle("充电记录")
            .setSilent(true)
            .setOngoing(true)
            .build()

    // 通知内容去重：每 10 秒采样都会走到这里，但只有「展示内容真的变了」才重发，
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

    private companion object {
        const val CHANNEL_ID = "charge_recording"
        const val NOTIFICATION_ID = 1001
        const val STOP_REQUEST = 11
        const val CANCEL_REQUEST = 12
        const val ACTION_STOP = "com.smalljelly.batterydata.STOP_RECORDING"
        const val ACTION_CANCEL = "com.smalljelly.batterydata.CANCEL_RECORDING"
    }
}
