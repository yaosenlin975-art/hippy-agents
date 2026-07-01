package com.lin.hippyagent.core.model.routing

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * LLM 任务路由器。
 *
 * 当 [RuleClassifier.classifyRoute] 返回 [RouteTarget.UNCERTAIN] 时，
 * 用云端小模型做一次轻量调用判断难度。
 *
 * 设计：
 * - 用云端小模型（如 gpt-4o-mini / qwen-turbo）
 * - temperature=0.1, maxTokens=100
 * - prompt 极简：仅判断"端侧可处理"或"需云端"
 * - 失败降级到云端（安全默认）
 *
 * @param modelClient 云端小模型的 ModelClient
 * @param config 路由器配置
 */
class LlmTaskRouter(
    private val modelClient: ModelClient,
    private val config: LlmTaskRouterConfig
) {

    data class LlmTaskRouterConfig(
        val enabled: Boolean = true,
        val routerModelName: String,
        val timeoutMs: Long = 5000
    )

    /**
     * 判断任务难度。
     *
     * @param userMessage 用户消息
     * @param hasTools 是否有可用工具
     * @return 路由决策
     */
    suspend fun route(
        userMessage: String,
        hasTools: Boolean
    ): RouteDecision {
        if (!config.enabled) {
            return RouteDecision(RouteTarget.CLOUD, 0.5f, "ROUTER_DISABLED")
        }

        val prompt = buildRouterPrompt(userMessage, hasTools)
        val request = ModelCallRequest(
            model = config.routerModelName,
            messages = listOf(
                ModelMessage(role = "system", content = ROUTER_SYSTEM_PROMPT),
                ModelMessage(role = "user", content = prompt)
            ),
            temperature = 0.1f,
            maxTokens = 100
        )

        return try {
            withTimeout(config.timeoutMs) {
                val response = modelClient.chatCompletion(request)
                val content = response.choices.firstOrNull()?.message?.content ?: ""
                parseRouterResponse(content)
            }
        } catch (e: Exception) {
            Timber.w(e, "LlmTaskRouter failed, defaulting to CLOUD")
            RouteDecision(RouteTarget.CLOUD, 0.3f, "ROUTER_FAILED: ${e.message}")
        }
    }

    private fun buildRouterPrompt(userMessage: String, hasTools: Boolean): String {
        return buildString {
            append("判断以下用户消息是否可以由一个轻量级本地模型（1B参数，无代码能力）处理：\n\n")
            append("消息：${userMessage.take(500)}\n\n")
            append("可用工具：${if (hasTools) "是" else "否"}\n\n")
            append("回答 ONDEVICE 或 CLOUD（仅回答一个词）：")
        }
    }

    private fun parseRouterResponse(content: String): RouteDecision {
        val trimmed = content.trim().uppercase()
        return when {
            trimmed.contains("ONDEVICE") -> RouteDecision(
                RouteTarget.ONDEVICE, 0.8f, "LLM_ROUTER_ONDEVICE"
            )
            trimmed.contains("CLOUD") -> RouteDecision(
                RouteTarget.CLOUD, 0.8f, "LLM_ROUTER_CLOUD"
            )
            else -> RouteDecision(
                RouteTarget.CLOUD, 0.5f, "LLM_ROUTER_UNPARSEABLE"
            )
        }
    }

    companion object {
        private val ROUTER_SYSTEM_PROMPT = """
            你是一个任务路由器。判断用户消息是否可以由轻量级本地模型处理。
            本地模型能力：简单问答、寒暄、基础知识。
            本地模型不能：写代码、复杂数学、长文本生成、多步推理。
            仅回答 ONDEVICE 或 CLOUD，不要解释。
        """.trimIndent()
    }
}
