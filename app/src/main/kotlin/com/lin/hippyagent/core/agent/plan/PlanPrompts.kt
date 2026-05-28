package com.lin.hippyagent.core.agent.plan

object PlanPrompts {

    const val PLAN_MODE_ENABLED_HINT = """
## Plan Mode Active

当用户发送任务时，请先分析任务复杂度：

**简单任务（单步骤）**: 直接执行，无需计划。

**复杂任务（多步骤）**: 必须先调用 `create_plan` 工具制定计划，等待用户确认后再执行。

## Available Plan Tools

- `create_plan`: 创建新的执行计划
- `update_subtask_state`: 更新子任务状态
- `revise_current_plan`: 修改当前计划的子任务
- `finish_plan`: 结束当前计划
"""

    const val PLAN_WORKFLOW_HINT = """
## Plan Workflow

1. **分析任务** - 分析用户需求，评估是否需要制定计划
2. **创建计划** - 调用 `create_plan` 分解任务为子任务
3. **展示计划** - 向用户展示计划，等待确认
4. **执行计划** - 用户确认后，更新子任务状态为 `in_progress` 并开始执行
5. **更新进度** - 使用 `update_subtask_state` 更新各子任务状态
6. **调整计划** - 如有需要，使用 `revise_current_plan` 调整
7. **完成计划** - 所有任务完成后，调用 `finish_plan` 结束
"""

    fun buildPlanStateHint(plan: PlanState): String {
        return buildString {
            appendLine("## Current Plan Status")
            appendLine()
            appendLine("**Plan**: ${plan.name}")
            appendLine("**State**: ${plan.state.name}")
            appendLine()
            appendLine("**Progress**: ${plan.doneCount}/${plan.totalCount} (${plan.progress}%)")
            appendLine()

            if (plan.subtasks.isNotEmpty()) {
                appendLine("**Subtasks**:")
                plan.subtasks.forEachIndexed { index, subtask ->
                    val icon = when (subtask.state) {
                        SubTaskState.DONE -> "✅"
                        SubTaskState.IN_PROGRESS -> "🔄"
                        SubTaskState.ABANDONED -> "⛔"
                        SubTaskState.TODO -> "⬜"
                    }
                    appendLine("  ${index + 1}. $icon ${subtask.name}")
                    if (subtask.state == SubTaskState.IN_PROGRESS) {
                        appendLine("     - ${subtask.description}")
                    }
                }
            }
        }
    }

    fun buildConfirmationHint(plan: PlanState): String {
        return buildString {
            appendLine("## Plan Created - Please Confirm")
            appendLine()
            appendLine("**${plan.name}**")
            appendLine(plan.description)
            appendLine()
            appendLine("**Expected Outcome**: ${plan.expectedOutcome}")
            appendLine()
            appendLine("**Subtasks (${plan.subtasks.size})**:")
            plan.subtasks.forEachIndexed { index, subtask ->
                appendLine("  ${index + 1}. ${subtask.name}")
                appendLine("     ${subtask.description}")
                appendLine("     Expected: ${subtask.expectedOutcome}")
            }
            appendLine()
            appendLine("**Next Steps**:")
            appendLine("- If you approve, reply 'yes' or 'start' to begin execution")
            appendLine("- If you want modifications, describe what to change")
            appendLine("- If you want to cancel, reply 'no' or 'cancel'")
        }
    }

    fun buildInProgressHint(plan: PlanState, currentSubtask: SubTask): String {
        return buildString {
            appendLine("## Currently Executing")
            appendLine()
            appendLine("**${currentSubtask.name}**")
            appendLine(currentSubtask.description)
            appendLine()
            appendLine("**Expected**: ${currentSubtask.expectedOutcome}")
            appendLine()
            appendLine(buildPlanStateHint(plan))
            appendLine()
            appendLine("**Instructions**:")
            appendLine("- Execute this subtask step by step")
            appendLine("- Use tools to complete the task")
            appendLine("- When done, use `update_subtask_state` with state='done'")
            appendLine("- CRITICAL: Always include a tool call, do not reply with text only")
        }
    }

    fun buildPlanSummaryForSystem(plan: PlanState): String {
        return buildString {
            appendLine("当前计划: ${plan.name}")
            appendLine("进度: ${plan.doneCount}/${plan.totalCount}")
            if (plan.inProgressCount > 0) {
                val inProgress = plan.subtasks.first { it.state == SubTaskState.IN_PROGRESS }
                appendLine("正在进行: ${inProgress.name}")
            }
        }
    }
}

object PlanToolGate {
    private var _gateEnabled = false
    private var _planJustMutated = false

    val isGateEnabled: Boolean get() = _gateEnabled
    val planJustMutated: Boolean get() = _planJustMutated

    fun enableGate() {
        _gateEnabled = true
        _planJustMutated = true
    }

    fun disableGate() {
        _gateEnabled = false
    }

    fun markMutationComplete() {
        _planJustMutated = false
    }

    fun canUseTool(toolName: String): Boolean {
        if (!_gateEnabled) return true

        val allowedTools = setOf("create_plan", "revise_current_plan")
        if (toolName in allowedTools) {
            return true
        }

        return false
    }

    fun getBlockedToolMessage(): String {
        return "Tool is not available. You MUST call 'create_plan' first to define the plan."
    }
}