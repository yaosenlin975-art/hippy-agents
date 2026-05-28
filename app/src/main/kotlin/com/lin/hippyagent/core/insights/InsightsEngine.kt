package com.lin.hippyagent.core.insights

import com.lin.hippyagent.core.agent.session.InsightsDao
import com.lin.hippyagent.core.agent.session.InsightsSessionRow
import com.lin.hippyagent.core.agent.session.HourlyActivityRow
import com.lin.hippyagent.core.agent.session.WeeklyActivityRow
import com.lin.hippyagent.core.agent.session.DailyTokenUsageRow
import com.lin.hippyagent.core.skill.BuiltinSkillNames
import com.lin.hippyagent.core.skill.SkillManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

data class InsightsPeriod(val days: Int, val label: String)

data class OverviewInsight(
    val totalSessions: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCacheReadTokens: Long,
    val totalCacheWriteTokens: Long,
    val estimatedCostUsd: Double,
    val avgTokensPerSession: Double,
    val completedSessions: Int,
    val failedSessions: Int
)

data class ModelUsage(
    val model: String,
    val sessionCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: Double
)

data class ToolUsage(
    val toolName: String,
    val callCount: Int
)

data class ActivityPattern(
    val hourly: List<HourlyActivityRow>,
    val weekly: List<WeeklyActivityRow>,
    val peakHour: Int,
    val peakWeekday: Int
)

data class TopSession(
    val id: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCostUsd: Double?
)

data class DailyTokenUsage(
    val date: String,
    val sessionCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long
)

data class InsightsReport(
    val overview: OverviewInsight,
    val modelBreakdown: List<ModelUsage>,
    val toolBreakdown: List<ToolUsage>,
    val activityPatterns: ActivityPattern,
    val topSessions: List<TopSession>,
    val dailyTokenUsage: List<DailyTokenUsage>,
    val period: InsightsPeriod,
    val generatedAt: Long,
    val skillBreakdown: List<ToolUsage> = emptyList(),
    val agentSessionCounts: List<AgentSessionCount> = emptyList()
)

data class AgentSessionCount(
    val agentId: String,
    val agentName: String,
    val sessionCount: Int
)

class InsightsEngine(
    private val insightsDao: InsightsDao,
    private val pricingEngine: PricingEngine,
    private val skillManager: SkillManager,
    private val agentRepository: com.lin.hippyagent.data.repository.AgentRepository
) {
    suspend fun generate(days: Int = 30): Result<InsightsReport> = withContext(Dispatchers.IO) {
        runCatching {
            val cutoff = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
            val period = InsightsPeriod(days, "${days}d")

            val sessions = insightsDao.getSessionsForInsights(cutoff)
            val toolRows = insightsDao.getToolUsageForInsights(cutoff)
            val hourly = insightsDao.getHourlyActivity(cutoff)
            val weekly = insightsDao.getWeeklyActivity(cutoff)
            val dailyTokens = insightsDao.getDailyTokenUsage(cutoff)
            val agentRows = insightsDao.getAgentSessionCounts(cutoff)

            val overview = buildOverview(sessions)
            val modelBreakdown = buildModelBreakdown(sessions)
            val toolBreakdown = buildToolBreakdown(toolRows)
            val skillBreakdown = buildSkillBreakdown(toolRows)
            val activityPatterns = buildActivityPatterns(hourly, weekly)
            val topSessions = buildTopSessions(sessions)
            val agentSessionCounts = buildAgentSessionCounts(agentRows)

            InsightsReport(
                overview = overview,
                modelBreakdown = modelBreakdown,
                toolBreakdown = toolBreakdown,
                activityPatterns = activityPatterns,
                topSessions = topSessions,
                dailyTokenUsage = dailyTokens.map { row ->
                    DailyTokenUsage(
                        date = row.date,
                        sessionCount = row.sessionCount,
                        inputTokens = row.inputTokens,
                        outputTokens = row.outputTokens,
                        totalTokens = row.totalTokens
                    )
                },
                period = period,
                generatedAt = System.currentTimeMillis(),
                skillBreakdown = skillBreakdown,
                agentSessionCounts = agentSessionCounts
            )
        }
    }

    private fun buildOverview(sessions: List<InsightsSessionRow>): OverviewInsight {
        val totalInput = sessions.sumOf { it.inputTokens.toLong() }
        val totalOutput = sessions.sumOf { it.outputTokens.toLong() }
        val totalCacheRead = sessions.sumOf { it.cacheReadTokens.toLong() }
        val totalCacheWrite = sessions.sumOf { it.cacheWriteTokens.toLong() }
        val totalCost = sessions.sumOf { it.estimatedCostUsd ?: 0.0 }
        val completed = sessions.count { it.status == "completed" }
        val failed = sessions.count { it.status == "failed" }

        return OverviewInsight(
            totalSessions = sessions.size,
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalCacheReadTokens = totalCacheRead,
            totalCacheWriteTokens = totalCacheWrite,
            estimatedCostUsd = totalCost,
            avgTokensPerSession = if (sessions.isNotEmpty()) (totalInput + totalOutput).toDouble() / sessions.size else 0.0,
            completedSessions = completed,
            failedSessions = failed
        )
    }

    private fun buildModelBreakdown(sessions: List<InsightsSessionRow>): List<ModelUsage> {
        return sessions.groupBy { it.model.ifBlank { "unknown" } }
            .map { (model, group) ->
                ModelUsage(
                    model = model,
                    sessionCount = group.size,
                    inputTokens = group.sumOf { it.inputTokens.toLong() },
                    outputTokens = group.sumOf { it.outputTokens.toLong() },
                    estimatedCostUsd = group.sumOf { it.estimatedCostUsd ?: pricingEngine.estimateCost(model, it.inputTokens.toLong(), it.outputTokens.toLong()).costUsd }
                )
            }
            .sortedByDescending { it.estimatedCostUsd }
    }

    private fun buildToolBreakdown(toolRows: List<com.lin.hippyagent.core.agent.session.ToolUsageRow>): List<ToolUsage> {
        val toolCounts = mutableMapOf<String, Int>()
        for (row in toolRows) {
            val json = row.toolCallsJson
            val toolNames = extractToolNames(json)
            for (name in toolNames) {
                toolCounts[name] = (toolCounts[name] ?: 0) + 1
            }
        }
        return toolCounts.map { (name, count) ->
            ToolUsage(toolName = name, callCount = count)
        }.sortedByDescending { it.callCount }
    }

    private fun buildSkillBreakdown(toolRows: List<com.lin.hippyagent.core.agent.session.ToolUsageRow>): List<ToolUsage> {
        val skillCounts = mutableMapOf<String, Int>()
        for (row in toolRows) {
            val json = row.toolCallsJson
            val skillNames = extractSkillNames(json)
            for (name in skillNames) {
                skillCounts[name] = (skillCounts[name] ?: 0) + 1
            }
        }
        return skillCounts.map { (id, count) ->
            val displayName = BuiltinSkillNames.getDisplayName(id)
            ToolUsage(toolName = displayName, callCount = count)
        }.sortedByDescending { it.callCount }
    }

    private fun buildActivityPatterns(
        hourly: List<HourlyActivityRow>,
        weekly: List<WeeklyActivityRow>
    ): ActivityPattern {
        val peakHour = hourly.maxByOrNull { it.count }?.hour ?: 12
        val peakWeekday = weekly.maxByOrNull { it.count }?.weekday ?: 1
        return ActivityPattern(hourly, weekly, peakHour, peakWeekday)
    }

    private fun buildTopSessions(sessions: List<InsightsSessionRow>): List<TopSession> {
        return sessions.sortedByDescending { it.inputTokens + it.outputTokens }
            .take(10)
            .map { s ->
                TopSession(
                    id = s.id,
                    model = s.model,
                    inputTokens = s.inputTokens,
                    outputTokens = s.outputTokens,
                    estimatedCostUsd = s.estimatedCostUsd
                )
            }
    }

    private suspend fun buildAgentSessionCounts(rows: List<com.lin.hippyagent.core.agent.session.AgentSessionCountRow>): List<AgentSessionCount> {
        return rows.mapNotNull { row ->
            val name = runCatching { agentRepository.getAgentById(row.agentId)?.name }.getOrNull() ?: return@mapNotNull null
            AgentSessionCount(agentId = row.agentId, agentName = name, sessionCount = row.sessionCount)
        }
    }

    private fun extractToolNames(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return TOOL_NAME_REGEX.findAll(json).map { it.groupValues[1] }.toList()
    }

    private fun extractSkillNames(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return SKILL_NAME_REGEX.findAll(json).map { it.groupValues[1] }.toList()
    }

    companion object {
        private val TOOL_NAME_REGEX = Regex(""""name"\s*:\s*"([^"]+)"""")
        private val SKILL_NAME_REGEX = Regex(""""name"\s*:\s*"load_skill".*?\\?"skill_name\\?"\s*:\s*\\?"([^"\\]+)""")
    }
}

