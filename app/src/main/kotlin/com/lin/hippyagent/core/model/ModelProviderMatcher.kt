package com.lin.hippyagent.core.model

object ModelProviderMatcher {
    fun findMatchingProvider(providers: List<ModelProvider>, providerId: String): ModelProvider? {
        if (providerId.isBlank()) return null
        return providers.firstOrNull { it.id == providerId && it.enabled }
            ?: providers.firstOrNull { it.name == providerId && it.enabled }
            ?: providers.firstOrNull { it.isDefault && it.enabled }
            ?: providers.firstOrNull { it.enabled }
    }

    fun findProviderForModel(providers: List<ModelProvider>, modelName: String): ModelProvider? {
        return providers.firstOrNull { provider ->
            provider.enabled && provider.models.any { it.name == modelName && it.enabled }
        }
    }
}
