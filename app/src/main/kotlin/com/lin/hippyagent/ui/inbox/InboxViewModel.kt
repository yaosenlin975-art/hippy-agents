package com.lin.hippyagent.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.session.InboxEvent
import com.lin.hippyagent.core.agent.session.PendingApproval
import com.lin.hippyagent.core.inbox.ApprovalService
import com.lin.hippyagent.core.inbox.InboxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InboxViewModel(
    private val inboxStore: InboxStore,
    private val approvalService: ApprovalService
) : ViewModel() {

    private val _events = MutableStateFlow<List<InboxEvent>>(emptyList())
    val events: StateFlow<List<InboxEvent>> = _events.asStateFlow()

    private val _approvals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val approvals: StateFlow<List<PendingApproval>> = _approvals.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _events.value = inboxStore.listEvents(50, 0)
            _approvals.value = inboxStore.getAllApprovals()
            _unreadCount.value = inboxStore.getUnreadCount()
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            inboxStore.markRead(id)
            _unreadCount.value = inboxStore.getUnreadCount()
            _events.value = _events.value.map {
                if (it.id == id) it.copy(read = true) else it
            }
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            inboxStore.markAllRead()
            _unreadCount.value = 0
            _events.value = _events.value.map { it.copy(read = true) }
        }
    }

    fun approve(requestId: String) {
        viewModelScope.launch {
            approvalService.resolveApproval(requestId, "approved")
            _approvals.value = _approvals.value.map {
                if (it.requestId == requestId) it.copy(status = "approved") else it
            }
        }
    }

    fun deny(requestId: String) {
        viewModelScope.launch {
            approvalService.resolveApproval(requestId, "denied")
            _approvals.value = _approvals.value.map {
                if (it.requestId == requestId) it.copy(status = "denied") else it
            }
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            inboxStore.deleteEvent(id)
            _events.value = _events.value.filter { it.id != id }
            _unreadCount.value = inboxStore.getUnreadCount()
        }
    }
}
