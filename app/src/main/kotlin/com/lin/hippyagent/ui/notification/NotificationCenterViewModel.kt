package com.lin.hippyagent.ui.notification

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.notification.NotificationCenter
import com.lin.hippyagent.core.notification.NotificationEvent
import com.lin.hippyagent.core.notification.NotificationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class NotificationCenterUiState(
    val events: List<NotificationEvent> = emptyList(),
    val activeType: NotificationType? = null,
    val loading: Boolean = false
)

class NotificationCenterViewModel(
    private val center: NotificationCenter
) : ViewModel() {

    private val activeType = MutableStateFlow<NotificationType?>(null)
    private val loading = MutableStateFlow(false)

    val uiState: StateFlow<NotificationCenterUiState> = combine(
        center.observe(),
        activeType,
        loading
    ) { events, type, isLoading ->
        NotificationCenterUiState(
            events = events,
            activeType = type,
            loading = isLoading
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = NotificationCenterUiState()
    )

    fun setActiveType(type: NotificationType?) {
        activeType.value = type
    }

    fun markRead(id: String) {
        viewModelScope.launch { center.markAsRead(id) }
    }

    fun acknowledge(id: String) {
        viewModelScope.launch { center.acknowledge(id) }
    }

    fun dismiss(id: String) {
        viewModelScope.launch { center.dismiss(id) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
