package com.lin.hippyagent.core.agent.middleware

import timber.log.Timber

class SummarizationMiddleware : AgentMiddleware {

    override val priority: Int = PRIORITY
    override val name: String = NAME

    override fun beforeModel(context: MiddlewareContext): MiddlewareResult {
        val messageCount = context.messages.size
        if (messageCount < MIN_MESSAGES_BEFORE_SUMMARIZATION) return MiddlewareResult.Continue

        Timber.d("SummarizationMiddleware: $messageCount messages in context, compaction may be needed")
        context.extra["needs_compaction_check"] = true

        return MiddlewareResult.Continue
    }

    companion object {
        const val PRIORITY = 30
        const val NAME = "summarization"
        const val MIN_MESSAGES_BEFORE_SUMMARIZATION = 20
    }
}
