package com.lin.hippyagent.core.security.pii

import com.lin.hippyagent.core.security.PatternLibrary
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.LinkedHashMap

/**
 * 可逆 PII 脱敏器：将 PII 替换为 token，提供还原能力。
 *
 * - 会话级 token map，会话结束调用 [clear] 清理
 * - token 格式：[TYPE_N]，如 [PHONE_1]、[IDCARD_1]、[BANKCARD_1]、[EMAIL_1]
 * - 上限 maxSize（默认 1000），超限 LRU 自动淘汰
 * - 线程安全：synchronizedMap + AtomicInteger
 */
class PiiMasker(
    private val maxSize: Int = 1000
) {
    private val tokenToOriginal: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                size > maxSize
        }
    )

    private val counters = java.util.concurrent.ConcurrentHashMap<PiiType, AtomicInteger>()

    enum class PiiType(val tokenPrefix: String, val pattern: Regex) {
        PHONE("PHONE", PatternLibrary.PHONE_CN),
        IDCARD("IDCARD", PatternLibrary.IDCARD_CN),
        BANKCARD("BANKCARD", PatternLibrary.BANKCARD),
        EMAIL("EMAIL", PatternLibrary.EMAIL);
    }

    /** 脱敏：扫描文本，将 PII 替换为 token，返回脱敏后文本 */
    fun mask(input: String): String {
        var result = input
        for (type in PiiType.entries) {
            result = type.pattern.replace(result) { matchResult ->
                val original = matchResult.value
                val token = allocateToken(type)
                synchronized(tokenToOriginal) {
                    tokenToOriginal[token] = original
                }
                token
            }
        }
        return result
    }

    /** 还原：扫描文本，将 token 替换回原始值 */
    fun unmask(output: String): String {
        var result = output
        synchronized(tokenToOriginal) {
            for ((token, original) in tokenToOriginal) {
                result = result.replace(token, original)
            }
        }
        return result
    }

    /** 清理：会话结束或超限时调用 */
    fun clear() {
        synchronized(tokenToOriginal) {
            tokenToOriginal.clear()
        }
        counters.clear()
    }

    private fun allocateToken(type: PiiType): String {
        val counter = counters.computeIfAbsent(type) { AtomicInteger(0) }
        val n = counter.incrementAndGet()
        return "[${type.tokenPrefix}_$n]"
    }
}
