package com.lin.hippyagent.core.context

import com.lin.hippyagent.core.agent.config.ContextCompactConfig
import com.lin.hippyagent.core.agent.config.LightContextConfig
import com.lin.hippyagent.core.agent.config.RunningConfig
import com.lin.hippyagent.core.agent.config.ToolResultPruningConfig
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest

/**
 * 上下文管理器 - 管理对话上下文的 Token 预算
 * 参考 QwenPaw 的 LightContextManager 实现
 *
 * 支持多后端：
 * - "light": LightContextManager（基于 token budget 的裁剪+压缩，默认）
 * - "none"/"off": 仅裁剪工具结果，不执行 LLM 压缩
 *
 * P1 增强:
 * - tool_use/tool_result 对齐保护
 * - 工具结果 MD5 去重
 * - head+tail 截断策略
 */
class ContextManager(
    private val runningConfig: RunningConfig = RunningConfig(),
    private val toolResultCacheDir: String? = null,
    private val compactionTriggers: List<CompactionTrigger> = emptyList()
) {
    private val tokenEstimator = TokenEstimator(runningConfig.lightContextConfig.tokenCountEstimateDivisor)
    private val compactConfig = runningConfig.lightContextConfig.contextCompactConfig
    private val pruningConfig = runningConfig.lightContextConfig.toolResultPruningConfig
    private val maxInputLength = runningConfig.maxInputLength
    val contextBackend: String = runningConfig.contextManagerBackend.ifBlank { "light" }

    val useCompression: Boolean get() = contextBackend != "none" && contextBackend != "off"

    init {
        Timber.d("ContextManager: backend=$contextBackend, compression=$useCompression")
    }

    /**
     * 上下文检查结果
     */
    data class ContextCheckResult(
        val needsCompression: Boolean,
        val totalTokens: Int,
        val maxTokens: Int,
        val usageRatio: Float,
        val messagesToCompress: List<SessionMessage>,
        val messagesToKeep: List<SessionMessage>,
        val prunedMessages: List<SessionMessage>
    )

    /**
     * 执行上下文检查 - 判断是否需要压缩
     */
    suspend fun checkContext(
        messages: List<SessionMessage>,
        systemPrompt: String = "",
        compressedSummary: String? = null,
        modelContextWindow: Int? = null
    ): ContextCheckResult = withContext(Dispatchers.Default) {
        val effectiveMaxInputLength = modelContextWindow ?: maxInputLength

        val systemTokens = tokenEstimator.count(systemPrompt)
        val summaryTokens = compressedSummary?.let { tokenEstimator.count(it) } ?: 0
        val fixedTokens = systemTokens + summaryTokens

        val compactThreshold = (effectiveMaxInputLength * compactConfig.compactThresholdRatio).toInt()
        val reserveThreshold = (effectiveMaxInputLength * compactConfig.reserveThresholdRatio).toInt()
        val effectiveThreshold = compactThreshold - fixedTokens

        // 3. 计算当前消息总 token
        val totalTokens = tokenEstimator.countMessages(messages) + fixedTokens

        // 4. 裁剪工具结果
        val prunedMessages = pruneToolResults(messages)

        // 5. 重新计算裁剪后的 token
        val prunedTokens = tokenEstimator.countMessages(prunedMessages) + fixedTokens

        // 6. 判断是否需要压缩
        val needsCompressionByToken = prunedTokens > effectiveThreshold
        val compactionCtx = CompactionContext(
            totalTokens = prunedTokens,
            maxTokens = effectiveMaxInputLength,
            messageCount = prunedMessages.size,
            recentTurnCount = prunedMessages.count { it.role == MessageRole.USER }
        )
        val triggeredBySystem = compactionTriggers.any { it.shouldCompact(compactionCtx) }
        val needsCompression = needsCompressionByToken || triggeredBySystem

        if (triggeredBySystem) {
            val triggerNames = compactionTriggers.filter { it.shouldCompact(compactionCtx) }.map { it.name }
            Timber.d("Context compaction triggered by: $triggerNames")
        }

        // 7. 确定需要压缩和保留的消息
        val (toCompress, toKeep) = if (needsCompression) {
            splitMessages(prunedMessages, reserveThreshold)
        } else {
            emptyList<SessionMessage>() to prunedMessages
        }

        Timber.d("Context check: total=$totalTokens, pruned=$prunedTokens, threshold=$effectiveThreshold, needsCompression=$needsCompression")

        ContextCheckResult(
            needsCompression = needsCompression,
            totalTokens = totalTokens,
            maxTokens = effectiveMaxInputLength,
            usageRatio = totalTokens.toFloat() / effectiveMaxInputLength,
            messagesToCompress = toCompress,
            messagesToKeep = toKeep,
            prunedMessages = prunedMessages
        )
    }

    /**
     * 裁剪工具结果 - 超长工具输出截断
     * P1-5 增强: MD5 去重，相同内容的工具结果只保留第一次出现的
     */
    private fun pruneToolResults(messages: List<SessionMessage>): List<SessionMessage> {
        if (!pruningConfig.enabled) return messages

        val seenContentHashes = mutableSetOf<String>()
        val dedupCount = intArrayOf(0)

        return messages.mapIndexed { index, message ->
            if (message.role == MessageRole.TOOL) {
                val contentHash = md5Hash(message.content)
                if (seenContentHashes.contains(contentHash)) {
                    dedupCount[0]++
                    message.copy(content = "[重复的工具结果，内容与之前相同]")
                } else {
                    seenContentHashes.add(contentHash)

                    if (isToolExempt(message.toolName)) {
                        message
                    } else {
                        val maxBytes = if (index >= messages.size - pruningConfig.pruningRecentN) {
                            pruningConfig.pruningRecentMsgMaxBytes
                        } else {
                            pruningConfig.pruningOldMsgMaxBytes
                        }

                        if (message.content.length > maxBytes) {
                            val offloadPath = offloadToolResult(message)
                            val truncated = headAndTailTruncate(message.content, maxBytes, offloadPath)
                            message.copy(content = truncated)
                        } else {
                            message
                        }
                    }
                }
            } else {
                message
            }
        }.also {
            if (dedupCount[0] > 0) {
                Timber.d("Pruned ${dedupCount[0]} duplicate tool results by MD5 hash")
            }
        }
    }

    private fun isToolExempt(toolName: String?): Boolean {
        if (toolName == null) return false
        return pruningConfig.exemptToolNames.contains(toolName)
    }

    private fun offloadToolResult(message: SessionMessage): String? {
        val cacheDir = toolResultCacheDir ?: return null
        try {
            val dir = java.io.File(cacheDir, pruningConfig.toolResultsCache)
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "${message.id}.txt")
            file.writeText(message.content)
            return file.absolutePath
        } catch (e: Exception) {
            Timber.w(e, "Failed to offload tool result ${message.id}")
            return null
        }
    }

    /**
     * P1-7: head+tail 截断策略。
     * 保留头部（文件开头/结构信息）和尾部（错误信息/JSON 闭合），
     * 中间用省略标记替代。比纯 tail 截断保留更多有用信息。
     */
    private fun headAndTailTruncate(content: String, maxBytes: Int, offloadPath: String? = null): String {
        if (content.length <= maxBytes) return content

        val headBudget = maxOf((maxBytes * 0.7).toInt(), 200)
        val tailBudget = maxOf(maxBytes - headBudget - 20, 200)

        val head = content.take(headBudget)
        val tail = content.takeLast(tailBudget.coerceAtMost(content.length))

        val pathHint = offloadPath?.let { "\n完整内容: $it" } ?: ""
        return "$head\n\n...[中间 ${content.length - headBudget - tailBudget} 字符已省略]$pathHint\n\n$tail"
    }

    /**
     * 计算 MD5 哈希（用于工具结果去重）
     */
    private fun md5Hash(content: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 语义重要性评分 — 基于启发式规则的轻量级评分（无需嵌入模型）
     *
     * 评分维度：
     * - 角色权重：USER > ASSISTANT > TOOL > SYSTEM
     * - 位置衰减：越早的消息衰减越多
     * - 内容特征：包含决策/错误/关键词的消息得分更高
     * - 工具结果：失败的工具结果比成功的重要
     *
     * @return 0.0~1.0 的重要性评分
     */
    fun scoreImportance(message: SessionMessage, indexInConversation: Int, totalMessages: Int): Float {
        var score = 0f

        // 1. 角色权重
        score += when (message.role) {
            MessageRole.USER -> 0.35f      // 用户消息最重要
            MessageRole.ASSISTANT -> 0.25f // 助手回复
            MessageRole.SYSTEM -> 0.15f    // 系统消息
            MessageRole.TOOL -> 0.10f      // 工具结果默认较低
            MessageRole.PRIVATE -> 0.20f   // 私聊消息
        }

        // 2. 位置衰减：近期消息更重要
        val recency = if (totalMessages > 0) {
            (indexInConversation.toFloat() / totalMessages)
        } else 1f
        score += recency * 0.25f

        // 3. 内容特征评分
        val content = message.content

        // 错误/失败关键词
        val errorKeywords = listOf("error", "failed", "失败", "错误", "exception", "timeout", "refused")
        val hasError = errorKeywords.any { content.contains(it, ignoreCase = true) }
        if (hasError) score += 0.15f

        // 决策关键词
        val decisionKeywords = listOf("决定", "选择", "选择", "decided", "chosen", "will use", "plan", "方案")
        val hasDecision = decisionKeywords.any { content.contains(it, ignoreCase = true) }
        if (hasDecision) score += 0.10f

        // 内容长度：过短的消息可能信息量少
        if (content.length < 20) {
            score -= 0.05f
        } else if (content.length > 500) {
            score += 0.05f // 较长的消息通常包含更多信息
        }

        // 4. 工具结果特殊性：失败比成功重要
        if (message.role == MessageRole.TOOL) {
            if (hasError) score += 0.10f // 失败的工具结果很重要
            // 检查是否有配对的 tool_use（有 toolCalls 的 ASSISTANT 消息）
            // 有配对的消息比孤立的重要
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * 分割消息 - 确定哪些需要压缩，哪些保留
     * P2 增强: 语义重要性评分辅助分割决策
     * P1-4 增强: tool_use/tool_result 对齐保护，防止截断后出现孤立的 tool 消息
     */
    private fun splitMessages(
        messages: List<SessionMessage>,
        reserveThreshold: Int
    ): Pair<List<SessionMessage>, List<SessionMessage>> {
        // 从最新消息向前遍历，找到满足保留阈值的最大切片
        var keepCount = 0
        var keepTokens = 0

        for (i in messages.indices.reversed()) {
            val msgTokens = tokenEstimator.countMessage(messages[i])
            if (keepTokens + msgTokens > reserveThreshold) break
            keepCount++
            keepTokens += msgTokens
        }

        // 确保至少保留 1 条消息
        keepCount = keepCount.coerceAtLeast(1)

        // P1-4: 对齐保护 — 如果保留区的第一条消息是 TOOL 角色，
        // 它可能对应一个已被压缩掉的 ASSISTANT tool_use 消息，
        // 这种孤立的 tool_result 会导致 LLM 报错，需要多保留一条
        val keepStartIndex = messages.size - keepCount
        if (keepStartIndex > 0 && keepStartIndex < messages.size) {
            val firstKeptMessage = messages[keepStartIndex]
            if (firstKeptMessage.role == MessageRole.TOOL) {
                // 往前多保留一条（通常是 ASSISTANT + tool_use），确保配对完整
                if (keepCount < messages.size) {
                    keepCount++
                }
            }
        }

        // P1-4: 检查压缩区尾部是否有 tool_use 但没有对应的 tool_result
        // 这种孤立 tool_use 也可能导致 LLM 问题，应该一并移入保留区
        val compressEndIndex = messages.size - keepCount
        if (compressEndIndex > 0) {
            val lastCompressed = messages[compressEndIndex - 1]
            if (lastCompressed.role == MessageRole.ASSISTANT && lastCompressed.toolCalls.isNotEmpty()) {
                // 最后一条被压缩的消息包含 tool_use，检查是否有对应的 tool_result 在保留区
                val hasMatchingResult = messages
                    .subList(compressEndIndex, messages.size)
                    .any { it.role == MessageRole.TOOL }
                if (hasMatchingResult) {
                    // 有部分 tool_result 在保留区，tool_use 也应该保留
                    keepCount++
                }
            }
        }

        val toKeep = messages.takeLast(keepCount)
        val toCompress = messages.dropLast(keepCount)

        // P2: 语义重要性评分 — 从压缩区提升高分消息到保留区
        if (toCompress.isNotEmpty()) {
            val importanceThreshold = 0.6f
            val highImportanceMessages = toCompress.mapIndexedNotNull { idx, msg ->
                val score = scoreImportance(msg, idx, messages.size)
                if (score >= importanceThreshold) idx to msg else null
            }
            if (highImportanceMessages.isNotEmpty()) {
                // 将高分消息加入保留区（避免重复，最多提升 3 条）
                val promoted = highImportanceMessages
                    .sortedByDescending { scoreImportance(it.second, it.first, messages.size) }
                    .take(3)
                    .map { it.second }
                val mergedKeep = (promoted + toKeep).distinctBy { it.id }
                val mergedCompress = toCompress.filter { msg -> msg.id !in promoted.map { it.id }.toSet() }
                Timber.d("Context split: promoted ${promoted.size} high-importance messages from compress to keep")
                return mergedCompress to mergedKeep
            }
        }

        return toCompress to toKeep
    }

    /**
     * 生成压缩摘要的提示词
     */
    fun buildCompactionPrompt(messages: List<SessionMessage>): String {
        val messageText = messages.joinToString("\n") { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "用户"
                MessageRole.ASSISTANT -> "助手"
                MessageRole.SYSTEM -> "系统"
                MessageRole.TOOL -> "工具"
                MessageRole.PRIVATE -> "私聊"
            }
            "[$role]: ${msg.content.take(500)}"
        }

        return """请将以下对话压缩为结构化摘要，保留关键信息：

$messageText

请按以下格式输出：
## 目标
用户的主要目标和意图

## 关键决策
对话中做出的重要决定

## 进度
已完成的工作和待完成的工作

## 关键上下文
需要记住的重要信息"""
    }

    /**
     * 获取 token 统计信息
     */
    fun getTokenStats(messages: List<SessionMessage>): Map<String, Int> {
        val totalTokens = tokenEstimator.countMessages(messages)
        val userTokens = messages.filter { it.role == MessageRole.USER }
            .sumOf { tokenEstimator.countMessage(it) }
        val assistantTokens = messages.filter { it.role == MessageRole.ASSISTANT }
            .sumOf { tokenEstimator.countMessage(it) }
        val toolTokens = messages.filter { it.role == MessageRole.TOOL }
            .sumOf { tokenEstimator.countMessage(it) }

        return mapOf(
            "total" to totalTokens,
            "user" to userTokens,
            "assistant" to assistantTokens,
            "tool" to toolTokens,
            "max" to maxInputLength,
            "remaining" to (maxInputLength - totalTokens).coerceAtLeast(0)
        )
    }

    fun quickEstimateNeedsCompression(
        messageCount: Int,
        avgTokensPerMessage: Int = 200,
        modelContextWindow: Int? = null
    ): Boolean {
        val effectiveMax = modelContextWindow ?: maxInputLength
        val estimatedTokens = messageCount * avgTokensPerMessage
        val threshold = (effectiveMax * compactConfig.compactThresholdRatio).toInt()
        return estimatedTokens > threshold
    }
}

