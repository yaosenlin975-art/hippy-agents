package com.lin.hippyagent.core.cron

import android.content.Context
import androidx.work.*
import com.lin.hippyagent.R
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CronJob(
    val id: String = com.lin.hippyagent.core.pool.FastId.next(),
    val name: String,
    val query: String,
    val schedule: String,
    val agentId: String,
    val channelId: String = "cron",
    val enabled: Boolean = true,
    val sessionId: String = "",
    val silentMode: Boolean = false
)

@Serializable
data class CronJobExecution(
    val jobId: String,
    val jobName: String,
    val agentId: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val success: Boolean = false,
    val error: String? = null,
    val outputLength: Int = 0
)

@Serializable
data class CronJobStats(
    val jobId: String,
    val jobName: String,
    val totalRuns: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastRunAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val averageDurationMs: Long = 0
)

class CronJobManager(
    private val context: Context,
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore
) {
    private val jobs = mutableListOf<CronJob>()
    private val executions = mutableListOf<CronJobExecution>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val workManager = WorkManager.getInstance(context)
    private val historyDir = File(context.filesDir, "cron_history")
    private val mutex = Mutex()

    init {
        historyDir.mkdirs()
        loadHistory()
    }

    suspend fun createJob(job: CronJob) = mutex.withLock {
        jobs.add(job)
        scheduleJob(job)
        persistJobs()
        Timber.i("Cron job created: ${job.name}")
    }

    suspend fun deleteJob(id: String) = mutex.withLock {
        jobs.removeAll { it.id == id }
        workManager.cancelUniqueWork(id)
        persistJobs()
        Timber.i("Cron job deleted: $id")
    }

    suspend fun updateJob(job: CronJob) = mutex.withLock {
        jobs.replaceAll { if (it.id == job.id) job else it }
        workManager.cancelUniqueWork(job.id)
        if (job.enabled) {
            scheduleJob(job)
        }
        persistJobs()
    }

    fun getJobs(): List<CronJob> = jobs.toList()

    fun getEnabledJobs(): List<CronJob> = jobs.toList().filter { it.enabled }

    fun getJob(id: String): CronJob? = jobs.toList().find { it.id == id }

    // --- Execution History ---

    suspend fun recordExecution(execution: CronJobExecution) = mutex.withLock {
        executions.add(execution)
        // Keep last 500 executions in memory
        if (executions.size > 500) {
            executions.removeFirst()
        }
        persistExecution(execution)
    }

    fun getExecutions(jobId: String? = null, limit: Int = 50): List<CronJobExecution> {
        val snapshot = executions.toList()
        val filtered = if (jobId != null) {
            snapshot.filter { it.jobId == jobId }
        } else {
            snapshot
        }
        return filtered.sortedByDescending { it.startedAt }.take(limit)
    }

    fun getStats(jobId: String): CronJobStats {
        val execsSnapshot = executions.toList()
        val jobExecutions = execsSnapshot.filter { it.jobId == jobId }
        val job = jobs.toList().find { it.id == jobId }
        val successes = jobExecutions.filter { it.success }
        val failures = jobExecutions.filter { !it.success }

        val avgDuration = if (jobExecutions.isNotEmpty()) {
            jobExecutions.mapNotNull { exec ->
                exec.completedAt?.let { it - exec.startedAt }
            }.average().toLong()
        } else 0L

        return CronJobStats(
            jobId = jobId,
            jobName = job?.name ?: jobId,
            totalRuns = jobExecutions.size,
            successCount = successes.size,
            failureCount = failures.size,
            lastRunAt = jobExecutions.maxOfOrNull { it.startedAt.toLong() },
            lastSuccessAt = successes.maxOfOrNull { it.completedAt?.toLong() ?: 0L },
            lastFailureAt = failures.maxOfOrNull { it.completedAt?.toLong() ?: 0L },
            averageDurationMs = avgDuration
        )
    }

    fun getAllStats(): List<CronJobStats> {
        return jobs.map { getStats(it.id) }
    }

    suspend fun clearHistory(jobId: String? = null) = mutex.withLock {
        if (jobId != null) {
            executions.removeAll { it.jobId == jobId }
            File(historyDir, "$jobId.jsonl").delete()
        } else {
            executions.clear()
            historyDir.listFiles()?.forEach { it.delete() }
        }
    }

    // --- Scheduling ---

    private fun scheduleJob(job: CronJob) {
        if (!job.enabled) return

        val delayMs = parseCronToDelay(job.schedule)

        val workRequest = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    "job_id" to job.id,
                    "job_name" to job.name,
                    "agent_id" to job.agentId,
                    "query" to job.query,
                    "channel_id" to job.channelId,
                    "schedule" to job.schedule,
                    "session_id" to job.sessionId,
                    "silent_mode" to job.silentMode
                )
            )
            .build()

        workManager.enqueueUniqueWork(
            job.id,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private suspend fun resolveSessionId(job: CronJob): String {
        val sessions = sessionStore.getSessionsForAgent(job.agentId).getOrNull()
        val cronSession = sessions?.find { it.title == context.getString(R.string.cron_job_session_title, job.name) }
        if (cronSession != null) return cronSession.id

        val newSession = sessionStore.createSession(
            agentId = job.agentId,
            title = context.getString(R.string.cron_job_session_title, job.name)
        ).getOrNull()
        return newSession?.id ?: "cron:${job.id}"
    }

    private fun parseCronToDelay(cronExpression: String): Long {
        val parts = cronExpression.split(" ")
        if (parts.size < 5) return 60 * 60 * 1000L

        val minuteField = parts[0]
        val hourField = parts[1]
        val domField = parts[2]
        val monthField = parts[3]
        val dowField = parts[4]

        if (minuteField.startsWith("*/") || hourField.startsWith("*/")) {
            val minuteStep = if (minuteField.startsWith("*/")) minuteField.removePrefix("*/").toIntOrNull() ?: 1 else 0
            val hourStep = if (hourField.startsWith("*/")) hourField.removePrefix("*/").toIntOrNull() ?: 1 else 0
            if (minuteStep > 0) return minuteStep * 60 * 1000L
            if (hourStep > 0) return hourStep * 60 * 60 * 1000L
        }

        val minute = parseCronField(minuteField, 0, 59)
        val hour = parseCronField(hourField, 0, 23)

        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance()
        target.set(java.util.Calendar.HOUR_OF_DAY, hour)
        target.set(java.util.Calendar.MINUTE, minute)
        target.set(java.util.Calendar.SECOND, 0)
        target.set(java.util.Calendar.MILLISECOND, 0)

        if (target.timeInMillis <= now.timeInMillis) {
            target.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }

    private fun parseCronField(field: String, min: Int, max: Int): Int {
        if (field == "*") return min
        return field.toIntOrNull() ?: min
    }

    suspend fun executeJob(job: CronJob): Result<Unit> {
        val startTime = System.currentTimeMillis()
        val agent = agentFactory.getAgent(job.agentId)
            ?: return Result.failure(IllegalStateException("Agent not found: ${job.agentId}"))

        val sessionId = if (job.silentMode) {
            job.channelId
        } else if (job.sessionId.isNotBlank()) {
            job.sessionId
        } else {
            resolveSessionId(job)
        }
        val result = agent.processMessage(sessionId, job.channelId, job.query)
        val endTime = System.currentTimeMillis()

        recordExecution(
            CronJobExecution(
                jobId = job.id,
                jobName = job.name,
                agentId = job.agentId,
                startedAt = startTime,
                completedAt = endTime,
                success = result.isSuccess,
                error = result.exceptionOrNull()?.message,
                outputLength = result.getOrDefault("").toString().length
            )
        )

        return result
    }

    suspend fun parseHeartbeatSchedule(file: File): CronHeartbeatConfig = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext CronHeartbeatConfig()
        }
        runCatching {
            json.decodeFromString<CronHeartbeatConfig>(file.readText())
        }.getOrDefault(CronHeartbeatConfig())
    }

    suspend fun parseDreamSchedule(file: File): CronDreamConfig = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext CronDreamConfig()
        }
        runCatching {
            json.decodeFromString<CronDreamConfig>(file.readText())
        }.getOrDefault(CronDreamConfig())
    }

    // --- Persistence ---

    private suspend fun persistJobs() = withContext(Dispatchers.IO) {
        try {
            val parentDir = historyDir.parentFile ?: context.filesDir
            val jobsFile = File(parentDir, "cron_jobs.json")
            val data = json.encodeToString(ListSerializer(CronJob.serializer()), jobs.toList())
            jobsFile.writeText(data)
            Timber.d("Persisted ${jobs.size} cron jobs to ${jobsFile.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist cron jobs")
        }
    }

    private suspend fun persistExecution(execution: CronJobExecution) = withContext(Dispatchers.IO) {
        try {
            val file = File(historyDir, "${execution.jobId}.jsonl")
            file.appendText(json.encodeToString(CronJobExecution.serializer(), execution) + "\n")
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist execution: ${execution.jobId}")
        }
    }

    private fun loadHistory() {
        try {
            val parentDir = historyDir.parentFile ?: context.filesDir
            val jobsFile = File(parentDir, "cron_jobs.json")
            if (jobsFile.exists()) {
                val loadedJobs = json.decodeFromString<List<CronJob>>(jobsFile.readText())
                jobs.addAll(loadedJobs)
                Timber.d("Loaded ${loadedJobs.size} cron jobs from ${jobsFile.absolutePath}")
                loadedJobs.filter { it.enabled }.forEach { job ->
                    runCatching { scheduleJob(job) }
                }
            }

            // Load recent executions (last 500)
            historyDir.listFiles()?.forEach { file ->
                if (file.extension == "jsonl") {
                    file.readLines().takeLast(100).forEach { line ->
                        if (line.isNotBlank()) {
                            runCatching {
                                executions.add(json.decodeFromString<CronJobExecution>(line))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load cron history")
        }
    }
}

class CronJobWorker(
    context: Context,
    params: WorkerParameters,
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore,
    private val cronJobManager: CronJobManager
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString("job_id") ?: return Result.failure()
        val jobName = inputData.getString("job_name") ?: jobId
        val agentId = inputData.getString("agent_id") ?: return Result.failure()
        val query = inputData.getString("query") ?: return Result.failure()
        val channelId = inputData.getString("channel_id") ?: "cron"
        val schedule = inputData.getString("schedule") ?: ""
        val sessionId = inputData.getString("session_id") ?: ""
        val silentMode = inputData.getBoolean("silent_mode", false)

        return try {
            val job = CronJob(
                id = jobId,
                name = jobName,
                query = query,
                schedule = schedule,
                agentId = agentId,
                channelId = channelId,
                sessionId = sessionId,
                silentMode = silentMode
            )

            cronJobManager.executeJob(job)
                .onSuccess { Timber.i("Cron job executed: $jobId") }
                .onFailure { e -> Timber.e(e, "Cron job failed: $jobId") }

            // 执行完后重新调度下一次执行
            val existingJob = cronJobManager.getJob(jobId)
            if (existingJob != null && existingJob.enabled) {
                cronJobManager.updateJob(existingJob)
            }

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Cron job worker failed: $jobId")
            Result.retry()
        }
    }
}

@Serializable
data class CronHeartbeatConfig(
    val interval: String = "30m",
    val cron: String? = null,
    val query: String = "Check inbox, summarize recent activity",
    val active_hours: String? = null
)

@Serializable
data class CronDreamConfig(
    val schedule: String = "0 3 * * *",
    val enabled: Boolean = true
)


