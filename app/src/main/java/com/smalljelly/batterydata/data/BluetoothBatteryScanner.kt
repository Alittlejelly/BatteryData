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
 * 页面状态：一次刷新的完整结果。
 *
 * 单纯用列表不够 —— 要区分"还没刷过 / 正在连 / 刷完了但一台都没连上"，
 * 「刚进页面还没读」和「确实没有设备」在界面上给的是完全不同的引导。
 */
data class DevicesState(
    val devices: List<DeviceBattery> = emptyList(),
    /** 正在逐个连接读取 */
    val refreshing: Boolean = false,
    /** 正在读的那台（界面显示进度文案用） */
    val readingName: String? = null,
    /** 蓝牙不可用时的原因；null = 一切正常 */
    val blockedReason: String? = null,
    /** 上一次刷新结束的时间戳 */
    val refreshedAt: Long = 0L,
)

/**
 * 多设备电量：只统计**你自己那几台**设备。
 *
 * 数据来路只有一条 —— 系统的已配对列表（`getBondedDevices`），逐台连上 GATT 读标准电量服务
 * （`0x180F` / `0x2A19`）。**不做 BLE 扫描**：扫出来的是一屋子邻居的设备，
 * 那不是"我的设备电量汇聚"，只是一份附近的设备名单。
 *
 * 每台分三种结局：
 * - 连上且读到电量 → 实时值，标「已连接」；
 * - 连上但设备没有标准电量服务（私有协议）→ 标「已连接」、电量未知；
 * - 连不上（关机 / 不在范围内 / 已不再配对）→ 用缓存里的旧值标「已断开」，
 *   作为历史留着，用户可以手动删除。
 *
 * 为什么串行读：GATT 连接在大多数手机上池子是有限的（常见 4~7 个），
 * 同时连一堆会出现刚连上就被挤掉、集体读失败。所以一台一台来，各自带超时。
 */
class BluetoothBatteryScanner(context: Context) {

    private val appContext = context.applicationContext
    private val store = DeviceBatteryStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(DevicesState(devices = store.load()))
    val state: StateFlow<DevicesState> = _state.asStateFlow()

    private var job: Job? = null
    private val adapter: BluetoothAdapter?
        get() = runCatching {
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

    /**
     * 是否有权限动蓝牙。
     *
     * 只需要 `BLUETOOTH_CONNECT`（读配对列表、连 GATT、读设备名都归它管）。
     * 不用 `BLUETOOTH_SCAN` —— 既然不扫描，就不该向用户要「附近的设备」这个吓人的权限。
     */
    fun permissionsGranted(): Boolean = hasConnectPermission(appContext)

    /** 蓝牙是否可用：没硬件 / 没开 */
    fun isReady(): Boolean = adapter?.isEnabled == true

    fun stop() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(refreshing = false, readingName = null)
    }

    /** 删掉一台设备的历史（界面上每张卡片的删除按钮） */
    fun remove(address: String) {
        val remaining = _state.value.devices.filterNot { it.address == address }
        store.save(remaining)
        _state.value = _state.value.copy(devices = remaining)
    }

    /** 清空全部缓存（不给界面入口，留给以后做「重置」） */
    fun clearHistory() {
        store.clear()
        _state.value = _state.value.copy(devices = emptyList())
    }

    /**
     * 跑一轮刷新：已配对设备 → 逐台连 GATT 读电量 → 合并缓存 → 发布。
     *
     * 同时把缓存里仍存在、但已不在配对列表中的设备（用户解绑了）也带上，
     * 这样历史记录不会莫名其妙消失。
     */
    fun refresh() {
        if (job?.isActive == true) return
        job = scope.launch {
            val stored = store.load().associateBy { it.address }

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

            val bonded = runCatching { adapter?.bondedDevices.orEmpty() }
                .getOrDefault(emptySet())
                .associateBy { it.address }

            // 候选 = 已配对 ∪ 缓存里有的；缓存里读过的排前面，一轮里先保证这些拿到实时值
            val candidates = buildList {
                bonded.values.forEach { device ->
                    add(Candidate(device, stored[device.address]))
                }
                stored.values.forEach { saved ->
                    if (bonded[saved.address] == null) add(Candidate(null, saved))
                }
            }
                .sortedByDescending { it.stored != null }
                .take(MAX_DEVICES)

            // 结果先铺缓存（本轮没连上的就靠它显示历史）
            val result = linkedMapOf<String, DeviceBattery>()
            stored.values.forEach { saved -> result[saved.address] = saved.copy(connected = false) }

            for ((index, candidate) in candidates.withIndex()) {
                if (!isActive) return@launch
                val device = candidate.device
                val address = device?.address ?: candidate.stored!!.address
                val name = device?.let { safeName(it) } ?: candidate.stored?.name ?: address
                _state.value = _state.value.copy(readingName = name)

                val outcome = if (device != null) readDevice(device) else ReadOutcome.Disconnected
                val previous = candidate.stored

                // 连不上、也没读到过的设备不进列表：那是"配对过但从没读到电量"的摆设，
                // 混进来只会把列表刷成一堆横线
                if (outcome.connected || previous != null) {
                    result[address] = DeviceBattery(
                        address = address,
                        name = name,
                        level = outcome.level ?: previous?.level,
                        updatedAt = if (outcome.level != null) {
                            System.currentTimeMillis()
                        } else {
                            previous?.updatedAt ?: 0L
                        },
                        connected = outcome.connected,
                    )
                }
                // 每读完一台就发布一次，列表逐条亮起来，比等全部结束更有反馈
                _state.value = _state.value.copy(devices = rank(result.values.toList()))
                if (index < candidates.lastIndex) delay(READ_GAP_MS)
            }

            val finalList = rank(result.values.toList())
            store.save(finalList)
            _state.value = DevicesState(
                devices = finalList,
                refreshing = false,
                readingName = null,
                blockedReason = null,
                refreshedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 排序：先已连接，再断开的历史。
     * 已连接里按电量升序（最需要充电的排最前），断开的历史按最近更新在前。
     */
    private fun rank(devices: List<DeviceBattery>): List<DeviceBattery> = devices.sortedWith(
        compareBy(
            { !it.connected },
            { it.level == null },
            { it.level ?: Int.MAX_VALUE },
            { -it.updatedAt },
        ),
    )

    // ---------------------------------------------------------------- 读电量

    private data class Candidate(val device: BluetoothDevice?, val stored: DeviceBattery?)

    private data class ReadOutcome(val connected: Boolean, val level: Int?) {
        companion object {
            val Disconnected = ReadOutcome(connected = false, level = null)
        }
    }

    /**
     * 连接设备读标准电量特征值。
     *
     * 关键在区分「连不上」和「连上了但没有电量服务」——前者是断开，后者只是电量未知，
     * 拿它当断开处理会让私有协议的耳机永远显示成离线。
     *
     * 回调只覆盖旧的 `onCharacteristicRead(gatt, characteristic, status)` 签名：
     * API 33 起新签名的默认实现就是转发到它，所以一套代码全版本可用。
     */
    @SuppressLint("MissingPermission")
    private suspend fun readDevice(device: BluetoothDevice): ReadOutcome =
        withTimeoutOrNull(READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var gatt: BluetoothGatt? = null
                var connected = false
                var finished = false

                fun finish(level: Int?) {
                    if (finished) return
                    finished = true
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    if (cont.isActive) cont.resume(ReadOutcome(connected, level))
                }

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        gatt = g
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connected = true
                                if (!runCatching { g.discoverServices() }.getOrDefault(false)) {
                                    finish(null)
                                }
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> finish(null)
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            finish(null)
                            return
                        }
                        val characteristic = g
                            .getService(BATTERY_SERVICE_UUID)
                            ?.getCharacteristic(BATTERY_LEVEL_UUID)
                        if (characteristic == null) {
                            // 不支持标准电量服务：这不是错误，各家耳机私有协议很常见
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
                            null
                        }
                        finish(level?.takeIf { it in 0..100 })
                    }
                }

                val opened = runCatching {
                    device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                }.getOrNull()
                gatt = opened
                if (opened == null) {
                    finish(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { finish(null) }
            }
        } ?: ReadOutcome.Disconnected

    private fun safeName(device: BluetoothDevice): String? = runCatching { device.name }
        .onFailure { if (it !is SecurityException) Log.w(TAG, "读设备名失败", it) }
        .getOrNull()

    private companion object {
        const val TAG = "DeviceBattery"

        /** 标准蓝牙电量服务 */
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")

        /** 电量百分比特征值 */
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("0000002a-0000-1000-8000-00805f9b34fb")

        /** 单台设备连接 + 读电量的超时；关机的设备通常很快断开，不会等到这么久 */
        const val READ_TIMEOUT_MS = 4_000L

        /** 两台之间留一点间隔，避免 GATT 连接池来不及释放 */
        const val READ_GAP_MS = 200L

        /** 单轮最多连多少台：配对的设备通常就几台，多出来的多半是车机、电视，读不出电量 */
        const val MAX_DEVICES = 12
    }
}

/** 读设备名 / 已配对列表 / 连接 GATT 都归 BLUETOOTH_CONNECT 管；Android 12 之前没有这个运行时权限 */
internal fun hasConnectPermission(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }