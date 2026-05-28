package com.lin.hippyagent.core.agent.plan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID

class AgentPlanManager(
    private val agentId: String
) {
    private val _currentPlan = MutableStateFlow<PlanState?>(null)
    val currentPlan: StateFlow<PlanState?> = _currentPlan.asStateFlow()

    private val _planConfig = MutableStateFlow(PlanConfig())
    val planConfig: StateFlow<PlanConfig> = _planConfig.asStateFlow()

    val isPlanEnabled: Boolean get() = _planConfig.value.enabled
    val hasActivePlan: Boolean get() = _currentPlan.value?.isActive == true

    fun setPlanEnabled(enabled: Boolean) {
        _planConfig.value = _planConfig.value.copy(enabled = enabled)
        Timber.d("Plan mode ${if (enabled) "enabled" else "disabled"} for agent: $agentId")
    }

    fun createPlan(
        name: String,
        description: String,
        expectedOutcome: String,
        subtasks: List<SubTask>
    ): PlanState {
        val plan = PlanState(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            expectedOutcome = expectedOutcome,
            state = PlanStateEnum.TODO,
            subtasks = subtasks,
            createdAt = System.currentTimeMillis()
        )
        _currentPlan.value = plan
        Timber.i("Plan created: ${plan.name} with ${subtasks.size} subtasks")
        return plan
    }

    fun activatePlan(): PlanState? {
        val current = _currentPlan.value ?: return null
        if (current.state != PlanStateEnum.TODO) return current

        val activated = current.activate()
        _currentPlan.value = activated
        Timber.d("Plan activated: ${activated.name}")
        return activated
    }

    fun updateSubTaskState(
        subtaskId: String,
        state: SubTaskState,
        outcome: String? = null
    ): PlanState? {
        val current = _currentPlan.value ?: return null

        val updated = current.updateSubTask(subtaskId, state, outcome)
        _currentPlan.value = updated

        Timber.d("Subtask $subtaskId updated to $state")
        return updated
    }

    fun updateFirstTodoToInProgress(): PlanState? {
        val current = _currentPlan.value ?: return null
        val firstTodo = current.subtasks.firstOrNull { it.state == SubTaskState.TODO }
            ?: return current

        val updated = current.updateSubTask(firstTodo.id, SubTaskState.IN_PROGRESS)
        _currentPlan.value = updated
        return updated
    }

    fun finishCurrentSubtask(outcome: String): PlanState? {
        val current = _currentPlan.value ?: return null
        val inProgress = current.subtasks.firstOrNull { it.state == SubTaskState.IN_PROGRESS }
            ?: return current

        val updated = current.updateSubTask(inProgress.id, SubTaskState.DONE, outcome)
        _currentPlan.value = updated

        if (updated.todoCount == 0 && updated.inProgressCount == 0) {
            return completePlan("所有任务已完成")
        }

        return updateFirstTodoToInProgress()
    }

    fun revisePlan(subtasks: List<SubTask>): PlanState? {
        val current = _currentPlan.value ?: return null

        val revised = current.updateSubtasks(subtasks)
        _currentPlan.value = revised
        Timber.i("Plan revised with ${subtasks.size} subtasks")
        return revised
    }

    fun completePlan(outcome: String? = null): PlanState? {
        val current = _currentPlan.value ?: return null

        val completed = current.complete(outcome)
        _currentPlan.value = completed
        Timber.i("Plan completed: ${completed.name}")
        return completed
    }

    fun abandonPlan(outcome: String? = null): PlanState? {
        val current = _currentPlan.value ?: return null

        val abandoned = current.abandon(outcome)
        _currentPlan.value = abandoned
        Timber.w("Plan abandoned: ${abandoned.name}")
        return abandoned
    }

    fun clearPlan() {
        val old = _currentPlan.value
        _currentPlan.value = null
        if (old != null) {
            Timber.d("Plan cleared: ${old.name}")
        }
    }

    fun finishPlan(state: PlanStateEnum, outcome: String? = null): PlanState? {
        return when (state) {
            PlanStateEnum.DONE -> completePlan(outcome)
            PlanStateEnum.ABANDONED -> abandonPlan(outcome)
            else -> {
                Timber.w("finishPlan called with invalid state: $state")
                null
            }
        }
    }

    fun getPlanSummary(): String? {
        return _currentPlan.value?.getSummary()
    }

    fun getPlanProgress(): Pair<Int, Int>? {
        val plan = _currentPlan.value ?: return null
        return plan.doneCount to plan.totalCount
    }
}