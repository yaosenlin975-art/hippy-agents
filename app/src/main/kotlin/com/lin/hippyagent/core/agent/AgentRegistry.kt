package com.lin.hippyagent.core.agent

import com.lin.hippyagent.core.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

class AgentRegistry {
    private val _agents = MutableStateFlow<Map<String, AgentProfile>>(emptyMap())
    val agents: StateFlow<Map<String, AgentProfile>> = _agents.asStateFlow()

    fun register(profile: AgentProfile) {
        _agents.update { it + (profile.agentId to profile) }
        Timber.i("Agent registered: ${profile.agentId}")
    }

    fun unregister(agentId: String) {
        _agents.update { it - agentId }
        Timber.i("Agent unregistered: $agentId")
    }

    fun getAgent(agentId: String): AgentProfile? = _agents.value[agentId]

    fun getAllAgents(): List<AgentProfile> = _agents.value.values.toList()

    fun getDefaultAgent(): AgentProfile? = _agents.value.values.firstOrNull { it.isDefault }
}

