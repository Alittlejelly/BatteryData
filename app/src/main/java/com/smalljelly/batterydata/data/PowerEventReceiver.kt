package com.smalljelly.batterydata.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 插电 / 开机自动复活：进程被划掉后台杀死后，下次插上充电器（或开机时正插着电），
 * 系统会把进程拉起来投递这条广播，这里调 [ChargeRecorder.ensure] 重新挂上
 * 前台服务（岛模式），超级岛不用手动开 App 就能回来。
 *
 * 两条广播都是系统豁免的隐式广播（targetSdk 26+ 清单注册有效），且只能由系统发出
 * （protected broadcast），所以 exported=true 是安全的。
 * 注意：用户在应用信息里「强行停止」后广播不再投递，这是系统规则，无解。
 *
 * 耗电：纯事件驱动，不充电时这个接收器什么都不做，零轮询零常驻。
 */
class PowerEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_BOOT_COMPLETED -> {
                // ensure 内部判断：有记录会话，或正在充电且岛开关开 → 拉起前台服务；
                // 否则什么都不做。快照 + 恢复会话都是毫秒级，无需 goAsync
                runCatching { ChargeRecorder.ensure(context.applicationContext) }
                // 重新排期充电触发 job：POWER_CONNECTED 清单注册在多数机型收不到，
                // 插电复活实际靠这个 job；开机 / 个别厂商放行广播时顺手把它排上。
                runCatching { ChargeKeepAliveJobService.schedule(context.applicationContext) }
            }
        }
    }
}