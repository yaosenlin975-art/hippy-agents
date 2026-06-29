package com.lin.hippyagent.core.memory.search

import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import com.lin.hippyagent.core.memory.volunteer.AliasResolver

/**
 * Post-fusion 6 阶段重排器。
 * 每阶段对 RRF 融合后的结果施加加权调整。
 *
 * 阶段顺序（gbrain 原版）：
 * 1. backlink       ── 被其他记忆链接次数（图谱入度）
 * 2. salience       ── confidence * 0.5 + importance * 0.5
 * 3. recency        ── 时间衰减：max(0, 1 - ageDays / 180)
 * 4. title          ── 查询词出现在 summary 中加分
 * 5. graph_signals  ── 图谱中心度（关联实体数）
 * 6. alias_resolved ── alias 命中加分
 *
 * 每阶段输出 [0, 1] 分数，加权叠加到 RRF 分数上（Float 运算）。
 */
class PostFusionReranker(
    private val graphBacklinkCounter: GraphBacklinkCounter,
    private val aliasResolver: AliasResolver
) {
    suspend fun rerank(
        query: String,
        items: List<ScoredItem>,
        entries: List<CommonMemoryEntry>,
        limit: Int
    ): List<ScoredItem> {
        if (items.isEmpty()) return emptyList()
        val entryById = entries.associateBy { it.id }
        val queryLower = query.lowercase()
        val now = System.currentTimeMillis()

        val scored = items.mapNotNull { item ->
            val entry = entryById[item.id] ?: return@mapNotNull null
            var bonus = 0f

            // 1. backlink
            val backlinks = graphBacklinkCounter.countBacklinks(item.id)
            bonus += (backlinks.coerceAtMost(5) / 5f) * BACKLINK_WEIGHT

            // 2. salience
            val salience = entry.confidence * 0.5f + entry.importance * 0.5f
            bonus += salience * SALIENCE_WEIGHT

            // 3. recency
            val ageDays = (now - entry.lastSeenAt) / (1000f * 60 * 60 * 24)
            val recency = (1f - (ageDays / 180f)).coerceIn(0f, 1f)
            bonus += recency * RECENCY_WEIGHT

            // 4. title (summary 命中)
            if (entry.summary.lowercase().contains(queryLower)) {
                bonus += TITLE_WEIGHT
            }

            // 5. graph_signals (关联实体数，复用 backlink counter 的总度数)
            val degree = graphBacklinkCounter.countDegree(item.id)
            bonus += (degree.coerceAtMost(10) / 10f) * GRAPH_SIGNALS_WEIGHT

            // 6. alias_resolved
            if (aliasResolver.matchesAnyAlias(query, item.id)) {
                bonus += ALIAS_WEIGHT
            }

            item.copy(score = item.score + bonus)
        }

        return scored.sortedByDescending { it.score }.take(limit)
    }

    companion object {
        private const val BACKLINK_WEIGHT = 0.10f
        private const val SALIENCE_WEIGHT = 0.20f
        private const val RECENCY_WEIGHT = 0.15f
        private const val TITLE_WEIGHT = 0.15f
        private const val GRAPH_SIGNALS_WEIGHT = 0.10f
        private const val ALIAS_WEIGHT = 0.10f
    }
}
