package com.lin.hippyagent.core.tools

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class AskClarificationTool : Tool() {

    override val definition = ToolDefinition(
        name = "ask",
        description = "当用户请求模糊、缺少关键信息、或存在多种执行方案时，向用户提问。调用后立即停止当前轮次，等待用户回复。",
        parameters = mapOf(
            "question" to ToolParameter(
                name = "question",
                type = "string",
                description = "向用户提出的澄清问题",
                required = true
            ),
            "clarification_type" to ToolParameter(
                name = "clarification_type",
                type = "string",
                description = "澄清类型：missing_info(缺少信息), approach_choice(方案选择), risk_confirmation(风险确认), scope_ambiguity(范围模糊), dependency_check(依赖检查)",
                required = true
            ),
            "context" to ToolParameter(
                name = "context",
                type = "string",
                description = "需要澄清的上下文描述",
                required = false
            ),
            "options" to ToolParameter(
                name = "options",
                type = "string",
                description = "可选的选项列表，JSON 数组格式。如 [\"选项A\", \"选项B\"]",
                required = false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val question = getRequiredArgument(arguments, "question")
        val clarificationType = getRequiredArgument(arguments, "clarification_type")
        val context = arguments["context"] as? String ?: ""
        val optionsJson = arguments["options"] as? String ?: ""

        val options = if (optionsJson.isNotBlank()) {
            try {
                val arr = org.json.JSONArray(optionsJson)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val summary = buildString {
            append("CLARIFICATION_REQUEST:")
            append("type=$clarificationType")
            append("|question=$question")
            if (context.isNotBlank()) append("|context=$context")
            if (options.isNotEmpty()) append("|options=${options.joinToString(";")}")
        }

        return ToolResult(
            callId = callId,
            success = true,
            output = "已向用户请求澄清: $question",
            forLLM = summary
        )
    }

    companion object {
        fun parseForLLM(forLLM: String): ClarificationRequest? {
            if (!forLLM.startsWith("CLARIFICATION_REQUEST:")) return null
            val parts = forLLM.removePrefix("CLARIFICATION_REQUEST:").split("|")
            val typePart = parts.firstOrNull()?.let {
                if (it.startsWith("type=")) it.removePrefix("type=") else null
            } ?: return null
            val questionPart = parts.firstOrNull {
                it.startsWith("question=")
            }?.removePrefix("question=") ?: return null
            val contextPart = parts.firstOrNull {
                it.startsWith("context=")
            }?.removePrefix("context=") ?: ""
            val optionsPart = parts.firstOrNull {
                it.startsWith("options=")
            }?.removePrefix("options=")?.split(";")?.filter { it.isNotBlank() } ?: emptyList()

            return ClarificationRequest(
                type = typePart,
                question = questionPart,
                context = contextPart,
                options = optionsPart
            )
        }
    }
}

data class ClarificationRequest(
    val type: String,
    val question: String,
    val context: String = "",
    val options: List<String> = emptyList()
)
