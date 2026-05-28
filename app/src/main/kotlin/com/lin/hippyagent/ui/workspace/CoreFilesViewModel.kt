package com.lin.hippyagent.ui.workspace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.config.CoreFile
import com.lin.hippyagent.data.repository.AgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class CoreFilesUiState(
    val files: List<CoreFile> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val editingFile: String? = null,
    val editingContent: String? = null
)

class CoreFilesViewModel(
    private val repository: AgentRepository,
    private val agentId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoreFilesUiState())
    val uiState: StateFlow<CoreFilesUiState> = _uiState.asStateFlow()

    init {
        refreshFiles()
    }

    fun refreshFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                repository.getCoreFiles(agentId).first()
                    .let { files ->
                        _uiState.update { it.copy(files = files, isLoading = false) }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load core files")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "加载文件列表失败: ${e.message}"
                    )
                }
            }
        }
    }

    fun toggleFile(filename: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                repository.toggleCoreFile(agentId, filename, enabled).first()
                    .let { files ->
                        _uiState.update { it.copy(files = files) }
                    }
            } catch (e: Exception) {
                Timber.e(e, "Failed to toggle file: $filename")
            }
        }
    }

    fun openFileForEdit(filename: String) {
        viewModelScope.launch {
            repository.readCoreFile(agentId, filename)
                .onSuccess { content ->
                    _uiState.update {
                        it.copy(
                            editingFile = filename,
                            editingContent = content
                        )
                    }
                }
                .onFailure {
                    Timber.e(it, "Failed to read file: $filename")
                }
        }
    }

    fun saveFile(content: String) {
        val filename = _uiState.value.editingFile ?: return
        viewModelScope.launch {
            repository.writeCoreFile(agentId, filename, content)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            editingFile = null,
                            editingContent = null
                        )
                    }
                    refreshFiles()
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(errorMessage = "保存失败: ${e.message}")
                    }
                }
        }
    }

    fun cancelEdit() {
        _uiState.update {
            it.copy(
                editingFile = null,
                editingContent = null
            )
        }
    }

    fun showFileActions(filename: String) {
        openFileForEdit(filename)
    }
}

