package com.lin.hippyagent.core.agent.middleware

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

class TitleMiddleware(
    private val llmClient: ModelClient,
    private val modelName: String,
    private val onTitleGenerated: (sessionId: String, title: String) -> Unit
) : AgentMiddleware {

    override val priority: Int = PRIORITY
    override val name: String = NAME

    private val generatedSessions = mutableSetOf<String>()

    override fun afterModel(context: MiddlewareContext, response: ModelResponse): MiddlewareResult {
        if (context.sessionId in generatedSessions) return MiddlewareResult.Continue
        if (context.iteration > 0) return MiddlewareResult.Continue

        generatedSessions.add(context.sessionId)

        val firstUserMsg = context.messages.firstOrNull { it.role == "user" }?.content ?: return MiddlewareResult.Continue

        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val request = ModelCallRequest(
                    model = modelName,
                    messages = listOf(
                        ModelMessage(role = "system", content = "为以下对话生成一个简短的标题（不超过30个字符），只输出标题文本，不要加引号或其他格式。"),
                        ModelMessage(role = "user", content = firstUserMsg.take(200))
                    ),
                    temperature = 0.3f,
                    maxTokens = 50
                )
                val resp = llmClient.chatCompletion(request)
                val title = resp.choices.firstOrNull()?.message?.content?.trim()?.take(30)
                if (!title.isNullOrBlank()) {
                    onTitleGenerated(context.sessionId, title)
                    Timber.i("TitleMiddleware: generated title '$title' for session ${context.sessionId}")
                }
            }.onFailure { e ->
                val fallback = firstUserMsg.take(30)
                onTitleGenerated(context.sessionId, fallback)
                Timber.w(e, "TitleMiddleware: LLM title generation failed, using fallback")
            }
        }

        return MiddlewareResult.Continue
    }

    companion object {
        const val PRIORITY = 40
        const val NAME = "title"
    }
}
