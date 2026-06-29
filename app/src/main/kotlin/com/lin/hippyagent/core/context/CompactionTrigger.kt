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

/**
 * 对话轮次保留触发器.
 *
 * 当 recentTurnCount (用户对话轮次) 达到硬保留阈值,
 * 且 messageCount (内部迭代数) 远超对话轮次 (说明工具日志膨胀) 时,
 * 触发"仅压缩工具日志, 不动用户消息"的精细压缩.
 *
 * 复用闲置的 CompactionContext.recentTurnCount 字段.
 */
class DialogTurnPreservationTrigger(
    private val threshold: Int = ConversationMemoryPolicy.HARD_KEEP_RECENT_DIALOG_TURNS
) : CompactionTrigger {
    override val name: String = "dialog_turn_preservation"

    override fun shouldCompact(context: CompactionContext): Boolean {
        // 工具日志膨胀判定: messageCount > recentTurnCount * 4
        // 即内部工具迭代数远超用户对话轮次, 此时用户消息不应被动
        return context.recentTurnCount >= threshold &&
               context.messageCount > context.recentTurnCount * 4
    }
}
