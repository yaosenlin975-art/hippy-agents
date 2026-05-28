package com.lin.hippyagent.core.agent.collaboration

enum class AgentWorkState {
    IDLE,
    WORKING,
    PAUSED
}

data class AgentWorkStatus(
    val agentId: String,
    val agentName: String,
    val state: AgentWorkState = AgentWorkState.IDLE,
    val currentTask: String? = null,
    val pendingMessages: Int = 0,
    val lastActiveTime: Long = System.currentTimeMillis()
) {
    val isWorking: Boolean get() = state == AgentWorkState.WORKING
    val isIdle: Boolean get() = state == AgentWorkState.IDLE
    val isPaused: Boolean get() = state == AgentWorkState.PAUSED
}

data class AgentWorkStatusSnapshot(
    val agentId: String,
    val agentName: String,
    val state: AgentWorkState,
    val currentTask: String?,
    val pendingMessages: Int
)