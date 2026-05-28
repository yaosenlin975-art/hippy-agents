package com.lin.hippyagent.core.knowledge

import com.lin.hippyagent.core.pool.FastId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Dream-知识图谱桥接器 - 连接 Dream 记忆系统与知识图谱。
 *
 * 在 Deep/REM Dream 阶段从记忆内容中提取实体和关系，
 * 写入知识图谱；在检索阶段用图谱实体丰富查询。
 */
class DreamEntityBridge(
    private val entityExtractor: EntityExtractor,
    private val graphStore: KnowledgeGraphStore
) {

    /**
     * 从 Dream 记忆内容中提取实体并同步到知识图谱。
     *
     * 流程：
     * 1. 从记忆文本中提取实体
     * 2. 从记忆文本中提取实体间关系
     * 3. 将实体和关系写入知识图谱（已存在则合并属性）
     *
     * @param memoryContent Dream 记忆的文本内容
     */
    suspend fun syncEntitiesFromMemory(memoryContent: String) = withContext(Dispatchers.IO) {
        if (memoryContent.isBlank()) {
            Timber.d("Memory content is blank, skipping entity sync")
            return@withContext
        }

        runCatching {
            Timber.i("Starting entity sync from memory (${memoryContent.length} chars)")

            // 1. 提取实体
            val extractedEntities = entityExtractor.extract(memoryContent)
            Timber.d("Extracted ${extractedEntities.size} entities")

            // 2. 写入实体到知识图谱，收集已存在的实体用于关系匹配
            val entityNameToId = mutableMapOf<String, String>()

            for (extracted in extractedEntities) {
                if (extracted.confidence < MIN_ENTITY_CONFIDENCE) {
                    Timber.d("Skipping low-confidence entity: ${extracted.name} (${extracted.confidence})")
                    continue
                }

                val graphEntity = extracted.toGraphEntity()
                val result = graphStore.addEntity(graphEntity)
                result.onSuccess { entity ->
                    entityNameToId[extracted.name.lowercase()] = entity.id
                    Timber.d("Added entity: ${extracted.name} (${extracted.type})")
                }.onFailure { e ->
                    Timber.w(e, "Failed to add entity: ${extracted.name}")
                }
            }

            // 3. 提取关系（传入已提取的实体用于验证关系端点）
            val extractedRelations = entityExtractor.extractRelations(memoryContent, extractedEntities)
            Timber.d("Extracted ${extractedRelations.size} relations")

            // 4. 写入关系到知识图谱
            var relationsAdded = 0
            for (relation in extractedRelations) {
                if (relation.confidence < MIN_RELATION_CONFIDENCE) continue

                val sourceId = entityNameToId[relation.source.lowercase()]
                val targetId = entityNameToId[relation.target.lowercase()]

                if (sourceId != null && targetId != null) {
                    val graphRelation = relation.toGraphRelation(sourceId, targetId)
                    val result = graphStore.addRelation(graphRelation)
                    result.onSuccess {
                        relationsAdded++
                    }.onFailure { e ->
                        Timber.w(e, "Failed to add relation: ${relation.source} -> ${relation.target}")
                    }
                }
            }

            Timber.i("Entity sync completed: ${entityNameToId.size} entities, $relationsAdded relations")
        }.onFailure { e ->
            Timber.e(e, "Entity sync from memory failed")
        }
    }

    /**
     * 用知识图谱实体丰富查询。
     *
     * 从查询文本中提取实体，然后查找图谱中相关实体，
     * 返回扩展后的查询关键词列表。
     *
     * @param query 原始查询文本
     * @return 扩展后的关键词列表（包含原始查询中的实体及其图谱邻居）
     */
    suspend fun enrichQueryWithEntities(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        runCatching {
            val enrichedTerms = mutableListOf<String>()

            // 1. 从查询中提取实体
            val extractedEntities = entityExtractor.extract(query)
            val entityNames = extractedEntities.map { it.name }

            enrichedTerms.addAll(entityNames)

            // 2. 在图谱中搜索匹配的实体，获取其关联实体
            for (entity in extractedEntities) {
                val graphEntities = graphStore.searchEntities(entity.name)
                for (graphEntity in graphEntities) {
                    // 获取关联实体
                    val relations = graphStore.getRelationsForEntity(graphEntity.id)
                    for (relation in relations) {
                        val neighborId = if (relation.sourceId == graphEntity.id) {
                            relation.targetId
                        } else {
                            relation.sourceId
                        }

                        val neighbor = graphStore.getEntity(neighborId)
                        if (neighbor != null && neighbor.name !in enrichedTerms) {
                            enrichedTerms.add(neighbor.name)
                        }
                    }

                    // 获取关联实体的类型信息作为上下文
                    enrichedTerms.add("${graphEntity.name}:${graphEntity.type}")
                }
            }

            val result = enrichedTerms.distinct()
            Timber.d("Enriched query: ${entityNames.size} entities -> ${result.size} terms")
            result
        }.onFailure { e ->
            Timber.e(e, "Query enrichment failed")
            // 降级：仅返回原始查询的实体
            entityExtractor.extract(query).map { it.name }
        }.getOrDefault(entityExtractor.extract(query).map { it.name })
    }

    /**
     * REM Dream 专属：深度更新知识图谱关系。
     *
     * 重新扫描所有实体，检测隐含关系，更新关系权重。
     * 与 syncEntitiesFromMemory 不同，此方法关注已有实体的关系补全。
     */
    suspend fun updateRelationships() = withContext(Dispatchers.IO) {
        runCatching {
            Timber.i("Starting deep relationship update for REM Dream")

            // 获取所有实体
            val allEntities = graphStore.getAllEntities()
            Timber.d("Scanning ${allEntities.size} entities for missing relationships")

            var newRelations = 0
            for (entity in allEntities) {
                // 获取该实体已有关系
                val existingRelations = graphStore.getRelationsForEntity(entity.id)
                val existingTargets = existingRelations.map { 
                    if (it.sourceId == entity.id) it.targetId else it.sourceId 
                }.toSet()

                // 查找可能相关的实体（同名/同类型）
                val relatedEntities = graphStore.searchEntities(entity.name)
                for (related in relatedEntities) {
                    if (related.id == entity.id) continue
                    if (related.id in existingTargets) continue

                    // 创建隐含关系
                    val relation = com.lin.hippyagent.core.knowledge.GraphRelation(
                        id = FastId.next(),
                        sourceId = entity.id,
                        targetId = related.id,
                        type = com.lin.hippyagent.core.knowledge.RelationType.RELATED_TO,
                        confidence = 0.5f,
                        properties = mapOf("discovered_by" to "rem_dream")
                    )
                    val result = graphStore.addRelation(relation)
                    if (result.isSuccess) newRelations++
                }
            }

            Timber.i("Deep relationship update completed: $newRelations new relations discovered")
        }.onFailure { e ->
            Timber.e(e, "Deep relationship update failed")
        }
    }

    companion object {
        /** 实体最低置信度阈值 */
        private const val MIN_ENTITY_CONFIDENCE = 0.5f
        /** 关系最低置信度阈值 */
        private const val MIN_RELATION_CONFIDENCE = 0.4f
    }
}

