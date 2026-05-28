package com.lin.hippyagent.ui.settings.mcp

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.config.MCPClientConfig
import com.lin.hippyagent.core.agent.config.MCPConfig
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class MCPUiState(
    val mcpConfig: MCPConfig = MCPConfig(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saveSuccess: Boolean = false,
    val showCreateDialog: Boolean = false,
    val editingClientKey: String? = null
)

class MCPViewModel(
    private val repository: AgentRepository,
    private val agentId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(MCPUiState())
    val uiState: StateFlow<MCPUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val config = repository.getMCPConfig(agentId).first()
                _uiState.update {
                    it.copy(
                        mcpConfig = config,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load MCP config")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载配置失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun toggleClientEnabled(key: String, enabled: Boolean) {
        val currentClients = _uiState.value.mcpConfig.clients.toMutableMap()
        val client = currentClients[key] ?: return
        currentClients[key] = client.copy(enabled = enabled)
        _uiState.update {
            it.copy(
                mcpConfig = MCPConfig(clients = currentClients)
            )
        }
    }

    fun addClient(key: String, config: MCPClientConfig) {
        val currentClients = _uiState.value.mcpConfig.clients.toMutableMap()
        currentClients[key] = config
        _uiState.update {
            it.copy(
                mcpConfig = MCPConfig(clients = currentClients),
                showCreateDialog = false
            )
        }
    }

    fun updateClient(key: String, config: MCPClientConfig) {
        val currentClients = _uiState.value.mcpConfig.clients.toMutableMap()
        currentClients[key] = config
        _uiState.update {
            it.copy(
                mcpConfig = MCPConfig(clients = currentClients),
                editingClientKey = null
            )
        }
    }

    fun deleteClient(key: String) {
        val currentClients = _uiState.value.mcpConfig.clients.toMutableMap()
        currentClients.remove(key)
        _uiState.update {
            it.copy(
                mcpConfig = MCPConfig(clients = currentClients)
            )
        }
    }

    fun showCreateDialog() {
        _uiState.update { it.copy(showCreateDialog = true, editingClientKey = null) }
    }

    fun showEditDialog(key: String) {
        _uiState.update { it.copy(editingClientKey = key) }
    }

    fun hideDialogs() {
        _uiState.update { it.copy(showCreateDialog = false, editingClientKey = null) }
    }

    fun saveConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, saveSuccess = false) }
            try {
                repository.saveMCPConfig(agentId, _uiState.value.mcpConfig)
                    .onSuccess {
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
                Timber.e(e, "Failed to save MCP config")
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

