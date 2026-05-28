package com.lin.hippyagent.core.agent.middleware

import com.lin.hippyagent.core.model.ModelMessage

interface AgentMiddleware {
    val priority: Int
    val name: String

    fun beforeModel(context: MiddlewareContext): MiddlewareResult = MiddlewareResult.Continue

    fun afterModel(context: MiddlewareContext, response: ModelResponse): MiddlewareResult = MiddlewareResult.Continue

    fun afterAgent(context: MiddlewareContext) {}
}

sealed class MiddlewareResult {
    data object Continue : MiddlewareResult()
    data class Modify(val messages: List<ModelMessage>) : MiddlewareResult()
    data class Respond(val message: String) : MiddlewareResult()
    data class AbortTurn(val reason: String) : MiddlewareResult()
    data object HardAbort : MiddlewareResult()
}

data class MiddlewareContext(
    val sessionId: String,
    val agentId: String,
    val messages: MutableList<ModelMessage>,
    val iteration: Int = 0,
    val extra: MutableMap<String, Any> = mutableMapOf()
)

data class ModelResponse(
    val content: String,
    val toolCalls: List<com.lin.hippyagent.core.model.ToolCallInfo>? = null,
    val finishReason: String? = null
)
