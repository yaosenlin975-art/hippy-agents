package com.lin.hippyagent.core.trace

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class TraceRetentionWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val traceSettings = GlobalContext.get().get<TraceSettings>()
        val repository = GlobalContext.get().get<TraceRepository>()

        val retentionDays = runCatching {
            traceSettings.retentionDays.first()
        }.getOrDefault(7)

        if (retentionDays > 0) {
            val cutoff = System.currentTimeMillis() - retentionDays * 24 * 3600 * 1000L
            runCatching { repository.deleteOlderThan(cutoff) }
        }

        val llmCutoff = System.currentTimeMillis() - 3 * 24 * 3600 * 1000L
        runCatching { repository.clearLlmContentOlderThan(llmCutoff) }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "trace_retention_cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TraceRetentionWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
