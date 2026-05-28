package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.chat.ChatTurnConverter
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolCall
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val taskStatuses = ConcurrentHashMap<String, TaskStatus>()

/**
 * 工具作用域管理器 - 提供可取消的 CoroutineScope
 */
object ToolScopeManager {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    fun getScope(): CoroutineScope {
        if (scope == null || job?.isActive != true) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
        return scope!!
    }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        job = getScope().launch(block = block)
        return job!!
    }

    fun shutdown() {
        scope?.cancel()
        scope = null
        job = null
    }
}

data class TaskStatus(
    val taskId: String,
    val agentId: String,
    val status: String,
    val result: String?,
    val createdAt: Long,
    val updatedAt: Long
)

class ChatWithAgentTool(
    private val agentFactory: AgentFactory,
    private val sessionStore: com.lin.hippyagent.core.agent.session.SessionStore? = null
) : Tool() {
    override val definition = ToolDefinition(
        name = "chat_with_agent",
        description = "与另一个智能体进行私聊讨论。你会收到对方的回复内容。讨论结果会自动摘要到群组中。注意：你需要根据自身上下文总结问题转述给对方，不要直接转发群聊原文。",
        parameters = mapOf(
            "agent_id" to ToolParameter(
                name = "agent_id",
                type = "string",
                description = "目标智能体 ID",
                required = true
            ),
            "message" to ToolParameter(
                name = "message",
                type = "string",
                description = "转述给对方的消息内容（需根据自身上下文总结，不要直接转发原文）",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        return execute(ToolContext(), arguments)
    }

    override suspend fun execute(ctx: ToolContext, arguments: Map<String, Any>): ToolResult {
        val targetAgentId = getRequiredArgument(arguments, "agent_id")
        val message = getRequiredArgument(arguments, "message")
        val callId = arguments["callId"] as? String ?: ""

        val callerAgentId = ctx.agentId
        val groupSessionId = ctx.sessionId.ifBlank { null }

        val agent = agentFactory.getAgent(targetAgentId)
            ?: return ToolResult(callId, false, error = "Agent not found: $targetAgentId")

        if (!agent.profileConfig.enabled) {
            return ToolResult(callId, false, error = "Agent $targetAgentId is disabled")
        }

        return try {
            val sourceSessionId = groupSessionId ?: callerAgentId
            val privateSessionId = "private_${callerAgentId}_${targetAgentId}_${System.currentTimeMillis()}_${sourceSessionId}"

            val result = agent.processMessage(
                sessionId = privateSessionId,
                channelId = "private",
                content = message
            )

            if (result.isFailure) {
                return ToolResult(callId, false, error = "Failed to get response: ${result.exceptionOrNull()?.message}")
            }

            val responseMessages = sessionStore?.getMessages(privateSessionId)?.getOrDefault(emptyList()) ?: emptyList()
            val lastAssistantMsg = responseMessages.lastOrNull { it.role == com.lin.hippyagent.core.agent.session.MessageRole.ASSISTANT }
            val responseContent = lastAssistantMsg?.content ?: "（对方未回复有效内容）"

            val (_, replyContent) = ChatTurnConverter.parseThinkingAndReply(responseContent)
            val cleanReply = replyContent.ifBlank { "" }

            if (groupSessionId != null && sessionStore != null && callerAgentId.isNotBlank()) {
                val targetAgentName = agent.profileConfig.name.ifBlank { targetAgentId }
                val callerAgentName = try {
                    agentFactory.getAgent(callerAgentId)?.profileConfig?.name?.ifBlank { callerAgentId } ?: callerAgentId
                } catch (_: Exception) { callerAgentId }
                val dialogText = "$callerAgentName: $message\n$targetAgentName: ${cleanReply.ifBlank { "（对方未回复有效内容）" }}"
                sessionStore.addMessage(
                    groupSessionId,
                    com.lin.hippyagent.core.agent.session.MessageRole.PRIVATE,
                    dialogText,
                    senderId = targetAgentId
                )
            }

            sessionStore?.hideSession(privateSessionId)

            ToolResult(callId, true, output = cleanReply.ifBlank { "（对方未回复有效内容）" })
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Error: ${e.message}")
        }
    }
}

class SubmitToAgentTool(
    private val agentFactory: AgentFactory
) : Tool() {
    override val definition = ToolDefinition(
        name = "submit_to_agent",
        description = "提交任务到另一个 Agent（异步）",
        parameters = mapOf(
            "agent_id" to ToolParameter(
                name = "agent_id",
                type = "string",
                description = "目标 Agent ID",
                required = true
            ),
            "task" to ToolParameter(
                name = "task",
                type = "string",
                description = "任务描述",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val agentId = getRequiredArgument(arguments, "agent_id")
        val task = getRequiredArgument(arguments, "task")
        val callId = arguments["callId"] as? String ?: ""

        val agent = agentFactory.getAgent(agentId)
            ?: return ToolResult(callId, false, error = "Agent not found: $agentId")

        if (!agent.profileConfig.enabled) {
            return ToolResult(callId, false, error = "Agent $agentId is disabled")
        }

        val taskId = com.lin.hippyagent.core.pool.FastId.next()
        val now = System.currentTimeMillis()
        taskStatuses[taskId] = TaskStatus(taskId, agentId, "submitted", null, now, now)

        ToolScopeManager.launch {
            runCatching {
                agent.processMessage(
                    sessionId = "task_$taskId",
                    channelId = "task",
                    content = task
                )
                taskStatuses[taskId] = TaskStatus(
                    taskId, agentId, "completed",
                    "Task executed", now, System.currentTimeMillis()
                )
            }.onFailure {
                taskStatuses[taskId] = TaskStatus(
                    taskId, agentId, "failed",
                    it.message, now, System.currentTimeMillis()
                )
            }
        }

        return ToolResult(callId, true, output = "Task submitted to agent: $agentId (taskId: $taskId)")
    }
}

class ListAgentsTool(
    private val agentFactory: AgentFactory
) : Tool() {
    override val definition = ToolDefinition(
        name = "list_agents",
        description = "列出所有已配置的 Agent",
        parameters = emptyMap()
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val agents = agentFactory.getAllAgents()
        val result = agents.joinToString("\n") { agent ->
            "${agent.profileConfig.agentId}: ${agent.profileConfig.name} (model: ${agent.profileConfig.modelName})"
        }
        return ToolResult(callId, true, result.ifEmpty { "No agents configured" })
    }
}

class CheckAgentTaskTool : Tool() {
    override val definition = ToolDefinition(
        name = "check_agent_task",
        description = "检查异步任务状态",
        parameters = mapOf(
            "task_id" to ToolParameter(
                name = "task_id",
                type = "string",
                description = "任务 ID",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val taskId = getRequiredArgument(arguments, "task_id")
        val callId = arguments["callId"] as? String ?: ""
        val task = taskStatuses[taskId]
            ?: return ToolResult(callId, false, error = "Task not found: $taskId")
        val resultInfo = task.result?.let { " | Result: $it" } ?: ""
        return ToolResult(callId, true, output = "Task ${task.taskId}: status=${task.status}${resultInfo} | Agent: ${task.agentId}")
    }
}

