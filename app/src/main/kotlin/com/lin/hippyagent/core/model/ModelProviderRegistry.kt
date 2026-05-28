package com.lin.hippyagent.core.model

import timber.log.Timber

class ModelProviderRegistry {
    private val plugins = mutableMapOf<String, ModelProviderPlugin>()

    fun register(plugin: ModelProviderPlugin) {
        plugins[plugin.id] = plugin
        Timber.i("Registered provider plugin: ${plugin.id} (${plugin.name})")
    }

    fun unregister(pluginId: String) {
        plugins.remove(pluginId)
    }

    fun getPlugin(protocol: String): ModelProviderPlugin? {
        return plugins[protocol]
    }

    fun getAllPlugins(): List<ModelProviderPlugin> = plugins.values.toList()

    fun createClient(provider: ModelProvider): Result<ModelClient> = runCatching {
        val plugin = plugins[provider.protocol]
            ?: throw IllegalArgumentException("No plugin registered for protocol: ${provider.protocol}")
        plugin.validateConfig(provider).getOrThrow()
        plugin.createClient(provider)
    }
}
