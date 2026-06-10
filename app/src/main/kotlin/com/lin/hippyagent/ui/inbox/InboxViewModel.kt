package com.lin.hippyagent.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.session.InboxEvent
import com.lin.hippyagent.core.inbox.InboxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 收件箱 ViewModel — 仅显示 push/system 消息。
 * 审批请求已迁移至 task.TaskApprovalService + ChatScreen 内联卡片, 不再走本 ViewModel。
 */
class InboxViewModel(
    private val inboxStore: InboxStore
) : ViewModel() {

    private val _events = MutableStateFlow<List<InboxEvent>>(emptyList())
    val events: StateFlow<List<InboxEvent>> = _events.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _events.value = inboxStore.listEvents(50, 0)
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

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            inboxStore.deleteEvent(id)
            _events.value = _events.value.filter { it.id != id }
            _unreadCount.value = inboxStore.getUnreadCount()
        }
    }
}
