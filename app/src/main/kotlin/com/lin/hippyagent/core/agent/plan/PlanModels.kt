package com.lin.hippyagent.core.agent.plan

import java.util.UUID

enum class PlanStateEnum {
    TODO,
    IN_PROGRESS,
    DONE,
    ABANDONED
}

enum class SubTaskState {
    TODO,
    IN_PROGRESS,
    DONE,
    ABANDONED
}

data class SubTask(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val expectedOutcome: String,
    val state: SubTaskState = SubTaskState.TODO,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val outcome: String? = null
) {
    fun markInProgress(): SubTask = copy(state = SubTaskState.IN_PROGRESS)

    fun markDone(outcome: String): SubTask = copy(
        state = SubTaskState.DONE,
        outcome = outcome,
        finishedAt = System.currentTimeMillis()
    )

    fun markAbandoned(outcome: String? = null): SubTask = copy(
        state = SubTaskState.ABANDONED,
        outcome = outcome,
        finishedAt = System.currentTimeMillis()
    )
}

data class PlanState(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val expectedOutcome: String,
    val state: PlanStateEnum = PlanStateEnum.TODO,
    val subtasks: List<SubTask> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val outcome: String? = null
) {
    val todoCount: Int get() = subtasks.count { it.state == SubTaskState.TODO }
    val inProgressCount: Int get() = subtasks.count { it.state == SubTaskState.IN_PROGRESS }
    val doneCount: Int get() = subtasks.count { it.state == SubTaskState.DONE || it.state == SubTaskState.ABANDONED }
    val totalCount: Int get() = subtasks.size
    val progress: Int get() = if (totalCount > 0) (doneCount * 100 / totalCount) else 0
    val isActive: Boolean get() = state == PlanStateEnum.TODO || state == PlanStateEnum.IN_PROGRESS
    val isComplete: Boolean get() = state == PlanStateEnum.DONE || state == PlanStateEnum.ABANDONED

    fun updateSubTask(subtaskId: String, newState: SubTaskState, outcome: String? = null): PlanState {
        val updatedSubtasks = subtasks.map { task ->
            if (task.id == subtaskId) {
                when (newState) {
                    SubTaskState.IN_PROGRESS -> task.markInProgress()
                    SubTaskState.DONE -> task.markDone(outcome ?: "")
                    SubTaskState.ABANDONED -> task.markAbandoned(outcome)
                    SubTaskState.TODO -> task
                }
            } else {
                task
            }
        }
        return copy(subtasks = updatedSubtasks)
    }

    fun activate(): PlanState = copy(state = PlanStateEnum.IN_PROGRESS)

    fun complete(outcome: String? = null): PlanState = copy(
        state = PlanStateEnum.DONE,
        outcome = outcome,
        finishedAt = System.currentTimeMillis()
    )

    fun abandon(outcome: String? = null): PlanState = copy(
        state = PlanStateEnum.ABANDONED,
        outcome = outcome,
        finishedAt = System.currentTimeMillis()
    )

    fun updateSubtasks(newSubtasks: List<SubTask>): PlanState = copy(subtasks = newSubtasks)

    fun getSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("计划: $name")
        sb.appendLine("进度: $doneCount/$totalCount (${progress}%)")
        if (inProgressCount > 0) {
            val inProgress = subtasks.first { it.state == SubTaskState.IN_PROGRESS }
            sb.appendLine("当前: ${inProgress.name}")
        }
        return sb.toString()
    }
}

data class PlanConfig(
    val enabled: Boolean = false
)