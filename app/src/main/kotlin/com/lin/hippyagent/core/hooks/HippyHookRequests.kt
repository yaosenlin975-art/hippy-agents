package com.lin.hippyagent.core.hooks

import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolResult

data class EventMeta(
    val agentId: String,
    val turnId: String,
    val sessionId: String,
    val iteration: Int,
    val source: String,
    val tracePath: String
)

data class LLMHookRequest(
    val meta: EventMeta,
    val model: String,
    val messages: List<Map<String, Any>>,
    val tools: List<ToolDefinition>,
    val options: Map<String, Any>,
    val gracefulTerminal: Boolean = false
) {
    fun clone(): LLMHookRequest = copy(
        messages = messages.map { it.toMap() },
        tools = tools.map { it },
        options = options.toMap()
    )
}

data class LLMHookResponse(
    val meta: EventMeta,
    val model: String,
    val response: Map<String, Any>
) {
    fun clone(): LLMHookResponse = copy(response = response.toMap())
}

data class ToolCallHookRequest(
    val meta: EventMeta,
    val tool: String,
    val arguments: Map<String, Any>,
    val channel: String = "",
    val chatId: String = "",
    val hookResult: ToolResult? = null
) {
    fun clone(): ToolCallHookRequest = copy(
        arguments = arguments.toMap(),
        hookResult = hookResult
    )
}

data class ToolApprovalRequest(
    val meta: EventMeta,
    val tool: String,
    val arguments: Map<String, Any>
) {
    fun clone(): ToolApprovalRequest = copy(arguments = arguments.toMap())
}

data class ToolResultHookResponse(
    val meta: EventMeta,
    val tool: String,
    val arguments: Map<String, Any>,
    val result: ToolResult,
    val durationMs: Long
) {
    fun clone(): ToolResultHookResponse = copy(
        arguments = arguments.toMap(),
        result = result
    )
}
