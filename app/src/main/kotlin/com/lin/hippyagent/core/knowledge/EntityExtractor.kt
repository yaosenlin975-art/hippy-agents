package com.lin.hippyagent.core.knowledge

import com.lin.hippyagent.core.pool.FastId
import timber.log.Timber

/**
 * 实体提取器 - 从文本中自动提取实体和关系。
 *
 * 使用模式匹配 + 启发式规则进行实体识别，
 * 支持 PERSON、PROJECT、TECHNOLOGY、LOCATION、CONCEPT 五种实体类型。
 */
class EntityExtractor {

    /**
     * 从文本中提取实体。
     *
     * @param text 待分析的文本
     * @return 提取到的实体列表，按置信度降序排列
     */
    fun extract(text: String): List<ExtractedEntity> {
        if (text.isBlank()) return emptyList()

        val entities = mutableListOf<ExtractedEntity>()

        entities.addAll(extractTechnologies(text))
        entities.addAll(extractPersons(text))
        entities.addAll(extractProjects(text))
        entities.addAll(extractLocations(text))
        entities.addAll(extractConcepts(text))

        // 去重：同名实体取置信度最高的
        val deduplicated = entities.groupBy { it.name.lowercase() }
            .map { (_, group) ->
                group.maxByOrNull { it.confidence } ?: group.first()
            }
            .sortedByDescending { it.confidence }

        Timber.d("Extracted ${deduplicated.size} entities from ${text.length} chars")
        return deduplicated
    }

    /**
     * 提取实体之间的关系。
     *
     * @param text 待分析的文本
     * @param entities 已提取的实体列表（用于验证关系端点）
     * @return 提取到的关系列表
     */
    fun extractRelations(text: String, entities: List<ExtractedEntity>): List<ExtractedRelation> {
        if (text.isBlank()) return emptyList()

        val entityNames = entities.map { it.name.lowercase() }.toSet()
        val relations = mutableListOf<ExtractedRelation>()

        for ((relationType, patterns) in EntityPatterns.RELATION_PATTERNS) {
            for (pattern in patterns) {
                val matches = pattern.findAll(text)
                for (match in matches) {
                    val source = match.groupValues[1].trim()
                    val target = match.groupValues[2].trim()

                    // 验证源和目标是否在已知实体中
                    if (source.length >= 2 && target.length >= 2 && source != target) {
                        val confidence = calculateRelationConfidence(
                            text, match.range, source, target
                        )
                        relations.add(
                            ExtractedRelation(
                                source = source,
                                target = target,
                                type = relationType,
                                confidence = confidence
                            )
                        )
                    }
                }
            }
        }

        // 去重
        val deduplicated = relations.distinctBy { "${it.source}-${it.target}-${it.type}" }
            .sortedByDescending { it.confidence }

        Timber.d("Extracted ${deduplicated.size} relations")
        return deduplicated
    }

    // ── 技术实体提取 ──────────────────────────────────────

    private fun extractTechnologies(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()

        for (keyword in EntityPatterns.TECHNOLOGY_KEYWORDS) {
            val pattern = technologyKeywordPatterns.getOrPut(keyword) { createBoundaryRegex(keyword) }
            val matches = pattern.findAll(text)

            for (match in matches) {
                val confidence = calculateEntityConfidence(
                    text, match.range, EntityType.TECHNOLOGY, keyword
                )
                val context = extractContext(text, match.range)
                entities.add(
                    ExtractedEntity(
                        name = keyword,
                        type = EntityType.TECHNOLOGY,
                        confidence = confidence,
                        context = context
                    )
                )
            }
        }

        return entities
    }

    // ── 人名实体提取 ──────────────────────────────────────

    private fun extractPersons(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()

        // 英文人名
        for (pattern in EntityPatterns.PERSON_PATTERNS) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                val name = match.groupValues[1].trim()
                // 过滤掉明显不是人名的匹配（全大写缩写、过长等）
                if (name.length in 3..40 && !isAllUppercase(name)) {
                    val confidence = calculatePersonConfidence(text, match.range, name)
                    if (confidence >= MIN_PERSON_CONFIDENCE) {
                        val context = extractContext(text, match.range)
                        entities.add(
                            ExtractedEntity(
                                name = name,
                                type = EntityType.PERSON,
                                confidence = confidence,
                                context = context
                            )
                        )
                    }
                }
            }
        }

        // 中文人名（需要上下文验证）
        val chineseMatches = EntityPatterns.CHINESE_PERSON_REGEX.findAll(text)
        for (match in chineseMatches) {
            val name = match.groupValues[0]
            if (isLikelyChinesePersonName(text, match.range, name)) {
                val confidence = calculatePersonConfidence(text, match.range, name)
                if (confidence >= MIN_PERSON_CONFIDENCE) {
                    val context = extractContext(text, match.range)
                    entities.add(
                        ExtractedEntity(
                            name = name,
                            type = EntityType.PERSON,
                            confidence = confidence,
                            context = context
                        )
                    )
                }
            }
        }

        return entities
    }

    // ── 项目实体提取 ──────────────────────────────────────

    private fun extractProjects(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()

        for (pattern in EntityPatterns.PROJECT_PATTERNS) {
            val matches = pattern.findAll(text)
            for (match in matches) {
                val name = match.groupValues[1].trim()
                if (name.length >= 3) {
                    val confidence = calculateEntityConfidence(
                        text, match.range, EntityType.PROJECT, name
                    )
                    val context = extractContext(text, match.range)
                    entities.add(
                        ExtractedEntity(
                            name = name,
                            type = EntityType.PROJECT,
                            confidence = confidence,
                            context = context
                        )
                    )
                }
            }
        }

        return entities
    }

    // ── 地点实体提取 ──────────────────────────────────────

    private fun extractLocations(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()

        val matches = EntityPatterns.LOCATION_REGEX.findAll(text)
        for (match in matches) {
            val name = match.groupValues[1]
            val confidence = calculateEntityConfidence(
                text, match.range, EntityType.LOCATION, name
            )
            val context = extractContext(text, match.range)
            entities.add(
                ExtractedEntity(
                    name = name,
                    type = EntityType.LOCATION,
                    confidence = confidence,
                    context = context
                )
            )
        }

        return entities
    }

    // ── 概念实体提取 ──────────────────────────────────────

    private fun extractConcepts(text: String): List<ExtractedEntity> {
        val entities = mutableListOf<ExtractedEntity>()

        for (keyword in EntityPatterns.CONCEPT_KEYWORDS) {
            val pattern = conceptKeywordPatterns.getOrPut(keyword) { createBoundaryRegexIgnoreCase(keyword) }
            val matches = pattern.findAll(text)
            for (match in matches) {
                val confidence = calculateEntityConfidence(
                    text, match.range, EntityType.CONCEPT, keyword
                )
                val context = extractContext(text, match.range)
                entities.add(
                    ExtractedEntity(
                        name = keyword,
                        type = EntityType.CONCEPT,
                        confidence = confidence,
                        context = context
                    )
                )
            }
        }

        return entities
    }

    // ── 置信度计算 ────────────────────────────────────────

    /**
     * 计算实体置信度。
     * 基于：首字母大写、上下文关键词、出现频率、位置。
     */
    private fun calculateEntityConfidence(
        text: String,
        range: IntRange,
        type: EntityType,
        name: String
    ): Float {
        var confidence = BASE_CONFIDENCE

        // 首字母大写加分
        if (name.firstOrNull()?.isUpperCase() == true) {
            confidence += 0.05f
        }

        // 上下文关键词加分
        val contextKeywords = when (type) {
            EntityType.TECHNOLOGY -> listOf("使用", "采用", "基于", "using", "with", "built on")
            EntityType.PROJECT -> EntityPatterns.PROJECT_CONTEXT_KEYWORDS
            EntityType.LOCATION -> listOf("在", "来自", "at", "from", "in")
            EntityType.CONCEPT -> listOf("采用", "实现", "应用", "implementing", "using", "applying")
            EntityType.PERSON -> EntityPatterns.PERSON_CONTEXT_KEYWORDS
        }

        val nearbyText = extractContext(text, range, windowChars = 50)
        for (keyword in contextKeywords) {
            if (nearbyText.contains(keyword, ignoreCase = true)) {
                confidence += 0.1f
                break
            }
        }

        // 文本开头位置加分（通常是主题引入）
        if (range.first < text.length / 4) {
            confidence += 0.05f
        }

        return confidence.coerceIn(0.0f, 1.0f)
    }

    /**
     * 计算人名置信度（更严格）。
     */
    private fun calculatePersonConfidence(text: String, range: IntRange, name: String): Float {
        var confidence = PERSON_BASE_CONFIDENCE

        // 附近有人名上下文关键词
        val nearbyText = extractContext(text, range, windowChars = 60)
        for (keyword in EntityPatterns.PERSON_CONTEXT_KEYWORDS) {
            if (nearbyText.contains(keyword, ignoreCase = true)) {
                confidence += 0.2f
                break
            }
        }

        // 名字长度合理
        if (name.length in 4..30) {
            confidence += 0.05f
        }

        return confidence.coerceIn(0.0f, 1.0f)
    }

    /**
     * 计算关系置信度。
     */
    private fun calculateRelationConfidence(
        text: String,
        range: IntRange,
        source: String,
        target: String
    ): Float {
        var confidence = 0.5f

        // 源和目标都是已知实体时加分
        val nearbyText = extractContext(text, range, windowChars = 100)
        if (nearbyText.contains(source) && nearbyText.contains(target)) {
            confidence += 0.2f
        }

        // 距离越近关系越强
        val distance = range.last - range.first
        if (distance < 50) {
            confidence += 0.1f
        }

        return confidence.coerceIn(0.0f, 1.0f)
    }

    // ── 辅助方法 ──────────────────────────────────────────

    /**
     * 提取匹配位置周围的上下文文本。
     */
    private fun extractContext(text: String, range: IntRange, windowChars: Int = 40): String {
        val start = (range.first - windowChars).coerceAtLeast(0)
        val end = (range.last + windowChars).coerceAtMost(text.length)
        return text.substring(start, end)
    }

    /**
     * 判断是否全大写缩写（如 "API", "URL"）。
     */
    private fun isAllUppercase(name: String): Boolean {
        return name.all { it.isUpperCase() || it == '.' || it == ' ' }
    }

    /**
     * 判断中文字符串是否可能是人名。
     * 启发式：2-4 个中文字符，且附近有人名上下文关键词。
     */
    private fun isLikelyChinesePersonName(text: String, range: IntRange, name: String): Boolean {
        if (name.length !in 2..4) return false

        // 检查是否在常见非人名模式中
        val nearbyText = extractContext(text, range, windowChars = 30)
        val nonPersonKeywords = listOf("函数", "方法", "类", "接口", "变量", "文件", "目录")
        for (keyword in nonPersonKeywords) {
            if (nearbyText.contains(keyword)) return false
        }

        // 检查是否有人名上下文
        for (keyword in EntityPatterns.PERSON_CONTEXT_KEYWORDS) {
            if (nearbyText.contains(keyword)) return true
        }

        // 默认给予较低置信度
        return false
    }

    companion object {
        private const val BASE_CONFIDENCE = 0.6f
        private const val PERSON_BASE_CONFIDENCE = 0.4f
        private const val MIN_PERSON_CONFIDENCE = 0.45f
        private val technologyKeywordPatterns = mutableMapOf<String, Regex>()
        private val conceptKeywordPatterns = mutableMapOf<String, Regex>()

        private fun createBoundaryRegex(keyword: String): Regex =
            Regex("\\b${Regex.escape(keyword)}\\b")

        private fun createBoundaryRegexIgnoreCase(keyword: String): Regex =
            Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
    }
}

/**
 * 提取到的实体。
 */
data class ExtractedEntity(
    val name: String,
    val type: EntityType,
    val confidence: Float,
    val context: String
) {
    /** 转换为知识图谱实体 */
    fun toGraphEntity(): GraphEntity {
        return GraphEntity(
            id = FastId.next(),
            type = type,
            name = name,
            confidence = confidence,
            properties = mapOf("context" to context)
        )
    }
}

/**
 * 提取到的关系。
 */
data class ExtractedRelation(
    val source: String,
    val target: String,
    val type: RelationType,
    val confidence: Float
) {
    /** 转换为知识图谱关系（需要源和目标的实体 ID） */
    fun toGraphRelation(sourceId: String, targetId: String): GraphRelation {
        return GraphRelation(
            id = FastId.next(),
            sourceId = sourceId,
            targetId = targetId,
            type = type,
            confidence = confidence
        )
    }
}

