package com.smalljelly.batterydata.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/**
 * 诊断日志：写到应用私有 files/logs，不申请存储权限。
 * 每次进入日志页时如果一份都没有，就抓一条当前设备 / 电池快照落盘，
 * 这样"日志诊断"入口始终有内容可看，也方便用户反馈问题时导出。
 */
class LogFileStore(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "logs")

    fun list(): List<LogFile> {
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { LogFile(it, it.name, it.length(), it.lastModified()) }
    }

    fun read(log: LogFile): String = runCatching { log.file.readText() }
        .getOrDefault("（读取失败）")

    fun deleteAll() {
        list().forEach { runCatching { it.file.delete() } }
    }

    // —— 实时记录 ——
    // 「开始记录日志」：建一份 live-*.txt，之后每采样一次追加一行，直到用户停止。
    // 记录句柄留在 store 里（不是文件锁），页面销毁时由调用方 stop。

    private var liveFile: File? = null

    /** 正在记录的那个文件；null = 没在记录 */
    val liveRecording: File? get() = liveFile

    fun startLiveRecording(): LogFile? {
        if (liveFile != null) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val now = Date()
        val file = File(
            dir,
            "live-${dayFormat.format(now)}-${timeFormat.format(now).replace(":", "")}.txt",
        )
        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "未知"
        val header = buildString {
            append("电池数据 · 实时记录日志\n")
            append("开始时间：").append(fullFormat.format(now)).append('\n')
            append("应用版本：").append(version).append('\n')
            append("设备：").append(Build.BRAND).append(' ').append(Build.MODEL).append('\n')
            append("系统：Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append('\n')
            append("—— 采样明细 ——\n")
            append("时间      电量  状态      温度     电流      电压\n")
        }
        val ok = runCatching { file.writeText(header) }.isSuccess
        if (!ok) return null
        liveFile = file
        return LogFile(file, file.name, file.length(), file.lastModified())
    }

    /** 追一条当前电池采样；没在记录时返回 null */
    fun appendLiveSample(): LogFile? {
        val file = liveFile ?: return null
        val sample = runCatching { BatteryRepository(appContext).snapshot() }.getOrNull()
        val line = buildString {
            append(timeFormat.format(Date()))
            if (sample == null) {
                append("  （读取失败）")
            } else {
                append("  ").append(sample.level).append("%")
                append("  ").append(sample.statusText)
                sample.temperatureC?.let { t -> append("  ").append(oneDecimal(t)).append("°C") }
                sample.currentMa?.let { i -> append("  ").append(i.toInt()).append("mA") }
                append("  ").append(sample.voltageMv).append("mV")
            }
        }
        runCatching { file.appendText(line + "\n") }
        return toLogFile(file)
    }

    /** 停止记录，返回写完的文件（没在记录时返回 null） */
    fun stopLiveRecording(): LogFile? {
        val file = liveFile ?: return null
        liveFile = null
        val tail = buildString {
            append('\n')
            append("结束时间：").append(fullFormat.format(Date())).append('\n')
        }
        runCatching { file.appendText(tail) }
        return toLogFile(file)
    }

    private fun toLogFile(file: File): LogFile? = if (file.exists()) {
        LogFile(file, file.name, file.length(), file.lastModified())
    } else {
        null
    }

    private fun oneDecimal(value: Float): String =
        String.format(Locale.US, "%.1f", value)

    /** 抓一份当前诊断快照，返回刚写入的文件；同一天多次调用会追加时间戳避免覆盖。 */
    fun collect(): LogFile? {
        if (!dir.exists() && !dir.mkdirs()) return null
        val now = Date()
        val stamp = dayFormat.format(now)
        var file = File(dir, "log-$stamp.txt")
        if (file.exists()) {
            file = File(dir, "log-$stamp-${timeFormat.format(now).replace(":", "")}.txt")
        }
        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "未知"
        val battery = runCatching { BatteryRepository(appContext).snapshot() }.getOrNull()

        val text = buildString {
            append("电池数据 · 运行诊断日志\n")
            append("生成时间：").append(fullFormat.format(now)).append('\n')
            append("应用版本：").append(version).append('\n')
            append("设备：").append(Build.BRAND).append(' ').append(Build.MODEL).append('\n')
            append("系统：Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append('\n')
            append("—— 电池状态 ——\n")
            if (battery == null) {
                append("（读取失败）\n")
            } else {
                append("电量：").append(battery.level).append("%\n")
                append("状态：").append(battery.statusText).append('\n')
                append("充电方式：").append(battery.pluggedText).append('\n')
                append("电池技术：").append(battery.technology).append('\n')
                append("电压：").append(battery.voltageMv).append(" mV\n")
                battery.currentMa?.let { append("电流：").append(it).append(" mA\n") }
                battery.temperatureC?.let { append("温度：").append(it).append(" °C\n") }
                battery.healthPercent?.let { append("健康度：").append(it).append("%\n") }
                battery.designCapacityMah?.let { append("设计容量：").append(it).append(" mAh\n") }
            }
        }
        return runCatching {
            file.writeText(text)
            LogFile(file, file.name, file.length(), file.lastModified())
        }.getOrNull()
    }

    companion object {
        private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        private val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    }
}