package com.lin.hippyagent.core.memory.search

import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry

/**
 * 混合搜索引擎 — 三路 RRF（FTS4 + 图谱）+ post-fusion 重排 + floor-ratio gate。
 *
 * 升级说明：
 * - 新增 GraphRetriever 第三路召回（图谱关系）
 * - 新增 PostFusionReranker 6 阶段重排
 * - cosineReScore：本期 cosineWeight=0（LocalEmbeddingModel 是 hash 嵌入无语义），未来真实嵌入接入后开启 0.3
 * - floor-ratio gate：裁剪低于 best×floorRatio 的弱命中
 *
 * 向后兼容：graphRetriever/postFusionReranker 为 null 时退化为原有 FTS4 + LightweightReranker 路径。
 */
class HybridSearchEngine(
    private val memoryRepository: MemoryRepository,
    private val rrfFuser: RRFFuser = RRFFuser(),
    private val reranker: LightweightReranker = LightweightReranker(),
    private val graphRetriever: GraphRetriever? = null,
    private val postFusionReranker: PostFusionReranker? = null,
    private val cosineWeight: Float = 0.0f,
    private val floorRatio: Float = 0.15f
) {
    /**
     * 混合搜索（无 agentId）
     */
    suspend fun search(
        query: String,
        options: SearchOptions = SearchOptions()
    ): List<Pair<CommonMemoryEntry, Float>> = search(query, null, options)

    /**
     * 混合搜索（按 agentId）
     */
    suspend fun search(
        query: String,
        agentId: String?,
        options: SearchOptions = SearchOptions()
    ): List<Pair<CommonMemoryEntry, Float>> {
        if (query.isBlank()) return emptyList()

        // 路径分流：未注入新依赖时走原 LightweightReranker 路径（兼容）
        if (graphRetriever == null || postFusionReranker == null) {
            return legacySearch(query, agentId, options)
        }
        return upgradedSearch(query, agentId, options)
    }

    /** 升级路径：三路 RRF + post-fusion + cosineReScore + floor-ratio gate */
    private suspend fun upgradedSearch(
        query: String,
        agentId: String?,
        options: SearchOptions
    ): List<Pair<CommonMemoryEntry, Float>> {
        // 1. 关键词召回（FTS4）
        val ftsResults = if (agentId != null) {
            memoryRepository.searchFtsByAgentId(query, agentId, options.ftsTopK)
        } else {
            memoryRepository.searchFts(query, options.ftsTopK)
        }

        // 2. 图谱召回
        val graphResults = graphRetriever!!.retrieve(query, options.finalTopK)

        // 3. RRF 融合（复用 RRFFuser，DRY）
        val ftsScored = ftsResults.mapIndexed { i, e -> ScoredItem(e.id, 1f / (options.rrfK + i + 1)) }
        val fused = rrfFuser.fuse(
            listOf(
                RankedList("fts", ftsScored),
                RankedList("graph", graphResults)
            ),
            k = options.rrfK
        ).take(options.finalTopK * 2)

        // 4. 取回 entry
        val entryById = mutableMapOf<String, CommonMemoryEntry>()
        val entries = mutableListOf<CommonMemoryEntry>()
        for (item in fused) {
            val entry = memoryRepository.findById(item.id) ?: continue
            entryById[entry.id] = entry
            entries.add(entry)
        }

        // 5. post-fusion 重排
        val reranked = postFusionReranker!!.rerank(query, fused, entries, options.finalTopK * 2)

        // 6. cosineReScore（本期 cosineWeight=0，纯 rrf）
        val rescored = reranked.map { item ->
            val rrfScore = item.score
            val finalScore = (1f - cosineWeight) * rrfScore + cosineWeight * 0f
            item.copy(score = finalScore)
        }

        // 7. floor-ratio gate：裁剪低于 best×floorRatio 的弱命中
        val best = rescored.maxOfOrNull { it.score } ?: 0f
        val threshold = best * floorRatio
        val gated = rescored.filter { it.score >= threshold }

        // 8. 映射回 entry 并取 topK
        return gated.mapNotNull { item -> entryById[item.id]?.let { it to item.score } }
            .take(options.finalTopK)
    }

    /** 兼容路径：原 FTS4 + LightweightReranker（graphRetriever/postFusionReranker 为 null 时） */
    private suspend fun legacySearch(
        query: String,
        agentId: String?,
        options: SearchOptions
    ): List<Pair<CommonMemoryEntry, Float>> {
        val ftsResults = if (agentId != null) {
            memoryRepository.searchFtsByAgentId(query, agentId, limit = options.ftsTopK)
        } else {
            memoryRepository.searchFts(query, limit = options.ftsTopK)
        }
        if (ftsResults.isEmpty()) return emptyList()

        // 构建 ID→Entry 索引
        val entryById = mutableMapOf<String, CommonMemoryEntry>()
        for (entry in ftsResults) {
            entryById[entry.id] = entry
        }

        // 初始评分（FTS4 无直接分数，用排名倒数近似）
        val ftsScored = ftsResults.mapIndexed { index, entry ->
            ScoredItem(entry.id, 1f / (index + 1))
        }

        // RRF 融合 — 单源退化为恒等
        val fused = rrfFuser.fuse(listOf(
            RankedList("fts", ftsScored)
        ), k = options.rrfK)

        // 按融合分数重排序，构建 (entry, score) 对列表
        val rerankable = fused.mapNotNull { scored ->
            entryById[scored.id]?.let { entry -> entry to scored.score }
        }

        // 启发式重排序
        val candidates = rerankable.map { (entry, _) ->
            ScoredItem(entry.id, 0f)
        }
        val entries = rerankable.map { it.first }

        return reranker.rerank(query, candidates, entries, topK = options.finalTopK)
    }
}

data class SearchOptions(
    val ftsTopK: Int = 50,
    val rrfK: Int = 60,
    val rerankTopK: Int = 20,
    val finalTopK: Int = 8
)
