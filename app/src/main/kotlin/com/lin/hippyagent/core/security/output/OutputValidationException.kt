package com.lin.hippyagent.core.security.output

/**
 * LLM 输出校验失败异常。
 * 由 [OutputValidator.validate] 检测到无效 tool_call 时抛出，
 * Agent 主循环捕获后向 LLM 返回修正提示，连续 3 次失败则终止本轮。
 */
class OutputValidationException(
    val errors: List<String>
) : Exception("LLM 输出校验失败: ${errors.joinToString("; ")}")
