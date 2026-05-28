package com.lin.hippyagent.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局共享的"当前选中智能体"状态
 * ConversationListViewModel 和 AgentListViewModel 共享此状态
 */
object AgentSelectionHolder {
    private val _currentAgentId = MutableStateFlow<String?>(null)
    val currentAgentId: StateFlow<String?> = _currentAgentId.asStateFlow()

    fun setCurrentAgent(agentId: String?) {
        _currentAgentId.value = agentId
    }
}

