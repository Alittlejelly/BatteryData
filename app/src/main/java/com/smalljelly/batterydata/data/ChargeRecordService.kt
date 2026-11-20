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

    private fun updateNotification(state: RecorderState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildNotification(state)
        runCatching {
            if (notification == null) manager.cancel(NOTIFICATION_ID)
            else manager.notify(NOTIFICATION_ID, notification)
        }
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
                "正在记录本次充电" to
                    "已充 $elapsed$avg，电量 ${state.level}%"
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
            is RecorderState.Recording -> builder.addAction(
                0,
                "结束并保存",
                serviceIntent(ACTION_STOP, STOP_REQUEST),
            )

            is RecorderState.Waiting -> builder.addAction(
                0,
                "取消",
                serviceIntent(ACTION_CANCEL, CANCEL_REQUEST),
            )

            RecorderState.Idle -> Unit
        }
        return builder.build()
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
