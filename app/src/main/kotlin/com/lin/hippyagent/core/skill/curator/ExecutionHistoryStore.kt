package com.lin.hippyagent.core.skill.curator

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * 执行历史存储 — 持久化 Agent 执行轨迹供 Curator 分析
 *
 * 参考: Hermes curator/store.py
 * 存储方式: JSON Lines 文件（每个执行历史一行 JSON），无需 Room 迁移
 */
class ExecutionHistoryStore(
    private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val writeMutex = Mutex()

    private val historyDir: File by lazy {
        File(context.filesDir, "curator/history").apply { mkdirs() }
    }

    /**
     * 保存执行历史
     */
    suspend fun save(history: ExecutionHistory) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = getDailyFile()
                val serializable = history.toSerializable()
                file.appendText(json.encodeToString(serializable) + "\n")
            }.onFailure { e ->
                Timber.w(e, "Failed to save execution history")
            }
        }
    }

    /**
     * 获取近期成功执行
     */
    suspend fun getRecentSuccessful(limit: Int = 50, sinceHours: Int = 24): List<ExecutionHistory> {
        val cutoffTime = System.currentTimeMillis() - (sinceHours * 60 * 60 * 1000L)
        val histories = mutableListOf<ExecutionHistory>()

        // 从今天的文件和昨天的文件读取
        val files = listOf(getDailyFile(0), getDailyFile(-1))
        for (file in files) {
            if (!file.exists()) continue
            try {
                file.readLines().forEach { line ->
                    if (line.isNotBlank()) {
                        runCatching {
                            val serializable = json.decodeFromString<ExecutionHistorySerializable>(line)
                            val history = serializable.toDomain()
                            if (history.success && history.createdAt >= cutoffTime) {
                                histories.add(history)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to read history file: ${file.name}")
            }
        }

        return histories.sortedByDescending { it.createdAt }.take(limit)
    }

    /**
     * 获取所有自动生成的技能（用于合并检查）
     */
    suspend fun getAllAutoHistories(limit: Int = 200): List<ExecutionHistory> {
        // 最近 30 天
        val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        val histories = mutableListOf<ExecutionHistory>()

        historyDir.listFiles()
            ?.filter { it.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(30) // 最多 30 个文件
            ?.forEach { file ->
                try {
                    file.readLines().forEach { line ->
                        if (line.isNotBlank()) {
                            runCatching {
                                val serializable = json.decodeFromString<ExecutionHistorySerializable>(line)
                                val history = serializable.toDomain()
                                if (history.createdAt >= cutoffTime) {
                                    histories.add(history)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to read history file: ${file.name}")
                }
            }

        return histories.sortedByDescending { it.createdAt }.take(limit)
    }

    /**
     * 清理过期记录
     */
    suspend fun pruneOldRecords(daysOld: Int = 7): Int {
        val cutoffTime = System.currentTimeMillis() - (daysOld.toLong() * 24 * 60 * 60 * 1000)
        var prunedCount = 0

        historyDir.listFiles()
            ?.filter { it.name.endsWith(".jsonl") }
            ?.forEach { file ->
                try {
                    val lines = file.readLines()
                    val newLines = lines.filter { line ->
                        if (line.isNotBlank()) {
                            runCatching {
                                val s = json.decodeFromString<ExecutionHistorySerializable>(line)
                                s.createdAt >= cutoffTime
                            }.getOrDefault(true)
                        } else true
                    }
                    if (newLines.size < lines.size) {
                        file.writeText(newLines.joinToString("\n") + "\n")
                        prunedCount += lines.size - newLines.size
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to prune history file: ${file.name}")
                }
            }

        return prunedCount
    }

    /**
     * 获取工具使用统计
     */
    suspend fun getToolUsageStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        val recent = getRecentSuccessful(limit = 200, sinceHours = 24 * 7)
        recent.forEach { history ->
            history.tools.forEach { tool ->
                stats[tool.toolName] = (stats[tool.toolName] ?: 0) + 1
            }
        }
        return stats
    }

    /**
     * 获取错误模式
     */
    suspend fun getErrorPatterns(): List<ErrorPattern> {
        val patterns = mutableMapOf<String, MutableMap<String, Int>>()
        val allHistories = getAllAutoHistories(limit = 200)
        allHistories.filter { !it.success }.forEach { history ->
            history.tools.filter { !it.success }.forEach { tool ->
                val toolErrors = patterns.getOrPut(tool.toolName) { mutableMapOf() }
                val errorKey = (tool.result?.take(80) ?: "unknown")
                toolErrors[errorKey] = (toolErrors[errorKey] ?: 0) + 1
            }
        }
        return patterns.flatMap { (toolName, errors) ->
            errors.map { (errorMsg, freq) ->
                ErrorPattern(toolName = toolName, errorMessage = errorMsg, frequency = freq)
            }
        }.sortedByDescending { it.frequency }
    }

    /**
     * 统计近期某个技能的使用次数
     */
    suspend fun countRecentUsage(skillId: String, days: Int = 7): Int {
        val recent = getRecentSuccessful(limit = 500, sinceHours = days * 24)
        return recent.count { it.query.contains(skillId, ignoreCase = true) }
    }

    /**
     * 检查是否是单次任务
     */
    suspend fun isOneOff(query: String): Boolean {
        val recent = getRecentSuccessful(limit = 100, sinceHours = 24 * 7)
        val similar = recent.filter { h ->
            val qWords = query.split(Regex("\\s+")).map { it.lowercase() }.toSet()
            val hWords = h.query.split(Regex("\\s+")).map { it.lowercase() }.toSet()
            val intersection = qWords.intersect(hWords)
            intersection.size >= 2
        }
        return similar.size <= 1
    }

    // ---- 内部 helpers ----

    private fun getDailyFile(offsetDays: Int = 0): File {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, offsetDays)
        val dateStr = "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
        return File(historyDir, "executions_$dateStr.jsonl")
    }

    private fun ExecutionHistory.toSerializable() = ExecutionHistorySerializable(
        id = id, agentId = agentId, sessionId = sessionId, query = query,
        tools = tools.map { ToolCallRecordSerializable(
            toolName = it.toolName,
            arguments = it.arguments.mapValues { (_, v) -> v.toString() }.toList(),
            result = it.result, order = it.order, durationMs = it.durationMs, success = it.success
        ) },
        success = success, durationMs = durationMs, tokenUsage = tokenUsage,
        isOneOff = isOneOff, createdAt = createdAt
    )

    private fun ExecutionHistorySerializable.toDomain() = ExecutionHistory(
        id = id, agentId = agentId, sessionId = sessionId, query = query,
        tools = tools.map { ToolCallRecord(
            toolName = it.toolName,
            arguments = it.arguments.toMap().mapValues { (_, v) -> v as Any },
            result = it.result, order = it.order, durationMs = it.durationMs, success = it.success
        ) },
        success = success, durationMs = durationMs, tokenUsage = tokenUsage,
        isOneOff = isOneOff, createdAt = createdAt
    )
}

@Serializable
private data class ExecutionHistorySerializable(
    val id: String, val agentId: String, val sessionId: String, val query: String,
    val tools: List<ToolCallRecordSerializable>,
    val success: Boolean, val durationMs: Long, val tokenUsage: Long,
    val isOneOff: Boolean, val createdAt: Long
)

@Serializable
private data class ToolCallRecordSerializable(
    val toolName: String, val arguments: List<Pair<String, String>>,
    val result: String? = null, val order: Int = 0, val durationMs: Long = 0L,
    val success: Boolean = true
) {
    // Map<String, Any> 不可序列化，转为 List<Pair<String, String>>
}
