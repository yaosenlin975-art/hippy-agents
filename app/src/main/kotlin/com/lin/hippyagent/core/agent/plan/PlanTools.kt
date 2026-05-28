package com.lin.hippyagent.core.agent.plan

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import com.lin.hippyagent.core.tools.ToolContext
import timber.log.Timber
import java.util.UUID

class CreatePlanTool(
    private val planManager: AgentPlanManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "create_plan",
        description = "创建执行计划，将复杂任务分解为可管理的子任务",
        parameters = mapOf(
            "name" to ToolParameter("name", "string", "计划名称", required = true),
            "description" to ToolParameter("description", "string", "计划描述", required = true),
            "expected_outcome" to ToolParameter("expected_outcome", "string", "预期结果", required = true),
            "subtasks" to ToolParameter("subtasks", "array", "子任务列表，每个子任务包含 name(名称)、description(描述)、expected_outcome(预期结果)", required = true,
                items = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "子任务名称"),
                        "description" to mapOf("type" to "string", "description" to "子任务描述"),
                        "expected_outcome" to mapOf("type" to "string", "description" to "预期结果")
                    ),
                    "required" to listOf("name", "description")
                )
            )
        )
    )

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val ctx = ToolContext(messageId = UUID.randomUUID().toString())
        return executeInternal(ctx, args)
    }

    private suspend fun executeInternal(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        return try {
            val name = args["name"]?.toString()
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "缺少参数: name"
                )

            val description = args["description"]?.toString()
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "缺少参数: description"
                )

            val expectedOutcome = args["expected_outcome"]?.toString()
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "缺少参数: expected_outcome"
                )

            val subtasksRaw = parseSubtasks(args["subtasks"])
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "subtasks 格式错误：需要 JSON 对象数组，如 [{\"name\":\"步骤1\",\"description\":\"描述\",\"expected_outcome\":\"结果\"}]，每个子任务必须包含 name 和 description"
                )

            val subtasks = subtasksRaw.mapNotNull { subtaskMap ->
                val subName = subtaskMap["name"]?.toString() ?: return@mapNotNull null
                val subDesc = subtaskMap["description"]?.toString() ?: ""
                val subOutcome = subtaskMap["expected_outcome"]?.toString() ?: ""
                SubTask(
                    name = subName,
                    description = subDesc,
                    expectedOutcome = subOutcome
                )
            }

            if (subtasks.isEmpty()) {
                return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "至少需要一个子任务"
                )
            }

            val plan = planManager.createPlan(name, description, expectedOutcome, subtasks)

            val summary = buildString {
                appendLine("计划已创建: ${plan.name}")
                appendLine()
                appendLine("子任务:")
                plan.subtasks.forEachIndexed { index, task ->
                    appendLine("${index + 1}. ${task.name}")
                    appendLine("   描述: ${task.description}")
                }
                appendLine()
                appendLine("请确认后开始执行，或修改计划。")
            }

            ToolResult(
                callId = ctx.messageId,
                success = true,
                output = summary,
                forLLM = summary,
                forUser = "计划已创建: ${plan.name}"
            )
        } catch (e: Exception) {
            Timber.e(e, "create_plan tool failed")
            ToolResult(
                callId = ctx.messageId,
                success = false,
                error = "创建计划失败: ${e.message}"
            )
        }
    }
}

class UpdateSubTaskStateTool(
    private val planManager: AgentPlanManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "update_subtask_state",
        description = "更新子任务状态 (in_progress, done, abandoned)",
        parameters = mapOf(
            "subtask_id" to ToolParameter("subtask_id", "string", "子任务ID", required = true),
            "state" to ToolParameter("state", "string", "新状态: in_progress, done, abandoned", required = true),
            "outcome" to ToolParameter("outcome", "string", "任务结果", required = false)
        )
    )

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val ctx = ToolContext(messageId = UUID.randomUUID().toString())
        return executeInternal(ctx, args)
    }

    private suspend fun executeInternal(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        return try {
            val subtaskId = args["subtask_id"]?.toString()
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "缺少参数: subtask_id"
                )

            val stateStr = args["state"]?.toString()
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "缺少参数: state"
                )

            val state = when (stateStr.lowercase()) {
                "in_progress" -> SubTaskState.IN_PROGRESS
                "done" -> SubTaskState.DONE
                "abandoned" -> SubTaskState.ABANDONED
                else -> return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "无效的 state: $stateStr，可选值: in_progress, done, abandoned"
                )
            }

            val outcome = args["outcome"]?.toString()

            val updated = planManager.updateSubTaskState(subtaskId, state, outcome)
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "找不到子任务或无活动计划"
                )

            ToolResult(
                callId = ctx.messageId,
                success = true,
                output = "子任务状态已更新: ${updated.doneCount}/${updated.totalCount}",
                forLLM = "Progress: ${updated.doneCount}/${updated.totalCount} (${updated.progress}%)",
                forUser = "进度: ${updated.doneCount}/${updated.totalCount}"
            )
        } catch (e: Exception) {
            Timber.e(e, "update_subtask_state tool failed")
            ToolResult(
                callId = ctx.messageId,
                success = false,
                error = "更新子任务状态失败: ${e.message}"
            )
        }
    }
}

class ReviseCurrentPlanTool(
    private val planManager: AgentPlanManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "revise_current_plan",
        description = "修改当前计划的子任务列表",
        parameters = mapOf(
            "subtasks" to ToolParameter("subtasks", "array", "新的子任务列表", required = true)
        )
    )

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val ctx = ToolContext(messageId = UUID.randomUUID().toString())
        return executeInternal(ctx, args)
    }

    private suspend fun executeInternal(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        return try {
            val subtasksRaw = parseSubtasks(args["subtasks"])
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "缺少参数: subtasks（需要 JSON 数组格式）"
                )

            val subtasks = subtasksRaw.mapNotNull { subtaskMap ->
                val subName = subtaskMap["name"]?.toString() ?: return@mapNotNull null
                val subDesc = subtaskMap["description"]?.toString() ?: ""
                val subOutcome = subtaskMap["expected_outcome"]?.toString() ?: ""
                SubTask(
                    id = subtaskMap["id"]?.toString() ?: UUID.randomUUID().toString(),
                    name = subName,
                    description = subDesc,
                    expectedOutcome = subOutcome
                )
            }

            if (subtasks.isEmpty()) {
                return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "至少需要一个子任务"
                )
            }

            val updated = planManager.revisePlan(subtasks)
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "无活动计划可修改"
                )

            ToolResult(
                callId = ctx.messageId,
                success = true,
                output = "计划已修改: ${subtasks.size} 个子任务",
                forLLM = "Plan revised: ${updated.doneCount}/${updated.totalCount}",
                forUser = "计划已更新"
            )
        } catch (e: Exception) {
            Timber.e(e, "revise_current_plan tool failed")
            ToolResult(
                callId = ctx.messageId,
                success = false,
                error = "修改计划失败: ${e.message}"
            )
        }
    }
}

class FinishPlanTool(
    private val planManager: AgentPlanManager
) : Tool() {
    override val definition = ToolDefinition(
        name = "finish_plan",
        description = "结束当前计划 (done 或 abandoned)",
        parameters = mapOf(
            "state" to ToolParameter("state", "string", "最终状态: done, abandoned", required = true),
            "outcome" to ToolParameter("outcome", "string", "最终总结", required = false)
        )
    )

    override suspend fun execute(args: Map<String, Any>): ToolResult {
        val ctx = ToolContext(messageId = UUID.randomUUID().toString())
        return executeInternal(ctx, args)
    }

    private suspend fun executeInternal(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        return try {
            val stateStr = args["state"]?.toString()
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "缺少参数: state"
                )

            val state = when (stateStr.lowercase()) {
                "done" -> PlanStateEnum.DONE
                "abandoned" -> PlanStateEnum.ABANDONED
                else -> return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "无效的 state: $stateStr，可选值: done, abandoned"
                )
            }

            val outcome = args["outcome"]?.toString()

            val finished = planManager.finishPlan(state, outcome)
                ?: return ToolResult(
                    callId = ctx.messageId,
                    success = false,
                    error = "无活动计划可结束"
                )

            ToolResult(
                callId = ctx.messageId,
                success = true,
                output = "计划已结束: ${finished.state.name}",
                forLLM = "Plan finished: ${finished.state.name}",
                forUser = "计划已完成"
            )
        } catch (e: Exception) {
            Timber.e(e, "finish_plan tool failed")
            ToolResult(
                callId = ctx.messageId,
                success = false,
                error = "结束计划失败: ${e.message}"
            )
        }
    }
}

object PlanToolFactory {
    fun createTools(planManager: AgentPlanManager): List<Tool> {
        return listOf(
            CreatePlanTool(planManager),
            UpdateSubTaskStateTool(planManager),
            ReviseCurrentPlanTool(planManager),
            FinishPlanTool(planManager)
        )
    }
}

private fun parseSubtasks(value: Any?): List<Map<String, Any>>? {
    if (value == null) return null
    @Suppress("UNCHECKED_CAST")
    (value as? List<Map<String, Any>>)?.let { return it }
    val str = value.toString().trim()
    if (str.startsWith("[")) {
        return try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val array = json.parseToJsonElement(str) as kotlinx.serialization.json.JsonArray
            array.mapNotNull { element ->
                (element as? kotlinx.serialization.json.JsonObject)?.let { obj ->
                    obj.mapValues { (_, v) ->
                        when (v) {
                            is kotlinx.serialization.json.JsonPrimitive -> v.content
                            is kotlinx.serialization.json.JsonArray -> v.toString()
                            else -> v.toString()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
    return null
}