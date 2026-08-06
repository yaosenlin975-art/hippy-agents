package com.lin.hippyagent.core.memory.search

import com.lin.hippyagent.core.memory.commonmemory.BrainMemoryType
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.memory.commonmemory.MemoryStats
import com.lin.hippyagent.core.memory.commonmemory.PruneResult
import com.lin.hippyagent.core.memory.commonmemory.SearchIntent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridSearchEngineTest {

    private val now = System.currentTimeMillis()

    private fun entry(id: String, summary: String = "note $id") = CommonMemoryEntry(
        id = id,
        type = BrainMemoryType.EPISODE,
        summary = summary,
        updatedAt = now
    )

    private class FakeMemoryRepository(
        val entries: Map<String, CommonMemoryEntry>
    ) : MemoryRepository {
        override suspend fun searchFts(query: String, limit: Int) =
            entries.values.filter { it.summary.contains(query) }.take(limit)

        override suspend fun searchFtsByAgentId(query: String, agentId: String, limit: Int) =
            entries.values.filter { it.agentId == agentId && it.summary.contains(query) }.take(limit)

        override suspend fun findById(id: String): CommonMemoryEntry? = entries[id]

        override suspend fun insert(entry: CommonMemoryEntry) {}
        override suspend fun update(entry: CommonMemoryEntry) {}
        override suspend fun searchHybrid(query: String, limit: Int) = emptyList<Pair<CommonMemoryEntry, Float>>()
        override suspend fun searchHybridByAgentId(query: String, agentId: String, limit: Int) = emptyList<Pair<CommonMemoryEntry, Float>>()
        override suspend fun search(query: String, agentId: String, intent: SearchIntent, limit: Int) = emptyList<Pair<CommonMemoryEntry, Float>>()
        override suspend fun findByType(type: BrainMemoryType, limit: Int) = emptyList<CommonMemoryEntry>()
        override suspend fun findByTypeAndAgentId(type: BrainMemoryType, agentId: String, limit: Int) = emptyList<CommonMemoryEntry>()
        override suspend fun findActive(limit: Int) = emptyList<CommonMemoryEntry>()
        override suspend fun findAll(limit: Int) = emptyList<CommonMemoryEntry>()
        override suspend fun findActiveByAgentId(agentId: String, limit: Int) = emptyList<CommonMemoryEntry>()
        override suspend fun findMergeCandidate(type: BrainMemoryType, normalizedTerms: List<String>) = null
        override suspend fun findConflictCandidate(type: BrainMemoryType, summaryTerms: List<String>) = null
        override suspend fun softDelete(id: String) {}
        override suspend fun findExpiredUploadFacts(nowMs: Long) = emptyList<CommonMemoryEntry>()
        override suspend fun searchBySummary(query: String, limit: Int) = emptyList<CommonMemoryEntry>()
        override suspend fun hardDelete(id: String) {}
        override suspend fun pruneStale() = PruneResult(0, 0)
        override suspend fun promoteToDurable() = 0
        override suspend fun getStats() = MemoryStats(0, emptyMap(), null, null)
        override fun scoreMemories(candidates: List<CommonMemoryEntry>, query: String) = emptyList<Pair<CommonMemoryEntry, Float>>()
        override fun close() {}
    }

    // ═══════════ legacy path (no graph / post-fusion) ═══════════

    @Test
    fun search_blankQuery_returnsEmpty() = runTest {
        val engine = HybridSearchEngine(FakeMemoryRepository(emptyMap()))
        assertTrue(engine.search("  ").isEmpty())
    }

    @Test
    fun legacySearch_noMatches_returnsEmpty() = runTest {
        val engine = HybridSearchEngine(FakeMemoryRepository(mapOf("1" to entry("1", "apple"))))
        assertTrue(engine.search("banana").isEmpty())
    }

    @Test
    fun legacySearch_matches_returnsRankedResults() = runTest {
        val repo = FakeMemoryRepository(
            mapOf(
                "1" to entry("1", "android backup"),
                "2" to entry("2", "android sync"),
                "3" to entry("3", "unrelated")
            )
        )
        val engine = HybridSearchEngine(repo)
        val results = engine.search("android")
        assertEquals(2, results.size)
        assertTrue(results.all { it.first.id != "3" })
    }

    @Test
    fun legacySearch_agentId_routesToAgentScopedQuery() = runTest {
        val a1 = entry("1", "android").copy(agentId = "agent-a")
        val a2 = entry("2", "android").copy(agentId = "agent-b")
        val repo = FakeMemoryRepository(mapOf("1" to a1, "2" to a2))
        val engine = HybridSearchEngine(repo)
        val results = engine.search("android", "agent-a")
        assertEquals(1, results.size)
        assertEquals("1", results[0].first.id)
    }

    @Test
    fun legacySearch_topK_limitsResults() = runTest {
        val entries = (1..10).associate { it.toString() to entry(it.toString(), "android note") }
        val engine = HybridSearchEngine(FakeMemoryRepository(entries), reranker = mockkLight())
        val results = engine.search("android", options = SearchOptions(finalTopK = 3))
        assertTrue(results.size <= 3)
    }

    private fun mockkLight(): LightweightReranker {
        val reranker = mockk<LightweightReranker>()
        every { reranker.rerank(any(), any(), any(), any()) } answers {
            val entries = arg<List<CommonMemoryEntry>>(2)
            val topK = arg<Int>(3)
            entries.map { it to 1f }.take(topK)
        }
        return reranker
    }

    // ═══════════ upgraded path (3-way RRF + post-fusion + floor gate) ═══════════

    @Test
    fun upgradedSearch_fusesFtsAndGraph_applyFloorGateAndTopK() = runTest {
        val repo = FakeMemoryRepository(
            mapOf(
                "1" to entry("1", "android"),
                "2" to entry("2", "android"),
                "3" to entry("3", "android")
            )
        )
        val graph = mockk<GraphRetriever>()
        coEvery { graph.retrieve(any(), any()) } returns listOf(ScoredItem("2", 1f), ScoredItem("3", 1f))

        val postFusion = mockk<PostFusionReranker>()
        coEvery { postFusion.rerank(any(), any(), any(), any()) } answers {
            val items = arg<List<ScoredItem>>(1)
            items.map { item ->
                // 给 "1" 一个极高 bonus，保证它保留；"2"/"3" 低分会被 floor gate 裁掉
                if (item.id == "1") item.copy(score = item.score + 10f) else item.copy(score = 0.0001f)
            }
        }

        val engine = HybridSearchEngine(
            memoryRepository = repo,
            graphRetriever = graph,
            postFusionReranker = postFusion,
            floorRatio = 0.15f
        )
        val results = engine.search("android", options = SearchOptions(finalTopK = 8))
        assertEquals(1, results.size)
        assertEquals("1", results[0].first.id)

        coVerify { graph.retrieve("android", 8) }
        coVerify { postFusion.rerank(any(), any(), any(), 16) }
    }

    @Test
    fun upgradedSearch_graphResultUnknownId_skipped() = runTest {
        val repo = FakeMemoryRepository(mapOf("1" to entry("1", "android")))
        val graph = mockk<GraphRetriever>()
        coEvery { graph.retrieve(any(), any()) } returns listOf(ScoredItem("ghost", 1f))
        val postFusion = mockk<PostFusionReranker>()
        coEvery { postFusion.rerank(any(), any(), any(), any()) } answers {
            val items = arg<List<ScoredItem>>(1)
            items.map { it.copy(score = it.score + 1f) }
        }
        val engine = HybridSearchEngine(
            memoryRepository = repo,
            graphRetriever = graph,
            postFusionReranker = postFusion
        )
        val results = engine.search("android")
        // ghost id 查不到 entry → 跳过
        assertTrue(results.none { it.first.id == "ghost" })
    }

    @Test
    fun upgradedSearch_blankQuery_shortCircuits() = runTest {
        val graph = mockk<GraphRetriever>()
        val postFusion = mockk<PostFusionReranker>()
        val engine = HybridSearchEngine(
            memoryRepository = FakeMemoryRepository(emptyMap()),
            graphRetriever = graph,
            postFusionReranker = postFusion
        )
        assertTrue(engine.search("").isEmpty())
    }
}
