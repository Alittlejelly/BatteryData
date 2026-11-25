package com.smalljelly.batterydata.shizuku

import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 特权服务实现（移植自 NexioSchedule）。
 *
 * 由 [ShizukuManager] 通过 `Shizuku.bindUserService` 拉起，运行在 Shizuku 的
 * shell/root 身份进程里——**不受 App 进程的 hiddenapi 限制**，因此能反射调用
 * `IConnectivityManager.setFirewallChainEnabled / setUidFirewallRule` 这两个 @hide 方法，
 * 临时放行/恢复 XMSF 的网络（超级岛渲染依赖它）。在 App 进程里直接反射会被 hiddenapi 拒绝。
 */
class PrivilegedServiceImpl : IPrivilegedService.Stub() {
    companion object {
        private const val TAG = "PrivilegedServiceImpl"
        private const val FIREWALL_CHAIN_OEM_DENY = 9
        private const val TIMEOUT_SECONDS = 3L
    }

    /**
     * 一键保活白名单（shell 身份执行）：
     * - Doze 白名单：打盹时广播 / 网络不被冻结，插电复活广播投递更稳；
     * - RUN_ANY_IN_BACKGROUND / RUN_IN_BACKGROUND：放开后台运行限制。
     *
     * 注意：MIUI/HyperOS 上 `appops` 命令可能要求开发者选项里的「USB 调试（安全设置）」
     * （需登录小米账号），没开时这两条会失败——如实报告，不假装成功。
     * MIUI 的自启动 / 省电「无限制」是厂商私有策略，AOSP 命令管不到，
     * 由关于页引导用户去系统设置手动开。
     */
    override fun applyKeepAliveWhitelist(packageName: String): String {
        // binder 参数防注入：只允许包名字符
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) return "无效包名"
        val commands = listOf(
            "Doze 白名单" to "dumpsys deviceidle whitelist +$packageName",
            "后台运行" to "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow",
            "后台运行(兼容)" to "cmd appops set $packageName RUN_IN_BACKGROUND allow",
        )
        return commands.joinToString("\n") { (label, cmd) -> "$label: ${runOne(cmd)}" }
    }

    private fun runOne(command: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        // 先读干输出再 wait，避免管道写满阻塞；输出都很短
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        when {
            !finished -> {
                process.destroyForcibly()
                "超时"
            }
            process.exitValue() == 0 -> "成功"
            else -> "失败(${process.exitValue()}) ${(err + out).trim().take(120)}"
        }
    } catch (e: Exception) {
        Log.e(TAG, "runOne failed: $command", e)
        "失败: ${e.message}"
    }

    override fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val connectivityBinder = getServiceMethod.invoke(null, "connectivity") as? IBinder
                ?: throw IllegalStateException("Connectivity service not found")

            val iConnectivityManager = Class.forName("android.net.IConnectivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, connectivityBinder)

            val latch = CountDownLatch(1)
            var result = false
            val thread = Thread {
                try {
                    val setFirewallChainEnabled = iConnectivityManager.javaClass.getMethod(
                        "setFirewallChainEnabled",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                    )
                    setFirewallChainEnabled.invoke(iConnectivityManager, FIREWALL_CHAIN_OEM_DENY, true)

                    val rule = if (enabled) 0 else 2 // 0 = ALLOW, 2 = DENY
                    val setUidFirewallRule = iConnectivityManager.javaClass.getMethod(
                        "setUidFirewallRule",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                    setUidFirewallRule.invoke(iConnectivityManager, FIREWALL_CHAIN_OEM_DENY, uid, rule)
                    result = true
                    Log.d(TAG, "set networking uid=$uid enabled=$enabled ok")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed in worker thread", e)
                    result = false
                } finally {
                    latch.countDown()
                }
            }
            thread.start()
            val completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                thread.interrupt()
                Log.w(TAG, "Operation timed out")
                false
            } else {
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set package networking", e)
            false
        }
    }
}