package com.lin.hippyagent.core.agent

import java.util.concurrent.ConcurrentHashMap

data class AgentSession(
    val sessionId: String,
    val agentId: String,
    val channelId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActivityAt: Long = System.currentTimeMillis()
)

class AgentSessionManager {
    private val sessions = ConcurrentHashMap<String, AgentSession>()

    fun createSession(sessionId: String, agentId: String, channelId: String): AgentSession {
        val session = AgentSession(sessionId, agentId, channelId)
        sessions[sessionId] = session
        return session
    }

    fun getSession(sessionId: String): AgentSession? = sessions[sessionId]

    fun updateActivity(sessionId: String) {
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(lastActivityAt = System.currentTimeMillis())
        }
    }

    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun getAgentSessions(agentId: String): List<AgentSession> {
        return sessions.values.filter { it.agentId == agentId }
    }
}

