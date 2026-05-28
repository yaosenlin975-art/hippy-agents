package com.lin.hippyagent.core.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.notification.HippyAgentNotificationService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

class ProactiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val sessionStore: SessionStore by inject()
    private val notificationService: HippyAgentNotificationService by inject()
    private val proactiveMemoryManager: ProactiveMemoryManager by inject()

    override suspend fun doWork(): Result {
        return try {
            val status = proactiveMemoryManager.getIdleStatus()
            val isIdle = status["isIdle"] as? Boolean ?: false
            val enabled = status["enabled"] as? Boolean ?: false

            if (!enabled || !isIdle) {
                Timber.d("ProactiveWorker: not idle or disabled, skipping")
                return Result.success()
            }

            proactiveMemoryManager.startMonitoring()
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "ProactiveWorker failed")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "proactive_memory_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProactiveWorker>(
                30, TimeUnit.MINUTES
            ).build()

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

