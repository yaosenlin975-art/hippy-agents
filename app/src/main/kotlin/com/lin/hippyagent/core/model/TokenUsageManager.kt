package com.lin.hippyagent.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@kotlinx.serialization.Serializable
data class TokenUsageRecord(
    val timestamp: Long,
    val providerId: String,
    val modelName: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val agentId: String,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0
)

data class TokenUsageSummary(
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCalls: Long,
    val totalCacheReadTokens: Long = 0,
    val totalCacheWriteTokens: Long = 0,
    val byModel: Map<String, ModelUsageSummary>
)

data class ModelUsageSummary(
    val providerId: String,
    val modelName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val calls: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0
)

data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val apiCalls: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0
)

class TokenUsageManager(
    private val workingDir: File
) {
    private val _dailyUsage = MutableStateFlow<Map<LocalDate, TokenUsageSummary>>(emptyMap())
    val dailyUsage: Flow<Map<LocalDate, TokenUsageSummary>> = _dailyUsage.asStateFlow()

    private val records = mutableListOf<TokenUsageRecord>()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        // 启动时从文件加载历史记录
        loadPersistedRecords()
    }

    /**
     * 从持久化文件加载历史记录
     */
    private fun loadPersistedRecords() {
        val usageFile = File(workingDir, "token_usage.json")
        if (!usageFile.exists()) return
        try {
            val text = usageFile.readText()
            if (text.isBlank()) return
            val loaded = json.decodeFromString<List<TokenUsageRecord>>(text)
            records.clear()
            records.addAll(loaded)
            // 重建 daily summary
            records.forEach { updateDailySummary(it) }
            Timber.d("Token usage loaded: ${records.size} records")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load token usage records")
        }
    }

    suspend fun recordUsage(
        providerId: String,
        modelName: String,
        inputTokens: Int,
        outputTokens: Int,
        agentId: String,
        cacheReadTokens: Int = 0,
        cacheWriteTokens: Int = 0
    ) = withContext(Dispatchers.IO) {
        val record = TokenUsageRecord(
            timestamp = System.currentTimeMillis(),
            providerId = providerId,
            modelName = modelName,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            agentId = agentId,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens
        )

        records.add(record)
        updateDailySummary(record)

        // 自动持久化 — 每次记录后保存到文件
        persist()

        Timber.d("Token usage recorded: $providerId/$modelName input=$inputTokens output=$outputTokens cacheR=$cacheReadTokens cacheW=$cacheWriteTokens")
    }

    private fun updateDailySummary(record: TokenUsageRecord) {
        val date = Instant.ofEpochMilli(record.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

        _dailyUsage.update { current ->
            val mutableMap = current.toMutableMap()
            val existing = mutableMap[date] ?: TokenUsageSummary(0, 0, 0, 0, 0, emptyMap())

            val modelKey = "${record.providerId}/${record.modelName}"
            val existingByModel = existing.byModel.toMutableMap()
            val existingModel = existingByModel[modelKey] ?: ModelUsageSummary(
                record.providerId, record.modelName, 0, 0, 0
            )

            existingByModel[modelKey] = existingModel.copy(
                inputTokens = existingModel.inputTokens + record.inputTokens,
                outputTokens = existingModel.outputTokens + record.outputTokens,
                calls = existingModel.calls + 1,
                cacheReadTokens = existingModel.cacheReadTokens + record.cacheReadTokens,
                cacheWriteTokens = existingModel.cacheWriteTokens + record.cacheWriteTokens
            )

            mutableMap[date] = existing.copy(
                totalInputTokens = existing.totalInputTokens + record.inputTokens,
                totalOutputTokens = existing.totalOutputTokens + record.outputTokens,
                totalCalls = existing.totalCalls + 1,
                totalCacheReadTokens = existing.totalCacheReadTokens + record.cacheReadTokens,
                totalCacheWriteTokens = existing.totalCacheWriteTokens + record.cacheWriteTokens,
                byModel = existingByModel
            )
            mutableMap.toMap()
        }
    }

    fun getUsage(): TokenUsage {
        val totalInput = records.sumOf { it.inputTokens.toLong() }
        val totalOutput = records.sumOf { it.outputTokens.toLong() }
        val totalCacheRead = records.sumOf { it.cacheReadTokens.toLong() }
        val totalCacheWrite = records.sumOf { it.cacheWriteTokens.toLong() }
        return TokenUsage(
            inputTokens = totalInput,
            outputTokens = totalOutput,
            totalTokens = totalInput + totalOutput,
            apiCalls = records.size.toLong(),
            cacheReadTokens = totalCacheRead,
            cacheWriteTokens = totalCacheWrite
        )
    }

    suspend fun getSummary(
        startDate: LocalDate,
        endDate: LocalDate
    ): TokenUsageSummary = withContext(Dispatchers.IO) {
        val filteredRecords = records.filter { record ->
            val date = Instant.ofEpochMilli(record.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            date in startDate..endDate
        }

        var totalInput = 0L
        var totalOutput = 0L
        val byModel = mutableMapOf<String, ModelUsageSummary>()

        filteredRecords.forEach { record ->
            totalInput += record.inputTokens
            totalOutput += record.outputTokens

            val modelKey = "${record.providerId}/${record.modelName}"
            val existing = byModel[modelKey] ?: ModelUsageSummary(
                record.providerId, record.modelName, 0, 0, 0
            )
            byModel[modelKey] = existing.copy(
                inputTokens = existing.inputTokens + record.inputTokens,
                outputTokens = existing.outputTokens + record.outputTokens,
                calls = existing.calls + 1
            )
        }

        TokenUsageSummary(
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalCalls = filteredRecords.size.toLong(),
            byModel = byModel
        )
    }

    suspend fun persist() = withContext(Dispatchers.IO) {
        val usageFile = File(workingDir, "token_usage.json")
        usageFile.parentFile?.mkdirs()
        usageFile.writeText(json.encodeToString(records))
        Timber.d("Token usage persisted")
    }

    fun getSummary(
        agentId: String? = null,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null
    ): TokenUsageSummary {
        val filtered = records.filter { record ->
            if (agentId != null && record.agentId != agentId) return@filter false
            if (startDate != null || endDate != null) {
                val date = Instant.ofEpochMilli(record.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                if (startDate != null && date < startDate) return@filter false
                if (endDate != null && date > endDate) return@filter false
            }
            true
        }

        var totalInput = 0L
        var totalOutput = 0L
        val byModel = mutableMapOf<String, ModelUsageSummary>()

        filtered.forEach { record ->
            totalInput += record.inputTokens
            totalOutput += record.outputTokens

            val modelKey = "${record.providerId}/${record.modelName}"
            val existing = byModel[modelKey] ?: ModelUsageSummary(
                record.providerId, record.modelName, 0, 0, 0
            )
            byModel[modelKey] = existing.copy(
                inputTokens = existing.inputTokens + record.inputTokens,
                outputTokens = existing.outputTokens + record.outputTokens,
                calls = existing.calls + 1
            )
        }

        return TokenUsageSummary(
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalCalls = filtered.size.toLong(),
            byModel = byModel
        )
    }

    data class DailyTrends(
        val inputTokens: List<com.lin.hippyagent.core.stats.TrendPoint>,
        val outputTokens: List<com.lin.hippyagent.core.stats.TrendPoint>,
        val llmCalls: List<com.lin.hippyagent.core.stats.TrendPoint>
    )

    fun getDailyTrends(
        agentId: String? = null,
        days: Int = 30
    ): DailyTrends {
        val endDate = LocalDate.now()
        val startDate = endDate.minusDays(days.toLong())
        val zoneId = ZoneId.systemDefault()

        val dateRange = generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .toList()

        val filtered = records.filter { record ->
            if (agentId != null && record.agentId != agentId) return@filter false
            val date = Instant.ofEpochMilli(record.timestamp).atZone(zoneId).toLocalDate()
            date in startDate..endDate
        }

        val inputByDate = mutableMapOf<LocalDate, Long>()
        val outputByDate = mutableMapOf<LocalDate, Long>()
        val callsByDate = mutableMapOf<LocalDate, Long>()

        filtered.forEach { record ->
            val date = Instant.ofEpochMilli(record.timestamp).atZone(zoneId).toLocalDate()
            inputByDate[date] = (inputByDate[date] ?: 0L) + record.inputTokens
            outputByDate[date] = (outputByDate[date] ?: 0L) + record.outputTokens
            callsByDate[date] = (callsByDate[date] ?: 0L) + 1
        }

        return DailyTrends(
            inputTokens = dateRange.map { com.lin.hippyagent.core.stats.TrendPoint(it.toString(), inputByDate[it] ?: 0L) },
            outputTokens = dateRange.map { com.lin.hippyagent.core.stats.TrendPoint(it.toString(), outputByDate[it] ?: 0L) },
            llmCalls = dateRange.map { com.lin.hippyagent.core.stats.TrendPoint(it.toString(), callsByDate[it] ?: 0L) }
        )
    }
}


