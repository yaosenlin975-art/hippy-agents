package com.lin.hippyagent.core.inbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

enum class ApprovalDecision {
    APPROVED,
    DENIED
}

class ApprovalService(
    private val inboxStore: InboxStore
) {
    private val pendingDeferreds = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()

    suspend fun createPendingApproval(
        requestId: String,
        sessionId: String,
        agentId: String,
        toolName: String,
        severity: String = "medium",
        findingsCount: Int = 0,
        findingsSummary: String = "",
        toolParams: String = "{}",
        timeoutSeconds: Float = 300f
    ) {
        inboxStore.addPendingApproval(
            requestId = requestId,
            sessionId = sessionId,
            agentId = agentId,
            toolName = toolName,
            severity = severity,
            findingsCount = findingsCount,
            findingsSummary = findingsSummary,
            toolParams = toolParams,
            timeoutSeconds = timeoutSeconds
        )
        pendingDeferreds[requestId] = CompletableDeferred()
    }

    suspend fun waitForApproval(requestId: String): ApprovalDecision {
        val deferred = pendingDeferreds[requestId]
            ?: return ApprovalDecision.DENIED
        val approval = withContext(Dispatchers.IO) {
            inboxStore.getPendingApprovals().find { it.requestId == requestId }
        }
        val timeoutMs = (approval?.timeoutSeconds?.toLong() ?: 300L) * 1000L
        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        if (result == null) {
            resolveApproval(requestId, "timeout")
            return ApprovalDecision.DENIED
        }
        return result
    }

    suspend fun resolveApproval(requestId: String, decision: String) {
        val approvalDecision = when (decision) {
            "approved" -> ApprovalDecision.APPROVED
            "denied" -> ApprovalDecision.DENIED
            "timeout" -> ApprovalDecision.DENIED
            else -> ApprovalDecision.DENIED
        }
        inboxStore.resolveApproval(requestId, decision)
        pendingDeferreds.remove(requestId)?.complete(approvalDecision)
    }

    suspend fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val pending = withContext(Dispatchers.IO) {
            inboxStore.getPendingApprovals()
        }
        for (approval in pending) {
            val timeoutMs = (approval.timeoutSeconds.toLong()) * 1000L
            if (now - approval.createdAt > timeoutMs) {
                resolveApproval(approval.requestId, "timeout")
            }
        }
    }
}
