package com.lin.hippyagent.core.agent.tools

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.model.ModelToolDefinition
import timber.log.Timber

/**
 * LLM-as-a-Router 工具裁剪器。
 *
 * 首轮 LLM 调用不带 tools schema，让模型从"可路由工具名清单"选出本轮需要的子集。
 * 失败降级到关键词启发式。寒暄场景直接 tools=[]。
 *
 * 正则全部放 companion object（遵循 coding.md）。
 * 寒暄正则用 [\s,，。.!！?？]*$ 锚定而非 \b（中文字符不算 \w，\b 在中文后不工作）。
 */
class LlmToolRouter {

    enum class RouteSource { SMALL_TALK, LLM_ROUTED, HEURISTIC_FALLBACK }

    data class RouteResult(
        val selectedTools: List<ModelToolDefinition>,
        val source: RouteSource
    )

    suspend fun routeTools(
        userMessage: String,
        availableTools: List<ModelToolDefinition>,
        routingClient: ModelClient,
        modelName: String
    ): RouteResult {
        // 1. 寒暄检测（fast path）
        if (isSmallTalk(userMessage)) {
            return RouteResult(emptyList(), RouteSource.SMALL_TALK)
        }

        // 2. LLM 路由
        val llmResult = tryLlmRoute(userMessage, availableTools, routingClient, modelName)
        if (llmResult != null) return llmResult

        // 3. 关键词启发式降级
        val heuristicSelected = heuristicRoute(userMessage, availableTools)
        val selected = availableTools.filter { it.name in heuristicSelected }
        return RouteResult(selected, RouteSource.HEURISTIC_FALLBACK)
    }

    private suspend fun tryLlmRoute(
        userMessage: String,
        availableTools: List<ModelToolDefinition>,
        client: ModelClient,
        modelName: String
    ): RouteResult? {
        if (availableTools.isEmpty()) return RouteResult(emptyList(), RouteSource.LLM_ROUTED)

        val availableNames = availableTools.map { it.name }.toSet()
        val toolListText = availableTools.joinToString("\n") { "- ${it.name}: ${it.description.take(80)}" }

        val request = ModelCallRequest(
            model = modelName,
            messages = listOf(
                ModelMessage(
                    role = "system",
                    content = buildRouterSystemPrompt(toolListText)
                ),
                ModelMessage(role = "user", content = userMessage.take(500))
            ),
            temperature = 0.1f,
            maxTokens = 420,
            tools = null
        )

        return try {
            val response = client.chatCompletion(request)
            val content = response.choices.firstOrNull()?.message?.content ?: ""
            val selectedNames = parseRouterResponse(content, availableNames)
            val selected = availableTools.filter { it.name in selectedNames }
            RouteResult(selected, RouteSource.LLM_ROUTED)
        } catch (e: Exception) {
            Timber.w(e, "LlmToolRouter LLM 调用失败，降级到启发式")
            null
        }
    }

    private fun buildRouterSystemPrompt(toolListText: String): String = """
        你是工具路由器。根据用户消息，从可用工具清单中选出本轮需要的工具子集。
        - 只返回工具名，逗号分隔，不要解释
        - 若用户是寒暄/闲聊/不需要工具，返回 NONE
        - 若不确定，返回 ALL

        可用工具清单：
        $toolListText
    """.trimIndent()

    companion object {
        private val SMALL_TALK_PATTERNS: List<Regex> = listOf(
            Regex("""^(你好|您好|hi|hello|hey|嗨|哈喽)[\s,，。.!！?？]*$""", RegexOption.IGNORE_CASE),
            Regex("""^(谢谢|thanks|thank you|多谢|辛苦了)[\s,，。.!！?？]*$""", RegexOption.IGNORE_CASE),
            Regex("""^(再见|bye|goodbye|拜拜|886)[\s,，。.!！?？]*$""", RegexOption.IGNORE_CASE),
            Regex("""^(好的|ok|okay|嗯|哦|行)[\s,，。.!！?？]*$""", RegexOption.IGNORE_CASE)
        )

        private const val SMALL_TALK_MAX_LEN = 50

        private fun isSmallTalk(message: String): Boolean {
            val trimmed = message.trim().take(SMALL_TALK_MAX_LEN)
            return SMALL_TALK_PATTERNS.any { it.containsMatchIn(trimmed) }
        }

        private val TOOL_LIST_SEPARATOR = Regex("""[,\s，、]+""")

        private fun parseRouterResponse(
            response: String,
            availableToolNames: Set<String>
        ): Set<String> {
            val trimmed = response.trim().uppercase()
            return when {
                trimmed == "NONE" -> emptySet()
                trimmed == "ALL" -> availableToolNames
                else -> {
                    trimmed.split(TOOL_LIST_SEPARATOR)
                        .map { it.lowercase().trim() }
                        .filter { it in availableToolNames }
                        .toSet()
                }
            }
        }

        private val HEURISTIC_RULES: List<Pair<Regex, List<String>>> = listOf(
            Regex("""(日程|提醒|闹钟|schedule|reminder|alarm)""", RegexOption.IGNORE_CASE) to
                listOf("calendar", "alarm", "notification"),
            Regex("""(打开|启动|点击|滑动|open|launch|click|swipe|app)""", RegexOption.IGNORE_CASE) to
                listOf("device", "screenshot", "app_launcher"),
            Regex("""(搜索|查询|获取|search|fetch|lookup|网页|web)""", RegexOption.IGNORE_CASE) to
                listOf("web_search", "web_fetch"),
            Regex("""(执行|命令|终端|execute|command|terminal|bash|shell)""", RegexOption.IGNORE_CASE) to
                listOf("shell")
        )

        private fun heuristicRoute(
            userMessage: String,
            availableTools: List<ModelToolDefinition>
        ): Set<String> {
            val availableNames = availableTools.map { it.name }.toSet()
            val selected = mutableSetOf<String>()
            for ((pattern, toolNames) in HEURISTIC_RULES) {
                if (pattern.containsMatchIn(userMessage)) {
                    selected.addAll(toolNames.filter { it in availableNames })
                }
            }
            return selected.ifEmpty { availableNames }
        }
    }
}
