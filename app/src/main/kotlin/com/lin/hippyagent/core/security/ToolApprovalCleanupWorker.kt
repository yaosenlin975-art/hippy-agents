package com.lin.hippyagent.core.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lin.hippyagent.core.agent.session.AppDatabase
import com.lin.hippyagent.core.agent.task.TaskStatus
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 每日清理 7 天前 source='tool_approval' && status IN (COMPLETED, FAILED) 的 TaskEntity
 *
 * 由 WorkManager 调度, 周期 24h。
 * pending-migration-v2 inbox-approval-merge §4.5 / §5 第 8 项。
 */
class ToolApprovalCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            // 兜底: 若 Koin 未 init (冷启动 + WorkManager 抢先跑) 直接返回 success, 下次再清
            val appDatabase: AppDatabase = try {
                org.koin.java.KoinJavaComponent.get(AppDatabase::class.java)
            } catch (e: Throwable) {
                Timber.w(e, "ToolApprovalCleanupWorker: Koin not ready, skip this run")
                return@runCatching Result.success()
            }
            val taskDao = appDatabase.taskDao()
            val cutoffMs = System.currentTimeMillis() - TOOL_APPROVAL_TTL_DAYS * 24L * 60L * 60L * 1000L
            val deleted = taskDao.deleteBySourceOlderThanStatuses(
                source = "tool_approval",
                statuses = listOf(TaskStatus.COMPLETED, TaskStatus.FAILED),
                cutoffMs = cutoffMs
            )
            Timber.i("ToolApprovalCleanupWorker: deleted $deleted tool_approval task(s) older than $TOOL_APPROVAL_TTL_DAYS days")
            Result.success()
        }.getOrElse { e ->
            Timber.e(e, "ToolApprovalCleanupWorker: failed")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "tool_approval_cleanup_daily"
        const val TOOL_APPROVAL_TTL_DAYS = 7L

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<ToolApprovalCleanupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("ToolApprovalCleanupWorker: scheduled (1d period, 1h initial delay)")
        }
    }
}
