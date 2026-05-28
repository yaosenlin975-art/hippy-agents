package com.lin.hippyagent.core.model

import com.lin.hippyagent.core.ondevice.LiteRTLMModelClient
import com.lin.hippyagent.core.ondevice.OnDeviceModelManager
import com.lin.hippyagent.core.storage.SecureStorage

object ModelClientFactory {
    fun create(
        provider: ModelProvider,
        secureStorage: SecureStorage? = null,
        onDeviceModelManager: OnDeviceModelManager? = null
    ): ModelClient {
        val apiKey = secureStorage?.getApiKey(provider.id) ?: provider.apiKey
        return when (provider.protocol) {
            "anthropic" -> AnthropicModelClient(
                baseUrl = provider.baseUrl,
                apiKey = apiKey
            )
            "ollama" -> OllamaModelClient(
                baseUrl = provider.baseUrl
            )
            "litertlm" -> {
                val manager = onDeviceModelManager
                    ?: throw IllegalStateException("OnDeviceModelManager not available for litertlm protocol")
                val modelId = provider.baseUrl.removePrefix("litertlm://")
                LiteRTLMModelClient(manager, modelId)
            }
            else -> OpenAIModelClient(
                baseUrl = provider.baseUrl,
                apiKey = apiKey
            )
        }
    }
}
