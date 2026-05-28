package com.lin.hippyagent.core.model.routing

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.serialization.Serializable
import timber.log.Timber

@Serializable
enum class ForceSummaryReason {
    STUCK,
    CONTEXT_GUARD,
    ABORTED
}

object ForceSummary {

    private const val SUMMARY_PROMPT = "请对以上对话进行纯文本总结，保留关键信息和决策，不要使用任何工具调用标记或特殊格式。"

    private val HALLUCINATED_MARKUP_PATTERNS = listOf(
        Regex("""<<<NEEDS_PRO>>>"""),
        Regex("""<<<NEEDS_PRO[^>]*>>>"""),
        Regex("""<\/?dsml[^>]*>"""),
        Regex("""<\/?DSML[^>]*>""")
    )

    suspend fun forceSummary(
        messages: List<ModelMessage>,
        reason: ForceSummaryReason,
        modelClient: ModelClient,
        lightModelName: String
    ): String {
        Timber.d("ForceSummary: triggered due to %s, messages=%d".format(reason, messages.size))

        val summaryMessages = messages + ModelMessage(
            role = "user",
            content = SUMMARY_PROMPT
        )

        val request = ModelCallRequest(
            model = lightModelName,
            messages = summaryMessages,
            temperature = 0.3f,
            stream = false
        )

        val response = try {
            modelClient.chatCompletion(request)
        } catch (e: Exception) {
            Timber.e(e, "ForceSummary: LLM call failed")
            return buildFallbackSummary(messages, reason)
        }

        val rawSummary = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (rawSummary.isEmpty()) {
            Timber.w("ForceSummary: empty response from LLM")
            return buildFallbackSummary(messages, reason)
        }

        val cleaned = stripHallucinatedToolMarkup(rawSummary)
        val prefixed = "[${reason.name}] $cleaned"

        Timber.d("ForceSummary: summary length=%d".format(prefixed.length))
        return prefixed
    }

    fun stripHallucinatedToolMarkup(text: String): String {
        var result = text
        for (pattern in HALLUCINATED_MARKUP_PATTERNS) {
            result = result.replace(pattern, "")
        }
        return result.trim()
    }

    private fun buildFallbackSummary(messages: List<ModelMessage>, reason: ForceSummaryReason): String {
        val sb = StringBuilder()
        sb.append("[${reason.name}] ")
        sb.appendLine("[强制摘要-回退模式]")
        val userMsgs = messages.filter { it.role == "user" }
        val assistantMsgs = messages.filter { it.role == "assistant" }
        if (userMsgs.isNotEmpty()) {
            sb.appendLine("用户请求:")
            userMsgs.takeLast(3).forEach { sb.appendLine("- ${it.content.take(150)}") }
        }
        if (assistantMsgs.isNotEmpty()) {
            sb.appendLine("助手回复:")
            assistantMsgs.takeLast(3).forEach { sb.appendLine("- ${it.content.take(150)}") }
        }
        sb.appendLine("共 ${messages.size} 条消息")
        return sb.toString()
    }
}
