package com.lin.hippyagent.core.ondevice

import android.content.Context
import kotlinx.serialization.json.Json

interface OnDeviceModelCatalog {
    fun getAvailableModels(): List<OnDeviceModelConfig>
    fun getModel(modelId: String): OnDeviceModelConfig?
}

class BuiltinOnDeviceModelCatalog(context: Context) : OnDeviceModelCatalog {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val models: List<OnDeviceModelConfig> by lazy {
        runCatching {
            val data = context.assets.open("ondevice_models.json").bufferedReader().use { it.readText() }
            json.decodeFromString<List<OnDeviceModelConfig>>(data)
        }.getOrElse { emptyList() }
    }

    override fun getAvailableModels(): List<OnDeviceModelConfig> = models

    override fun getModel(modelId: String): OnDeviceModelConfig? = models.firstOrNull { it.id == modelId }
}
