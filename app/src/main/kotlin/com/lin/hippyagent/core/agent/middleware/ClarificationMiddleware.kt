package com.lin.hippyagent.core.agent.middleware

import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.tools.ClarificationRequest
import timber.log.Timber

class ClarificationMiddleware : AgentMiddleware {

    override val priority: Int = PRIORITY
    override val name: String = NAME

    var pendingClarification: ClarificationRequest? = null
        private set

    override fun afterModel(context: MiddlewareContext, response: ModelResponse): MiddlewareResult {
        val toolCalls = response.toolCalls ?: return MiddlewareResult.Continue
        val clarificationCall = toolCalls.firstOrNull { it.function.name == "ask" } ?: return MiddlewareResult.Continue

        val request = parseArguments(clarificationCall.function.arguments)
        if (request != null) {
            pendingClarification = request
            Timber.i("ClarificationMiddleware: intercepted clarification request type=${request.type}")
            return MiddlewareResult.Respond(request.question)
        }

        return MiddlewareResult.Continue
    }

    private fun parseArguments(json: String): ClarificationRequest? {
        return runCatching {
            val obj = org.json.JSONObject(json)
            val question = obj.optString("question", "") ?: return null
            val type = obj.optString("clarification_type", "missing_info")
            val ctx = obj.optString("context", "")
            val optionsStr = obj.optString("options", "")
            val options = if (optionsStr.isNotBlank()) {
                runCatching {
                    val arr = org.json.JSONArray(optionsStr)
                    (0 until arr.length()).map { arr.getString(it) }
                }.getOrDefault(emptyList())
            } else emptyList()
            ClarificationRequest(type = type, question = question, context = ctx, options = options)
        }.onFailure { e ->
            Timber.w(e, "ClarificationMiddleware: failed to parse arguments")
        }.getOrNull()
    }

    fun clearPending() {
        pendingClarification = null
    }

    companion object {
        const val PRIORITY = 90
        const val NAME = "clarification"
    }
}
