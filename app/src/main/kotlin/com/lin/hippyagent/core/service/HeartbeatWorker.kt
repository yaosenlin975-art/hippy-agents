package com.lin.hippyagent.core.service

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.first
import timber.log.Timber

class ScheduledDreamWorker(
    context: Context,
    workerParams: WorkerParameters,
    private val memoryStore: com.lin.hippyagent.core.memory.MemoryStore
) : CoroutineWorker(context, workerParams) {

    private val dreamProcessor by lazy {
        val workingDir = context.filesDir
        com.lin.hippyagent.core.memory.dream.DreamMemoryProcessor(workingDir, memoryStore)
    }

    override suspend fun doWork(): Result {
        val agentId = inputData.getString("agent_id") ?: return Result.failure()
        val phaseName = inputData.getString("phase") ?: com.lin.hippyagent.core.memory.DreamPhase.DEEP.name
        val phase = try {
            com.lin.hippyagent.core.memory.DreamPhase.valueOf(phaseName)
        } catch (e: Exception) {
            com.lin.hippyagent.core.memory.DreamPhase.DEEP
        }

        Timber.d("DreamWorker executing for agent: $agentId, phase: $phase")

        return try {
            val result = dreamProcessor.dreamMemory(phase)
            result.onSuccess { dreamResult ->
                Timber.i("Dream completed ($phase): $dreamResult")
            }
            result.fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() }
            )
        } catch (e: Exception) {
            Timber.e(e, "DreamWorker failed for agent: $agentId, phase: $phase")
            Result.retry()
        }
    }

    companion object {
        private const val TAG_PREFIX = "dream_"

        fun scheduleDream(
            context: Context,
            agentId: String,
            cronExpression: String
        ) {
            com.lin.hippyagent.core.memory.DreamScheduler.schedule(context)

            Timber.i("Dream scheduled for agent: $agentId (cron: $cronExpression)")
        }

        fun cancelDream(context: Context, agentId: String) {
            com.lin.hippyagent.core.memory.DreamScheduler.cancel(context)
        }
    }
}

class BootSetupWorker(
    context: Context,
    workerParams: WorkerParameters,
    private val agentRepository: com.lin.hippyagent.data.repository.AgentRepository,
    private val heartbeatScheduler: com.lin.hippyagent.core.heartbeat.HeartbeatScheduler
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.i("BootSetupWorker executing - restoring agent services")

        return try {
            // Load all agent profiles
            val profiles = agentRepository.loadAgentProfiles().first()

            for ((agentId, profile) in profiles) {
                if (!profile.enabled) {
                    Timber.d("Skipping disabled agent: $agentId")
                    continue
                }

                // Restore heartbeat for agents with heartbeat enabled
                if (profile.heartbeat.enabled) {
                    Timber.i("Restoring heartbeat for agent: $agentId")
                    heartbeatScheduler.scheduleHeartbeat(agentId, profile.heartbeat)
                        .onFailure { e ->
                            Timber.e(e, "Failed to restore heartbeat for agent: $agentId")
                        }
                }

                // Schedule dream if configured
                val dreamCron = profile.running.remeLightMemoryConfig?.dreamCron
                if (!dreamCron.isNullOrBlank()) {
                    Timber.i("Restoring dream schedule for agent: $agentId (cron: $dreamCron)")
                    ScheduledDreamWorker.scheduleDream(applicationContext, agentId, dreamCron)
                }

                Timber.i("Agent services restored for: $agentId")
            }

            Timber.i("BootSetupWorker completed - restored ${profiles.size} agents")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "BootSetupWorker failed")
            Result.retry()
        }
    }

    companion object {
        fun scheduleBootSetup(context: Context) {
            val work = OneTimeWorkRequestBuilder<BootSetupWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "boot_setup",
                    ExistingWorkPolicy.KEEP,
                    work
                )
        }
    }
}



