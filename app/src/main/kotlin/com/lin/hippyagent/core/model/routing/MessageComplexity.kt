package com.lin.hippyagent.core.model.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Six-dimensional representation of message complexity.
 * Each dimension captures a different axis of computational cost,
 * and [compositeScore] provides a single weighted scalar for classification.
 */
@Serializable
data class MessageComplexity(
    @SerialName("cjk_token_estimate")
    val cjkTokenEstimate: Int,

    @SerialName("code_block_count")
    val codeBlockCount: Int,

    @SerialName("tool_call_density")
    val toolCallDensity: Float,

    @SerialName("total_length")
    val totalLength: Int,

    @SerialName("question_count")
    val questionCount: Int,

    @SerialName("technical_term_count")
    val technicalTermCount: Int,

    @SerialName("history_context_size")
    val historyContextSize: Int = 0,

    @SerialName("has_multimodal")
    val hasMultimodal: Boolean = false
) {
    val compositeScore: Float by lazy {
        val cjkNorm = (cjkTokenEstimate.toFloat() / 500f).coerceIn(0f, 1f)
        val codeNorm = (codeBlockCount.toFloat() / 5f).coerceIn(0f, 1f)
        val toolNorm = toolCallDensity.coerceIn(0f, 1f)
        val lengthNorm = (totalLength.toFloat() / 4000f).coerceIn(0f, 1f)
        val questionNorm = (questionCount.toFloat() / 10f).coerceIn(0f, 1f)
        val techNorm = (technicalTermCount.toFloat() / 15f).coerceIn(0f, 1f)
        val contextNorm = (historyContextSize.toFloat() / 8000f).coerceIn(0f, 1f)
        val multimodalNorm = if (hasMultimodal) 1f else 0f

        (cjkNorm * WEIGHT_CJK +
            codeNorm * WEIGHT_CODE +
            toolNorm * WEIGHT_TOOL +
            lengthNorm * WEIGHT_LENGTH +
            questionNorm * WEIGHT_QUESTION +
            techNorm * WEIGHT_TECH +
            contextNorm * WEIGHT_CONTEXT +
            multimodalNorm * WEIGHT_MULTIMODAL).coerceIn(0f, 1f)
    }

    companion object {
        private const val WEIGHT_CJK = 0.12f
        private const val WEIGHT_CODE = 0.20f
        private const val WEIGHT_TOOL = 0.12f
        private const val WEIGHT_LENGTH = 0.12f
        private const val WEIGHT_QUESTION = 0.08f
        private const val WEIGHT_TECH = 0.16f
        private const val WEIGHT_CONTEXT = 0.10f
        private const val WEIGHT_MULTIMODAL = 0.10f
    }
}

/**
 * Extracts [MessageComplexity] features from raw message text.
 *
 * The extraction is intentionally lightweight (regex-based, no ML models)
 * so it can run synchronously on the main thread without measurable latency.
 */
object MessageComplexityExtractor {

    /** Common technical terms that signal domain-specific requests. */
    private val TECHNICAL_PATTERNS = listOf(
        Regex("""\bAPI\b""", RegexOption.IGNORE_CASE),
        Regex("""\bSDK\b""", RegexOption.IGNORE_CASE),
        Regex("""\bSQL\b""", RegexOption.IGNORE_CASE),
        Regex("""\bREST\b""", RegexOption.IGNORE_CASE),
        Regex("""\bJSON\b""", RegexOption.IGNORE_CASE),
        Regex("""\bXML\b""", RegexOption.IGNORE_CASE),
        Regex("""\bHTTP\b""", RegexOption.IGNORE_CASE),
        Regex("""\bHTTPS\b""", RegexOption.IGNORE_CASE),
        Regex("""\bOAuth\b""", RegexOption.IGNORE_CASE),
        Regex("""\bJWT\b""", RegexOption.IGNORE_CASE),
        Regex("""\bWebSocket\b""", RegexOption.IGNORE_CASE),
        Regex("""\bgRPC\b""", RegexOption.IGNORE_CASE),
        Regex("""\bKotlin\b""", RegexOption.IGNORE_CASE),
        Regex("""\bJava\b""", RegexOption.IGNORE_CASE),
        Regex("""\bPython\b""", RegexOption.IGNORE_CASE),
        Regex("""\bTypeScript\b""", RegexOption.IGNORE_CASE),
        Regex("""\bJavaScript\b""", RegexOption.IGNORE_CASE),
        Regex("""\bReact\b""", RegexOption.IGNORE_CASE),
        Regex("""\bDocker\b""", RegexOption.IGNORE_CASE),
        Regex("""\bKubernetes\b""", RegexOption.IGNORE_CASE),
        Regex("""\bregex\b""", RegexOption.IGNORE_CASE),
        Regex("""\bLambda\b""", RegexOption.IGNORE_CASE),
        Regex("""\bCoroutine\b""", RegexOption.IGNORE_CASE),
        Regex("""\bFlow\b""", RegexOption.IGNORE_CASE),
        Regex("""\bRoom\b""", RegexOption.IGNORE_CASE),
        Regex("""\bGradle\b""", RegexOption.IGNORE_CASE),
        Regex("""\bMoshi\b""", RegexOption.IGNORE_CASE),
        Regex("""\bRetrofit\b""", RegexOption.IGNORE_CASE),
        Regex("""\bOkHttp\b""", RegexOption.IGNORE_CASE)
    )

    private val CODE_BLOCK_DELIMITER = Regex("```")

    private val QUESTION_MARK = Regex("[?？]")

    /**
     * Extract complexity features from [message].
     *
     * @param message       The user message text.
     * @param toolCallCount Optional: number of tool calls in the current turn
     *                      (useful when the message is part of an agentic loop).
     * @return A [MessageComplexity] instance with all six dimensions populated.
     */
    fun extract(
        message: String,
        toolCallCount: Int = 0,
        historyTokenEstimate: Int = 0,
        hasMultimodal: Boolean = false
    ): MessageComplexity {
        val totalLength = message.length

        // CJK token estimate: count characters in CJK Unified Ideographs range
        // and divide by 2 (approximate tokenization for Chinese/Japanese).
        val cjkCharCount = message.count { ch ->
            val code = ch.code
            (code in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
                (code in 0x3400..0x4DBF) ||   // CJK Extension A
                (code in 0x3000..0x303F) ||   // CJK Symbols and Punctuation
                (code in 0xFF00..0xFFEF)      // Fullwidth Forms
        }
        val cjkTokenEstimate = cjkCharCount / 2

        // Code block count: each pair of ``` delimiters is one block
        val codeBlockCount = CODE_BLOCK_DELIMITER.findAll(message).count() / 2

        // Tool call density: calls per 100 characters
        val toolCallDensity = if (totalLength > 0) {
            (toolCallCount.toFloat() / totalLength) * 100f
        } else {
            0f
        }

        // Question count
        val questionCount = QUESTION_MARK.findAll(message).count()

        // Technical term count
        val technicalTermCount = TECHNICAL_PATTERNS.count { pattern ->
            pattern.containsMatchIn(message)
        }

        return MessageComplexity(
            cjkTokenEstimate = cjkTokenEstimate,
            codeBlockCount = codeBlockCount,
            toolCallDensity = toolCallDensity,
            totalLength = totalLength,
            questionCount = questionCount,
            technicalTermCount = technicalTermCount,
            historyContextSize = historyTokenEstimate,
            hasMultimodal = hasMultimodal
        )
    }
}

