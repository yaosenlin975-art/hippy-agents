package com.lin.hippyagent.core.security

import com.lin.hippyagent.core.security.injection.InjectionDetector
import com.lin.hippyagent.core.security.injection.JailbreakDetector
import com.lin.hippyagent.core.security.pii.PiiMasker

/**
 * 输入侧统一守卫。
 * 处理顺序：PII 脱敏 → 注入检测 → 越狱检测 → 隔离包装。
 *
 * @param piiMasker 会话级 PiiMasker 实例
 * @param traceId 当前 Agent 轮次的 traceId（用于安全事件上报）
 */
class InputGuard(
    private val piiMasker: PiiMasker,
    private val traceId: String
) {

    data class GuardOutput(
        val processedText: String,
        val detections: List<Detection>
    )

    data class Detection(
        val type: DetectionType,
        val severity: RiskLevel,
        val source: InjectionDetector.DetectionResult.Source,
        val ruleId: String?,
        val matchedSnippet: String?,
        val blocked: Boolean
    ) {
        enum class DetectionType { PII_MASKED, INJECTION_DETECTED, JAILBREAK_DETECTED }
    }

    fun guard(
        text: String,
        source: InjectionDetector.DetectionResult.Source
    ): GuardOutput {
        val detections = mutableListOf<Detection>()

        // 1. PII 脱敏
        val masked = piiMasker.mask(text)
        if (masked != text) {
            detections.add(
                Detection(
                    type = Detection.DetectionType.PII_MASKED,
                    severity = RiskLevel.LOW,
                    source = source,
                    ruleId = null,
                    matchedSnippet = null,
                    blocked = false
                )
            )
        }

        // 2. 注入检测
        val injectionResult = InjectionDetector.detect(masked, source)
        var processed = masked
        if (injectionResult != null) {
            detections.add(
                Detection(
                    type = Detection.DetectionType.INJECTION_DETECTED,
                    severity = RiskLevel.MEDIUM,
                    source = source,
                    ruleId = injectionResult.ruleId,
                    matchedSnippet = injectionResult.matchedSnippet,
                    blocked = false
                )
            )
            processed = InjectionDetector.wrapUntrusted(masked, injectionResult)
        }

        // 3. 越狱检测
        val jailbreakResult = JailbreakDetector.detect(masked, source)
        if (jailbreakResult != null) {
            detections.add(
                Detection(
                    type = Detection.DetectionType.JAILBREAK_DETECTED,
                    severity = RiskLevel.HIGH,
                    source = source,
                    ruleId = jailbreakResult.ruleId,
                    matchedSnippet = jailbreakResult.matchedSnippet,
                    blocked = false
                )
            )
            // 越狱检测结果若未被注入检测包裹，再包裹一次
            if (injectionResult == null) {
                processed = InjectionDetector.wrapUntrusted(
                    masked,
                    InjectionDetector.DetectionResult(
                        detected = true,
                        ruleId = jailbreakResult.ruleId,
                        matchedPattern = "",
                        matchedSnippet = jailbreakResult.matchedSnippet,
                        source = source
                    )
                )
            }
        }

        return GuardOutput(processedText = processed, detections = detections)
    }
}
