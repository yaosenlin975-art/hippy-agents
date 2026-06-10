package com.lin.hippyagent.core.agent.task

import com.lin.hippyagent.core.agent.AgentFactory
import com.lin.hippyagent.core.task.HippyJobContext
import com.lin.hippyagent.core.task.HippyJobHandler
import timber.log.Timber

/**
 * TaskExecutionEngine 提交的 step job 的消费者:
 * - 从 job.data 读取 taskId/stepId/agentId/description
 * - 取对应 Agent,复用 task.sessionId 调 processMessage
 * - 成功 → engine.markStepCompleted; 失败 → engine.markStepFailed
 *
 * 上下文标记通过 systemPromptSuffix 注入"[定时任务]"前缀,不改 Agent 签名。
 */
class TaskStepJobHandler(
    private val engine: TaskExecutionEngine,
    private val agentFactory: AgentFactory,
) : HippyJobHandler {

    override suspend fun execute(context: HippyJobContext): Map<String, Any> {
        val taskId = context.data["task_id"] as? String
            ?: throw IllegalArgumentException("task_id required")
        val stepId = context.data["step_id"] as? String
            ?: throw IllegalArgumentException("step_id required")
        val agentId = context.data["agent_id"] as? String
            ?: throw IllegalArgumentException("agent_id required")
        val description = context.data["description"] as? String ?: ""

        val task = engine.getTaskForWorker(taskId) ?: return mapOf(
            "status" to "skipped",
            "reason" to "task_not_found",
            "task_id" to taskId,
        )
        if (task.status.isTerminal) return mapOf(
            "status" to "skipped",
            "reason" to "task_terminal:${task.status}",
            "task_id" to taskId,
        )

        val sessionId = task.sessionId
            ?: "cron_task_${taskId}"

        val agent = agentFactory.getAgent(agentId)
            ?: throw IllegalArgumentException("Agent not found: $agentId")

        Timber.d("TaskStepJobHandler: taskId=$taskId stepId=$stepId agent=$agentId session=$sessionId")
        val result = agent.processMessage(
            sessionId = sessionId,
            channelId = CRON_CHANNEL,
            content = description,
            systemPromptSuffix = CRON_SUFFIX,
        )

        return if (result.isSuccess) {
            // Agent.processMessage 返回 Result<Unit>, 实际 LLM 响应经 channel 流走,
            // 这里不要把 Unit.toString()="kotlin.Unit" 写进 step.result.
            engine.markStepCompleted(taskId, stepId, result = null)
            mapOf("status" to "completed", "task_id" to taskId, "step_id" to stepId)
        } else {
            val err = result.exceptionOrNull()?.message ?: "unknown error"
            engine.markStepFailed(taskId, stepId, err)
            mapOf("status" to "failed", "task_id" to taskId, "step_id" to stepId, "error" to err)
        }
    }

    private companion object {
        const val CRON_CHANNEL = "cron"
        const val CRON_SUFFIX = "[定时任务] 此消息由定时任务触发;直接执行并报告结果,不要向用户追问。"
    }
}
