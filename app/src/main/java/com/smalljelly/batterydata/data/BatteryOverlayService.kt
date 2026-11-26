package com.smalljelly.batterydata.data

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/**
 * 实时电量悬浮窗前台服务：默认显示极简小胶囊，点击展开详情，支持拖动。
 *
 * 收起态只显示电量百分比（充电时追加“· 功率”），高度不超过 40dp；
 * 展开态沿用两行详情（电量/状态 + 功率/电流/温度），切换带 160ms 尺寸与透明度过渡。
 */
class BatteryOverlayService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            // 兜底：任何未捕获异常都只停掉悬浮窗服务，绝不让它带崩整个应用进程。
            // 之前这里没有 handler，采样/渲染抛异常会直接杀死进程；配合 START_STICKY
            // 会被系统反复拉起再崩，表现就是“打开应用一直闪退”。
            Log.e(TAG, "悬浮窗协程未捕获异常，停止服务", e)
            stopSafely()
        },
    )

    private var windowManager: WindowManager? = null
    private var overlayView: CapsuleFrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var line1: TextView? = null
    private var line2: TextView? = null
    private var lastSample: BatterySample? = null
    private var detailed = false
    private var viewAttached = false
    private var sizeAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 先进入前台，避免 startForegroundService 后 5 秒内未调用 startForeground 被判 ANR。
        // 失败就必须退出：留在后台继续跑会被系统以
        // ForegroundServiceDidNotStartInTimeException 直接杀掉整个进程。
        if (!enterForeground()) {
            Log.w(TAG, "无法进入前台，悬浮窗服务退出")
            stopSelf()
            return
        }
        if (!runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，悬浮窗服务退出")
            stopSelf()
            return
        }
        if (!addOverlay()) {
            Log.w(TAG, "悬浮窗添加失败，服务退出")
            stopSelf()
            return
        }
        // 全部就绪后才标记运行，避免失败后留下“假运行”状态挡住下次重试
        running = true
        startSampling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 从通知里停止：同步关掉设置开关，下次打开应用不再自动恢复
            runCatching { BatterySettings(this).setOverlayEnabled(false) }
                .onFailure { Log.w(TAG, "关闭悬浮窗设置开关失败: ${it.message}", it) }
            // 立即摘窗口并复位 running，别等 onDestroy 期间还留着窗口和“运行中”状态
            stopSafely()
            return START_NOT_STICKY
        }
        // 不用 START_STICKY：服务若因设备限制初始化失败，sticky 会让系统反复拉起、
        // 反复失败，用户看到的就是应用持续闪退。需要时由开关或前台 Activity 再次启动。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    /** 安全停止：先摘窗口再停服务，避免异常路径里重复 removeView。 */
    private fun stopSafely() {
        running = false
        removeOverlay()
        stopSelf()
    }

    /** 建立通知渠道 battery_overlay。渠道创建失败不应阻止服务启动。 */
    private fun createChannel() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "电量悬浮窗",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "实时电量悬浮窗运行中"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }.onFailure { Log.w(TAG, "创建通知渠道失败: ${it.message}", it) }
    }

    /** small icon 优先用项目图标，不存在则退回系统图标。 */
    private fun smallIconRes(): Int = runCatching {
        val id = resources.getIdentifier("ic_stat_battery_charging", "drawable", packageName)
        if (id != 0) id else android.R.drawable.ic_dialog_info
    }.getOrDefault(android.R.drawable.ic_dialog_info)

    /** 点击通知停止服务。 */
    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BatteryOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle("电量悬浮窗运行中")
            .setContentText("点击停止悬浮窗")
            .setContentIntent(stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 进入前台（API 34+ 声明特殊用途类型，失败时退回不带类型以免ANR）。返回是否成功。 */
    private fun enterForeground(): Boolean {
        // 通知构建本身也可能因 ROM 限制抛异常，失败按“进前台失败”处理，绝不带崩进程
        val notifResult = runCatching { buildNotification() }
        val notification = notifResult.getOrNull()
        if (notification == null) {
            Log.e(TAG, "构建前台通知失败: ${notifResult.exceptionOrNull()?.message}")
            return false
        }
        val typed = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType())
        }
        if (typed.isSuccess) return true
        Log.w(TAG, "startForeground(带类型) 失败: ${typed.exceptionOrNull()?.message}")
        return runCatching {
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure { e ->
            Log.e(TAG, "startForeground 兜底也失败", e)
        }.isSuccess
    }

    private fun fgsType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    ).toInt()

    /** 用代码构建悬浮窗 View 并加到 WindowManager。返回是否真正挂上。 */
    private fun addOverlay(): Boolean {
        val wm = runCatching { getSystemService(WindowManager::class.java) }.getOrNull()
            ?: return false
        return runCatching { buildAndAttachOverlay(wm) }.getOrElse { e ->
            // 构建/挂载过程中任何异常都只让它返回 false，由 onCreate 走正常退出路径
            Log.e(TAG, "构建悬浮窗失败", e)
            removeOverlay()
            false
        }
    }

    private fun buildAndAttachOverlay(wm: WindowManager): Boolean {
        val l1 = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, COLLAPSED_TEXT_SP)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            text = "--%"
        }
        val l2 = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, EXPANDED_LINE2_SP)
            setTextColor(Color.parseColor("#B3FFFFFF"))
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            text = "— W · — mA · — ℃"
            visibility = View.GONE
        }
        line1 = l1
        line2 = l2

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(l1, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(l2, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2f) })
        }

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // 先给一个收起态的近似全圆角，真实值在每次布局后按 高度/2 修正
            cornerRadius = dp(18f).toFloat()
            setColor(Color.parseColor("#CC1C1B1F"))
        }
        val container = CapsuleFrameLayout(this).apply {
            background = bg
            maxHeightPx = dp(40f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            addView(texts, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val height = v.height
                if (height <= 0) return@addOnLayoutChangeListener
                // 收起态严格全圆（高度/2）；展开态沿用原本约 28dp 的圆角
                val collapsed = (v as? CapsuleFrameLayout)?.isCollapsed ?: true
                bg.cornerRadius = if (collapsed) height / 2f else dp(28f).toFloat()
            }
        }
        overlayView = container

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16f)
            y = dp(160f)
        }
        layoutParams = lp

        attachTouch(container, lp)
        val added = runCatching { wm.addView(container, lp) }
            .onFailure { Log.w(TAG, "addView 失败: ${it.message}", it) }
            .isSuccess
        if (!added) {
            // addView 失败（权限被回收 / ROM 拒绝窗口类型）就彻底清掉引用，
            // 不要留一个“服务在跑但没窗口”的假状态；顺带尽力摘一次，防个别 ROM 部分挂载
            runCatching { wm.removeViewImmediate(container) }
            overlayView = null
            layoutParams = null
            line1 = null
            line2 = null
            viewAttached = false
            return false
        }
        windowManager = wm
        viewAttached = true
        lastSample?.let { render(it) }
        return true
    }

    /** 拖动 + 点击切换：移动超过阈值视为拖动，否则视为点击。 */
    private fun attachTouch(view: View, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var paramX = 0
        var paramY = 0
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    paramX = lp.x
                    paramY = lp.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        lp.x = paramX + dx.toInt()
                        lp.y = paramY + dy.toInt()
                        runCatching { windowManager?.updateViewLayout(view, lp) }
                            .onFailure { Log.w(TAG, "拖动悬浮窗失败: ${it.message}", it) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 只有没超过拖动阈值才算点击；切换本身再套一层 runCatching，
                        // 保证触摸回调里不会漏出未捕获异常
                        runCatching { setExpanded(!detailed, animate = true) }
                            .onFailure { Log.w(TAG, "切换悬浮窗展开状态失败: ${it.message}", it) }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    moved = false
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 应用收起/展开态的字体、内边距与行可见性。
     * 只改内容样式，窗口尺寸由 [setExpanded] 统一处理。
     */
    private fun applyState(expanded: Boolean) {
        val container = overlayView ?: return
        val l1 = line1 ?: return
        val l2 = line2 ?: return
        if (expanded) {
            l1.setTextSize(TypedValue.COMPLEX_UNIT_SP, EXPANDED_LINE1_SP)
            l2.setTextSize(TypedValue.COMPLEX_UNIT_SP, EXPANDED_LINE2_SP)
            l2.visibility = View.VISIBLE
            container.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            container.maxHeightPx = Int.MAX_VALUE
            container.isCollapsed = false
        } else {
            l1.setTextSize(TypedValue.COMPLEX_UNIT_SP, COLLAPSED_TEXT_SP)
            l2.visibility = View.GONE
            container.setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            container.maxHeightPx = dp(40f)
            container.isCollapsed = true
        }
        lastSample?.let { render(it) }
    }

    /**
     * 切换收起/展开。尺寸用 ValueAnimator 从当前 px 尺寸过渡到目标尺寸，
     * 透明度用 View.animate().setDuration(160) 同步淡入，全程不引入 Compose。
     * 触摸回调已在外层 runCatching，这里任何步骤失败都只打日志不抛出。
     */
    private fun setExpanded(expanded: Boolean, animate: Boolean) {
        if (detailed == expanded) return
        val container = overlayView ?: return
        val wm = windowManager ?: return
        val lp = layoutParams ?: return
        detailed = expanded

        cancelSizeAnimation()

        val startWidth = container.width.takeIf { it > 0 } ?: lp.width.takeIf { it > 0 } ?: 0
        val startHeight = container.height.takeIf { it > 0 } ?: lp.height.takeIf { it > 0 } ?: 0
        applyState(expanded)

        // 还没挂上窗口或拿不到当前尺寸时直接切到目标，不做动画
        if (!viewAttached || startWidth <= 0 || startHeight <= 0 || !animate) {
            applyWrapSize(wm, container, lp)
            return
        }

        val measured = runCatching {
            // 用无约束测量拿到目标状态的自然尺寸（宽度同样 wrap_content）
            container.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            container.measuredWidth to container.measuredHeight
        }.getOrElse { e ->
            Log.w(TAG, "预测量悬浮窗失败，直接切换尺寸: ${e.message}", e)
            applyWrapSize(wm, container, lp)
            return
        }
        val endWidth = measured.first.takeIf { it > 0 } ?: startWidth
        val endHeight = measured.second.takeIf { it > 0 } ?: startHeight

        // 先把窗口钉在当前尺寸，避免修改内容后窗口瞬间跳到目标大小
        lp.width = startWidth
        lp.height = startHeight
        val pinned = runCatching { wm.updateViewLayout(container, lp) }
            .onFailure { Log.w(TAG, "固定悬浮窗起始尺寸失败: ${it.message}", it) }
            .isSuccess
        if (!pinned) {
            applyWrapSize(wm, container, lp)
            return
        }

        container.animate().cancel()
        container.alpha = 0.55f
        container.animate().setDuration(ANIM_DURATION_MS).alpha(1f).start()

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                if (overlayView !== container) return@addUpdateListener
                val fraction = va.animatedValue as Float
                lp.width = (startWidth + (endWidth - startWidth) * fraction).toInt()
                lp.height = (startHeight + (endHeight - startHeight) * fraction).toInt()
                // 动画期间窗口可能已被移除，updateViewLayout 必须吞掉异常
                runCatching { windowManager?.updateViewLayout(container, lp) }
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (sizeAnimator === animation) sizeAnimator = null
                    if (canceled || overlayView !== container) return
                    // 动画结束交还 wrap_content，后续文本宽度变化仍能自适应
                    applyWrapSize(wm, container, lp)
                    container.alpha = 1f
                }
            })
        }
        sizeAnimator = animator
        animator.start()
    }

    /** 把窗口宽高交还给 wrap_content；任何失败只记日志。 */
    private fun applyWrapSize(
        wm: WindowManager,
        container: View,
        lp: WindowManager.LayoutParams,
    ) {
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        runCatching { wm.updateViewLayout(container, lp) }
            .onFailure { Log.w(TAG, "应用悬浮窗自适应尺寸失败: ${it.message}", it) }
    }

    /** 取消尺寸动画，防止 View 移除后动画仍在 updateViewLayout。 */
    private fun cancelSizeAnimation() {
        val animator = sizeAnimator
        sizeAnimator = null
        runCatching { animator?.cancel() }
    }

    /** 每秒采样，只更新文本，不重建 View。任何异常都只停服务，不让进程崩溃。 */
    private fun startSampling() {
        scope.launch {
            try {
                BatteryRepository(applicationContext).stream(1_000L).collect { sample ->
                    lastSample = sample
                    render(sample)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "悬浮窗采样异常，停止服务", e)
                stopSafely()
            }
        }
    }

    /** 刷新文本：详情两行全显；收起态只显示电量，充电时追加“· 功率”。 */
    private fun render(sample: BatterySample) {
        val power = sample.powerW?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        if (detailed) {
            line1?.text = "${sample.level}% · ${sample.statusText}"
            val current = sample.currentMa?.let { String.format(Locale.US, "%.0f", it) } ?: "—"
            val temp = sample.temperatureC?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            line2?.text = "$power W · $current mA · $temp ℃"
            line2?.visibility = View.VISIBLE
        } else {
            val shortPower = sample.powerW?.let { formatPowerShort(it) }
            line1?.text = when {
                sample.isCharging && shortPower != null -> "${sample.level}% · $shortPower"
                sample.isCharging -> "${sample.level}% ⚡"
                else -> "${sample.level}%"
            }
            line2?.visibility = View.GONE
        }
    }

    /** 收起态功率格式：18.0 → 18W，18.4 → 18.4W。 */
    private fun formatPowerShort(watts: Float): String {
        val rounded = String.format(Locale.US, "%.1f", watts).toFloat()
        return if (abs(rounded - rounded.toInt()) < 0.01f) {
            "${rounded.toInt()}W"
        } else {
            String.format(Locale.US, "%.1fW", rounded)
        }
    }

    /** 移除悬浮窗并释放引用。重复调用安全：viewAttached 保证 removeView 只执行一次。 */
    private fun removeOverlay() {
        cancelSizeAnimation()
        val view = overlayView
        val wm = windowManager
        val attached = viewAttached

        overlayView = null
        layoutParams = null
        windowManager = null
        line1 = null
        line2 = null
        viewAttached = false

        if (view != null && attached) {
            // 两个清理动作分开兜底：即使取消失败，也一定要尝试摘掉窗口
            runCatching { view.animate().cancel() }
            runCatching { wm?.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "移除悬浮窗失败: ${it.message}", it) }
        }
    }

    companion object {
        private const val TAG = "BatteryOverlayService"
        private const val CHANNEL_ID = "battery_overlay"
        private const val NOTIFICATION_ID = 3003
        private const val ACTION_STOP = "com.smalljelly.batterydata.STOP_OVERLAY"

        /** 展开/收起过渡时长。 */
        private const val ANIM_DURATION_MS = 160L

        /** 收起态字号：15sp 等宽。 */
        private const val COLLAPSED_TEXT_SP = 15f

        /** 展开态两行字号：18sp / 14sp。 */
        private const val EXPANDED_LINE1_SP = 18f
        private const val EXPANDED_LINE2_SP = 14f

        @Volatile
        private var running = false

        /** UI 点击开启时调用；后台启动受限时只记录日志，不崩溃。 */
        fun start(context: Context) {
            if (running) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BatteryOverlayService::class.java),
                )
            }.onFailure { e ->
                Log.w(TAG, "启动悬浮窗失败（可能被后台启动限制）: ${e.message}", e)
            }
        }

        /** 关闭悬浮窗。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, BatteryOverlayService::class.java))
            }.onFailure { Log.w(TAG, "停止悬浮窗失败: ${it.message}", it) }
        }

        /** 服务是否运行中。 */
        fun isRunning(): Boolean = running
    }
}

/**
 * 悬浮窗根容器：收起态把高度硬限制在 40dp 以内（超大字体缩放下也不会撑破胶囊）。
 * 展开/动画期间窗口高度由 WindowManager.LayoutParams 的精确像素控制，
 * MeasureSpec 为 EXACTLY 时不做上限裁剪，否则会打断尺寸动画。
 */
private class CapsuleFrameLayout(context: Context) : FrameLayout(context) {
    var maxHeightPx: Int = Int.MAX_VALUE
    var isCollapsed: Boolean = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 动画/展开时窗口给的是精确像素，不能按收起态上限再裁一次
        if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY) return
        if (maxHeightPx in 1 until measuredHeight) {
            setMeasuredDimension(measuredWidth, maxHeightPx)
        }
    }
}
