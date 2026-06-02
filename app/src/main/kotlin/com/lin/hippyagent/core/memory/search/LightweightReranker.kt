package com.lin.hippyagent.core.memory.search

import com.lin.hippyagent.core.memory.ChineseTokenizer
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry

/**
 * 轻量级重排序器 — 基于启发式规则对搜索结果进行重排序
 *
 * Phase 1 实现：纯算法，无 LLM/ONNX 调用
 * 评分维度：关键词覆盖率 + 时效性 + 置信度 + 结构特征
 *
 * 参考: gbrain search/rerank/Reranker.ts
 */
class LightweightReranker {

    /**
     * 重排序候选项
     * @param query 原始查询
     * @param candidates 候选结果列表（已含分数）
     * @param entries 对应的 CommonMemoryEntry 列表（与 candidates 顺序一致）
     * @param topK 最终返回数量
     */
    fun rerank(
        query: String,
        candidates: List<ScoredItem>,
        entries: List<CommonMemoryEntry>,
        topK: Int = 8
    ): List<Pair<CommonMemoryEntry, Float>> {
        if (candidates.isEmpty() || entries.isEmpty()) return emptyList()

        val maxScore = candidates.maxOf { it.score }.coerceAtLeast(0.001f)
        val queryTerms = ChineseTokenizer.segment(query.lowercase()).filter { it.length > 1 }

        val enhanced = candidates.mapIndexed { index, item ->
            val entry = entries.getOrNull(index) ?: return@mapIndexed null as?
                    Pair<CommonMemoryEntry, Float>
            val normalizedBase = item.score / maxScore
            val boost = calculateBoost(queryTerms, entry)
            val finalScore = normalizedBase * 0.6f + boost * 0.4f
            entry to finalScore
        }.filterNotNull()

        return enhanced
            .sortedByDescending { it.second }
            .take(topK)
    }

    /**
     * 计算增强分数
     * @return 0.0 ~ 1.0 的增强分值
     */
    private fun calculateBoost(queryTerms: List<String>, entry: CommonMemoryEntry): Float {
        if (queryTerms.isEmpty()) return 0f

        var boost = 0f

        val summaryLower = entry.summary.lowercase()
        if (queryTerms.isNotEmpty()) {
            val matchCount = queryTerms.count { term -> summaryLower.contains(term) }
            boost += (matchCount.toFloat() / queryTerms.size) * 0.4f
        }

        // 2. 标题/结构化内容加分 (权重 0.15)
        if (entry.summary.startsWith("#") || entry.summary.startsWith("**")) {
            boost += 0.15f
        }

        // 3. 时效性加分 (权重 0.20)
        val ageHours = (System.currentTimeMillis() - entry.updatedAt) / (1000 * 60 * 60)
        if (ageHours < 24) boost += 0.20f
        else if (ageHours < 168) boost += 0.10f   // 7天内
        else if (ageHours < 720) boost += 0.05f    // 30天内

        // 4. 置信度加分 (权重 0.15)
        boost += entry.confidence.coerceIn(0f, 1f) * 0.15f

        // 5. 证据次数加分 (权重 0.10)
        if (entry.evidenceCount >= 5) boost += 0.10f
        else if (entry.evidenceCount >= 3) boost += 0.05f

        // 6. 查询词在详情中的覆盖加分 (权重 0.10，额外)
        if (entry.detail != null && queryTerms.isNotEmpty()) {
            val detailLower = entry.detail.lowercase()
            val detailMatchCount = queryTerms.count { term -> detailLower.contains(term) }
            if (queryTerms.isNotEmpty()) {
                boost += (detailMatchCount.toFloat() / queryTerms.size) * 0.10f
            }
        }

        return boost.coerceIn(0f, 1f)
    }
}
