package com.lin.hippyagent.core.knowledge

/**
 * 零 LLM 链接抽取四层正则阶梯。
 *
 * 层级（gbrain 原版优先级，从高到低）：
 * 1. QUALIFIED_WIKILINK_RE  ── [[slug|alias]] 带别名的 wiki 链接
 * 2. WIKILINK_RE             ── [[slug]] 简单 wiki 链接
 * 3. FRONTMATTER_LINKS_RE    ── YAML frontmatter 中的 links 字段（多行 + 行内两式）
 * 4. ENTITY_REF_EN_RE        ── 裸实体引用（英文大写词）；中文已知实体走 EntityPatterns 词典
 *
 * 解析顺序：先高层后低层，高层匹配后低层跳过同一文本段（consumed 区间集合）。
 */
object LinkExtractionPatterns {

    // 1. [[slug|alias]] 带别名的 wiki 链接
    val QUALIFIED_WIKILINK_RE: Regex = Regex("""\[\[([^\]|]+)\|([^\]]+)\]\]""")

    // 2. [[slug]] 简单 wiki 链接
    val WIKILINK_RE: Regex = Regex("""\[\[([^\]|]+)\]\]""")

    // 3. YAML frontmatter links 字段（多行式：links:\n  - a\n  - b）
    val FRONTMATTER_LINKS_RE: Regex = Regex(
        """^links:\s*\n((?:\s+-\s+.+\n?)+)""", RegexOption.MULTILINE
    )

    // 3b. YAML frontmatter links 字段（行内式：links: [a, b, c]）
    val FRONTMATTER_LINKS_INLINE_RE: Regex = Regex(
        """^links:\s*\[([^\]]*)\]""", RegexOption.MULTILINE
    )

    // 4. 裸实体引用（英文：2+ 个大写开头单词或单个大写缩写 ≥2 字符）
    val ENTITY_REF_EN_RE: Regex = Regex(
        """\b([A-Z][a-z]+(?:\s[A-Z][a-z]+)+)\b|\b([A-Z]{2,})\b"""
    )

    /** 从文本中提取所有层级的链接，按优先级去重（consumed 区间跳过） */
    fun extractAll(text: String): List<ExtractedLink> {
        val links = mutableListOf<ExtractedLink>()
        val consumed = mutableListOf<IntRange>()

        fun isConsumed(range: IntRange): Boolean = consumed.any { it.contains(range.first) }

        // 1. QUALIFIED_WIKILINK
        for (match in QUALIFIED_WIKILINK_RE.findAll(text)) {
            links.add(ExtractedLink(
                slug = match.groupValues[1].trim(),
                alias = match.groupValues[2].trim(),
                type = LinkType.QUALIFIED_WIKILINK,
                range = match.range
            ))
            consumed.add(match.range)
        }

        // 2. WIKILINK（跳过已被 QUALIFIED 消费的段）
        for (match in WIKILINK_RE.findAll(text)) {
            if (isConsumed(match.range)) continue
            links.add(ExtractedLink(
                slug = match.groupValues[1].trim(),
                alias = null,
                type = LinkType.WIKILINK,
                range = match.range
            ))
            consumed.add(match.range)
        }

        // 3. FRONTMATTER links（多行式）
        for (match in FRONTMATTER_LINKS_RE.findAll(text)) {
            val slugs = match.groupValues[1].split("\n").mapNotNull { line ->
                line.trim().removePrefix("-").trim().takeIf { it.isNotEmpty() }
            }
            for (slug in slugs) {
                links.add(ExtractedLink(slug, null, LinkType.FRONTMATTER, match.range))
            }
        }
        // 3b. FRONTMATTER links（行内式）
        for (match in FRONTMATTER_LINKS_INLINE_RE.findAll(text)) {
            val slugs = match.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            for (slug in slugs) {
                links.add(ExtractedLink(slug, null, LinkType.FRONTMATTER, match.range))
            }
        }

        // 4. ENTITY_REF（英文大写词）
        for (match in ENTITY_REF_EN_RE.findAll(text)) {
            if (isConsumed(match.range)) continue
            val name = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
            if (name.isNotEmpty()) {
                links.add(ExtractedLink(name, null, LinkType.ENTITY_REF, match.range))
            }
        }

        // 4b. 中文已知实体：匹配 EntityPatterns 词典（城市/国家/技术/概念）
        for (keyword in EntityPatterns.TECHNOLOGY_KEYWORDS + EntityPatterns.CITIES + EntityPatterns.COUNTRIES) {
            var idx = text.indexOf(keyword, ignoreCase = true)
            while (idx >= 0) {
                val range = idx until idx + keyword.length
                if (!isConsumed(range)) {
                    links.add(ExtractedLink(keyword, null, LinkType.ENTITY_REF, range))
                }
                idx = text.indexOf(keyword, idx + keyword.length, ignoreCase = true)
            }
        }

        return links.distinctBy { it.slug.lowercase() }
    }
}

data class ExtractedLink(
    val slug: String,
    val alias: String?,
    val type: LinkType,
    val range: IntRange
)

enum class LinkType {
    QUALIFIED_WIKILINK,  // [[slug|alias]]
    WIKILINK,            // [[slug]]
    FRONTMATTER,         // YAML links
    ENTITY_REF           // 裸实体引用
}
