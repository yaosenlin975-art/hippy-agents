package com.lin.hippyagent.core.inbox

import com.lin.hippyagent.core.agent.session.InboxDao
import com.lin.hippyagent.core.agent.session.InboxEvent
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
}
