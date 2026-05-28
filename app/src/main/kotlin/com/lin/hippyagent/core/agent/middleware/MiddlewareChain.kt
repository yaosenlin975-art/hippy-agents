package com.lin.hippyagent.core.agent.middleware

import com.lin.hippyagent.core.model.ModelMessage
import timber.log.Timber

class MiddlewareChain(
    middlewares: List<AgentMiddleware>
) {
    private val sortedMiddlewares = middlewares.sortedBy { it.priority }

    fun runBeforeModel(context: MiddlewareContext): MiddlewareResult {
        for (mw in sortedMiddlewares) {
            when (val result = mw.beforeModel(context)) {
                is MiddlewareResult.Continue -> {}
                is MiddlewareResult.Modify -> {
                    context.messages.clear()
                    context.messages.addAll(result.messages)
                }
                is MiddlewareResult.Respond -> return result
                is MiddlewareResult.AbortTurn -> {
                    Timber.w("Middleware ${mw.name} aborted turn: ${result.reason}")
                    return result
                }
                is MiddlewareResult.HardAbort -> {
                    Timber.w("Middleware ${mw.name} hard-aborted")
                    return result
                }
            }
        }
        return MiddlewareResult.Continue
    }

    fun runAfterModel(context: MiddlewareContext, response: ModelResponse): MiddlewareResult {
        for (mw in sortedMiddlewares) {
            when (val result = mw.afterModel(context, response)) {
                is MiddlewareResult.Continue -> {}
                is MiddlewareResult.Respond -> return result
                is MiddlewareResult.AbortTurn -> return result
                is MiddlewareResult.HardAbort -> return result
                else -> {}
            }
        }
        return MiddlewareResult.Continue
    }

    fun runAfterAgent(context: MiddlewareContext) {
        for (mw in sortedMiddlewares) {
            runCatching {
                mw.afterAgent(context)
            }.onFailure { e ->
                Timber.e(e, "Middleware ${mw.name} afterAgent failed")
            }
        }
    }

    fun getMiddleware(name: String): AgentMiddleware? {
        return sortedMiddlewares.firstOrNull { it.name == name }
    }

    fun getAllMiddlewares(): List<AgentMiddleware> = sortedMiddlewares.toList()
}
