package com.lin.hippyagent.core.memory.compaction

import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionMessage
import com.lin.hippyagent.core.pool.StringBuilderPool
import timber.log.Timber

/**
 * 迭代式摘要合并器 - 将已有压缩摘要与新消息进行迭代合并，
 * 而非简单拼接，以保持上下文连贯性。
 */
class IterativeSummaryMerger(
    private val maxSummaryTokens: Int = 2000
) {
    private val sbPool = StringBuilderPool()

    /**
     * 将已有压缩摘要与新消息迭代合并。
     *
     * 策略：
     * 1. 从已有摘要中提取结构化段落（目标、决策、进度、上下文）
     * 2. 从新消息中提取关键信息
     * 3. 按优先级合并，确保不超出 token 预算
     *
     * @param existingSummary 已有的压缩摘要
     * @param newMessages 新增的消息列表
     * @return 合并后的摘要文本
     */
    fun merge(existingSummary: String, newMessages: List<SessionMessage>): String {
        if (newMessages.isEmpty()) return existingSummary
        if (existingSummary.isBlank()) return summarizeMessages(newMessages)

        val existingSections = parseSections(existingSummary)
        val newInformation = extractKeyInformation(newMessages)

        val merged = mergeSections(existingSections, newInformation)
        val result = formatSections(merged)

        return if (estimateTokens(result) > maxSummaryTokens) {
            Timber.w("Merged summary exceeds token budget (${estimateTokens(result)} > $maxSummaryTokens), truncating")
            truncateToBudget(merged)
        } else {
            result
        }
    }

    fun mergeWithNewSummary(existingSummary: String, newSummary: String): String {
        if (newSummary.isBlank()) return existingSummary
        if (existingSummary.isBlank()) return newSummary

        val existingSections = parseSections(existingSummary)
        val newSections = parseSections(newSummary)

        val merged = mergeSections(existingSections, newSections)
        val result = formatSections(merged)

        return if (estimateTokens(result) > maxSummaryTokens) {
            truncateToBudget(merged)
        } else {
            result
        }
    }

    /**
     * 按类型分配 token 预算。
     *
     * @param totalTokens 总 token 预算
     * @param systemTokens 系统提示占比
     * @param memoryTokens 记忆占比
     * @param conversationTokens 对话占比
     * @param toolTokens 工具输出占比
     * @return 分配后的 [TokenBudget]
     */
    fun allocateBudget(
        totalTokens: Int,
        systemTokens: Float = 0.2f,
        memoryTokens: Float = 0.2f,
        conversationTokens: Float = 0.4f,
        toolTokens: Float = 0.2f
    ): TokenBudget {
        // 确保占比之和为 1.0，按比例归一化
        val totalRatio = systemTokens + memoryTokens + conversationTokens + toolTokens
        val normalizedSystem = systemTokens / totalRatio
        val normalizedMemory = memoryTokens / totalRatio
        val normalizedConversation = conversationTokens / totalRatio
        val normalizedTool = toolTokens / totalRatio

        return TokenBudget(
            system = (totalTokens * normalizedSystem).toInt(),
            memory = (totalTokens * normalizedMemory).toInt(),
            conversation = (totalTokens * normalizedConversation).toInt(),
            tool = (totalTokens * normalizedTool).toInt()
        )
    }

    // ── 内部实现 ──────────────────────────────────────────

    /**
     * 解析摘要中的结构化段落。
     */
    private fun parseSections(summary: String): MutableMap<String, String> = sbPool.use { currentContent ->
        val sections = linkedMapOf<String, String>()
        var currentKey = "general"

        for (line in summary.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("## ") -> {
                    if (currentContent.isNotEmpty()) {
                        sections[currentKey] = currentContent.toString().trim()
                        currentContent.clear()
                    }
                    currentKey = trimmed.removePrefix("## ").trim()
                }
                trimmed.startsWith("### ") -> {
                    if (currentContent.isNotEmpty()) {
                        sections[currentKey] = currentContent.toString().trim()
                        currentContent.clear()
                    }
                    currentKey = trimmed.removePrefix("### ").trim()
                }
                else -> {
                    if (trimmed.isNotEmpty()) {
                        currentContent.appendLine(trimmed)
                    }
                }
            }
        }

        if (currentContent.isNotEmpty()) {
            sections[currentKey] = currentContent.toString().trim()
        }

        sections
    }

    /**
     * 从新消息中提取关键信息。
     */
    private fun extractKeyInformation(messages: List<SessionMessage>): Map<String, String> {
        val information = linkedMapOf<String, String>()

        val userMessages = messages.filter { it.role == MessageRole.USER }
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }
        val toolMessages = messages.filter { it.role == MessageRole.TOOL }

        // 提取用户意图
        if (userMessages.isNotEmpty()) {
            val intentSummary = userMessages.joinToString("; ") { msg ->
                msg.content.take(200).trim()
            }
            information["用户意图"] = intentSummary
        }

        // 提取助手回复要点
        if (assistantMessages.isNotEmpty()) {
            val responseSummary = assistantMessages.joinToString("; ") { msg ->
                extractKeyPoints(msg.content)
            }
            information["助手回复"] = responseSummary
        }

        // 提取工具使用情况
        if (toolMessages.isNotEmpty()) {
            val toolSummary = "${toolMessages.size} 次工具调用"
            information["工具使用"] = toolSummary
        }

        return information
    }

    /**
     * 合并已有段落和新信息。
     */
    private fun mergeSections(
        existing: MutableMap<String, String>,
        newInfo: Map<String, String>
    ): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        merged.putAll(existing)

        for ((key, value) in newInfo) {
            val existingValue = merged[key]
            if (existingValue == null) {
                merged[key] = value
            } else {
                // 迭代合并：将新信息追加到已有段落，但限制长度
                val maxSectionTokens = maxSummaryTokens / (merged.size.coerceAtLeast(1))
                val maxSectionChars = maxSectionTokens * 4
                val combined = "$existingValue\n$value"
                merged[key] = if (combined.length > maxSectionChars) {
                    buildString { append(combined.take(maxSectionChars)); append("\n...") }
                } else {
                    combined
                }
            }
        }

        return merged
    }

    /**
     * 格式化段落为摘要文本。
     */
    private fun formatSections(sections: Map<String, String>): String {
        return sections.entries.joinToString("\n\n") { (key, value) ->
            "## $key\n$value"
        }
    }

    /**
     * 截断摘要到 token 预算内。
     */
    private fun truncateToBudget(sections: Map<String, String>): String = sbPool.use { result ->
        val budget = maxSummaryTokens * 4
        var currentLength = 0

        for ((key, value) in sections) {
            val header = "## $key\n"
            if (currentLength + header.length + value.length > budget) {
                val remaining = budget - currentLength - header.length - 3
                if (remaining > 0) {
                    result.append(header)
                    result.append(value.take(remaining))
                    result.append("...")
                }
                break
            }
            result.append(header)
            result.append(value)
            result.append("\n\n")
            currentLength += header.length + value.length + 2
        }

        result.toString().trim()
    }

    /**
     * 从文本中提取关键点（取前几句）。
     */
    private fun extractKeyPoints(content: String): String {
        val sentences = content.split(Regex("[。！？\\n]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
        return if (sentences.isNotEmpty()) {
            sentences.joinToString("。") + "。"
        } else {
            content.take(100)
        }
    }

    /**
     * 简单摘要：将消息列表压缩为摘要文本。
     */
    private fun summarizeMessages(messages: List<SessionMessage>): String = sbPool.use { sb ->
        sb.appendLine("## 对话摘要")

        val userMessages = messages.filter { it.role == MessageRole.USER }
        val assistantMessages = messages.filter { it.role == MessageRole.ASSISTANT }

        if (userMessages.isNotEmpty()) {
            sb.appendLine("### 用户请求")
            sb.appendLine(userMessages.takeLast(3).joinToString("\n") { "- ${it.content.take(200)}" })
        }

        if (assistantMessages.isNotEmpty()) {
            sb.appendLine("### 助手回复")
            sb.appendLine(assistantMessages.takeLast(3).joinToString("\n") { "- ${extractKeyPoints(it.content)}" })
        }

        sb.appendLine("### 统计")
        sb.appendLine("- 总消息数: ${messages.size}")
        sb.appendLine("- 用户消息: ${userMessages.size}")
        sb.appendLine("- 助手消息: ${assistantMessages.size}")

        sb.toString()
    }

    /**
     * 粗略估算 token 数（1 token ≈ 4 个字符）。
     */
    private fun estimateTokens(text: String): Int {
        return text.length / 4
    }
}

/**
 * Token 预算分配结果。
 */
data class TokenBudget(
    val system: Int,
    val memory: Int,
    val conversation: Int,
    val tool: Int
) {
    val total: Int get() = system + memory + conversation + tool
}

