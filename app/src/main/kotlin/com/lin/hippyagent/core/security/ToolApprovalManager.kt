package com.lin.hippyagent.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lin.hippyagent.core.inbox.ApprovalService
import com.lin.hippyagent.core.inbox.InboxStore
import com.lin.hippyagent.core.tools.ToolGuardian
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

private val Context.toolApprovalDataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_approval_rules")

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

class ToolApprovalManager(
    private val context: Context,
    private val approvalService: ApprovalService,
    private val inboxStore: InboxStore
) {
    private val dataStore = context.toolApprovalDataStore

    private val _pendingApprovals = MutableStateFlow<List<PendingToolApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingToolApproval>> = _pendingApprovals.asStateFlow()
    private val pendingMutex = Mutex()

    private val pendingDeferreds = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ApprovalAction>>()

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
        val toolKey = ruleKeyForTool(toolName)

        val prefs = dataStore.data.first()
        val exactAction = prefs[stringPreferencesKey(exactKey)]
        if (exactAction != null) {
            val action = ApprovalAction.valueOf(exactAction)
            if (action == ApprovalAction.ALLOW_ALWAYS) return ApprovalAction.ALLOW_ALWAYS
            if (action == ApprovalAction.DENY_ALWAYS) return ApprovalAction.DENY_ALWAYS
        }

        val toolAction = prefs[stringPreferencesKey(toolKey)]
        if (toolAction != null) {
            val action = ApprovalAction.valueOf(toolAction)
            if (action == ApprovalAction.ALLOW_ALWAYS) return ApprovalAction.ALLOW_ALWAYS
            if (action == ApprovalAction.DENY_ALWAYS) return ApprovalAction.DENY_ALWAYS
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

        val requestId = java.util.UUID.randomUUID().toString()
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
        pendingDeferreds[requestId] = kotlinx.coroutines.CompletableDeferred()

        val severity = when (riskLevel) {
            ToolGuardian.RiskLevel.CRITICAL -> "critical"
            ToolGuardian.RiskLevel.HIGH -> "high"
            ToolGuardian.RiskLevel.MEDIUM -> "medium"
            else -> "low"
        }
        val findingsSummary = findings.take(3).joinToString(", ") { it.title }

        approvalService.createPendingApproval(
            requestId = requestId,
            sessionId = sessionId ?: "",
            agentId = agentId,
            toolName = toolName,
            severity = severity,
            findingsCount = findings.size,
            findingsSummary = findingsSummary,
            toolParams = arguments.toString()
        )

        inboxStore.appendEvent(
            agentId = agentId,
            sourceType = "tool_approval",
            sourceId = requestId,
            eventType = "approval_requested",
            status = "pending",
            severity = severity,
            title = "工具审批请求: $toolName",
            body = findingsSummary,
            payload = arguments.toString()
        )

        return try {
            kotlinx.coroutines.withTimeoutOrNull(300_000L) {
                pendingDeferreds[requestId]?.await()
            } ?: ApprovalAction.DENY_ONCE
        } finally {
            pendingMutex.withLock {
                _pendingApprovals.value = _pendingApprovals.value.filter { it.requestId != requestId }
            }
            pendingDeferreds.remove(requestId)
        }
    }

    suspend fun resolveApproval(requestId: String, action: ApprovalAction) {
        val pending = _pendingApprovals.value.find { it.requestId == requestId } ?: run {
            pendingDeferreds.remove(requestId)?.complete(action)
            return
        }

        when (action) {
            ApprovalAction.ALLOW_ONCE -> {}
            ApprovalAction.ALLOW_ALWAYS -> saveRule(pending.toolName, pending.arguments, ApprovalAction.ALLOW_ALWAYS)
            ApprovalAction.DENY_ONCE -> {}
            ApprovalAction.DENY_ALWAYS -> saveRule(pending.toolName, pending.arguments, ApprovalAction.DENY_ALWAYS)
        }

        val decisionStr = when (action) {
            ApprovalAction.ALLOW_ONCE, ApprovalAction.ALLOW_ALWAYS -> "approved"
            ApprovalAction.DENY_ONCE, ApprovalAction.DENY_ALWAYS -> "denied"
        }

        approvalService.resolveApproval(requestId, decisionStr)

        inboxStore.appendEvent(
            agentId = pending.agentId,
            sourceType = "tool_approval",
            sourceId = requestId,
            eventType = "approval_resolved",
            status = decisionStr,
            severity = when (pending.riskLevel) {
                ToolGuardian.RiskLevel.CRITICAL -> "critical"
                ToolGuardian.RiskLevel.HIGH -> "high"
                else -> "medium"
            },
            title = "工具审批${if (decisionStr == "approved") "已通过" else "已拒绝"}: ${pending.toolName}",
            body = "操作: ${action.name}",
            payload = pending.arguments.toString()
        )

        pendingDeferreds.remove(requestId)?.complete(action)
    }

    private suspend fun saveRule(toolName: String, arguments: Map<String, Any>, action: ApprovalAction) {
        val exactKey = ruleKey(toolName, arguments)
        val toolKey = ruleKeyForTool(toolName)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(exactKey)] = action.name
            prefs[stringPreferencesKey(toolKey)] = action.name
        }
        Timber.i("Approval rule saved: tool=$toolName action=$action")
    }

    suspend fun getAllRules(): List<ApprovalRule> {
        val prefs = dataStore.data.first()
        val rules = mutableListOf<ApprovalRule>()
        for ((key, value) in prefs.asMap()) {
            if (value is String) {
                try {
                    val action = ApprovalAction.valueOf(value)
                    if (action == ApprovalAction.ALLOW_ALWAYS || action == ApprovalAction.DENY_ALWAYS) {
                        rules.add(ApprovalRule(key = key.name, action = action))
                    }
                } catch (_: Exception) {}
            }
        }
        return rules.sortedByDescending { it.createdAt }
    }

    suspend fun removeRule(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
        Timber.i("Approval rule removed: key=$key")
    }

    suspend fun clearAllRules() {
        dataStore.edit { it.clear() }
        Timber.i("All approval rules cleared")
    }

    suspend fun recordBlockedCall(
        toolName: String,
        arguments: Map<String, Any>,
        reason: String,
        agentId: String,
        sessionId: String?
    ) {
        inboxStore.appendEvent(
            agentId = agentId,
            sourceType = "tool_approval",
            sourceId = "blocked_${System.currentTimeMillis()}",
            eventType = "blocked_by_guardian",
            status = "denied",
            severity = "high",
            title = "工具被安全规则阻止: $toolName",
            body = reason,
            payload = arguments.toString()
        )
        Timber.i("Blocked call recorded in inbox: tool=$toolName, reason=$reason")
    }
}
