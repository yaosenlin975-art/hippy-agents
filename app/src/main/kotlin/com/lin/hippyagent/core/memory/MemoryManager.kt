package com.lin.hippyagent.core.memory

data class MemoryEntry(
    val id: String,
    val content: String,
    val timestamp: Long,
    val type: MemoryType,
    val metadata: Map<String, String> = emptyMap(),
    val embedding: FloatArray? = null
)

enum class MemoryType {
    SHORT_TERM,
    LONG_TERM,
    WORKING,
    EPISODIC,
    SEMANTIC
}

data class MemoryQuery(
    val query: String,
    val maxResults: Int = 10,
    val minScore: Float = 0.0f,
    val filters: Map<String, String> = emptyMap(),
    val memoryTypes: List<MemoryType> = MemoryType.entries.toList()
)

data class SearchResult(
    val entry: MemoryEntry,
    val score: Float
)

interface MemoryStore {
    suspend fun addEntry(entry: MemoryEntry): Result<String>
    suspend fun getEntry(id: String): Result<MemoryEntry?>
    suspend fun deleteEntry(id: String): Result<Unit>
    suspend fun updateEntry(id: String, content: String): Result<Unit>
    suspend fun search(query: MemoryQuery): Result<List<SearchResult>>
    suspend fun getAll(): Result<List<MemoryEntry>>
    suspend fun clear(): Result<Unit>
}

interface EmbeddingModel {
    suspend fun embed(text: String): Result<FloatArray>
    suspend fun embedBatch(texts: List<String>): Result<List<FloatArray>>
    val dimensions: Int
}

interface Retriever {
    suspend fun retrieve(query: MemoryQuery): Result<List<SearchResult>>
}

class SemanticRetriever(
    private val store: MemoryStore,
    private val embeddingModel: EmbeddingModel
) : Retriever {
    override suspend fun retrieve(query: MemoryQuery): Result<List<SearchResult>> {
        return runCatching {
            val queryEmbedding = embeddingModel.embed(query.query).getOrThrow()
            val results = store.search(query).getOrThrow()
            results
                .map { result ->
                    val entryEmbedding = result.entry.embedding
                        ?: embeddingModel.embed(result.entry.content).getOrThrow()
                    val cosineSimilarity = cosineSimilarity(queryEmbedding, entryEmbedding)
                    result.copy(score = cosineSimilarity)
                }
                .filter { it.score >= query.minScore }
                .sortedByDescending { it.score }
                .take(query.maxResults)
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding dimensions must match" }
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val ai = a[i].toDouble()
            val bi = b[i].toDouble()
            dotProduct += ai * bi
            normA += ai * ai
            normB += bi * bi
        }
        normA = Math.sqrt(normA)
        normB = Math.sqrt(normB)
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dotProduct / (normA * normB)).toFloat()
    }
}

class HybridRetriever(
    private val semanticRetriever: Retriever,
    private val keywordRetriever: Retriever,
    private val symbolicRetriever: Retriever? = null,
    private val rrfK: Int = 60
) : Retriever {
    override suspend fun retrieve(query: MemoryQuery): Result<List<SearchResult>> {
        return runCatching {
            val semanticResults = semanticRetriever.retrieve(query).getOrDefault(emptyList())
            val keywordResults = keywordRetriever.retrieve(query).getOrDefault(emptyList())
            val symbolicResults = symbolicRetriever?.retrieve(query)?.getOrDefault(emptyList()) ?: emptyList()

            val allResults = listOf(semanticResults, keywordResults, symbolicResults).filter { it.isNotEmpty() }
            rrfMerge(allResults, query.maxResults)
        }
    }

    private fun rrfMerge(resultLists: List<List<SearchResult>>, maxResults: Int): List<SearchResult> {
        val entryMap = mutableMapOf<String, MemoryEntry>()
        val rrfScores = mutableMapOf<String, Double>()

        for (results in resultLists) {
            for ((rank, result) in results.withIndex()) {
                entryMap[result.entry.id] = result.entry
                rrfScores[result.entry.id] = rrfScores.getOrDefault(result.entry.id, 0.0) + 1.0 / (rrfK + rank + 1)
            }
        }

        return rrfScores
            .map { (id, score) -> SearchResult(entry = entryMap[id]!!, score = score.toFloat()) }
            .sortedByDescending { it.score }
            .take(maxResults)
    }
}

class MemoryManager(
    private val store: MemoryStore,
    private val retriever: Retriever
) {
    suspend fun recall(query: String, maxResults: Int = 10, minScore: Float = 0.0f): Result<List<SearchResult>> {
        val memoryQuery = MemoryQuery(
            query = query,
            maxResults = maxResults,
            minScore = minScore
        )
        return retriever.retrieve(memoryQuery)
    }
}

