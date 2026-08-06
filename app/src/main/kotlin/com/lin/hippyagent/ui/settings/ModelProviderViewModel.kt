package com.lin.hippyagent.ui.settings

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.R
import com.lin.hippyagent.core.model.AnthropicModelClient
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelConfig
import com.lin.hippyagent.core.model.ModelProvider
import com.lin.hippyagent.core.model.ModelProviderStore
import com.lin.hippyagent.core.model.OnDeviceRoutingConfig
import com.lin.hippyagent.core.model.OllamaModelClient
import com.lin.hippyagent.core.model.OpenAIModelClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ModelProviderUiState(
    val providers: List<ModelProvider> = emptyList(),
    val isLoading: Boolean = false,
    val isTestingConnection: Boolean = false,
    val isFetchingModels: Boolean = false,
    val testResult: String? = null,
    val errorMessage: String? = null,
    val onDeviceRouting: OnDeviceRoutingConfig = OnDeviceRoutingConfig()
)

class ModelProviderViewModel(
    private val store: ModelProviderStore,
    private val application: Application
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelProviderUiState())
    val uiState: StateFlow<ModelProviderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            store.providers.collect { list ->
                _uiState.update { it.copy(providers = list, isLoading = false) }
            }
        }
        viewModelScope.launch {
            store.onDeviceRoutingConfig.collect { config ->
                _uiState.update { it.copy(onDeviceRouting = config) }
            }
        }
    }

    fun addProvider(provider: ModelProvider) {
        viewModelScope.launch {
            try {
                store.addProvider(provider)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun updateProvider(provider: ModelProvider) {
        viewModelScope.launch {
            try {
                store.updateProvider(provider)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            try {
                store.deleteProvider(id)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun setDefault(id: String) {
        viewModelScope.launch {
            try {
                store.setDefault(id)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun addModel(providerId: String, model: ModelConfig) {
        viewModelScope.launch {
            try {
                val providers = _uiState.value.providers
                val provider = providers.find { it.id == providerId } ?: return@launch
                val updatedModels = provider.models + model
                val updatedProvider = provider.copy(models = updatedModels)
                store.updateProvider(updatedProvider)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun updateModel(providerId: String, model: ModelConfig) {
        viewModelScope.launch {
            try {
                val providers = _uiState.value.providers
                val provider = providers.find { it.id == providerId } ?: return@launch
                val updatedModels = provider.models.map { if (it.id == model.id) model else it }
                val updatedProvider = provider.copy(models = updatedModels)
                store.updateProvider(updatedProvider)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun deleteModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            try {
                val providers = _uiState.value.providers
                val provider = providers.find { it.id == providerId } ?: return@launch
                val updatedModels = provider.models.filter { it.id != modelId }
                val updatedProvider = provider.copy(models = updatedModels)
                store.updateProvider(updatedProvider)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun setDefaultModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            try {
                val providers = _uiState.value.providers
                val provider = providers.find { it.id == providerId } ?: return@launch
                val updatedModels = provider.models.map {
                    it.copy(isDefault = it.id == modelId)
                }
                val updatedProvider = provider.copy(models = updatedModels)
                store.updateProvider(updatedProvider)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun testConnection(provider: ModelProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, testResult = null) }
            try {
                val client = createClient(provider)
                val result = client.testConnection()
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        testResult = if (result.isSuccess) {
                            application.getString(R.string.model_connect_success)
                        } else {
                            application.getString(R.string.model_connect_failed, result.exceptionOrNull()?.message)
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTestingConnection = false, testResult = application.getString(R.string.model_connect_failed, e.message))
                }
            }
        }
    }

    fun fetchModels(provider: ModelProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingModels = true, errorMessage = null) }
            try {
                val client = createClient(provider)
                val modelNames = client.listModels()
                if (modelNames.isEmpty()) {
                    _uiState.update { it.copy(isFetchingModels = false, errorMessage = application.getString(R.string.model_fetch_empty)) }
                    return@launch
                }
                val existingIds = provider.models.map { it.id }.toSet()
                val newModels = modelNames.filter { it !in existingIds }.map { name ->
                    ModelConfig(
                        id = name,
                        providerId = provider.id,
                        name = name,
                        displayName = name
                    )
                }
                if (newModels.isNotEmpty()) {
                    val updatedProvider = provider.copy(models = provider.models + newModels)
                    store.updateProvider(updatedProvider)
                    _uiState.update { it.copy(isFetchingModels = false, errorMessage = null) }
                } else {
                    _uiState.update { it.copy(isFetchingModels = false, errorMessage = application.getString(R.string.model_all_exist)) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isFetchingModels = false, errorMessage = application.getString(R.string.model_fetch_failed, e.message))
                }
            }
        }
    }

    fun clearTestResult() {
        _uiState.update { it.copy(testResult = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun updateOnDeviceRouting(config: OnDeviceRoutingConfig) {
        viewModelScope.launch {
            try {
                store.updateOnDeviceRoutingConfig(config)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    private fun createClient(provider: ModelProvider): ModelClient {
        return when (provider.protocol) {
            "anthropic" -> AnthropicModelClient(baseUrl = provider.baseUrl, apiKey = provider.apiKey)
            "ollama" -> OllamaModelClient(baseUrl = provider.baseUrl)
            else -> OpenAIModelClient(baseUrl = provider.baseUrl, apiKey = provider.apiKey)
        }
    }
}

