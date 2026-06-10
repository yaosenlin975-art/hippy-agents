package com.lin.hippyagent.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.task.TaskApprovalService
import com.lin.hippyagent.core.agent.task.TaskDao
import com.lin.hippyagent.core.agent.task.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class TaskDetailViewModel(
    private val dao: TaskDao,
    private val approvalService: TaskApprovalService?,
) : ViewModel() {

    private val _task = MutableStateFlow<TaskEntity?>(null)
    val task: StateFlow<TaskEntity?> = _task.asStateFlow()

    fun load(taskId: String) {
        viewModelScope.launch {
            runCatching { dao.getById(taskId) }
                .onSuccess { _task.value = it }
                .onFailure { Timber.e(it, "Failed to load task $taskId") }
        }
    }

    fun approve(nodeId: String) {
        val svc = approvalService ?: return
        viewModelScope.launch {
            runCatching { svc.approve(nodeId) }
                .onFailure { Timber.e(it, "Approve failed") }
        }
    }

    fun reject(nodeId: String, reason: String? = null) {
        val svc = approvalService ?: return
        viewModelScope.launch {
            runCatching { svc.reject(nodeId, reason) }
                .onFailure { Timber.e(it, "Reject failed") }
        }
    }
}
