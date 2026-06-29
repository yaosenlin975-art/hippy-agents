package com.lin.hippyagent.core.security.memory

import com.lin.hippyagent.core.security.injection.InjectionDetector
import com.lin.hippyagent.core.security.injection.JailbreakDetector

/**
 * 第二大脑写入内容守卫。
 * 检测写入内容是否含注入/越狱模式，若含则标记 untrusted 而非拒绝写入。
 *
 * 设计依据：拒绝写入会丢失信息（用户可能确实在讨论注入攻击），
 * 标记 untrusted 保留信息但在 recall 时隔离到 <untrusted> 区。
 */
object MemoryContentGuard {

    data class GuardResult(
        val safe: Boolean,
        val untrusted: Boolean,
        val ruleId: String?,
        val reason: String?
    )

    fun check(content: String): GuardResult {
        val injectionResult = InjectionDetector.detect(
            content,
            InjectionDetector.DetectionResult.Source.MEMORY_RECALL
        )
        if (injectionResult != null) {
            return GuardResult(
                safe = true,
                untrusted = true,
                ruleId = injectionResult.ruleId,
                reason = "INJECTION_DETECTED"
            )
        }

        val jailbreakResult = JailbreakDetector.detect(
            content,
            InjectionDetector.DetectionResult.Source.MEMORY_RECALL
        )
        if (jailbreakResult != null) {
            return GuardResult(
                safe = true,
                untrusted = true,
                ruleId = jailbreakResult.ruleId,
                reason = "JAILBREAK_DETECTED"
            )
        }

        return GuardResult(safe = true, untrusted = false, ruleId = null, reason = null)
    }
}
