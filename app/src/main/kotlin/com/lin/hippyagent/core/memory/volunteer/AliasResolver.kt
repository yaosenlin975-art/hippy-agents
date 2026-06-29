package com.lin.hippyagent.core.memory.volunteer

import com.lin.hippyagent.core.knowledge.KnowledgeGraphStore

/**
 * Alias 命中查询器（T2-1 PostFusionReranker + T2-2 VolunteerContextInjector 共享）。
 *
 * 判断查询字符串是否命中某记忆的 alias（图谱实体 name）。
 */
class AliasResolver(
    private val knowledgeGraphStore: KnowledgeGraphStore
) {
    suspend fun matchesAnyAlias(query: String, memoryId: String): Boolean {
        val entities = knowledgeGraphStore.searchEntities(query)
        return entities.any { it.properties["memoryId"] == memoryId }
    }
}
