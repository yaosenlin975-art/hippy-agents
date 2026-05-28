package com.lin.hippyagent.core.memory

import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
data class ReflectionResult(
    val isSufficient: Boolean,
    val reason: String = "",
    val followUpQuery: String? = null
)

class ReflectionRetriever(
    private val modelClient: ModelClient,
    private val modelName: String,
    private val maxRounds: Int = 2,
    private val topK: Int = 20
) {

    suspend fun reflect(
        query: String,
        candidates: List<Pair<CommonMemoryEntry, Float>>,
        maxResults: Int,
        retriever: suspend (String) -> List<Pair<CommonMemoryEntry, Float>>
    ): List<Pair<CommonMemoryEntry, Float>> = withContext(Dispatchers.Default) {
        if (candidates.isEmpty()) return@withContext candidates

        var allResults = candidates.toMutableList()
        var currentQuery = query
        var round = 0

        while (round < maxRounds) {
            round++
            Timber.d("Reflection retrieval round $round: $currentQuery")

            if (round > 1) {
                val newResults = retriever(currentQuery)
                allResults.addAll(newResults)
            }

            if (round < maxRounds) {
                val reflection = evaluateSufficiency(query, allResults, currentQuery)
                Timber.d("Reflection result: sufficient=${reflection.isSufficient}, reason=${reflection.reason}")

                if (reflection.isSufficient || reflection.followUpQuery.isNullOrBlank()) {
                    break
                }

                currentQuery = reflection.followUpQuery
            }
        }

        allResults
            .distinctBy { it.first.id }
            .sortedByDescending { it.second }
            .take(maxResults)
    }

    private suspend fun evaluateSufficiency(
        originalQuery: String,
        results: List<Pair<CommonMemoryEntry, Float>>,
        currentQuery: String
    ): ReflectionResult {
        return runCatching {
            val resultsContext = results.take(5).joinToString("\n") { (entry, score) ->
                "[Score: $score] ${entry.summary.take(100)}"
            }

            val systemPrompt = """
                你是检索充分性评估专家。判断当前检索结果是否足够回答用户问题。
                
                评估标准：
                1. 结果是否覆盖用户问题的核心意图
                2. 结果内容是否具体且相关
                3. 是否需要补充其他角度的信息
                
                返回JSON格式：
                {
                    "isSufficient": true/false,
                    "reason": "简要说明原因",
                    "followUpQuery": "如果不足，生成追加查询（最多20字）"
                }
            """.trimIndent()

            val userPrompt = """
                原始问题：$originalQuery
                当前查询：$currentQuery
                检索结果：
                $resultsContext
                
                请评估检索结果是否充分。
            """.trimIndent()

            val request = ModelCallRequest(
                model = modelName,
                messages = listOf(
                    ModelMessage(role = "system", content = systemPrompt),
                    ModelMessage(role = "user", content = userPrompt)
                ),
                temperature = 0.2f,
                maxTokens = 200,
                stream = false
            )

            val response = modelClient.chatCompletion(request)
            val content = response.choices.firstOrNull()?.message?.content ?: ""

            val json = Json { ignoreUnknownKeys = true }
            val reflection = json.decodeFromString<ReflectionResult>(extractJson(content))

            ReflectionResult(
                isSufficient = reflection.isSufficient,
                reason = reflection.reason,
                followUpQuery = reflection.followUpQuery?.takeIf { it.isNotBlank() }
            )
        }.getOrElse {
            Timber.w(it, "LLM反思评估失败，使用规则回退")
            fallbackSufficiency(results)
        }
    }

    private fun extractJson(content: String): String {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        return if (start >= 0 && end > start) content.substring(start, end + 1) else "{}"
    }

    private fun fallbackSufficiency(results: List<Pair<CommonMemoryEntry, Float>>): ReflectionResult {
        if (results.size >= 10) return ReflectionResult(isSufficient = true, reason = "结果数量充足")
        if (results.any { it.second > 0.8f }) return ReflectionResult(isSufficient = true, reason = "存在高相关结果")

        return ReflectionResult(
            isSufficient = false,
            reason = "结果不足",
            followUpQuery = "补充相关信息"
        )
    }
}
