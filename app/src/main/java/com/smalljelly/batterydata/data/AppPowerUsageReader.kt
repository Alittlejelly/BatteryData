package com.smalljelly.batterydata.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个应用在统计区间内的耗电排行项。
 *
 * [percent] 是**前台时长占比**，不是系统电池统计里的 mAh —— 后者要 `BATTERY_STATS`
 * 签名权限（`BatteryStatsManager` 里那些接口第三方拿不到），
 * 所以这里用 `UsageStatsManager` 的公开数据做能耗的相对度量：
 * 屏幕亮着时哪个 App 占的时间多，它就消耗得多，这跟 MIUI 那张排行图的形态是一致的。
 */
data class AppPowerUsage(
    val packageName: String,
    val label: String,
    /** 前台时长（毫秒） */
    val foregroundMs: Long,
    /** 占全部统计时长的比例 0..1 */
    val percent: Float,
)

/**
 * 应用耗电排行：读系统的使用统计，按前台时长排序。
 *
 * 用 [UsageStatsManager] 而不是 `dumpsys batterystats`：
 * 后者是 shell 权限，普通 App 拿不到；前者是公开 API，
 * 只需用户授予「使用情况访问」权限（`PACKAGE_USAGE_STATS`）。
 */
class AppPowerUsageReader(context: Context) {

    private val appContext = context.applicationContext
    private val usageManager: UsageStatsManager?
        get() = runCatching {
            appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        }.getOrNull()

    /**
     * 是否有「使用情况访问」权限。
     *
     * 直接试读，不靠猜：没有权限时 [UsageStatsManager.queryUsageStats] 返回**空列表**
     * （Android 11 起行为如此，不抛异常），有权限时通常至少能读到本应用自己的记录 ——
     * 因为本应用此刻就在前台。所以"能读到任何一条 > 0 的前台时长"就是有权限。
     *
     * 之前用 `queryEvents` 判断是错的：最近真没事件时同样返回空迭代器，
     * 会把"有权限"误判成"没权限"。
     */
    fun hasPermission(): Boolean {
        val manager = usageManager ?: return false
        return runCatching {
            val now = System.currentTimeMillis()
            val stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 7 * 24 * 60 * 60 * 1000L,
                now,
            )
            stats?.any { it.totalTimeInForeground > 0L } == true
        }.getOrDefault(false)
    }

    /**
     * 统计 [sinceMillis] 到 [untilMillis] 的应用前台时长排行。
     *
     * [untilMillis] 默认"现在" —— 但翻一条**历史放电记录**时必须传记录结束时间，
     * 否则统计到的是"从现在往回"，和那条记录无关。
     *
     * @param limit 最多返回几项
     */
    suspend fun read(
        sinceMillis: Long,
        untilMillis: Long = System.currentTimeMillis(),
        limit: Int = 6,
    ): List<AppPowerUsage> = withContext(Dispatchers.IO) {
        val manager = usageManager ?: return@withContext emptyList()
        val end = untilMillis
        val start = sinceMillis.coerceAtLeast(end - 24 * 60 * 60 * 1000L)

        val stats = runCatching {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        }.getOrNull().orEmpty()

            // 按包名聚合（同一 App 可能有多条记录），只留前台时长 > 0 的
            val byPackage = linkedMapOf<String, Long>()
            stats.forEach { s ->
                if (s.totalTimeInForeground <= 0L) return@forEach
                byPackage[s.packageName] = (byPackage[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
            if (byPackage.isEmpty()) return@withContext emptyList()

            val total = byPackage.values.sum().coerceAtLeast(1L)
            val pm = appContext.packageManager

            byPackage.entries
                .sortedByDescending { it.value }
                // 系统框架 / 桌面这类不算"应用耗电"，和 MIUI 那张图的口径一致
                .filterNot { (pkg, _) -> pkg == "android" || isLauncher(pm, pkg) }
                .take(limit)
                .map { (pkg, ms) ->
                    AppPowerUsage(
                        packageName = pkg,
                        label = appLabel(pm, pkg),
                        foregroundMs = ms,
                        percent = ms.toFloat() / total,
                    )
                }
        }

    private fun appLabel(pm: PackageManager, packageName: String): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun isLauncher(pm: PackageManager, packageName: String): Boolean = runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolve = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        resolve?.activityInfo?.packageName == packageName
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AppPowerUsage"
    }
}