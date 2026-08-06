package com.lin.hippyagent.core.memory.search

import com.lin.hippyagent.core.memory.commonmemory.BrainMemoryType
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightweightRerankerTest {

    private val reranker = LightweightReranker()

    private fun entry(
        id: String,
        summary: String,
        detail: String? = null,
        confidence: Float = 0.5f,
        evidenceCount: Int = 1,
        updatedAt: Long = System.currentTimeMillis(),
        importance: Float = 0.5f
    ) = CommonMemoryEntry(
        id = id,
        type = BrainMemoryType.EPISODE,
        summary = summary,
        detail = detail,
        confidence = confidence,
        importance = importance,
        evidenceCount = evidenceCount,
        updatedAt = updatedAt
    )

    private fun scored(id: String, score: Float = 1f) = ScoredItem(id, score)

    @Test
    fun rerank_emptyCandidates_returnsEmpty() {
        assertTrue(reranker.rerank("query", emptyList(), emptyList()).isEmpty())
        assertTrue(reranker.rerank("query", listOf(scored("x")), emptyList()).isEmpty())
    }

    @Test
    fun rerank_normalizesBaseScore() {
        val e1 = entry("a", "hello world note")
        val e2 = entry("b", "another note")
        val candidates = listOf(scored("a", 1f), scored("b", 0.5f))
        val result = reranker.rerank("hello", candidates, listOf(e1, e2), topK = 8)
        assertEquals(2, result.size)
        // 两者 boost 相同，score 应随归一化 base 单调
        val a = result.first { it.first.id == "a" }.second
        val b = result.first { it.first.id == "b" }.second
        assertTrue(a > b)
    }

    @Test
    fun rerank_keywordMatch_boostsAboveNonMatch() {
        val matched = entry("a", "android backup tips")
        val unmatched = entry("b", "unrelated content here")
        val candidates = listOf(scored("a"), scored("b"))
        val result = reranker.rerank("android", candidates, listOf(matched, unmatched), topK = 8)
        val a = result.first { it.first.id == "a" }.second
        val b = result.first { it.first.id == "b" }.second
        assertTrue(a > b)
    }

    @Test
    fun rerank_detailMatch_addsCoverageBoost() {
        val withDetail = entry("a", "short summary", detail = "mentions android here")
        val without = entry("b", "short summary")
        val candidates = listOf(scored("a"), scored("b"))
        val result = reranker.rerank("android", candidates, listOf(withDetail, without), topK = 8)
        val a = result.first { it.first.id == "a" }.second
        val b = result.first { it.first.id == "b" }.second
        assertTrue(a > b)
    }

    @Test
    fun rerank_markdownHeader_getsTitleBoost() {
        val titled = entry("a", "# 重要标题")
        val plain = entry("b", "普通内容")
        val candidates = listOf(scored("a"), scored("b"))
        val result = reranker.rerank("zq", candidates, listOf(titled, plain), topK = 8)
        assertTrue(result.first { it.first.id == "a" }.second > result.first { it.first.id == "b" }.second)
    }

    @Test
    fun rerank_freshEntry_getsRecencyBoost() {
        val fresh = entry("a", "today note", updatedAt = System.currentTimeMillis())
        val old = entry("b", "today note", updatedAt = System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        val candidates = listOf(scored("a"), scored("b"))
        val result = reranker.rerank("zz", candidates, listOf(fresh, old), topK = 8)
        assertTrue(result.first { it.first.id == "a" }.second > result.first { it.first.id == "b" }.second)
    }

    @Test
    fun rerank_highConfidence_getsConfidenceBoost() {
        val confident = entry("a", "note", confidence = 1f)
        val uncertain = entry("b", "note", confidence = 0f)
        val candidates = listOf(scored("a"), scored("b"))
        val result = reranker.rerank("zz", candidates, listOf(confident, uncertain), topK = 8)
        assertTrue(result.first { it.first.id == "a" }.second > result.first { it.first.id == "b" }.second)
    }

    @Test
    fun rerank_highEvidenceCount_getsEvidenceBoost() {
        val many = entry("a", "note", evidenceCount = 5)
        val few = entry("b", "note", evidenceCount = 1)
        val candidates = listOf(scored("a"), scored("b"))
        val result = reranker.rerank("zz", candidates, listOf(many, few), topK = 8)
        assertTrue(result.first { it.first.id == "a" }.second > result.first { it.first.id == "b" }.second)
    }

    @Test
    fun rerank_topK_limitsResultSize() {
        val entries = (1..10).map { entry("e$it", "note $it") }
        val candidates = entries.map { scored(it.id) }
        val result = reranker.rerank("note", candidates, entries, topK = 3)
        assertEquals(3, result.size)
    }

    @Test
    fun rerank_scoreInRange() {
        val entries = listOf(entry("a", "note"), entry("b", "note"))
        val candidates = listOf(scored("a"), scored("b"))
        val result = reranker.rerank("note", candidates, entries, topK = 8)
        result.forEach { (_, score) ->
            assertTrue("score $score out of range", score in 0f..1f)
        }
    }
}
