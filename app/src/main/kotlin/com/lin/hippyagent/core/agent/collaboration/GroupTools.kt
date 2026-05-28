package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

/**
 * 在群组中 @ 提及其他 Agent 的工具
 */
class MentionInGroupTool(
    private val groupRegistry: GroupRegistry,
    private val agentGroupManager: AgentGroupManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "mention_in_group",
        description = "在群组中 @ 提及其他 Agent，触发其响应",
        parameters = mapOf(
            "group_id" to ToolParameter(
                name = "group_id",
                type = "string",
                description = "群组 ID",
                required = true
            ),
            "agent_ids" to ToolParameter(
                name = "agent_ids",
                type = "string",
                description = "要 @ 的 Agent ID 列表，逗号分隔",
                required = true
            ),
            "message" to ToolParameter(
                name = "message",
                type = "string",
                description = "消息内容",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val groupId = getRequiredArgument(arguments, "group_id")
        val agentIdsStr = getRequiredArgument(arguments, "agent_ids")
        val message = getRequiredArgument(arguments, "message")
        val callId = arguments["callId"] as? String ?: ""

        val group = groupRegistry.getGroup(groupId)
            ?: return ToolResult(callId, false, error = "Group not found: $groupId")

        val mentionedAgentIds = agentIdsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // 验证所有提到的 Agent 都在群组中
        val invalidAgents = mentionedAgentIds.filter { it !in group.agentIds }
        if (invalidAgents.isNotEmpty()) {
            return ToolResult(callId, false, error = "Agents not in group: ${invalidAgents.joinToString()}")
        }

        return try {
            val agentGroup = agentGroupManager.getOrCreateAgentGroup(groupId)
                ?: return ToolResult(callId, false, error = "Failed to create AgentGroup for: $groupId")
            val result = agentGroup.processMessage(
                senderId = "user",
                content = message,
                mentionedAgentIds = mentionedAgentIds
            )

            result.fold(
                onSuccess = { ToolResult(callId, true, output = it) },
                onFailure = { ToolResult(callId, false, error = "Failed: ${it.message}") }
            )
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Error: ${e.message}")
        }
    }
}

/**
 * 获取群组消息历史的工具
 */
class GetGroupHistoryTool(
    private val groupRegistry: GroupRegistry,
    private val agentGroupManager: AgentGroupManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "get_group_history",
        description = "获取群组消息历史",
        parameters = mapOf(
            "group_id" to ToolParameter(
                name = "group_id",
                type = "string",
                description = "群组 ID",
                required = true
            ),
            "limit" to ToolParameter(
                name = "limit",
                type = "integer",
                description = "返回消息数量限制（默认 50）",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val groupId = getRequiredArgument(arguments, "group_id")
        val limit = (arguments["limit"] as? Number)?.toInt() ?: 50
        val callId = arguments["callId"] as? String ?: ""

        val group = groupRegistry.getGroup(groupId)
            ?: return ToolResult(callId, false, error = "Group not found: $groupId")

        return try {
            val agentGroup = agentGroupManager.getOrCreateAgentGroup(groupId)
                ?: return ToolResult(callId, false, error = "Failed to create AgentGroup for: $groupId")
            val history = agentGroup.getHistory(limit)

            if (history.isEmpty()) {
                return ToolResult(callId, true, output = "No messages in group history")
            }

            val result = history.joinToString("\n") { msg ->
                "[${msg.agentId}] (${msg.timestamp}): ${msg.content}"
            }

            ToolResult(callId, true, result)
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Error: ${e.message}")
        }
    }
}

