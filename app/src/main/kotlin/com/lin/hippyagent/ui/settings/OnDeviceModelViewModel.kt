package com.lin.hippyagent.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.R
import com.lin.hippyagent.core.ondevice.BackendPreference
import com.lin.hippyagent.core.ondevice.DownloadProgress
import com.lin.hippyagent.core.ondevice.DownloadState
import com.lin.hippyagent.core.ondevice.EngineState
import com.lin.hippyagent.core.ondevice.HuggingFaceMirror
import com.lin.hippyagent.core.ondevice.HuggingFaceModel
import com.lin.hippyagent.core.ondevice.HuggingFaceSearchApi
import com.lin.hippyagent.core.ondevice.OnDeviceModelConfig
import com.lin.hippyagent.core.ondevice.OnDeviceCapability
import com.lin.hippyagent.core.ondevice.OnDeviceModelManager
import com.lin.hippyagent.core.ondevice.OnDeviceModelState
import com.lin.hippyagent.core.ondevice.OnDeviceModelStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class OnDeviceModelUiState(
    val availableModels: List<OnDeviceModelConfig> = emptyList(),
    val modelStates: Map<String, OnDeviceModelState> = emptyMap(),
    val currentEngineModelId: String? = null,
    val downloadProgress: Map<String, DownloadProgress> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val autoUnload: Boolean = true,
    val hfModels: List<HuggingFaceModel> = emptyList(),
    val hfSearchQuery: String = "",
    val hfLoading: Boolean = false,
)

class OnDeviceModelViewModel(
    private val manager: OnDeviceModelManager,
    private val store: OnDeviceModelStore,
    private val context: android.app.Application,
) : ViewModel() {

    private val _isLoading = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _autoUnload = MutableStateFlow(true)
    private val _hfModels = MutableStateFlow<List<HuggingFaceModel>>(emptyList())
    private val _hfSearchQuery = MutableStateFlow("")
    private val _hfLoading = MutableStateFlow(false)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<OnDeviceModelUiState> = combine(
        store.models,
        manager.currentEngineModelId,
        manager.downloadProgress,
        _isLoading,
        _errorMessage,
        _autoUnload,
        _hfModels,
        _hfSearchQuery,
        _hfLoading,
    ) { args ->
        val models = args[0] as List<OnDeviceModelState>
        val engineId = args[1] as String?
        val progress = args[2] as Map<String, DownloadProgress>
        val loading = args[3] as Boolean
        val error = args[4] as String?
        val autoUnload = args[5] as Boolean
        val hfModels = args[6] as List<HuggingFaceModel>
        val hfQuery = args[7] as String
        val hfLoading = args[8] as Boolean
        OnDeviceModelUiState(
            availableModels = manager.availableModels,
            modelStates = models.associateBy { it.modelId },
            currentEngineModelId = engineId,
            downloadProgress = progress,
            isLoading = loading,
            errorMessage = error,
            autoUnload = autoUnload,
            hfModels = hfModels,
            hfSearchQuery = hfQuery,
            hfLoading = hfLoading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OnDeviceModelUiState())

    init {
        viewModelScope.launch {
            runCatching { manager.recoverStaleDownloads() }
                .onFailure { Timber.w(it, "Recover stale downloads failed") }
            runCatching { HuggingFaceMirror.restoreCache(store) }
                .onFailure { Timber.w(it, "Restore mirror cache failed") }
            runCatching { HuggingFaceMirror.probeAndCache(store) }
                .onFailure { Timber.w(it, "Probe mirror failed") }
            runCatching { manager.syncVirtualProviders() }
                .onFailure { Timber.w(it, "Sync virtual providers failed") }
        }
    }

    fun downloadModel(modelId: String) {
        manager.startDownload(modelId)
    }

    fun downloadHuggingFaceModel(model: HuggingFaceModel) {
        val ggufFile = model.siblings.firstOrNull { it.rfilename.endsWith(".gguf") }?.rfilename
        if (ggufFile == null) {
            _errorMessage.value = context.getString(R.string.ondevice_no_gguf)
            return
        }
        val modelId = "hf_${model.displayName.replace("/", "_").replace(" ", "_")}"
        val config = OnDeviceModelConfig(
            id = modelId,
            name = model.displayName,
            description = "${model.pipeline_tag ?: ""} model from HuggingFace",
            downloadUrl = HuggingFaceMirror.resolveUrl("https://huggingface.co/${model.displayName}/resolve/main/$ggufFile"),
            originalUrl = "https://huggingface.co/${model.displayName}",
            fileSize = 0L,
            capabilities = setOf(OnDeviceCapability.TEXT),
        )
        viewModelScope.launch {
            runCatching {
                store.addModel(OnDeviceModelState(modelId = modelId))
                manager.startDownloadWithConfig(config)
            }.onFailure {
                Timber.e(it, "HF model download failed")
                _errorMessage.value = context.getString(R.string.ondevice_download_failed_msg, it.message ?: "")
            }
        }
    }

    fun pauseDownload(modelId: String) {
        manager.pauseDownload(modelId)
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            runCatching { manager.deleteModel(modelId) }
                .onFailure { Timber.e(it, "Delete model failed"); _errorMessage.value = it.message }
        }
    }

    fun loadEngine(modelId: String, backend: BackendPreference = BackendPreference.AUTO) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                runCatching { manager.loadEngine(modelId, backend) }
                    .onFailure { Timber.e(it, "Load engine failed"); _errorMessage.value = it.message }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun unloadEngine() {
        viewModelScope.launch {
            runCatching { manager.unloadEngine() }
                .onFailure { Timber.e(it, "Unload engine failed"); _errorMessage.value = it.message }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setAutoUnload(enabled: Boolean) {
        _autoUnload.value = enabled
    }

    private var searchJob: Job? = null

    fun searchHuggingFace(query: String) {
        _hfSearchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            _hfLoading.value = true
            HuggingFaceSearchApi.search(query = query).fold(
                onSuccess = { _hfModels.value = it.filter { model -> model.siblings.any { f -> f.rfilename.endsWith(".gguf") } } },
                onFailure = { Timber.w(it, "HuggingFace search failed"); _errorMessage.value = "搜索失败: ${it.message}" }
            )
            _hfLoading.value = false
        }
    }

    fun loadPopularHuggingFaceModels() {
        if (_hfModels.value.isNotEmpty()) return
        searchHuggingFace("")
    }
}
