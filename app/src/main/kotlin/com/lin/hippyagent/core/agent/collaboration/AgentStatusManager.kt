package com.lin.hippyagent.core.agent.collaboration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class AgentStatusManager {
    private val _statuses = ConcurrentHashMap<String, AgentWorkStatus>()
    private val _statusesFlow = MutableStateFlow<Map<String, AgentWorkStatus>>(emptyMap())

    val allStatuses: StateFlow<Map<String, AgentWorkStatus>> = _statusesFlow.asStateFlow()

    fun registerAgent(agentId: String, agentName: String) {
        if (_statuses.containsKey(agentId)) {
            Timber.w("Agent already registered: $agentId")
            return
        }
        _statuses[agentId] = AgentWorkStatus(
            agentId = agentId,
            agentName = agentName
        )
        emitUpdate()
        Timber.d("Agent registered: $agentId ($agentName)")
    }

    fun unregisterAgent(agentId: String) {
        _statuses.remove(agentId)
        emitUpdate()
        Timber.d("Agent unregistered: $agentId")
    }

    fun updateStatus(agentId: String, status: AgentWorkStatus) {
        _statuses[agentId] = status.copy(lastActiveTime = System.currentTimeMillis())
        emitUpdate()
    }

    fun setWorking(agentId: String, currentTask: String? = null) {
        val current = _statuses[agentId] ?: return
        _statuses[agentId] = current.copy(
            state = AgentWorkState.WORKING,
            currentTask = currentTask,
            lastActiveTime = System.currentTimeMillis()
        )
        emitUpdate()
        Timber.d("Agent $agentId set to WORKING: $currentTask")
    }

    fun setIdle(agentId: String) {
        val current = _statuses[agentId] ?: return
        _statuses[agentId] = current.copy(
            state = AgentWorkState.IDLE,
            currentTask = null,
            lastActiveTime = System.currentTimeMillis()
        )
        emitUpdate()
        Timber.d("Agent $agentId set to IDLE")
    }

    fun setPaused(agentId: String) {
        val current = _statuses[agentId] ?: return
        _statuses[agentId] = current.copy(
            state = AgentWorkState.PAUSED,
            lastActiveTime = System.currentTimeMillis()
        )
        emitUpdate()
        Timber.d("Agent $agentId set to PAUSED")
    }

    fun updatePendingMessages(agentId: String, count: Int) {
        val current = _statuses[agentId] ?: return
        _statuses[agentId] = current.copy(pendingMessages = count)
        emitUpdate()
    }

    fun incrementPendingMessages(agentId: String) {
        val current = _statuses[agentId] ?: return
        _statuses[agentId] = current.copy(pendingMessages = current.pendingMessages + 1)
        emitUpdate()
    }

    fun decrementPendingMessages(agentId: String) {
        val current = _statuses[agentId] ?: return
        _statuses[agentId] = current.copy(
            pendingMessages = maxOf(0, current.pendingMessages - 1)
        )
        emitUpdate()
    }

    fun interrupt(agentId: String): Boolean {
        val current = _statuses[agentId]
        if (current == null) {
            Timber.w("Cannot interrupt: agent not found $agentId")
            return false
        }

        if (current.state != AgentWorkState.WORKING) {
            Timber.w("Cannot interrupt: agent $agentId is not WORKING (${current.state})")
            return false
        }

        _statuses[agentId] = current.copy(
            state = AgentWorkState.IDLE,
            currentTask = null,
            lastActiveTime = System.currentTimeMillis()
        )
        emitUpdate()
        Timber.i("Agent $agentId interrupted successfully")
        return true
    }

    fun getStatus(agentId: String): AgentWorkStatus? = _statuses[agentId]

    fun getWorkingAgents(): List<AgentWorkStatus> {
        return _statuses.values.filter { it.state == AgentWorkState.WORKING }
    }

    fun getIdleAgents(): List<AgentWorkStatus> {
        return _statuses.values.filter { it.state == AgentWorkState.IDLE }
    }

    fun getAllAgents(): List<AgentWorkStatus> {
        return _statuses.values.toList()
    }

    fun getWorkingCount(): Int {
        return _statuses.values.count { it.state == AgentWorkState.WORKING }
    }

    fun getSnapshot(): List<AgentWorkStatusSnapshot> {
        return _statuses.values.map { status ->
            AgentWorkStatusSnapshot(
                agentId = status.agentId,
                agentName = status.agentName,
                state = status.state,
                currentTask = status.currentTask,
                pendingMessages = status.pendingMessages
            )
        }
    }

    private fun emitUpdate() {
        _statusesFlow.tryEmit(_statuses.toMap())
    }
}