package com.lin.hippyagent.core.model.builtin

import com.lin.hippyagent.core.model.*

class OpenAIProviderPlugin : ModelProviderPlugin {
    override val id = "openai"
    override val name = "OpenAI Compatible"
    override val protocol = "openai"
    override fun createClient(provider: ModelProvider): ModelClient =
        OpenAIModelClient(baseUrl = provider.baseUrl, apiKey = provider.apiKey)
    override fun validateConfig(provider: ModelProvider): Result<Unit> = runCatching {
        require(provider.baseUrl.isNotBlank()) { "Base URL is required" }
    }
}

class AnthropicProviderPlugin : ModelProviderPlugin {
    override val id = "anthropic"
    override val name = "Anthropic"
    override val protocol = "anthropic"
    override fun createClient(provider: ModelProvider): ModelClient =
        AnthropicModelClient(baseUrl = provider.baseUrl, apiKey = provider.apiKey)
    override fun validateConfig(provider: ModelProvider): Result<Unit> = runCatching {
        require(provider.apiKey.isNotBlank()) { "API Key is required for Anthropic" }
    }
}

class OllamaProviderPlugin : ModelProviderPlugin {
    override val id = "ollama"
    override val name = "Ollama"
    override val protocol = "ollama"
    override fun createClient(provider: ModelProvider): ModelClient =
        OllamaModelClient(baseUrl = provider.baseUrl)
    override fun validateConfig(provider: ModelProvider): Result<Unit> = runCatching {
        require(provider.baseUrl.isNotBlank()) { "Base URL is required for Ollama" }
    }
}
