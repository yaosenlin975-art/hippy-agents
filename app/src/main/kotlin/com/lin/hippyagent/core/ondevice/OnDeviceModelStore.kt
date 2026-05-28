package com.lin.hippyagent.core.ondevice

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.onDeviceModelDataStore by preferencesDataStore(name = "ondevice_models")

class OnDeviceModelStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val modelsKey = stringPreferencesKey("models")
    private val mirrorDomainKey = stringPreferencesKey("mirror_domain")

    val models: Flow<List<OnDeviceModelState>> = context.onDeviceModelDataStore.data.map { prefs ->
        prefs[modelsKey]?.let { json.decodeFromString<List<OnDeviceModelState>>(it) } ?: emptyList()
    }

    suspend fun updateModel(modelId: String, transform: (OnDeviceModelState) -> OnDeviceModelState) {
        context.onDeviceModelDataStore.edit { prefs ->
            val current = prefs[modelsKey]?.let { json.decodeFromString<List<OnDeviceModelState>>(it) } ?: emptyList()
            val updated = current.map { if (it.modelId == modelId) transform(it) else it }
            prefs[modelsKey] = json.encodeToString(updated)
        }
    }

    suspend fun addModel(model: OnDeviceModelState) {
        context.onDeviceModelDataStore.edit { prefs ->
            val current = prefs[modelsKey]?.let { json.decodeFromString<List<OnDeviceModelState>>(it) } ?: emptyList()
            if (current.none { it.modelId == model.modelId }) {
                prefs[modelsKey] = json.encodeToString(current + model)
            }
        }
    }

    suspend fun removeModel(modelId: String) {
        context.onDeviceModelDataStore.edit { prefs ->
            val current = prefs[modelsKey]?.let { json.decodeFromString<List<OnDeviceModelState>>(it) } ?: emptyList()
            prefs[modelsKey] = json.encodeToString(current.filter { it.modelId != modelId })
        }
    }

    suspend fun saveMirrorDomain(domain: String) {
        context.onDeviceModelDataStore.edit { prefs ->
            prefs[mirrorDomainKey] = domain
        }
    }

    suspend fun loadMirrorDomain(): String? {
        return context.onDeviceModelDataStore.data.map { it[mirrorDomainKey] }.first()
    }
}
