package com.lin.hippyagent.core.agent.subagent

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult

class SpawnSubAgentTool(
    private val orchestrator: SubAgentOrchestrator
) : Tool() {
    override val definition: ToolDefinition
        get() = ToolDefinition(
            name = "spawn_subagent",
            description = "派生并发子任务，由当前智能体自身并行执行。每个子任务在独立的嵌套会话中运行，任务完成后会话自动销毁。返回父任务 ID，可用于后续查询状态和汇总结果。",
            parameters = mapOf(
                "tasks" to ToolParameter(
                    name = "tasks",
                    type = "string",
                    description = "JSON 数组，每项包含 prompt(任务描述)，可选 max_turns(最大轮数，默认20) 和 context(上下文信息对象)。示例: [{\"prompt\":\"研究竞品A\",\"context\":{\"goal\":\"市场分析\"}},{\"prompt\":\"分析用户反馈\",\"max_turns\":10}]",
                    required = true
                )
            )
        )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        return execute(ToolContext(), arguments)
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val tasksJson = getRequiredArgument(args, "tasks")

        val tasks = try {
            parseTasks(tasksJson)
        } catch (e: Exception) {
            return ToolResult(callId, false, error = "Invalid tasks JSON: ${e.message}")
        }

        if (tasks.isEmpty()) {
            return ToolResult(callId, false, error = "No tasks provided")
        }

        if (tasks.size > 3) {
            return ToolResult(callId, false, error = "Too many tasks (max 3 per batch), got ${tasks.size}. Split into multiple batches of 3.")
        }

        return try {
            val result = orchestrator.spawnMultiple(
                tasks = tasks,
                agentId = ctx.agentId,
                parentSessionId = ctx.sessionId
            )
            val summary = result.jobs.mapIndexed { i, job ->
                "  [${i + 1}] job=${job.id} prompt=${tasks[i].prompt.take(50)}"
            }.joinToString("\n")

            ToolResult(
                callId, true,
                output = "Spawned ${result.jobs.size} concurrent task(s). Parent job: ${result.parentJobId}\n$summary\n\nUse check_subagent_tasks or aggregate_subagent_results with parent_job_id=${result.parentJobId} to track progress.",
                forLLM = "spawned_subagents:${result.jobs.size}:parent_job_id=${result.parentJobId}"
            )
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to spawn sub-agents: ${e.message}")
        }
    }

    private fun parseTasks(tasksJson: String): List<SubAgentTask> {
        val jsonArray = org.json.JSONArray(tasksJson)
        return (0 until jsonArray.length()).map { i ->
            val obj = jsonArray.getJSONObject(i)
            val contextMap = if (obj.has("context")) {
                val ctxObj = obj.getJSONObject("context")
                (0 until ctxObj.length()).associate { key ->
                    val keys = ctxObj.keys().asSequence().toList()
                    keys[key] to ctxObj.getString(keys[key])
                }
            } else {
                emptyMap()
            }
            SubAgentTask(
                prompt = obj.getString("prompt"),
                maxTurns = obj.optInt("max_turns", 20),
                context = contextMap
            )
        }
    }
}

class CheckSubAgentTasksTool(
    private val orchestrator: SubAgentOrchestrator
) : Tool() {
    override val definition = ToolDefinition(
        name = "check_subagent_tasks",
        description = "查询子智能体任务状态。返回指定父任务下所有子任务的当前状态。",
        parameters = mapOf(
            "parent_job_id" to ToolParameter(
                name = "parent_job_id",
                type = "string",
                description = "父任务 ID（spawn_subagent 返回的 parent_job_id）",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val parentJobId = getRequiredArgument(arguments, "parent_job_id").toLongOrNull()
            ?: return ToolResult(callId, false, error = "parent_job_id must be a valid number")

        return try {
            val children = orchestrator.getChildrenStatus(parentJobId)
            if (children.isEmpty()) {
                return ToolResult(callId, true, output = "No child tasks found for parent_job_id=$parentJobId")
            }

            var active = 0
            var completed = 0
            var failed = 0
            val details = children.joinToString("\n") { job ->
                when (job.status) {
                    com.lin.hippyagent.core.task.HippyJobStatus.ACTIVE, com.lin.hippyagent.core.task.HippyJobStatus.WAITING -> { active++; "  ⟳" }
                    com.lin.hippyagent.core.task.HippyJobStatus.COMPLETED -> { completed++; "  ✓" }
                    com.lin.hippyagent.core.task.HippyJobStatus.FAILED -> { failed++; "  ✗" }
                    else -> "  ○"
                } + " job=${job.id} name=${job.name} status=${job.status}"
            }

            ToolResult(
                callId, true,
                output = "Child tasks for parent=$parentJobId: active=$active completed=$completed failed=$failed\n$details"
            )
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to check tasks: ${e.message}")
        }
    }
}

class AggregateSubAgentResultsTool(
    private val orchestrator: SubAgentOrchestrator,
    private val aggregator: SubAgentAggregator
) : Tool() {
    override val definition = ToolDefinition(
        name = "aggregate_subagent_results",
        description = "汇总子智能体任务结果。等待所有子任务完成后返回结构化汇总。如果子任务仍在执行，会等待直到完成或超时。",
        parameters = mapOf(
            "parent_job_id" to ToolParameter(
                name = "parent_job_id",
                type = "string",
                description = "父任务 ID（spawn_subagent 返回的 parent_job_id）",
                required = true
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        val parentJobId = getRequiredArgument(arguments, "parent_job_id").toLongOrNull()
            ?: return ToolResult(callId, false, error = "parent_job_id must be a valid number")

        return try {
            val result = orchestrator.awaitChildren(parentJobId)

            val childDetails = result.children.joinToString("\n") { child ->
                val icon = if (child.status == "completed") "✓" else "✗"
                "  $icon [${child.jobName}] id=${child.childId} → ${child.status}"
            }

            val timeoutWarning = if (result.timedOut) "\n⚠️ TIMEOUT: Some sub-agents are still running. Results are partial." else ""

            ToolResult(
                callId, true,
                output = "${result.summary}\n\n$childDetails$timeoutWarning",
                forLLM = "aggregated:total=${result.total}:completed=${result.completed}:failed=${result.failed}:timedOut=${result.timedOut}"
            )
        } catch (e: Exception) {
            ToolResult(callId, false, error = "Failed to aggregate results: ${e.message}")
        }
    }
}
