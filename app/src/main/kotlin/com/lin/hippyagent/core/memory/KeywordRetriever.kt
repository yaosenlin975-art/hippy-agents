package com.lin.hippyagent.core.memory

class KeywordRetriever(
    private val store: MemoryStore
) : Retriever {

    private val stopwords = setOf("的", "了", "是", "在", "和", "与", "或", "一个", "这", "那", "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "它们", "就", "都", "也", "还", "很", "太", "最", "更", "比较", "非常", "真的", "可以", "应该", "能够", "可能", "需要", "想", "会", "不会", "没有", "这个", "那个", "这些", "那些", "什么", "怎么", "为什么", "如何", "哪里", "何时", "谁")

    override suspend fun retrieve(query: MemoryQuery): Result<List<SearchResult>> = runCatching {
        val allEntries = store.getAll().getOrThrow()
        val queryTokens = tokenize(query.query)

        val results = allEntries
            .filter { it.type in query.memoryTypes }
            .filter { query.filters.all { (k, v) -> it.metadata[k] == v } }
            .mapNotNull { entry ->
                val entryTokens = tokenize(entry.content)
                val score = jaccardSimilarity(queryTokens, entryTokens)
                if (score >= query.minScore) SearchResult(entry, score) else null
            }
            .sortedByDescending { it.score }
            .take(query.maxResults)

        results
    }

    private val CJK_REGEX = Regex("[\\u4e00-\\u9fff]")

    private fun tokenize(text: String): Set<String> {
        val normalized = text.lowercase()
            .replace(Regex("[，。！？；：、,.!?;:]"), " ")

        val tokens = mutableSetOf<String>()
        normalized.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { segment ->
            if (segment.any { CJK_REGEX.matches(it.toString()) }) {
                segment.forEach { char ->
                    val c = char.toString()
                    if (CJK_REGEX.matches(c) && !stopwords.contains(c)) {
                        tokens.add(c)
                    }
                }
                val nonCjkParts = segment.split(Regex("[\\u4e00-\\u9fff]+")).filter { it.length > 1 }
                tokens.addAll(nonCjkParts)
            } else {
                if (segment.length > 1 && !stopwords.contains(segment)) {
                    tokens.add(segment)
                }
            }
        }
        return tokens
    }

    private fun jaccardSimilarity(set1: Set<String>, set2: Set<String>): Float {
        if (set1.isEmpty() || set2.isEmpty()) return 0f
        val intersection = set1.intersect(set2).size
        val union = set1.union(set2).size
        return if (union == 0) 0f else intersection.toFloat() / union
    }
}

