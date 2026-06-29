package com.lin.hippyagent.core.security.output

import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.security.pii.PiiMasker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * LLM 输出校验器。
 * 1. 检查 tool_call JSON 结构完整性（name/arguments 字段存在）
 * 2. 检查 arguments 是合法 JSON 对象
 * 3. 检查 tool_name 在已注册工具列表中
 * 4. 还原 PII token（调用 PiiMasker.unmask）
 */
class OutputValidator(
    private val piiMasker: PiiMasker
) {
    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String>,
        val sanitizedOutput: String
    )

    fun validate(
        response: ModelCallResponse,
        registeredTools: Set<String>
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val message = response.choices.firstOrNull()?.message
        val toolCalls = message?.toolCalls.orEmpty()

        for (toolCall in toolCalls) {
            // 1. 字段完整性（name 非空）
            if (toolCall.function.name.isBlank()) {
                errors.add("tool_call id=${toolCall.id}: missing 'name'")
            }
            // 2. arguments 是合法 JSON 对象
            if (!isValidJsonObject(toolCall.function.arguments)) {
                errors.add("tool_call '${toolCall.function.name}': arguments is not a JSON object")
            }
            // 3. 工具名注册
            if (toolCall.function.name.isNotBlank() && toolCall.function.name !in registeredTools) {
                errors.add("tool_call '${toolCall.function.name}': tool not registered")
            }
        }

        // 4. 还原 token
        val unmasked = piiMasker.unmask(message?.content ?: "")

        return ValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            sanitizedOutput = unmasked
        )
    }

    private fun isValidJsonObject(text: String): Boolean =
        runCatching {
            Json.parseToJsonElement(text) is JsonObject
        }.getOrDefault(false)
}
