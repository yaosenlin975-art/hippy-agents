package com.lin.hippyagent.core.knowledge

import com.lin.hippyagent.core.pool.FastId
import timber.log.Timber

/**
 * 零 LLM 链接抽取器：从文本抽取链接 → SlugResolver 归一化 → 写入图谱关系。
 *
 * 集成点：MemoryExtractor 写入记忆后调用，建立记忆间的图谱链接。
 * 与 EntityExtractor（语义关系）互补：本类只处理 markdown 链接语法。
 */
class LinkExtractor(
    private val slugResolver: SlugResolver,
    private val knowledgeGraphStore: KnowledgeGraphStore
) {
    /**
     * 从源文本抽取链接，建立 sourceEntityId → 目标实体的图谱关系。
     *
     * @param sourceEntityId 源实体（通常是记忆对应的图谱实体）
     * @param text 源文本
     * @return 成功建立的关系列表
     */
    suspend fun extractAndLink(sourceEntityId: String, text: String): List<GraphRelation> {
        val links = LinkExtractionPatterns.extractAll(text)
        if (links.isEmpty()) return emptyList()

        val relations = mutableListOf<GraphRelation>()
        for (link in links) {
            // runCatching 各走各路：单个 slug 解析失败不影响其他
            val resolved = runCatching { slugResolver.resolve(link.slug) }.getOrNull()
                ?: continue

            val linkType = inferLinkType(link, text)
            val relation = GraphRelation(
                id = FastId.next(),
                sourceId = sourceEntityId,
                targetId = resolved.entityId,
                type = linkType,
                properties = mapOf(
                    "source" to "link_extraction",
                    "link_type" to link.type.name,
                    "alias" to (link.alias ?: "")
                ),
                confidence = computeLinkConfidence(link)
            )
            runCatching { knowledgeGraphStore.addRelation(relation) }
                .onSuccess { relations.add(relation) }
                .onFailure { Timber.w(it, "LinkExtractor: addRelation failed for ${link.slug}") }
        }
        return relations
    }

    /** 双层类型推断：链接语法层 + 上下文语义层 */
    private fun inferLinkType(link: ExtractedLink, text: String): RelationType {
        // 第一层：链接语法
        if (link.type == LinkType.QUALIFIED_WIKILINK || link.type == LinkType.WIKILINK) {
            return RelationType.RELATED_TO
        }

        // 第二层：上下文语义（关键词匹配）
        val contextWindow = extractContext(text, link.range, 40)
        return when {
            contextWindow.contains("使用") || contextWindow.contains("基于") -> RelationType.USED_IN
            KEYWORDS_DEPENDS.any { contextWindow.contains(it, ignoreCase = true) } -> RelationType.DEPENDS_ON
            KEYWORDS_BELONGS.any { contextWindow.contains(it, ignoreCase = true) } -> RelationType.BELONGS_TO
            KEYWORDS_CREATED.any { contextWindow.contains(it, ignoreCase = true) } -> RelationType.CREATED_BY
            else -> RelationType.RELATED_TO
        }
    }

    private fun extractContext(text: String, range: IntRange, window: Int): String {
        val start = (range.first - window).coerceAtLeast(0)
        val end = (range.last + window + 1).coerceAtMost(text.length)
        return text.substring(start, end)
    }

    private fun computeLinkConfidence(link: ExtractedLink): Float = when (link.type) {
        LinkType.QUALIFIED_WIKILINK -> 0.9f
        LinkType.WIKILINK -> 0.8f
        LinkType.FRONTMATTER -> 0.7f
        LinkType.ENTITY_REF -> 0.5f
    }

    companion object {
        private val KEYWORDS_DEPENDS = listOf("依赖", "需要", "depends")
        private val KEYWORDS_BELONGS = listOf("属于", "归属", "part of")
        private val KEYWORDS_CREATED = listOf("创建", "开发", "created")
    }
}
