package com.lin.hippyagent.core.security.injection

import com.lin.hippyagent.core.security.PatternLibrary

/**
 * Prompt Injection 检测器。
 * 检测用户输入和工具输出中的注入模式，返回检测结果。
 * 检测到后不拒绝，而是通过 [wrapUntrusted] 隔离到 <untrusted> 区。
 */
object InjectionDetector {

    data class DetectionResult(
        val detected: Boolean,
        val ruleId: String,
        val matchedPattern: String,
        val matchedSnippet: String,
        val source: Source
    ) {
        enum class Source { USER_INPUT, TOOL_OUTPUT, MEMORY_RECALL }
    }

    fun detect(text: String, source: DetectionResult.Source): DetectionResult? {
        for ((ruleId, pattern) in PatternLibrary.ALL_INJECTION_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return DetectionResult(
                detected = true,
                ruleId = ruleId,
                matchedPattern = pattern.pattern,
                matchedSnippet = match.value,
                source = source
            )
        }
        return null
    }

    /**
     * 将检测到注入的内容包裹在 <untrusted> 标签内。
     * 若匹配段在文本中可定位，仅包裹匹配段及其上下文（前后各 50 字符）；
     * 否则整体包裹。
     */
    fun wrapUntrusted(text: String, result: DetectionResult): String {
        val idx = text.indexOf(result.matchedSnippet)
        if (idx < 0) return wrapWhole(text, result)
        val start = maxOf(0, idx - 50)
        val end = minOf(text.length, idx + result.matchedSnippet.length + 50)
        val before = text.substring(0, start)
        val segment = text.substring(start, end)
        val after = text.substring(end)
        return buildString {
            append(before)
            appendUntrustedOpen(result)
            append(segment)
            appendUntrustedClose()
            append(after)
        }
    }

    private fun wrapWhole(text: String, result: DetectionResult): String =
        buildString {
            appendUntrustedOpen(result)
            append(text)
            appendUntrustedClose()
        }

    private fun StringBuilder.appendUntrustedOpen(result: DetectionResult) {
        append("<untrusted source=\"${result.source}\" reason=\"INJECTION_PATTERN_MATCHED\" rule_id=\"${result.ruleId}\">\n")
    }

    private fun StringBuilder.appendUntrustedClose() {
        append("\n</untrusted>")
    }
}
