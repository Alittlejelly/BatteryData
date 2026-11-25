package com.smalljelly.batterydata.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 插电自动复活（进程被杀后也能被系统拉起）。
 *
 * 为什么不用清单注册 ACTION_POWER_CONNECTED：它**不在** Android 8+ 隐式广播豁免清单里，
 * targetSdk 26+ 的清单接收器根本收不到；即便个别厂商放行，Android 12+ 从后台启动前台服务
 * 也会因 POWER_CONNECTED 不在豁免清单而被拒。
 *
 * JobScheduler 的充电约束 job 由系统持久化调度：进程被杀后系统仍会重排未完成的 job，
 * 插电（约束满足）时拉起进程执行 [onStartJob]；job 运行期间应用在临时白名单里，可以
 * 启动前台服务。这里调 [ChargeRecorder.ensure] 挂上岛模式前台服务，超级岛即恢复。
 *
 * - [onStartJob] 返回 true 让 job 在充电期间保持活跃，拔电 / 超时由 [onStopJob] 重新排期。
 * - 插电瞬间 `BatteryManager` 的 status 可能还是 NOT_CHARGING，[ChargeRecorder.ensure]
 *   里的 `isCharging` 判定会假失败。所以这里延迟补试两次，覆盖状态更新滞后。
 * - expedited：避免应用长期不用落进 rare standby bucket 后 job 被推迟很久才跑。
 */
class ChargeKeepAliveJobService : JobService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "onStartJob：充电约束满足，尝试拉起岛模式前台服务")
        tryEnsure()
        // 第一次若因 status 尚未切到 CHARGING 而没起来，补试两次（幂等，已起来时无副作用）
        handler.postDelayed({ tryEnsure() }, 1_500)
        handler.postDelayed({ tryEnsure() }, 6_000)
        // 保持活跃：充电期间不 jobFinished，拔电 / 超时交给 onStopJob 重新排期
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.i(TAG, "onStopJob：约束丢失或超时，重新排期等下次插电")
        handler.removeCallbacksAndMessages(null)
        schedule(applicationContext)
        return false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun tryEnsure() {
        runCatching { ChargeRecorder.ensure(applicationContext) }
            .onFailure { Log.e(TAG, "ensure 抛异常", it) }
    }

    companion object {
        private const val TAG = "ChargeKeepAlive"
        private const val JOB_ID = 2002

        /** 排期（或替换）充电触发 job。持久化、重启后仍在；同 ID 幂等。 */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            // 先试 expedited（提升调度优先级）。它在部分 ROM 上与 persisted 组合会直接抛异常，
            // 而 persisted 才是"进程被杀/重启后 job 仍在"的关键 —— 所以失败必须退回普通 job。
            // 之前这里把两者的 build 放在同一个 runCatching 里，异常被吞成 result=null，
            // job 从未排进队列，插电复活整个失效（`cmd jobscheduler run` 报 Could not find job）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ok = runCatching {
                    scheduler.schedule(newJobInfo(context, expedited = true))
                }.onFailure {
                    Log.w(TAG, "expedited 排期失败，退回普通持久化 job", it)
                }.getOrNull()
                if (ok == JobScheduler.RESULT_SUCCESS) {
                    Log.i(TAG, "schedule(JOB_ID=$JOB_ID): expedited 成功")
                    return
                }
            }
            val result = runCatching {
                scheduler.schedule(newJobInfo(context, expedited = false))
            }.onFailure { Log.e(TAG, "schedule 失败", it) }.getOrNull()
            Log.i(TAG, "schedule(JOB_ID=$JOB_ID) result=$result")
        }

        private fun newJobInfo(context: Context, expedited: Boolean): JobInfo {
            val builder = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, ChargeKeepAliveJobService::class.java),
            )
                .setRequiresCharging(true)
                .setPersisted(true)
            if (expedited) builder.setExpedited(true)
            return builder.build()
        }
    }
}