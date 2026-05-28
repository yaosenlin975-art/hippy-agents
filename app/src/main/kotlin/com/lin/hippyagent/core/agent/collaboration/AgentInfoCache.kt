package com.lin.hippyagent.core.agent.collaboration

import java.util.concurrent.ConcurrentHashMap

class AgentInfoCache {
    private val cache = ConcurrentHashMap<String, AgentCard>()

    fun get(agentId: String): AgentCard? = cache[agentId]

    fun put(agentId: String, card: AgentCard) {
        cache[agentId] = card
    }

    fun invalidate(agentId: String) {
        cache.remove(agentId)
    }

    fun invalidateAll() {
        cache.clear()
    }

    val size: Int get() = cache.size
}
