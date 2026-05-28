package com.lin.hippyagent.ui.settings.acp

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.collaboration.AcpClientStore
import com.lin.hippyagent.core.agent.collaboration.AcpRemoteServer
import com.lin.hippyagent.core.agent.collaboration.DelegateExternalAgentTool
import com.lin.hippyagent.core.agent.collaboration.DiscoveredAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

data class AcpClientUiState(
    val servers: List<AcpRemoteServer> = emptyList(),
    val isDiscovering: Set<String> = emptySet(),
    val showAddDialog: Boolean = false,
    val editingServer: AcpRemoteServer? = null,
    val error: String? = null,
    val successMessage: String? = null
)

class AcpClientViewModel(
    private val clientStore: AcpClientStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AcpClientUiState())
    val uiState: StateFlow<AcpClientUiState> = _uiState.asStateFlow()

    private val discoverTool = DelegateExternalAgentTool(clientStore)

    init {
        loadServers()
    }

    fun loadServers() {
        _uiState.update { it.copy(servers = clientStore.getServers()) }
    }

    fun showAddDialog() {
        _uiState.update { it.copy(showAddDialog = true, editingServer = null) }
    }

    fun showEditDialog(server: AcpRemoteServer) {
        _uiState.update { it.copy(showAddDialog = true, editingServer = server) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(showAddDialog = false, editingServer = null) }
    }

    fun addOrUpdateServer(name: String, host: String, port: Int) {
        val editing = _uiState.value.editingServer
        if (editing != null) {
            clientStore.updateServer(editing.copy(name = name, host = host, port = port))
        } else {
            clientStore.addServer(
                AcpRemoteServer(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    host = host,
                    port = port
                )
            )
        }
        _uiState.update { it.copy(showAddDialog = false, editingServer = null) }
        loadServers()
    }

    fun removeServer(serverId: String) {
        clientStore.removeServer(serverId)
        loadServers()
    }

    fun toggleServerEnabled(server: AcpRemoteServer) {
        clientStore.updateServer(server.copy(enabled = !server.enabled))
        loadServers()
    }

    fun discoverAgents(server: AcpRemoteServer) {
        _uiState.update { it.copy(isDiscovering = it.isDiscovering + server.id) }
        viewModelScope.launch {
            val result = discoverTool.discoverAgents(server.host, server.port)
            result.onSuccess { agents ->
                clientStore.updateServer(
                    server.copy(
                        discoveredAgents = agents,
                        lastDiscoveryAt = System.currentTimeMillis()
                    )
                )
                _uiState.update {
                    it.copy(
                        isDiscovering = it.isDiscovering - server.id,
                        successMessage = "发现 ${agents.size} 个智能体"
                    )
                }
                loadServers()
            }.onFailure { e ->
                Timber.w(e, "Discovery failed for ${server.host}:${server.port}")
                _uiState.update {
                    it.copy(
                        isDiscovering = it.isDiscovering - server.id,
                        error = "发现失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}

