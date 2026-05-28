package com.lin.hippyagent.core.memory.search

import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry

/**
 * 混合搜索引擎 — 融合 FTS4 + RRF + 重排序
 *
 * 渐进式方案:
 * - Phase 1 (当前): FTS4 + RRF(单源退化为恒等) + LightweightReranker
 * - Phase 2: + ONNX 嵌入 + VectorStore 余弦相似度 → 双源 RRF
 * - Phase 3: + NNAPI 加速
 *
 * 参考: gbrain search/SearchEngine.ts
 */
class HybridSearchEngine(
    private val memoryRepository: MemoryRepository,
    private val rrfFuser: RRFFuser = RRFFuser(),
    private val reranker: LightweightReranker = LightweightReranker()
) {
    /**
     * 混合搜索 — Phase 1: FTS4 + 重排序
     * Phase 2 后将升级为: 语义向量 + FTS4 + RRF + 重排序
     */
    suspend fun search(
        query: String,
        options: SearchOptions = SearchOptions()
    ): List<Pair<CommonMemoryEntry, Float>> {
        return search(query, null, options)
    }

    suspend fun search(
        query: String,
        agentId: String?,
        options: SearchOptions = SearchOptions()
    ): List<Pair<CommonMemoryEntry, Float>> {
        if (query.isBlank()) return emptyList()

        val ftsResults = if (agentId != null) {
            memoryRepository.searchFtsByAgentId(query, agentId, limit = options.ftsTopK)
        } else {
            memoryRepository.searchFts(query, limit = options.ftsTopK)
        }
        if (ftsResults.isEmpty()) return emptyList()

        // 2. 构建 ID→Entry 索引
        val entryById = mutableMapOf<String, CommonMemoryEntry>()
        val idOrder = mutableListOf<String>()
        for (entry in ftsResults) {
            entryById[entry.id] = entry
            idOrder.add(entry.id)
        }

        // 3. 初始评分（FTS4 无直接分数，用排名倒数近似）
        val ftsScored = ftsResults.mapIndexed { index, entry ->
            ScoredItem(entry.id, 1f / (index + 1))
        }

        // 4. RRF 融合 — Phase 1 只有 FTS4 单源，退化为恒等
        val fused = rrfFuser.fuse(listOf(
            RankedList("fts", ftsScored)
        ), k = options.rrfK)

        // 5. 按融合分数重排序，构建 (entry, score) 对列表
        val rerankable = fused.mapNotNull { scored ->
            entryById[scored.id]?.let { entry -> entry to scored.score }
        }

        // 6. 启发式重排序
        val candidates = rerankable.map { (entry, _) ->
            ScoredItem(entry.id, 0f)  // 分数由 reranker 重新计算
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
