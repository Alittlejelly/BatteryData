package com.smalljelly.batterydata.data

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 单次记录器的三种状态。 */
sealed interface RecorderState {
    /** 没在记录 */
    data object Idle : RecorderState

    /** 用户已经点了“开始记录”，正在等充电器插上 */
    data class Waiting(val since: Long, val level: Int) : RecorderState

    /** 正在记录一次充电（从插上到拔电） */
    data class Recording(
        val startAt: Long,
        val fromLevel: Int,
        val level: Int,
        val avgPowerW: Float?,
        val lastAt: Long,
        /** 最近一次采样的瞬时值，用来在卡片上实时显示 */
        val lastPowerW: Float? = null,
        val lastCurrentMa: Float? = null,
        val lastVoltageV: Float? = null,
        /** 到目前为止充进去的电能（Wh） */
        val energyWh: Float? = null,
        /** 记录类型：charge / discharge / test */
        val recordType: String = "charge",
        /** 测试场景名 */
        val scenarioName: String? = null,
    ) : RecorderState {
        val minutes: Int get() = ((lastAt - startAt) / 60_000L).toInt().coerceAtLeast(0)
    }
}

/**
 * 单次充电记录的记录器。
 *
 * 流程：用户点“开始记录”→ [arm]。
 *  - 此刻正在充电：立刻开始记；
 *  - 没在充电：进入 [RecorderState.Waiting]，等下一次插上充电器再开始。
 * 开始之后一直采电流 / 电压 / 功率，直到拔电（或充满后仍插着）自动结算成一条记录。
 *
 * 采样由前台服务 [ChargeRecordService] 驱动，App 在前台时页面也会喂同一批采样，
 * 两边靠 [MIN_SAMPLE_GAP_MS] 去重，所以关掉界面、锁屏、切后台都不会中断记录。
 * 会话状态每隔一分钟落一次盘，进程被杀后重新打开 App 会接着记。
 */
object ChargeRecorder {

    private const val TAG = "ChargeRecorder"

    /** 两次有效采样之间的最小间隔：App 前台采样与前台服务采样重叠时用它去重 */
    private const val MIN_SAMPLE_GAP_MS = 4_000L

    /** 每条曲线最多保留的点数，超过就隔点抽稀，避免落盘 JSON 无限膨胀 */
    private const val SERIES_LIMIT = 720

    /** 恢复会话时，超过这个时间没采到新数据就认为上次的记录已废弃 */
    private const val STALE_MS = 3 * 60 * 60 * 1000L

    /**
     * 一次充电没涨够测量门槛时，把「起点」保留下来，下次充电接着累计。
     * 超过这个时间就不再接：库仑计漂移会越来越大，接上去反而不准。
     */
    private const val CARRY_MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L

    /** 估算存储能量用的标称电压 */
    private const val NOMINAL_VOLTAGE = 3.85f

    private const val PREFS = "battery_charge_session"
    private const val KEY_SESSION = "session"
    private const val KEY_CARRY = "carry"

    private var appContext: Context? = null
    private var store: ChargeRecordStore? = null

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /** 记录条数版本号：结算 / 删除后自增，列表据此刷新 */
    private val _recordsVersion = MutableStateFlow(0)
    val recordsVersion: StateFlow<Int> = _recordsVersion.asStateFlow()

    private var session: Session? = null
    private var lastAcceptedAt = 0L
    private var since = 0L
    private var waitedLevel = 0
    private var persistTicks = 0

    // ---------------------------------------------------------------- 对外操作

    /**
     * App 启动 / 插电 / 开机时调一次：初始化 + 恢复上次没记完的会话。
     * 有记录会话，或**正在充电且岛开关开着**（岛模式保活）时拉起前台服务。
     * 服务自己会在拔电 / 关岛 / 记录结束后退出，这里只管拉。
     */
    @Synchronized
    fun ensure(context: Context) {
        init(context)
        if (_state.value is RecorderState.Idle) restore()
        val ctx = appContext ?: return
        // 自动充电记录：没在记、当前正充电时自动开一段（覆盖进程重启 / 开机场景）
        //
        // ⚠ 这里**不能**调 onAutoRecordSettingChanged：它内部会再调一次 ensure，
        // 而 ensure 又回头调它 —— 只要「自动充电记录」开着就是无限互相递归，
        // 启动即 StackOverflowError（现场日志 8188KB 栈打满）。
        // 两者共用 autoStartIfCharging（它不 re-enter ensure）即可。
        if (_state.value is RecorderState.Idle && BatterySettings(ctx).autoRecordEnabled()) {
            autoStartIfCharging(ctx)
        }
        val islandKeepAlive = _state.value is RecorderState.Idle && run {
            val sample = BatteryRepository(ctx).snapshot()
            val settings = BatterySettings(ctx)
            sample.isCharging && settings.islandEnabled() && settings.chargeNoticeEnabled()
        }
        if (_state.value !is RecorderState.Idle || islandKeepAlive) startService(ctx)
    }

    /**
     * 前台服务自己启动时用：只初始化 + 恢复，不再去拉起服务，
     * 否则服务会在自己的 onCreate 里再次 startForegroundService 自己。
     */
    @Synchronized
    fun attach(context: Context) {
        init(context)
        if (_state.value is RecorderState.Idle) restore()
    }

    private fun init(context: Context) {
        val app = context.applicationContext
        if (appContext !== app) {
            appContext = app
            store = ChargeRecordStore(app)
        }
    }

    /**
     * 开始记录：在充电就直接记，否则等插上充电器
     */
    @Synchronized
    fun arm(context: Context) {
        ensure(context)
        if (_state.value !is RecorderState.Idle) return
        val sample = BatteryRepository(context.applicationContext).snapshot()
        if (sample.isCharging || sample.isFull) {
            beginRecording(sample, recordType = "charge")
        } else {
            since = System.currentTimeMillis()
            waitedLevel = sample.level
            _state.value = RecorderState.Waiting(since, sample.level)
            persist()
        }
        startService(context.applicationContext)
    }

    /**
     * 开始一次**放电记录**：记录"不插充电器"期间的耗电过程。
     *
     * 和充电记录对称：充电记录从插上开始、拔电结束；
     * 放电记录从拔电开始、插上充电器结束（见 [onSample] 里的放电分支）。
     * 正在充电时点这个按钮没有意义 —— 那属于充电记录，这里直接不开始。
     *
     * 功率按**绝对值**累计：放电时电流是负的，直接累加会把 Wh 记成负数。
     */
    @Synchronized
    fun startDischarge(context: Context) {
        ensure(context)
        if (_state.value !is RecorderState.Idle) return
        val sample = BatteryRepository(context.applicationContext).snapshot()
        // 正在充电就不开放电记录：这段过程属于"充电"，两边语义别混
        if (sample.isCharging) {
            Log.i(TAG, "当前在充电，不开始放电记录")
            return
        }
        beginRecording(sample, recordType = "discharge")
        startService(context.applicationContext)
    }

    /**
     * 开始一次耗电测试（放电会话）：立即从当前电量开始，直到手动结束或充满电自动结算。
     * @param scenario 场景名，如"游戏""视频""待机"
     */
    @Synchronized
    fun startDischargeTest(context: Context, scenario: String) {
        ensure(context)
        if (_state.value !is RecorderState.Idle) return
        val sample = BatteryRepository(context.applicationContext).snapshot()
        beginRecording(sample, recordType = "test", scenarioName = scenario)
        startService(context.applicationContext)
    }

    /**
     * 自动充电记录开关变化时调用：开启后若正在充电则立刻开始一段记录。
     *
     * 注意不要在这里调 [ensure]（见 [ensure] 里的注释：会互相递归）。
     * 只做「准备好全局状态 + 必要时开一段」这一步。
     */
    @Synchronized
    fun onAutoRecordSettingChanged(context: Context, enabled: Boolean) {
        if (!enabled) return
        init(context)
        val ctx = appContext ?: return
        autoStartIfCharging(ctx)
    }

    /** 正在充电且还没在记录 → 开一段。**不**调用 [ensure]，供 [ensure] 自己复用。 */
    private fun autoStartIfCharging(ctx: Context) {
        if (_state.value !is RecorderState.Idle) return
        val sample = BatteryRepository(ctx).snapshot()
        if (sample.isCharging && !sample.isFull) {
            beginRecording(sample, recordType = "charge")
            startService(ctx)
        }
    }

    /** 放弃当前这次记录（等待中或记录中都直接丢掉） */
    @Synchronized
    fun cancel(context: Context) {
        ensure(context)
        session = null
        _state.value = RecorderState.Idle
        clearPersist()
        // 不直接 stopService：还插着电且岛开着时，服务要转岛模式继续保活；
        // 不满足时服务自己的状态收集器会退出
    }

    /** 立刻结束并保存（通知栏的“结束并保存”） */
    @Synchronized
    fun stopAndSave(context: Context) {
        ensure(context)
        finish(lastSample = null, save = true)
    }

    /** 外部删除记录后调一下，让列表刷新 */
    fun notifyRecordsChanged() {
        _recordsVersion.value++
    }

    /** 通知权限刚被授予时调一下：让前台服务立刻补发一次常驻通知 */
    fun refreshNotification() {
        val ctx = appContext ?: return
        if (_state.value is RecorderState.Idle) return
        startService(ctx)
    }

    // ---------------------------------------------------------------- 采样驱动

    /** 每次采样喂进来（服务 5s / 前台页面 10s），内部做状态机推进 */
    @Synchronized
    fun onSample(sample: BatterySample) {
        val now = System.currentTimeMillis()
        // 超级岛：与记录状态机无关，只要采样就驱动——充电中更新岛，非充电收起。
        // 前台页面与前台服务都会喂采样，故 App 打开或记录中时岛持续刷新。
        appContext?.let { ctx ->
            val settings = BatterySettings(ctx)
            com.smalljelly.batterydata.island.BatteryIslandHelper.onSample(
                ctx,
                sample,
                settings.islandEnabled(),
                settings.chargeNoticeEnabled(),
            )
        }
        when (val current = _state.value) {
            is RecorderState.Idle -> Unit

            is RecorderState.Waiting -> {
                waitedLevel = sample.level
                if (sample.isCharging) {
                    beginRecording(sample)
                } else {
                    _state.value = current.copy(level = sample.level)
                }
            }

            is RecorderState.Recording -> {
                // 前台页面与前台服务会同时喂采样，只认第一条
                if (now - lastAcceptedAt < MIN_SAMPLE_GAP_MS) return
                lastAcceptedAt = now
                val active = session ?: run {
                    _state.value = RecorderState.Idle
                    clearPersist()
                    return
                }
                if (active.recordType != "charge") {
                    // 放电会话：放电 = **不插充电器**的那段时间。
                    // 所以判定和充电记录正好相反 ——
                    // - 拔电（正在放电）→ 继续累计；
                    // - 插上充电器 → **放电过程结束**，立刻结算保存（连续两次确认，防抖动）。
                    // 测试会话（"test"）不同：那是定场景测试，插电不结束，由用户手动收尾。
                    val isTest = active.recordType == "test"
                    when {
                        isTest -> {
                            accumulate(active, sample, now)
                            publish()
                        }

                        sample.isCharging -> {
                            active.idleTicks++
                            if (active.idleTicks >= 2) finish(sample, save = true)
                            else {
                                accumulate(active, sample, now)
                                publish()
                            }
                        }

                        else -> {
                            active.idleTicks = 0
                            accumulate(active, sample, now)
                            publish()
                        }
                    }
                    return
                }
                when {
                    sample.isCharging -> {
                        active.idleTicks = 0
                        active.fullTicks = 0
                        accumulate(active, sample, now)
                        publish()
                    }

                    sample.isFull -> {
                        // 充满后还插着：再观察两次采样就收尾，免得一直挂着
                        active.idleTicks = 0
                        accumulate(active, sample, now)
                        active.fullTicks++
                        publish()
                        if (active.fullTicks >= 2) finish(sample, save = true)
                    }

                    else -> {
                        active.fullTicks = 0
                        active.idleTicks++
                        // 拔电判定：连续两次都没在充才收尾，避免状态抖动把记录截断
                        if (active.idleTicks >= 2) finish(sample, save = true)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun beginRecording(
        sample: BatterySample,
        recordType: String = "charge",
        scenarioName: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val active = Session(
            startAt = now,
            fromLevel = sample.level,
            level = sample.level,
            lastAt = now,
            recordType = recordType,
            scenarioName = scenarioName,
        )
        active.counterStart = sample.chargeCounterMah
        active.counterEnd = sample.chargeCounterMah
        active.designMah = sample.designCapacityMah
        // 测量起点可以比本次记录更早：上一次充电没涨够 15% 的话，这里接着累计
        val carry = loadCarry()?.takeIf { validCarry(it, sample) }
        active.measureFromLevel = carry?.level ?: sample.level
        active.measureCounterStart = carry?.counterMah ?: sample.chargeCounterMah
        active.measureStartedAt = carry?.startedAt ?: now
        session = active
        lastAcceptedAt = now
        persistTicks = 0
        _state.value = RecorderState.Recording(
            startAt = now,
            fromLevel = sample.level,
            level = sample.level,
            avgPowerW = null,
            lastAt = now,
            recordType = recordType,
            scenarioName = scenarioName,
        )
        persist()
    }

    private fun accumulate(active: Session, sample: BatterySample, now: Long) {
        active.level = sample.level
        sample.chargeCounterMah?.let { active.counterEnd = it }
        sample.designCapacityMah?.let { active.designMah = it }

        val power = sample.powerW?.let { abs(it) }?.takeIf { it > 0f }
        if (power != null) {
            active.power.push(power)
            active.peakPower = maxOf(active.peakPower ?: 0f, power)
            // 时间戳与温度必须与功率曲线等长，供分段报告按点索引对齐
            active.timeOffsets.add((now - active.startAt).coerceAtLeast(0L))
            active.temperatures.add(sample.temperatureC ?: Float.NaN)
            sample.currentMa?.let { active.current.push(abs(it)) }
            if (sample.voltageMv > 0) active.voltage.push(sample.voltageMv / 1000f)

            // 间隔按实际时间算；休眠唤醒那种超长间隔不计入
            val hours = ((now - active.lastAt).coerceIn(0L, 120_000L)) / 3_600_000.0
            active.energyWh += power * hours
            active.powerSum += power
            active.powerCount++
        }
        active.lastPowerW = power
        active.lastCurrentMa = sample.currentMa?.let { abs(it) }
        active.lastVoltageV = sample.voltageMv.takeIf { it > 0 }?.let { it / 1000f }
        active.lastAt = now
    }

    private fun publish() {
        val active = session ?: return
        _state.value = RecorderState.Recording(
            startAt = active.startAt,
            fromLevel = active.fromLevel,
            level = active.level,
            avgPowerW = if (active.powerCount > 0) {
                (active.powerSum / active.powerCount).toFloat()
            } else {
                null
            },
            lastAt = active.lastAt,
            lastPowerW = active.lastPowerW,
            lastCurrentMa = active.lastCurrentMa,
            lastVoltageV = active.lastVoltageV,
            energyWh = active.energyWh.takeIf { it > 0.01 }?.toFloat(),
            recordType = active.recordType,
            scenarioName = active.scenarioName,
        )
        persistTicks++
        if (persistTicks % 6 == 0) persist()
    }

    private fun finish(lastSample: BatterySample?, save: Boolean) {
        val active = session
        val now = lastSample?.updatedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
        session = null
        _state.value = RecorderState.Idle
        clearPersist()
        // 服务的去留由它自己的状态收集器决定：还插着电且岛开着 → 转岛模式保活，
        // 否则退出。这里不 stopService，免得把岛模式一起杀掉
        if (active == null || !save) return

        val to = maxOf(active.level, lastSample?.level ?: 0)
        val minutes = ((now - active.startAt) / 60_000L).toInt()
        // 插上又立刻拔掉这种没有意义的片段直接丢掉
        if (minutes < 1 && to - active.fromLevel < 1) return

        val measured = measureFullCapacity(active, to)
        val design = active.designMah
        val health = measured?.let { full ->
            design?.takeIf { it > 100f }?.let { (full / it * 100f).takeIf { v -> v in 40f..105f } }
        }
        // 这次没测出来（涨幅不够门槛 / 库仑计没动）：把测量起点留到下次，接着累计
        if (measured == null) carryOver(active, to, now) else clearCarry()

        val record = ChargeRecord(
            id = active.startAt,
            dateMillis = active.startAt,
            fromLevel = active.fromLevel,
            toLevel = to,
            avgPowerW = if (active.powerCount > 0) {
                (active.powerSum / active.powerCount).toFloat()
            } else {
                null
            },
            lossPercent = estimateLoss(active, to, measured),
            durationMinutes = minutes,
            energyWh = active.energyWh.takeIf { it > 0.01 }?.toFloat(),
            measuredCapacityMah = measured,
            healthPercent = health,
            powerSeries = active.power.downsample(),
            currentSeries = active.current.downsample(),
            voltageSeries = active.voltage.downsample(),
            seriesTimesMs = active.timeOffsets.downsampleLong(),
            temperatureSeries = active.temperatures.downsample(),
            peakPowerW = active.peakPower,
            recordType = active.recordType,
            scenarioName = active.scenarioName,
        )
        store?.add(record)
        _recordsVersion.value++
    }

    /**
     * ΔQ ÷ ΔSoC：这段时间充进去的电量换算成满电容量（要求电量至少涨 15% 才够准）。
     *
     * 分母是整数百分比，涨得越少误差越大（涨 2% 时误差能到 ±25%），所以门槛不能降。
     * 涨不够的那部分不会白充：起点会被 [carryOver] 留下来，下一次充电接着累计。
     */
    private fun measureFullCapacity(active: Session, toLevel: Int): Float? {
        val start = active.measureCounterStart ?: return null
        val end = active.counterEnd ?: return null
        val deltaSoc = (toLevel - active.measureFromLevel) / 100f
        if (deltaSoc < 0.15f) return null
        val deltaQ = end - start
        if (deltaQ <= 0f) return null
        return (deltaQ / deltaSoc).takeIf { it in 800f..20_000f }
    }

    // ---------------------------------------------------------------- 累计测量

    /** 一次没测够的充电：记住起点，等下一段充电接着算 */
    private data class Carry(
        /** 起点电量（%） */
        val level: Int,
        /** 起点时的库仑计读数（mAh） */
        val counterMah: Float,
        /** 起点的墙钟时间 */
        val startedAt: Long,
        /** 写下这条 carry 的时间，用来判断是否过期 */
        val savedAt: Long,
        /** 写下时的开机时长，用来识别期间是否重启过（重启后库仑计会归零） */
        val elapsedAt: Long,
    )

    /**
     * 续上一次没测够的起点。要求：库仑计读数没有倒退（没重启）、没放太久、
     * 电量也确实比起点高。
     */
    private fun validCarry(carry: Carry, sample: BatterySample): Boolean {
        val counter = sample.chargeCounterMah ?: return false
        if (counter < carry.counterMah) return false
        if (sample.level <= carry.level) return false
        val now = System.currentTimeMillis()
        if (now - carry.savedAt > CARRY_MAX_AGE_MS) return false
        // 重启后 elapsedRealtime 会倒退，说明库仑计被清零过，接上去会算出假容量
        if (SystemClock.elapsedRealtime() < carry.elapsedAt) return false
        return true
    }

    private fun carryOver(active: Session, toLevel: Int, now: Long) {
        val ctx = appContext ?: return
        val counterStart = active.measureCounterStart ?: return
        // 连电量都没动，没有可累计的东西
        if (toLevel <= active.measureFromLevel) return
        val json = JSONObject().apply {
            put("level", active.measureFromLevel)
            put("counter", counterStart.toDouble())
            put("startedAt", active.measureStartedAt)
            put("savedAt", now)
            put("elapsedAt", SystemClock.elapsedRealtime())
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CARRY, json.toString())
            .apply()
    }

    private fun loadCarry(): Carry? {
        val ctx = appContext ?: return null
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CARRY, null)
            ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return Carry(
            level = json.optInt("level"),
            counterMah = json.optDouble("counter").toFloat(),
            startedAt = json.optLong("startedAt"),
            savedAt = json.optLong("savedAt"),
            elapsedAt = json.optLong("elapsedAt"),
        )
    }

    private fun clearCarry() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CARRY).apply()
    }

    /**
     * 充电损耗：输入电能里没有进入电池的那部分。
     * 进入电池的能量按 电量增量 × 满电容量 × 标称电压 估算。
     */
    private fun estimateLoss(active: Session, toLevel: Int, measured: Float?): Float? {
        if (active.energyWh <= 0.0) return null
        val full = measured ?: active.designMah ?: return null
        val deltaSoc = (toLevel - active.fromLevel) / 100f
        if (deltaSoc <= 0f) return null
        val storedWh = deltaSoc * full / 1000f * NOMINAL_VOLTAGE
        if (storedWh <= 0f) return null
        val loss = (1.0 - storedWh / active.energyWh) * 100.0
        // 采样粒度决定了这只能是估算，超出合理范围宁可不显示
        return loss.toFloat().takeIf { it in 0.0..40.0 }
    }

    // ---------------------------------------------------------------- 落盘 / 恢复

    private fun persist() {
        val ctx = appContext ?: return
        val json = JSONObject()
        when (val current = _state.value) {
            is RecorderState.Idle -> {
                clearPersist()
                return
            }

            is RecorderState.Waiting -> {
                json.put("mode", "waiting")
                json.put("since", current.since)
                json.put("level", current.level)
            }

            is RecorderState.Recording -> {
                val active = session ?: return
                json.put("mode", "recording")
                json.put("startAt", active.startAt)
                json.put("fromLevel", active.fromLevel)
                json.put("level", active.level)
                json.put("lastAt", active.lastAt)
                json.put("energy", active.energyWh)
                json.put("powerSum", active.powerSum)
                json.put("powerCount", active.powerCount)
                active.counterStart?.let { json.put("counterStart", it.toDouble()) }
                active.counterEnd?.let { json.put("counterEnd", it.toDouble()) }
                active.designMah?.let { json.put("design", it.toDouble()) }
                json.put("measureFrom", active.measureFromLevel)
                active.measureCounterStart?.let { json.put("measureStart", it.toDouble()) }
                json.put("measureAt", active.measureStartedAt)
                json.put("power", active.power.toJsonArray())
                json.put("current", active.current.toJsonArray())
                json.put("voltage", active.voltage.toJsonArray())
                json.put("times", JSONArray().apply { active.timeOffsets.forEach { put(it) } })
                json.put("temps", active.temperatures.toJsonArray())
                active.peakPower?.let { json.put("peak", it.toDouble()) }
                if (active.recordType != "charge") json.put("type", active.recordType)
                active.scenarioName?.let { json.put("scenario", it) }
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSION, json.toString())
            .apply()
    }

    private fun restore() {
        val ctx = appContext ?: return
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSION, null)
            ?: return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            clearPersist()
            return
        }
        when (json.optString("mode")) {
            "waiting" -> {
                since = json.optLong("since")
                waitedLevel = json.optInt("level")
                _state.value = RecorderState.Waiting(since, waitedLevel)
            }

            "recording" -> {
                val lastAt = json.optLong("lastAt")
                // 太久没采到数据（服务被杀 / 手机重启）就当作废，不要攒出一条假记录
                if (System.currentTimeMillis() - lastAt > STALE_MS) {
                    clearPersist()
                    return
                }
                val active = Session(
                    startAt = json.optLong("startAt"),
                    fromLevel = json.optInt("fromLevel"),
                    level = json.optInt("level"),
                    lastAt = lastAt,
                    recordType = json.optString("type", "charge").ifEmpty { "charge" },
                    scenarioName = json.optString("scenario").takeIf { it.isNotEmpty() },
                )
                if (json.has("peak")) active.peakPower = json.optDouble("peak").toFloat()
                active.energyWh = json.optDouble("energy", 0.0)
                active.powerSum = json.optDouble("powerSum", 0.0)
                active.powerCount = json.optInt("powerCount", 0)
                active.counterStart =
                    if (json.has("counterStart")) json.optDouble("counterStart").toFloat() else null
                active.counterEnd =
                    if (json.has("counterEnd")) json.optDouble("counterEnd").toFloat() else null
                active.designMah =
                    if (json.has("design")) json.optDouble("design").toFloat() else null
                active.measureFromLevel = json.optInt("measureFrom", active.fromLevel)
                active.measureCounterStart = if (json.has("measureStart")) {
                    json.optDouble("measureStart").toFloat()
                } else {
                    active.counterStart
                }
                active.measureStartedAt = json.optLong("measureAt", active.startAt)
                active.power.addAll(json.optJSONArray("power").toFloatList())
                active.current.addAll(json.optJSONArray("current").toFloatList())
                active.voltage.addAll(json.optJSONArray("voltage").toFloatList())
                session = active
                lastAcceptedAt = lastAt
                _state.value = RecorderState.Recording(
                    startAt = active.startAt,
                    fromLevel = active.fromLevel,
                    level = active.level,
                    avgPowerW = if (active.powerCount > 0) {
                        (active.powerSum / active.powerCount).toFloat()
                    } else {
                        null
                    },
                    lastAt = active.lastAt,
                )
            }

            else -> clearPersist()
        }
    }

    private fun clearPersist() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SESSION).apply()
    }

    // ---------------------------------------------------------------- 服务

    private fun startService(context: Context) {
        // 已在运行就别再拉起：插电复活 job 会补试两次，重复 start 只会多走一遍启动流程
        if (ChargeRecordService.isRunning) {
            Log.i(TAG, "服务已在运行，跳过重复启动")
            return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChargeRecordService::class.java),
            )
            Log.i(TAG, "startForegroundService 已发出")
        } catch (e: Exception) {
            // 最常见是 ForegroundServiceStartNotAllowedException：从后台（含 job / 广播）
            // 启动前台服务被系统拒绝。以前这里用 runCatching 静默吞掉，
            // "被杀后插电不复活"完全无从排查 —— 现在至少能在 logcat 里看到原因。
            Log.e(TAG, "startForegroundService 失败", e)
        }
    }

    // ---------------------------------------------------------------- 数据结构

    private class Session(
        val startAt: Long,
        var fromLevel: Int,
        var level: Int,
        var lastAt: Long,
        val recordType: String = "charge",
        val scenarioName: String? = null,
    ) {
        var energyWh = 0.0
        var powerSum = 0.0
        var powerCount = 0
        var peakPower: Float? = null
        var counterStart: Float? = null
        var counterEnd: Float? = null
        var designMah: Float? = null
        /** 健康度测量的起点，可能来自上一次没测够的充电（累计测量） */
        var measureFromLevel: Int = fromLevel
        var measureCounterStart: Float? = null
        var measureStartedAt: Long = startAt
        var lastPowerW: Float? = null
        var lastCurrentMa: Float? = null
        var lastVoltageV: Float? = null
        var idleTicks = 0
        var fullTicks = 0
        val power = mutableListOf<Float>()
        val current = mutableListOf<Float>()
        val voltage = mutableListOf<Float>()
        /** 与 power 等长：每个点的相对时间偏移 */
        val timeOffsets = mutableListOf<Long>()
        /** 与 power 等长：每个点的温度 */
        val temperatures = mutableListOf<Float>()
    }
}

/** 曲线入库前抽稀：超过上限就隔点抽一半，保证细节还在、体积可控 */
private fun List<Float>.downsample(): List<Float> {
    if (size <= 120) return this
    val step = size.toFloat() / 120
    return List(120) { index -> this[(index * step).toInt().coerceAtMost(lastIndex)] }
}

/** 时间戳列与功率列用同样的抽稀比例，保证等长对齐 */
private fun List<Long>.downsampleLong(): List<Long> {
    if (size <= 120) return this
    val step = size.toFloat() / 120
    return List(120) { index -> this[(index * step).toInt().coerceAtMost(lastIndex)] }
}

/** 记录过程中的原始点也不能无限长：超上限就整体抽稀一半 */
private fun MutableList<Float>.push(value: Float) {
    add(value)
    if (size <= 720) return
    var i = 1
    while (i < size) {
        removeAt(i)
        i++
    }
}

private fun List<Float>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { array.put((it * 100).toInt() / 100.0) }
    return array
}

private fun JSONArray?.toFloatList(): List<Float> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) add(optDouble(i).toFloat())
    }
}
