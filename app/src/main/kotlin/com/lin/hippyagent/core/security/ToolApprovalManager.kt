package com.lin.hippyagent.core.security

import com.lin.hippyagent.core.agent.task.ApprovalNode
import com.lin.hippyagent.core.agent.task.ExecutionContext
import com.lin.hippyagent.core.agent.task.TaskApprovalService
import com.lin.hippyagent.core.agent.task.TaskDao
import com.lin.hippyagent.core.agent.task.TaskEntity
import com.lin.hippyagent.core.agent.task.TaskStatus
import com.lin.hippyagent.core.agent.task.TaskStep
import com.lin.hippyagent.core.tools.ToolGuardian
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ApprovalAction {
    ALLOW_ONCE,
    ALLOW_ALWAYS,
    DENY_ONCE,
    DENY_ALWAYS
}

data class PendingToolApproval(
    val requestId: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    val riskLevel: ToolGuardian.RiskLevel,
    val findings: List<GuardFinding>,
    val sessionId: String?,
    val agentId: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ApprovalRule(
    val key: String,
    val action: ApprovalAction,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isAllowed: Boolean get() = action == ApprovalAction.ALLOW_ALWAYS
    val isDenied: Boolean get() = action == ApprovalAction.DENY_ALWAYS
}

/**
 * 单工具调用的审批拦截器。
 *
 * 重构 (pending-migration-v2 inbox-approval-merge): 不再写 inbox_events,
 * 改为建临时 TaskEntity (source='tool_approval') 走 TaskApprovalService。
 * 规则从 DataStore 迁到 Room tool_approval_rules (ToolApprovalRuleDao)。
 *
 * 流程:
 * 1. requestApproval → checkRule 查 Room → 命中 ALWAYS_* 直接返回
 * 2. 未命中 → 建临时 TaskEntity (status=AWAITING_APPROVAL) + 调 TaskApprovalService.register
 * 3. 决策 (UI / broadcast) → resolveApproval → 写 ALWAYS_* 规则 (可选) + TaskApprovalService.approve/reject + complete deferred
 */
class ToolApprovalManager(
    private val taskApprovalService: TaskApprovalService,
    private val taskDao: TaskDao,
    private val ruleDao: ToolApprovalRuleDao,
) {
    private val _pendingApprovals = MutableStateFlow<List<PendingToolApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingToolApproval>> = _pendingApprovals.asStateFlow()
    private val pendingMutex = Mutex()

    private val pendingDeferreds = ConcurrentHashMap<String, CompletableDeferred<ApprovalAction>>()
    // 防止 UI + broadcast 并发重复处理同一 requestId
    private val resolvedSet = ConcurrentHashMap.newKeySet<String>()

    fun ruleKey(toolName: String, arguments: Map<String, Any>): String {
        val argSummary = arguments.entries
            .filter { it.key != "callId" }
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value.toString().take(50)}" }
        return "$toolName|$argSummary"
    }

    fun ruleKeyForTool(toolName: String): String {
        return "$toolName|*"
    }

    suspend fun checkRule(toolName: String, arguments: Map<String, Any>): ApprovalAction? {
        val exactKey = ruleKey(toolName, arguments)
        ruleDao.getByKey(exactKey)?.let {
            val a = runCatching { ApprovalAction.valueOf(it.action) }.getOrNull()
            if (a == ApprovalAction.ALLOW_ALWAYS || a == ApprovalAction.DENY_ALWAYS) return a
        }
        val toolKey = ruleKeyForTool(toolName)
        ruleDao.getByKey(toolKey)?.let {
            val a = runCatching { ApprovalAction.valueOf(it.action) }.getOrNull()
            if (a == ApprovalAction.ALLOW_ALWAYS || a == ApprovalAction.DENY_ALWAYS) return a
        }
        return null
    }

    suspend fun requestApproval(
        toolName: String,
        arguments: Map<String, Any>,
        riskLevel: ToolGuardian.RiskLevel,
        findings: List<GuardFinding>,
        sessionId: String?,
        agentId: String
    ): ApprovalAction {
        val existingRule = checkRule(toolName, arguments)
        if (existingRule == ApprovalAction.ALLOW_ALWAYS) return ApprovalAction.ALLOW_ALWAYS
        if (existingRule == ApprovalAction.DENY_ALWAYS) return ApprovalAction.DENY_ALWAYS

        val requestId = UUID.randomUUID().toString()
        val pending = PendingToolApproval(
            requestId = requestId,
            toolName = toolName,
            arguments = arguments,
            riskLevel = riskLevel,
            findings = findings,
            sessionId = sessionId,
            agentId = agentId
        )

        pendingMutex.withLock {
            _pendingApprovals.value = _pendingApprovals.value + pending
        }
        pendingDeferreds[requestId] = CompletableDeferred()

        val findingsSummary = findings.take(3).joinToString(", ") { it.title }
        // node.id == task.id == requestId: 1 step + 1 node, 一一对应, 简化下游查找
        val node = ApprovalNode(
            id = requestId,
            stepId = requestId,
            prompt = findingsSummary.ifBlank { "工具 $toolName 需审批" },
            timeoutSec = 300
        )
        val step = TaskStep(
            id = requestId,
            description = toolName,
            requiresApproval = true,
            toolRef = toolName,
            payload = arguments.toString()
        )
        val task = TaskEntity(
            id = requestId,
            title = "工具审批: $toolName",
            agentId = agentId,
            sessionId = sessionId,
            status = TaskStatus.AWAITING_APPROVAL,
            source = "tool_approval",
            steps = listOf(step),
            executionContext = ExecutionContext(snapshot = findingsSummary),
            approvalNodes = listOf(node),
        )
        taskDao.insert(task)
        taskApprovalService.register(task, node)

        return try {
            withTimeoutOrNull(310_000L) { // 略大于 TaskApprovalService 内部 300s
                pendingDeferreds[requestId]?.await()
            } ?: ApprovalAction.DENY_ONCE
        } finally {
            pendingMutex.withLock {
                _pendingApprovals.value = _pendingApprovals.value.filter { it.requestId != requestId }
            }
            pendingDeferreds.remove(requestId)
        }
    }

    /**
     * UI / broadcast 调起, 完成审批决策。
     * - ALWAYS_* → 写 tool_approval_rules
     * - 调 TaskApprovalService.approve/reject (完成 Room + NotificationCenter + 超时清理)
     * - complete deferred 让 requestApproval 返回
     *
     * 同 requestId 多次调用幂等: resolvedSet 保证只处理一次, 后续只补一次 deferred.complete。
     */
    suspend fun resolveApproval(requestId: String, action: ApprovalAction) {
        if (!resolvedSet.add(requestId)) {
            // 已处理过, 补一次 deferred (可能另一线程先 await 后我们再 complete)
            pendingDeferreds.remove(requestId)?.complete(action)
            return
        }

        val pending = _pendingApprovals.value.find { it.requestId == requestId }

        when (action) {
            ApprovalAction.ALLOW_ONCE -> {}
            ApprovalAction.ALLOW_ALWAYS -> if (pending != null) saveRule(pending.toolName, pending.arguments, ApprovalAction.ALLOW_ALWAYS)
            ApprovalAction.DENY_ONCE -> {}
            ApprovalAction.DENY_ALWAYS -> if (pending != null) saveRule(pending.toolName, pending.arguments, ApprovalAction.DENY_ALWAYS)
        }

        when (action) {
            ApprovalAction.ALLOW_ONCE, ApprovalAction.ALLOW_ALWAYS ->
                runCatching { taskApprovalService.approve(requestId) }
                    .onFailure { Timber.w(it, "ToolApprovalManager.approve failed for $requestId") }
            ApprovalAction.DENY_ONCE, ApprovalAction.DENY_ALWAYS ->
                runCatching { taskApprovalService.reject(requestId) }
                    .onFailure { Timber.w(it, "ToolApprovalManager.reject failed for $requestId") }
        }

        pendingDeferreds.remove(requestId)?.complete(action)
    }

    private suspend fun saveRule(toolName: String, arguments: Map<String, Any>, action: ApprovalAction) {
        val exactKey = ruleKey(toolName, arguments)
        val toolKey = ruleKeyForTool(toolName)
        val now = System.currentTimeMillis()
        ruleDao.insert(
            ToolApprovalRule(
                key = exactKey,
                action = action.name,
                toolName = toolName,
                argHash = arguments.hashCode().toString(),
                createdAt = now
            )
        )
        ruleDao.insert(
            ToolApprovalRule(
                key = toolKey,
                action = action.name,
                toolName = toolName,
                argHash = "*",
                createdAt = now
            )
        )
        Timber.i("Approval rule saved: tool=$toolName action=$action")
    }

    suspend fun getAllRules(): List<ApprovalRule> {
        return ruleDao.getAll().mapNotNull { entity ->
            val a = runCatching { ApprovalAction.valueOf(entity.action) }.getOrNull() ?: return@mapNotNull null
            if (a == ApprovalAction.ALLOW_ALWAYS || a == ApprovalAction.DENY_ALWAYS) {
                ApprovalRule(key = entity.key, action = a, createdAt = entity.createdAt)
            } else null
        }.sortedByDescending { it.createdAt }
    }

    suspend fun removeRule(key: String) {
        ruleDao.deleteByKey(key)
        Timber.i("Approval rule removed: key=$key")
    }

    suspend fun clearAllRules() {
        ruleDao.clearAll()
        Timber.i("All approval rules cleared")
    }

    /**
     * 工具被 Guardian 直接拦截 (risk 等级爆表) 时的审计记录。
     * 重构: 不再写 inbox_events, 只留 log。
     */
    suspend fun recordBlockedCall(
        toolName: String,
        arguments: Map<String, Any>,
        reason: String,
        agentId: String,
        sessionId: String?
    ) {
        Timber.i("Blocked call: tool=$toolName agent=$agentId reason=$reason")
    }
}
