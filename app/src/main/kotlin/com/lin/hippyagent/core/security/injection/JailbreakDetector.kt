package com.lin.hippyagent.core.security.injection

import com.lin.hippyagent.core.security.PatternLibrary

/**
 * 越狱检测器。与 InjectionDetector 类似但聚焦"角色替换"和"权限提升"模式。
 * 检测到越狱时，同样隔离到 <untrusted> 区，并记录更高 severity 的安全事件。
 */
object JailbreakDetector {

    data class DetectionResult(
        val detected: Boolean,
        val ruleId: String,
        val matchedSnippet: String,
        val source: InjectionDetector.DetectionResult.Source
    )

    fun detect(text: String, source: InjectionDetector.DetectionResult.Source): DetectionResult? {
        for ((ruleId, pattern) in PatternLibrary.JAILBREAK_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return DetectionResult(
                detected = true,
                ruleId = ruleId,
                matchedSnippet = match.value,
                source = source
            )
        }
        return null
    }
}
