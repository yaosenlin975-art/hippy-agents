package com.lin.hippyagent.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber

class ModelManager(
    private val modelProviderStore: ModelProviderStore,
    private val secureStorage: com.lin.hippyagent.core.storage.SecureStorage
) {
    @Volatile
    private var _currentProvider = ModelProvider(
        id = "openai",
        name = "OpenAI",
        apiKey = "",
        baseUrl = "https://api.openai.com/v1"
    )

    suspend fun getProviders(): List<ModelProvider> = modelProviderStore.providers.first()

    suspend fun setProvider(provider: ModelProvider) {
        _currentProvider = provider
        Timber.i("Switched model provider to: ${provider.name}")
    }

    suspend fun setDefaultProvider() {
        val providers = modelProviderStore.providers.first()
        val default = providers.find { it.isDefault } ?: providers.firstOrNull()
        if (default != null) {
            _currentProvider = default
            Timber.i("Switched to default provider: ${default.name}")
        }
    }

    private fun createClient(provider: ModelProvider): ModelClient {
        return ModelClientFactory.create(provider, secureStorage)
    }

    suspend fun testConnection(provider: ModelProvider): Result<Boolean> {
        return try {
            val client = createClient(provider)
            val result = client.testConnection()
            result.map { true }
        } catch (e: Exception) {
            Timber.e(e, "Connection test failed for ${provider.name}")
            Result.failure(e)
        }
    }

    suspend fun getCurrentProvider(): ModelProvider {
        return _currentProvider
    }

    suspend fun listAvailableModels(): List<String> {
        return try {
            val client = createClient(_currentProvider)
            client.listModels()
        } catch (e: Exception) {
            Timber.e(e, "Failed to list models")
            emptyList()
        }
    }
}

