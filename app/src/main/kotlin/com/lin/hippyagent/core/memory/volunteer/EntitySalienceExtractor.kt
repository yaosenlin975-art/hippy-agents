package com.lin.hippyagent.core.memory.volunteer

import com.lin.hippyagent.core.knowledge.EntityPatterns
import com.lin.hippyagent.core.memory.ChineseTokenizer

/**
 * 零 LLM 实体显著性抽取器（中文适配版）。
 *
 * gbrain 原版用 CAP_RUN_RE（拉丁大写 run），中文无大写，改用三路：
 * 1. @handles 检测：@后跟非空白字符（中英文通用）
 * 2. 命名实体词典：复用 EntityPatterns 的 CITIES/COUNTRIES/TECHNOLOGY_KEYWORDS/CONCEPT_KEYWORDS
 * 3. 中文分词：ChineseTokenizer 分词后取长度≥2 的非停用词 token
 *
 * MAX_CANDIDATES=12，STOPWORDS/COMMON_WORDS 双层过滤。
 */
class EntitySalienceExtractor(
    private val chineseTokenizer: ChineseTokenizer
) {
    fun extract(text: String): List<EntityCandidate> {
        if (text.isBlank()) return emptyList()
        val candidates = mutableListOf<EntityCandidate>()

        // 1. @handles 检测（中英文通用）
        for (match in HANDLES_REGEX.findAll(text)) {
            candidates.add(EntityCandidate(match.groupValues[1], EntityCandidate.Source.HANDLE))
        }

        // 2. 命名实体词典（城市/国家）
        for (city in EntityPatterns.CITIES) {
            if (text.contains(city)) {
                candidates.add(EntityCandidate(city, EntityCandidate.Source.DICT_LOCATION))
            }
        }
        for (country in EntityPatterns.COUNTRIES) {
            if (text.contains(country)) {
                candidates.add(EntityCandidate(country, EntityCandidate.Source.DICT_LOCATION))
            }
        }

        // 3. 技术关键词 + 概念关键词
        for (keyword in EntityPatterns.TECHNOLOGY_KEYWORDS) {
            if (text.contains(keyword, ignoreCase = true)) {
                candidates.add(EntityCandidate(keyword, EntityCandidate.Source.DICT_TECH))
            }
        }
        for (keyword in EntityPatterns.CONCEPT_KEYWORDS) {
            if (text.contains(keyword, ignoreCase = true)) {
                candidates.add(EntityCandidate(keyword, EntityCandidate.Source.DICT_CONCEPT))
            }
        }

        // 4. 中文分词（取长度≥2 的非停用词 token）
        val segments = chineseTokenizer.segmentToString(text)
        for (seg in segments.split(" ")) {
            if (seg.length >= 2 && seg !in STOPWORDS && seg !in COMMON_WORDS) {
                candidates.add(EntityCandidate(seg, EntityCandidate.Source.SEGMENT))
            }
        }

        // 去重 + 取 top MAX_CANDIDATES
        return candidates
            .groupBy { it.name.lowercase() }
            .map { (_, group) -> group.first() }
            .take(MAX_CANDIDATES)
    }

    companion object {
        private val HANDLES_REGEX = Regex("@([\\w\\u4e00-\\u9fa5]+)")

        private val STOPWORDS = setOf(
            "的", "了", "是", "在", "和", "与", "或", "一个", "这", "那",
            "我", "你", "他", "她", "它", "我们", "你们", "他们",
            "就", "都", "也", "还", "很", "太", "最", "更", "可以", "应该"
        )

        private val COMMON_WORDS = setOf(
            "今天", "昨天", "明天", "现在", "之前", "之后", "里面", "外面",
            "什么", "怎么", "为什么", "如何", "哪里", "这个", "那个"
        )

        private const val MAX_CANDIDATES = 12
    }
}

data class EntityCandidate(
    val name: String,
    val source: Source
) {
    enum class Source { HANDLE, DICT_LOCATION, DICT_TECH, DICT_CONCEPT, SEGMENT }
}
