package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 定时任务执行技能 - 支持 cron 表达式调度 Agent 任务
 * 使用协程替代 ScheduledExecutorService，避免 runBlocking 阻塞线程池
 */
class CronSkill(
    private val context: Context,
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scheduledTasks = ConcurrentHashMap<String, ScheduledTask>()

    /**
     * 添加定时任务
     */
    suspend fun addTask(
        taskId: String,
        agentId: String,
        cronExpression: String,
        command: String
    ): Result<ScheduledTask> {
        return try {
            val agent = agentFactory.getAgent(agentId)
                ?: return Result.failure(IllegalStateException("Agent not found: $agentId"))

            val task = ScheduledTask(
                taskId = taskId,
                agentId = agentId,
                cronExpression = cronExpression,
                command = command,
                isActive = true,
                createdAt = System.currentTimeMillis()
            )

            // 解析 cron 表达式并调度
            val initialDelay = calculateNextDelay(cronExpression)
            if (initialDelay > 0) {
                val job = scope.launch {
                while (coroutineContext.isActive) {
                    val nextDelay = calculateNextDelay(cronExpression)
                    delay(nextDelay)
                    executeTask(task)
                }
            }

            scheduledTasks[taskId] = task.copy(job = job)
                Timber.i("Scheduled task: $taskId with initial delay: ${initialDelay}ms")
            }

            Result.success(task)
        } catch (e: Exception) {
            Timber.e(e, "Failed to add cron task: $taskId")
            Result.failure(e)
        }
    }

    /**
     * 移除定时任务
     */
    fun removeTask(taskId: String): Result<Unit> {
        return try {
            val task = scheduledTasks[taskId]
                ?: return Result.failure(IllegalStateException("Task not found: $taskId"))

            task.job?.cancel()
            scheduledTasks.remove(taskId)
            Timber.i("Removed cron task: $taskId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove cron task: $taskId")
            Result.failure(e)
        }
    }

    /**
     * 获取所有任务
     */
    fun getTasks(): List<ScheduledTask> {
        return scheduledTasks.values.toList()
    }

    /**
     * 暂停任务
     */
    fun pauseTask(taskId: String): Result<Unit> {
        return try {
            val task = scheduledTasks[taskId]
                ?: return Result.failure(IllegalStateException("Task not found: $taskId"))

            task.job?.cancel()
            scheduledTasks[taskId] = task.copy(isActive = false, job = null)
            Timber.i("Paused cron task: $taskId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to pause cron task: $taskId")
            Result.failure(e)
        }
    }

    /**
     * 恢复任务
     */
    fun resumeTask(taskId: String): Result<Unit> {
        return try {
            val task = scheduledTasks[taskId]
                ?: return Result.failure(IllegalStateException("Task not found: $taskId"))

            if (task.isActive) {
                return Result.success(Unit)
            }

            val job = scope.launch {
                while (coroutineContext.isActive) {
                    val nextDelay = calculateNextDelay(task.cronExpression)
                    delay(nextDelay)
                    executeTask(task)
                }
            }

            scheduledTasks[taskId] = task.copy(isActive = true, job = job)
            Timber.i("Resumed cron task: $taskId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume cron task: $taskId")
            Result.failure(e)
        }
    }

    private suspend fun executeTask(task: ScheduledTask) {
        try {
            val sessionId = "cron:${task.taskId}"

            val agent = agentFactory.getAgent(task.agentId) ?: return
            agent.processMessage(sessionId, "cron", task.command)
            Timber.i("Executed cron task: ${task.taskId}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to execute cron task: ${task.taskId}")
        }
    }

    /**
     * 计算下一次执行的延迟毫秒数
     * 支持简单的 cron 表达式（* * * * * 格式）
     */
    private fun calculateNextDelay(cronExpression: String): Long {
        val parts = cronExpression.split(" ")
        if (parts.size < 5) return 60_000L // 默认 1 分钟

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        // 分钟
        val minute = parts[0].toIntOrNull()
        if (minute != null) {
            calendar.set(Calendar.MINUTE, minute)
        }

        // 小时
        val hour = parts[1].toIntOrNull()
        if (hour != null) {
            calendar.set(Calendar.HOUR_OF_DAY, hour)
        }

        // 如果时间已过，推迟到下一个执行时间
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        return calendar.timeInMillis - System.currentTimeMillis()
    }

    /**
     * 停止所有任务
     */
    fun shutdown() {
        scheduledTasks.values.forEach { it.job?.cancel() }
        scheduledTasks.clear()
        scope.cancel()
    }
}

data class ScheduledTask(
    val taskId: String,
    val agentId: String,
    val cronExpression: String,
    val command: String,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val job: Job? = null
)

