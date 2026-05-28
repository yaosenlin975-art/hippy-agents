package com.lin.hippyagent.core.stats

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 智能体统计数据
 */
@Serializable
data class AgentStats(
    val agentId: String,
    val totalSessions: Int = 0,
    val totalMessages: Int = 0,
    val totalTokensUsed: Long = 0,
    val totalApiCalls: Int = 0,
    val totalToolCalls: Int = 0,
    val totalToolErrors: Int = 0,
    val avgResponseTimeMs: Long = 0,
    val firstUsedAt: String? = null,
    val lastUsedAt: String? = null,
    val successRate: Float = 0f,
    val favoriteTools: Map<String, Int> = emptyMap()
)

/**
 * 全局统计
 */
@Serializable
data class GlobalStats(
    val totalAgents: Int = 0,
    val totalSessions: Int = 0,
    val totalMessages: Int = 0,
    val totalTokensUsed: Long = 0,
    val totalApiCalls: Int = 0,
    val totalToolCalls: Int = 0,
    val totalToolErrors: Int = 0,
    val totalMissions: Int = 0,
    val completedMissions: Int = 0,
    val totalDreams: Int = 0,
    val optimizedMemories: Int = 0
)

/**
 * 统计记录器
 */
@Serializable
data class StatRecord(
    val timestamp: String,
    val agentId: String,
    val eventType: StatEventType,
    val details: Map<String, String> = emptyMap()
)

enum class StatEventType {
    SESSION_START,
    SESSION_END,
    MESSAGE_SENT,
    MESSAGE_RECEIVED,
    TOOL_CALL,
    TOOL_ERROR,
    MISSION_START,
    MISSION_COMPLETE,
    DREAM_COMPLETE
}

/**
 * 智能体统计管理器
 */
class StatsManager(
    private val context: Context
) {
    private val statsDir by lazy {
        File(context.filesDir, "stats").apply { mkdirs() }
    }

    private val recordsDir by lazy {
        File(statsDir, "records").apply { mkdirs() }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * 记录事件
     */
    suspend fun recordEvent(
        agentId: String,
        eventType: StatEventType,
        details: Map<String, String> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val record = StatRecord(
                timestamp = Instant.now().toString(),
                agentId = agentId,
                eventType = eventType,
                details = details
            )

            val dateStr = DateTimeFormatter.ISO_LOCAL_DATE.format(
                java.time.LocalDateTime.now()
            )
            val recordFile = File(recordsDir, "${agentId}_$dateStr.jsonl")
            recordFile.appendText(json.encodeToString(record) + "\n")
        }.onFailure { e ->
            Timber.e(e, "Failed to record event: $eventType")
        }
    }

    /**
     * 获取智能体统计
     */
    suspend fun getAgentStats(agentId: String): AgentStats = withContext(Dispatchers.IO) {
        val statsFile = File(statsDir, "${agentId}_stats.json")

        if (statsFile.exists()) {
            try {
                json.decodeFromString<AgentStats>(statsFile.readText())
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse stats for $agentId")
                AgentStats(agentId = agentId)
            }
        } else {
            AgentStats(agentId = agentId)
        }
    }

    /**
     * 更新智能体统计
     */
    suspend fun updateAgentStats(agentId: String, update: AgentStats.() -> AgentStats) = withContext(Dispatchers.IO) {
        runCatching {
            val currentStats = getAgentStats(agentId)
            val updatedStats = currentStats.update()
            val statsFile = File(statsDir, "${agentId}_stats.json")
            statsFile.writeText(json.encodeToString(updatedStats))
        }.onFailure { e ->
            Timber.e(e, "Failed to update stats for $agentId")
        }
    }

    /**
     * 获取全局统计
     */
    suspend fun getGlobalStats(): GlobalStats = withContext(Dispatchers.IO) {
        val agentFiles = statsDir.listFiles { f -> f.name.endsWith("_stats.json") } ?: emptyArray()
        val agentStats = agentFiles.mapNotNull { f ->
            runCatching {
                json.decodeFromString<AgentStats>(f.readText())
            }.getOrNull()
        }

        GlobalStats(
            totalAgents = agentStats.size,
            totalSessions = agentStats.sumOf { it.totalSessions },
            totalMessages = agentStats.sumOf { it.totalMessages },
            totalTokensUsed = agentStats.sumOf { it.totalTokensUsed },
            totalApiCalls = agentStats.sumOf { it.totalApiCalls },
            totalToolCalls = agentStats.sumOf { it.totalToolCalls },
            totalToolErrors = agentStats.sumOf { it.totalToolErrors }
        )
    }

    /**
     * 获取最近事件记录
     */
    suspend fun getRecentEvents(agentId: String, limit: Int = 50): List<StatRecord> = withContext(Dispatchers.IO) {
        val records = mutableListOf<StatRecord>()

        val recordFiles = recordsDir.listFiles { f -> f.name.startsWith("${agentId}_") }
            ?.sortedByDescending { it.name }
            ?.take(7) // 最近 7 天
            ?: emptyList()

        for (file in recordFiles) {
            try {
                file.readLines().forEach { line ->
                    if (line.isNotBlank()) {
                        records.add(json.decodeFromString<StatRecord>(line))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse records from ${file.name}")
            }
        }

        records.sortedByDescending { it.timestamp }.take(limit)
    }

    /**
     * 清除统计
     */
    suspend fun clearStats(agentId: String? = null) = withContext(Dispatchers.IO) {
        if (agentId != null) {
            File(statsDir, "${agentId}_stats.json").delete()
            recordsDir.listFiles { f -> f.name.startsWith("${agentId}_") }
                ?.forEach { it.delete() }
        } else {
            statsDir.deleteRecursively()
            statsDir.mkdirs()
            recordsDir.mkdirs()
        }
    }
}

