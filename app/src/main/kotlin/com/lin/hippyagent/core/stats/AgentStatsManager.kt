package com.lin.hippyagent.core.stats

import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.model.TokenUsageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId

data class DetailedAgentStats(
    val totalSessions: Int,
    val totalMessages: Int,
    val userMessages: Int,
    val agentMessages: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val llmCalls: Long,
    val toolCalls: Long
)

data class SessionChannelStats(
    val channelId: String,
    val sessionCount: Int
)

data class MessageChannelStats(
    val channelId: String,
    val messageCount: Int
)

data class TrendPoint(
    val date: String,
    val value: Long
)

data class TrendData(
    val userMessages: List<TrendPoint>,
    val agentMessages: List<TrendPoint>,
    val sessions: List<TrendPoint>,
    val inputTokens: List<TrendPoint>,
    val outputTokens: List<TrendPoint>,
    val llmCalls: List<TrendPoint>,
    val toolCalls: List<TrendPoint>
)

class AgentStatsManager(
    private val sessionStore: SessionStore,
    private val tokenUsageManager: TokenUsageManager
) {
    suspend fun getStats(
        agentId: String? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null
    ): Result<DetailedAgentStats> = withContext(Dispatchers.IO) {
        runCatching {
            val sessions = if (agentId != null) {
                sessionStore.getSessionsForAgent(agentId).getOrDefault(emptyList())
            } else {
                sessionStore.getAllSessions().getOrDefault(emptyList())
            }

            val filteredSessions = filterByDate(sessions, startDate, endDate)

            var totalMessages = 0
            var userMessages = 0
            var agentMessages = 0
            var toolCalls = 0L

            for (session in filteredSessions) {
                val msgs = sessionStore.getMessages(session.id, limit = Int.MAX_VALUE)
                    .getOrDefault(emptyList())
                totalMessages += msgs.size
                userMessages += msgs.count { it.role == MessageRole.USER }
                agentMessages += msgs.count { it.role == MessageRole.ASSISTANT }
                toolCalls += msgs.sumOf { it.toolCalls.size.toLong() }
            }

            val tokenSummary = tokenUsageManager.getSummary(
                agentId = agentId,
                startDate = startDate,
                endDate = endDate
            )

            DetailedAgentStats(
                totalSessions = filteredSessions.size,
                totalMessages = totalMessages,
                userMessages = userMessages,
                agentMessages = agentMessages,
                inputTokens = tokenSummary.totalInputTokens,
                outputTokens = tokenSummary.totalOutputTokens,
                llmCalls = tokenSummary.totalCalls,
                toolCalls = toolCalls
            )
        }
    }

    suspend fun getSessionChannelStats(
        agentId: String? = null
    ): Result<List<SessionChannelStats>> = withContext(Dispatchers.IO) {
        runCatching {
            val sessions = if (agentId != null) {
                sessionStore.getSessionsForAgent(agentId).getOrDefault(emptyList())
            } else {
                sessionStore.getAllSessions().getOrDefault(emptyList())
            }

            val grouped = sessions.groupBy { it.agentId }
            grouped.map { (channelId, list) ->
                SessionChannelStats(channelId = channelId, sessionCount = list.size)
            }.sortedByDescending { it.sessionCount }
        }
    }

    suspend fun getMessageChannelStats(
        agentId: String? = null
    ): Result<List<MessageChannelStats>> = withContext(Dispatchers.IO) {
        runCatching {
            val sessions = if (agentId != null) {
                sessionStore.getSessionsForAgent(agentId).getOrDefault(emptyList())
            } else {
                sessionStore.getAllSessions().getOrDefault(emptyList())
            }

            val messageCounts = mutableMapOf<String, Int>()
            for (session in sessions) {
                val msgs = sessionStore.getMessages(session.id, limit = Int.MAX_VALUE)
                    .getOrDefault(emptyList())
                messageCounts[session.agentId] = (messageCounts[session.agentId] ?: 0) + msgs.size
            }

            messageCounts.map { (channelId, count) ->
                MessageChannelStats(channelId = channelId, messageCount = count)
            }.sortedByDescending { it.messageCount }
        }
    }

    suspend fun getTrends(
        agentId: String? = null,
        days: Int = 30
    ): Result<TrendData> = withContext(Dispatchers.IO) {
        runCatching {
            val endDate = LocalDate.now()
            val startDate = endDate.minusDays(days.toLong())
            val zoneId = ZoneId.systemDefault()

            val sessions = if (agentId != null) {
                sessionStore.getSessionsForAgent(agentId).getOrDefault(emptyList())
            } else {
                sessionStore.getAllSessions().getOrDefault(emptyList())
            }

            val dateRange = generateSequence(startDate) { it.plusDays(1) }
                .takeWhile { !it.isAfter(endDate) }
                .toList()

            val userMsgByDate = mutableMapOf<LocalDate, Long>()
            val agentMsgByDate = mutableMapOf<LocalDate, Long>()
            val sessionsByDate = mutableMapOf<LocalDate, Long>()
            val toolCallsByDate = mutableMapOf<LocalDate, Long>()

            for (session in sessions) {
                val createdDate = session.createdAt.atZone(zoneId).toLocalDate()
                if (createdDate in startDate..endDate) {
                    sessionsByDate[createdDate] = (sessionsByDate[createdDate] ?: 0L) + 1
                }

                val msgs = sessionStore.getMessages(session.id, limit = Int.MAX_VALUE)
                    .getOrDefault(emptyList())
                for (msg in msgs) {
                    val msgDate = msg.timestamp.atZone(zoneId).toLocalDate()
                    if (msgDate in startDate..endDate) {
                        when (msg.role) {
                            MessageRole.USER -> userMsgByDate[msgDate] = (userMsgByDate[msgDate] ?: 0L) + 1
                            MessageRole.ASSISTANT -> agentMsgByDate[msgDate] = (agentMsgByDate[msgDate] ?: 0L) + 1
                            else -> {}
                        }
                        toolCallsByDate[msgDate] = (toolCallsByDate[msgDate] ?: 0L) + msg.toolCalls.size
                    }
                }
            }

            val tokenTrends = tokenUsageManager.getDailyTrends(agentId, days)

            TrendData(
                userMessages = dateRange.map { TrendPoint(it.toString(), userMsgByDate[it] ?: 0L) },
                agentMessages = dateRange.map { TrendPoint(it.toString(), agentMsgByDate[it] ?: 0L) },
                sessions = dateRange.map { TrendPoint(it.toString(), sessionsByDate[it] ?: 0L) },
                inputTokens = tokenTrends.inputTokens,
                outputTokens = tokenTrends.outputTokens,
                llmCalls = tokenTrends.llmCalls,
                toolCalls = dateRange.map { TrendPoint(it.toString(), toolCallsByDate[it] ?: 0L) }
            )
        }
    }

    private fun filterByDate(
        sessions: List<com.lin.hippyagent.core.agent.session.Session>,
        startDate: LocalDate?,
        endDate: LocalDate?
    ): List<com.lin.hippyagent.core.agent.session.Session> {
        if (startDate == null && endDate == null) return sessions
        val zoneId = ZoneId.systemDefault()
        return sessions.filter { session ->
            val date = session.createdAt.atZone(zoneId).toLocalDate()
            (startDate == null || date >= startDate) && (endDate == null || date <= endDate)
        }
    }
}

