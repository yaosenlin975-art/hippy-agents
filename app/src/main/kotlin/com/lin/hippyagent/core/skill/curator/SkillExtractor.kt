package com.lin.hippyagent.core.skill.curator

import timber.log.Timber

/**
 * 技能提取器 — 从执行历史中提取可复用的技能模式
 *
 * 参考: Hermes curator/extractor.py — SkillExtractor.extract()
 */
class SkillExtractor {

    /** 从执行历史提取技能 */
    fun extract(history: ExecutionHistory): CuratorSkillManifest? {
        if (!isExtractable(history)) return null

        val toolSequence = history.tools
        val keywords = extractKeywords(history.query)

        val workflowSteps = toolSequence.mapIndexed { i, call ->
            CuratorWorkflowStep(
                order = i + 1,
                tool = call.toolName,
                description = generateStepDescription(call),
                parameterTemplate = extractParameterPattern(call.arguments)
            )
        }

        val name = generateSkillName(history.query, toolSequence)
        val description = generateDescriptionHeuristic(history)

        return CuratorSkillManifest(
            name = name,
            description = description,
            triggers = CuratorTriggerCondition(
                keywords = keywords,
                toolPattern = toolSequence.map { it.toolName }
            ),
            tools = toolSequence.map { CuratorToolBinding(name = it.toolName) },
            workflow = workflowSteps,
            confidence = 0.5f
        )
    }

    /** 判断是否可提取 */
    fun isExtractable(history: ExecutionHistory): Boolean {
        return history.success &&
               history.tools.size >= 2 &&
               !history.isOneOff &&
               history.durationMs > 5_000
    }

    /** 从查询中提取关键词 */
    private fun extractKeywords(query: String): List<String> {
        val stopWords = setOf("请", "帮我", "的", "了", "在", "把", "被", "是", "有", "和", "与", "或",
            "please", "help", "need", "want", "can", "could", "would")
        return query.split(Regex("[\\s，。！？,.!?，。！？、：；（）()【】\\[\\]{}]+"))
            .filter { it.length > 1 && it.lowercase() !in stopWords }
            .distinct()
            .take(5)
    }

    /** 启发式生成技能描述 */
    private fun generateDescriptionHeuristic(history: ExecutionHistory): String {
        val tools = history.tools.map { it.toolName }.distinct()
        val query = history.query.take(80)
        return "自动技能: 执行「$query」，使用工具 [${tools.joinToString(", ")}]"
    }

    /** 生成技能名称 */
    private fun generateSkillName(query: String, tools: List<ToolCallRecord>): String {
        val queryPrefix = query.take(24).trim().replace(Regex("[\\s，。！？,.!?]+"), "_")
        val primaryTool = tools.firstOrNull()?.toolName ?: "auto"
        val safeName = "${queryPrefix}_${primaryTool}"
            .replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fff]"), "")
            .take(48)
        return safeName.ifBlank { "auto_${com.lin.hippyagent.core.pool.FastId.nextShort()}" }
    }

    /** 提取参数模式 */
    private fun extractParameterPattern(args: Map<String, Any>): Map<String, String> {
        return args.mapValues { (key, _) ->
            "\${$key}"
        }
    }

    /** 生成步骤描述 */
    private fun generateStepDescription(call: ToolCallRecord): String {
        val args = call.arguments.entries.joinToString(", ") { (k, v) ->
            "$k=${v.toString().take(40)}"
        }
        return "调用 ${call.toolName}($args)"
    }
}
