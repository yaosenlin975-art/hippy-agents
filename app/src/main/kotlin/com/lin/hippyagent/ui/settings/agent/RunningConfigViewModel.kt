package com.lin.hippyagent.ui.settings.agent

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.config.RunningConfig
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class RunningConfigUiState(
    val runningConfig: RunningConfig = RunningConfig(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saveSuccess: Boolean = false
)

class RunningConfigViewModel(
    private val repository: AgentRepository,
    private val agentId: String,
    private val agentFactory: com.lin.hippyagent.core.agent.AgentFactory
) : ViewModel() {

    private val _uiState = MutableStateFlow(RunningConfigUiState())
    val uiState: StateFlow<RunningConfigUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val config = repository.getRunningConfig(agentId).first()
                _uiState.update {
                    it.copy(runningConfig = config, isLoading = false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load running config")
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "加载配置失败: ${e.message}")
                }
            }
        }
    }

    fun updateConfig(config: RunningConfig) {
        _uiState.update { it.copy(runningConfig = config) }
    }

    fun resetConfig() {
        _uiState.update { it.copy(runningConfig = RunningConfig()) }
    }

    fun saveConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, saveSuccess = false) }
            try {
                repository.saveRunningConfig(agentId, _uiState.value.runningConfig)
                    .onSuccess {
                        agentFactory.reloadAgent(agentId)
                        _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
                    }
                    .onFailure { e ->
                        _uiState.update {
                            it.copy(isSaving = false, errorMessage = "保存失败: ${e.message}")
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save running config")
                _uiState.update {
                    it.copy(isSaving = false, errorMessage = "保存失败: ${e.message}")
                }
            }
        }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
}

