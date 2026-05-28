package com.lin.hippyagent.core.context

interface CompactionTrigger {
    fun shouldCompact(context: CompactionContext): Boolean
    val name: String
}

data class CompactionContext(
    val totalTokens: Int,
    val maxTokens: Int,
    val messageCount: Int,
    val recentTurnCount: Int
)

class TokenCountTrigger(
    private val threshold: Int = DEFAULT_THRESHOLD
) : CompactionTrigger {
    override val name: String = "token_count"

    override fun shouldCompact(context: CompactionContext): Boolean {
        return context.totalTokens > threshold
    }

    companion object {
        const val DEFAULT_THRESHOLD = 8000
    }
}

class MessageCountTrigger(
    private val threshold: Int = DEFAULT_THRESHOLD
) : CompactionTrigger {
    override val name: String = "message_count"

    override fun shouldCompact(context: CompactionContext): Boolean {
        return context.messageCount > threshold
    }

    companion object {
        const val DEFAULT_THRESHOLD = 40
    }
}

class ContextRatioTrigger(
    private val ratio: Float = DEFAULT_RATIO
) : CompactionTrigger {
    override val name: String = "context_ratio"

    override fun shouldCompact(context: CompactionContext): Boolean {
        if (context.maxTokens <= 0) return false
        return context.totalTokens.toFloat() / context.maxTokens > ratio
    }

    companion object {
        const val DEFAULT_RATIO = 0.75f
    }
}
