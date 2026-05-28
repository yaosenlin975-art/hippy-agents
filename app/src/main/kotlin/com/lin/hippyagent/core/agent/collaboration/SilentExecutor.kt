package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.agent.Agent
import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.agent.session.MessageRole
import timber.log.Timber

/**
 * 静默执行器，不暴露工具调用细节
 * 用于群组聊天中 Agent 执行工具时隐藏中间过程
 */
class SilentExecutor(
    private val agentFactory: AgentFactory,
    private val sessionStore: SessionStore
) {
    /**
     * 静默执行 Agent 消息，返回最终回复
     * 工具调用过程不会暴露给用户
     */
    suspend fun executeSilently(
        agentId: String,
        sessionId: String,
        message: String,
        channel: String = "group"
    ): Result<String> {
        return try {
            val agent = agentFactory.getAgent(agentId)
                ?: return Result.failure(IllegalStateException("Agent not found: $agentId"))

            // 执行消息处理
            val result = agent.processMessage(sessionId, channel, message)

            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: RuntimeException("Silent execution failed"))
            }

            // 获取最后一条助手消息作为回复
            val messages = sessionStore.getMessages(sessionId).getOrDefault(emptyList())
            val lastAssistantMsg = messages.lastOrNull {
                it.role == MessageRole.ASSISTANT
            }

            val response = lastAssistantMsg?.content ?: ""
            Result.success(response)
        } catch (e: Exception) {
            Timber.e(e, "Silent execution failed for agent: $agentId")
            Result.failure(e)
        }
    }

    /**
     * 批量静默执行多个 Agent
     */
    suspend fun executeBatchSilently(
        agentIds: List<String>,
        sessionId: String,
        message: String,
        channel: String = "group"
    ): Map<String, Result<String>> {
        val results = mutableMapOf<String, Result<String>>()

        for (agentId in agentIds) {
            results[agentId] = executeSilently(agentId, sessionId, message, channel)
        }

        return results
    }
}

