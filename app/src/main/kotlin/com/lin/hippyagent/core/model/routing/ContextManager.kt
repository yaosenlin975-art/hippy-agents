package com.lin.hippyagent.core.model.routing

import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.serialization.Serializable
import timber.log.Timber
import kotlin.math.roundToInt

@Serializable
enum class ContextLevel {
    NORMAL,
    FOLD_NORMAL,
    FOLD_AGGRESSIVE,
    FORCE_SUMMARY,
    MECHANICAL_TRUNCATE
}

data class ContextCheckResult(
    val level: ContextLevel,
    val ratio: Float,
    val messagesToCompress: List<ModelMessage>,
    val messagesToKeep: List<ModelMessage>
)

@Serializable
data class FoldResult(
    val summary: String,
    val foldedCount: Int,
    val keptCount: Int
)

object ContextManager {

    private const val ESTIMATE_DIVISOR = 4.0f

    fun checkContextLevel(
        messages: List<ModelMessage>,
        systemPrompt: String,
        modelContextWindow: Int
    ): ContextCheckResult {
        val systemTokens = estimateTokens(systemPrompt)
        val messagesTokens = estimateMessages(messages)
        val totalTokens = systemTokens + messagesTokens
        val ratio = totalTokens.toFloat() / modelContextWindow

        val level = when {
            ratio > 0.95f -> ContextLevel.MECHANICAL_TRUNCATE
            ratio > 0.80f -> ContextLevel.FORCE_SUMMARY
            ratio > 0.70f -> ContextLevel.FOLD_AGGRESSIVE
            ratio > 0.50f -> ContextLevel.FOLD_NORMAL
            else -> ContextLevel.NORMAL
        }

        val tailBudget = when (level) {
            ContextLevel.FOLD_NORMAL -> (0.20f * modelContextWindow).toInt()
            ContextLevel.FOLD_AGGRESSIVE -> (0.10f * modelContextWindow).toInt()
            else -> 0
        }

        val (toCompress, toKeep) = if (level == ContextLevel.FOLD_NORMAL || level == ContextLevel.FOLD_AGGRESSIVE) {
            splitByTailBudget(messages, tailBudget)
        } else if (level == ContextLevel.FORCE_SUMMARY) {
            splitByTailBudget(messages, (0.20f * modelContextWindow).toInt())
        } else if (level == ContextLevel.MECHANICAL_TRUNCATE) {
            val targetTokens = (0.70f * modelContextWindow).toInt()
            splitByTokenTarget(messages, targetTokens - systemTokens)
        } else {
            emptyList<ModelMessage>() to messages
        }

        Timber.d("ContextManager: ratio=%.2f, level=%s, compress=%d, keep=%d".format(ratio, level, toCompress.size, toKeep.size))

        return ContextCheckResult(
            level = level,
            ratio = ratio,
            messagesToCompress = toCompress,
            messagesToKeep = toKeep
        )
    }

    fun foldMessages(
        messages: List<ModelMessage>,
        tailBudget: Int,
        modelContextWindow: Int
    ): FoldResult {
        val (toFold, toKeep) = splitByTailBudget(messages, tailBudget)
        if (toFold.isEmpty()) {
            return FoldResult(summary = "", foldedCount = 0, keptCount = toKeep.size)
        }

        val summary = buildFoldSummary(toFold)
        Timber.d("ContextManager: folded %d messages, kept %d".format(toFold.size, toKeep.size))

        return FoldResult(
            summary = summary,
            foldedCount = toFold.size,
            keptCount = toKeep.size
        )
    }

    fun mechanicalTruncate(
        messages: List<ModelMessage>,
        targetRatio: Float,
        modelContextWindow: Int
    ): List<ModelMessage> {
        val targetTokens = (targetRatio * modelContextWindow).toInt()
        val result = mutableListOf<ModelMessage>()
        var accumulated = 0

        for (msg in messages.reversed()) {
            val msgTokens = estimateMessage(msg)
            if (accumulated + msgTokens > targetTokens) break
            result.add(0, msg)
            accumulated += msgTokens
        }

        Timber.d("ContextManager: mechanical truncate %d -> %d messages".format(messages.size, result.size))
        return result
    }

    private fun splitByTailBudget(messages: List<ModelMessage>, tailBudget: Int): Pair<List<ModelMessage>, List<ModelMessage>> {
        val keep = mutableListOf<ModelMessage>()
        var tailTokens = 0

        for (msg in messages.reversed()) {
            val msgTokens = estimateMessage(msg)
            if (tailTokens + msgTokens > tailBudget) break
            keep.add(0, msg)
            tailTokens += msgTokens
        }

        val compress = messages.take(messages.size - keep.size)
        return compress to keep
    }

    private fun splitByTokenTarget(messages: List<ModelMessage>, targetTokens: Int): Pair<List<ModelMessage>, List<ModelMessage>> {
        val keep = mutableListOf<ModelMessage>()
        var accumulated = 0

        for (msg in messages.reversed()) {
            val msgTokens = estimateMessage(msg)
            if (accumulated + msgTokens > targetTokens) break
            keep.add(0, msg)
            accumulated += msgTokens
        }

        val compress = messages.take(messages.size - keep.size)
        return compress to keep
    }

    private fun buildFoldSummary(messages: List<ModelMessage>): String {
        val sb = StringBuilder()
        sb.appendLine("[上下文折叠摘要]")
        val userMsgs = messages.filter { it.role == "user" }
        val assistantMsgs = messages.filter { it.role == "assistant" }
        val toolMsgs = messages.filter { it.role == "tool" }

        if (userMsgs.isNotEmpty()) {
            sb.appendLine("用户请求:")
            userMsgs.takeLast(5).forEach { sb.appendLine("- ${it.content.take(200)}") }
        }
        if (assistantMsgs.isNotEmpty()) {
            sb.appendLine("助手回复:")
            assistantMsgs.takeLast(5).forEach { sb.appendLine("- ${it.content.take(200)}") }
        }
        if (toolMsgs.isNotEmpty()) {
            sb.appendLine("工具调用: ${toolMsgs.size}次")
        }
        sb.appendLine("共折叠 ${messages.size} 条消息")
        return sb.toString()
    }

    private fun estimateMessages(messages: List<ModelMessage>): Int {
        return messages.sumOf { estimateMessage(it) }
    }

    private fun estimateMessage(message: ModelMessage): Int {
        var tokens = estimateTokens(message.content)
        tokens += 4
        message.reasoningContent?.let { tokens += estimateTokens(it) }
        message.toolCalls?.let { tcs ->
            tokens += tcs.sumOf { tc ->
                estimateTokens(tc.function.name) + estimateTokens(tc.function.arguments) + 10
            }
        }
        return tokens
    }

    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.toByteArray(Charsets.UTF_8).size / ESTIMATE_DIVISOR).roundToInt()
    }
}
