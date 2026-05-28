package com.lin.hippyagent.core.model

import kotlinx.serialization.Serializable

interface ModelProviderPlugin {
    val id: String
    val name: String
    val protocol: String
    fun createClient(provider: ModelProvider): ModelClient
    fun validateConfig(provider: ModelProvider): Result<Unit>
}

@Serializable
data class ProviderPluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val protocol: String,
    val entryClass: String
)
