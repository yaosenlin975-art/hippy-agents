package com.lin.hippyagent.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin.hippyagent.core.agent.task.TaskDao
import com.lin.hippyagent.core.agent.task.TaskEntity
import com.lin.hippyagent.core.agent.task.TaskExecutionEngine
import com.lin.hippyagent.core.agent.task.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TaskListViewModel(
    private val dao: TaskDao,
    private val engine: TaskExecutionEngine? = null,
) : ViewModel() {

    private val _statusFilter = MutableStateFlow<TaskStatus?>(null)
    val statusFilter: StateFlow<TaskStatus?> = _statusFilter.asStateFlow()

    // 默认隐藏 source='tool_approval' (单工具临时任务), 避免污染任务列表
    private val _showToolApproval = MutableStateFlow(false)
    val showToolApproval: StateFlow<Boolean> = _showToolApproval.asStateFlow()

    val tasks: StateFlow<List<TaskEntity>> = combine(
        _statusFilter.flatMapLatest { filter ->
            if (filter == null) dao.observeAll() else dao.observeByStatus(filter)
        },
        _showToolApproval
    ) { list, showToolApproval ->
        if (showToolApproval) list else list.filter { it.source != "tool_approval" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setStatusFilter(status: TaskStatus?) {
        _statusFilter.value = status
    }

    fun setShowToolApproval(show: Boolean) {
        _showToolApproval.value = show
    }

    /**
     * 取消任务:优先走 TaskExecutionEngine(同步停掉子任务/Worker),
     * 引擎不可用时回退到直接更新状态(仅 UI 兜底)。
     */
    fun cancelTask(task: TaskEntity) {
        viewModelScope.launch {
            runCatching {
                if (engine != null) {
                    engine.cancelTask(task.id)
                } else {
                    dao.update(
                        task.copy(
                            status = TaskStatus.CANCELLED,
                            updatedAt = System.currentTimeMillis(),
                            completedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }
}
