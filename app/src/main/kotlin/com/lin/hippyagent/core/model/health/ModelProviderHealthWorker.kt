package com.lin.hippyagent.core.model.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.context.GlobalContext
import timber.log.Timber

class ModelProviderHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val koin = GlobalContext.getOrNull() ?: run {
                Timber.w("Koin not initialized, skip health check")
                return Result.retry()
            }
            val healthService = koin.get<ModelProviderHealthService>()
            val summary = healthService.checkAllProviders()

            Timber.i("Provider health check: ${summary.healthyProviders}/${summary.totalProviders} healthy")

            summary.details.filter { !it.healthy }.forEach { status ->
                notifyUnhealthyProvider(status)
            }

            Result.success()
        }.getOrElse { e ->
            Timber.e(e, "Provider health worker failed")
            Result.retry()
        }
    }

    private fun notifyUnhealthyProvider(status: ProviderHealthStatus) {
        runCatching {
            Timber.w("Provider ${status.providerId} unhealthy: ${status.errorType} - ${status.errorMessage}")
        }.onFailure { e ->
            Timber.w(e, "Failed to notify unhealthy provider ${status.providerId}")
        }
    }

    companion object {
        const val WORK_NAME = "model_provider_health_check"

        const val REPEAT_INTERVAL_MINUTES: Long = 15
    }
}
