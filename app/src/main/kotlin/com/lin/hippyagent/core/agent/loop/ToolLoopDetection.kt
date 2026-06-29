package com.lin.hippyagent.core.agent.loop

import timber.log.Timber
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 4 模式工具循环检测器.
 *
 * 参考: X-OmniClaw agent/loop/ToolLoopDetection.kt (456 行)
 *
 * 4 类检测:
 * 1. generic_repeat: 同 tool+args 重复 N 次 → WARN
 * 2. known_poll_no_progress: 轮询类工具相同 args 相同 result → WARN/CRITICAL
 * 3. ping_pong: 两工具交替无进展 → WARN/CRITICAL
 * 4. global_circuit_breaker: 同 tool+args+result 无进展 30 次 → CRITICAL 熔断
 *
 * 设计:
 * - SHA-256 签名含 (toolName, params, result[:500]), 精准识别"换参数的同名工具"
 * - ArrayDeque(30) 滚动窗口, 自动淘汰旧记录 (符合 coding.md "Map缓存无清理 → ✅ remove用完即清")
 * - reportedWarnings 去重, 同一规则不重复上报, 上限 1000 LRU 淘汰
 * - knownPollTools 集合上限 100
 *
 * 线程安全: MessageDigest 在 companion object 用 synchronized 保护;
 *   recentRecords/reportedWarnings/knownPollTools 由调用方保证单线程访问 (Agent 单协程顺序调用).
 */
class ToolLoopDetection(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val genericRepeatThreshold: Int = 10,
    private val pollNoProgressThreshold: Int = 10,
    private val pollNoProgressHardThreshold: Int = 20,
    private val pingPongThreshold: Int = 10,
    private val pingPongHardThreshold: Int = 20,
    private val circuitBreakerThreshold: Int = 30
) {
    /** 滚动窗口: 每条记录是 (toolName, paramsHash, resultHash) */
    private val recentRecords = ArrayDeque<ToolRecord>(windowSize)

    /** 已上报的警告 (ruleId + signature), 防重复上报. 上限 1000, 超限清空重建. */
    private val reportedWarnings: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val reportedWarningsLimit = 1000

    /** 已知的轮询类工具 (如 check_status, poll_result). 上限 100, 超限清空重建. */
    private val knownPollTools: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private val knownPollToolsLimit = 100

    enum class LoopLevel { NONE, WARN, CRITICAL }

    enum class LoopPattern(val ruleId: String) {
        GENERIC_REPEAT("GENERIC_REPEAT"),
        KNOWN_POLL_NO_PROGRESS("KNOWN_POLL_NO_PROGRESS"),
        PING_PONG("PING_PONG"),
        GLOBAL_CIRCUIT_BREAKER("GLOBAL_CIRCUIT_BREAKER")
    }

    data class ToolRecord(
        val toolName: String,
        val paramsHash: String,
        val resultHash: String
    )

    data class DetectionResult(
        val level: LoopLevel,
        val pattern: LoopPattern?,
        val message: String,
        val signature: String
    )

    /**
     * 检查并记录本轮工具调用.
     *
     * @param toolName 工具名 (一轮多个工具时取第一个, 或调用方聚合后传入)
     * @param paramsJson 工具参数 JSON
     * @param resultText 工具结果文本 (取前 500 字符参与哈希)
     */
    fun checkAndRecord(toolName: String, paramsJson: String, resultText: String): DetectionResult {
        val paramsHash = sha256(paramsJson)
        val resultHash = sha256(resultText.take(500))
        val record = ToolRecord(toolName, paramsHash, resultHash)

        recentRecords.addLast(record)
        if (recentRecords.size > windowSize) {
            recentRecords.removeFirst()
        }

        // 1. global_circuit_breaker (最高优先级, CRITICAL 熔断)
        val circuitBreakerSig = "CB:${record.toolName}:${record.paramsHash}:${record.resultHash}"
        val circuitCount = recentRecords.count {
            it.toolName == record.toolName &&
            it.paramsHash == record.paramsHash &&
            it.resultHash == record.resultHash
        }
        if (circuitCount >= circuitBreakerThreshold) {
            return report(
                LoopLevel.CRITICAL,
                LoopPattern.GLOBAL_CIRCUIT_BREAKER,
                "工具 $toolName 相同参数相同结果连续 $circuitCount 次, 触发熔断",
                circuitBreakerSig
            )
        }

        // 2. known_poll_no_progress (轮询类工具无进展)
        if (toolName in knownPollTools) {
            val pollSig = "POLL:${record.toolName}:${record.paramsHash}:${record.resultHash}"
            val pollCount = recentRecords.count {
                it.toolName == record.toolName &&
                it.paramsHash == record.paramsHash
            }
            if (pollCount >= pollNoProgressHardThreshold) {
                return report(
                    LoopLevel.CRITICAL,
                    LoopPattern.KNOWN_POLL_NO_PROGRESS,
                    "轮询工具 $toolName 无进展 $pollCount 次",
                    pollSig
                )
            }
            if (pollCount >= pollNoProgressThreshold) {
                return report(
                    LoopLevel.WARN,
                    LoopPattern.KNOWN_POLL_NO_PROGRESS,
                    "轮询工具 $toolName 无进展 $pollCount 次",
                    pollSig
                )
            }
        }

        // 3. ping_pong (两工具交替无进展)
        detectPingPong(record)?.let { return it }

        // 4. generic_repeat (同 tool+args 重复)
        val repeatSig = "REP:${record.toolName}:${record.paramsHash}"
        val repeatCount = recentRecords.count {
            it.toolName == record.toolName &&
            it.paramsHash == record.paramsHash
        }
        if (repeatCount >= genericRepeatThreshold) {
            return report(
                LoopLevel.WARN,
                LoopPattern.GENERIC_REPEAT,
                "工具 $toolName 相同参数重复 $repeatCount 次",
                repeatSig
            )
        }

        return DetectionResult(LoopLevel.NONE, null, "", "")
    }

    /**
     * ping_pong 检测: 最近 N 条记录呈现 A B A B A B... 交替模式.
     */
    private fun detectPingPong(current: ToolRecord): DetectionResult? {
        if (recentRecords.size < pingPongThreshold) return null
        val recent = recentRecords.toList().takeLast(pingPongThreshold)
        val unique = recent.distinctBy { it.toolName to it.paramsHash }
        if (unique.size != 2) return null
        val (a, b) = unique
        // 验证交替模式: 偶数位是 a, 奇数位是 b (或反之)
        val isAlternating = recent.indices.all { i ->
            val expected = if (i % 2 == 0) a else b
            recent[i].toolName == expected.toolName &&
            recent[i].paramsHash == expected.paramsHash
        }
        if (!isAlternating) return null

        val sig = "PP:${a.toolName}:${a.paramsHash}<->${b.toolName}:${b.paramsHash}"
        val level = if (recent.size >= pingPongHardThreshold) LoopLevel.CRITICAL else LoopLevel.WARN
        return report(
            level,
            LoopPattern.PING_PONG,
            "工具 ${a.toolName} <-> ${b.toolName} 交替无进展 ${recent.size} 次",
            sig
        )
    }

    private fun report(
        level: LoopLevel,
        pattern: LoopPattern,
        message: String,
        signature: String
    ): DetectionResult {
        // 去重: 同 signature 的 WARN 仅上报一次; CRITICAL 始终上报 (升级事件必须通知)
        if (level == LoopLevel.WARN && !reportedWarnings.add(signature)) {
            return DetectionResult(LoopLevel.NONE, null, "", "")
        }
        // LRU 淘汰: 超限时清空重建 (简化策略, 实际可改为 LRU)
        if (reportedWarnings.size > reportedWarningsLimit) {
            reportedWarnings.clear()
            reportedWarnings.add(signature)
        }
        if (level == LoopLevel.CRITICAL) {
            Timber.w("LoopDetection CRITICAL: $message (pattern=$pattern)")
        } else {
            Timber.i("LoopDetection WARN: $message (pattern=$pattern)")
        }
        return DetectionResult(level, pattern, message, signature)
    }

    /** 注册轮询类工具 (由配置或运行时学习) */
    fun registerPollTool(toolName: String) {
        if (knownPollTools.size >= knownPollToolsLimit) {
            knownPollTools.clear()
        }
        knownPollTools.add(toolName)
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 30

        // MessageDigest 放 companion object (符合 coding.md "正则/常量 → ✅ companion object / 顶层 private val")
        private val md: MessageDigest = MessageDigest.getInstance("SHA-256")

        /** SHA-256 哈希, 返回 hex 字符串. synchronized 保证线程安全 (MessageDigest 非线程安全). */
        private fun sha256(input: String): String {
            synchronized(md) {
                val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
                md.reset()
                return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }
        }

        /** 默认的轮询类工具名集合 (可被配置覆盖) */
        val DEFAULT_POLL_TOOLS: Set<String> = setOf(
            "check_status", "poll_result", "wait", "sleep",
            "get_state", "query_status", "check_progress"
        )
    }
}
