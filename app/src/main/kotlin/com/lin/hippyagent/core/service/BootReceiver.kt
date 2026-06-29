package com.lin.hippyagent.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Timber.d("Boot completed received")
                maybeAutoStartAgent(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // App 升级后, AlarmManager/WorkManager 注册的定时任务被系统清除, 需重注册
                Timber.i("Package replaced, rescheduling all cron jobs")
                rescheduleAllCronJobs(context)
            }
        }
    }

    /**
     * 开机自启: 检查偏好开关, 启动上次运行的 Agent.
     */
    private fun maybeAutoStartAgent(context: Context) {
        val prefs = context.getSharedPreferences("hippy_settings", Context.MODE_PRIVATE)
        val autoStartEnabled = prefs.getBoolean("auto_start_on_boot", false)
        if (!autoStartEnabled) {
            Timber.d("Auto-start disabled, skipping")
            return
        }
        val lastAgentId = prefs.getString("last_running_agent_id", null)
        if (lastAgentId != null) {
            Timber.i("Auto-starting agent: $lastAgentId")
            AgentForegroundService.start(context, lastAgentId)
        } else {
            Timber.d("No agent ID found, skipping auto-start")
        }
    }

    /**
     * 升级后重注册 cron: 通过 Koin 取 CronJobManager + applicationScope, 异步触发 rescheduleAll.
     *
     * 协程合规: 使用 Koin 单例 applicationScope, 不新建 CoroutineScope() (符合 coding.md).
     * 容错: runCatching 各走各路, Koin 未初始化时静默失败 (升级后首次启动可能 Koin 未就绪).
     */
    private fun rescheduleAllCronJobs(context: Context) {
        runCatching {
            val koin = GlobalContext.getOrNull() ?: run {
                Timber.w("Koin not initialized yet, skip reschedule")
                return
            }
            val cronJobManager = koin.get<com.lin.hippyagent.core.cron.CronJobManager>()
            val appScope = koin.get<CoroutineScope>(
                org.koin.core.qualifier.named("applicationScope")
            )
            appScope.launch {
                runCatching { cronJobManager.rescheduleAll() }
                    .onFailure { Timber.e(it, "rescheduleAll failed after package replace") }
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to access Koin for rescheduleAllCronJobs")
        }
    }
}
