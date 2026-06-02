package com.lin.hippyagent.core.memory

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
data class RetrievalPlan(
    val subQueries: List<String>,
    val filters: Map<String, String> = emptyMap(),
    val maxRounds: Int = 2
)

class RetrievalPlanner(
    private val modelClient: ModelClient,
    private val modelName: String,
    private val maxSubQueries: Int = 6,
    private val commonMemoryRepo: MemoryRepository? = null
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun plan(query: String): RetrievalPlan = withContext(Dispatchers.Default) {
        val subQueries = generateSubQueries(query)
        val filters = extractFilters(query)
        
        RetrievalPlan(
            subQueries = subQueries,
            filters = filters,
            maxRounds = 2
        )
    }

    suspend fun searchCommonMemory(query: String, limit: Int = 10): List<Pair<CommonMemoryEntry, Float>> {
        val repo = commonMemoryRepo ?: return emptyList()
        val plan = plan(query)
        val allResults = mutableMapOf<String, CommonMemoryEntry>()

        for (subQuery in plan.subQueries) {
            runCatching {
                val results = repo.searchFts(subQuery, limit)
                for (entry in results) {
                    allResults[entry.id] = entry
                }
            }.onFailure { e ->
                Timber.w(e, "RetrievalPlanner: FTS search failed for subQuery='$subQuery'")
            }
        }

        if (allResults.isEmpty()) {
            runCatching {
                val results = repo.searchFts(query, limit)
                for (entry in results) {
                    allResults[entry.id] = entry
                }
            }
        }

        return repo.scoreMemories(allResults.values.toList(), query).take(limit)
    }

    private suspend fun generateSubQueries(query: String): List<String> {
        return runCatching {
            val systemPrompt = """
                你是一个检索规划专家。根据用户的查询，生成3-6个子查询来全面覆盖用户需求。
                
                要求：
                1. 每个子查询从不同角度切入
                2. 保持简洁，不超过20字
                3. 返回JSON数组格式，只包含子查询字符串
                
                示例：
                输入：如何优化Android端侧AI模型的内存占用
                输出：["Android AI模型内存优化方案", "端侧AI模型量化方法", "Android内存管理机制", "LLM模型压缩技术"]
            """.trimIndent()

            val request = ModelCallRequest(
                model = modelName,
                messages = listOf(
                    ModelMessage(role = "system", content = systemPrompt),
                    ModelMessage(role = "user", content = query)
                ),
                temperature = 0.3f,
                maxTokens = 300,
                stream = false
            )

            val response = modelClient.chatCompletion(request)
            val content = response.choices.firstOrNull()?.message?.content ?: ""
            
            val subQueries = json.decodeFromString<List<String>>(extractJson(content))
            
            if (subQueries.isNotEmpty()) subQueries.distinct().take(maxSubQueries)
            else fallbackSubQueries(query)
        }.getOrElse {
            Timber.w(it, "LLM子查询生成失败，使用规则回退")
            fallbackSubQueries(query)
        }
    }

    private fun extractJson(content: String): String {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        return if (start >= 0 && end > start) content.substring(start, end + 1) else "[]"
    }

    private fun fallbackSubQueries(query: String): List<String> {
        val keywords = query.split(WHITESPACE_REGEX)
            .filter { it.length > 2 }
            .distinct()
        
        if (keywords.isEmpty()) return listOf(query)

        val subQueries = mutableListOf<String>()
        
        for (i in keywords.indices) {
            subQueries.add("$query ${keywords[i]}")
        }
        
        for (i in 0 until keywords.size - 1) {
            subQueries.add("${keywords[i]} ${keywords[i + 1]}")
        }
        
        subQueries.add(query)
        
        return subQueries.distinct().take(maxSubQueries)
    }

    private fun extractFilters(query: String): Map<String, String> {
        val filters = mutableMapOf<String, String>()
        
        val dateMatch = DATE_PATTERN.find(query)
        dateMatch?.let {
            filters["date"] = it.groupValues[1]
        }
        
        val typeMatch = TYPE_PATTERN.find(query)
        typeMatch?.let {
            filters["type"] = it.groupValues[2]
        }
        
        return filters
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val DATE_PATTERN = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b")
        private val TYPE_PATTERN = Regex("\\b(type:|记忆类型:)(\\w+)\\b")
    }
}

