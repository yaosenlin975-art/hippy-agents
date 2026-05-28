package com.lin.hippyagent.core.agent.session

import androidx.room.Dao
import androidx.room.Query

@Dao
interface InsightsDao {
    @Query("""
        SELECT s.id, s.model, s.status,
               COALESCE(st.inputTokens, 0) as inputTokens, COALESCE(st.outputTokens, 0) as outputTokens,
               COALESCE(st.cacheReadTokens, 0) as cacheReadTokens, COALESCE(st.cacheWriteTokens, 0) as cacheWriteTokens,
               st.estimatedCostUsd, s.createdAt, st.finishedAt
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        WHERE (st.finishedAt >= :cutoff OR (st.finishedAt IS NULL AND s.createdAt >= :cutoff))
    """)
    suspend fun getSessionsForInsights(cutoff: Long): List<InsightsSessionRow>

    @Query("""
        SELECT m.toolCallsJson
        FROM messages m
        INNER JOIN sessions s ON m.sessionId = s.id
        WHERE (s.createdAt >= :cutoff)
        AND m.toolCallsJson IS NOT NULL
        AND m.toolCallsJson != '[]'
        AND m.toolCallsJson != ''
    """)
    suspend fun getToolUsageForInsights(cutoff: Long): List<ToolUsageRow>

    @Query("""
        SELECT CAST(strftime('%H', datetime(COALESCE(st.finishedAt, s.createdAt)/1000, 'unixepoch', 'localtime')) AS INTEGER) as hour,
               COUNT(*) as count,
               SUM(COALESCE(st.inputTokens, 0) + COALESCE(st.outputTokens, 0)) as tokens
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        WHERE (st.finishedAt >= :cutoff OR (st.finishedAt IS NULL AND s.createdAt >= :cutoff))
        GROUP BY hour
        ORDER BY hour
    """)
    suspend fun getHourlyActivity(cutoff: Long): List<HourlyActivityRow>

    @Query("""
        SELECT CAST(strftime('%w', datetime(COALESCE(st.finishedAt, s.createdAt)/1000, 'unixepoch', 'localtime')) AS INTEGER) as weekday,
               COUNT(*) as count,
               SUM(COALESCE(st.inputTokens, 0) + COALESCE(st.outputTokens, 0)) as tokens
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        WHERE (st.finishedAt >= :cutoff OR (st.finishedAt IS NULL AND s.createdAt >= :cutoff))
        GROUP BY weekday
        ORDER BY weekday
    """)
    suspend fun getWeeklyActivity(cutoff: Long): List<WeeklyActivityRow>

    @Query("""
        SELECT strftime('%Y-%m-%d', datetime(COALESCE(st.finishedAt, s.createdAt)/1000, 'unixepoch', 'localtime')) as date,
               COUNT(*) as sessionCount,
               SUM(COALESCE(st.inputTokens, 0)) as inputTokens,
               SUM(COALESCE(st.outputTokens, 0)) as outputTokens,
               SUM(COALESCE(st.inputTokens, 0) + COALESCE(st.outputTokens, 0)) as totalTokens
        FROM sessions s
        LEFT JOIN session_stats st ON s.id = st.sessionId
        WHERE (st.finishedAt >= :cutoff OR (st.finishedAt IS NULL AND s.createdAt >= :cutoff))
        GROUP BY date
        ORDER BY date
    """)
    suspend fun getDailyTokenUsage(cutoff: Long): List<DailyTokenUsageRow>

    @Query("""
        SELECT agentId, COUNT(*) as sessionCount
        FROM sessions
        WHERE createdAt >= :cutoff
        GROUP BY agentId
        ORDER BY sessionCount DESC
    """)
    suspend fun getAgentSessionCounts(cutoff: Long): List<AgentSessionCountRow>
}

data class InsightsSessionRow(
    val id: String,
    val model: String,
    val status: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val estimatedCostUsd: Double?,
    val createdAt: Long,
    val finishedAt: Long?
)

data class ToolUsageRow(
    val toolCallsJson: String
)

data class HourlyActivityRow(val hour: Int, val count: Int, val tokens: Int?)

data class WeeklyActivityRow(val weekday: Int, val count: Int, val tokens: Int?)

data class DailyTokenUsageRow(
    val date: String,
    val sessionCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long
)

data class AgentSessionCountRow(
    val agentId: String,
    val sessionCount: Int
)
