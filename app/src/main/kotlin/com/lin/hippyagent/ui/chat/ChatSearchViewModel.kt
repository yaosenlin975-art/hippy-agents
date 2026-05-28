package com.lin.hippyagent.ui.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.session.MessageSearchResult
import com.lin.hippyagent.core.agent.session.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class ChatSearchUiState(
    val query: String = "",
    val results: List<MessageSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val errorMessage: String? = null
)

class ChatSearchViewModel(
    private val sessionStore: SessionStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatSearchUiState())
    val uiState: StateFlow<ChatSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        _uiState.update { it.copy(isSearching = true) }
        sessionStore.searchAllMessages(query)
            .onSuccess { results ->
                _uiState.update { it.copy(results = results, isSearching = false, errorMessage = null) }
            }
            .onFailure { e ->
                Timber.e(e, "Search failed")
                _uiState.update { it.copy(isSearching = false, errorMessage = e.message) }
            }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update { ChatSearchUiState() }
    }
}

