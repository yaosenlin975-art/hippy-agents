package com.lin.hippyagent.core.knowledge

import com.lin.hippyagent.core.pool.FastId

/**
 * Slug 归一化器：将链接中的 slug 映射到图谱实体 ID。
 *
 * 规则（gbrain 原版）：
 * 1. slug 小写化 + 空格/下划线转连字符
 * 2. 精确匹配图谱实体 name
 * 3. 模糊匹配（contains 双向）
 * 4. 未匹配则创建新实体（零 LLM，标记 source="link_extraction"）
 */
class SlugResolver(
    private val knowledgeGraphStore: KnowledgeGraphStore
) {
    suspend fun resolve(slug: String): ResolvedSlug {
        val normalized = normalizeSlug(slug)

        // 1. 精确匹配
        val exact = knowledgeGraphStore.searchEntities(normalized)
            .firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        if (exact != null) {
            return ResolvedSlug(exact.id, exact.name, ResolutionType.EXACT)
        }

        // 2. 模糊匹配（contains 双向）
        val fuzzy = knowledgeGraphStore.searchEntities(normalized)
            .firstOrNull {
                it.name.contains(normalized, ignoreCase = true) ||
                normalized.contains(it.name, ignoreCase = true)
            }
        if (fuzzy != null) {
            return ResolvedSlug(fuzzy.id, fuzzy.name, ResolutionType.FUZZY)
        }

        // 3. 未匹配，创建新实体
        val newEntity = GraphEntity(
            id = FastId.next(),
            type = inferEntityType(normalized),
            name = normalized,
            properties = mapOf("source" to "link_extraction", "original_slug" to slug),
            confidence = 0.5f
        )
        knowledgeGraphStore.addEntity(newEntity)
        return ResolvedSlug(newEntity.id, newEntity.name, ResolutionType.CREATED_NEW)
    }

    private fun normalizeSlug(slug: String): String {
        return SLUG_NORMALIZE_REGEX.replace(slug.lowercase(), "-").trim('-')
    }

    /** 双层类型推断：基于 EntityPatterns 词典 */
    private fun inferEntityType(name: String): EntityType {
        if (EntityPatterns.CITIES.any { it.equals(name, ignoreCase = true) } ||
            EntityPatterns.COUNTRIES.any { it.equals(name, ignoreCase = true) }) {
            return EntityType.LOCATION
        }
        if (EntityPatterns.TECHNOLOGY_KEYWORDS.any { it.equals(name, ignoreCase = true) }) {
            return EntityType.TECHNOLOGY
        }
        if (EntityPatterns.CONCEPT_KEYWORDS.any { it.equals(name, ignoreCase = true) }) {
            return EntityType.CONCEPT
        }
        return EntityType.CONCEPT
    }

    companion object {
        private val SLUG_NORMALIZE_REGEX = Regex("[\\s_]+")
    }
}

data class ResolvedSlug(
    val entityId: String,
    val canonicalName: String,
    val resolutionType: ResolutionType
) {
    enum class ResolutionType { EXACT, FUZZY, CREATED_NEW }
}
