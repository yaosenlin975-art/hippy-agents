package com.lin.hippyagent.core.agent.middleware

import timber.log.Timber

class DeferredToolFilterMiddleware(
    private val deferredToolNames: Set<String>
) : AgentMiddleware {

    override val priority: Int = PRIORITY
    override val name: String = NAME

    override fun beforeModel(context: MiddlewareContext): MiddlewareResult {
        if (deferredToolNames.isEmpty()) return MiddlewareResult.Continue

        val toolCalls = context.messages.lastOrNull { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
            ?.toolCalls ?: return MiddlewareResult.Continue

        val hasDeferredCall = toolCalls.any { it.function.name in deferredToolNames }
        if (hasDeferredCall) {
            Timber.w("DeferredToolFilterMiddleware: blocked deferred tool call in message")
        }

        return MiddlewareResult.Continue
    }

    companion object {
        const val PRIORITY = 60
        const val NAME = "deferred_tool_filter"
    }
}
