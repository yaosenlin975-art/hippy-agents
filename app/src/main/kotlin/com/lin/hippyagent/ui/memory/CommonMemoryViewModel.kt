package com.lin.hippyagent.ui.memory

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.memory.commonmemory.BrainMemoryType
import com.lin.hippyagent.core.memory.commonmemory.MemoryRepository
import com.lin.hippyagent.core.memory.commonmemory.MemoryStats
import com.lin.hippyagent.core.memory.commonmemory.PruneResult
import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CommonMemoryUiState(
    val entries: List<CommonMemoryEntry> = emptyList(),
    val stats: MemoryStats? = null,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val filterType: BrainMemoryType? = null,
    val pendingDeleteId: String? = null
)

class CommonMemoryViewModel(
    private val memoryRepository: MemoryRepository,
    private val agentId: String = ""
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommonMemoryUiState())
    val uiState: StateFlow<CommonMemoryUiState> = _uiState.asStateFlow()

    init {
        loadEntries()
    }

    fun loadEntries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val entries = memoryRepository.findActive(200)
                val stats = memoryRepository.getStats()
                _uiState.update { it.copy(entries = entries, stats = stats, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            if (query.isBlank()) {
                loadEntries()
                return@launch
            }
            val results = memoryRepository.searchFts(query, 50)
            // FTS 查询已过滤 dismissed = 0
            _uiState.update { it.copy(entries = results) }
        }
    }

    fun filterByType(type: BrainMemoryType?) {
        _uiState.update { it.copy(filterType = type) }
        viewModelScope.launch {
            if (type == null) {
                loadEntries()
                return@launch
            }
            val entries = memoryRepository.findByType(type, 200)
            // findByType 已过滤 dismissed = 0
            _uiState.update { it.copy(entries = entries) }
        }
    }

    fun confirmDelete(id: String) {
        _uiState.update { it.copy(pendingDeleteId = id) }
    }

    fun deleteEntry(id: String) {
        // 乐观删除：立即从列表中移除，避免卡顿
        _uiState.update {
            it.copy(
                pendingDeleteId = null,
                entries = it.entries.filter { e -> e.id != id }
            )
        }
        // 后台异步执行软删除并刷新统计
        viewModelScope.launch {
            memoryRepository.softDelete(id)
            val stats = memoryRepository.getStats()
            _uiState.update { it.copy(stats = stats) }
        }
    }
}
