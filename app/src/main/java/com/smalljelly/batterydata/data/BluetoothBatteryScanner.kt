package com.smalljelly.batterydata.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 页面状态。
 *
 * 三个列表分开表达三件事，混在一起就没法区分「没连上」和「连上了但没加进列表」：
 * - [connected]：系统报告**当前已连接**的设备（耳机 / 手环 / 键盘鼠标）
 * - [tracked]：用户勾选要跟踪的那几台 —— 只有它们才去读电量、才进「我的设备」
 * - [available]：已连接但还没被勾选的（候选）
 */
data class DevicesState(
    val connected: List<DeviceBattery> = emptyList(),
    val tracked: List<DeviceBattery> = emptyList(),
    val refreshing: Boolean = false,
    val readingName: String? = null,
    val blockedReason: String? = null,
    val refreshedAt: Long = 0L,
) {
    val available: List<DeviceBattery>
        get() = connected.filterNot { c -> tracked.any { it.address == c.address } }
}

/**
 * 多设备电量：只读**系统当前已连接**的蓝牙设备，并且只读用户勾选的那几台。
 *
 * 为什么是"已连接"而不是"已配对"：配对列表里有耳机、车机、旧手机、电视……绝大多数
 * 此刻根本没连上，读电量必然失败，列表于是刷成一堆横线。系统当前连着的才是"正在用"的设备。
 *
 * 为什么让用户勾选：即使连上了，也不是每台都值得显示（比如车机、键盘）。
 * 自动全加进来会挤满列表，用户还得逐个删。
 *
 * 读电量的两条路：
 * 1. **GATT 标准电量服务**（`0x180F` / `0x2A19`）—— 手环、部分耳机、部分键盘鼠标走这个；
 * 2. 读不到时回退到**系统缓存**：`BluetoothDevice` 的 `BluetoothClass` 与部分厂商把电量
 *    塞在 SDP / 广播里，系统 Bluetooth 会缓存，但不保证所有机型都有。
 *
 * ⚠ 关键：`connectGatt` 必须用 `TRANSPORT_AUTO`，不能用 `TRANSPORT_LE`。
 * 真无线耳机大多是 **BR/EDR 单模**（`dumpsys bluetooth_manager` 里显示 `LE: N/A`），
 * 用 LE 去连它永远连不上，`onServicesDiscovered` 也不会来 —— 这就是"连耳机都读不出电量"。
 */
class BluetoothBatteryScanner(context: Context) {

    private val appContext = context.applicationContext
    private val store = DeviceBatteryStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DevicesState(tracked = store.load()))
    val state: StateFlow<DevicesState> = _state.asStateFlow()

    private var job: Job? = null
    private var probeJob: Job? = null

    /** 最近一轮刷新拿到的已连接设备对象（探测要用真实的 BluetoothDevice，不能只靠 MAC） */
    private var lastConnectedDevices: List<BluetoothDevice> = emptyList()

    private val adapter: BluetoothAdapter?
        get() = runCatching {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

    /** 只需要 BLUETOOTH_CONNECT：读连接列表、连 GATT、读设备名都归它管 */
    fun permissionsGranted(): Boolean = hasConnectPermission(appContext)

    fun isReady(): Boolean = adapter?.isEnabled == true

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(refreshing = false, readingName = null)
    }

    /** 用户勾选一台设备加入跟踪列表（立刻尝试读电量） */
    fun track(context: Context, device: DeviceBattery) {
        val current = _state.value.tracked
        if (current.any { it.address == device.address }) return
        val next = current + device.copy(connected = true)
        store.save(next)
        _state.value = _state.value.copy(tracked = next)
        refresh()
    }

    /** 取消跟踪（从列表移除） */
    fun untrack(address: String) {
        val next = _state.value.tracked.filterNot { it.address == address }
        store.save(next)
        _state.value = _state.value.copy(tracked = next)
    }

    fun clearHistory() {
        store.clear()
        _state.value = _state.value.copy(tracked = emptyList())
    }

    /**
     * 跑一轮刷新：拿系统「当前已连接」的设备 → 只对已勾选的读电量 → 发布。
     */
    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!isReady()) {
                _state.value = _state.value.copy(
                    refreshing = false,
                    readingName = null,
                    blockedReason = if (adapter == null) "这台设备没有蓝牙" else "蓝牙未开启",
                )
                return@launch
            }
            if (!permissionsGranted()) {
                _state.value = _state.value.copy(
                    refreshing = false,
                    readingName = null,
                    blockedReason = "缺少蓝牙权限",
                )
                return@launch
            }

            _state.value = _state.value.copy(
                refreshing = true,
                blockedReason = null,
                readingName = null,
            )

            val connected = connectedDevices()
            lastConnectedDevices = connected
            val trackedAddresses = _state.value.tracked.map { it.address }.toSet()

            // 已连接设备先铺成"未读电量"的骨架，界面立刻有东西可点
            val connectedList = connected.map { device ->
                val saved = _state.value.tracked.firstOrNull { it.address == device.address }
                DeviceBattery(
                    address = device.address,
                    name = saved?.name ?: safeName(device) ?: device.address,
                    level = saved?.level,
                    updatedAt = saved?.updatedAt ?: 0L,
                    connected = true,
                )
            }
            _state.value = _state.value.copy(connected = rank(connectedList))

            // 只读用户勾选过的那几台
            val trackedResult = _state.value.tracked.associateBy { it.address }.toMutableMap()
            for ((index, device) in connected.withIndex()) {
                if (!isActive) return@launch
                if (device.address !in trackedAddresses) continue
                val name = safeName(device) ?: trackedResult[device.address]?.name ?: device.address
                _state.value = _state.value.copy(readingName = name)

                val level = readBatteryLevel(device)
                val previous = trackedResult[device.address]
                trackedResult[device.address] = DeviceBattery(
                    address = device.address,
                    name = name,
                    level = level ?: previous?.level,
                    updatedAt = if (level != null) System.currentTimeMillis() else previous?.updatedAt ?: 0L,
                    connected = true,
                )
                _state.value = _state.value.copy(
                    tracked = rank(trackedResult.values.toList()),
                    connected = rank(
                        connectedList.map { c ->
                            if (c.address == device.address) {
                                trackedResult[device.address] ?: c
                            } else {
                                c
                            }
                        },
                    ),
                )
                if (index < connected.lastIndex) delay(READ_GAP_MS)
            }

            // 已连接但没勾选的，保持连接态；列表里只留勾选过的
            val finalTracked = rank(trackedResult.values.toList())
            store.save(finalTracked.map { it.copy(connected = false) })
            _state.value = DevicesState(
                connected = rank(
                    connectedList.map { c -> trackedResult[c.address] ?: c },
                ),
                tracked = finalTracked,
                refreshing = false,
                readingName = null,
                blockedReason = null,
                refreshedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 系统当前**已连接**的蓝牙设备。
     *
     * 走 profile 代理（HEADSET + A2DP + GATT）而不是 `bondedDevices`：
     * 后者是"配对过"，前者才是"此刻连着"。GATT 代理能覆盖手环 / 键盘鼠标这类没有音频 profile 的。
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectedDevices(): List<BluetoothDevice> {
        val btAdapter = adapter ?: return emptyList()
        val found = linkedMapOf<String, BluetoothDevice>()

        val profiled = withTimeoutOrNull(PROFILE_QUERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var remaining = PROFILE_LIST.size
                val opened = mutableListOf<BluetoothProfile?>()
                fun maybeFinish() {
                    if (remaining > 0) return
                    runCatching { btAdapter.closeProfileProxy(BluetoothProfile.HEADSET, opened[0]) }
                    runCatching { btAdapter.closeProfileProxy(BluetoothProfile.A2DP, opened[1]) }
                    runCatching { btAdapter.closeProfileProxy(BluetoothProfile.GATT, opened[2]) }
                    if (cont.isActive) cont.resume(Unit)
                }
                PROFILE_LIST.forEachIndexed { index, profile ->
                    opened.add(null)
                    runCatching {
                        btAdapter.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
                            override fun onServiceConnected(type: Int, proxy: BluetoothProfile?) {
                                opened[index] = proxy
                                runCatching {
                                    proxy?.connectedDevices?.forEach { d ->
                                        d.address?.let { found[it] = d }
                                    }
                                }.onFailure { if (it !is SecurityException) Log.w(TAG, "读已连接设备失败", it) }
                                remaining--
                                maybeFinish()
                            }

                            override fun onServiceDisconnected(type: Int) {
                                remaining--
                                maybeFinish()
                            }
                        }, profile)
                    }.onFailure {
                        // 这个 profile 不可用（比如没有音频硬件）：别让它卡住整轮
                        remaining--
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { btAdapter.closeProfileProxy(BluetoothProfile.HEADSET, opened[0]) }
                    runCatching { btAdapter.closeProfileProxy(BluetoothProfile.A2DP, opened[1]) }
                    runCatching { btAdapter.closeProfileProxy(BluetoothProfile.GATT, opened[2]) }
                }
            }
        }
        // 超时也把已经拿到的返回去（部分机型 GATT 代理迟迟不回调）
        profiled?.let { /* 结果已在 found 里 */ }
        return found.values.toList()
    }

    /**
     * 探测用的已连接设备。优先用 [refresh] 缓存下来的真实 [BluetoothDevice] 对象；
     * 缓存为空时（页面刚进、还没刷完）按 MAC 从已连接列表里补查一次。
     */
    fun firstConnectedDevice(): DeviceBattery? {
        val candidate = _state.value.connected.firstOrNull() ?: return null
        if (lastConnectedDevices.any { it.address == candidate.address }) return candidate
        // 缓存里没有真实对象：这次探测注定拿不到（connectGatt 需要 BluetoothDevice），
        // 先触发一轮刷新，让用户再点一次
        refresh()
        return null
    }

    /** 后台跑一次 GATT 探测，结果只进 logcat（不做任何状态变更） */
    fun probeAsync(device: DeviceBattery) {
        probeJob?.cancel()
        probeJob = scope.launch {
            Log.i(TAG, "开始探测 ${device.name} (${device.address})")
            // 缓存里没有就现场用 adapter 拿一次真实的 BluetoothDevice
            val target = lastConnectedDevices.firstOrNull { it.address == device.address }
                ?: adapter?.getRemoteDevice(device.address)
            if (target == null) {
                Log.e(TAG, "拿不到 BluetoothDevice，跳过探测")
                return@launch
            }
            val report = probeGattServices(target)
            Log.i(TAG, "探测结束，共 ${report.lines().size} 行")
        }
    }

    /** 排序：有电量的在前（电量升序，最需要充电的最靠前），没读到的垫底 */
    private fun rank(devices: List<DeviceBattery>): List<DeviceBattery> = devices.sortedWith(
        compareBy(
            { it.level == null },
            { it.level ?: Int.MAX_VALUE },
            { it.name.lowercase() },
        ),
    )

    /**
     * 连 GATT 读标准电量服务。
     *
     * ⚠ 用 `TRANSPORT_AUTO` 而不是 `TRANSPORT_LE`：真无线耳机常是 BR/EDR 单模，
     * 用 LE 连它必然失败，`onServicesDiscovered` 根本不会回调。AUTO 会按设备实际支持的
     * 传输方式去连，双模设备也能正常走到 LE 那半边。
     */
    @SuppressLint("MissingPermission")
    private suspend fun readBatteryLevel(device: BluetoothDevice): Int? =
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var gatt: BluetoothGatt? = null
                var finished = false
                var servicesDiscovered = false

                fun finish(level: Int?) {
                    if (finished) return
                    finished = true
                    // 先等服务发现完再断：有些设备在服务还没 enumerate 时就 disconnect，
                    // 会直接返回空值
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    if (cont.isActive) cont.resume(level)
                }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        gatt = g
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                if (!runCatching { g.discoverServices() }.getOrDefault(false)) {
                                    finish(null)
                                }
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                // 断开时如果服务已经发现过、只是没读到电量，按"不支持"处理
                                finish(null)
                            }
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        servicesDiscovered = true
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            finish(null)
                            return
                        }
                        val characteristic = g
                            .getService(BATTERY_SERVICE_UUID)
                            ?.getCharacteristic(BATTERY_LEVEL_UUID)
                        if (characteristic == null) {
                            // 不支持标准电量服务：这不是错误，各家耳机私有协议很常见
                            Log.i(TAG, "设备 ${device.address} 没有标准电量服务")
                            finish(null)
                            return
                        }
                        if (!runCatching { g.readCharacteristic(characteristic) }.getOrDefault(false)) {
                            finish(null)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onCharacteristicRead(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        val level = if (status == BluetoothGatt.GATT_SUCCESS) {
                            runCatching { characteristic.value?.firstOrNull()?.toInt()?.and(0xFF) }
                                .getOrNull()
                        } else {
                            Log.w(TAG, "读电量特征值失败 status=$status")
                            null
                        }
                        finish(level?.takeIf { it in 0..100 })
                    }
                }

                val opened = runCatching {
                    device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_AUTO)
                }.getOrNull()
                gatt = opened
                if (opened == null) {
                    finish(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { finish(null) }
            }
        }

    /**
     * 探测一台设备暴露的**全部** GATT 服务与特征，结果打进 logcat（TAG = DeviceBattery）。
     *
     * 用途只有一个：搞清楚厂商私有电量服务长什么样。标准电量服务（0x180F/0x2A19）在
     * BR/EDR 链路上不可达时（`GATT_ERROR(13)` / `No active GAP service found`），
     * 唯一的路就是照抄厂商自己用的那条服务 —— 但 UUID 得先从设备上抓出来。
     *
     * @return 可读的探测报告（也一并 Log 出来），连不上时返回原因
     */
    @SuppressLint("MissingPermission")
    suspend fun probeGattServices(device: BluetoothDevice): String =
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var gatt: BluetoothGatt? = null
                var finished = false

                fun finish(report: String) {
                    if (finished) return
                    finished = true
                    // 整段打进 logcat：`adb logcat -s DeviceBattery` 就能看到全部 UUID
                    report.lineSequence().forEach { Log.i(TAG, it) }
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    if (cont.isActive) cont.resume(report)
                }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        gatt = g
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            runCatching { g.discoverServices() }
                                .onFailure { finish("discoverServices 抛异常: ${it.message}") }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            finish("连接断开 (status=$status)，拿不到服务列表")
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            finish("服务发现失败 status=$status")
                            return
                        }
                        val sb = StringBuilder()
                        sb.appendLine("=== GATT 探测 ${device.address} ===")
                        val services = g.services.orEmpty()
                        if (services.isEmpty()) {
                            sb.appendLine("设备没有暴露任何服务")
                        }
                        services.forEach { service ->
                            sb.appendLine("service ${service.uuid}")
                            service.characteristics.orEmpty().forEach { ch ->
                                sb.appendLine(
                                    "   char ${ch.uuid} props=${ch.properties}" +
                                        " perms=${ch.permissions}",
                                )
                            }
                        }
                        finish(sb.toString())
                    }
                }

                val opened = runCatching {
                    device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_AUTO)
                }.getOrNull()
                gatt = opened
                if (opened == null) {
                    finish("connectGatt 返回 null（设备可能已断开或在别的连接上）")
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { finish("取消") }
            }
        } ?: "探测超时（${PROBE_TIMEOUT_MS}ms）"

    private fun safeName(device: BluetoothDevice): String? = runCatching { device.name }
        .onFailure { if (it !is SecurityException) Log.w(TAG, "读设备名失败", it) }
        .getOrNull()

    private companion object {
        const val TAG = "DeviceBattery"

        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("0000002a-0000-1000-8000-00805f9b34fb")

        /** 查已连接设备时轮询的 profile 代理 */
        val PROFILE_LIST = listOf(
            BluetoothProfile.HEADSET,
            BluetoothProfile.A2DP,
            BluetoothProfile.GATT,
        )
        const val PROFILE_QUERY_TIMEOUT_MS = 2_000L
        const val READ_TIMEOUT_MS = 4_000L
        const val READ_GAP_MS = 200L
        const val PROBE_TIMEOUT_MS = 8_000L
    }
}

/** 读设备名 / 连接列表 / 连 GATT 都归 BLUETOOTH_CONNECT 管；Android 12 之前没有这个运行时权限 */
internal fun hasConnectPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }