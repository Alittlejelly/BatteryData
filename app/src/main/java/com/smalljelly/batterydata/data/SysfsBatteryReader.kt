package com.smalljelly.batterydata.data

import java.io.File

/**
 * 读取 /sys/class/power_supply 里的原始节点，拿到设计容量、满电容量、循环次数等
 * BatteryManager 没有公开的数据。部分机型这些节点不可读，此时返回 null 由 UI 降级展示。
 */
internal class SysfsBatteryReader {

    data class Raw(
        val fullChargeMah: Float? = null,
        val designCapacityMah: Float? = null,
        val cycleCount: Int? = null,
    ) {
        val isEmpty: Boolean get() = fullChargeMah == null && designCapacityMah == null && cycleCount == null
    }

    private var cache: Raw = Raw()
    private var lastReadAt = 0L

    fun read(force: Boolean = false): Raw {
        val now = System.currentTimeMillis()
        if (!force && now - lastReadAt < CACHE_WINDOW_MS) return cache
        lastReadAt = now
        cache = runCatching { readNow() }.getOrDefault(Raw())
        return cache
    }

    private fun readNow(): Raw {
        val root = File("/sys/class/power_supply")
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (dirs.isEmpty()) return Raw()

        // 电池节点可能叫 battery / bms / maxfuelgauge 等，先按 type 判断。
        // 目录顺序在不同 ROM 上不稳定，这里固定排序，避免每次采样读到的来源漂移。
        val ordered = dirs.sortedWith(compareBy({ nodeRank(it) }, { it.name }))

        val fullCharge = readChannel(ordered, CHARGE_FULL_KEYS, AhUnit.MICRO)
        val design = readChannel(ordered, DESIGN_KEYS, AhUnit.MICRO) ?: readChannel(ordered, VENDOR_DESIGN_KEYS, AhUnit.AUTO)
        val cycles = readCycles(ordered)

        return Raw(fullChargeMah = fullCharge, designCapacityMah = design, cycleCount = cycles)
    }

    /** 电池节点优先，其次是厂商常挂容量的 bms / fuelgauge 一类节点。 */
    private fun nodeRank(dir: File): Int {
        val type = readValue(File(dir, "type")).orEmpty()
        if (type.contains("Battery", ignoreCase = true)) return 0
        val name = dir.name.lowercase()
        return when {
            name == "battery" -> 1
            name.contains("batt") -> 2
            name.contains("bms") || name.contains("fuel") || name.contains("gauge") -> 3
            else -> 4
        }
    }

    /**
     * 按节点顺序、键顺序逐个尝试，跳过 0 / -1 / unknown / NaN / Infinity 等无效候选，
     * 而不是遇到第一个“非空字符串”就返回。
     */
    private fun readChannel(dirs: List<File>, keys: List<String>, unit: AhUnit): Float? {
        for (dir in dirs) {
            val props = loadProps(dir)
            for (key in keys) {
                props[key]?.let { raw ->
                    toMah(raw, unit)?.let { return it }
                }
                val file = File(dir, key.lowercase())
                readValue(file)?.let { raw ->
                    toMah(raw, unit)?.let { return it }
                }
            }
        }
        return null
    }

    private fun readCycles(dirs: List<File>): Int? {
        for (dir in dirs) {
            val props = loadProps(dir)
            for (key in CYCLE_KEYS) {
                val candidates = listOfNotNull(props[key], readValue(File(dir, key.lowercase())))
                for (raw in candidates) {
                    val value = raw.toIntOrNull() ?: continue
                    if (value in 0..100_000) return value
                }
            }
        }
        return null
    }

    private fun toMah(raw: String, unit: AhUnit): Float? {
        val value = raw.trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        if (value <= 0f) return null
        // CHARGE_* 标准节点是 µAh；厂商 design_capacity 有的写 mAh 有的写 µAh，按量级判断。
        val mah = when (unit) {
            AhUnit.MICRO -> value / 1000f
            AhUnit.AUTO -> if (value > 30_000f) value / 1000f else value
        }
        return mah.takeIf { it in MIN_SANE_MAH..MAX_SANE_MAH }
    }

    private fun loadProps(dir: File): Map<String, String> = runCatching {
        File(dir, "uevent").readLines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else {
                line.substring(0, index).uppercase() to line.substring(index + 1).trim()
            }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun readValue(file: File): String? = runCatching {
        if (!file.canRead()) null else file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private enum class AhUnit { MICRO, AUTO }

    private companion object {
        const val CACHE_WINDOW_MS = 15_000L
        const val MIN_SANE_MAH = 300f
        const val MAX_SANE_MAH = 30_000f

        val CHARGE_FULL_KEYS = listOf("POWER_SUPPLY_CHARGE_FULL", "CHARGE_FULL", "charge_full")
        val DESIGN_KEYS = listOf(
            "POWER_SUPPLY_CHARGE_FULL_DESIGN",
            "CHARGE_FULL_DESIGN",
            "charge_full_design",
        )
        val VENDOR_DESIGN_KEYS = listOf(
            "POWER_SUPPLY_DESIGN_CAPACITY",
            "DESIGN_CAPACITY",
            "design_capacity",
        )
        val CYCLE_KEYS = listOf("POWER_SUPPLY_CYCLE_COUNT", "CYCLE_COUNT", "cycle_count")
    }
}
