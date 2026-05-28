package com.lin.hippyagent.core.tools.builtin

import com.lin.hippyagent.core.cron.CronJob
import com.lin.hippyagent.core.cron.CronJobManager
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import kotlinx.serialization.json.Json

class CronTool(
    private val cronJobManager: CronJobManager
) : Tool() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val definition = ToolDefinition(
        name = "cron",
        description = "管理定时任务。支持创建、查看、删除定时任务，使用 cron 表达式调度 Agent 定期执行任务。",
        parameters = mapOf(
            "action" to ToolParameter(
                name = "action",
                type = "string",
                description = "操作类型: create(创建定时任务), list(查看所有定时任务), delete(删除定时任务)",
                required = true
            ),
            "name" to ToolParameter(
                name = "name",
                type = "string",
                description = "任务名称 (action=create 时必填)",
                required = false
            ),
            "schedule" to ToolParameter(
                name = "schedule",
                type = "string",
                description = "Cron 表达式，格式: 分 时 日 月 周 (如 '0 9 * * *' 表示每天9点, '30 14 * * *' 表示每天14:30)",
                required = false
            ),
            "query" to ToolParameter(
                name = "query",
                type = "string",
                description = "定时执行的任务内容/指令 (action=create 时必填)",
                required = false
            ),
            "job_id" to ToolParameter(
                name = "job_id",
                type = "string",
                description = "任务ID (action=delete 时必填)",
                required = false
            )
        ),
        isAndroidSpecific = true
    )

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val action = getRequiredArgument(args, "action")
        return when (action) {
            "create" -> createJob(ctx, args)
            "list" -> listJobs(ctx, args)
            "delete" -> deleteJob(args)
            else -> ToolResult(
                callId = args["callId"] as? String ?: "",
                success = false,
                error = "Unknown action: $action. Supported: create, list, delete"
            )
        }
    }

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        return execute(ToolContext(), arguments)
    }

    private suspend fun createJob(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val name = getRequiredArgument(args, "name")
        val schedule = getOptionalArgument(args, "schedule") ?: ""
        val query = getRequiredArgument(args, "query")

        if (schedule.isBlank()) {
            return ToolResult(
                callId = callId,
                success = false,
                error = "必须提供 schedule (cron 表达式), 格式: 分 时 日 月 周 (如 '0 9 * * *')"
            )
        }

        if (schedule.split(" ").size < 5) {
            return ToolResult(
                callId = callId,
                success = false,
                error = "Invalid cron expression: '$schedule'. Expected format: 分 时 日 月 周 (e.g. '0 9 * * *')"
            )
        }

        val job = CronJob(
            name = name,
            query = query,
            schedule = schedule,
            agentId = ctx.agentId,
            channelId = ctx.channel.ifBlank { "cron" }
        )

        cronJobManager.createJob(job)

        val createMsg = "定时任务已创建: [${job.id}] $name, 调度: $schedule, 指令: $query"
        return ToolResult(
            callId = callId,
            success = true,
            output = createMsg,
            forLLM = createMsg,
            forUser = "已创建定时任务「$name」，将在 $schedule 自动执行"
        )
    }

    private fun listJobs(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val allJobs = cronJobManager.getJobs()
        val jobs = if (ctx.agentId.isNotEmpty()) {
            allJobs.filter { it.agentId == ctx.agentId || it.agentId.isEmpty() }
        } else allJobs

        if (jobs.isEmpty()) {
            return ToolResult(
                callId = callId,
                success = true,
                output = "当前没有定时任务",
                forLLM = "当前没有定时任务",
                forUser = "暂无定时任务"
            )
        }

        val lines = jobs.map { job ->
            val status = if (job.enabled) "启用" else "禁用"
            "- [${job.id}] ${job.name} | 调度: ${job.schedule} | 状态: $status | 指令: ${job.query}"
        }

        val listMsg = "定时任务列表:\n${lines.joinToString("\n")}"
        return ToolResult(
            callId = callId,
            success = true,
            output = listMsg,
            forLLM = listMsg,
            forUser = "共 ${jobs.size} 个定时任务"
        )
    }

    private suspend fun deleteJob(args: Map<String, Any>): ToolResult {
        val callId = args["callId"] as? String ?: ""
        val jobId = getRequiredArgument(args, "job_id")

        val existing = cronJobManager.getJob(jobId)
        if (existing == null) {
            return ToolResult(
                callId = callId,
                success = false,
                error = "定时任务不存在: $jobId"
            )
        }

        cronJobManager.deleteJob(jobId)

        val deleteMsg = "定时任务已删除: [${existing.id}] ${existing.name}"
        return ToolResult(
            callId = callId,
            success = true,
            output = deleteMsg,
            forLLM = deleteMsg,
            forUser = "已删除定时任务「${existing.name}」"
        )
    }
}
