package com.lin.hippyagent.core.inbox

import com.lin.hippyagent.core.agent.session.InboxDao
import com.lin.hippyagent.core.agent.session.InboxEvent
import com.lin.hippyagent.core.agent.session.PendingApproval
import java.util.UUID

class InboxStore(
    private val inboxDao: InboxDao
) {
    suspend fun appendEvent(
        agentId: String,
        sourceType: String,
        sourceId: String,
        eventType: String,
        status: String,
        severity: String,
        title: String,
        body: String,
        payload: String
    ) {
        val event = InboxEvent(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            sourceType = sourceType,
            sourceId = sourceId,
            eventType = eventType,
            status = status,
            severity = severity,
            title = title,
            body = body,
            payload = payload,
            read = false,
            createdAt = System.currentTimeMillis()
        )
        inboxDao.insertEvent(event)
    }

    suspend fun listEvents(limit: Int = 50, offset: Int = 0): List<InboxEvent> {
        return inboxDao.getEvents(limit, offset)
    }

    suspend fun getUnreadCount(): Int {
        return inboxDao.getUnreadCount()
    }

    suspend fun markRead(id: String) {
        inboxDao.markRead(id)
    }

    suspend fun markAllRead() {
        inboxDao.markAllRead()
    }

    suspend fun deleteEvent(id: String) {
        inboxDao.deleteEvent(id)
    }

    suspend fun addPendingApproval(
        requestId: String,
        sessionId: String,
        agentId: String,
        toolName: String,
        severity: String,
        findingsCount: Int,
        findingsSummary: String,
        toolParams: String,
        timeoutSeconds: Float
    ) {
        val approval = PendingApproval(
            requestId = requestId,
            sessionId = sessionId,
            agentId = agentId,
            toolName = toolName,
            severity = severity,
            findingsCount = findingsCount,
            findingsSummary = findingsSummary,
            toolParams = toolParams,
            timeoutSeconds = timeoutSeconds,
            status = "pending",
            createdAt = System.currentTimeMillis()
        )
        inboxDao.insertApproval(approval)
    }

    suspend fun getPendingApprovals(): List<PendingApproval> {
        return inboxDao.getPendingApprovals()
    }

    suspend fun getAllApprovals(): List<PendingApproval> {
        return inboxDao.getAllApprovals()
    }

    suspend fun resolveApproval(requestId: String, status: String) {
        inboxDao.updateApprovalStatus(requestId, status)
    }

    suspend fun getPendingApprovalCount(): Int {
        return inboxDao.getPendingApprovalCount()
    }
}
