package com.lin.hippyagent.core.memory.commonmemory

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import timber.log.Timber

data class MemoryCandidate(
    val type: BrainMemoryType,
    val summary: String,
    val detail: String? = null,
    val confidence: Float,
    val importance: Float,
    val durability: Float,
    val scope: BrainMemoryScope = BrainMemoryScope.ACTIVE,
    val evidenceKind: EvidenceKind = EvidenceKind.INFERRED
)

class MemoryExtractor(
    private val llmClient: ModelClient,
    private val modelName: String,
    private val memoryRepo: MemoryRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pendingConversation: List<Pair<String, String>> = emptyList()
    private var debounceJob: kotlinx.coroutines.Job? = null
    private val debounceMutex = kotlinx.coroutines.sync.Mutex()

    fun extractAndStoreAsync(conversation: List<Pair<String, String>>) {
        scope.launch {
            debounceMutex.withLock {
                pendingConversation = pendingConversation + conversation
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    kotlinx.coroutines.delay(30_000L)
                    val merged: List<Pair<String, String>>
                    debounceMutex.withLock {
                        merged = pendingConversation
                        pendingConversation = emptyList()
                        debounceJob = null
                    }
                    if (merged.isNotEmpty()) {
                        runCatching {
                            extractMemories(merged)
                        }.onFailure { e ->
                            Timber.w(e, "MemoryExtractor: debounced extraction failed")
                        }
                    }
                }
            }
        }
    }

    suspend fun extractMemories(conversation: List<Pair<String, String>>) {
        if (conversation.size < 2) return

        val candidates = extractCandidates(conversation)
        for (candidate in candidates) {
            if (!shouldStore(candidate)) continue
            tryInsertOrUpdate(candidate)
        }
    }

    private suspend fun extractCandidates(conversation: List<Pair<String, String>>): List<MemoryCandidate> {
        val conversationText = conversation.takeLast(20).joinToString("\n") { (role, content) ->
            "${role.uppercase()}: ${content.take(500)}"
        }

        val systemPrompt = """
从以下对话中提取 0-3 条有价值的事实。
每条事实包含：type, summary (≤220字符), confidence (0-1), importance (0-1), durability (0-1)

type 可选值: identity, preference, goal, project, habit, decision, constraint, relationship, episode, reflection

规则：
- 忽略问候、闲聊、填充语
- confidence < 0.55 不存储
- durability < 0.4 且 importance < 0.7 不存储
- summary 长度 12-220 字符

输出 JSON 数组，每项格式：
{"type":"...","summary":"...","detail":"...","confidence":0.0,"importance":0.0,"durability":0.0}
        """.trimIndent()

        return runCatching {
            val request = ModelCallRequest(
                model = modelName,
                messages = listOf(
                    ModelMessage(role = "system", content = systemPrompt),
                    ModelMessage(role = "user", content = conversationText)
                ),
                temperature = 0.2f,
                maxTokens = 800,
                stream = false
            )

            val response = llmClient.chatCompletion(request)
            val content = response.choices.firstOrNull()?.message?.content ?: return@runCatching emptyList()

            parseCandidates(content)
        }.getOrElse { e ->
            Timber.w(e, "MemoryExtractor: LLM extraction failed")
            emptyList()
        }
    }

    private fun parseCandidates(content: String): List<MemoryCandidate> {
        val jsonStart = content.indexOf('[')
        val jsonEnd = content.lastIndexOf(']')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()

        val jsonStr = content.substring(jsonStart, jsonEnd + 1)
        return runCatching {
            val elements = lenientJson.parseToJsonElement(jsonStr).jsonArray
            elements.mapNotNull { element ->
                val obj = element.jsonObject
                val typeStr = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val summary = obj["summary"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val confidence = (obj["confidence"] as? JsonPrimitive)?.let { p ->
                    p.floatOrNull ?: p.content.toFloatOrNull() ?: 0.5f
                } ?: 0.5f
                val importance = (obj["importance"] as? JsonPrimitive)?.let { p ->
                    p.floatOrNull ?: p.content.toFloatOrNull() ?: 0.5f
                } ?: 0.5f
                val durability = (obj["durability"] as? JsonPrimitive)?.let { p ->
                    p.floatOrNull ?: p.content.toFloatOrNull() ?: 0.5f
                } ?: 0.5f
                val detail = obj["detail"]?.jsonPrimitive?.content

                MemoryCandidate(
                    type = BrainMemoryType.fromString(typeStr),
                    summary = summary,
                    detail = detail,
                    confidence = confidence,
                    importance = importance,
                    durability = durability
                )
            }
        }.getOrElse { e ->
            Timber.w(e, "MemoryExtractor: JSON parse failed")
            emptyList()
        }
    }

    private fun shouldStore(c: MemoryCandidate): Boolean {
        return c.confidence >= 0.55f
            && (c.durability >= 0.4f || c.importance >= 0.7f)
            && c.summary.length in 12..220
    }

    private fun isUploadRelatedFact(summary: String): Boolean {
        return UPLOAD_PATTERN.containsMatchIn(summary)
    }

    companion object {
        private val TERM_SPLIT_REGEX = Regex("[\\s,，。.、！!？?]+")
        private val UPLOAD_PATTERN = Regex(
            "(上传|uploaded|upload|文件|file|attachment|附件)\\s*[:：]?\\s*[\\w.\\-]+\\.\\w{1,10}",
            RegexOption.IGNORE_CASE
        )
        private const val UPLOAD_FACT_TTL_MS = 86_400_000L
        private const val SEMANTIC_DUPLICATE_THRESHOLD = 0.7f
        private val lenientJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private suspend fun tryInsertOrUpdate(candidate: MemoryCandidate) {
        val terms = candidate.summary.split(TERM_SPLIT_REGEX).filter { it.length >= 2 }
        val mergeCandidate = memoryRepo.findMergeCandidate(candidate.type, terms)

        if (mergeCandidate != null) {
            val merged = mergeCandidate.copy(
                confidence = minOf(1.0f, mergeCandidate.confidence + 0.1f),
                evidenceCount = mergeCandidate.evidenceCount + 1,
                updatedAt = System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis()
            )
            memoryRepo.update(merged)
            Timber.d("MemoryExtractor: merged into existing ${mergeCandidate.id}")
        } else {
            val conflictCandidate = memoryRepo.findConflictCandidate(candidate.type, terms)
            if (conflictCandidate != null) {
                val demoted = conflictCandidate.copy(
                    confidence = conflictCandidate.confidence * 0.5f,
                    updatedAt = System.currentTimeMillis()
                )
                memoryRepo.update(demoted)
            }

            val semanticDupes = memoryRepo.searchBySummary(candidate.summary, limit = 5)
            val isDuplicate = semanticDupes.any { existing ->
                existing.id != (conflictCandidate?.id ?: "") &&
                computeSimilarity(existing.summary, candidate.summary) > SEMANTIC_DUPLICATE_THRESHOLD
            }

            if (isDuplicate) {
                Timber.d("MemoryExtractor: skipped semantic duplicate: ${candidate.summary.take(40)}")
                return
            }

            val entry = CommonMemoryEntry(
                id = com.lin.hippyagent.core.pool.FastId.next(),
                type = candidate.type,
                summary = candidate.summary,
                detail = candidate.detail,
                scope = candidate.scope,
                evidenceKind = candidate.evidenceKind,
                confidence = candidate.confidence,
                importance = candidate.importance,
                durability = candidate.durability,
                isUploadRelated = isUploadRelatedFact(candidate.summary),
                expiresAt = if (isUploadRelatedFact(candidate.summary)) System.currentTimeMillis() + UPLOAD_FACT_TTL_MS else null
            )
            memoryRepo.insert(entry)
            Timber.d("MemoryExtractor: inserted new memory [${candidate.type.value}] ${candidate.summary.take(40)}")
        }
    }

    private fun computeSimilarity(a: String, b: String): Float {
        val setA = a.split(TERM_SPLIT_REGEX).filter { it.length >= 2 }.toSet()
        val setB = b.split(TERM_SPLIT_REGEX).filter { it.length >= 2 }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }
}

