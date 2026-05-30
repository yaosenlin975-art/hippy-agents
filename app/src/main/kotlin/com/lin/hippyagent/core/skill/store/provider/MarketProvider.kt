package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.skill.store.SkillSource
import com.lin.hippyagent.core.skill.store.StoreSkillItem

data class AvailabilityResult(val available: Boolean, val reason: String?)

interface MarketProvider {
    val key: String
    val label: String
    val source: SkillSource

    fun available(): AvailabilityResult

    suspend fun search(query: String, page: Int, pageSize: Int): Result<SearchResult>

    suspend fun install(identifier: String): Result<String>
}

data class SearchResult(
    val items: List<StoreSkillItem>,
    val hasMore: Boolean,
    val total: Int? = null
)
