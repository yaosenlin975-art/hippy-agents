package com.lin.hippyagent.ui.settings.agent

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.config.ActiveHoursConfig
import com.lin.hippyagent.core.agent.config.HeartbeatConfig
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class HeartbeatUiState(
    val heartbeatConfig: HeartbeatConfig = HeartbeatConfig(),
    val intervalValue: Int = 6,
    val intervalUnit: String = "h",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saveSuccess: Boolean = false
)

class HeartbeatViewModel(
    private val repository: AgentRepository,
    private val agentId: String,
    private val heartbeatScheduler: com.lin.hippyagent.core.heartbeat.HeartbeatScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(HeartbeatUiState())
    val uiState: StateFlow<HeartbeatUiState> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    private fun debouncedSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500L)
            saveConfig()
        }
    }

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val config = repository.getHeartbeatConfig(agentId).first()
                val intervalPair: Pair<Int, String> = parseInterval(config.every)
                val value = intervalPair.first
                val unit = intervalPair.second
                _uiState.update {
                    it.copy(
                        heartbeatConfig = config,
                        intervalValue = value,
                        intervalUnit = unit,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load heartbeat config")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载配置失败: ${e.message}"
                    )
                }
            }
        }
    }

    private fun parseInterval(every: String): Pair<Int, String> {
        return when {
            every.endsWith("h") -> Pair(every.dropLast(1).toIntOrNull() ?: 6, "h")
            every.endsWith("m") -> Pair(every.dropLast(1).toIntOrNull() ?: 360, "m")
            else -> Pair(6, "h")
        }
    }

    private fun buildInterval(value: Int, unit: String): String {
        return "$value$unit"
    }

    fun updateEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(heartbeatConfig = it.heartbeatConfig.copy(enabled = enabled))
        }
        debouncedSave()
    }

    fun updateIntervalValue(value: Int) {
        if (value < 1) return
        _uiState.update { it.copy(intervalValue = value) }
        updateInterval()
        debouncedSave()
    }

    fun updateIntervalUnit(unit: String) {
        _uiState.update { it.copy(intervalUnit = unit) }
        updateInterval()
        debouncedSave()
    }

    private fun updateInterval() {
        val state = _uiState.value
        val interval = buildInterval(state.intervalValue, state.intervalUnit)
        _uiState.update {
            it.copy(heartbeatConfig = it.heartbeatConfig.copy(every = interval))
        }
    }

    fun updateTarget(target: String) {
        _uiState.update {
            it.copy(heartbeatConfig = it.heartbeatConfig.copy(target = target))
        }
        debouncedSave()
    }

    fun updateActiveHoursEnabled(enabled: Boolean) {
        val state = _uiState.value
        if (enabled && state.heartbeatConfig.activeHours == null) {
            _uiState.update {
                it.copy(
                    heartbeatConfig = it.heartbeatConfig.copy(
                        activeHours = ActiveHoursConfig()
                    )
                )
            }
        } else if (!enabled) {
            _uiState.update {
                it.copy(
                    heartbeatConfig = it.heartbeatConfig.copy(activeHours = null)
                )
            }
        }
        debouncedSave()
    }

    fun updateActiveHoursStart(start: String) {
        val activeHours = _uiState.value.heartbeatConfig.activeHours ?: return
        _uiState.update {
            it.copy(
                heartbeatConfig = it.heartbeatConfig.copy(
                    activeHours = activeHours.copy(start = start)
                )
            )
        }
        debouncedSave()
    }

    fun updateActiveHoursEnd(end: String) {
        val activeHours = _uiState.value.heartbeatConfig.activeHours ?: return
        _uiState.update {
            it.copy(
                heartbeatConfig = it.heartbeatConfig.copy(
                    activeHours = activeHours.copy(end = end)
                )
            )
        }
        debouncedSave()
    }

    fun resetConfig() {
        _uiState.update {
            it.copy(
                heartbeatConfig = HeartbeatConfig(),
                intervalValue = 6,
                intervalUnit = "h"
            )
        }
    }

    fun saveConfig() {
        if (_uiState.value.intervalValue < 1) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, saveSuccess = false) }
            try {
                repository.saveHeartbeatConfig(agentId, _uiState.value.heartbeatConfig)
                    .onSuccess {
                        heartbeatScheduler.scheduleHeartbeat(agentId, _uiState.value.heartbeatConfig)
                        _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
                    }
                    .onFailure { e ->
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = "保存失败: ${e.message}"
                            )
                        }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save heartbeat config")
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = "保存失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
}

