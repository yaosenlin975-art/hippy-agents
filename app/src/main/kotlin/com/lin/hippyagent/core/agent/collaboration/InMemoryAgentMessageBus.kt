package com.lin.hippyagent.core.agent.collaboration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于内存的 Agent 消息总线实现。
 *
 * 使用 [MutableSharedFlow] 实现发布-订阅模式:
 *  - 每个 Agent 拥有一个独立的 SharedFlow channel
 *  - 每个 Group 拥有一个独立的 SharedFlow channel
 *  - send 时同时向接收方的 agent channel 和所属 group channel 推送
 *
 * 注意: 这是进程内实现，消息不跨进程持久化。
 * 适用于单设备多 Agent 协作场景。
 */
class InMemoryAgentMessageBus : AgentMessageBus {

    /** agentId -> 该 agent 的消息流 */
    private val agentChannels = ConcurrentHashMap<String, MutableSharedFlow<AgentMessage>>()

    /** groupId -> 该 group 的消息流 */
    private val groupChannels = ConcurrentHashMap<String, MutableSharedFlow<AgentMessage>>()

    /** agentId -> 该 agent 所属的 groupIds */
    private val agentGroups = ConcurrentHashMap<String, MutableSet<String>>()

    // ── Public API ──────────────────────────────────────────────

    override suspend fun send(from: AgentId, to: AgentId, message: AgentMessage) {
        // 推送到接收方的 agent channel
        getOrCreateAgentChannel(to.value).emit(message)
        Timber.d("Message sent: ${from.value} -> ${to.value}, type=${message.type}")

        // 推送到接收方所属的所有 group channel
        val groups = agentGroups[to.value]
        if (groups != null) {
            for (groupId in groups) {
                getOrCreateGroupChannel(groupId).emit(message)
            }
        }
    }

    /**
     * 向 group 内所有成员广播消息。
     */
    suspend fun broadcastToGroup(groupId: String, message: AgentMessage) {
        val channel = getOrCreateGroupChannel(groupId)
        channel.emit(message)
        Timber.d("Broadcast to group $groupId: from=${message.from.value}, type=${message.type}")
    }

    override fun observe(agentId: AgentId): Flow<AgentMessage> {
        return getOrCreateAgentChannel(agentId.value).asSharedFlow()
    }

    override fun observeGroup(groupId: String): Flow<AgentMessage> {
        return getOrCreateGroupChannel(groupId).asSharedFlow()
    }

    /**
     * 将 agent 注册到 group，后续该 group 的消息会推送到 agent channel。
     */
    fun registerAgentToGroup(agentId: String, groupId: String) {
        agentGroups.getOrPut(agentId) { ConcurrentHashMap.newKeySet() }.add(groupId)
        Timber.d("Registered agent $agentId to group $groupId")
    }

    /**
     * 将 agent 从 group 移除。
     */
    fun unregisterAgentFromGroup(agentId: String, groupId: String) {
        agentGroups[agentId]?.remove(groupId)
        Timber.d("Unregistered agent $agentId from group $groupId")
    }

    /**
     * 获取 agent 所属的 group 列表。
     */
    fun getAgentGroups(agentId: String): Set<String> {
        return agentGroups[agentId]?.toSet() ?: emptySet()
    }

    // ── Internal ────────────────────────────────────────────────

    private fun getOrCreateAgentChannel(agentId: String): MutableSharedFlow<AgentMessage> {
        return agentChannels.getOrPut(agentId) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
        }
    }

    private fun getOrCreateGroupChannel(groupId: String): MutableSharedFlow<AgentMessage> {
        return groupChannels.getOrPut(groupId) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
        }
    }
}

