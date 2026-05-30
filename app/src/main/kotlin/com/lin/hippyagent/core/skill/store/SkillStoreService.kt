package com.lin.hippyagent.core.skill.store

import com.lin.hippyagent.core.skill.store.provider.MarketProvider
import com.lin.hippyagent.core.skill.store.provider.MarketProviderInfo
import com.lin.hippyagent.core.skill.store.provider.MarketSearchError
import com.lin.hippyagent.core.skill.store.provider.MarketSearchResponse
import com.lin.hippyagent.core.skill.store.provider.PageInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber

class SkillStoreService(
    private val linuxManager: com.lin.hippyagent.core.linux.LinuxManager,
    private val providers: Map<String, MarketProvider> = emptyMap()
) {
    internal fun getLinuxManager(): com.lin.hippyagent.core.linux.LinuxManager = linuxManager

    suspend fun listProviders(): List<MarketProviderInfo> =
        providers.values.map { p ->
            val result = p.available()
            MarketProviderInfo(p.key, p.label, result.available, result.reason)
        }

    suspend fun searchAll(
        query: String,
        selectedKeys: Set<String>,
        pages: Map<String, Int> = emptyMap(),
        pageSize: Int = 10
    ): MarketSearchResponse = coroutineScope {
        val deferreds = selectedKeys.mapNotNull { key ->
            val provider = providers[key] ?: return@mapNotNull null
            val page = pages[key] ?: 1
            async { provider to provider.search(query, page, pageSize) }
        }

        val results = mutableListOf<StoreSkillItem>()
        val errors = mutableListOf<MarketSearchError>()
        val byProvider = mutableMapOf<String, PageInfo>()

        deferreds.awaitAll().forEach { (provider, result) ->
            result.fold(
                onSuccess = { searchResult ->
                    results.addAll(searchResult.items)
                    byProvider[provider.key] = PageInfo(searchResult.hasMore, searchResult.total)
                },
                onFailure = { e ->
                    val msg = e.message ?: "unknown error"
                    Timber.w("Provider ${provider.key} search failed: $msg")
                    errors.add(MarketSearchError(provider.key, msg))
                }
            )
        }

        MarketSearchResponse(results, errors, byProvider)
    }

    suspend fun install(providerKey: String, identifier: String): Result<String> {
        val provider = providers[providerKey]
            ?: return Result.failure(IllegalArgumentException("Unknown provider: $providerKey"))
        return provider.install(identifier)
    }
}
