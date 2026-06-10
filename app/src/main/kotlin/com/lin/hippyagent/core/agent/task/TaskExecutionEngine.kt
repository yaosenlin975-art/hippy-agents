package com.lin.hippyagent.core.agent.task

import com.lin.hippyagent.core.notification.NotificationCenter
import com.lin.hippyagent.core.notification.NotificationPriority
import com.lin.hippyagent.core.task.HippyJobQueue
import com.lin.hippyagent.core.task.HippyJobSubmitOpts
import com.lin.hippyagent.core.task.StallDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.coroutines.coroutineContext

enum class ApprovalDecision {
    APPROVE,
    REJECT,
    MODIFY
}

class TaskExecutionEngine(
    private val dao: TaskDao,
    private val jobQueue: HippyJobQueue,
    private val stallDetector: StallDetector,
    private val applicationScope: CoroutineScope,
    private val taskPlanner: TaskPlanner? = null,
    private val notificationCenter: NotificationCenter? = null,
    private val auditLogger: AuditLogger? = null,
    private val approvalService: TaskApprovalService? = null
) {

    init {
        applicationScope.launch {
            while (coroutineContext.isActive) {
                runCatching { stallDetector.detectAndRecover() }
                    .onFailure { Timber.e(it, "TaskExecutionEngine stall detection error") }
                delay(STALL_CHECK_INTERVAL_MS)
            }
        }
    }

    suspend fun startTask(taskId: String) {
        val task = requireTask(taskId)
        val started = transition(task, TaskStatus.RUNNING)
        dao.update(started)
        emitStatusChange(started, "已启动")
        runNextStep(taskId)
    }

    /**
     * 创建并启动一个任务。query >= planThreshold 时调 TaskPlanner.planTask (失败抛异常);
     * 短 query 直接退到单 step。task 已写入 dao + startTask 已触发。
     *
     * @throws TaskPlanningException 拆解失败时抛出(调用方负责记录 CronRunLog 等外部日志)
     */
    suspend fun enqueueTask(
        query: String,
        title: String,
        agentId: String,
        sessionId: String? = null,
        planThreshold: Int = PLAN_THRESHOLD_CHARS,
    ): String {
        val steps: List<TaskStep> = if (query.trim().length >= planThreshold && taskPlanner != null) {
            taskPlanner.planTask(query)
        } else {
            listOf(TaskStep(description = query.trim()))
        }
        val task = TaskEntity(
            title = title,
            agentId = agentId,
            sessionId = sessionId,
            status = TaskStatus.PENDING,
            steps = steps,
            executionContext = ExecutionContext(snapshot = query),
            approvalNodes = emptyList(),
        )
        dao.insert(task)
        startTask(task.id)
        return task.id
    }

    /**
     * 把当前 step 标记为 COMPLETED,记 result,推进到下一步。
     * 由 TaskStepJobHandler 在 Agent.processMessage 成功后调用。
     */
    suspend fun markStepCompleted(taskId: String, stepId: String, result: String?) {
        val task = requireTask(taskId)
        if (isTerminal(task.status)) return
        val now = System.currentTimeMillis()
        val updated = task.copy(
            steps = task.steps.map { step ->
                if (step.id == stepId) step.copy(
                    status = StepStatus.COMPLETED,
                    result = result,
                    completedAt = now,
                ) else step
            },
        )
        dao.update(updated)
        runNextStep(taskId)
    }

    /**
     * 把当前 step 标记为 FAILED,记 error,任务整体转 FAILED + 通知。
     */
    suspend fun markStepFailed(taskId: String, stepId: String, errorMessage: String) {
        val task = requireTask(taskId)
        if (isTerminal(task.status)) return
        val now = System.currentTimeMillis()
        val withStepFailed = task.copy(
            steps = task.steps.map { step ->
                if (step.id == stepId) step.copy(
                    status = StepStatus.FAILED,
                    error = errorMessage,
                    completedAt = now,
                ) else step
            },
        )
        dao.update(withStepFailed)
        failTask(taskId, "步骤失败: $errorMessage")
    }

    suspend fun runNextStep(taskId: String) {
        val task = requireTask(taskId)
        if (isTerminal(task.status)) return

        val nextStep = task.steps.firstOrNull { it.status == StepStatus.PENDING }
        if (nextStep == null) {
            maybeCompleteIfAllDone(task)
            return
        }

        val now = System.currentTimeMillis()
        val withStep = task.copy(
            steps = task.steps.map { step ->
                if (step.id == nextStep.id) step.copy(
                    status = StepStatus.RUNNING,
                    startedAt = now
                ) else step
            },
            executionContext = task.executionContext.copy(currentStepId = nextStep.id)
        )

        if (nextStep.requiresApproval) {
            val awaiting = transition(withStep, TaskStatus.AWAITING_APPROVAL)
            dao.update(awaiting)
            emitStatusChange(awaiting, "等待审批: ${nextStep.description}")
            requestApproval(awaiting, nextStep)
        } else {
            dao.update(withStep)
            enqueueStep(withStep, nextStep)
        }
    }

    suspend fun handleApproval(
        taskId: String,
        approvalId: String,
        decision: ApprovalDecision,
        reason: String? = null,
    ) {
        // 优先委托给 TaskApprovalService(统一超时/审计/状态检查/通知),
        // 服务不可用时回退到本地处理,保证向后兼容。
        val service = approvalService
        if (service != null) {
            when (decision) {
                ApprovalDecision.APPROVE -> service.approve(approvalId, reason = reason)
                ApprovalDecision.REJECT -> service.reject(approvalId, reason = reason)
                ApprovalDecision.MODIFY -> service.modify(approvalId, reason = reason ?: "user modify")
            }
            // 服务已经发了 STATUS_CHANGE 通知,这里不再二次 emit;
            // 只根据最新状态决定是否继续推进(继续 = RUNNING)或保持终态。
            val resumed = dao.getById(taskId)
            if (resumed != null && resumed.status == TaskStatus.RUNNING) {
                runNextStep(taskId)
            }
            return
        }

        val task = requireTask(taskId)
        val node = task.approvalNodes.firstOrNull { it.id == approvalId }
            ?: throw IllegalArgumentException("Approval $approvalId not found in task $taskId")

        val now = System.currentTimeMillis()
        val updatedNode = when (decision) {
            ApprovalDecision.APPROVE -> node.copy(status = ApprovalStatus.APPROVED, decidedAt = now)
            ApprovalDecision.REJECT -> node.copy(status = ApprovalStatus.REJECTED, decidedAt = now)
            ApprovalDecision.MODIFY -> node.copy(status = ApprovalStatus.MODIFIED, decidedAt = now)
        }
        val withNode = task.copy(
            approvalNodes = task.approvalNodes.map { if (it.id == approvalId) updatedNode else it }
        )

        when (decision) {
            ApprovalDecision.APPROVE -> {
                val resumed = transition(withNode, TaskStatus.RUNNING)
                dao.update(resumed)
                emitStatusChange(resumed, "审批通过,继续执行")
                runNextStep(taskId)
            }
            ApprovalDecision.REJECT -> {
                val failed = transition(withNode, TaskStatus.FAILED)
                dao.update(failed)
                emitTaskFailed(failed, "用户拒绝审批")
            }
            ApprovalDecision.MODIFY -> {
                val resumed = transition(withNode, TaskStatus.RUNNING)
                dao.update(resumed)
                emitStatusChange(resumed, "用户修改,重新执行")
                runNextStep(taskId)
            }
        }
    }

    suspend fun completeTask(taskId: String, result: String? = null) {
        val task = requireTask(taskId)
        val now = System.currentTimeMillis()
        val withResult = task.copy(
            result = result,
            completedAt = now
        )
        val final = transition(withResult, TaskStatus.COMPLETED)
        dao.update(final)
        emitTaskCompleted(final, "任务完成")
    }

    suspend fun failTask(taskId: String, errorMessage: String) {
        val task = requireTask(taskId)
        val now = System.currentTimeMillis()
        val withError = task.copy(
            errorMessage = errorMessage,
            completedAt = now
        )
        val final = transition(withError, TaskStatus.FAILED)
        dao.update(final)
        emitTaskFailed(final, errorMessage)
    }

    suspend fun cancelTask(taskId: String) {
        val task = requireTask(taskId)
        val now = System.currentTimeMillis()
        val withCancel = task.copy(completedAt = now)
        val final = transition(withCancel, TaskStatus.CANCELLED)
        dao.update(final)
        emitStatusChange(final, "已取消")
    }

    private suspend fun requestApproval(task: TaskEntity, step: TaskStep) {
        Timber.d("TaskExecutionEngine: approval requested taskId=${task.id} stepId=${step.id}")
        // 避免重复注册:如果该 stepId 已经存在 PENDING 节点,直接复用
        val existing = task.approvalNodes.firstOrNull { it.stepId == step.id }
        val node = existing ?: ApprovalNode(
            stepId = step.id,
            prompt = "步骤需要审批: ${step.description}",
            options = listOf("approve", "reject", "modify"),
            timeoutSec = 300
        )
        val withNode = task.copy(
            approvalNodes = if (existing == null) task.approvalNodes + node else task.approvalNodes
        )
        dao.update(withNode)
        if (existing == null) {
            approvalService?.register(withNode, node)
        }
        notificationCenter?.notifyApprovalRequest(
            approvalId = node.id,
            taskId = task.id,
            prompt = node.prompt,
            options = node.options
        )
    }

    private suspend fun maybeCompleteIfAllDone(task: TaskEntity) {
        val allDone = task.steps.isNotEmpty() && task.steps.all {
            it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED
        }
        if (allDone) {
            val final = transition(task, TaskStatus.COMPLETED).copy(
                completedAt = System.currentTimeMillis()
            )
            dao.update(final)
            emitTaskCompleted(final, "所有步骤已完成")
        }
    }

    private suspend fun enqueueStep(task: TaskEntity, step: TaskStep) {
        runCatching {
            jobQueue.submit(
                name = TASK_STEP_JOB_NAME,
                data = mapOf(
                    "task_id" to task.id,
                    "step_id" to step.id,
                    "agent_id" to task.agentId,
                    "description" to step.description,
                    "tool_ref" to (step.toolRef ?: ""),
                    "payload" to (step.payload ?: "")
                ),
                opts = HippyJobSubmitOpts(
                    queue = "default",
                    timeoutMs = STEP_TIMEOUT_MS
                )
            )
        }.onFailure { Timber.e(it, "Failed to enqueue task step: ${task.id}/${step.id}") }
            .onSuccess {
                if (auditLogger != null && !step.toolRef.isNullOrBlank()) {
                    val toolName = step.toolRef
                    val arguments = step.payload ?: ""
                    auditLogger.logToolCall(
                        stepId = step.id,
                        toolName = toolName,
                        arguments = arguments,
                        output = "",
                        durationMs = 0L,
                        success = true,
                        sessionId = task.sessionId,
                        agentId = task.agentId,
                        taskId = task.id
                    )
                }
            }
    }

    private fun transition(task: TaskEntity, newStatus: TaskStatus): TaskEntity {
        validateTransition(task.status, newStatus)
        return task.copy(
            status = newStatus,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun validateTransition(current: TaskStatus, next: TaskStatus) {
        if (!current.canTransitionTo(next)) {
            throw IllegalStateException("Illegal task status transition: $current -> $next")
        }
    }

    private fun isTerminal(status: TaskStatus): Boolean = status.isTerminal

    private suspend fun requireTask(taskId: String): TaskEntity {
        return dao.getById(taskId) ?: throw IllegalArgumentException("Task $taskId not found")
    }

    /** worker 读 task 入口(不抛异常,找不到返 null) */
    suspend fun getTaskForWorker(taskId: String): TaskEntity? = dao.getById(taskId)

    private suspend fun emitTaskCompleted(task: TaskEntity, reason: String) {
        notificationCenter?.notifyTaskCompleted(
            taskId = task.id,
            title = "任务完成: ${task.title}",
            body = reason,
            priority = NotificationPriority.NORMAL
        )
    }

    private suspend fun emitTaskFailed(task: TaskEntity, reason: String) {
        notificationCenter?.notifyTaskFailed(
            taskId = task.id,
            title = "任务失败: ${task.title}",
            body = reason,
            priority = NotificationPriority.HIGH
        )
    }

    private suspend fun emitStatusChange(task: TaskEntity, message: String) {
        notificationCenter?.notifyStatusChange(
            source = task.id,
            sourceType = "task",
            title = "任务状态变更: ${task.title}",
            body = message
        )
    }

    companion object {
        const val TASK_STEP_JOB_NAME = "task_step"
        private const val STALL_CHECK_INTERVAL_MS = 30_000L
        private const val STEP_TIMEOUT_MS = 600_000L
        private const val PLAN_THRESHOLD_CHARS = 30
    }
}
