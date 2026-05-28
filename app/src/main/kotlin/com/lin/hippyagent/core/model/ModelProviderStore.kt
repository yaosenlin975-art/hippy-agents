package com.lin.hippyagent.core.model

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.modelProviderDataStore by preferencesDataStore(name = "model_providers")

class ModelProviderStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("providers")

    val providers: Flow<List<ModelProvider>> = context.modelProviderDataStore.data.map { prefs ->
        prefs[key]?.let { json.decodeFromString<List<ModelProvider>>(it) }?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_MODEL_PROVIDERS
    }

    /**
     * 确保默认提供商存在 — 首次启动或数据丢失后调用
     * 不会覆盖已有提供商，只补充缺失的
     */
    suspend fun ensureDefaults() {
        context.modelProviderDataStore.edit { prefs ->
            val current = prefs[key]?.let { json.decodeFromString<List<ModelProvider>>(it) } ?: emptyList()
            if (current.isEmpty()) {
                prefs[key] = json.encodeToString(DEFAULT_MODEL_PROVIDERS)
            } else {
                val existingIds = current.filter { !it.isVirtual }.map { it.id }.toSet()
                val missing = DEFAULT_MODEL_PROVIDERS.filter { it.id !in existingIds }
                if (missing.isNotEmpty()) {
                    prefs[key] = json.encodeToString(current + missing)
                }
            }
        }
    }

    suspend fun addProvider(provider: ModelProvider) {
        context.modelProviderDataStore.edit { prefs ->
            val current = prefs[key]?.let { json.decodeFromString<List<ModelProvider>>(it) } ?: emptyList()
            prefs[key] = json.encodeToString(current + provider)
        }
    }

    suspend fun updateProvider(provider: ModelProvider) {
        context.modelProviderDataStore.edit { prefs ->
            val current = prefs[key]?.let { json.decodeFromString<List<ModelProvider>>(it) } ?: emptyList()
            prefs[key] = json.encodeToString(current.map { if (it.id == provider.id) provider else it })
        }
    }

    suspend fun deleteProvider(id: String) {
        context.modelProviderDataStore.edit { prefs ->
            val current = prefs[key]?.let { json.decodeFromString<List<ModelProvider>>(it) } ?: emptyList()
            prefs[key] = json.encodeToString(current.filter { it.id != id })
        }
    }

    suspend fun setDefault(id: String) {
        context.modelProviderDataStore.edit { prefs ->
            val current = prefs[key]?.let { json.decodeFromString<List<ModelProvider>>(it) } ?: emptyList()
            prefs[key] = json.encodeToString(current.map { it.copy(isDefault = it.id == id) })
        }
    }
}

