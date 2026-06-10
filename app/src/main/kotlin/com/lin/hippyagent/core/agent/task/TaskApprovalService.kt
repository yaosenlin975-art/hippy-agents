package com.lin.hippyagent.core.agent.task

import com.lin.hippyagent.core.notification.NotificationCenter
import com.lin.hippyagent.core.notification.NotificationEvent
import com.lin.hippyagent.core.notification.NotificationPriority
import com.lin.hippyagent.core.notification.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务审批服务 — 在 TaskExecutionEngine 流程中嵌入显式审批节点。
 *
 * 流程:
 * 1. 步骤执行过程中遇到 ApprovalNode(requiresApproval=true) → 任务转入 AWAITING_APPROVAL
 * 2. 通过 NotificationCenter 发出 APPROVAL_REQUEST
 * 3. 等待用户决策 (approve/reject/modify) 或超时
 * 4. 决策落地后任务回到 RUNNING 继续执行
 *
 * 审计: 每次决策都写入 AuditLogger 留痕。
 */
class TaskApprovalService(
    private val dao: TaskDao,
    private val auditLogger: AuditLogger,
    private val notificationCenter: NotificationCenter?,
    private val applicationScope: CoroutineScope,
) {
    private val timeoutJobs = ConcurrentHashMap<String, Job>()

    /**
     * 注册审批节点(由 TaskExecutionEngine 在步骤需要审批时调用)。
     * 启动超时监控协程;超时不响应则按 PENDING → TIMEOUT 记录。
     * 若同一 node 重复注册,先取消旧的超时协程再启动新的,避免泄漏。
     */
    fun register(task: TaskEntity, node: ApprovalNode): TaskEntity {
        timeoutJobs.remove(node.id)?.cancel()
        val job = applicationScope.launch {
            runCatching {
                delay(node.timeoutSec * 1000L)
                handleTimeout(task.id, node.id)
            }.onFailure { Timber.w(it, "Approval timeout watcher failed for ${node.id}") }
        }
        timeoutJobs[node.id] = job
        job.invokeOnCompletion { _ -> timeoutJobs.remove(node.id) }
        return task
    }

    /**
     * 用户通过审批。
     */
    suspend fun approve(nodeId: String, reason: String? = null) {
        timeoutJobs.remove(nodeId)?.cancel()
        decide(nodeId, ApprovalStatus.APPROVED, decidedBy = "user", reason = reason)
    }

    /**
     * 用户拒绝审批。
     */
    suspend fun reject(nodeId: String, reason: String? = null) {
        timeoutJobs.remove(nodeId)?.cancel()
        decide(nodeId, ApprovalStatus.REJECTED, decidedBy = "user", reason = reason)
    }

    /**
     * 用户修改(携带修改备注)。
     */
    suspend fun modify(nodeId: String, reason: String) {
        timeoutJobs.remove(nodeId)?.cancel()
        decide(nodeId, ApprovalStatus.MODIFIED, decidedBy = "user", reason = reason)
    }

    private suspend fun decide(
        nodeId: String,
        status: ApprovalStatus,
        decidedBy: String,
        reason: String?,
    ) {
        val task = findTaskByNodeId(nodeId) ?: run {
            Timber.w("TaskApprovalService: task for node $nodeId not found")
            return
        }
        val currentNode = task.approvalNodes.firstOrNull { it.id == nodeId } ?: run {
            Timber.w("TaskApprovalService: node $nodeId not found in task ${task.id}")
            return
        }
        // 防止并发覆盖:已决策的节点不再接受新的决策
        if (currentNode.status != ApprovalStatus.PENDING) {
            Timber.d("TaskApprovalService: node $nodeId already ${currentNode.status}, skip $status")
            return
        }
        // 校验任务状态:必须在 AWAITING_APPROVAL,否则是并发串改(用户取消/超时)
        if (task.status != TaskStatus.AWAITING_APPROVAL) {
            Timber.d("TaskApprovalService: task ${task.id} status=${task.status} (expected AWAITING_APPROVAL), skip $status")
            return
        }
        val nextStatus = when (status) {
            ApprovalStatus.APPROVED, ApprovalStatus.MODIFIED -> TaskStatus.RUNNING
            ApprovalStatus.REJECTED, ApprovalStatus.TIMEOUT -> TaskStatus.FAILED
            ApprovalStatus.PENDING -> task.status
        }
        // 走共享状态机校验,引擎侧也用同一份规则
        if (!task.status.canTransitionTo(nextStatus)) {
            Timber.w("TaskApprovalService: illegal transition ${task.status} -> $nextStatus for task ${task.id}")
            return
        }
        val updatedNodes = task.approvalNodes.map { n ->
            if (n.id == nodeId) {
                n.copy(
                    status = status,
                    decidedBy = decidedBy,
                    decidedAt = System.currentTimeMillis(),
                    decisionReason = reason
                )
            } else n
        }
        val updated = task.copy(
            approvalNodes = updatedNodes,
            status = nextStatus,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(updated)
        auditLogger.logToolCall(
            stepId = nodeId,
            toolName = "approval:${nodeId}",
            arguments = "decision=${status.name};reason=${reason ?: ""}",
            output = "approval $status at ${updated.updatedAt}",
            durationMs = 0L,
            success = status != ApprovalStatus.REJECTED,
            taskId = task.id
        )
        notificationCenter?.notify(
            NotificationEvent(
                id = "approval-${nodeId}-${updated.updatedAt}",
                type = NotificationType.STATUS_CHANGE,
                priority = NotificationPriority.NORMAL,
                title = "审批${statusName(status)}",
                body = "任务 ${task.title} 审批${statusName(status)}",
                source = "TaskApprovalService",
                sourceType = "task.approval",
                payload = "{\"taskId\":\"${task.id}\",\"nodeId\":\"$nodeId\",\"status\":\"${status.name}\"}",
                aggregateKey = "task-${task.id}-approval",
                createdAt = updated.updatedAt
            )
        )
    }

    private suspend fun handleTimeout(taskId: String, nodeId: String) {
        val task = dao.getById(taskId) ?: return
        val node = task.approvalNodes.firstOrNull { it.id == nodeId } ?: return
        if (node.status != ApprovalStatus.PENDING) return
        decide(nodeId, ApprovalStatus.TIMEOUT, decidedBy = "system", reason = "审批超时")
    }

    /**
     * 用 nodeId 查找任务。
     * 节点 ID 在 JSON 中以 `"id":"<nodeId>"` 形式存在,使用前后边界避免子串误匹配。
     */
    private suspend fun findTaskByNodeId(nodeId: String): TaskEntity? {
        val escaped = nodeId
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val pattern = "%\"id\":\"$escaped\"%"
        return runCatching { dao.findByApprovalNodeId(pattern) }.getOrNull()
    }

    private fun statusName(status: ApprovalStatus): String = when (status) {
        ApprovalStatus.APPROVED -> "通过"
        ApprovalStatus.REJECTED -> "拒绝"
        ApprovalStatus.MODIFIED -> "修改"
        ApprovalStatus.TIMEOUT -> "超时"
        ApprovalStatus.PENDING -> "待决"
    }
}
