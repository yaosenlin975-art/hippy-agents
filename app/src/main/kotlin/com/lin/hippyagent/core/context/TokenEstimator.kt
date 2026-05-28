package com.lin.hippyagent.core.context

import com.lin.hippyagent.core.agent.session.SessionMessage
import kotlin.math.roundToInt

/**
 * 基于字节的轻量级 Token 估算器
 * 参考 QwenPaw 的 EstimatedTokenCounter 实现
 */
class TokenEstimator(
    private val estimateDivisor: Float = 4.0f
) {
    /**
     * 估算文本的 token 数量
     * token_count = utf8_byte_length / divisor
     */
    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.toByteArray(Charsets.UTF_8).size / estimateDivisor).roundToInt()
    }

    /**
     * 估算消息列表的总 token 数量
     */
    fun countMessages(messages: List<SessionMessage>): Int {
        return messages.sumOf { countMessage(it) }
    }

    /**
     * 估算单条消息的 token 数量
     */
    fun countMessage(message: SessionMessage): Int {
        var tokens = count(message.content)
        // role 标记约占 4 tokens
        tokens += 4
        // tool calls 额外开销
        if (message.toolCalls.isNotEmpty()) {
            tokens += message.toolCalls.sumOf { tc ->
                count(tc.name) + count(tc.arguments) + (tc.output?.let { count(it) } ?: 0) + 10
            }
        }
        return tokens
    }
}

