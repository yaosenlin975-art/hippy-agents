package com.lin.hippyagent.core.cron

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lin.hippyagent.core.agent.task.TaskExecutionEngine
import com.lin.hippyagent.core.agent.task.TaskPlanningException
import com.lin.hippyagent.core.scheduler.ScheduledTask
import com.lin.hippyagent.core.scheduler.ScheduledTaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CronService(
    private val applicationScope: CoroutineScope,
    private val workManager: WorkManager,
    private val runLog: CronRunLog,
    private val taskStore: ScheduledTaskStore,
    private val taskEngine: TaskExecutionEngine? = null,
    private val parser: CronScheduleParser = CronScheduleParser()
) {
    private val mutex = Mutex()
    private val nextFireCache = MutableStateFlow<Map<String, Long?>>(emptyMap())

    fun observeNextFireTime(taskId: String): Flow<Long?> {
        val scheduled = taskStore.observeAll()
        val cached = nextFireCache.asStateFlow().map { it[taskId] }
        return combine(scheduled, cached) { tasks, cachedMs ->
            cachedMs ?: tasks.find { it.id == taskId }?.nextFireTime?.takeIf { it > 0L }
        }
    }

    fun observeRecentRuns(taskId: String, limit: Int = 50): Flow<List<CronRunRecord>> =
        runLog.observeByTask(taskId).map { list ->
            list.sortedByDescending { it.scheduledTime }.take(limit)
        }

    fun computeNextFire(task: CronTask): Long? =
        parser.nextFireTime(task.cronExpression)

    fun computeNextFireFromExpression(expression: String, from: Long = System.currentTimeMillis()): Long? =
        parser.nextFireTime(expression, from)

    suspend fun scheduleTask(task: CronTask): Long = mutex.withLock {
        val nextMs = parser.nextFireTime(task.cronExpression) ?: 0L
        val enriched = task.copy(nextFireTime = nextMs)
        persistTask(enriched)
        cacheNextFire(enriched.id, nextMs)
        if (enriched.enabled && nextMs > 0L) {
            enqueueWork(enriched.id, nextMs)
        }
        nextMs
    }

    suspend fun cancelTask(taskId: String) = mutex.withLock {
        workManager.cancelUniqueWork(uniqueWorkName(taskId))
        cacheNextFire(taskId, null)
        runCatching { taskStore.delete(taskId) }
    }

    suspend fun rescheduleAll() = mutex.withLock {
        val all = taskStore.getAll()
        for (task in all) {
            val nextMs = parser.nextFireTime(task.cron) ?: 0L
            taskStore.update(task.copy(nextFireTime = nextMs))
            cacheNextFire(task.id, nextMs)
            workManager.cancelUniqueWork(uniqueWorkName(task.id))
            if (task.enabled && nextMs > 0L) {
                enqueueWork(task.id, nextMs)
            }
        }
    }

    internal suspend fun handleFire(taskId: String): FireOutcome = mutex.withLock {
        val task = taskStore.getById(taskId)
        if (task == null || !task.enabled) {
            workManager.cancelUniqueWork(uniqueWorkName(taskId))
            return@withLock FireOutcome(skipped = true, nextFireTime = 0L)
        }
        val start = System.currentTimeMillis()
        val scheduled = task.nextFireTime.takeIf { it > 0L } ?: start
        val success = runTask(task)
        val duration = System.currentTimeMillis() - start
        val status = if (success) CronRunStatus.SUCCESS else CronRunStatus.FAILED
        runLog.insert(
            CronRunRecord(
                taskId = taskId,
                scheduledTime = scheduled,
                actualFireTime = start,
                completedAt = System.currentTimeMillis(),
                status = status.name,
                errorMessage = if (success) null else "runTask returned false",
                durationMs = duration
            )
        )
        val nextMs = parser.nextFireTime(task.cron, from = System.currentTimeMillis()) ?: 0L
        taskStore.update(
            task.copy(
                nextFireTime = nextMs,
                lastFireTime = start
            )
        )
        cacheNextFire(taskId, nextMs)
        if (nextMs > 0L) {
            enqueueWork(taskId, nextMs)
        }
        FireOutcome(skipped = false, nextFireTime = nextMs)
    }

    internal fun cacheNextFire(taskId: String, nextMs: Long?) {
        val current = nextFireCache.value.toMutableMap()
        if (nextMs == null) current.remove(taskId) else current[taskId] = nextMs
        nextFireCache.value = current.toMap()
    }

    internal fun enqueueWork(taskId: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<CronServiceWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString("task_id", taskId)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(taskId),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private suspend fun persistTask(task: CronTask) {
        val scheduled = ScheduledTask(
            id = task.id,
            name = task.name,
            query = task.taskRef,
            cron = task.cronExpression,
            isoTimestamp = "",
            originalNlText = task.name,
            nextFireTime = task.nextFireTime,
            enabled = task.enabled,
            agentId = "",
            sessionId = ""
        )
        val existing = taskStore.getById(task.id)
        if (existing == null) taskStore.insert(scheduled) else taskStore.update(scheduled)
    }

    internal fun uniqueWorkName(taskId: String): String = "cron_service_$taskId"

    private suspend fun runTask(task: ScheduledTask): Boolean {
        val engine = taskEngine ?: run {
            Timber.w("CronService.runTask: taskEngine 未注入, 跳过 task=${task.id}")
            return false
        }
        val query = task.query.trim()
        if (query.isEmpty()) {
            Timber.w("CronService.runTask: query 为空 task=${task.id}")
            return false
        }
        if (task.agentId.isBlank()) {
            Timber.w("CronService.runTask: agentId 为空 task=${task.id}")
            return false
        }
        return runCatching {
            engine.enqueueTask(
                query = query,
                title = task.name.ifBlank { query.take(20) },
                agentId = task.agentId,
                sessionId = task.sessionId?.takeIf { it.isNotBlank() },
            )
            true
        }.getOrElse { e ->
            if (e is TaskPlanningException) {
                Timber.w(e, "CronService.runTask: 规划失败 task=${task.id} (不创建 TaskEntity)")
            } else {
                Timber.e(e, "CronService.runTask: 异常 task=${task.id}")
            }
            false
        }
    }
}

internal data class FireOutcome(val skipped: Boolean, val nextFireTime: Long)

class CronServiceWorker(
    context: Context,
    params: WorkerParameters,
    private val cronService: CronService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.failure()
        return try {
            cronService.handleFire(taskId)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "CronServiceWorker: failed for $taskId")
            Result.retry()
        }
    }
}
