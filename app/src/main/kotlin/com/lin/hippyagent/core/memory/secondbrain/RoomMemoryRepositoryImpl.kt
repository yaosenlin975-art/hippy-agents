package com.lin.hippyagent.core.memory.commonmemory

import androidx.room.*
import com.lin.hippyagent.core.memory.ChineseTokenizer
import com.lin.hippyagent.core.memory.SymbolicRetriever
import com.lin.hippyagent.core.memory.ReflectionRetriever
import com.lin.hippyagent.core.memory.search.HybridSearchEngine
import com.lin.hippyagent.core.memory.search.LightweightReranker
import com.lin.hippyagent.core.memory.search.ScoredItem
import com.lin.hippyagent.core.memory.search.SearchOptions
import com.lin.hippyagent.core.memory.commonmemory.SearchIntent

/**
 * RoomMemoryRepositoryImpl（MemoryRepository 的 Room 实现）
 *
 * 参考 Mercury Agent CommonMemory 设计
 */
class RoomMemoryRepositoryImpl(
    private val dao: MemoryDao,
    private val userKey: String = "user:owner",
    private val symbolicRetriever: SymbolicRetriever? = null,
    private val reflectionRetriever: ReflectionRetriever? = null,
    private val database: MemoryDatabase? = null
) : MemoryRepository {

    private val hybridSearchEngine by lazy {
        HybridSearchEngine(memoryRepository = this)
    }

    private val reranker by lazy { LightweightReranker() }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val NON_ALPHANUM_REGEX = Regex("[^a-z0-9\\u4e00-\\u9fff]+")
        private val POLARITY_PAIRS = listOf(
            "prefers" to "does not prefer",
            "likes" to "does not like",
            "wants" to "does not want",
            "喜欢" to "不喜欢",
            "需要" to "不需要",
            "偏好" to "不偏好"
        )
    }

    // ============ 基础 CRUD ============

    override suspend fun insert(entry: CommonMemoryEntry) {
        val entity = entry.toEntity()
        dao.insert(entity)
        dao.insertFts(MemoryFts(
            rowId = entity.id.hashCode().toLong(),
            memoryId = entity.id,
            summary = ChineseTokenizer.segmentToString(entry.summary),
            detail = entry.detail?.let { ChineseTokenizer.segmentToString(it) }
        ))
    }

    override suspend fun update(entry: CommonMemoryEntry) {
        val entity = entry.toEntity()
        dao.update(entity)
        dao.deleteFtsByRowId(entity.id.hashCode().toLong())
        dao.insertFts(MemoryFts(
            rowId = entity.id.hashCode().toLong(),
            memoryId = entity.id,
            summary = ChineseTokenizer.segmentToString(entry.summary),
            detail = entry.detail?.let { ChineseTokenizer.segmentToString(it) }
        ))
    }

    override suspend fun findById(id: String): CommonMemoryEntry? {
        return dao.findById(id)?.toCommonMemoryEntry()
    }

    override suspend fun searchFts(query: String, limit: Int): List<CommonMemoryEntry> {
        val ftsQuery = ChineseTokenizer.segmentForSearch(query)
        if (ftsQuery.isBlank()) {
            return dao.findActive().map { it.toCommonMemoryEntry() }
        }
        return dao.searchFts(ftsQuery, limit).map { it.toCommonMemoryEntry() }
    }

    override suspend fun searchFtsByAgentId(query: String, agentId: String, limit: Int): List<CommonMemoryEntry> {
        val ftsQuery = ChineseTokenizer.segmentForSearch(query)
        if (ftsQuery.isBlank()) {
            return dao.findActiveByAgentId(agentId).map { it.toCommonMemoryEntry() }
        }
        return dao.searchFtsByAgentId(ftsQuery, agentId, limit).map { it.toCommonMemoryEntry() }
    }

    override suspend fun searchHybrid(query: String, limit: Int): List<Pair<CommonMemoryEntry, Float>> {
        return hybridSearchEngine.search(query, SearchOptions(finalTopK = limit))
    }

    override suspend fun searchHybridByAgentId(query: String, agentId: String, limit: Int): List<Pair<CommonMemoryEntry, Float>> {
        return hybridSearchEngine.search(query, agentId, SearchOptions(finalTopK = limit))
    }

    override suspend fun search(query: String, agentId: String, intent: SearchIntent, limit: Int): List<Pair<CommonMemoryEntry, Float>> {
        return when (intent) {
            SearchIntent.PRECISE -> searchPrecise(query, agentId, limit)
            SearchIntent.RECENT -> searchRecent(query, agentId, limit)
            SearchIntent.BALANCED -> searchBalanced(query, agentId, limit)
            SearchIntent.BROAD -> searchBroad(query, agentId, limit)
        }
    }

    private suspend fun searchPrecise(query: String, agentId: String, limit: Int): List<Pair<CommonMemoryEntry, Float>> {
        val ftsResults = searchFtsByAgentId(query, agentId, limit)
        val symbolicResults = symbolicRetriever?.retrieve(query, agentId, limit) ?: emptyList()
        val merged = (ftsResults.mapIndexed { index, entry -> entry to (1f / (index + 1)) } + symbolicResults)
            .distinctBy { it.first.id }
        return reranker.rerank(query, merged.map { ScoredItem(it.first.id, it.second) }, merged.map { it.first }, limit)
    }

    private suspend fun searchRecent(query: String, agentId: String, limit: Int): List<Pair<CommonMemoryEntry, Float>> {
        val ftsResults = searchFtsByAgentId(query, agentId, limit * 3)
        val timeFiltered = ftsResults.filter { entry ->
            val ageDays = (System.currentTimeMillis() - entry.lastSeenAt) / 86400000
            ageDays <= 30
        }
        val scored = timeFiltered.mapIndexed { index, entry -> entry to (1f / (index + 1)) }
        return reranker.rerank(query, scored.map { ScoredItem(it.first.id, it.second) }, timeFiltered, limit)
    }

    private suspend fun searchBalanced(query: String, agentId: String, limit: Int): List<Pair<CommonMemoryEntry, Float>> {
        val ftsResults = searchFtsByAgentId(query, agentId, limit * 2)
        val scored = ftsResults.mapIndexed { index, entry -> entry to (1f / (index + 1)) }
        val reflected = if (reflectionRetriever != null) {
            reflectionRetriever.reflect(query, scored, limit * 2) { followUpQuery ->
                searchFtsByAgentId(followUpQuery, agentId, limit).mapIndexed { index, entry -> entry to (1f / (index + 1)) }
            }
        } else {
            scored
        }
        return reranker.rerank(query, reflected.map { ScoredItem(it.first.id, it.second) }, reflected.map { it.first }, limit)
    }

    private suspend fun searchBroad(query: String, agentId: String, limit: Int): List<Pair<CommonMemoryEntry, Float>> {
        val ftsResults = if (query.length > 20) {
            searchFtsByAgentId(query, agentId, limit * 3)
        } else {
            searchFtsByAgentId(query, agentId, limit * 2)
        }
        val scored = ftsResults.mapIndexed { index, entry -> entry to (1f / (index + 1)) }
        val reflected = if (reflectionRetriever != null) {
            reflectionRetriever.reflect(query, scored, limit * 2) { followUpQuery ->
                searchFtsByAgentId(followUpQuery, agentId, limit).mapIndexed { index, entry -> entry to (1f / (index + 1)) }
            }
        } else {
            scored
        }
        return reranker.rerank(query, reflected.map { ScoredItem(it.first.id, it.second) }, reflected.map { it.first }, limit)
    }

    override suspend fun findByType(type: BrainMemoryType, limit: Int): List<CommonMemoryEntry> {
        return dao.findByType(type.value, limit).map { it.toCommonMemoryEntry() }
    }

    override suspend fun findByTypeAndAgentId(type: BrainMemoryType, agentId: String, limit: Int): List<CommonMemoryEntry> {
        return dao.findByTypeAndAgentId(type.value, agentId, limit).map { it.toCommonMemoryEntry() }
    }

    override suspend fun findActive(limit: Int): List<CommonMemoryEntry> {
        return dao.findActive().take(limit).map { it.toCommonMemoryEntry() }
    }

    override suspend fun findAll(limit: Int): List<CommonMemoryEntry> {
        return dao.findAll().take(limit).map { it.toCommonMemoryEntry() }
    }

    override suspend fun findActiveByAgentId(agentId: String, limit: Int): List<CommonMemoryEntry> {
        return dao.findActiveByAgentId(agentId).take(limit).map { it.toCommonMemoryEntry() }
    }

    // ============ 合并/冲突 ============

    override suspend fun findMergeCandidate(
        type: BrainMemoryType,
        normalizedTerms: List<String>
    ): CommonMemoryEntry? {
        if (normalizedTerms.isEmpty()) return null

        val term1 = "%${normalizedTerms[0]}%"
        val term2 = if (normalizedTerms.size > 1) "%${normalizedTerms[1]}%" else "%${normalizedTerms[0]}%"
        val term3 = if (normalizedTerms.size > 2) "%${normalizedTerms[2]}%" else "%${normalizedTerms[0]}%"

        val candidate = dao.findMergeCandidate(type.value, term1, term2, term3)
        if (candidate == null) return null

        // 验证 overlapScore ≥ 0.74
        val candidateTerms = normalize(candidate.summary)
        if (overlapScore(candidateTerms, normalizedTerms.joinToString(" ")) >= 0.74f) {
            return candidate.toCommonMemoryEntry()
        }
        return null
    }

    override suspend fun findConflictCandidate(
        type: BrainMemoryType,
        summaryTerms: List<String>
    ): CommonMemoryEntry? {
        if (summaryTerms.isEmpty()) return null

        val term1 = "%${summaryTerms[0]}%"
        val term2 = if (summaryTerms.size > 1) "%${summaryTerms[1]}%" else "%${summaryTerms[0]}%"
        val term3 = if (summaryTerms.size > 2) "%${summaryTerms[2]}%" else "%${summaryTerms[0]}%"

        val candidate = dao.findConflictCandidate(type.value, term1, term2, term3)
        if (candidate == null) return null

        // 验证极性冲突
        if (hasPolarityMismatch(candidate.summary, summaryTerms.joinToString(" "))) {
            return candidate.toCommonMemoryEntry()
        }
        return null
    }

    // ============ 晋升/修剪 ============

    override suspend fun promoteToDurable(): Int {
        return dao.promoteToDurable(System.currentTimeMillis())
    }

    override suspend fun pruneStale(): PruneResult {
        val now = System.currentTimeMillis()
        val cutoffInferred = now - 21 * 24 * 60 * 60 * 1000L  // 21 天
        val cutoffDirect = now - 42 * 24 * 60 * 60 * 1000L     // 42 天
        val cutoffDurable = now - 120 * 24 * 60 * 60 * 1000L // 120 天

        val prunedInferred = dao.pruneInferredActive(now, cutoffInferred)
        val prunedDirect = dao.pruneDirectActive(now, cutoffDirect)
        val decayed = dao.decayDurableInferred(now, cutoffDurable)
        val deleted = dao.pruneDurableInferred(now, cutoffDurable)

        return PruneResult(
            activePruned = prunedInferred + prunedDirect,
            durablePruned = deleted
        )
    }

    // ============ 删除 ============

    override suspend fun softDelete(id: String) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    override suspend fun findExpiredUploadFacts(nowMs: Long): List<CommonMemoryEntry> {
        return dao.findExpiredUploadFacts(nowMs).map { it.toCommonMemoryEntry() }
    }

    override suspend fun searchBySummary(query: String, limit: Int): List<CommonMemoryEntry> {
        return dao.searchBySummary("%$query%", limit).map { it.toCommonMemoryEntry() }
    }

    override suspend fun hardDelete(id: String) {
        dao.hardDeleteById(id)
    }

    override suspend fun getStats(): MemoryStats {
        val total = dao.countActive()
        val byType = dao.countByType().associate { it.type to it.count }
        val profile = dao.getProfileSummary()
        val active = dao.getActiveSummary()
        return MemoryStats(total, byType, profile, active)
    }

    override fun close() {
    }

    suspend fun rebuildFtsIndex() {
        val db = database ?: return
        val allEntries = dao.findActive(Int.MAX_VALUE, 0)
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("DELETE FROM memories_fts")
            allEntries.forEach { entity ->
                val ftsSummary = ChineseTokenizer.segmentToString(entity.summary)
                val ftsDetail = entity.detail?.let { ChineseTokenizer.segmentToString(it) }
                db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO memories_fts(rowid, memory_id, summary, detail) VALUES(?, ?, ?, ?)",
                    arrayOf<Any?>(entity.id.hashCode().toLong(), entity.id, ftsSummary, ftsDetail)
                )
            }
        }
    }

    override fun scoreMemories(
        candidates: List<CommonMemoryEntry>,
        query: String
    ): List<Pair<CommonMemoryEntry, Float>> {
        val queryTokens = query.lowercase().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val now = System.currentTimeMillis()

        return candidates.map { entity ->
            var score = 0f
            score += entity.confidence * 0.3f
            score += entity.importance * 0.25f
            score += entity.durability * 0.15f

            val ageDays = (now - entity.updatedAt) / (1000f * 60 * 60 * 24)
            score += maxOf(0f, 0.2f - ageDays * 0.005f)

            if (queryTokens.isNotEmpty()) {
                val lower = (entity.summary + " " + (entity.detail ?: "")).lowercase()
                val matchCount = queryTokens.count { lower.contains(it) }
                score += (matchCount.toFloat() / maxOf(queryTokens.size, 1)) * 0.1f
            }

            entity to score
        }.sortedByDescending { it.second }
    }

    // ============ 辅助函数 ============

    private fun normalize(input: String): String {
        return input.lowercase()
            .split(NON_ALPHANUM_REGEX)
            .filter { it.length >= 2 }
            .joinToString(" ")
    }

    private fun overlapScore(a: String, b: String): Float {
        val aTerms = a.split(" ").toSet()
        val bTerms = b.split(" ").toSet()
        if (aTerms.isEmpty() || bTerms.isEmpty()) return 0f

        val overlap = aTerms.count { bTerms.contains(it) }.toFloat()
        return overlap / maxOf(aTerms.size, bTerms.size).toFloat()
    }

    private fun hasPolarityMismatch(existing: String, incoming: String): Boolean {
        val lowerExisting = existing.lowercase()
        val lowerIncoming = incoming.lowercase()

        for ((positive, negative) in POLARITY_PAIRS) {
            val hasPosInExisting = lowerExisting.contains(positive)
            val hasNegInExisting = lowerExisting.contains(negative)
            val hasPosInIncoming = lowerIncoming.contains(positive)
            val hasNegInIncoming = lowerIncoming.contains(negative)

            if ((hasPosInExisting && hasNegInIncoming) || (hasNegInExisting && hasPosInIncoming)) {
                val existingTerms = normalize(existing)
                val incomingTerms = normalize(incoming)
                if (overlapScore(existingTerms, incomingTerms) >= 0.5f) {
                    return true
                }
            }
        }
        return false
    }
}

// ============ Extension Functions（Entity ↔ Entry 转换） ============

fun CommonMemoryEntry.toEntity(): MemoryEntity {
    return MemoryEntity(
        id = this.id,
        userKey = "user:owner",
        agentId = this.agentId,
        type = this.type.value,
        summary = this.summary,
        detail = this.detail,
        scope = this.scope.value,
        evidenceKind = this.evidenceKind.value,
        confidence = this.confidence,
        importance = this.importance,
        durability = this.durability,
        evidenceCount = this.evidenceCount,
        dismissed = this.dismissed,
        isUploadRelated = this.isUploadRelated,
        expiresAt = this.expiresAt,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
        lastSeenAt = this.lastSeenAt,
        lastUsedAt = this.lastUsedAt
    )
}

fun MemoryEntity.toCommonMemoryEntry(): CommonMemoryEntry {
    return CommonMemoryEntry(
        id = this.id,
        agentId = this.agentId,
        type = BrainMemoryType.fromString(this.type),
        summary = this.summary,
        detail = this.detail,
        scope = BrainMemoryScope.fromString(this.scope),
        evidenceKind = EvidenceKind.fromString(this.evidenceKind),
        confidence = this.confidence,
        importance = this.importance,
        durability = this.durability,
        evidenceCount = this.evidenceCount,
        dismissed = this.dismissed,
        isUploadRelated = this.isUploadRelated,
        expiresAt = this.expiresAt,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt,
        lastSeenAt = this.lastSeenAt,
        lastUsedAt = this.lastUsedAt
    )
}

