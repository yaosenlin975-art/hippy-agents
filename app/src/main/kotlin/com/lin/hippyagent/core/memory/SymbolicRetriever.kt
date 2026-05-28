package com.lin.hippyagent.core.memory

import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import com.lin.hippyagent.core.memory.commonmemory.MemoryDao
import com.lin.hippyagent.core.memory.commonmemory.toCommonMemoryEntry

class SymbolicRetriever(
    private val memoryDao: MemoryDao
) {
    suspend fun retrieve(query: String, agentId: String, limit: Int = 10): List<Pair<CommonMemoryEntry, Float>> {
        val segmentedQuery = ChineseTokenizer.segmentToString(query)
        if (segmentedQuery.isBlank()) return emptyList()
        val exactMatches = memoryDao.searchByExactPhrase(segmentedQuery, agentId)
        val phraseMatches = memoryDao.searchByPhrasePrefix(segmentedQuery, agentId)
        return (exactMatches.map { it.toCommonMemoryEntry() to 1.0f } + phraseMatches.map { it.toCommonMemoryEntry() to 0.7f })
            .distinctBy { it.first.id }
            .take(limit)
    }
}
