package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.skill.store.StoreSkillItem

data class MarketProviderInfo(
    val key: String,
    val label: String,
    val available: Boolean,
    val reason: String? = null
)

data class MarketSearchResponse(
    val results: List<StoreSkillItem>,
    val errors: List<MarketSearchError>,
    val byProvider: Map<String, PageInfo>
)

data class MarketSearchError(
    val provider: String,
    val message: String
)

data class PageInfo(
    val hasMore: Boolean,
    val total: Int? = null
)
