package com.lin.hippyagent.core.memory.search

import com.lin.hippyagent.core.knowledge.EntityExtractor
import com.lin.hippyagent.core.knowledge.KnowledgeGraphStore

/**
 * 图谱检索器：从查询中抽取实体 → 查图谱关联实体 → 返回关联记忆 ID。
 *
 * 设计：
 * - 复用 EntityExtractor 做零 LLM 实体抽取
 * - 通过 KnowledgeGraphStore 查 1 跳关系
 * - 返回 (memoryId, score) 对，score 基于 relation confidence
 * - 上限 maxEntities 防止图谱爆炸
 */
class GraphRetriever(
    private val knowledgeGraphStore: KnowledgeGraphStore,
    private val entityExtractor: EntityExtractor,
    private val maxEntities: Int = 5
) {
    suspend fun retrieve(query: String, limit: Int): List<ScoredItem> {
        val entities = entityExtractor.extract(query).take(maxEntities)
        if (entities.isEmpty()) return emptyList()

        val results = mutableListOf<ScoredItem>()
        for (entity in entities) {
            val graphEntities = knowledgeGraphStore.searchEntities(entity.name)
            for (graphEntity in graphEntities) {
                val relations = knowledgeGraphStore.getRelationsForEntity(graphEntity.id)
                for (relation in relations) {
                    // 关联实体的 properties 中的 memoryId 作为召回结果
                    val memoryId = relation.properties["memoryId"] ?: continue
                    results.add(ScoredItem(memoryId, relation.confidence))
                }
            }
        }
        return results
            .distinctBy { it.id }
            .sortedByDescending { it.score }
            .take(limit)
    }
}
