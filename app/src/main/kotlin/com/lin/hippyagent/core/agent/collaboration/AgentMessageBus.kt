package com.lin.hippyagent.core.agent.collaboration

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

/**
 * Agent 唯一标识 — 值类型，防止与 String 混用。
 */
@JvmInline
value class AgentId(val value: String)

/**
 * 消息类型枚举。
 */
enum class MessageType {
    /** 普通文本消息 */
    TEXT,
    /** 工具调用结果 */
    TOOL_RESULT,
    /** 状态变更通知 */
    STATUS_UPDATE,
    /** 心跳 */
    HEARTBEAT
}

/**
 * Agent 间消息数据类。
 *
 * @property id        消息唯一 ID
 * @property from      发送方 Agent ID
 * @property to        接收方 Agent ID
 * @property type      消息类型
 * @property payload   消息内容（JSON 或纯文本）
 * @property timestamp 发送时间
 */
data class AgentMessage(
    val id: String = UUID.randomUUID().toString(),
    val from: AgentId,
    val to: AgentId,
    val type: MessageType,
    val payload: String,
    val timestamp: Instant = Instant.now()
)

/**
 * Agent 消息总线接口。
 *
 * 提供点对点发送和基于 agent / group 的订阅能力。
 * 实现类负责消息路由和持久化策略。
 */
interface AgentMessageBus {

    /**
     * 发送一条消息。
     *
     * @param from    发送方
     * @param to      接收方
     * @param message 消息内容
     */
    suspend fun send(from: AgentId, to: AgentId, message: AgentMessage)

    /**
     * 观察发给指定 Agent 的所有消息。
     *
     * @param agentId 目标 Agent
     * @return 消息 Flow
     */
    fun observe(agentId: AgentId): Flow<AgentMessage>

    /**
     * 观察指定 Group 内的所有消息。
     *
     * @param groupId Group 标识
     * @return 消息 Flow
     */
    fun observeGroup(groupId: String): Flow<AgentMessage>
}

